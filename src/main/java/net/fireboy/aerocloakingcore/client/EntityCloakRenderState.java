package net.fireboy.aerocloakingcore.client;

import java.util.ArrayDeque;

/**
 * Temporary render-thread state used while cloaked entity/block-entity
 * geometry is being flushed.
 *
 * The state is stack-safe because a block-entity renderer may itself render
 * an item/entity that enters the same cloak path before returning.
 */
public final class EntityCloakRenderState {

    private static final ThreadLocal<Float> ACTIVE_CLOAK_STRENGTH =
            ThreadLocal.withInitial(() -> 0.0F);

    private static final ThreadLocal<CloakRenderMode> ACTIVE_RENDER_MODE =
            ThreadLocal.withInitial(() -> CloakRenderMode.DITHER);

    private static final ThreadLocal<ArrayDeque<State>> HISTORY =
            ThreadLocal.withInitial(ArrayDeque::new);

    private EntityCloakRenderState() {
    }

    public static void begin(
            float cloakStrength,
            CloakRenderMode renderMode
    ) {
        HISTORY.get().push(
                new State(
                        ACTIVE_CLOAK_STRENGTH.get(),
                        ACTIVE_RENDER_MODE.get()
                )
        );

        ACTIVE_CLOAK_STRENGTH.set(clamp(cloakStrength));
        ACTIVE_RENDER_MODE.set(
                renderMode != null
                        ? renderMode
                        : CloakRenderMode.DITHER
        );
    }

    /** Retained as a safe fallback for any older call sites. */
    public static void begin(float cloakStrength) {
        begin(cloakStrength, CloakRenderMode.DITHER);
    }

    public static void end() {
        ArrayDeque<State> history = HISTORY.get();

        if (history.isEmpty()) {
            ACTIVE_CLOAK_STRENGTH.set(0.0F);
            ACTIVE_RENDER_MODE.set(CloakRenderMode.DITHER);
            return;
        }

        State previous = history.pop();
        ACTIVE_CLOAK_STRENGTH.set(previous.cloakStrength());
        ACTIVE_RENDER_MODE.set(previous.renderMode());
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

    private record State(
            float cloakStrength,
            CloakRenderMode renderMode
    ) {
    }
}
