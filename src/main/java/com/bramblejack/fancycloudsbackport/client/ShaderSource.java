package com.bramblejack.fancycloudsbackport.client;

import com.bramblejack.fancycloudsbackport.FancyCloudsBackport;
import net.minecraft.ResourceLocationException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL20;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads GLSL source from the resource manager and resolves {@code #moj_import} directives the way 1.21 does,
 * including namespaced imports. Includes that changed format between 1.20.1 and 1.21 (fog, dynamic transforms,
 * projection, globals) are always served from this mod's compat copies.
 */
final class ShaderSource {
    private static final Pattern IMPORT = Pattern.compile("^\\s*#moj_import\\s+[<\"]([^>\"]+)[>\"]\\s*$");
    private static final Pattern VERSION = Pattern.compile("^(\\s*#version\\s+)(\\d+)(.*)$", Pattern.MULTILINE);
    private static final Set<String> COMPAT_INCLUDES = Set.of("fog.glsl", "dynamictransforms.glsl", "projection.glsl", "globals.glsl");
    private static final int MAX_IMPORT_DEPTH = 16;

    private ShaderSource() {
    }

    static String load(ResourceManager resourceManager, ResourceLocation location) throws IOException {
        return expand(resourceManager, read(resourceManager, location), new HashSet<>(), 0);
    }

    static String read(ResourceManager resourceManager, ResourceLocation location) throws IOException {
        Resource resource = resourceManager.getResourceOrThrow(location);
        try (InputStream in = resource.open()) {
            return decode(in.readAllBytes());
        }
    }

    static String decode(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static String expand(ResourceManager resourceManager, String source, Set<String> imported, int depth) throws IOException {
        if (depth > MAX_IMPORT_DEPTH) {
            throw new IOException("#moj_import nesting too deep");
        }

        StringBuilder out = new StringBuilder(source.length() + 256);
        for (String line : source.split("\\R", -1)) {
            Matcher matcher = IMPORT.matcher(line);
            if (matcher.matches()) {
                ResourceLocation include = includeLocation(matcher.group(1).trim());
                if (imported.add(include.toString())) {
                    out.append(expand(resourceManager, read(resourceManager, include), imported, depth + 1)).append('\n');
                }
            } else {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static ResourceLocation includeLocation(String spec) throws IOException {
        ResourceLocation location;
        try {
            location = ResourceLocation.parse(spec);
        } catch (ResourceLocationException e) {
            throw new IOException("Invalid #moj_import target '" + spec + "'", e);
        }

        if (location.getNamespace().equals("minecraft") && COMPAT_INCLUDES.contains(location.getPath())) {
            return ResourceLocation.fromNamespaceAndPath(FancyCloudsBackport.MOD_ID, "shaders/compat/" + location.getPath());
        }
        return ResourceLocation.fromNamespaceAndPath(location.getNamespace(), "shaders/include/" + location.getPath());
    }

    /** Rewrites the {@code #version} number of a shader, leaving it untouched when there is no such directive. */
    static String withVersion(String source, int version) {
        Matcher matcher = VERSION.matcher(source);
        if (!matcher.find()) {
            return source;
        }
        return matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1)) + version + Matcher.quoteReplacement(matcher.group(3)));
    }

    /**
     * Translates a 1.21-style post-effect fragment shader (resource pack path {@code shaders/post/transparency.fsh})
     * to the 1.20.1 post-chain conventions: GLSL 150 and the {@code Diffuse*} sampler names of the main target.
     */
    static String translatePostFragment(String source) {
        return withVersion(source, 150)
                .replaceAll("\\bMainDepthSampler\\b", "DiffuseDepthSampler")
                .replaceAll("\\bMainSampler\\b", "DiffuseSampler");
    }

    /** Trial-compiles a shader on the current GL context, logging the driver output on failure. */
    static boolean compiles(int type, String source, String label) {
        int shader = GL20.glCreateShader(type);
        try {
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != 0) {
                return true;
            }
            FancyCloudsBackport.LOGGER.warn("Shader '{}' failed to compile:\n{}", label, GL20.glGetShaderInfoLog(shader, 8192));
            return false;
        } finally {
            GL20.glDeleteShader(shader);
        }
    }
}
