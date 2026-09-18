package net.fireboy.aerocloakingcore.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerBlockEntity;
import dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerRenderer;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;

import net.fireboy.aerocloakingcore.client.AlphaSubLevelRenderQueue;
import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.fireboy.aerocloakingcore.network.CloakingClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the sublevel cloak to Aeronautics' direct burner-flame draw.
 *
 * <p>The flame bypasses MultiBufferSource and is drawn directly with Veil's
 * burner_flame shader. DITHER is sent directly to the injected flame shader's
 * active OpenGL uniform. ALPHA is intentionally implemented with fixed-function
 * constant-alpha blending so it keeps working even if Veil's shader injection
 * changes between versions.</p>
 */
@Mixin(value = HotAirBurnerRenderer.class, remap = false)
public abstract class HotAirBurnerRendererCloakMixin {

    @Unique
    private boolean aerocloakingcore$burnerChangedBlend;

    @Unique
    private boolean aerocloakingcore$burnerBlendWasEnabled;

    @Unique
    private int aerocloakingcore$burnerPreviousBlendSrcRgb;

    @Unique
    private int aerocloakingcore$burnerPreviousBlendDstRgb;

    @Unique
    private int aerocloakingcore$burnerPreviousBlendSrcAlpha;

    @Unique
    private int aerocloakingcore$burnerPreviousBlendDstAlpha;

    @Unique
    private int aerocloakingcore$burnerDitherProgram;

    @Unique
    private int aerocloakingcore$burnerDitherUniform = -1;

    @Unique
    private boolean aerocloakingcore$burnerChangedDepthFunc;

    @Unique
    private int aerocloakingcore$burnerPreviousDepthFunc = GL11C.GL_LEQUAL;

    /**
     * Make the DITHER hull depth available immediately before Aeronautics
     * binds the burner flame shader.  Doing this here is important: the depth
     * replay temporarily binds Minecraft terrain shaders, while the next
     * original instruction re-binds burner_flame and uploads its uniforms.
     * The direct flame draw therefore sees the completed dithered hull depth
     * without us disturbing the Veil shader that actually renders the flame.
     */
    @Inject(
            method = "renderSafe",
            at = @At(
                    value = "INVOKE",
                    target = "Lfoundry/veil/api/client/render/VeilRenderSystem;setShader(Lnet/minecraft/resources/ResourceLocation;)Lfoundry/veil/api/client/render/shader/program/ShaderProgram;",
                    shift = At.Shift.BEFORE
            ),
            remap = false
    )
    private void aerocloakingcore$prepareDitherDepthBeforeBurnerShader(
            HotAirBurnerBlockEntity blockEntity,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            int packedOverlay,
            CallbackInfo ci
    ) {
        SubLevel containing = Sable.HELPER.getContaining(blockEntity);

        if (!(containing instanceof ClientSubLevel clientSubLevel)) {
            return;
        }

        if (CloakingClient.getRenderMode(clientSubLevel)
                != CloakRenderMode.DITHER) {
            return;
        }

        float cloakStrength = clamp(
                CloakingClient.getViewerCloakStrength(clientSubLevel)
        );

        if (cloakStrength <= 0.0001F
                || cloakStrength >= 0.9999F
                || CloakingClient.shouldHideSubLevel(clientSubLevel)) {
            return;
        }

        /*
         * This is deliberately called at the flame site rather than relying
         * on the end-of-world replay ordering.  Whichever late-render mixin
         * runs first, the hull's surviving dither pixels are guaranteed to be
         * in the main depth buffer before renderFlame() enables depth testing.
         */
        AlphaSubLevelRenderQueue.renderDitherDepthPrepassFor(clientSubLevel);
    }

