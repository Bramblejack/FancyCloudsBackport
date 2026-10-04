package com.bramblejack.fancycloudsbackport.client;

import com.bramblejack.fancycloudsbackport.Config;
import com.bramblejack.fancycloudsbackport.FancyCloudsBackport;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Port of the 1.21.6+ {@code CloudRenderer}. The cloud mesh is a list of three-byte face records in a texel buffer;
 * the vertex shader pulls position, direction and flags from it using {@code gl_VertexID}, so no vertex format is
 * involved. Drawing happens into whatever target the level renderer has bound, which is the clouds target of the
 * Fabulous transparency chain (picked up by the post shader) or the main target otherwise.
 */
public final class CloudRenderer extends SimplePreparableReloadListener<Optional<CloudRenderer.TextureData>> implements AutoCloseable {
    public static final CloudRenderer INSTANCE = new CloudRenderer();

    private static final ResourceLocation TEXTURE_LOCATION = ResourceLocation.withDefaultNamespace("textures/environment/clouds.png");
    private static final float CELL_SIZE_IN_BLOCKS = 12.0F;
    private static final float CELL_HEIGHT_IN_BLOCKS = 4.0F;
    private static final int TICKS_PER_CELL = 400;
    private static final float BLOCKS_PER_TICK = 0.030000001F;
    private static final int FLAG_INSIDE_FACE = 16;
    private static final int FLAG_USE_TOP_COLOR = 32;
    private static final int GL_LEQUAL = 515;

    private TextureData texture;
    private boolean needsRebuild = true;
    private int prevCellX = Integer.MIN_VALUE;
    private int prevCellZ = Integer.MIN_VALUE;
    private int prevSize = -1;
    private RelativeCameraPos prevRelativeCameraPos = RelativeCameraPos.INSIDE_CLOUDS;
    private CloudStatus prevType;
    private int quadCount;
    private ByteBuffer mesh;

    private CloudProgram program;
    private boolean programDirty = true;
    private int vertexArray;
    private int meshBuffer;
    private int meshTexture;

    private final CloudProgram.Frame frame = new CloudProgram.Frame();

    private CloudRenderer() {
    }

