package net.fireboy.aerocloakingcore;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.fireboy.aerocloakingcore.block.ModBlocks;
import net.fireboy.aerocloakingcore.block.entity.ModBlockEntities;
import net.fireboy.aerocloakingcore.item.ModItems;

import net.minecraft.resources.ResourceLocation;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Main entry point for Aero Cloaking Core.
 *
 * Handles registration of the mod's blocks, items and block entities,
 * along with integrations that need to run during common setup.
 */
@Mod(AeroCloakingCore.MOD_ID)
public class AeroCloakingCore {

    /**
     * The namespace used by all Aero Cloaking Core resources and registry entries.
     */
    public static final String MOD_ID = "aerocloakingcore";

    /**
     * Logger shared throughout the mod.
     */
    public static final Logger LOGGER = LogUtils.getLogger();


    /**
     * Creates a ResourceLocation inside the Aero Cloaking Core namespace.
     *
     * Example:
     * path("cloaking_core")
     * becomes
     * aerocloakingcore:cloaking_core
     *
     * @param path resource path
     * @return namespaced ResourceLocation
     */
    public static ResourceLocation path(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }


    /**
     * Called by NeoForge when the mod is loaded.
     *
     * All deferred registers belonging to the mod are attached
     * to the mod event bus here.
     *
     * @param modEventBus the event bus for this mod
     */
    public AeroCloakingCore(IEventBus modEventBus) {

        // Register lifecycle events.
        modEventBus.addListener(this::commonSetup);

        // Register all Aero Cloaking Core content.
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);

        LOGGER.info("Loading Aero Cloaking Core");
    }


    /**
     * Runs during NeoForge's common setup stage.
     *
     * The Simulated creative tab is populated here because its
     * registries and creative-tab structures are available by this point.
     *
     * enqueueWork is used so the integration is performed safely
     * on NeoForge's main setup thread.
     */
    private void commonSetup(final FMLCommonSetupEvent event) {

        event.enqueueWork(() -> {

            // Add the Cloaking Core item to our section
            // of Simulated's creative mode tab.
            SimulatedTabIntegration.add(
                    ModItems.CLOAKING_CORE.getId(),
                    ModItems.CLOAKING_CORE::get
            );

            LOGGER.info("Registered Aero Cloaking Core Simulated integration");
        });
    }
}