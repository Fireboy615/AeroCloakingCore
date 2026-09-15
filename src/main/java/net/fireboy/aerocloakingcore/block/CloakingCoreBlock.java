package net.fireboy.aerocloakingcore.block;

import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.foundation.block.IBE;

import net.fireboy.aerocloakingcore.block.entity.CloakingCoreBlockEntity;
import net.fireboy.aerocloakingcore.block.entity.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

import org.jetbrains.annotations.Nullable;

/**
 * Two-block Cloaking Core.
 *
 * The drive half is a real Create kinetic consumer. Rotation enters through
 * the rear face, along the core's long axis. The chamber half is structural
 * only and deliberately has no block entity / kinetic connection.
 */
public class CloakingCoreBlock extends KineticBlock
        implements IBE<CloakingCoreBlockEntity> {

    /** Direction from DRIVE -> CHAMBER. */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    /** false = drive / kinetic half, true = chamber half. */
    public static final BooleanProperty CHAMBER = BooleanProperty.create("chamber");

    private static final ThreadLocal<Boolean> REMOVING_OTHER_HALF =
            ThreadLocal.withInitial(() -> false);

    public CloakingCoreBlock(Properties properties) {
        super(properties);

        registerDefaultState(
                stateDefinition.any()
                        .setValue(FACING, Direction.SOUTH)
                        .setValue(CHAMBER, false)
        );
    }

    // ---------------------------------------------------------------------
    // CREATE KINETICS
    // ---------------------------------------------------------------------

    /**
     * The shaft connects only to the rear of the drive half.
     *
     * FACING points from the drive into the chamber, so the input shaft is on
     * FACING.getOpposite().
     */
    @Override
    public boolean hasShaftTowards(
            LevelReader world,
            BlockPos pos,
            BlockState state,
            Direction face
    ) {
        return !state.getValue(CHAMBER)
                && face == state.getValue(FACING).getOpposite();
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    /**
     * Use Create's MEDIUM speed tier as the minimum operating speed.
     * This is 32 RPM with Create's normal server settings.
     */
    @Override
    public IRotate.SpeedLevel getMinimumRequiredSpeedLevel() {
        return IRotate.SpeedLevel.MEDIUM;
    }

    /**
     * Keep Create's wrench from rotating just one half of this two-block
     * machine. Sneak-wrench removal still works through IWrenchable; proper
     * two-block wrench rotation can be added later if we want it.
     */
    @Override
    public BlockState getRotatedBlockState(
            BlockState originalState,
            Direction targetedFace
    ) {
        return originalState;
    }

    @Override
    public Class<CloakingCoreBlockEntity> getBlockEntityClass() {
        return CloakingCoreBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends CloakingCoreBlockEntity> getBlockEntityType() {
        return ModBlockEntities.CLOAKING_CORE.get();
    }

    // ---------------------------------------------------------------------
    // BLOCK STATE
    // ---------------------------------------------------------------------

    @Override
    protected void createBlockStateDefinition(
            StateDefinition.Builder<Block, BlockState> builder
    ) {
        builder.add(FACING, CHAMBER);
    }

    // ---------------------------------------------------------------------
    // PLACEMENT
    // ---------------------------------------------------------------------

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getClickedFace();

        BlockPos drivePos = context.getClickedPos();
        BlockPos chamberPos = drivePos.relative(facing);

        if (!context.getLevel().getBlockState(chamberPos).isAir()) {
            return null;
        }

        return defaultBlockState()
                .setValue(FACING, facing)
                .setValue(CHAMBER, false);
    }

    @Override
    public void setPlacedBy(
            Level level,
            BlockPos pos,
            BlockState state,
            @Nullable LivingEntity placer,
            ItemStack stack
    ) {
        // Important: KineticBlock's implementation queues Create's rotation
        // indicators and handles its normal placement behaviour.
        super.setPlacedBy(level, pos, state, placer, stack);

        if (level.isClientSide || state.getValue(CHAMBER)) {
            return;
        }

        Direction facing = state.getValue(FACING);
        BlockPos chamberPos = pos.relative(facing);

        if (!level.getBlockState(chamberPos).isAir()) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            return;
        }

        BlockState chamberState = defaultBlockState()
                .setValue(FACING, facing)
                .setValue(CHAMBER, true);

        level.setBlock(chamberPos, chamberState, Block.UPDATE_ALL);
    }

    // ---------------------------------------------------------------------
    // TWO-BLOCK HELPERS
    // ---------------------------------------------------------------------

    private BlockPos getDrivePos(BlockPos pos, BlockState state) {
        if (!state.getValue(CHAMBER)) {
            return pos;
        }

        return pos.relative(state.getValue(FACING).getOpposite());
    }

    private BlockPos getOtherHalfPos(BlockPos pos, BlockState state) {
        Direction facing = state.getValue(FACING);
        return state.getValue(CHAMBER)
                ? pos.relative(facing.getOpposite())
                : pos.relative(facing);
    }

    private boolean isMatchingOtherHalf(
            BlockState state,
            BlockState otherState
    ) {
        if (!otherState.is(this)
                || !otherState.hasProperty(FACING)
                || !otherState.hasProperty(CHAMBER)) {
            return false;
        }

        return otherState.getValue(FACING) == state.getValue(FACING)
                && otherState.getValue(CHAMBER) != state.getValue(CHAMBER);
    }

    // ---------------------------------------------------------------------
    // BREAKING / REMOVAL
    // ---------------------------------------------------------------------

    @Override
    public void onRemove(
            BlockState state,
            Level level,
            BlockPos pos,
            BlockState newState,
            boolean movedByPiston
    ) {
        if (!state.is(newState.getBlock()) && !REMOVING_OTHER_HALF.get()) {
            BlockPos otherPos = getOtherHalfPos(pos, state);
            BlockState otherState = level.getBlockState(otherPos);

            if (isMatchingOtherHalf(state, otherState)) {
                REMOVING_OTHER_HALF.set(true);

                try {
                    level.setBlock(
                            otherPos,
                            Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_ALL
                    );
                } finally {
                    REMOVING_OTHER_HALF.set(false);
                }
            }
        }

        // KineticBlock.onRemove() performs Create's SmartBlockEntity cleanup
        // and detaches the drive half from the kinetic network.
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    // ---------------------------------------------------------------------
    // INTERACTION
    // ---------------------------------------------------------------------

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

        BlockPos drivePos = getDrivePos(pos, state);
        BlockState driveState = level.getBlockState(drivePos);

        if (!driveState.is(this) || driveState.getValue(CHAMBER)) {
            return InteractionResult.PASS;
        }

        BlockEntity blockEntity = level.getBlockEntity(drivePos);
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

    // ---------------------------------------------------------------------
    // BLOCK ENTITY
    // ---------------------------------------------------------------------

    /**
     * Only the drive half owns a block entity. The chamber half is purely the
     * second half of the model and must not become a second kinetic consumer.
     */
    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        if (state.getValue(CHAMBER)) {
            return null;
        }

        return getBlockEntityType().create(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> type
    ) {
        if (state.getValue(CHAMBER)) {
            return null;
        }

        return IBE.super.getTicker(level, state, type);
    }

    // ---------------------------------------------------------------------
    // RENDERING
    // ---------------------------------------------------------------------

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}
