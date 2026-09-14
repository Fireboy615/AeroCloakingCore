package net.fireboy.aerocloakingcore.cloak;

import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * Per-core settings that are stored on the block entity and synced to clients.
 *
 * Viewer reveal/fade behaviour is server-configured globally; render mode stays
 * per-core because different Cloaking Cores may use different visual effects.
 */
public record CloakingCoreSettings(
        float cloakStrength,
        CloakRenderMode renderMode
) {

    public static final CloakingCoreSettings DEFAULT = new CloakingCoreSettings(
            0.0F,
            CloakRenderMode.DITHER
    );

    public CloakingCoreSettings normalized() {
        return new CloakingCoreSettings(
                clamp(cloakStrength, 0.0F, 1.0F),
                renderMode == null ? CloakRenderMode.DITHER : renderMode
        );
    }

    public CloakingCoreSettings withCloakStrength(float value) {
        return new CloakingCoreSettings(value, renderMode).normalized();
    }

    public CloakingCoreSettings withRenderMode(CloakRenderMode value) {
        return new CloakingCoreSettings(cloakStrength, value).normalized();
    }

    public void write(RegistryFriendlyByteBuf buffer) {
        CloakingCoreSettings value = normalized();
        buffer.writeFloat(value.cloakStrength());
        buffer.writeVarInt(value.renderMode().ordinal());
    }

    public static CloakingCoreSettings read(RegistryFriendlyByteBuf buffer) {
        return new CloakingCoreSettings(
                buffer.readFloat(),
                enumByOrdinal(
                        CloakRenderMode.values(),
                        buffer.readVarInt(),
                        CloakRenderMode.DITHER
                )
        ).normalized();
    }

    public void save(CompoundTag tag) {
        CloakingCoreSettings value = normalized();
        tag.putFloat("CloakStrength", value.cloakStrength());
        tag.putString("RenderMode", value.renderMode().name());
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

        return new CloakingCoreSettings(
                strength,
                parseEnum(
                        CloakRenderMode.class,
                        tag.getString("RenderMode"),
                        DEFAULT.renderMode()
                )
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
