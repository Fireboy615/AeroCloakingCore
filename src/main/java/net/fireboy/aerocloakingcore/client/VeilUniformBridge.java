package net.fireboy.aerocloakingcore.client;

import java.lang.reflect.Method;

/**
 * Tiny reflection bridge to Veil's currently selected ShaderProgram.
 *
 * <p>Veil is bundled transitively by Aeronautics/Sable at runtime, but it is
 * not guaranteed to be present as a direct compile dependency for this addon.
 * Keeping this bridge reflective lets the hot-air fixes talk to Veil uniforms
 * without making Aero Cloaking Core depend on Veil's Java API directly.</p>
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

            Object uniform = shader.getClass()
                    .getMethod("getUniformSafe", String.class)
                    .invoke(shader, uniformName);

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

            Object uniform = shader.getClass()
                    .getMethod("getUniformSafe", String.class)
                    .invoke(shader, uniformName);

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
