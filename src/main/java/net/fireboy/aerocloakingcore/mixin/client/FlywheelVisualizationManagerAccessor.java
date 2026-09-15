package net.fireboy.aerocloakingcore.mixin.client;

import dev.engine_room.flywheel.api.backend.RenderContext;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Calls Flywheel's already-prepared engine render directly for the late ALPHA
 * pass.  This avoids invoking the public RenderDispatcher callback a second
 * time after its documented before-crumbling phase has already completed.
 */
@Mixin(
        targets = "dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl",
        remap = false
)
public interface FlywheelVisualizationManagerAccessor {

    @Invoker("render")
    void aerocloakingcore$renderPreparedFrame(RenderContext context);
}
