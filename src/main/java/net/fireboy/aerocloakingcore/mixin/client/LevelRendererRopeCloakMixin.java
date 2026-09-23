package net.fireboy.aerocloakingcore.mixin.client;

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
 * Owns only the per-frame lifecycle of the rope cloak queue.
 *
 * The actual ALPHA/ALPHA_SURFACE rope flush happens in
 * LevelRendererAlphaBlockEntityMixin immediately after deferred block
 * entities are replayed.  Keeping the flush there prevents rope render timing
 * from depending on which endpoint was clicked first.
 */
@Mixin(value = LevelRenderer.class, priority = 900)
public abstract class LevelRendererRopeCloakMixin {

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void aerocloakingcore$beginRopeCloakFrame(
            DeltaTracker deltaTracker,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f frustumMatrix,
            Matrix4f projectionMatrix,
            CallbackInfo ci
    ) {
        RopeCloakRenderQueue.beginFrame(
                deltaTracker.getGameTimeDeltaPartialTick(false)
        );
    }

}
