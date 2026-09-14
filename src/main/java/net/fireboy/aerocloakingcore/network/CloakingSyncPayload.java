package net.fireboy.aerocloakingcore.network;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.fireboy.aerocloakingcore.cloak.CloakingCoreSettings;
import net.fireboy.aerocloakingcore.cloak.CloakingServerSettings;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record CloakingSyncPayload(
        CloakingServerSettings serverSettings,
        List<Entry> entries
) implements CustomPacketPayload {

    public record Entry(
            UUID subLevelId,
            CloakingCoreSettings settings
    ) {
        public void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeUUID(subLevelId);
            settings.write(buffer);
        }

        public static Entry read(RegistryFriendlyByteBuf buffer) {
            return new Entry(
                    buffer.readUUID(),
                    CloakingCoreSettings.read(buffer)
            );
        }
    }

    public static final Type<CloakingSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    AeroCloakingCore.MOD_ID,
                    "cloaking_sync"
            ));

    public static final StreamCodec<RegistryFriendlyByteBuf, CloakingSyncPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public CloakingSyncPayload decode(RegistryFriendlyByteBuf buffer) {
                    CloakingServerSettings serverSettings =
                            CloakingServerSettings.read(buffer);

                    int size = buffer.readVarInt();
                    List<Entry> entries = new ArrayList<>(size);

                    for (int i = 0; i < size; i++) {
                        entries.add(Entry.read(buffer));
                    }

                    return new CloakingSyncPayload(
                            serverSettings,
                            entries
                    );
                }

                @Override
                public void encode(
                        RegistryFriendlyByteBuf buffer,
                        CloakingSyncPayload payload
                ) {
                    payload.serverSettings().write(buffer);

                    buffer.writeVarInt(payload.entries().size());

                    for (Entry entry : payload.entries()) {
                        entry.write(buffer);
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
