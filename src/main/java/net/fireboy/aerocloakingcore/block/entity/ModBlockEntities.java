package net.fireboy.aerocloakingcore.block.entity;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.fireboy.aerocloakingcore.block.ModBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(
                    Registries.BLOCK_ENTITY_TYPE,
                    AeroCloakingCore.MOD_ID
            );

    public static final DeferredHolder<
            BlockEntityType<?>,
            BlockEntityType<CloakingCoreBlockEntity>
            > CLOAKING_CORE =
            BLOCK_ENTITIES.register(
                    "cloaking_core",
                    () -> BlockEntityType.Builder.of(
                            CloakingCoreBlockEntity::new,
                            ModBlocks.CLOAKING_CORE.get()
                    ).build(null)
            );
}