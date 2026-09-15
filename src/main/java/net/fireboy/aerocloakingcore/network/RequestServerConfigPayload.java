package net.fireboy.aerocloakingcore.network;

import net.fireboy.aerocloakingcore.AeroCloakingCore;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Requests the live server config and this player's edit permission. */
public record RequestServerConfigPayload() implements CustomPacketPayload {

    public static final Type<RequestServerConfigPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    AeroCloakingCore.MOD_ID,
                    "request_server_config"
            ));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestServerConfigPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public RequestServerConfigPayload decode(
                        RegistryFriendlyByteBuf buffer
                ) {
                    return new RequestServerConfigPayload();
                }

                @Override
                public void encode(
                        RegistryFriendlyByteBuf buffer,
                        RequestServerConfigPayload payload
                ) {
                    // No fields.
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
