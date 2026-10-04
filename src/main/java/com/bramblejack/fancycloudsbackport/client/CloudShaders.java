package com.bramblejack.fancycloudsbackport.client;

import com.bramblejack.fancycloudsbackport.FancyCloudsBackport;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * 1.21.1 still has a vanilla {@code rendertype_clouds} shader program (the old cloud renderer's), living at the same
 * path the 1.21.6+ cloud shaders use. Resource packs written for the new renderer therefore replace the files the
 * vanilla program is built from, which breaks it. This class tells the two generations of shader apart so each side only
 * ever sees its own.
 */
final class CloudShaders {
    static final ResourceLocation VERTEX = ResourceLocation.withDefaultNamespace("shaders/core/rendertype_clouds.vsh");
    static final ResourceLocation FRAGMENT = ResourceLocation.withDefaultNamespace("shaders/core/rendertype_clouds.fsh");
    static final ResourceLocation DEFAULT_VERTEX = ResourceLocation.fromNamespaceAndPath(FancyCloudsBackport.MOD_ID, "shaders/core/rendertype_clouds.vsh");
    static final ResourceLocation DEFAULT_FRAGMENT = ResourceLocation.fromNamespaceAndPath(FancyCloudsBackport.MOD_ID, "shaders/core/rendertype_clouds.fsh");

    private static final String[] NEW_STYLE_MARKERS = {"CloudFaces", "CloudInfo", "FogCloudsEnd", "#moj_import <minecraft:"};

    private CloudShaders() {
    }

    static boolean isCloudShader(ResourceLocation location) {
        return location.equals(VERTEX) || location.equals(FRAGMENT);
    }

    static boolean looksNewStyle(String source) {
        for (String marker : NEW_STYLE_MARKERS) {
            if (source.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksNewStyle(Resource resource) {
        try {
            return looksNewStyle(ShaderSource.read(resource));
        } catch (IOException e) {
            return false;
        }
    }

    /** Source of the highest-priority new-style shader at {@code location}, if any pack provides one. */
    static Optional<String> findNewStyleSource(ResourceManager resources, ResourceLocation location) throws IOException {
        List<Resource> stack = resources.getResourceStack(location);
        for (int i = stack.size() - 1; i >= 0; i--) {
            String source = ShaderSource.read(stack.get(i));
            if (looksNewStyle(source)) {
                return Optional.of(source);
            }
        }
        return Optional.empty();
    }

    /** The highest-priority resource at {@code location} that is not a new-style shader, i.e. what vanilla's own program should read. */
    static Optional<Resource> findVanillaStyle(ResourceManager resources, ResourceLocation location) {
        List<Resource> stack = resources.getResourceStack(location);
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (!looksNewStyle(stack.get(i))) {
                return Optional.of(stack.get(i));
            }
        }
        return Optional.empty();
    }
}
