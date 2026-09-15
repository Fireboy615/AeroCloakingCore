package net.fireboy.aerocloakingcore.mixin.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;

import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.fireboy.aerocloakingcore.network.CloakingClient;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;

import org.joml.Matrix4f;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the configured partial-cloak rendering mode to Sable's
 * vanilla/Sodium-compatible chunk renderer.
 *
 * DITHER mode:
 *   Uses AeroCloakStrength exactly as before.
 *
 * ALPHA mode:
 *   - SOLID / TRANSLUCENT / TRIPWIRE:
 *       Fade through ColorModulator.a using normal source-alpha blending.
 *
 *   - CUTOUT / CUTOUT_MIPPED:
 *       Do NOT reduce ColorModulator.a.
 *
 *       Vanilla cutout shaders perform their alpha discard using the colour
 *       after ColorModulator has already been multiplied in. Reducing
 *       ColorModulator.a therefore lowers otherwise-valid glass/leaf pixels
 *       toward the shader's discard cutoff and can make them snap out before
 *       cloak strength reaches 1.
 *
 *       Instead, these layers keep their normal shader alpha so vanilla's
 *       cutout test behaves exactly as usual. The cloak fade is applied later,
 *       in the fixed-function blend stage, using GL_CONSTANT_ALPHA.
 *
 *       Result:
 *         - no dither tail
 *         - normal glass fades smoothly
 *         - transparent holes still discard normally
 *         - leaves/cutout-mipped textures keep their normal shape
 *         - the working late ALPHA render/depth pipeline is unchanged
 */
@Mixin(
        targets = "dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData"
)
public abstract class VanillaChunkedSubLevelRenderDataMixin {

    @Shadow
    @Final
    private ClientSubLevel subLevel;

    @Unique
    private int aerocloakingcore$cloakUniformLocation = -1;

    @Unique
    private boolean aerocloakingcore$modifiedColorModulator;

    @Unique
    private boolean aerocloakingcore$changedBlendEnabled;

    @Unique
    private boolean aerocloakingcore$usingConstantAlphaBlend;

    @Unique
    private int aerocloakingcore$previousBlendSrcRgb;

    @Unique
    private int aerocloakingcore$previousBlendDstRgb;

    @Unique
    private int aerocloakingcore$previousBlendSrcAlpha;

    @Unique
    private int aerocloakingcore$previousBlendDstAlpha;


    @Inject(
            method = "renderChunkedSubLevel",
            at = @At("HEAD")
    )
    private void aerocloakingcore$beginCloakRender(
            RenderType layer,
            ShaderInstance shader,
            Matrix4f modelView,
            double camX,
            double camY,
            double camZ,
            CallbackInfo ci
    ) {

        aerocloakingcore$modifiedColorModulator = false;
        aerocloakingcore$changedBlendEnabled = false;
        aerocloakingcore$usingConstantAlphaBlend = false;

        float cloakStrength =
                CloakingClient.getViewerCloakStrength(subLevel);

        int program =
                shader.getId();

        aerocloakingcore$cloakUniformLocation =
                GL20C.glGetUniformLocation(
                        program,
                        "AeroCloakStrength"
                );

        CloakRenderMode renderMode =
                CloakingClient.getRenderMode(subLevel);


        // -------------------------------------------------------------
        // DITHER MODE
        // -------------------------------------------------------------

        if (renderMode == CloakRenderMode.DITHER) {

            if (aerocloakingcore$cloakUniformLocation >= 0) {
                GL20C.glUniform1f(
                        aerocloakingcore$cloakUniformLocation,
                        cloakStrength
                );
            }

            return;
        }


        // -------------------------------------------------------------
        // ALPHA MODE
        // -------------------------------------------------------------

        /*
         * ALPHA mode never uses the cloak dither shader.
         */
        if (aerocloakingcore$cloakUniformLocation >= 0) {
            GL20C.glUniform1f(
                    aerocloakingcore$cloakUniformLocation,
                    0.0F
            );
        }

        float alpha =
                1.0F - cloakStrength;

        if (alpha >= 0.999F) {
            return;
        }


        boolean cutoutLayer =
                layer == RenderType.cutout()
                        || layer == RenderType.cutoutMipped();


        /*
         * Preserve whether blending was active when this layer entered.
         */
        boolean blendAlreadyEnabled =
                GL11C.glIsEnabled(GL11C.GL_BLEND);

        if (!blendAlreadyEnabled) {
            RenderSystem.enableBlend();
            aerocloakingcore$changedBlendEnabled = true;
        }


        if (cutoutLayer) {

            // ---------------------------------------------------------
            // CUTOUT / CUTOUT_MIPPED:
            // Fade AFTER the shader's discard test.
            // ---------------------------------------------------------

            aerocloakingcore$usingConstantAlphaBlend = true;

            /*
             * Save the exact blend factors that were active before we replace
             * them, so this mixin does not leak state into later draws.
             */
            aerocloakingcore$previousBlendSrcRgb =
                    GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB);

            aerocloakingcore$previousBlendDstRgb =
                    GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB);

