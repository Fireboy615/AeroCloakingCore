package net.fireboy.aerocloakingcore.network;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.fireboy.aerocloakingcore.cloak.CloakingServerSettings;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Operator-only request to replace the live server cloak settings. */
public record UpdateServerConfigPayload(
        CloakingServerSettings settings
) implements CustomPacketPayload {

    public static final Type<UpdateServerConfigPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    AeroCloakingCore.MOD_ID,
                    "update_server_config"
            ));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateServerConfigPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public UpdateServerConfigPayload decode(
                        RegistryFriendlyByteBuf buffer
                ) {
                    return new UpdateServerConfigPayload(
                            CloakingServerSettings.read(buffer)
                    );
                }

                @Override
                public void encode(
                        RegistryFriendlyByteBuf buffer,
                        UpdateServerConfigPayload payload
                ) {
                    payload.settings().normalized().write(buffer);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
