package com.bramblejack.fancycloudsbackport.client;

import java.lang.reflect.Method;

/**
 * Oculus/Iris takes over cloud and transparency rendering while a shader pack is active, so this mod steps aside.
 * Uses reflection so Iris/Oculus stays an optional dependency.
 */
public final class IrisCompat {
    private static boolean initialized;
    private static Object api;
    private static Method isShaderPackInUse;

    private IrisCompat() {
    }

    public static boolean isShaderPackInUse() {
        if (!initialized) {
            initialized = true;
            try {
                Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                api = apiClass.getMethod("getInstance").invoke(null);
                isShaderPackInUse = apiClass.getMethod("isShaderPackInUse");
            } catch (Throwable ignored) {
                isShaderPackInUse = null;
            }
        }

        if (isShaderPackInUse == null) {
            return false;
        }
        try {
            return (boolean) isShaderPackInUse.invoke(api);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
