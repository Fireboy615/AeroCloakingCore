package net.fireboy.aerocloakingcore.client;

import net.fireboy.aerocloakingcore.AeroCloakingCore;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/**
 * Sable 2.0.5 ships its own overrides for Flywheel's embedded shaders under
 * the flywheel namespace.  A normal mod-resource override can therefore lose
 * to Sable depending on resource-pack ordering.
 *
 * Register our coordinated Flywheel/Sable shader copies as a forced built-in
 * resource pack at TOP priority so the cloak additions are the final versions
 * Flywheel compiles.
 */
@EventBusSubscriber(
        modid = AeroCloakingCore.MOD_ID,
        value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.MOD
)
public final class FlywheelShaderOverridePack {

    private FlywheelShaderOverridePack() {
    }

    @SubscribeEvent
    public static void addPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }

        event.addPackFinders(
                AeroCloakingCore.path("resourcepacks/flywheel_cloak"),
                PackType.CLIENT_RESOURCES,
                Component.literal("Aero Cloaking Core - Flywheel Cloak Shaders"),
                PackSource.BUILT_IN,
                true,
                Pack.Position.TOP
        );

        AeroCloakingCore.LOGGER.info(
                "Registered forced top-priority Flywheel cloak shader pack"
        );
    }
}
