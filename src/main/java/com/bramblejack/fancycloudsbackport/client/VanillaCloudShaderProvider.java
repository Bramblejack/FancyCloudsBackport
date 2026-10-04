package com.bramblejack.fancycloudsbackport.client;

import com.bramblejack.fancycloudsbackport.FancyCloudsBackport;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.util.Optional;

/**
 * Handed to {@code GameRenderer.reloadShaders} so vanilla's own {@code rendertype_clouds} program keeps reading
 * old-style shader files even when a resource pack replaces them with 1.21.6+ cloud shaders (which only this mod can run).
 */
public final class VanillaCloudShaderProvider implements ResourceProvider {
    private final ResourceProvider delegate;
    private final ResourceManager manager;

    private VanillaCloudShaderProvider(ResourceProvider delegate, ResourceManager manager) {
        this.delegate = delegate;
        this.manager = manager;
    }

    public static ResourceProvider wrap(ResourceProvider original) {
        ResourceManager manager = null;
        if (original instanceof ResourceManager direct) {
            manager = direct;
        } else if (original instanceof GameRenderer.ResourceCache cache && cache.original() instanceof ResourceManager underlying) {
            // Vanilla hands reloadShaders a cache of the topmost shader files, which hides the pack stack; the manager behind it still has it.
            manager = underlying;
        }

        if (manager == null) {
            FancyCloudsBackport.LOGGER.warn("Unexpected shader resource provider {}, cannot shield vanilla's cloud shader from resource pack overrides", original.getClass().getName());
            return original;
        }
        return new VanillaCloudShaderProvider(original, manager);
    }

    @Override
    public Optional<Resource> getResource(ResourceLocation location) {
        if (CloudShaders.isCloudShader(location)) {
            Optional<Resource> vanillaStyle = CloudShaders.findVanillaStyle(this.manager, location);
            if (vanillaStyle.isPresent()) {
                return vanillaStyle;
            }
        }
        return this.delegate.getResource(location);
    }
}
