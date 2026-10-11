package net.fireboy.aerocloakingcore.block;

import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.createmod.catnip.math.VoxelShaper;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.foundation.block.IBE;

import net.fireboy.aerocloakingcore.block.entity.CloakingCoreBlockEntity;
import net.fireboy.aerocloakingcore.block.entity.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.BlockGetter;
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
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

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

    // The full Blockbench casing is rendered only by the drive, but both
    // physical block spaces need their OWN hitboxes. These follow the actual
    // rear machinery, four frame rails and front frame (all coordinates 0..16).
    private static final VoxelShape CORNER_RAILS = Shapes.or(
            Block.box(0, 0, 0, 2, 2, 16),
            Block.box(0, 14, 0, 2, 16, 16),
            Block.box(14, 0, 0, 16, 2, 16),
            Block.box(14, 14, 0, 16, 16, 16)
    );
    private static final VoxelShape DRIVE_SOUTH = Shapes.or(
            CORNER_RAILS,
            Block.box(0, 0, 0, 2, 16, 2),
            Block.box(14, 0, 0, 16, 16, 2),
            Block.box(2, 0, 0, 14, 2, 2),
            Block.box(2, 14, 0, 14, 16, 2),
            Block.box(2, 1, 2, 14, 15, 12)
    );
    private static final VoxelShape CHAMBER_SOUTH = Shapes.or(
            CORNER_RAILS,
            Block.box(0, 0, 14, 2, 16, 16),
            Block.box(14, 0, 14, 16, 16, 16),
            Block.box(2, 0, 14, 14, 2, 16),
            Block.box(2, 14, 14, 14, 16, 16)
    );
    private static final VoxelShaper DRIVE_SHAPES =
            VoxelShaper.forDirectional(DRIVE_SOUTH, Direction.SOUTH);
    private static final VoxelShaper CHAMBER_SHAPES =
            VoxelShaper.forDirectional(CHAMBER_SOUTH, Direction.SOUTH);

    public static VoxelShape chamberShape(Direction facing) {
        return CHAMBER_SHAPES.get(facing);
    }

    private static boolean matchesDrive(BlockState drive, Direction facing) {
        return drive.is(ModBlocks.CLOAKING_CORE.get())
                && !drive.getValue(CHAMBER)
                && drive.getValue(FACING) == facing;
    }

    private static boolean matchesChamber(BlockState chamber, Direction facing) {
        return (chamber.is(ModBlocks.CLOAKING_CORE_CHAMBER.get())
                    && chamber.getValue(CloakingCoreChamberBlock.FACING) == facing)
                || (chamber.is(ModBlocks.CLOAKING_CORE.get())
                    && chamber.getValue(CHAMBER)
                    && chamber.getValue(FACING) == facing);
    }

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

        if (context.getLevel().isOutsideBuildHeight(chamberPos)
                || !context.getLevel().getWorldBorder().isWithinBounds(chamberPos)
                || !context.getLevel().getBlockState(chamberPos).canBeReplaced()) {
            return null;
        }

        BlockState companion = ModBlocks.CLOAKING_CORE_CHAMBER.get()
                .defaultBlockState()
                .setValue(CloakingCoreChamberBlock.FACING, facing);
        if (!context.getLevel().isUnobstructed(
                companion, chamberPos, CollisionContext.empty())) {
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

        BlockState current = level.getBlockState(chamberPos);
        if (!current.canBeReplaced()) {
            // The destination was occupied after our placement precheck.
            // Never leave a half-built kinetic machine behind.
            level.destroyBlock(pos, true);
            return;
        }

        BlockState chamberState = ModBlocks.CLOAKING_CORE_CHAMBER.get()
                .defaultBlockState()
                .setValue(CloakingCoreChamberBlock.FACING, facing);

        if (!level.setBlock(chamberPos, chamberState, Block.UPDATE_ALL)) {
            level.destroyBlock(pos, true);
        }
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

    private boolean isMatchingOtherHalf(BlockState state, BlockState otherState) {
        Direction facing = state.getValue(FACING);
        return state.getValue(CHAMBER)
                ? matchesDrive(otherState, facing)
                : matchesChamber(otherState, facing);
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
        boolean pairingChanged = !newState.is(this)
                || (newState.hasProperty(CHAMBER)
                    && (state.getValue(CHAMBER) != newState.getValue(CHAMBER)
                        || state.getValue(FACING) != newState.getValue(FACING)));

        if (!level.isClientSide && pairingChanged && !REMOVING_OTHER_HALF.get()) {
            BlockPos otherPos = getOtherHalfPos(pos, state);
            BlockState otherState = level.getBlockState(otherPos);

            if (isMatchingOtherHalf(state, otherState)) {
                REMOVING_OTHER_HALF.set(true);
                try {
                    if (state.getValue(CHAMBER)) {
                        // A pre-upgrade, legacy chamber was itself a
                        // CloakingCoreBlock. Break its source for one item.
                        level.destroyBlock(otherPos, true);
                    } else {
                        // The drive owns the only loot drop; its companion
                        // is a structural placeholder and drops nothing.
                        level.setBlock(
                                otherPos, Blocks.AIR.defaultBlockState(),
                                Block.UPDATE_ALL
                        );
                    }
                } finally {
                    REMOVING_OTHER_HALF.set(false);
                }
            }
        }

        // Keep Create's kinetic network cleanup on the drive block.
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative() && state.getValue(CHAMBER)) {
            BlockPos drivePos = getDrivePos(pos, state);
            if (matchesDrive(level.getBlockState(drivePos), state.getValue(FACING))) {
                REMOVING_OTHER_HALF.set(true);
                try {
                    level.destroyBlock(drivePos, false);
                } finally {
                    REMOVING_OTHER_HALF.set(false);
                }
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
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
    // TWO-BLOCK CONSISTENCY (CREATE CONNECTED BRIDGE PATTERN)
    // ---------------------------------------------------------------------

    @Override
    public void onPlace(
            BlockState state, Level level, BlockPos pos,
            BlockState oldState, boolean movedByPiston
    ) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide && !oldState.equals(state)) {
            level.scheduleTick(pos, this, 2);
        }
    }

    @Override
    public BlockState updateShape(
            BlockState state, Direction direction, BlockState neighbor,
            LevelAccessor level, BlockPos pos, BlockPos neighborPos
    ) {
        Direction linkedSide = state.getValue(CHAMBER)
                ? state.getValue(FACING).getOpposite()
                : state.getValue(FACING);

        if (!level.isClientSide() && direction == linkedSide) {
            // Schedule instead of deleting immediately. BlockItem places the
            // drive and its frame in two writes, and neighbour updates can
            // arrive while only one half exists.
            level.scheduleTick(pos, this, 2);
        }
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        BlockPos otherPos = getOtherHalfPos(pos, state);
        if (!level.isLoaded(otherPos)) {
            level.scheduleTick(pos, this, 20);
            return;
        }

        BlockState other = level.getBlockState(otherPos);
        if (state.getValue(CHAMBER)) {
            if (!matchesDrive(other, state.getValue(FACING))) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                return;
            }

            // Existing saves may contain the old chamber=true state. Convert
            // it to the inert companion without removing its kinetic drive.
            REMOVING_OTHER_HALF.set(true);
            try {
                level.setBlock(pos, ModBlocks.CLOAKING_CORE_CHAMBER.get()
                        .defaultBlockState()
                        .setValue(CloakingCoreChamberBlock.FACING, state.getValue(FACING)),
                        Block.UPDATE_ALL);
            } finally {
                REMOVING_OTHER_HALF.set(false);
            }
            return;
        }

        if (matchesChamber(other, state.getValue(FACING))) {
            return;
        }

        // Restore a missing half only when its position is actually free.
        // An obstructed destination breaks the entire machine instead of
        // leaving a half-core still connected to Create's kinetic network.
        if (level.isOutsideBuildHeight(otherPos)
                || !level.getWorldBorder().isWithinBounds(otherPos)
                || !other.canBeReplaced()) {
            level.destroyBlock(pos, true);
            return;
        }

        level.setBlock(otherPos, ModBlocks.CLOAKING_CORE_CHAMBER.get()
                .defaultBlockState()
                .setValue(CloakingCoreChamberBlock.FACING, state.getValue(FACING)),
                Block.UPDATE_ALL);
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        // Rotating the kinetic half independently would orphan its companion.
        return InteractionResult.PASS;
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
    protected VoxelShape getShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx
    ) {
        Direction facing = state.getValue(FACING);
        return state.getValue(CHAMBER)
                ? chamberShape(facing)
                : DRIVE_SHAPES.get(facing);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}
