package net.fireboy.aerocloakingcore.network;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Sets one of the Cloaking Core's two Create Redstone Link frequencies. */
public record UpdateCloakingCoreLinkFrequencyPayload(
        int containerId,
        boolean first,
        ItemStack frequency
) implements CustomPacketPayload {

    public static final Type<UpdateCloakingCoreLinkFrequencyPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    AeroCloakingCore.MOD_ID,
                    "update_cloaking_core_link_frequency"
            ));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateCloakingCoreLinkFrequencyPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public UpdateCloakingCoreLinkFrequencyPayload decode(
                        RegistryFriendlyByteBuf buffer
                ) {
                    return new UpdateCloakingCoreLinkFrequencyPayload(
                            buffer.readVarInt(),
                            buffer.readBoolean(),
                            ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer)
                    );
                }

                @Override
                public void encode(
                        RegistryFriendlyByteBuf buffer,
                        UpdateCloakingCoreLinkFrequencyPayload payload
                ) {
                    buffer.writeVarInt(payload.containerId());
                    buffer.writeBoolean(payload.first());
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(
                            buffer,
                            payload.frequency()
                    );
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
