package net.fireboy.aerocloakingcore.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;

import net.fireboy.aerocloakingcore.client.BlockEntityCloakRenderQueue;
import net.fireboy.aerocloakingcore.client.CloakRenderMode;
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

        // At full cloak, submit no BER geometry at all.
        if (cloakStrength >= 0.999F) {
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
}
