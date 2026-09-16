package net.fireboy.aerocloakingcore.mixin.client;

import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;

import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.fireboy.aerocloakingcore.client.FlywheelCloakEmbedding;
import net.fireboy.aerocloakingcore.client.FlywheelAlphaRenderState;
import net.fireboy.aerocloakingcore.network.CloakingClient;

import net.minecraft.core.Vec3i;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds AeroCloakingCore state to Sable's per-sublevel Flywheel embedding.
 *
 * v7.1 deliberately does NOT use @ModifyArg against Sable's injected method.
 * That was fragile at mixin-application time and could prevent Minecraft from
 * reaching the main menu. Instead, after Sable has finished updating the
 * embedding, we reproduce the same rigid transform and submit it one more time
 * with AeroCloakingCore's signed cloak signal encoded in normal-matrix scale.
 */
@Mixin(
        targets = "dev.engine_room.flywheel.impl.visualization.storage.BlockEntityStorage",
        priority = 900,
        remap = false
)
public abstract class FlywheelBlockEntityStorageMixin {

    @Unique
    private static final float AEROCLOAKINGCORE$HIDDEN_Y = -1_000_000.0F;

    @Unique
    private float aerocloakingcore$currentFlywheelCloakStrength = 0.0F;

    /**
     * Update the value used by the already-working instancing backend before
     * Sable updates this embedding.
     */
    @Inject(
            method = "sable$updateEmbeddingTransforms(Ldev/engine_room/flywheel/api/visualization/VisualizationContext;Ldev/ryanhcode/sable/sublevel/ClientSubLevel;Ldev/engine_room/flywheel/api/visualization/VisualEmbedding;)V",
            at = @At("HEAD"),
            require = 0,
            remap = false
    )
    private void aerocloakingcore$updateFlywheelCloakStrength(
            VisualizationContext visualizationContext,
            ClientSubLevel subLevel,
            VisualEmbedding embedding,
            CallbackInfo ci
    ) {
        float strength = Math.max(
                0.0F,
                Math.min(1.0F, CloakingClient.getViewerCloakStrength(subLevel))
        );

        CloakRenderMode renderMode = CloakingClient.getRenderMode(subLevel);

        /*
         * Signed Flywheel cloak signal:
         *   0..+1 = DITHER strength
         *   0..-1 = ALPHA strength
         *
         * A tiny ALPHA value is treated as fully visible so we never suppress
         * the normal Flywheel pass without scheduling the late alpha pass.
         */
        float signal = 0.0F;

        if (renderMode == CloakRenderMode.DITHER) {
            signal = strength;
        } else if (renderMode.isAlpha()
                && strength > 0.0001F) {
            signal = -strength;

            // At full cloak the existing far-away transform fallback is
            // sufficient; no second translucent Flywheel pass is needed.
            if (strength < 0.9999F) {
                FlywheelAlphaRenderState.markAlphaActive();
            }
        }

        aerocloakingcore$currentFlywheelCloakStrength = signal;

        if (embedding instanceof FlywheelCloakEmbedding cloakEmbedding) {
            cloakEmbedding.aerocloakingcore$setFlywheelCloakStrength(signal);
        }
    }

    /**
     * INDIRECT BACKEND TRANSPORT
     *
     * Sable has already called embedding.transforms() by TAIL. Reconstruct the
     * exact same rigid transform from the same inputs Sable uses. DITHER is
     * encoded with normal-matrix scale 1..2; ALPHA uses 1..0.5. The indirect
     * shader decodes the signed signal and removes the scale before lighting,
     * so the visible normal direction is unchanged.
     */
    @Inject(
            method = "sable$updateEmbeddingTransforms(Ldev/engine_room/flywheel/api/visualization/VisualizationContext;Ldev/ryanhcode/sable/sublevel/ClientSubLevel;Ldev/engine_room/flywheel/api/visualization/VisualEmbedding;)V",
            at = @At("TAIL"),
            require = 0,
            remap = false
    )
    private void aerocloakingcore$applyFlywheelCloakTransform(
            VisualizationContext visualizationContext,
            ClientSubLevel subLevel,
            VisualEmbedding embedding,
            CallbackInfo ci
    ) {
        // Keep the proven hard-hide fallback at full cloak.
        if (CloakingClient.shouldHideSubLevel(subLevel)) {
            Matrix4f hiddenPose = new Matrix4f().translation(
                    0.0F,
                    AEROCLOAKINGCORE$HIDDEN_Y,
                    0.0F
            );
            embedding.transforms(hiddenPose, new Matrix3f());
            return;
        }

        Pose3dc renderPose = subLevel.renderPose();
        Vector3dc rotationPoint = renderPose.rotationPoint();
        Vector3dc position = renderPose.position();

        Matrix4f transformation = new Matrix4f();
        Vec3i parentOrigin = visualizationContext.renderOrigin();

        transformation.setTranslation(
                (float) (position.x() - parentOrigin.getX()),
                (float) (position.y() - parentOrigin.getY()),
                (float) (position.z() - parentOrigin.getZ())
        );

        transformation.rotate(new Quaternionf().set(renderPose.orientation()));

        Vec3i localOrigin = embedding.renderOrigin();
        Vector3d localOffset = rotationPoint.sub(
                localOrigin.getX(),
                localOrigin.getY(),
                localOrigin.getZ(),
                new Vector3d()
        );

        transformation.translate(
                (float) -localOffset.x,
                (float) -localOffset.y,
                (float) -localOffset.z
        );

        Matrix3f normal = transformation.normal(new Matrix3f());

        if (aerocloakingcore$currentFlywheelCloakStrength > 0.0F) {
            // DITHER: encode +strength as a normal-column length of 1..2.
            normal.scale(1.0F + aerocloakingcore$currentFlywheelCloakStrength);
        } else if (aerocloakingcore$currentFlywheelCloakStrength < 0.0F) {
            // ALPHA: encode -strength as a normal-column length of 1..0.5.
            // The indirect shader removes this scale before lighting.
            normal.scale(
                    1.0F
                            + 0.5F
                            * aerocloakingcore$currentFlywheelCloakStrength
            );
        }

        embedding.transforms(transformation, normal);
    }
}
