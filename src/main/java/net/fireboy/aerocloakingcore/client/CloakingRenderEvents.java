package net.fireboy.aerocloakingcore.client;

import net.fireboy.aerocloakingcore.network.CloakingClient;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;

import net.fireboy.aerocloakingcore.AeroCloakingCore;

@EventBusSubscriber(
        modid = AeroCloakingCore.MOD_ID,
        value = net.neoforged.api.distmarker.Dist.CLIENT,
        bus = EventBusSubscriber.Bus.GAME
)
public final class CloakingRenderEvents {

    private CloakingRenderEvents() {
    }

    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {

        Player player = event.getEntity();

        if (CloakingClient.shouldHidePlayer(player)) {
            event.setCanceled(true);
        }
    }
}