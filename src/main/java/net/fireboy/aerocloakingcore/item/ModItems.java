package net.fireboy.aerocloakingcore.item;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.fireboy.aerocloakingcore.block.ModBlocks;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

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
}