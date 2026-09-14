package net.fireboy.aerocloakingcore.block;

import com.mojang.serialization.MapCodec;
import net.fireboy.aerocloakingcore.block.entity.CloakingCoreBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

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
            BlockHitResult hit
    ) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);

        if (!(blockEntity instanceof CloakingCoreBlockEntity core)) {
            return InteractionResult.PASS;
        }

        if (player.isShiftKeyDown()) {
            float strength = core.toggleManualCloak();

            player.displayClientMessage(
                    Component.translatable(
                            "message.aerocloakingcore.manual_cloak",
                            Math.round(strength * 100.0F)
                    ),
                    true
            );

            return InteractionResult.SUCCESS;
        }

        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(
                    core,
                    buffer -> {
                        core.getSettings().write(buffer);
                        ItemStack.OPTIONAL_STREAM_CODEC.encode(
                                buffer,
                                core.getLinkFrequency(true)
                        );
                        ItemStack.OPTIONAL_STREAM_CODEC.encode(
                                buffer,
                                core.getLinkFrequency(false)
                        );
                    }
            );
        }

        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CloakingCoreBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> type
    ) {
        if (level instanceof ServerLevel) {
            return (level0, pos, state0, blockEntity) -> {
                if (blockEntity instanceof CloakingCoreBlockEntity core) {
                    core.serverTick();
                }
            };
        }

        return null;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}
