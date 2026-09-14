package net.fireboy.aerocloakingcore.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;

import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.fireboy.aerocloakingcore.client.EntityCloakRenderState;
import net.fireboy.aerocloakingcore.client.config.AeroCloakingCoreClientConfig;

import net.minecraft.client.renderer.RenderType;

import org.lwjgl.opengl.GL11C;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/**
 * Handles ALPHA mode for partially cloaked entities.
 *
 * DITHER mode is intentionally handled later in VertexBufferEntityDitherMixin,
 * after ShaderInstance.apply(), so the raw shader uniform is not overwritten.
 */
@Mixin(RenderType.class)
public abstract class RenderTypeEntityCloakMixin {

    @Unique
    private boolean aerocloakingcore$modifiedEntityAlphaState;

    @Unique
    private boolean aerocloakingcore$blendWasEnabled;

    @Unique
    private float aerocloakingcore$oldRed;

    @Unique
    private float aerocloakingcore$oldGreen;

    @Unique
    private float aerocloakingcore$oldBlue;

    @Unique
    private float aerocloakingcore$oldAlpha;


    @Inject(
            method = "draw",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/BufferUploader;drawWithShader(Lcom/mojang/blaze3d/vertex/MeshData;)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void aerocloakingcore$beforeEntityCloakDraw(
            CallbackInfo ci
    ) {

        aerocloakingcore$modifiedEntityAlphaState = false;

        if (AeroCloakingCoreClientConfig.RENDER_MODE.get()
                != CloakRenderMode.ALPHA) {
            return;
        }

        float cloakStrength =
                EntityCloakRenderState.getCloakStrength();

        if (cloakStrength <= 0.001F) {
            return;
        }

        float alpha = 1.0F - cloakStrength;

        if (alpha >= 0.999F) {
            return;
        }

        aerocloakingcore$modifiedEntityAlphaState = true;

        float[] shaderColor = RenderSystem.getShaderColor();

        aerocloakingcore$oldRed = shaderColor[0];
        aerocloakingcore$oldGreen = shaderColor[1];
        aerocloakingcore$oldBlue = shaderColor[2];
        aerocloakingcore$oldAlpha = shaderColor[3];

        RenderSystem.setShaderColor(
                aerocloakingcore$oldRed,
                aerocloakingcore$oldGreen,
                aerocloakingcore$oldBlue,
                aerocloakingcore$oldAlpha * alpha
        );

        aerocloakingcore$blendWasEnabled =
                GL11C.glIsEnabled(GL11C.GL_BLEND);

        if (!aerocloakingcore$blendWasEnabled) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
        }
    }


    @Inject(
            method = "draw",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/BufferUploader;drawWithShader(Lcom/mojang/blaze3d/vertex/MeshData;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void aerocloakingcore$afterEntityCloakDraw(
            CallbackInfo ci
    ) {

        if (!aerocloakingcore$modifiedEntityAlphaState) {
            return;
        }

        RenderSystem.setShaderColor(
                aerocloakingcore$oldRed,
                aerocloakingcore$oldGreen,
                aerocloakingcore$oldBlue,
                aerocloakingcore$oldAlpha
        );

        if (!aerocloakingcore$blendWasEnabled) {
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
        }

        aerocloakingcore$modifiedEntityAlphaState = false;
    }
}