    @Override
    protected Optional<TextureData> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        try (InputStream in = resourceManager.open(TEXTURE_LOCATION); NativeImage image = NativeImage.read(in)) {
            int width = image.getWidth();
            int height = image.getHeight();
            long[] cells = new long[width * height];

            for (int z = 0; z < height; z++) {
                for (int x = 0; x < width; x++) {
                    int pixel = image.getPixelRGBA(x, z);
                    if (isCellEmpty(pixel)) {
                        continue;
                    }
                    boolean north = isCellEmpty(image.getPixelRGBA(x, Math.floorMod(z - 1, height)));
                    boolean east = isCellEmpty(image.getPixelRGBA(Math.floorMod(x + 1, width), z));
                    boolean south = isCellEmpty(image.getPixelRGBA(x, Math.floorMod(z + 1, height)));
                    boolean west = isCellEmpty(image.getPixelRGBA(Math.floorMod(x - 1, width), z));
                    cells[x + z * width] = packCellData(pixel, north, east, south, west);
                }
            }
            return Optional.of(new TextureData(cells, width, height));
        } catch (IOException e) {
            FancyCloudsBackport.LOGGER.error("Failed to load cloud texture", e);
            return Optional.empty();
        }
    }

    @Override
    protected void apply(Optional<TextureData> data, ResourceManager resourceManager, ProfilerFiller profiler) {
        this.texture = data.orElse(null);
        this.needsRebuild = true;
        this.programDirty = true;
    }

    private static boolean isCellEmpty(int abgr) {
        return (abgr >>> 24) < 10;
    }

    private static long packCellData(int color, boolean north, boolean east, boolean south, boolean west) {
        return ((long) color << 4) | (north ? 8 : 0) | (east ? 4 : 0) | (south ? 2 : 0) | (west ? 1 : 0);
    }

    private static boolean isNorthEmpty(long cell) {
        return (cell >> 3 & 1L) != 0L;
    }

    private static boolean isEastEmpty(long cell) {
        return (cell >> 2 & 1L) != 0L;
    }

    private static boolean isSouthEmpty(long cell) {
        return (cell >> 1 & 1L) != 0L;
    }

    private static boolean isWestEmpty(long cell) {
        return (cell & 1L) != 0L;
    }

    /** Cheap pre-check for the mixin: whether this renderer wants to take over cloud rendering right now. */
    public boolean wantsToRender() {
        return Config.enabled() && this.texture != null && !IrisCompat.isShaderPackInUse();
    }

    /**
     * Draws the clouds.
     *
     * @return {@code true} when clouds were handled (including "nothing to draw"), {@code false} when vanilla should render instead
     */
    public boolean render(PoseStack poseStack, Matrix4f projection, float partialTick, double camX, double camY, double camZ, long ticks, ClientLevel level) {
        TextureData tex = this.texture;
        if (tex == null) {
            return false;
        }

        float cloudHeight = level.effects().getCloudHeight();
        if (Float.isNaN(cloudHeight)) {
            return true;
        }
        if (!this.ensureProgram()) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        CloudStatus status = minecraft.options.getCloudsType();
        boolean fancy = status == CloudStatus.FANCY;

        int cloudDistanceBlocks = Config.cloudDistanceChunks() * 16;
        int size = Mth.ceil(cloudDistanceBlocks / CELL_SIZE_IN_BLOCKS);

        float relativeBottom = (float) (cloudHeight - camY);
        float relativeTop = relativeBottom + CELL_HEIGHT_IN_BLOCKS;
        RelativeCameraPos relativePos;
        if (relativeTop < 0.0F) {
            relativePos = RelativeCameraPos.ABOVE_CLOUDS;
        } else if (relativeBottom > 0.0F) {
            relativePos = RelativeCameraPos.BELOW_CLOUDS;
        } else {
            relativePos = RelativeCameraPos.INSIDE_CLOUDS;
        }

        float cloudTicks = (float) (ticks % ((long) tex.width * TICKS_PER_CELL)) + partialTick;
        double scrollX = camX + cloudTicks * BLOCKS_PER_TICK;
        double scrollZ = camZ + 3.96F;
        double textureBlocksX = tex.width * (double) CELL_SIZE_IN_BLOCKS;
        double textureBlocksZ = tex.height * (double) CELL_SIZE_IN_BLOCKS;
        scrollX -= Mth.floor(scrollX / textureBlocksX) * textureBlocksX;
        scrollZ -= Mth.floor(scrollZ / textureBlocksZ) * textureBlocksZ;
        int cellX = Mth.floor(scrollX / CELL_SIZE_IN_BLOCKS);
        int cellZ = Mth.floor(scrollZ / CELL_SIZE_IN_BLOCKS);
        float fractionX = (float) (scrollX - cellX * CELL_SIZE_IN_BLOCKS);
        float fractionZ = (float) (scrollZ - cellZ * CELL_SIZE_IN_BLOCKS);

        this.ensureGlObjects();

        if (this.needsRebuild
                || cellX != this.prevCellX
                || cellZ != this.prevCellZ
                || size != this.prevSize
                || relativePos != this.prevRelativeCameraPos
                || status != this.prevType) {
            this.needsRebuild = false;
            this.prevCellX = cellX;
            this.prevCellZ = cellZ;
            this.prevSize = size;
            this.prevRelativeCameraPos = relativePos;
            this.prevType = status;
            this.rebuildMesh(tex, relativePos, cellX, cellZ, fancy, size);
        }

        if (this.quadCount == 0) {
            return true;
        }

        FogRenderer.levelFogColor();
        this.fillFrame(minecraft, level, poseStack, projection, partialTick, cloudHeight, relativeBottom, fractionX, fractionZ, cloudDistanceBlocks);

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL_LEQUAL);
        RenderSystem.depthMask(true);
        if (fancy) {
            RenderSystem.enableCull();
        } else {
            RenderSystem.disableCull();
        }

        int textureUnit = GlStateManager._getActiveTexture() - GL13.GL_TEXTURE0;
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, this.meshTexture);
        GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_R8I, this.meshBuffer);

        this.program.bind(this.frame, textureUnit);

        GlStateManager._glBindVertexArray(this.vertexArray);
        RenderSystem.AutoStorageIndexBuffer indexBuffer = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        int indexCount = this.quadCount * 6;
        indexBuffer.bind(indexCount);
        GL11.glDrawElements(GL11.GL_TRIANGLES, indexCount, indexBuffer.type().asGLType, 0L);

        this.program.unbind();
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
        VertexBuffer.unbind();

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        return true;
    }

    private void fillFrame(Minecraft minecraft, ClientLevel level, PoseStack poseStack, Matrix4f projection, float partialTick,
                           float cloudHeight, float relativeBottom, float fractionX, float fractionZ, int cloudDistanceBlocks) {
        CloudProgram.Frame f = this.frame;
        f.modelView = poseStack.last().pose();
        f.projection = projection;

        Vec3 color = level.getCloudColor(partialTick);
        f.cloudColor[0] = (float) color.x;
        f.cloudColor[1] = (float) color.y;
        f.cloudColor[2] = (float) color.z;
        f.cloudColor[3] = 1.0F;
        f.cloudOffset[0] = -fractionX;
        f.cloudOffset[1] = relativeBottom;
        f.cloudOffset[2] = -fractionZ;
        f.cellSize[0] = CELL_SIZE_IN_BLOCKS;
        f.cellSize[1] = CELL_HEIGHT_IN_BLOCKS;
        f.cellSize[2] = CELL_SIZE_IN_BLOCKS;

        f.fogColor = RenderSystem.getShaderFogColor();
        f.fogEnvironmentalStart = RenderSystem.getShaderFogStart();
        f.fogEnvironmentalEnd = RenderSystem.getShaderFogEnd();
        float renderDistance = minecraft.options.getEffectiveRenderDistance() * 16.0F;
        f.fogRenderDistanceEnd = renderDistance;
        f.fogRenderDistanceStart = renderDistance - Mth.clamp(renderDistance / 10.0F, 4.0F, 64.0F);
        f.fogSkyEnd = renderDistance;
        f.fogCloudsEnd = cloudDistanceBlocks;

        f.screenWidth = minecraft.getWindow().getWidth();
        f.screenHeight = minecraft.getWindow().getHeight();
        f.glintAlpha = minecraft.options.glintStrength().get().floatValue();
        f.gameTime = (level.getGameTime() % 24000L + partialTick) / 24000.0F;
    }

    private boolean ensureProgram() {
        if (this.programDirty) {
            this.programDirty = false;
            this.closeProgram();
            try {
                this.program = CloudProgram.create(Minecraft.getInstance().getResourceManager());
            } catch (IOException | RuntimeException e) {
                FancyCloudsBackport.LOGGER.error("Could not build the cloud shader, falling back to vanilla clouds until the next resource reload", e);
            }
        }
        return this.program != null;
    }

    private void closeProgram() {
        if (this.program != null) {
            this.program.close();
            this.program = null;
        }
    }

    private void ensureGlObjects() {
        if (this.vertexArray == 0) {
            this.vertexArray = GlStateManager._glGenVertexArrays();
        }
        if (this.meshBuffer == 0) {
            this.meshBuffer = GL15.glGenBuffers();
        }
        if (this.meshTexture == 0) {
            this.meshTexture = GL11.glGenTextures();
        }
    }

    private void rebuildMesh(TextureData tex, RelativeCameraPos relativePos, int cellX, int cellZ, boolean fancy, int size) {
        if (this.mesh == null) {
            this.mesh = MemoryUtil.memAlloc(1 << 18);
        }
        this.mesh.clear();
        this.buildMesh(tex, relativePos, cellX, cellZ, fancy, size);
        this.mesh.flip();
        this.quadCount = this.mesh.remaining() / 3;

        if (this.quadCount > 0) {
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, this.meshBuffer);
            GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, this.mesh, GL15.GL_DYNAMIC_DRAW);
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
        }
    }

    private void buildMesh(TextureData tex, RelativeCameraPos relativePos, int cellX, int cellZ, boolean fancy, int size) {
        for (int ring = 0; ring <= 2 * size; ring++) {
            for (int x = -ring; x <= ring; x++) {
                int z = ring - Math.abs(x);
                if (z >= 0 && z <= size && x * x + z * z <= size * size) {
                    if (z != 0) {
                        this.tryBuildCell(tex, relativePos, cellX, cellZ, fancy, x, -z);
                    }
                    this.tryBuildCell(tex, relativePos, cellX, cellZ, fancy, x, z);
                }
            }
        }
    }

    private void tryBuildCell(TextureData tex, RelativeCameraPos relativePos, int cellX, int cellZ, boolean fancy, int x, int z) {
        int textureX = Math.floorMod(cellX + x, tex.width);
        int textureZ = Math.floorMod(cellZ + z, tex.height);
        long cell = tex.cells[textureX + textureZ * tex.width];
        if (cell == 0L) {
            return;
        }
        if (fancy) {
            this.buildExtrudedCell(relativePos, x, z, cell);
        } else {
            this.encodeFace(x, z, Direction.DOWN, FLAG_USE_TOP_COLOR);
        }
    }

    private void buildExtrudedCell(RelativeCameraPos relativePos, int x, int z, long cell) {
        if (relativePos != RelativeCameraPos.BELOW_CLOUDS) {
            this.encodeFace(x, z, Direction.UP, 0);
        }
        if (relativePos != RelativeCameraPos.ABOVE_CLOUDS) {
            this.encodeFace(x, z, Direction.DOWN, 0);
        }
        if (isNorthEmpty(cell) && z > 0) {
            this.encodeFace(x, z, Direction.NORTH, 0);
        }
        if (isSouthEmpty(cell) && z < 0) {
            this.encodeFace(x, z, Direction.SOUTH, 0);
        }
        if (isWestEmpty(cell) && x > 0) {
            this.encodeFace(x, z, Direction.WEST, 0);
        }
        if (isEastEmpty(cell) && x < 0) {
            this.encodeFace(x, z, Direction.EAST, 0);
        }

        if (Math.abs(x) <= 1 && Math.abs(z) <= 1) {
            for (Direction direction : Direction.values()) {
                this.encodeFace(x, z, direction, FLAG_INSIDE_FACE);
            }
        }
    }

    private void encodeFace(int x, int z, Direction face, int flags) {
        if (this.mesh.remaining() < 3) {
            this.growMesh();
        }
        int packed = face.get3DDataValue() | flags;
        packed |= (x & 1) << 7;
        packed |= (z & 1) << 6;
        this.mesh.put((byte) (x >> 1)).put((byte) (z >> 1)).put((byte) packed);
    }

    private void growMesh() {
        int position = this.mesh.position();
        this.mesh = MemoryUtil.memRealloc(this.mesh, this.mesh.capacity() * 2);
        this.mesh.clear();
        this.mesh.position(position);
    }

    @Override
    public void close() {
        this.closeProgram();
        if (this.mesh != null) {
            MemoryUtil.memFree(this.mesh);
            this.mesh = null;
        }
        if (this.meshBuffer != 0) {
            GL15.glDeleteBuffers(this.meshBuffer);
            this.meshBuffer = 0;
        }
        if (this.meshTexture != 0) {
            GL11.glDeleteTextures(this.meshTexture);
            this.meshTexture = 0;
        }
    }

    private enum RelativeCameraPos {
        ABOVE_CLOUDS,
        INSIDE_CLOUDS,
        BELOW_CLOUDS
    }

    public record TextureData(long[] cells, int width, int height) {
    }
}
