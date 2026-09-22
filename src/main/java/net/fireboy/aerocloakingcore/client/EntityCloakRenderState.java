package net.fireboy.aerocloakingcore.client;

import java.util.ArrayDeque;

/**
 * Temporary render-thread state used while cloaked entity/block-entity/rope
 * geometry is being flushed.
 *
 * Besides the normal single-mode path this also supports a composite state
 * where alpha blending and dither can both be active for the same draw.  The
 * rope renderer uses that only while transitioning between a DITHER endpoint
 * and an ALPHA/ALPHA_SURFACE endpoint.
 *
 * The state is stack-safe because a block-entity renderer may itself render
 * an item/entity that enters the same cloak path before returning.
 */
public final class EntityCloakRenderState {

    private static final ThreadLocal<Float> ACTIVE_CLOAK_STRENGTH =
            ThreadLocal.withInitial(() -> 0.0F);

    private static final ThreadLocal<CloakRenderMode> ACTIVE_RENDER_MODE =
            ThreadLocal.withInitial(() -> CloakRenderMode.DITHER);

    /** Amount of stable screen-space dither/discard to apply. */
    private static final ThreadLocal<Float> ACTIVE_DITHER_STRENGTH =
            ThreadLocal.withInitial(() -> 0.0F);

    /** Multiplicative alpha applied to surviving fragments. */
    private static final ThreadLocal<Float> ACTIVE_ALPHA_MULTIPLIER =
            ThreadLocal.withInitial(() -> 1.0F);

    /** True while geometry is being replayed into depth only. */
    private static final ThreadLocal<Boolean> ACTIVE_DEPTH_ONLY =
            ThreadLocal.withInitial(() -> false);

    private static final ThreadLocal<ArrayDeque<State>> HISTORY =
            ThreadLocal.withInitial(ArrayDeque::new);

    private EntityCloakRenderState() {
    }

    public static void begin(
            float cloakStrength,
            CloakRenderMode renderMode
    ) {
        float strength = clamp(cloakStrength);
        CloakRenderMode mode = renderMode != null
                ? renderMode
                : CloakRenderMode.DITHER;

        beginInternal(
                strength,
                mode,
                mode == CloakRenderMode.DITHER ? strength : 0.0F,
                mode.isAlpha() ? 1.0F - strength : 1.0F,
                false
        );
    }

    /**
     * Starts a draw that may use dither and alpha at the same time.
     *
     * @param logicalCloakStrength the rope's ordinary cloak strength at this
     *                             point (used by generic state queries)
     * @param ditherStrength       screen-space discard amount, 0..1
     * @param alphaMultiplier      alpha multiplier for surviving fragments,
     *                             0..1
     */
    public static void beginComposite(
            float logicalCloakStrength,
            float ditherStrength,
            float alphaMultiplier
    ) {
        float alpha = clamp(alphaMultiplier);
        float dither = clamp(ditherStrength);

        CloakRenderMode representativeMode =
                alpha < 0.999F
                        ? CloakRenderMode.ALPHA
                        : CloakRenderMode.DITHER;

        beginInternal(
                clamp(logicalCloakStrength),
                representativeMode,
                dither,
                alpha,
                false
        );
    }

    /** Retained as a safe fallback for any older call sites. */
    public static void begin(float cloakStrength) {
        begin(cloakStrength, CloakRenderMode.DITHER);
    }

    /**
     * Starts a geometry replay that writes depth but no colour.
     *
     * <p>This is used by OCCLUDED_ONLY for physical geometry such as fully
     * cloaked ropes. Dither and alpha are deliberately disabled so the full
     * model becomes an entity-only depth occluder.</p>
     */
    public static void beginDepthOnly() {
        beginInternal(
                0.0F,
                CloakRenderMode.DITHER,
                0.0F,
                1.0F,
                true
        );
    }

    private static void beginInternal(
            float cloakStrength,
            CloakRenderMode renderMode,
            float ditherStrength,
            float alphaMultiplier,
            boolean depthOnly
    ) {
        HISTORY.get().push(
                new State(
                        ACTIVE_CLOAK_STRENGTH.get(),
                        ACTIVE_RENDER_MODE.get(),
                        ACTIVE_DITHER_STRENGTH.get(),
                        ACTIVE_ALPHA_MULTIPLIER.get(),
                        ACTIVE_DEPTH_ONLY.get()
                )
        );

        ACTIVE_CLOAK_STRENGTH.set(clamp(cloakStrength));
        ACTIVE_RENDER_MODE.set(
                renderMode != null
                        ? renderMode
                        : CloakRenderMode.DITHER
        );
        ACTIVE_DITHER_STRENGTH.set(clamp(ditherStrength));
        ACTIVE_ALPHA_MULTIPLIER.set(clamp(alphaMultiplier));
        ACTIVE_DEPTH_ONLY.set(depthOnly);
    }

    public static void end() {
        ArrayDeque<State> history = HISTORY.get();

        if (history.isEmpty()) {
            ACTIVE_CLOAK_STRENGTH.set(0.0F);
            ACTIVE_RENDER_MODE.set(CloakRenderMode.DITHER);
            ACTIVE_DITHER_STRENGTH.set(0.0F);
            ACTIVE_ALPHA_MULTIPLIER.set(1.0F);
            ACTIVE_DEPTH_ONLY.set(false);
            return;
        }

        State previous = history.pop();
        ACTIVE_CLOAK_STRENGTH.set(previous.cloakStrength());
        ACTIVE_RENDER_MODE.set(previous.renderMode());
        ACTIVE_DITHER_STRENGTH.set(previous.ditherStrength());
        ACTIVE_ALPHA_MULTIPLIER.set(previous.alphaMultiplier());
        ACTIVE_DEPTH_ONLY.set(previous.depthOnly());
    }

    public static float getCloakStrength() {
        return ACTIVE_CLOAK_STRENGTH.get();
    }

    /**
     * Alpha actually used by the current draw.  For ordinary ALPHA draws this
     * is exactly 1 - cloakStrength; for DITHER it is 1.
     */
    public static float getAlpha() {
        return ACTIVE_ALPHA_MULTIPLIER.get();
    }

    public static float getAlphaMultiplier() {
        return ACTIVE_ALPHA_MULTIPLIER.get();
    }

    public static float getDitherStrength() {
        return ACTIVE_DITHER_STRENGTH.get();
    }

    public static CloakRenderMode getRenderMode() {
        return ACTIVE_RENDER_MODE.get();
    }

    public static boolean isDepthOnly() {
        return ACTIVE_DEPTH_ONLY.get();
    }

    public static boolean isActive() {
        return isDepthOnly()
                || getCloakStrength() > 0.001F
                || getDitherStrength() > 0.001F
                || getAlphaMultiplier() < 0.999F;
    }

    private static float clamp(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private record State(
            float cloakStrength,
            CloakRenderMode renderMode,
            float ditherStrength,
            float alphaMultiplier,
            boolean depthOnly
    ) {
    }
}
