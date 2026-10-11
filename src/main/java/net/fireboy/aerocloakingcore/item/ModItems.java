package net.fireboy.aerocloakingcore.item;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.fireboy.aerocloakingcore.block.ModBlocks;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyItem;

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

    /**
     * Work-in-progress scanner for Create Sequenced Assembly. Deliberately
     * excluded from the normal creative tab: it only represents progress
     * through the scanner's manufacturing process.
     */
    public static final DeferredItem<SequencedAssemblyItem> INCOMPLETE_SUBLEVEL_SCANNER =
            ITEMS.register(
                    "incomplete_sublevel_scanner",
                    () -> new SequencedAssemblyItem(new Item.Properties().stacksTo(1))
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
