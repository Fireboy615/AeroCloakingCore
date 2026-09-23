package net.fireboy.aerocloakingcore.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;

import net.fireboy.aerocloakingcore.client.EntityCloakRenderState;

import net.minecraft.client.renderer.RenderType;

import org.lwjgl.opengl.GL11C;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/**
 * Applies the alpha component of the active cloak state around RenderType
 * BufferUploader draws.
 *
 * <p>Dither uniforms are deliberately <strong>not</strong> written here.
 * BufferUploader ultimately calls VertexBuffer#_drawWithShader, and that method
 * binds the ShaderInstance immediately before the draw and clears it immediately
 * afterward. Writing a GL uniform on either side of BufferUploader therefore
 * races the program bind and can hit GL_CURRENT_PROGRAM == 0. The companion
 * VertexBufferEntityDitherMixin owns the dither uniform at the only safe point:
 * directly after ShaderInstance#apply and before ShaderInstance#clear.</p>
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

        /*
         * Depth-only rope/entity replays use a dedicated DEPTH_WRITE RenderType.
         * The VertexBuffer mixin explicitly forces AeroCloakStrength to zero for
         * these draws after the shader is actually bound, so no GL shader work is
         * needed here.
         */
        if (EntityCloakRenderState.isDepthOnly()) {
            return;
        }

        float alpha = EntityCloakRenderState.getAlphaMultiplier();

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