    @Inject(
            method = "renderSafe",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/eriksonn/aeronautics/content/blocks/hot_air/hot_air_burner/HotAirBurnerRenderer;renderFlame(Lcom/mojang/blaze3d/vertex/PoseStack;)V",
                    shift = At.Shift.BEFORE
            ),
            remap = false
    )
    private void aerocloakingcore$beforeBurnerFlame(
            HotAirBurnerBlockEntity blockEntity,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            int packedOverlay,
            CallbackInfo ci
    ) {
        aerocloakingcore$burnerChangedBlend = false;
        aerocloakingcore$burnerChangedDepthFunc = false;

        float ditherStrength = 0.0F;
        float alphaMultiplier = 1.0F;

        SubLevel containing = Sable.HELPER.getContaining(blockEntity);

        if (containing instanceof ClientSubLevel clientSubLevel) {
            float cloakStrength = clamp(
                    CloakingClient.getViewerCloakStrength(clientSubLevel)
            );
            CloakRenderMode mode = CloakingClient.getRenderMode(clientSubLevel);

            if (mode == CloakRenderMode.DITHER) {
                ditherStrength = cloakStrength;
            } else if (mode.isAlpha()) {
                alphaMultiplier = 1.0F - cloakStrength;
            }

            if (CloakingClient.shouldHideSubLevel(clientSubLevel)) {
                alphaMultiplier = 0.0F;
            }
        }

        if (ditherStrength > 0.0001F) {
            /*
             * The DITHER burner is replayed late. Force the flame back onto
             * Minecraft's main target and use the ordinary world depth test.
             * Aeronautics only enables depth testing inside renderFlame(); it
             * does not restore GL_DEPTH_FUNC itself, so inheriting a late-pass
             * depth function can make the billboard appear through the hull.
             */
            Minecraft.getInstance().getMainRenderTarget().bindWrite(false);

            aerocloakingcore$burnerPreviousDepthFunc =
                    GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);

            if (aerocloakingcore$burnerPreviousDepthFunc != GL11C.GL_LEQUAL) {
                GL11C.glDepthFunc(GL11C.GL_LEQUAL);
                aerocloakingcore$burnerChangedDepthFunc = true;
            }
        }

        /*
         * Aeronautics has already selected/bound burner_flame at this point.
         * Write the injected uniform directly to the active GL program. This
         * avoids relying on Veil's uniform wrapper upload timing and guarantees
         * the flame uses the exact same screen-space dither mask as the hull.
         */
        aerocloakingcore$burnerDitherProgram =
                GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        aerocloakingcore$burnerDitherUniform = -1;

        boolean ditherAvailable = false;
        if (aerocloakingcore$burnerDitherProgram != 0) {
            aerocloakingcore$burnerDitherUniform =
                    GL20C.glGetUniformLocation(
                            aerocloakingcore$burnerDitherProgram,
                            "AeroCloakDitherStrength"
                    );

            if (aerocloakingcore$burnerDitherUniform >= 0) {
                GL20C.glUniform1f(
                        aerocloakingcore$burnerDitherUniform,
                        clamp(ditherStrength)
                );
                ditherAvailable = true;
            }
        }

        /*
         * If the shader injection is unavailable, degrade to alpha rather than
         * leaving a fully visible flame attached to a cloaked sublevel.
         */
        if (ditherStrength > 0.0F && !ditherAvailable) {
            alphaMultiplier *= 1.0F - ditherStrength;
        }

        if (alphaMultiplier >= 0.9999F) {
            return;
        }

        aerocloakingcore$burnerChangedBlend = true;
        aerocloakingcore$burnerBlendWasEnabled =
                GL11C.glIsEnabled(GL11C.GL_BLEND);

        aerocloakingcore$burnerPreviousBlendSrcRgb =
                GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB);
        aerocloakingcore$burnerPreviousBlendDstRgb =
                GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB);
        aerocloakingcore$burnerPreviousBlendSrcAlpha =
                GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA);
        aerocloakingcore$burnerPreviousBlendDstAlpha =
                GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA);

        RenderSystem.enableBlend();

        /*
         * The burner shader outputs fully opaque surviving flame pixels. A
         * constant-alpha blend therefore gives us a true whole-flame opacity
         * multiplier without modifying Aeronautics' flame silhouette/cutout.
         */
        GL14C.glBlendColor(0.0F, 0.0F, 0.0F, clamp(alphaMultiplier));
        RenderSystem.blendFuncSeparate(
                GL14C.GL_CONSTANT_ALPHA,
                GL14C.GL_ONE_MINUS_CONSTANT_ALPHA,
                GL11C.GL_ONE,
                GL11C.GL_ONE_MINUS_SRC_ALPHA
        );
    }

    @Inject(
            method = "renderSafe",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/eriksonn/aeronautics/content/blocks/hot_air/hot_air_burner/HotAirBurnerRenderer;renderFlame(Lcom/mojang/blaze3d/vertex/PoseStack;)V",
                    shift = At.Shift.AFTER
            ),
            remap = false
    )
    private void aerocloakingcore$afterBurnerFlame(
            HotAirBurnerBlockEntity blockEntity,
            float partialTicks,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            int packedOverlay,
            CallbackInfo ci
    ) {
        /* Prevent one burner from leaking dither state into the next. */
        int currentProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        if (aerocloakingcore$burnerDitherUniform >= 0
                && currentProgram == aerocloakingcore$burnerDitherProgram) {
            GL20C.glUniform1f(aerocloakingcore$burnerDitherUniform, 0.0F);
        }
        aerocloakingcore$burnerDitherProgram = 0;
        aerocloakingcore$burnerDitherUniform = -1;

        if (aerocloakingcore$burnerChangedDepthFunc) {
            GL11C.glDepthFunc(aerocloakingcore$burnerPreviousDepthFunc);
            aerocloakingcore$burnerChangedDepthFunc = false;
        }

        if (!aerocloakingcore$burnerChangedBlend) {
            return;
        }

        RenderSystem.blendFuncSeparate(
                aerocloakingcore$burnerPreviousBlendSrcRgb,
                aerocloakingcore$burnerPreviousBlendDstRgb,
                aerocloakingcore$burnerPreviousBlendSrcAlpha,
                aerocloakingcore$burnerPreviousBlendDstAlpha
        );

        GL14C.glBlendColor(0.0F, 0.0F, 0.0F, 0.0F);

        if (aerocloakingcore$burnerBlendWasEnabled) {
            RenderSystem.enableBlend();
        } else {
            RenderSystem.disableBlend();
        }

        aerocloakingcore$burnerChangedBlend = false;
    }

    @Unique
    private static float clamp(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}
