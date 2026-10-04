package com.bramblejack.fancycloudsbackport.client;

import com.bramblejack.fancycloudsbackport.FancyCloudsBackport;
import com.mojang.blaze3d.shaders.ProgramManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The cloud shader program, compiled from {@code minecraft:shaders/core/rendertype_clouds.{vsh,fsh}} so that resource
 * packs override it exactly like in 1.21. 1.20.1 has no uniform buffer support in its shader classes, so the std140
 * uniform blocks the 1.21 shaders declare (CloudInfo, Fog, DynamicTransforms, Projection, Globals) are discovered after
 * linking and filled here every frame.
 */
final class CloudProgram implements AutoCloseable {
    private static final ResourceLocation VERTEX_SHADER = ResourceLocation.fromNamespaceAndPath("minecraft", "shaders/core/rendertype_clouds.vsh");
    private static final ResourceLocation FRAGMENT_SHADER = ResourceLocation.fromNamespaceAndPath("minecraft", "shaders/core/rendertype_clouds.fsh");
    private static final int UBO_BINDING_BASE = 24;
    private static final int FALLBACK_GLSL_VERSION = 150;

    /** Everything the 1.21 uniform blocks can ask for in one frame. */
    static final class Frame {
        Matrix4f modelView = new Matrix4f();
        Matrix4f projection = new Matrix4f();
        final float[] cloudColor = new float[4];
        final float[] cloudOffset = new float[3];
        final float[] cellSize = new float[3];
        float[] fogColor = {1.0F, 1.0F, 1.0F, 1.0F};
        float fogEnvironmentalStart;
        float fogEnvironmentalEnd;
        float fogRenderDistanceStart;
        float fogRenderDistanceEnd;
        float fogSkyEnd;
        float fogCloudsEnd;
        float screenWidth;
        float screenHeight;
        float glintAlpha;
        float gameTime;
    }

    private final int program;
    private final int facesLocation;
    private final List<UniformBlock> blocks = new ArrayList<>();

    private CloudProgram(int program) {
        this.program = program;
        this.facesLocation = GL20.glGetUniformLocation(program, "CloudFaces");
        if (this.facesLocation < 0) {
            FancyCloudsBackport.LOGGER.warn("Cloud shader has no active 'CloudFaces' sampler; clouds will not render correctly");
        }
        this.discoverUniformBlocks();
    }

    static CloudProgram create(ResourceManager resourceManager) throws IOException {
        String vertex = ShaderSource.load(resourceManager, VERTEX_SHADER);
        String fragment = ShaderSource.load(resourceManager, FRAGMENT_SHADER);

        int id;
        try {
            id = link(vertex, fragment);
        } catch (IOException first) {
            String vertexFallback = ShaderSource.withVersion(vertex, FALLBACK_GLSL_VERSION);
            String fragmentFallback = ShaderSource.withVersion(fragment, FALLBACK_GLSL_VERSION);
            if (vertexFallback.equals(vertex) && fragmentFallback.equals(fragment)) {
                throw first;
            }
            FancyCloudsBackport.LOGGER.warn("Cloud shader failed to build as written, retrying as GLSL {}: {}", FALLBACK_GLSL_VERSION, first.getMessage());
            try {
                id = link(vertexFallback, fragmentFallback);
            } catch (IOException second) {
                second.addSuppressed(first);
                throw second;
            }
        }
        return new CloudProgram(id);
    }

