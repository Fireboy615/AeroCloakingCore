package net.fireboy.aerocloakingcore.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.api.equipment.goggles.IProxyHoveringInformation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.BlockGetter;

import org.jetbrains.annotations.Nullable;

/**
 * Inert, item-less second half of the two-block Cloaking Core.
 *
 * Like Create Connected's Kinetic Bridge destination, this is a separate
 * registered Block, not a second KineticBlock / block entity. Only the drive
 * block has the Create shaft and consumes SU.
 */
public final class CloakingCoreChamberBlock extends Block implements IWrenchable, IProxyHoveringInformation {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    public CloakingCoreChamberBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    public static BlockPos drivePos(BlockPos chamberPos, BlockState chamber) {
        return chamberPos.relative(chamber.getValue(FACING).getOpposite());
    }

    /**
     * Create Goggles check the block entity at the selected position.
     * The inert chamber has none, so proxy the overlay to the kinetic drive
     * just as Create does for Large Water Wheel structural blocks.
     */
    @Override
    public BlockPos getInformationSource(Level level, BlockPos pos, BlockState state) {
        return hasDrive(level, pos, state) ? drivePos(pos, state) : pos;
    }

    public static boolean hasDrive(LevelReader level, BlockPos pos, BlockState chamber) {
        BlockPos drivePos = drivePos(pos, chamber);
        BlockState drive = level.getBlockState(drivePos);
        return drive.is(ModBlocks.CLOAKING_CORE.get())
                && !drive.getValue(CloakingCoreBlock.CHAMBER)
                && drive.getValue(CloakingCoreBlock.FACING) == chamber.getValue(FACING);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        // This block has no item and must only be constructed by its drive.
        return null;
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide && !oldState.is(this)) {
            level.scheduleTick(pos, this, 2);
        }
    }

    @Override
    public BlockState updateShape(
            BlockState state, Direction direction, BlockState neighbor,
            LevelAccessor level, BlockPos pos, BlockPos neighborPos
    ) {
        if (!level.isClientSide() && direction == state.getValue(FACING).getOpposite()
                && !hasDrive(level, pos, state)) {
            level.scheduleTick(pos, this, 2);
        }
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        BlockPos source = drivePos(pos, state);
        if (!level.isLoaded(source)) {
            // Never delete the frame when its partner happens to be in a
            // neighbouring chunk that has not finished loading.
            level.scheduleTick(pos, this, 20);
            return;
        }

        if (!hasDrive(level, pos, state)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean movedByPiston) {
        // Breaking the frame also removes the Create-powered drive half, which
        // owns the sole item drop. The frame itself has an empty loot table.
        if (!level.isClientSide && !newState.is(this) && hasDrive(level, pos, state)) {
            level.destroyBlock(drivePos(pos, state), true);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // Creative dismantling must not spawn an item from the drive.
        if (!level.isClientSide && player.isCreative() && hasDrive(level, pos, state)) {
            level.destroyBlock(drivePos(pos, state), false);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!hasDrive(level, pos, state)) {
            return InteractionResult.PASS;
        }
        BlockPos origin = drivePos(pos, state);
        return ModBlocks.CLOAKING_CORE.get().useWithoutItem(
                level.getBlockState(origin), level, origin, player, hit
        );
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        // A simple Create wrench rotation would rotate only this one half.
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!hasDrive(level, pos, state)) {
            return InteractionResult.PASS;
        }
        BlockPos origin = drivePos(pos, state);
        BlockHitResult redirectedHit = new BlockHitResult(
                context.getClickLocation(), context.getClickedFace(), origin, context.isInside()
        );
        UseOnContext redirected = new UseOnContext(
                level, context.getPlayer(), context.getHand(), context.getItemInHand(), redirectedHit
        );
        return ModBlocks.CLOAKING_CORE.get().onSneakWrenched(
                level.getBlockState(origin), redirected
        );
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(ModBlocks.CLOAKING_CORE.get());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return CloakingCoreBlock.chamberShape(state.getValue(FACING));
    }
}
