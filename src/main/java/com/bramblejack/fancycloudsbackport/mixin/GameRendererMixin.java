package com.bramblejack.fancycloudsbackport.mixin;

import com.bramblejack.fancycloudsbackport.client.VanillaCloudShaderProvider;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    /**
     * Keeps vanilla's own cloud shader program reading old-style shader files even when a resource pack ships
     * 1.21.6+ cloud shaders at the same path, which would otherwise fail to build and break resource reloading.
     */
    @ModifyVariable(method = "reloadShaders", at = @At("HEAD"), argsOnly = true)
    private ResourceProvider fancycloudsbackport$isolateCloudShaders(ResourceProvider original) {
        return VanillaCloudShaderProvider.wrap(original);
    }
}
