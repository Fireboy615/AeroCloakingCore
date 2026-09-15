package net.fireboy.aerocloakingcore.mixin.client;

import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;

import net.fireboy.aerocloakingcore.network.CloakingClient;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sable puts Flywheel block-entity visuals belonging to each sublevel inside
 * a dedicated VisualEmbedding.  Those visuals bypass Sable's vanilla/fancy
 * sublevel block renderer, so the existing cloak render hooks never see them.
 *
 * This compatibility mixin runs after Sable has refreshed the embedding's
 * transform for the frame.  When the local viewer sees that sublevel as fully
 * cloaked, its Flywheel embedding is moved well outside the view frustum.
 *
 * On the next frame Sable always writes the real transform first, so lowering
 * the cloak strength (including proximity reveal / visible-while-aboard) makes
 * the Flywheel visuals return without recreating their instances.
 */
@Mixin(
        targets = "dev.engine_room.flywheel.impl.visualization.storage.BlockEntityStorage",
        priority = 900,
        remap = false
)
public abstract class FlywheelBlockEntityStorageMixin {

    @Unique
    private static final float AEROCLOAKINGCORE$HIDDEN_Y = -1_000_000.0F;

    /**
     * This method is added to BlockEntityStorage by Sable's Flywheel
     * compatibility mixin.  Sable's mixin uses the default priority (1000),
     * while this mixin uses 900, so the injected method exists before this
     * injection is applied.
     *
     * require = 0 keeps the mod from hard-crashing if Sable changes this
     * internal compatibility method in a future version.
     */
    @Inject(
            method = "sable$updateEmbeddingTransforms(Ldev/engine_room/flywheel/api/visualization/VisualizationContext;Ldev/ryanhcode/sable/sublevel/ClientSubLevel;Ldev/engine_room/flywheel/api/visualization/VisualEmbedding;)V",
            at = @At("TAIL"),
            require = 0,
            remap = false
    )
    private void aerocloakingcore$applyFlywheelFullCloak(
            VisualizationContext visualizationContext,
            ClientSubLevel subLevel,
            VisualEmbedding embedding,
            CallbackInfo ci
    ) {
        if (!CloakingClient.shouldHideSubLevel(subLevel)) {
            return;
        }

        /*
         * Do not delete/recreate Flywheel instances.  Moving the embedding is
         * cheap, reversible next frame, and does not interfere with Create's
         * kinetic animation state.
         */
        Matrix4f hiddenPose = new Matrix4f().translation(
                0.0F,
                AEROCLOAKINGCORE$HIDDEN_Y,
                0.0F
        );

        embedding.transforms(
                hiddenPose,
                new Matrix3f()
        );
    }
}
