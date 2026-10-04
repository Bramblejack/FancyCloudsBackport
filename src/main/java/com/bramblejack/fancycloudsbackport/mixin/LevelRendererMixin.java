package com.bramblejack.fancycloudsbackport.mixin;

import com.bramblejack.fancycloudsbackport.client.CloudRenderer;
import com.bramblejack.fancycloudsbackport.client.TransparencyResourceProvider;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Priority below the default so this runs before other mods that also cancel cloud rendering at HEAD (e.g. Sodium).
@Mixin(value = LevelRenderer.class, priority = 900)
public abstract class LevelRendererMixin {
    @Shadow
    private ClientLevel level;

    @Shadow
    private int ticks;

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void fancycloudsbackport$renderClouds(PoseStack poseStack, Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick, double camX, double camY, double camZ, CallbackInfo ci) {
        CloudRenderer renderer = CloudRenderer.INSTANCE;
        if (!renderer.wantsToRender()) {
            return;
        }

        // Same hook vanilla (NeoForge) calls first, so dimensions with their own cloud renderer keep working.
        if (this.level.effects().renderClouds(this.level, this.ticks, partialTick, poseStack, camX, camY, camZ, frustumMatrix, projectionMatrix)) {
            ci.cancel();
            return;
        }

        if (renderer.render(poseStack, frustumMatrix, projectionMatrix, partialTick, camX, camY, camZ, this.ticks, this.level)) {
            ci.cancel();
        }
    }

    @ModifyArg(
            method = "initTransparency",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/PostChain;<init>(Lnet/minecraft/client/renderer/texture/TextureManager;Lnet/minecraft/server/packs/resources/ResourceProvider;Lcom/mojang/blaze3d/pipeline/RenderTarget;Lnet/minecraft/resources/ResourceLocation;)V"
            ),
            index = 1,
            require = 0
    )
    private ResourceProvider fancycloudsbackport$wrapTransparencyResources(ResourceProvider original) {
        return TransparencyResourceProvider.wrapIfNeeded(original);
    }
}
