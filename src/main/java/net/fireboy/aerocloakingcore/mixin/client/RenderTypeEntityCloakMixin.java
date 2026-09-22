package net.fireboy.aerocloakingcore.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;

import net.fireboy.aerocloakingcore.client.EntityCloakRenderState;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/**
 * Applies the alpha component of the active cloak state.
 *
 * Normal entity/block-entity ALPHA rendering still behaves exactly as before.
 * Rope segments may additionally combine this alpha multiplier with dither so
 * a strand can transition continuously from a DITHER endpoint to an ALPHA
 * endpoint without forcing the entire rope into one render technique.
 */
@Mixin(RenderType.class)
public abstract class RenderTypeEntityCloakMixin {

    @Unique
    private boolean aerocloakingcore$modifiedEntityAlphaState;

    @Unique
    private boolean aerocloakingcore$blendWasEnabled;

    /**
     * BufferUploader-backed RenderTypes (including Simulated rope buffers) do
     * not pass through VertexBufferEntityDitherMixin. Track the shader uniform
     * here so rope/block-entity dither state is applied and then cleared around
     * the actual draw.
     */
    @Unique
    private int aerocloakingcore$bufferDitherUniformLocation = -1;

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
        aerocloakingcore$bufferDitherUniformLocation = -1;

        /*
         * RenderType.draw() uses BufferUploader, not VertexBuffer, so the
         * normal VertexBufferEntityDitherMixin never sees rope geometry. The
         * shader is already bound at this injection point; apply the active
         * dither strength directly and always clear it after the draw.
         *
         * This is especially important for OCCLUDED_ONLY. At 100% ship cloak,
         * a solid/cutout shader may otherwise still contain a cloak strength of
         * 1 and discard every fragment of the rope's depth replay. That is the
         * exact 99% -> 100% pop where an entity suddenly became visible through
         * an invisible rope.
         */
        ShaderInstance shader = RenderSystem.getShader();
        if (shader != null && EntityCloakRenderState.isActive()) {
            int program = shader.getId();
            if (program != 0) {
                aerocloakingcore$bufferDitherUniformLocation =
                        GL20C.glGetUniformLocation(program, "AeroCloakStrength");

                if (aerocloakingcore$bufferDitherUniformLocation >= 0) {
                    float ditherStrength = EntityCloakRenderState.isDepthOnly()
                            ? 0.0F
                            : EntityCloakRenderState.getDitherStrength();
                    GL20C.glUniform1f(
                            aerocloakingcore$bufferDitherUniformLocation,
                            ditherStrength
                    );
                }
            }
        }

        /*
         * Depth-only rope replays now use a dedicated RenderType whose
         * write-mask state is DEPTH_WRITE. Do not mutate the GL colour/depth
         * masks here; this mixin only clears AeroCloakStrength so the solid
         * shader cannot discard the physical rope fragments.
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

        if (aerocloakingcore$bufferDitherUniformLocation >= 0) {
            GL20C.glUniform1f(
                    aerocloakingcore$bufferDitherUniformLocation,
                    0.0F
            );
            aerocloakingcore$bufferDitherUniformLocation = -1;
        }

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
