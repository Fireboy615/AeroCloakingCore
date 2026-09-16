package net.fireboy.aerocloakingcore.mixin.client;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;

import net.fireboy.aerocloakingcore.client.AlphaSubLevelRenderQueue;
import net.fireboy.aerocloakingcore.client.FlywheelAlphaRenderState;
import net.fireboy.aerocloakingcore.client.FlywheelLateRenderContext;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderBuffers;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flywheel normally renders immediately after entities, before Minecraft's
 * translucent world effects.  That timing is correct for opaque and DITHER
 * visuals, but not for a real alpha fade: water and clouds rendered later can
 * be rejected by depth written by a partially transparent Flywheel visual.
 *
 * <p>During ALPHA cloaking the normal Flywheel pass suppresses the alpha
 * embedded fragments.  At the end of the world pass we invoke Flywheel a
 * second time.  A shader-side pass flag discards everything except
 * ALPHA-cloaked embedded visuals during this second pass.</p>
 */
@Mixin(value = LevelRenderer.class, priority = 900)
public abstract class FlywheelLevelRendererAlphaMixin {

    @Shadow
    @Nullable
    private ClientLevel level;

    @Shadow
    @Final
    private RenderBuffers renderBuffers;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void aerocloakingcore$beginFlywheelAlphaFrame(
            CallbackInfo ci
    ) {
        FlywheelAlphaRenderState.beginFrame();
    }

    /**
     * Render the ALPHA-only Flywheel pass at the same final world stage used
     * by the vanilla/Sable ALPHA replay: after water, particles, clouds,
     * weather, and Fabulous transparency composition.
     *
     * <p>Rendering immediately after translucent terrain fixed water, but
     * clouds are drawn later and their depth test could still be cut out by
     * the partially transparent Flywheel geometry.  Moving the pass here
     * removes that ordering problem as well.</p>
     */
    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderDebug(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/Camera;)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void aerocloakingcore$renderLateFlywheelAlpha(
            DeltaTracker deltaTracker,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f modelMatrix,
            Matrix4f projectionMatrix,
            CallbackInfo ci
    ) {
        /*
         * Surface Alpha needs its opaque/cutout shell in the main depth buffer
         * BEFORE Flywheel performs its late OIT pass. Flywheel's OIT framebuffer
         * attaches/copies that depth, so Create/Flywheel visuals behind an
         * opaque cloaked block fail the normal depth test instead of showing
         * through the hull.
         *
         * Classic Alpha has no surface pre-pass, so its existing behaviour is
         * unchanged.
         */
        AlphaSubLevelRenderQueue.renderSurfaceDepthPrepass();

        if (!FlywheelAlphaRenderState.isAlphaActive() || level == null) {
            return;
        }

        VisualizationManager manager = VisualizationManager.get(level);
        if (manager == null) {
            return;
        }

        FlywheelLateRenderContext context = FlywheelLateRenderContext.create(
                (LevelRenderer) (Object) this,
                level,
                renderBuffers,
                modelMatrix,
                projectionMatrix,
                camera,
                deltaTracker.getGameTimeDeltaPartialTick(false)
        );

        if (!(manager instanceof FlywheelVisualizationManagerAccessor accessor)) {
            return;
        }

        FlywheelAlphaRenderState.setLateAlphaPass(true);
        try {
            // onStartLevelRender has already prepared this frame. Invoke the
            // manager's private render stage directly rather than firing the
            // public RenderDispatcher callback a second time.
            accessor.aerocloakingcore$renderPreparedFrame(context);
        } finally {
            FlywheelAlphaRenderState.setLateAlphaPass(false);
        }
    }
}
