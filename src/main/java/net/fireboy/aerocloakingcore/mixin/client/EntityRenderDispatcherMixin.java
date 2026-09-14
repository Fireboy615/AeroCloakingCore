package net.fireboy.aerocloakingcore.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.fireboy.aerocloakingcore.client.EntityCloakRenderState;
import net.fireboy.aerocloakingcore.network.CloakingClient;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/**
 * Gives entities the same viewer-specific cloak strength and render mode
 * as the Sable sublevel they belong to.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Unique
    private boolean aerocloakingcore$entityCloakActive;


    @Inject(
            method = "render",
            at = @At("HEAD"),
            cancellable = true
    )
    private void aerocloakingcore$beginEntityCloak(
            Entity entity,
            double x,
            double y,
            double z,
            float rotationYaw,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            CallbackInfo ci
    ) {

        aerocloakingcore$entityCloakActive = false;

        float cloakStrength =
                CloakingClient.getEntityViewerCloakStrength(entity);

        // Fully visible: normal entity rendering.
        if (cloakStrength <= 0.001F) {
            return;
        }

        // Fully cloaked: submit no entity geometry.
        if (cloakStrength >= 0.999F) {
            ci.cancel();
            return;
        }

        CloakRenderMode renderMode =
                CloakingClient.getEntityRenderMode(entity);

        /*
         * Flush anything already queued before enabling this entity's
         * per-core cloak state. Otherwise another entity could inherit it.
         */
        if (buffer instanceof MultiBufferSource.BufferSource bufferSource) {
            bufferSource.endBatch();

            EntityCloakRenderState.begin(
                    cloakStrength,
                    renderMode
            );

            aerocloakingcore$entityCloakActive = true;
        }
    }


    @Inject(
            method = "render",
            at = @At("TAIL")
    )
    private void aerocloakingcore$endEntityCloak(
            Entity entity,
            double x,
            double y,
            double z,
            float rotationYaw,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            CallbackInfo ci
    ) {

        if (!aerocloakingcore$entityCloakActive) {
            return;
        }

        try {
            if (buffer instanceof MultiBufferSource.BufferSource bufferSource) {
                bufferSource.endBatch();
            }
        } finally {
            EntityCloakRenderState.end();
            aerocloakingcore$entityCloakActive = false;
        }
    }
}
