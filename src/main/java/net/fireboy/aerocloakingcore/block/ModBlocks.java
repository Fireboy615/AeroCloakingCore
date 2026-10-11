package net.fireboy.aerocloakingcore.block;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import net.minecraft.world.level.block.state.BlockBehaviour;

public class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(AeroCloakingCore.MOD_ID);

    /**
     * The companion frame has no item and no kinetic block entity.
     * Registered separately, as with Create Connected's Kinetic Bridge.
     */
    public static final DeferredBlock<CloakingCoreChamberBlock> CLOAKING_CORE_CHAMBER =
            BLOCKS.register(
                    "cloaking_core_chamber",
                    () -> new CloakingCoreChamberBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(3.0F)
                                    .requiresCorrectToolForDrops()
                                    .noOcclusion()
                    )
            );

    public static final DeferredBlock<CloakingCoreBlock> CLOAKING_CORE =
            BLOCKS.register(
                    "cloaking_core",
                    () -> new CloakingCoreBlock(
                            BlockBehaviour.Properties.of()
                                    .strength(3.0F)
                                    .requiresCorrectToolForDrops()
                                    .noOcclusion()
                    )
            );
}