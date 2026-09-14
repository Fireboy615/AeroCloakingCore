package net.fireboy.aerocloakingcore.network;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Updates per-core UI settings.
 *
 * updateStrength is separate so changing render mode alone does not steal
 * control from a Redstone Link. Moving the strength slider intentionally does.
 */
public record UpdateCloakingCoreSettingsPayload(
        int containerId,
        boolean updateStrength,
        float cloakStrength,
        CloakRenderMode renderMode
) implements CustomPacketPayload {

    public static final Type<UpdateCloakingCoreSettingsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    AeroCloakingCore.MOD_ID,
                    "update_cloaking_core_settings"
            ));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateCloakingCoreSettingsPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public UpdateCloakingCoreSettingsPayload decode(RegistryFriendlyByteBuf buffer) {
                    int containerId = buffer.readVarInt();
                    boolean updateStrength = buffer.readBoolean();
                    float cloakStrength = buffer.readFloat();
                    int ordinal = buffer.readVarInt();

                    CloakRenderMode[] values = CloakRenderMode.values();
                    CloakRenderMode mode = ordinal >= 0 && ordinal < values.length
                            ? values[ordinal]
                            : CloakRenderMode.DITHER;

                    return new UpdateCloakingCoreSettingsPayload(
                            containerId,
                            updateStrength,
                            cloakStrength,
                            mode
                    );
                }

                @Override
                public void encode(
                        RegistryFriendlyByteBuf buffer,
                        UpdateCloakingCoreSettingsPayload payload
                ) {
                    buffer.writeVarInt(payload.containerId());
                    buffer.writeBoolean(payload.updateStrength());
                    buffer.writeFloat(payload.cloakStrength());
                    buffer.writeVarInt(payload.renderMode().ordinal());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