    private static int link(String vertexSource, String fragmentSource) throws IOException {
        int vertex = compile(GL20.GL_VERTEX_SHADER, vertexSource, "vertex");
        int fragment;
        try {
            fragment = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource, "fragment");
        } catch (IOException e) {
            GL20.glDeleteShader(vertex);
            throw e;
        }

        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertex);
        GL20.glAttachShader(program, fragment);
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);

        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            String log = GL20.glGetProgramInfoLog(program, 8192);
            GL20.glDeleteProgram(program);
            throw new IOException("Cloud shader failed to link: " + log);
        }
        return program;
    }

    private static int compile(int type, String source, String label) throws IOException {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(shader, 8192);
            GL20.glDeleteShader(shader);
            throw new IOException("Cloud " + label + " shader failed to compile: " + log);
        }
        return shader;
    }

    private void discoverUniformBlocks() {
        int blockCount = GL20.glGetProgrami(this.program, GL31.GL_ACTIVE_UNIFORM_BLOCKS);
        for (int i = 0; i < blockCount; i++) {
            String name = GL31.glGetActiveUniformBlockName(this.program, i);
            int size = GL31.glGetActiveUniformBlocki(this.program, i, GL31.GL_UNIFORM_BLOCK_DATA_SIZE);
            int binding = UBO_BINDING_BASE + i;
            GL31.glUniformBlockBinding(this.program, i, binding);
            this.blocks.add(new UniformBlock(name, size, binding));
        }

        int uniformCount = GL20.glGetProgrami(this.program, GL20.GL_ACTIVE_UNIFORMS);
        for (int u = 0; u < uniformCount; u++) {
            int blockIndex = GL31.glGetActiveUniformsi(this.program, u, GL31.GL_UNIFORM_BLOCK_INDEX);
            if (blockIndex < 0 || blockIndex >= this.blocks.size()) {
                continue;
            }
            String name = GL31.glGetActiveUniformName(this.program, u, 256);
            int offset = GL31.glGetActiveUniformsi(this.program, u, GL31.GL_UNIFORM_OFFSET);
            this.blocks.get(blockIndex).offsets.put(name, offset);
        }
    }

    /** Makes the program current, uploads and binds every uniform block and points the faces sampler at {@code textureUnit}. */
    void bind(Frame frame, int textureUnit) {
        ProgramManager.glUseProgram(this.program);
        if (this.facesLocation >= 0) {
            GL20.glUniform1i(this.facesLocation, textureUnit);
        }
        for (UniformBlock block : this.blocks) {
            block.clear();
            this.fill(block, frame);
            block.upload();
        }
    }

    void unbind() {
        for (UniformBlock block : this.blocks) {
            block.unbind();
        }
        ProgramManager.glUseProgram(0);
    }

    private void fill(UniformBlock block, Frame f) {
        switch (block.name) {
            case "CloudInfo" -> {
                block.putVec4("CloudColor", f.cloudColor);
                block.putVec3("CloudOffset", f.cloudOffset);
                block.putVec3("CellSize", f.cellSize);
            }
            case "Fog" -> {
                block.putVec4("FogColor", f.fogColor);
                block.putFloat("FogEnvironmentalStart", f.fogEnvironmentalStart);
                block.putFloat("FogEnvironmentalEnd", f.fogEnvironmentalEnd);
                block.putFloat("FogRenderDistanceStart", f.fogRenderDistanceStart);
                block.putFloat("FogRenderDistanceEnd", f.fogRenderDistanceEnd);
                block.putFloat("FogSkyEnd", f.fogSkyEnd);
                block.putFloat("FogCloudsEnd", f.fogCloudsEnd);
            }
            case "DynamicTransforms" -> {
                block.putMat4("ModelViewMat", f.modelView);
                block.putVec4("ColorModulator", 1.0F, 1.0F, 1.0F, 1.0F);
                block.putVec3("ModelOffset", 0.0F, 0.0F, 0.0F);
                block.putMat4("TextureMat", new Matrix4f());
            }
            case "Projection" -> block.putMat4("ProjMat", f.projection);
            case "Globals" -> {
                block.putVec2("ScreenSize", f.screenWidth, f.screenHeight);
                block.putFloat("GlintAlpha", f.glintAlpha);
                block.putFloat("GameTime", f.gameTime);
                block.putInt("MenuBlurRadius", 0);
            }
            default -> {
            }
        }
    }

    @Override
    public void close() {
        for (UniformBlock block : this.blocks) {
            block.close();
        }
        this.blocks.clear();
        GL20.glDeleteProgram(this.program);
    }

    private static final class UniformBlock implements AutoCloseable {
        final String name;
        final int binding;
        final int buffer;
        final ByteBuffer data;
        final Map<String, Integer> offsets = new HashMap<>();

        UniformBlock(String name, int size, int binding) {
            this.name = name;
            this.binding = binding;
            this.buffer = GL15.glGenBuffers();
            this.data = MemoryUtil.memCalloc(Math.max(size, 16));
        }

        void clear() {
            MemoryUtil.memSet(this.data, 0);
        }

        void putFloat(String member, float value) {
            Integer offset = this.offsets.get(member);
            if (offset != null) {
                this.data.putFloat(offset, value);
            }
        }

        void putInt(String member, int value) {
            Integer offset = this.offsets.get(member);
            if (offset != null) {
                this.data.putInt(offset, value);
            }
        }

        void putVec2(String member, float x, float y) {
            Integer offset = this.offsets.get(member);
            if (offset != null) {
                this.data.putFloat(offset, x).putFloat(offset + 4, y);
            }
        }

        void putVec3(String member, float... v) {
            Integer offset = this.offsets.get(member);
            if (offset != null) {
                this.data.putFloat(offset, v[0]).putFloat(offset + 4, v[1]).putFloat(offset + 8, v[2]);
            }
        }

        void putVec4(String member, float... v) {
            Integer offset = this.offsets.get(member);
            if (offset != null) {
                this.data.putFloat(offset, v[0]).putFloat(offset + 4, v[1]).putFloat(offset + 8, v[2]).putFloat(offset + 12, v[3]);
            }
        }

        void putMat4(String member, Matrix4f matrix) {
            Integer offset = this.offsets.get(member);
            if (offset != null) {
                matrix.get(offset, this.data);
            }
        }

        void upload() {
            this.data.clear();
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, this.buffer);
            GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, this.data, GL15.GL_DYNAMIC_DRAW);
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, this.binding, this.buffer);
        }

        void unbind() {
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, this.binding, 0);
        }

        @Override
        public void close() {
            GL15.glDeleteBuffers(this.buffer);
            MemoryUtil.memFree(this.data);
        }
    }
}
