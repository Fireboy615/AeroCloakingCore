package net.fireboy.aerocloakingcore.block;

import net.fireboy.aerocloakingcore.block.entity.CloakingCoreBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;

public class CloakingCoreBlock extends BaseEntityBlock {

    public static final MapCodec<CloakingCoreBlock> CODEC =
            simpleCodec(CloakingCoreBlock::new);

    public CloakingCoreBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hit) {

        if (!level.isClientSide) {

            BlockEntity blockEntity = level.getBlockEntity(pos);

            if (blockEntity instanceof CloakingCoreBlockEntity core) {

                core.toggle();

                player.displayClientMessage(
                        Component.literal(
                                core.isActive()
                                        ? "Cloaking Core: ON"
                                        : "Cloaking Core: OFF"
                        ),
                        true
                );
            }
        }

        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockEntity newBlockEntity(
            BlockPos pos,
            BlockState state) {

        return new CloakingCoreBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> type) {

        if (level instanceof ServerLevel) {

            return (level0, pos, state0, blockEntity) -> {

                if (blockEntity instanceof CloakingCoreBlockEntity core) {
                    core.serverTick();
                }
            };
        }

        return null;
    }
}