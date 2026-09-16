package net.fireboy.aerocloakingcore.cloak;

import net.fireboy.aerocloakingcore.client.CloakEasing;
import net.fireboy.aerocloakingcore.server.config.AeroCloakingCoreServerConfig;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * Small network-safe snapshot of the server config that clients need while rendering.
 */
public record CloakingServerSettings(
        float transitionDurationSeconds,
        CloakEasing transitionEasing,
        boolean visibleWhileAboard,
        float aboardFadeSeconds,
        float leaveGraceSeconds,
        float leaveFadeSeconds,
        boolean proximityRevealEnabled,
        double fullyVisibleDistance,
        double fullyCloakedDistance
) {

    public static final CloakingServerSettings DEFAULT = new CloakingServerSettings(
            3.0F,
            CloakEasing.SMOOTHSTEP,
            true,
            1.0F,
            2.0F,
            2.0F,
            true,
            4.0,
            14.0
    );

    public static CloakingServerSettings fromConfig() {
        return new CloakingServerSettings(
                AeroCloakingCoreServerConfig.TRANSITION_DURATION_SECONDS.get().floatValue(),
                AeroCloakingCoreServerConfig.TRANSITION_EASING.get(),
                AeroCloakingCoreServerConfig.VISIBLE_WHILE_ABOARD.get(),
                AeroCloakingCoreServerConfig.ABOARD_FADE_SECONDS.get().floatValue(),
                AeroCloakingCoreServerConfig.LEAVE_GRACE_SECONDS.get().floatValue(),
                AeroCloakingCoreServerConfig.LEAVE_FADE_SECONDS.get().floatValue(),
                AeroCloakingCoreServerConfig.PROXIMITY_REVEAL_ENABLED.get(),
                AeroCloakingCoreServerConfig.FULLY_VISIBLE_DISTANCE.get(),
                AeroCloakingCoreServerConfig.FULLY_CLOAKED_DISTANCE.get()
        ).normalized();
    }

    public CloakingServerSettings normalized() {
        double visible = clamp(fullyVisibleDistance, 0.0, 128.0);
        double cloaked = clamp(fullyCloakedDistance, 0.0, 256.0);

        if (cloaked < visible) {
            cloaked = visible;
        }

        return new CloakingServerSettings(
                clamp(transitionDurationSeconds, 0.0F, 30.0F),
                transitionEasing == null ? CloakEasing.SMOOTHSTEP : transitionEasing,
                visibleWhileAboard,
                clamp(aboardFadeSeconds, 0.0F, 60.0F),
                clamp(leaveGraceSeconds, 0.0F, 60.0F),
                clamp(leaveFadeSeconds, 0.0F, 60.0F),
                proximityRevealEnabled,
                visible,
                cloaked
        );
    }

    public void write(RegistryFriendlyByteBuf buffer) {
        CloakingServerSettings value = normalized();

        buffer.writeFloat(value.transitionDurationSeconds());
        buffer.writeVarInt(value.transitionEasing().ordinal());
        buffer.writeBoolean(value.visibleWhileAboard());
        buffer.writeFloat(value.aboardFadeSeconds());
        buffer.writeFloat(value.leaveGraceSeconds());
        buffer.writeFloat(value.leaveFadeSeconds());
        buffer.writeBoolean(value.proximityRevealEnabled());
        buffer.writeDouble(value.fullyVisibleDistance());
        buffer.writeDouble(value.fullyCloakedDistance());
    }

    public static CloakingServerSettings read(RegistryFriendlyByteBuf buffer) {
        return new CloakingServerSettings(
                buffer.readFloat(),
                enumByOrdinal(CloakEasing.values(), buffer.readVarInt(), CloakEasing.SMOOTHSTEP),
                buffer.readBoolean(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readBoolean(),
                buffer.readDouble(),
                buffer.readDouble()
        ).normalized();
    }

    private static <T> T enumByOrdinal(T[] values, int ordinal, T fallback) {
        return ordinal >= 0 && ordinal < values.length
                ? values[ordinal]
                : fallback;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
