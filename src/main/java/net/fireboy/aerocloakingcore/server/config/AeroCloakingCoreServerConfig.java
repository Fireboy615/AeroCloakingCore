package net.fireboy.aerocloakingcore.server.config;

import net.fireboy.aerocloakingcore.client.CloakEasing;
import net.fireboy.aerocloakingcore.cloak.CloakingServerSettings;
import net.fireboy.aerocloakingcore.cloak.RopeCloakBehavior;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-authoritative cloak behaviour. */
public final class AeroCloakingCoreServerConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.DoubleValue TRANSITION_DURATION_SECONDS;
    public static final ModConfigSpec.EnumValue<CloakEasing> TRANSITION_EASING;

    public static final ModConfigSpec.BooleanValue VISIBLE_WHILE_ABOARD;
    public static final ModConfigSpec.DoubleValue ABOARD_FADE_SECONDS;
    public static final ModConfigSpec.DoubleValue LEAVE_GRACE_SECONDS;
    public static final ModConfigSpec.DoubleValue LEAVE_FADE_SECONDS;

    public static final ModConfigSpec.BooleanValue PROXIMITY_REVEAL_ENABLED;
    public static final ModConfigSpec.DoubleValue FULLY_VISIBLE_DISTANCE;
    public static final ModConfigSpec.DoubleValue REVEAL_DISTANCE_MULTIPLIER;

    public static final ModConfigSpec.EnumValue<RopeCloakBehavior> ROPE_CLOAK_BEHAVIOR;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder
                .comment("Server-authoritative cloak transition settings.")
                .push("transition");

        TRANSITION_DURATION_SECONDS = builder
                .comment("Base seconds used when cloak strength changes at 64 RPM. System RPM then scales this duration.")
                .defineInRange("durationSeconds", 3.0, 0.0, 30.0);

        TRANSITION_EASING = builder
                .comment("Easing curve used for cloak-strength transitions.")
                .defineEnum("easing", CloakEasing.SMOOTHSTEP);

        builder.pop();

        builder
                .comment("Server-authoritative viewer reveal settings.")
                .push("viewerReveal");

        VISIBLE_WHILE_ABOARD = builder
                .comment("Players aboard/tracking a cloaked sublevel see it fully visible.")
                .define("visibleWhileAboard", true);

        ABOARD_FADE_SECONDS = builder
                .comment("Seconds used to fade a cloaked sublevel back in when the viewer boards/tracks it.")
                .defineInRange("aboardFadeSeconds", 1.0, 0.0, 60.0);

        LEAVE_GRACE_SECONDS = builder
                .comment("Seconds a sublevel remains at its current reveal level after a player leaves it.")
                .defineInRange("leaveGraceSeconds", 2.0, 0.0, 60.0);

        LEAVE_FADE_SECONDS = builder
                .comment("Seconds used to restore cloak after the leave grace period.")
                .defineInRange("leaveFadeSeconds", 2.0, 0.0, 60.0);

        PROXIMITY_REVEAL_ENABLED = builder
                .comment("Reveal cloaked sublevels when a player gets close to them.")
                .define("proximityRevealEnabled", true);

        FULLY_VISIBLE_DISTANCE = builder
                .comment(
                        "Distance from the sublevel bounds where the cloak is fully revealed.",
                        "This remains the inner/full-reveal distance; ship size and RPM only change",
                        "the outer distance where the ship begins to become visible."
                )
                .defineInRange("fullyVisibleDistance", 4.0, 0.0, 64.0);

        REVEAL_DISTANCE_MULTIPLIER = builder
                .comment(
                        "Multiplier for the ship-size-based reveal range. ",
                        "1.0 keeps the default balance: a 256-block ship at <=128 average RPM ",
                        "starts revealing 14 blocks from its bounds and is fully visible at 4 blocks."
                )
                .defineInRange("revealDistanceMultiplier", 1.0, 0.0, 10.0);

        builder.pop();

        builder
                .comment("How Simulated rope strands connected to cloaked sublevels should render.")
                .push("ropes");

        ROPE_CLOAK_BEHAVIOR = builder
                .comment(
                        "GRADIENT interpolates cloak strength between both rope endpoints.",
                        "INHERIT_STRONGEST applies the strongest endpoint cloak to the whole rope."
                )
                .defineEnum("cloakBehavior", RopeCloakBehavior.GRADIENT);

        builder.pop();

        SPEC = builder.build();
    }

    /** Applies a validated network snapshot to the live server config and saves it. */
    public static CloakingServerSettings applyAndSave(
            CloakingServerSettings requested
    ) {
        CloakingServerSettings normalized =
                requested != null
                        ? requested.normalized()
                        : CloakingServerSettings.fromConfig();

        float transitionDuration = finiteOr(
                normalized.transitionDurationSeconds(),
                TRANSITION_DURATION_SECONDS.get().floatValue()
        );
        float aboardFade = finiteOr(
                normalized.aboardFadeSeconds(),
                ABOARD_FADE_SECONDS.get().floatValue()
        );
        float leaveGrace = finiteOr(
                normalized.leaveGraceSeconds(),
                LEAVE_GRACE_SECONDS.get().floatValue()
        );
        float leaveFade = finiteOr(
                normalized.leaveFadeSeconds(),
                LEAVE_FADE_SECONDS.get().floatValue()
        );
        double fullyVisibleDistance = finiteOr(
                normalized.fullyVisibleDistance(),
                FULLY_VISIBLE_DISTANCE.get()
        );
        double revealMultiplier = finiteOr(
                normalized.revealDistanceMultiplier(),
                REVEAL_DISTANCE_MULTIPLIER.get()
        );

        transitionDuration = clamp(transitionDuration, 0.0F, 30.0F);
        aboardFade = clamp(aboardFade, 0.0F, 60.0F);
        leaveGrace = clamp(leaveGrace, 0.0F, 60.0F);
        leaveFade = clamp(leaveFade, 0.0F, 60.0F);
        fullyVisibleDistance = clamp(fullyVisibleDistance, 0.0, 64.0);
        revealMultiplier = clamp(revealMultiplier, 0.0, 10.0);

        TRANSITION_DURATION_SECONDS.set((double) transitionDuration);
        TRANSITION_EASING.set(
                normalized.transitionEasing() != null
                        ? normalized.transitionEasing()
                        : CloakEasing.SMOOTHSTEP
        );

        VISIBLE_WHILE_ABOARD.set(normalized.visibleWhileAboard());
        ABOARD_FADE_SECONDS.set((double) aboardFade);
        LEAVE_GRACE_SECONDS.set((double) leaveGrace);
        LEAVE_FADE_SECONDS.set((double) leaveFade);
        PROXIMITY_REVEAL_ENABLED.set(normalized.proximityRevealEnabled());
        FULLY_VISIBLE_DISTANCE.set(fullyVisibleDistance);
        REVEAL_DISTANCE_MULTIPLIER.set(revealMultiplier);
        ROPE_CLOAK_BEHAVIOR.set(normalized.ropeCloakBehavior());

        SPEC.save();
        return CloakingServerSettings.fromConfig();
    }

    private static float finiteOr(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }

    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private AeroCloakingCoreServerConfig() {
    }
}
