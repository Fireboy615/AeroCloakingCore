package net.fireboy.aerocloakingcore.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;

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
 * Gives entities the same viewer-specific cloak strength as the Sable
 * sublevel they belong to.
 *
 * The actual partial-cloak effect is selected later at RenderType draw time:
 * DITHER uses the cloak shader uniform, while ALPHA uses temporary blending.
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

        // Fully visible: leave normal entity rendering completely alone.
        if (cloakStrength <= 0.001F) {
            return;
        }

        // Fully cloaked: do not submit any entity geometry at all.
        if (cloakStrength >= 0.999F) {
            ci.cancel();
            return;
        }

        /*
         * LevelRenderer normally shares one BufferSource across many entities.
         * Flush anything already queued before enabling our per-entity state,
         * otherwise another entity could accidentally inherit this cloak value.
         */
        if (buffer instanceof MultiBufferSource.BufferSource bufferSource) {
            bufferSource.endBatch();

            EntityCloakRenderState.begin(cloakStrength);
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
            /*
             * Flush this entity while its cloak state is still active.
             * That covers the model and common render layers/equipment that
             * use the same BufferSource.
             */
            if (buffer instanceof MultiBufferSource.BufferSource bufferSource) {
                bufferSource.endBatch();
            }
        } finally {
            EntityCloakRenderState.end();
            aerocloakingcore$entityCloakActive = false;
        }
    }
}
