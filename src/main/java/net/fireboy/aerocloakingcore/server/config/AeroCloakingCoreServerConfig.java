package net.fireboy.aerocloakingcore.server.config;

import net.fireboy.aerocloakingcore.client.CloakEasing;
import net.fireboy.aerocloakingcore.cloak.CloakingServerSettings;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-authoritative cloak behaviour.
 *
 * These values control how every client reveals/fades cloaked sublevels.
 * They are synced to clients inside the normal cloak-state payload.
 */
public final class AeroCloakingCoreServerConfig {

    public static final ModConfigSpec SPEC;

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
                .comment("Server-authoritative cloak transition settings.")
                .push("transition");

        TRANSITION_DURATION_SECONDS = builder
                .comment("Seconds used when cloak strength changes.")
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

        LEAVE_GRACE_SECONDS = builder
                .comment("Seconds a sublevel remains fully visible after a player leaves it.")
                .defineInRange("leaveGraceSeconds", 2.0, 0.0, 60.0);

        LEAVE_FADE_SECONDS = builder
                .comment("Seconds used to restore cloak after the leave grace period.")
                .defineInRange("leaveFadeSeconds", 2.0, 0.0, 60.0);

        PROXIMITY_REVEAL_ENABLED = builder
                .comment("Reveal cloaked sublevels when a player gets close to them.")
                .define("proximityRevealEnabled", true);

        FULLY_VISIBLE_DISTANCE = builder
                .comment("Distance in blocks from the sublevel bounds where cloak is fully suppressed.")
                .defineInRange("fullyVisibleDistance", 4.0, 0.0, 128.0);

        FULLY_CLOAKED_DISTANCE = builder
                .comment("Distance in blocks from the sublevel bounds where full cloak strength is restored.")
                .defineInRange("fullyCloakedDistance", 14.0, 0.0, 256.0);

        builder.pop();

        SPEC = builder.build();
    }

    /**
     * Applies a validated network snapshot to the live server config and saves
     * it to disk. Returns the exact values that were accepted.
     */
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

        float leaveGrace = finiteOr(
                normalized.leaveGraceSeconds(),
                LEAVE_GRACE_SECONDS.get().floatValue()
        );

        float leaveFade = finiteOr(
                normalized.leaveFadeSeconds(),
                LEAVE_FADE_SECONDS.get().floatValue()
        );

        double fullyVisible = finiteOr(
                normalized.fullyVisibleDistance(),
                FULLY_VISIBLE_DISTANCE.get()
        );

        double fullyCloaked = finiteOr(
                normalized.fullyCloakedDistance(),
                FULLY_CLOAKED_DISTANCE.get()
        );

        transitionDuration = clamp(transitionDuration, 0.0F, 30.0F);
        leaveGrace = clamp(leaveGrace, 0.0F, 60.0F);
        leaveFade = clamp(leaveFade, 0.0F, 60.0F);
        fullyVisible = clamp(fullyVisible, 0.0, 128.0);
        fullyCloaked = clamp(fullyCloaked, 0.0, 256.0);

        if (fullyCloaked < fullyVisible) {
            fullyCloaked = fullyVisible;
        }

        TRANSITION_DURATION_SECONDS.set((double) transitionDuration);
        TRANSITION_EASING.set(
                normalized.transitionEasing() != null
                        ? normalized.transitionEasing()
                        : CloakEasing.SMOOTHSTEP
        );

        VISIBLE_WHILE_ABOARD.set(normalized.visibleWhileAboard());
        LEAVE_GRACE_SECONDS.set((double) leaveGrace);
        LEAVE_FADE_SECONDS.set((double) leaveFade);

        PROXIMITY_REVEAL_ENABLED.set(normalized.proximityRevealEnabled());
        FULLY_VISIBLE_DISTANCE.set(fullyVisible);
        FULLY_CLOAKED_DISTANCE.set(fullyCloaked);

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
