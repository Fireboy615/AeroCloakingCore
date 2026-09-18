package net.fireboy.aerocloakingcore.mixin.compat;

import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes Blaze Burner collision on Sable sublevels match normal-world player
 * collision.
 *
 * Create intentionally returns a short base-only shape when queried with
 * CollisionContext.empty(). Sable's entity/sublevel collision path calls
 * BlockState#getCollisionShape(BlockGetter, BlockPos), which uses that empty
 * context, so the upper burner cage becomes non-solid on a sublevel.
 *
 * Sable also uses an empty collision context while baking Rapier colliders.
 * For either Sable path, use Create's normal full burner shape instead.
 */
@Mixin(BlazeBurnerBlock.class)
public abstract class BlazeBurnerSubLevelCollisionMixin {

    private static final StackWalker AEROCLOAKINGCORE_STACK_WALKER =
            StackWalker.getInstance();

    @Inject(
            method = "getCollisionShape",
            at = @At("HEAD"),
            cancellable = true
    )
    private void aerocloakingcore$useFullShapeForSable(
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            CollisionContext context,
            CallbackInfoReturnable<VoxelShape> cir
    ) {
        // Create only returns its base-only special shape for the shared empty
        // context. Do not alter normal world/player collision queries.
        if (context != CollisionContext.empty()) {
            return;
        }

        if (!aerocloakingcore$isSableCollisionQuery(level)) {
            return;
        }

        BlazeBurnerBlock burner = (BlazeBurnerBlock) (Object) this;
        cir.setReturnValue(burner.getShape(state, level, pos, context));
    }

    private static boolean aerocloakingcore$isSableCollisionQuery(BlockGetter level) {
        // Player/entity collision against sublevels comes through
        // dev.ryanhcode.sable.util.LevelAccelerator. This is the path the
        // previous physics-bake-only fix missed.
        if (level != null && level.getClass().getName().startsWith("dev.ryanhcode.sable.")) {
            return true;
        }

        // The Rapier collider bakery can pass a BlockGetter whose concrete
        // class is not itself in the Sable namespace, so retain a stack-based
        // fallback for that path and for future Sable wrappers.
        return AEROCLOAKINGCORE_STACK_WALKER.walk(frames ->
                frames.anyMatch(frame -> frame.getClassName().startsWith("dev.ryanhcode.sable."))
        );
    }
}
