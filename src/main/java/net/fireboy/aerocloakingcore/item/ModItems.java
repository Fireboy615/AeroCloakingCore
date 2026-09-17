package net.fireboy.aerocloakingcore.item;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.fireboy.aerocloakingcore.block.ModBlocks;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(AeroCloakingCore.MOD_ID);

    public static final DeferredItem<BlockItem> CLOAKING_CORE =
            ITEMS.register(
                    "cloaking_core",
                    () -> new BlockItem(
                            ModBlocks.CLOAKING_CORE.get(),
                            new Item.Properties()
                    )
            );

    public static final DeferredItem<SublevelCompassItem> SUBLEVEL_COMPASS =
            ITEMS.register(
                    "sublevel_compass",
                    () -> new SublevelCompassItem(
                            new Item.Properties()
                                    .stacksTo(1)
                                    .rarity(Rarity.UNCOMMON)
                    )
            );

    public static final DeferredItem<SublevelScannerItem> SUBLEVEL_SCANNER =
            ITEMS.register(
                    "sublevel_scanner",
                    () -> new SublevelScannerItem(
                            new Item.Properties()
                                    .stacksTo(1)
                                    .rarity(Rarity.EPIC)
                    )
            );
}
