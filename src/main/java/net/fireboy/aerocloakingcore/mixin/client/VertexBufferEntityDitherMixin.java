package net.fireboy.aerocloakingcore.mixin.client;

import com.mojang.blaze3d.vertex.VertexBuffer;

import net.fireboy.aerocloakingcore.client.EntityCloakRenderState;

import net.minecraft.client.renderer.ShaderInstance;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/**
 * Applies the entity/rope cloak dither only while the ShaderInstance program is
 * actually bound.
 *
 * <p>Minecraft 1.21.1's BufferUploader path ends up in
 * VertexBuffer#_drawWithShader. That method calls ShaderInstance#apply, draws,
 * then ShaderInstance#clear. These injections sit inside that bound-program
 * window, avoiding GL_INVALID_OPERATION / "No active program" spam.</p>
 */
@Mixin(VertexBuffer.class)
public abstract class VertexBufferEntityDitherMixin {

    @Unique
    private int aerocloakingcore$entityDitherUniformLocation = -1;

    @Unique
    private int aerocloakingcore$entityDitherProgram = 0;


    @Inject(
            method = "_drawWithShader",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ShaderInstance;apply()V",
                    shift = At.Shift.AFTER
            )
    )
    private void aerocloakingcore$applyEntityDither(
            Matrix4f modelViewMatrix,
            Matrix4f projectionMatrix,
            ShaderInstance shader,
            CallbackInfo ci
    ) {

        aerocloakingcore$entityDitherUniformLocation = -1;
        aerocloakingcore$entityDitherProgram = 0;

        if (!EntityCloakRenderState.isActive()) {
            return;
        }

        int program = shader.getId();

        if (program == 0
                || GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM) != program) {
            return;
        }

        int uniformLocation = GL20C.glGetUniformLocation(
                program,
                "AeroCloakStrength"
        );

        if (uniformLocation < 0) {
            return;
        }

        /*
         * Depth-only and ALPHA-only draws must explicitly upload zero. The
         * injected uniform is not managed by ShaderInstance, so its last value
         * otherwise persists on the GL program across draws.
         */
        float ditherStrength = EntityCloakRenderState.isDepthOnly()
                ? 0.0F
                : EntityCloakRenderState.getDitherStrength();

        GL20C.glUniform1f(uniformLocation, ditherStrength);

        aerocloakingcore$entityDitherUniformLocation = uniformLocation;
        aerocloakingcore$entityDitherProgram = program;
    }


    @Inject(
            method = "_drawWithShader",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;draw()V",
                    shift = At.Shift.AFTER
            )
    )
    private void aerocloakingcore$clearEntityDither(
            Matrix4f modelViewMatrix,
            Matrix4f projectionMatrix,
            ShaderInstance shader,
            CallbackInfo ci
    ) {

        int uniformLocation = aerocloakingcore$entityDitherUniformLocation;
        int program = aerocloakingcore$entityDitherProgram;

        aerocloakingcore$entityDitherUniformLocation = -1;
        aerocloakingcore$entityDitherProgram = 0;

        if (uniformLocation < 0 || program == 0) {
            return;
        }

        /*
         * We are still before ShaderInstance#clear here. Guarding the program
         * identity makes this robust against another renderer unexpectedly
         * changing programs during draw().
         */
        if (GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM) == program) {
            GL20C.glUniform1f(uniformLocation, 0.0F);
        }
    }
}
