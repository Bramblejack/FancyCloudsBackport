package com.bramblejack.fancycloudsbackport.client;

import com.bramblejack.fancycloudsbackport.Config;
import com.bramblejack.fancycloudsbackport.FancyCloudsBackport;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL20;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Wraps the resource manager handed to the Fabulous transparency {@code PostChain}. When a resource pack ships a
 * 1.21-style {@code shaders/post/transparency.fsh}, the 1.20.1 {@code shaders/program/transparency.fsh} lookup is
 * answered with that shader translated to 1.20.1 conventions. If the pack has no such file, or its translation does not
 * compile on this GPU, everything falls through to vanilla untouched.
 */
public final class TransparencyResourceManager implements ResourceManager {
    private static final ResourceLocation VANILLA_FRAGMENT = ResourceLocation.fromNamespaceAndPath("minecraft", "shaders/program/transparency.fsh");
    private static final ResourceLocation PACK_FRAGMENT = ResourceLocation.fromNamespaceAndPath("minecraft", "shaders/post/transparency.fsh");

    private final ResourceManager delegate;

    private TransparencyResourceManager(ResourceManager delegate) {
        this.delegate = delegate;
    }

    public static ResourceManager wrapIfNeeded(ResourceManager original) {
        if (!Config.enabled() || !Config.usePackTransparencyShader() || IrisCompat.isShaderPackInUse()) {
            return original;
        }
        if (original.getResource(PACK_FRAGMENT).isEmpty()) {
            return original;
        }
        return new TransparencyResourceManager(original);
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
        try (var in = resource.open()) {
            source = ShaderSource.translatePostFragment(ShaderSource.decode(in.readAllBytes()));
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

    @Override
    public Set<String> getNamespaces() {
        return this.delegate.getNamespaces();
    }

    @Override
    public List<Resource> getResourceStack(ResourceLocation location) {
        return this.delegate.getResourceStack(location);
    }

    @Override
    public Map<ResourceLocation, Resource> listResources(String path, Predicate<ResourceLocation> filter) {
        return this.delegate.listResources(path, filter);
    }

    @Override
    public Map<ResourceLocation, List<Resource>> listResourceStacks(String path, Predicate<ResourceLocation> filter) {
        return this.delegate.listResourceStacks(path, filter);
    }

    @Override
    public Stream<PackResources> listPacks() {
        return this.delegate.listPacks();
    }
}
