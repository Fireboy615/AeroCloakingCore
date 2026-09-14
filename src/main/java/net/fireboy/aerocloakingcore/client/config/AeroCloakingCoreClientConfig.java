package net.fireboy.aerocloakingcore.client.config;

import net.fireboy.aerocloakingcore.client.CloakEasing;
import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Global client-side defaults for Aero Cloaking Core visuals.
 *
 * These are intentionally client settings because viewer reveal distance,
 * post-exit visibility and render technique are visual preferences.
 * A future per-core UI can override these defaults for a specific core.
 */
public final class AeroCloakingCoreClientConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.EnumValue<CloakRenderMode> RENDER_MODE;
    public static final ModConfigSpec.DoubleValue TRANSITION_DURATION_SECONDS;
    public static final ModConfigSpec.EnumValue<CloakEasing> TRANSITION_EASING;

    public static final ModConfigSpec.BooleanValue VISIBLE_WHILE_ABOARD;
    public static final ModConfigSpec.DoubleValue LEAVE_GRACE_SECONDS;
    public static final ModConfigSpec.DoubleValue LEAVE_FADE_SECONDS;

    public static final ModConfigSpec.BooleanValue PROXIMITY_REVEAL_ENABLED;
    public static final ModConfigSpec.DoubleValue FULLY_VISIBLE_DISTANCE;
    public static final ModConfigSpec.DoubleValue FULLY_CLOAKED_DISTANCE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder
                .comment("How partial cloaking is rendered.")
                .translation("aerocloakingcore.configuration.category.rendering")
                .push("rendering");

        RENDER_MODE = builder
                .comment(
                        "DITHER: recommended. Preserves normal depth/translucency behaviour.",
                        "ALPHA: smooth transparency, but may cause world water/translucency artifacts."
                )
                .translation("aerocloakingcore.configuration.renderMode")
                .defineEnum("renderMode", CloakRenderMode.DITHER);

        TRANSITION_DURATION_SECONDS = builder
                .comment("Seconds used when the server cloak state changes between visible and cloaked.")
                .translation("aerocloakingcore.configuration.transitionDurationSeconds")
                .defineInRange("transitionDurationSeconds", 3.0, 0.0, 10.0);

        TRANSITION_EASING = builder
                .comment("Easing curve used for the main cloak transition.")
                .translation("aerocloakingcore.configuration.transitionEasing")
                .defineEnum("transitionEasing", CloakEasing.SMOOTHSTEP);

        builder.pop();

        builder
                .comment("Controls how the local player can reveal a cloaked sublevel.")
                .translation("aerocloakingcore.configuration.category.viewerVisibility")
                .push("viewerVisibility");

        VISIBLE_WHILE_ABOARD = builder
                .comment("Keep the sublevel fully visible while the local player is aboard/tracking it.")
                .translation("aerocloakingcore.configuration.visibleWhileAboard")
                .define("visibleWhileAboard", true);

        LEAVE_GRACE_SECONDS = builder
                .comment("Seconds the sublevel stays fully visible after the local player leaves it.")
                .translation("aerocloakingcore.configuration.leaveGraceSeconds")
                .defineInRange("leaveGraceSeconds", 2.0, 0.0, 30.0);

        LEAVE_FADE_SECONDS = builder
                .comment("Seconds used to fade from the post-exit visibility override back to normal cloak strength.")
                .translation("aerocloakingcore.configuration.leaveFadeSeconds")
                .defineInRange("leaveFadeSeconds", 2.0, 0.0, 30.0);

        PROXIMITY_REVEAL_ENABLED = builder
                .comment("Reveal cloaked sublevels when the local player gets close to them.")
                .translation("aerocloakingcore.configuration.proximityRevealEnabled")
                .define("proximityRevealEnabled", true);

        FULLY_VISIBLE_DISTANCE = builder
                .comment("Distance in blocks from the sublevel bounds where it becomes fully visible.")
                .translation("aerocloakingcore.configuration.fullyVisibleDistance")
                .defineInRange("fullyVisibleDistance", 4.0, 0.0, 128.0);

        FULLY_CLOAKED_DISTANCE = builder
                .comment(
                        "Distance in blocks from the sublevel bounds where full cloak strength is restored.",
                        "This should normally be greater than Fully Visible Distance."
                )
                .translation("aerocloakingcore.configuration.fullyCloakedDistance")
                .defineInRange("fullyCloakedDistance", 14.0, 0.0, 256.0);

        builder.pop();

        SPEC = builder.build();
    }

    private AeroCloakingCoreClientConfig() {
    }
}
