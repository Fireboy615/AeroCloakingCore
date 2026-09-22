package net.fireboy.aerocloakingcore.cloak;

import net.fireboy.aerocloakingcore.client.CloakEasing;
import net.fireboy.aerocloakingcore.server.config.AeroCloakingCoreServerConfig;
import net.minecraft.network.RegistryFriendlyByteBuf;


/**
 * Small network-safe snapshot of the server config that clients need while rendering.
 */
public record CloakingServerSettings(
        boolean modEnabled,
        float transitionDurationSeconds,
        CloakEasing transitionEasing,
        boolean visibleWhileAboard,
        float aboardFadeSeconds,
        float leaveGraceSeconds,
        float leaveFadeSeconds,
        boolean proximityRevealEnabled,
        double fullyVisibleDistance,
        double revealDistanceMultiplier,
        CloakDistanceMode cloakDistanceMode,
        EntityCloakBehavior entityCloakBehavior,
        RopeCloakBehavior ropeCloakBehavior
) {

    /** Default distance from the sublevel bounds where proximity reveal is 100%. */
    public static final double DEFAULT_FULLY_VISIBLE_DISTANCE_BLOCKS = 4.0;

    /**
     * Reveal-gap baseline at 256 ship blocks, 128 average RPM and a 1.0x
     * reveal-distance multiplier. This preserves the old 4 -> 14 behaviour.
     */
    public static final double BASE_REVEAL_GAP_BLOCKS = 10.0;

    public static final CloakingServerSettings DEFAULT = new CloakingServerSettings(
            true,
            3.0F,
            CloakEasing.SMOOTHSTEP,
            true,
            1.0F,
            2.0F,
            2.0F,
            true,
            DEFAULT_FULLY_VISIBLE_DISTANCE_BLOCKS,
            1.0,
            CloakDistanceMode.CLOSEST_FACE,
            EntityCloakBehavior.MATCH_SHIP,
            RopeCloakBehavior.GRADIENT
    );

    public static CloakingServerSettings fromConfig() {
        return new CloakingServerSettings(
                AeroCloakingCoreServerConfig.MOD_ENABLED.get(),
                AeroCloakingCoreServerConfig.TRANSITION_DURATION_SECONDS.get().floatValue(),
                AeroCloakingCoreServerConfig.TRANSITION_EASING.get(),
                AeroCloakingCoreServerConfig.VISIBLE_WHILE_ABOARD.get(),
                AeroCloakingCoreServerConfig.ABOARD_FADE_SECONDS.get().floatValue(),
                AeroCloakingCoreServerConfig.LEAVE_GRACE_SECONDS.get().floatValue(),
                AeroCloakingCoreServerConfig.LEAVE_FADE_SECONDS.get().floatValue(),
                AeroCloakingCoreServerConfig.PROXIMITY_REVEAL_ENABLED.get(),
                AeroCloakingCoreServerConfig.FULLY_VISIBLE_DISTANCE.get(),
                AeroCloakingCoreServerConfig.REVEAL_DISTANCE_MULTIPLIER.get(),
                AeroCloakingCoreServerConfig.CLOAK_DISTANCE_MODE.get(),
                AeroCloakingCoreServerConfig.ENTITY_CLOAK_BEHAVIOR.get(),
                AeroCloakingCoreServerConfig.ROPE_CLOAK_BEHAVIOR.get()
        ).normalized();
    }

    public CloakingServerSettings normalized() {
        return new CloakingServerSettings(
                modEnabled,
                clamp(transitionDurationSeconds, 0.0F, 30.0F),
                transitionEasing == null ? CloakEasing.SMOOTHSTEP : transitionEasing,
                visibleWhileAboard,
                clamp(aboardFadeSeconds, 0.0F, 60.0F),
                clamp(leaveGraceSeconds, 0.0F, 60.0F),
                clamp(leaveFadeSeconds, 0.0F, 60.0F),
                proximityRevealEnabled,
                clamp(fullyVisibleDistance, 0.0, 64.0),
                clamp(revealDistanceMultiplier, 0.0, 10.0),
                cloakDistanceMode == null ? CloakDistanceMode.CLOSEST_FACE : cloakDistanceMode,
                entityCloakBehavior == null ? EntityCloakBehavior.MATCH_SHIP : entityCloakBehavior,
                ropeCloakBehavior == null ? RopeCloakBehavior.GRADIENT : ropeCloakBehavior
        );
    }

    public void write(RegistryFriendlyByteBuf buffer) {
        CloakingServerSettings value = normalized();

        buffer.writeBoolean(value.modEnabled());
        buffer.writeFloat(value.transitionDurationSeconds());
        buffer.writeVarInt(value.transitionEasing().ordinal());
        buffer.writeBoolean(value.visibleWhileAboard());
        buffer.writeFloat(value.aboardFadeSeconds());
        buffer.writeFloat(value.leaveGraceSeconds());
        buffer.writeFloat(value.leaveFadeSeconds());
        buffer.writeBoolean(value.proximityRevealEnabled());
        buffer.writeDouble(value.fullyVisibleDistance());
        buffer.writeDouble(value.revealDistanceMultiplier());
        buffer.writeVarInt(value.cloakDistanceMode().ordinal());
        buffer.writeVarInt(value.entityCloakBehavior().ordinal());
        buffer.writeVarInt(value.ropeCloakBehavior().ordinal());
    }

    public static CloakingServerSettings read(RegistryFriendlyByteBuf buffer) {
        return new CloakingServerSettings(
                buffer.readBoolean(),
                buffer.readFloat(),
                enumByOrdinal(CloakEasing.values(), buffer.readVarInt(), CloakEasing.SMOOTHSTEP),
                buffer.readBoolean(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readBoolean(),
                buffer.readDouble(),
                buffer.readDouble(),
                enumByOrdinal(CloakDistanceMode.values(), buffer.readVarInt(), CloakDistanceMode.CLOSEST_FACE),
                enumByOrdinal(EntityCloakBehavior.values(), buffer.readVarInt(), EntityCloakBehavior.MATCH_SHIP),
                enumByOrdinal(RopeCloakBehavior.values(), buffer.readVarInt(), RopeCloakBehavior.GRADIENT)
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
