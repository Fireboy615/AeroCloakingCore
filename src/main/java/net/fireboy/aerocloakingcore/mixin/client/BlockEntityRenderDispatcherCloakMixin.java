package net.fireboy.aerocloakingcore.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;

import net.fireboy.aerocloakingcore.client.BlockEntityCloakRenderQueue;
import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.fireboy.aerocloakingcore.client.RopeCloakRenderQueue;
import net.fireboy.aerocloakingcore.network.CloakingClient;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Adds cloak fading to vanilla/modded BlockEntityRenderer output without ever
 * flushing Minecraft's shared MultiBufferSource.
 *
 * The first implementation flushed RenderBuffers.bufferSource() around each
 * chest. That made the chest fade, but it also forced unrelated world batches
 * to draw at the wrong point in LevelRenderer, which could bring back the
 * water/cloud ordering artifacts. This version redirects only the actual BER
 * draw into Aero Cloaking Core's isolated render path.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherCloakMixin {

    @Redirect(
            method = "setupAndRender",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V"
            )
    )
    private static <T extends BlockEntity> void aerocloakingcore$renderCloakedBlockEntity(
            BlockEntityRenderer<T> renderer,
            T blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource originalBufferSource,
            int packedLight,
            int packedOverlay
    ) {
        /*
         * Register the physical rope before any cloak early-return or ALPHA
         * deferral. The visible rope renderer is not a reliable registration
         * point at 100% cloak because its owner BER may itself be deferred.
         */
        if (blockEntity instanceof SmartBlockEntity smartBlockEntity) {
            RopeStrandHolderBehavior ropeHolder =
                    smartBlockEntity.getBehaviour(RopeStrandHolderBehavior.TYPE);
            if (ropeHolder != null) {
                RopeCloakRenderQueue.queueEntityOcclusionDepth(
                        smartBlockEntity,
                        ropeHolder,
                        partialTick
                );
            }
        }

        SubLevel subLevel = Sable.HELPER.getContaining(blockEntity);

        if (!(subLevel instanceof ClientSubLevel clientSubLevel)) {
            renderer.render(
                    blockEntity,
                    partialTick,
                    poseStack,
                    originalBufferSource,
                    packedLight,
                    packedOverlay
            );
            return;
        }

        float cloakStrength =
                CloakingClient.getViewerCloakStrength(clientSubLevel);

        if (cloakStrength <= 0.001F) {
            renderer.render(
                    blockEntity,
                    partialTick,
                    poseStack,
                    originalBufferSource,
                    packedLight,
                    packedOverlay
            );
            return;
        }

        // Normally a fully cloaked BER submits no geometry at all. Rope-holder
        // BEs are the one exception: their renderer also owns Simulated's rope
        // call, and the rope may need a visible gradient toward its other end.
        if (cloakStrength >= 0.999F
                && !aerocloakingcore$needsRopeRender(blockEntity)) {
            return;
        }

        CloakRenderMode renderMode =
                CloakingClient.getRenderMode(clientSubLevel);

        if (renderMode.isAlpha()) {
            /*
             * Alpha BERs must be replayed after water/clouds/translucent world
             * rendering for the same reason ALPHA sublevel terrain is delayed.
             */
            BlockEntityCloakRenderQueue.enqueueAlpha(
                    renderer,
                    blockEntity,
                    partialTick,
                    poseStack,
                    packedLight,
                    packedOverlay,
                    cloakStrength,
                    renderMode
            );
            return;
        }

        /*
         * Dither keeps normal timing/depth behaviour. Render it now, but only
         * into a private buffer so no shared world batch is force-flushed.
         */
        BlockEntityCloakRenderQueue.renderDitherNow(
                renderer,
                blockEntity,
                partialTick,
                poseStack,
                packedLight,
                packedOverlay,
                cloakStrength
        );
    }
    private static boolean aerocloakingcore$needsRopeRender(
            BlockEntity blockEntity
    ) {
        if (!(blockEntity instanceof SmartBlockEntity smartBlockEntity)) {
            return false;
        }

        RopeStrandHolderBehavior ropeHolder =
                smartBlockEntity.getBehaviour(RopeStrandHolderBehavior.TYPE);

        return ropeHolder != null
                && ropeHolder.ownsRope()
                && ropeHolder.getClientStrand() != null;
    }

}
