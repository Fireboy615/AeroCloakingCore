package net.fireboy.aerocloakingcore.network;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.fireboy.aerocloakingcore.cloak.CloakingServerSettings;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Live server-config snapshot used by the remote config editor.
 */
public record ServerConfigSnapshotPayload(
        CloakingServerSettings settings,
        boolean canEdit,
        Result result
) implements CustomPacketPayload {

    public enum Result {
        NONE,
        SAVED,
        DENIED
    }

    public static final Type<ServerConfigSnapshotPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    AeroCloakingCore.MOD_ID,
                    "server_config_snapshot"
            ));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerConfigSnapshotPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ServerConfigSnapshotPayload decode(
                        RegistryFriendlyByteBuf buffer
                ) {
                    CloakingServerSettings settings =
                            CloakingServerSettings.read(buffer);

                    boolean canEdit = buffer.readBoolean();
                    int ordinal = buffer.readVarInt();

                    Result[] values = Result.values();
                    Result result = ordinal >= 0 && ordinal < values.length
                            ? values[ordinal]
                            : Result.NONE;

                    return new ServerConfigSnapshotPayload(
                            settings,
                            canEdit,
                            result
                    );
                }

                @Override
                public void encode(
                        RegistryFriendlyByteBuf buffer,
                        ServerConfigSnapshotPayload payload
                ) {
                    payload.settings().normalized().write(buffer);
                    buffer.writeBoolean(payload.canEdit());
                    buffer.writeVarInt(payload.result().ordinal());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
