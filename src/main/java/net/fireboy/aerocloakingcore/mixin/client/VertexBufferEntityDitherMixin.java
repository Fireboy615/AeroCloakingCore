package net.fireboy.aerocloakingcore.mixin.client;

import com.mojang.blaze3d.vertex.VertexBuffer;

import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.fireboy.aerocloakingcore.client.EntityCloakRenderState;
import net.fireboy.aerocloakingcore.client.config.AeroCloakingCoreClientConfig;

import net.minecraft.client.renderer.ShaderInstance;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL20C;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/**
 * Applies entity dither after Minecraft has actually bound/applied the shader.
 *
 * RenderType.draw() calls BufferUploader.drawWithShader(), which in turn calls
 * ShaderInstance.apply() immediately before the VBO draw. Setting the raw GL
 * uniform any earlier gets lost because the shader has not been applied yet.
 */
@Mixin(VertexBuffer.class)
public abstract class VertexBufferEntityDitherMixin {

    @Unique
    private int aerocloakingcore$entityDitherUniformLocation = -1;


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

        if (AeroCloakingCoreClientConfig.RENDER_MODE.get()
                != CloakRenderMode.DITHER) {
            return;
        }

        float cloakStrength =
                EntityCloakRenderState.getCloakStrength();

        if (cloakStrength <= 0.001F) {
            return;
        }

        int program = shader.getId();

        if (program == 0) {
            return;
        }

        aerocloakingcore$entityDitherUniformLocation =
                GL20C.glGetUniformLocation(
                        program,
                        "AeroCloakStrength"
                );

        if (aerocloakingcore$entityDitherUniformLocation >= 0) {
            GL20C.glUniform1f(
                    aerocloakingcore$entityDitherUniformLocation,
                    cloakStrength
            );
        }
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

        if (aerocloakingcore$entityDitherUniformLocation < 0) {
            return;
        }

        /*
         * GL uniforms persist on the program. Reset immediately after this
         * entity draw so normal entities cannot inherit the cloak strength.
         */
        GL20C.glUniform1f(
                aerocloakingcore$entityDitherUniformLocation,
                0.0F
        );

        aerocloakingcore$entityDitherUniformLocation = -1;
    }
}
