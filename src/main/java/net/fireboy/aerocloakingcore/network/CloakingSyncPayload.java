package net.fireboy.aerocloakingcore.network;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record CloakingSyncPayload(List<UUID> subLevels)
        implements CustomPacketPayload {

    public static final Type<CloakingSyncPayload> TYPE =
            new Type<>(
                    ResourceLocation.fromNamespaceAndPath(
                            AeroCloakingCore.MOD_ID,
                            "cloaking_sync"
                    )
            );

    public static final StreamCodec<RegistryFriendlyByteBuf, CloakingSyncPayload> STREAM_CODEC =
            new StreamCodec<>() {

                @Override
                public CloakingSyncPayload decode(RegistryFriendlyByteBuf buffer) {
                    int size = buffer.readVarInt();

                    List<UUID> ids = new ArrayList<>(size);

                    for (int i = 0; i < size; i++) {
                        ids.add(buffer.readUUID());
                    }

                    return new CloakingSyncPayload(ids);
                }

                @Override
                public void encode(
                        RegistryFriendlyByteBuf buffer,
                        CloakingSyncPayload payload
                ) {
                    buffer.writeVarInt(payload.subLevels().size());

                    for (UUID id : payload.subLevels()) {
                        buffer.writeUUID(id);
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}