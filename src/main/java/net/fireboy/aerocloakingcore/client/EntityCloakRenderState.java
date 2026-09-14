package net.fireboy.aerocloakingcore.client;

/**
 * Temporary render-thread state used while a single cloaked entity's
 * buffered geometry is being flushed.
 */
public final class EntityCloakRenderState {

    private static final ThreadLocal<Float> ACTIVE_CLOAK_STRENGTH =
            ThreadLocal.withInitial(() -> 0.0F);

    private EntityCloakRenderState() {
    }

    public static void begin(float cloakStrength) {
        ACTIVE_CLOAK_STRENGTH.set(clamp(cloakStrength));
    }

    public static void end() {
        ACTIVE_CLOAK_STRENGTH.set(0.0F);
    }

    public static float getCloakStrength() {
        return ACTIVE_CLOAK_STRENGTH.get();
    }

    public static float getAlpha() {
        return 1.0F - getCloakStrength();
    }

    public static boolean isActive() {
        return getCloakStrength() > 0.001F;
    }

    private static float clamp(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}
