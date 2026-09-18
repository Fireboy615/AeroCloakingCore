package net.fireboy.aerocloakingcore.client;

import java.lang.reflect.Method;

/**
 * Tiny reflection bridge to Veil's currently selected ShaderProgram.
 *
 * <p>Veil is bundled transitively by Aeronautics/Sable at runtime, but it is
 * not guaranteed to be present as a direct compile dependency for this addon.
 * Keeping this bridge reflective lets the hot-air fixes talk to Veil uniforms
 * without making Aero Cloaking Core depend on Veil's Java API directly.</p>
 *
 * <p>Important: Veil's ShaderProgram#getUniformSafe method takes a
 * {@link CharSequence}, not a {@link String}. Reflection requires the exact
 * declared parameter type, so looking it up with String.class silently made
 * every custom Veil uniform appear unavailable. That caused the burner DITHER
 * path to fall back to alpha blending instead of using the same screen-space
 * discard mask as the sublevel hull.</p>
 */
public final class VeilUniformBridge {

    private static boolean initialized;
    private static Method getShaderMethod;

    private VeilUniformBridge() {
    }

    public static boolean setFloat(String uniformName, float value) {
        try {
            Object shader = currentShader();
            if (shader == null) {
                return false;
            }

            Object uniform = getUniform(shader, uniformName);

            if (uniform == null || !isValid(uniform)) {
                return false;
            }

            uniform.getClass()
                    .getMethod("setFloat", float.class)
                    .invoke(uniform, value);
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    public static boolean setVector4(
            String uniformName,
            float x,
            float y,
            float z,
            float w
    ) {
        try {
            Object shader = currentShader();
            if (shader == null) {
                return false;
            }

            Object uniform = getUniform(shader, uniformName);

            if (uniform == null || !isValid(uniform)) {
                return false;
            }

            uniform.getClass()
                    .getMethod(
                            "setVector",
                            float.class,
                            float.class,
                            float.class,
                            float.class
                    )
                    .invoke(uniform, x, y, z, w);
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static Object getUniform(
            Object shader,
            String uniformName
    ) throws ReflectiveOperationException {
        Method getter;

        try {
            /* Veil 1.21.x API: getUniformSafe(CharSequence). */
            getter = shader.getClass().getMethod(
                    "getUniformSafe",
                    CharSequence.class
            );
        } catch (NoSuchMethodException ignored) {
            /* Compatibility fallback for any Veil build exposing String. */
            try {
                getter = shader.getClass().getMethod(
                        "getUniformSafe",
                        String.class
                );
            } catch (NoSuchMethodException ignoredAgain) {
                getter = findCompatibleUniformGetter(shader.getClass());
            }
        }

        if (getter == null) {
            return null;
        }

        return getter.invoke(shader, uniformName);
    }

    private static Method findCompatibleUniformGetter(Class<?> shaderClass) {
        for (Method method : shaderClass.getMethods()) {
            if (!method.getName().equals("getUniformSafe")
                    || method.getParameterCount() != 1) {
                continue;
            }

            Class<?> parameterType = method.getParameterTypes()[0];
            if (parameterType.isAssignableFrom(String.class)) {
                return method;
            }
        }

        return null;
    }

    private static Object currentShader() throws ReflectiveOperationException {
        if (!initialized) {
            initialized = true;
            Class<?> renderSystem = Class.forName(
                    "foundry.veil.api.client.render.VeilRenderSystem"
            );
            getShaderMethod = renderSystem.getMethod("getShader");
        }

        if (getShaderMethod == null) {
            return null;
        }

        return getShaderMethod.invoke(null);
    }

    private static boolean isValid(Object uniform)
            throws ReflectiveOperationException {
        Object value = uniform.getClass().getMethod("isValid").invoke(uniform);
        return value instanceof Boolean bool && bool;
    }
}
