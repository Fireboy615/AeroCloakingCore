package net.fireboy.aerocloakingcore.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.map.BalloonMap;

import net.fireboy.aerocloakingcore.client.HotAirBalloonCloakRenderState;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;

import org.joml.Matrix4f;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replays ALPHA hot-air balloon heat at the final world stage, but BEFORE the
 * delayed alpha sublevel terrain itself.
 *
 * <p>Priority 1200 is intentionally above the alpha terrain callback (1100).
 * Both inject at the same renderDebug boundary, so the heat soft-light pass is
 * composited after water/clouds/weather/Fabulous transparency, while the
 * transparent ship hull has not yet written its late depth. The hull then
 * fades over the already-composited heat naturally.</p>
 */
@Mixin(value = LevelRenderer.class, priority = 1200)
public abstract class LevelRendererHotAirBalloonCloakMixin {

    @Inject(
            method = "renderLevel",
            at = @At("HEAD")
    )
    private void aerocloakingcore$beginHotAirCloakFrame(
            CallbackInfo ci
    ) {
        HotAirBalloonCloakRenderState.beginFrame();
    }

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderDebug(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/Camera;)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void aerocloakingcore$renderLateAlphaHotAir(
            DeltaTracker deltaTracker,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f frustumMatrix,
            Matrix4f projectionMatrix,
            CallbackInfo ci
    ) {
        if (!HotAirBalloonCloakRenderState.needsLateAlphaPass()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;

        if (level == null) {
            HotAirBalloonCloakRenderState.endLateAlphaPass();
            return;
        }

        BalloonMap balloonMap = BalloonMap.MAP.get(level);

        if (balloonMap.isEmpty()) {
            HotAirBalloonCloakRenderState.endLateAlphaPass();
            return;
        }

        HotAirBalloonCloakRenderState.beginLateAlphaPass();

        try {
            /*
             * Reuse Aeronautics' own framebuffer, hot-air shader, textures and
             * soft-light post pipeline. HeatedCulledRenderRegionCloakMixin
             * filters this second pass so only partially cloaked ALPHA /
             * ALPHA_SURFACE regions submit geometry.
             */
            ClientBalloonEffectRendererAccessor.aerocloakingcore$renderBalloonEffects(
                    balloonMap,
                    frustumMatrix,
                    projectionMatrix,
                    (int) level.getGameTime()
            );
        } finally {
            HotAirBalloonCloakRenderState.endLateAlphaPass();

            minecraft.getMainRenderTarget().bindWrite(false);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.depthMask(true);
        }
    }
}
