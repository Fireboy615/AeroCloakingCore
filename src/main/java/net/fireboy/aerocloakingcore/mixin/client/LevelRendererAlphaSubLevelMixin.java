package net.fireboy.aerocloakingcore.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;

import net.fireboy.aerocloakingcore.client.AeroCloakingCoreClient;
import net.fireboy.aerocloakingcore.client.AlphaSubLevelRenderQueue;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;

import org.joml.Matrix4f;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replays ALPHA-cloaked Sable block geometry at the very end of the world
 * render, after water, particles, clouds, weather, and Fabulous transparency
 * composition.
 *
 * AlphaSubLevelRenderQueue now restores each source terrain layer's own shader
 * and Sable lighting state. This class only owns the final replay timing and
 * the already-working late block-selection outline.
 */
@Mixin(value = LevelRenderer.class, priority = 1100)
public abstract class LevelRendererAlphaSubLevelMixin {

    @Inject(
            method = "renderLevel",
            at = @At("HEAD")
    )
    private void aerocloakingcore$beginAlphaSubLevelFrame(
            CallbackInfo ci
    ) {
        AlphaSubLevelRenderQueue.beginFrame();
    }

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderDebug(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/Camera;)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void aerocloakingcore$renderLateAlphaSubLevels(
            DeltaTracker deltaTracker,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f frustumMatrix,
            Matrix4f projectionMatrix,
            CallbackInfo ci
    ) {
        try {
            if (!AlphaSubLevelRenderQueue.isEmpty()) {
                AlphaSubLevelRenderQueue.renderQueued();
            }
        } finally {
            AlphaSubLevelRenderQueue.clear();

            Minecraft.getInstance()
                    .getMainRenderTarget()
                    .bindWrite(false);

            RenderSystem.depthMask(true);
        }

        /*
         * Keep the outline fix exactly where it now works: after every late
         * ALPHA terrain layer has finished drawing.
         */
        AeroCloakingCoreClient.renderPendingAlphaOutline();
    }
}