            aerocloakingcore$previousBlendSrcAlpha =
                    GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA);

            aerocloakingcore$previousBlendDstAlpha =
                    GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA);


            /*
             * Constant alpha is applied by the framebuffer blend stage, after
             * the fragment shader has already decided whether to discard the
             * texel.
             *
             * RGB:
             *   out = src * alpha + dst * (1 - alpha)
             *
             * Alpha channel uses the same factors. The delayed ALPHA pipeline
             * draws directly to the final main target, so this is appropriate.
             */
            GL14C.glBlendColor(
                    0.0F,
                    0.0F,
                    0.0F,
                    alpha
            );

            RenderSystem.blendFunc(
                    GlStateManager.SourceFactor.CONSTANT_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_CONSTANT_ALPHA
            );


            /*
             * IMPORTANT:
             * Do not modify ColorModulator.a here.
             *
             * Vanilla's cutout shader must see the original texture/material
             * alpha so its normal cutout threshold is independent of cloak
             * strength.
             */
            return;
        }


        // -------------------------------------------------------------
        // NORMAL ALPHA PATH
        // -------------------------------------------------------------

        /*
         * These layers do not need the cutout workaround, so keep the smooth
         * shader-alpha fade that already works.
         */
        if (!blendAlreadyEnabled) {
            RenderSystem.defaultBlendFunc();
        }

        Uniform colorModulator =
                shader.getUniform("ColorModulator");

        if (colorModulator != null) {

            float[] normalColor =
                    RenderSystem.getShaderColor();

            colorModulator.set(
                    normalColor[0],
                    normalColor[1],
                    normalColor[2],
                    normalColor[3] * alpha
            );

            colorModulator.upload();

            aerocloakingcore$modifiedColorModulator = true;
        }
    }


    @Inject(
            method = "renderChunkedSubLevel",
            at = @At("TAIL")
    )
    private void aerocloakingcore$endCloakRender(
            RenderType layer,
            ShaderInstance shader,
            Matrix4f modelView,
            double camX,
            double camY,
            double camZ,
            CallbackInfo ci
    ) {

        /*
         * Always clear the dither uniform before another draw reuses this
         * shader program.
         */
        if (aerocloakingcore$cloakUniformLocation >= 0) {
            GL20C.glUniform1f(
                    aerocloakingcore$cloakUniformLocation,
                    0.0F
            );
        }

        aerocloakingcore$cloakUniformLocation = -1;


        /*
         * Restore ColorModulator only if this draw used the normal ALPHA path.
         */
        if (aerocloakingcore$modifiedColorModulator) {

            Uniform colorModulator =
                    shader.getUniform("ColorModulator");

            if (colorModulator != null) {

                float[] normalColor =
                        RenderSystem.getShaderColor();

                colorModulator.set(
                        normalColor[0],
                        normalColor[1],
                        normalColor[2],
                        normalColor[3]
                );

                colorModulator.upload();
            }

            aerocloakingcore$modifiedColorModulator = false;
        }


        /*
         * Restore the blend factors after a cutout constant-alpha draw.
         */
        if (aerocloakingcore$usingConstantAlphaBlend) {

            RenderSystem.blendFuncSeparate(
                    aerocloakingcore$previousBlendSrcRgb,
                    aerocloakingcore$previousBlendDstRgb,
                    aerocloakingcore$previousBlendSrcAlpha,
                    aerocloakingcore$previousBlendDstAlpha
            );

            GL14C.glBlendColor(
                    0.0F,
                    0.0F,
                    0.0F,
                    0.0F
            );

            aerocloakingcore$usingConstantAlphaBlend = false;
        }


        /*
         * If this mixin enabled blending for the draw, return it to the
         * disabled state expected by solid/cutout terrain layers.
         */
        if (aerocloakingcore$changedBlendEnabled) {

            RenderSystem.disableBlend();

            aerocloakingcore$changedBlendEnabled = false;
        }
    }
}
