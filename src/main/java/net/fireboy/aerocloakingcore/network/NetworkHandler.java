package net.fireboy.aerocloakingcore.network;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(
        modid = "aerocloakingcore"
)
public final class NetworkHandler {

    private NetworkHandler() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {

        event.registrar("1")
                .playToClient(
                        CloakingSyncPayload.TYPE,
                        CloakingSyncPayload.STREAM_CODEC,
                        (payload, context) -> {

                            CloakingClient.setCloakedSubLevels(
                                    payload.subLevels()
                            );
                        }
                );
    }
}