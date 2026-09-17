package net.fireboy.aerocloakingcore.mixin.client;

import com.mojang.blaze3d.vertex.VertexBuffer;

import net.fireboy.aerocloakingcore.client.EntityCloakRenderState;

import net.minecraft.client.renderer.ShaderInstance;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL20C;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/**
 * Applies the dither component of the active cloak state after Minecraft has
 * actually bound/applied the shader.
 *
 * The dither amount is now independent from the alpha component.  Ordinary
 * DITHER entities still receive exactly their cloak strength, while rope
 * segments can use both components at once during a dither<->alpha transition.
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

        float cloakStrength =
                EntityCloakRenderState.getDitherStrength();

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
