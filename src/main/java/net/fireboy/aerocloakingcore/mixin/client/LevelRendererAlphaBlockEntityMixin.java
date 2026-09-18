package net.fireboy.aerocloakingcore.mixin.client;

import net.fireboy.aerocloakingcore.client.BlockEntityCloakRenderQueue;
import net.fireboy.aerocloakingcore.client.ParticleCloakRenderQueue;
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
 * Owns the per-frame lifecycle of deferred ALPHA block entities and particles.
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
        ParticleCloakRenderQueue.beginFrame();
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
        BlockEntityCloakRenderQueue.renderQueued();
        RopeCloakRenderQueue.renderQueued();
        ParticleCloakRenderQueue.renderQueued(lightTexture);
    }
}
