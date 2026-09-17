package net.fireboy.aerocloakingcore;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.fireboy.aerocloakingcore.block.ModBlocks;
import net.fireboy.aerocloakingcore.block.entity.ModBlockEntities;
import net.fireboy.aerocloakingcore.server.config.AeroCloakingCoreServerConfig;
import net.fireboy.aerocloakingcore.item.ModItems;
import net.fireboy.aerocloakingcore.menu.ModMenus;

import net.minecraft.resources.ResourceLocation;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Main entry point for Aero Cloaking Core.
 */
@Mod(AeroCloakingCore.MOD_ID)
public class AeroCloakingCore {

    public static final String MOD_ID = "aerocloakingcore";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static ResourceLocation path(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public AeroCloakingCore(
            IEventBus modEventBus,
            ModContainer modContainer
    ) {
        modEventBus.addListener(this::commonSetup);

        ModBlocks.BLOCKS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);

        // Server-authoritative cloak behaviour and reveal distances.
        modContainer.registerConfig(
                ModConfig.Type.SERVER,
                AeroCloakingCoreServerConfig.SPEC
        );

        LOGGER.info("Loading Aero Cloaking Core");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            SimulatedTabIntegration.add(
                    ModItems.CLOAKING_CORE.getId(),
                    ModItems.CLOAKING_CORE::get
            );
            SimulatedTabIntegration.add(
                    ModItems.SUBLEVEL_COMPASS.getId(),
                    ModItems.SUBLEVEL_COMPASS::get
            );
            SimulatedTabIntegration.add(
                    ModItems.SUBLEVEL_SCANNER.getId(),
                    ModItems.SUBLEVEL_SCANNER::get
            );

            LOGGER.info("Registered Aero Cloaking Core Simulated integration");
        });
    }
}
