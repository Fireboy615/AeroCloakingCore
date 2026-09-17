package net.fireboy.aerocloakingcore.mixin.client;

import net.fireboy.aerocloakingcore.client.BlockEntityCloakRenderQueue;
import net.fireboy.aerocloakingcore.client.RopeCloakRenderQueue;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;

import org.joml.Matrix4f;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Owns the late ALPHA block-entity replay.
 *
 * Rope order is intentionally coupled to this pass.  A Simulated rope whose
 * owner lives on an ALPHA/ALPHA_SURFACE sublevel is not discovered until that
 * owner's block-entity renderer is replayed here.  Flush ropes immediately
 * after the whole block-entity queue has completed so world-owned ropes and
 * alpha-sublevel-owned ropes are rendered at exactly the same point in the
 * frame.
 */
@Mixin(value = LevelRenderer.class, priority = 1000)
public abstract class LevelRendererAlphaBlockEntityMixin {

    @Inject(
            method = "renderLevel",
            at = @At("HEAD")
    )
    private void aerocloakingcore$beginBlockEntityCloakFrame(
            CallbackInfo ci
    ) {
        BlockEntityCloakRenderQueue.beginFrame();
    }

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderDebug(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/Camera;)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void aerocloakingcore$renderLateAlphaBlockEntities(
            DeltaTracker deltaTracker,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f frustumMatrix,
            Matrix4f projectionMatrix,
            CallbackInfo ci
    ) {
        /*
         * This may discover ropes owned by alpha-cloaked block entities.
         * Do not return control to LevelRenderer before flushing those ropes;
         * otherwise their render timing depends on which endpoint owns them.
         */
        BlockEntityCloakRenderQueue.renderQueued();

        /*
         * Also flushes ropes queued earlier by world/dither owners.  The rope
         * queue clears what it draws, so every rope gets one consistent late
         * draw point regardless of connection direction.
         */
        RopeCloakRenderQueue.renderQueued();
    }
}
