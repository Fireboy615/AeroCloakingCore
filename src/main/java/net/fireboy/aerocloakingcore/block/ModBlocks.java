package net.fireboy.aerocloakingcore.block;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(AeroCloakingCore.MOD_ID);

    public static final DeferredBlock<CloakingCoreBlock> CLOAKING_CORE =
            BLOCKS.register(
                    "cloaking_core",
                    () -> new CloakingCoreBlock(
                            net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()
                                    .strength(3.0F)
                                    .requiresCorrectToolForDrops()
                    )
            );
}