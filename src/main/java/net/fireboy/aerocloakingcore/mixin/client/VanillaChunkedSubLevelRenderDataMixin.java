package net.fireboy.aerocloakingcore.mixin.client;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;

import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.fireboy.aerocloakingcore.client.config.AeroCloakingCoreClientConfig;
import net.fireboy.aerocloakingcore.network.CloakingClient;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;

import org.joml.Matrix4f;

import org.lwjgl.opengl.GL11C;
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
    private boolean aerocloakingcore$modifiedAlphaState;

    @Unique
    private boolean aerocloakingcore$changedBlendState;

    @Unique
    private boolean aerocloakingcore$blendWasEnabled;


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

        float cloakStrength =
                CloakingClient.getViewerCloakStrength(subLevel);

        int program = shader.getId();

        aerocloakingcore$cloakUniformLocation =
                GL20C.glGetUniformLocation(
                        program,
                        "AeroCloakStrength"
                );

        CloakRenderMode renderMode =
                AeroCloakingCoreClientConfig.RENDER_MODE.get();

        if (renderMode == CloakRenderMode.DITHER) {

            if (aerocloakingcore$cloakUniformLocation >= 0) {
                GL20C.glUniform1f(
                        aerocloakingcore$cloakUniformLocation,
                        cloakStrength
                );
            }

            aerocloakingcore$modifiedAlphaState = false;
            aerocloakingcore$changedBlendState = false;
            return;
        }

        // ALPHA mode must disable the dither effect for this draw.
        if (aerocloakingcore$cloakUniformLocation >= 0) {
            GL20C.glUniform1f(
                    aerocloakingcore$cloakUniformLocation,
                    0.0F
            );
        }

        float alpha = 1.0F - cloakStrength;

        if (alpha >= 0.999F) {
            aerocloakingcore$modifiedAlphaState = false;
            aerocloakingcore$changedBlendState = false;
            return;
        }

        aerocloakingcore$modifiedAlphaState = true;

        /*
         * In ALPHA mode the dispatcher now defers all sublevel block geometry
         * until Minecraft's translucent pass. Keep depth writes exactly as the
         * active render pass configured them; do NOT force depthMask(false).
         *
         * That preserves self-occlusion between blocks on the same sublevel
         * and avoids the severe "blocks behind blocks" artifact from the
         * previous depth-mask experiment.
         */
        boolean blendAlreadyEnabled =
                GL11C.glIsEnabled(GL11C.GL_BLEND);

        aerocloakingcore$changedBlendState =
                !blendAlreadyEnabled;

        if (aerocloakingcore$changedBlendState) {
            aerocloakingcore$blendWasEnabled = false;
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
        }

        Uniform colorModulator =
                shader.getUniform("ColorModulator");

        if (colorModulator != null) {
            float[] normalColor = RenderSystem.getShaderColor();

            colorModulator.set(
                    normalColor[0],
                    normalColor[1],
                    normalColor[2],
                    normalColor[3] * alpha
            );

            colorModulator.upload();
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

        if (aerocloakingcore$cloakUniformLocation >= 0) {
            GL20C.glUniform1f(
                    aerocloakingcore$cloakUniformLocation,
                    0.0F
            );
        }

        aerocloakingcore$cloakUniformLocation = -1;

        if (!aerocloakingcore$modifiedAlphaState) {
            return;
        }

        Uniform colorModulator =
                shader.getUniform("ColorModulator");

        if (colorModulator != null) {
            float[] normalColor = RenderSystem.getShaderColor();

            colorModulator.set(
                    normalColor[0],
                    normalColor[1],
                    normalColor[2],
                    normalColor[3]
            );

            colorModulator.upload();
        }

        if (aerocloakingcore$changedBlendState) {
            if (!aerocloakingcore$blendWasEnabled) {
                RenderSystem.disableBlend();
            }

            aerocloakingcore$changedBlendState = false;
        }

        aerocloakingcore$modifiedAlphaState = false;
    }
}
