package net.fireboy.aerocloakingcore.cloak;

import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * Per-cloak-system settings stored on each Cloaking Core and mirrored across
 * every core that belongs to the same connected cloak group.
 */
public record CloakingCoreSettings(
        float cloakStrength,
        CloakRenderMode renderMode,
        boolean cloakConnectedSubLevels,
        boolean cloakRopeConnectedSubLevels
) {

    /**
     * Normal physical connections (swivels, docking connectors, etc.) inherit
     * cloak by default. Rope propagation is opt-in because ropes can connect a
     * craft to a large/distant sublevel unexpectedly.
     */
    public static final CloakingCoreSettings DEFAULT = new CloakingCoreSettings(
            0.0F,
            CloakRenderMode.DITHER,
            true,
            false
    );

    public CloakingCoreSettings normalized() {
        return new CloakingCoreSettings(
                clamp(cloakStrength, 0.0F, 1.0F),
                renderMode == null ? CloakRenderMode.DITHER : renderMode,
                cloakConnectedSubLevels,
                cloakRopeConnectedSubLevels
        );
    }

    public CloakingCoreSettings withCloakStrength(float value) {
        return new CloakingCoreSettings(
                value,
                renderMode,
                cloakConnectedSubLevels,
                cloakRopeConnectedSubLevels
        ).normalized();
    }

    public CloakingCoreSettings withRenderMode(CloakRenderMode value) {
        return new CloakingCoreSettings(
                cloakStrength,
                value,
                cloakConnectedSubLevels,
                cloakRopeConnectedSubLevels
        ).normalized();
    }

    public CloakingCoreSettings withConnectionOptions(
            boolean cloakConnectedSubLevels,
            boolean cloakRopeConnectedSubLevels
    ) {
        return new CloakingCoreSettings(
                cloakStrength,
                renderMode,
                cloakConnectedSubLevels,
                cloakRopeConnectedSubLevels
        ).normalized();
    }

    public void write(RegistryFriendlyByteBuf buffer) {
        CloakingCoreSettings value = normalized();
        buffer.writeFloat(value.cloakStrength());
        buffer.writeVarInt(value.renderMode().ordinal());
        buffer.writeBoolean(value.cloakConnectedSubLevels());
        buffer.writeBoolean(value.cloakRopeConnectedSubLevels());
    }

    public static CloakingCoreSettings read(RegistryFriendlyByteBuf buffer) {
        return new CloakingCoreSettings(
                buffer.readFloat(),
                enumByOrdinal(
                        CloakRenderMode.values(),
                        buffer.readVarInt(),
                        CloakRenderMode.DITHER
                ),
                buffer.readBoolean(),
                buffer.readBoolean()
        ).normalized();
    }

    public void save(CompoundTag tag) {
        CloakingCoreSettings value = normalized();
        tag.putFloat("CloakStrength", value.cloakStrength());
        tag.putString("RenderMode", value.renderMode().name());
        tag.putBoolean(
                "CloakConnectedSubLevels",
                value.cloakConnectedSubLevels()
        );
        tag.putBoolean(
                "CloakRopeConnectedSubLevels",
                value.cloakRopeConnectedSubLevels()
        );
    }

    public static CloakingCoreSettings load(CompoundTag tag) {
        float strength = DEFAULT.cloakStrength();

        if (tag.contains("CloakStrength")) {
            strength = tag.getFloat("CloakStrength");
        }

        // Migration from the previous Active boolean. An old disabled core
        // should load visible even if it had CloakStrength=1 stored beside it.
        if (tag.contains("Active") && !tag.getBoolean("Active")) {
            strength = 0.0F;
        }

        boolean connected = tag.contains("CloakConnectedSubLevels")
                ? tag.getBoolean("CloakConnectedSubLevels")
                : DEFAULT.cloakConnectedSubLevels();

        boolean ropes = tag.contains("CloakRopeConnectedSubLevels")
                ? tag.getBoolean("CloakRopeConnectedSubLevels")
                : DEFAULT.cloakRopeConnectedSubLevels();

        return new CloakingCoreSettings(
                strength,
                parseEnum(
                        CloakRenderMode.class,
                        tag.getString("RenderMode"),
                        DEFAULT.renderMode()
                ),
                connected,
                ropes
        ).normalized();
    }

    private static <T> T enumByOrdinal(T[] values, int ordinal, T fallback) {
        return ordinal >= 0 && ordinal < values.length
                ? values[ordinal]
                : fallback;
    }

    private static <E extends Enum<E>> E parseEnum(
            Class<E> type,
            String value,
            E fallback
    ) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
