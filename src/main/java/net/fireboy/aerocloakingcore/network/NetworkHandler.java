package net.fireboy.aerocloakingcore.network;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.fireboy.aerocloakingcore.menu.CloakingCoreMenu;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = AeroCloakingCore.MOD_ID)
public final class NetworkHandler {

    private NetworkHandler() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("3");

        registrar.playToClient(
                CloakingSyncPayload.TYPE,
                CloakingSyncPayload.STREAM_CODEC,
                (payload, context) -> CloakingClient.setCloakStates(
                        payload.entries(),
                        payload.serverSettings()
                )
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
                            payload.renderMode()
                    );
                }
        );
    }
}
