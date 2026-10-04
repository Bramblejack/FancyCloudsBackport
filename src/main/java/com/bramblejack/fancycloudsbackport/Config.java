package com.bramblejack.fancycloudsbackport;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Render clouds with the 1.21.6+ cloud renderer. When disabled, vanilla 1.21.1 clouds are used.")
            .define("enabled", true);

    private static final ModConfigSpec.IntValue CLOUD_DISTANCE_CHUNKS = BUILDER
            .comment("Cloud render distance in chunks (the Cloud Distance option of 1.21.5+). Also used as the cloud fog end.")
            .defineInRange("cloudDistanceChunks", 64, 4, 128);

    private static final ModConfigSpec.BooleanValue USE_PACK_TRANSPARENCY_SHADER = BUILDER
            .comment("Use the post/transparency.fsh shader of a 1.21-style resource pack for the Fabulous transparency composite.",
                    "Only has an effect when a resource pack actually provides that file and Fabulous graphics are on.")
            .define("usePackTransparencyShader", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    public static boolean enabled() {
        try {
            return ENABLED.get();
        } catch (IllegalStateException e) {
            return ENABLED.getDefault();
        }
    }

    public static int cloudDistanceChunks() {
        try {
            return CLOUD_DISTANCE_CHUNKS.get();
        } catch (IllegalStateException e) {
            return CLOUD_DISTANCE_CHUNKS.getDefault();
        }
    }

    public static boolean usePackTransparencyShader() {
        try {
            return USE_PACK_TRANSPARENCY_SHADER.get();
        } catch (IllegalStateException e) {
            return USE_PACK_TRANSPARENCY_SHADER.getDefault();
        }
    }
}
