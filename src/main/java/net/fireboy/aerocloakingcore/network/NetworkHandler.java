package net.fireboy.aerocloakingcore.network;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.fireboy.aerocloakingcore.cloak.CloakingManager;
import net.fireboy.aerocloakingcore.cloak.CloakingServerSettings;
import net.fireboy.aerocloakingcore.menu.CloakingCoreMenu;
import net.fireboy.aerocloakingcore.server.config.AeroCloakingCoreServerConfig;

import net.minecraft.server.level.ServerPlayer;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = AeroCloakingCore.MOD_ID)
public final class NetworkHandler {

    /** Operator permission level required for remote server-config editing. */
    private static final int SERVER_CONFIG_PERMISSION_LEVEL = 2;

    private NetworkHandler() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("7");

        registrar.playToClient(
                CloakingSyncPayload.TYPE,
                CloakingSyncPayload.STREAM_CODEC,
                (payload, context) -> {
                    CloakingClient.setCloakStates(
                            payload.entries(),
                            payload.serverSettings()
                    );

                    ServerConfigClientState.updateSettings(
                            payload.serverSettings()
                    );
                }
        );

        registrar.playToClient(
                ServerConfigSnapshotPayload.TYPE,
                ServerConfigSnapshotPayload.STREAM_CODEC,
                (payload, context) ->
                        ServerConfigClientState.apply(payload)
        );

        registrar.playToServer(
                RequestServerConfigPayload.TYPE,
                RequestServerConfigPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (!(context.player() instanceof ServerPlayer player)) {
                        return;
                    }

                    sendServerConfigSnapshot(
                            player,
                            ServerConfigSnapshotPayload.Result.NONE
                    );
                }
        );

        registrar.playToServer(
                UpdateServerConfigPayload.TYPE,
                UpdateServerConfigPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (!(context.player() instanceof ServerPlayer player)) {
                        return;
                    }

                    if (!canEditServerConfig(player)) {
                        sendServerConfigSnapshot(
                                player,
                                ServerConfigSnapshotPayload.Result.DENIED
                        );
                        return;
                    }

                    CloakingServerSettings applied =
                            AeroCloakingCoreServerConfig.applyAndSave(
                                    payload.settings()
                            );

                    /*
                     * Existing cloak sync already contains serverSettings, so
                     * this immediately updates every connected player's render
                     * behaviour as well as the config screen cache.
                     */
                    CloakingManager.sync();

                    PacketDistributor.sendToPlayer(
                            player,
                            new ServerConfigSnapshotPayload(
                                    applied,
                                    true,
                                    ServerConfigSnapshotPayload.Result.SAVED
                            )
                    );
                }
        );

        registrar.playToServer(
                UpdateCloakingCoreSettingsPayload.TYPE,
                UpdateCloakingCoreSettingsPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (!(context.player() instanceof ServerPlayer player)) {
                        return;
                    }

                    if (!(player.containerMenu instanceof CloakingCoreMenu menu)) {
                        return;
                    }

                    if (menu.containerId != payload.containerId()) {
                        return;
                    }

                    menu.applySettings(
                            player,
                            payload.updateStrength(),
                            payload.cloakStrength(),
                            payload.renderMode(),
                            payload.cloakConnectedSubLevels(),
                            payload.cloakRopeConnectedSubLevels()
                    );
                }
        );

        registrar.playToServer(
                UpdateCloakingCoreLinkFrequencyPayload.TYPE,
                UpdateCloakingCoreLinkFrequencyPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (!(context.player() instanceof ServerPlayer player)) {
                        return;
                    }

                    if (!(player.containerMenu instanceof CloakingCoreMenu menu)) {
                        return;
                    }

                    if (menu.containerId != payload.containerId()) {
                        return;
                    }

                    menu.applyFrequency(
                            player,
                            payload.first(),
                            payload.frequency()
                    );
                }
        );
    }

    private static boolean canEditServerConfig(ServerPlayer player) {
        return player.hasPermissions(SERVER_CONFIG_PERMISSION_LEVEL);
    }

    private static void sendServerConfigSnapshot(
            ServerPlayer player,
            ServerConfigSnapshotPayload.Result result
    ) {
        PacketDistributor.sendToPlayer(
                player,
                new ServerConfigSnapshotPayload(
                        CloakingServerSettings.fromConfig(),
                        canEditServerConfig(player),
                        result
                )
        );
    }
}
