package net.fireboy.aerocloakingcore.client;

/**
 * Temporary render-thread state used while a single cloaked entity's
 * buffered geometry is being flushed.
 */
public final class EntityCloakRenderState {

    private static final ThreadLocal<Float> ACTIVE_CLOAK_STRENGTH =
            ThreadLocal.withInitial(() -> 0.0F);

    private static final ThreadLocal<CloakRenderMode> ACTIVE_RENDER_MODE =
            ThreadLocal.withInitial(() -> CloakRenderMode.DITHER);

    private EntityCloakRenderState() {
    }

    public static void begin(
            float cloakStrength,
            CloakRenderMode renderMode
    ) {
        ACTIVE_CLOAK_STRENGTH.set(clamp(cloakStrength));
        ACTIVE_RENDER_MODE.set(
                renderMode != null
                        ? renderMode
                        : CloakRenderMode.DITHER
        );
    }

    /**
     * Retained as a safe fallback for any older call sites.
     */
    public static void begin(float cloakStrength) {
        begin(cloakStrength, CloakRenderMode.DITHER);
    }

    public static void end() {
        ACTIVE_CLOAK_STRENGTH.set(0.0F);
        ACTIVE_RENDER_MODE.set(CloakRenderMode.DITHER);
    }

    public static float getCloakStrength() {
        return ACTIVE_CLOAK_STRENGTH.get();
    }

    public static float getAlpha() {
        return 1.0F - getCloakStrength();
    }

    public static CloakRenderMode getRenderMode() {
        return ACTIVE_RENDER_MODE.get();
    }

    public static boolean isActive() {
        return getCloakStrength() > 0.001F;
    }

    private static float clamp(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}
