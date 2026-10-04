package com.bramblejack.fancycloudsbackport.client;

import com.bramblejack.fancycloudsbackport.Config;
import com.bramblejack.fancycloudsbackport.FancyCloudsBackport;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.lwjgl.opengl.GL20;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Wraps the resource provider handed to the Fabulous transparency {@code PostChain}. When a resource pack ships a
 * 1.21.6+-style {@code shaders/post/transparency.fsh}, the {@code shaders/program/transparency.fsh} lookup is
 * answered with that shader translated to this version's conventions. If the pack has no such file, or its translation
 * does not compile on this GPU, everything falls through to vanilla untouched.
 */
public final class TransparencyResourceProvider implements ResourceProvider {
    private static final ResourceLocation VANILLA_FRAGMENT = ResourceLocation.withDefaultNamespace("shaders/program/transparency.fsh");
    private static final ResourceLocation PACK_FRAGMENT = ResourceLocation.withDefaultNamespace("shaders/post/transparency.fsh");

    private final ResourceProvider delegate;

    private TransparencyResourceProvider(ResourceProvider delegate) {
        this.delegate = delegate;
    }

    public static ResourceProvider wrapIfNeeded(ResourceProvider original) {
        if (!Config.enabled() || !Config.usePackTransparencyShader() || IrisCompat.isShaderPackInUse()) {
            return original;
        }
        if (original.getResource(PACK_FRAGMENT).isEmpty()) {
            return original;
        }
        return new TransparencyResourceProvider(original);
    }

    @Override
    public Optional<Resource> getResource(ResourceLocation location) {
        if (location.equals(VANILLA_FRAGMENT)) {
            Optional<Resource> translated = this.translatedPackShader();
            if (translated.isPresent()) {
                return translated;
            }
        }
        return this.delegate.getResource(location);
    }

    private Optional<Resource> translatedPackShader() {
        Optional<Resource> packShader = this.delegate.getResource(PACK_FRAGMENT);
        if (packShader.isEmpty()) {
            return Optional.empty();
        }

        Resource resource = packShader.get();
        String source;
        try {
            source = ShaderSource.translatePostFragment(ShaderSource.read(resource));
        } catch (IOException e) {
            FancyCloudsBackport.LOGGER.warn("Could not read {} from pack '{}', using vanilla transparency shader", PACK_FRAGMENT, resource.sourcePackId(), e);
            return Optional.empty();
        }

        String label = PACK_FRAGMENT + " (" + resource.sourcePackId() + ")";
        if (!ShaderSource.compiles(GL20.GL_FRAGMENT_SHADER, source, label)) {
            FancyCloudsBackport.LOGGER.warn("Using vanilla transparency shader instead of {}", label);
            return Optional.empty();
        }

        FancyCloudsBackport.LOGGER.info("Using transparency shader from pack '{}'", resource.sourcePackId());
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        return Optional.of(new Resource(resource.source(), () -> new ByteArrayInputStream(bytes)));
    }
}
