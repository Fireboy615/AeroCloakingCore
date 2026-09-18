package net.fireboy.aerocloakingcore.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;

import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.fireboy.aerocloakingcore.client.HotAirBalloonCloakRenderState;
import net.fireboy.aerocloakingcore.network.CloakingClient;

import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes Aeronautics' hot-air heat volume follow the owning sublevel cloak.
 *
 * <p>The hot-air volume is unusual because Aeronautics renders it directly
 * into its own framebuffer with a Veil shader.  Do not rely on Veil's Java
 * uniform wrapper here: by the time HeatedCulledRenderRegion#render reaches
 * ShaderInstance#apply the actual OpenGL program is bound, so writing the
 * uniforms directly is both simpler and deterministic.</p>
 *
 * <p>ALPHA changes the existing ColorModulator alpha. DITHER drives the
 * injected AeroCloakDitherStrength uniform. If the injection is unavailable,
 * DITHER falls back to an alpha fade instead of leaking a fully-visible heat
 * volume through the dithered hull.</p>
 */
@Mixin(
        targets = "dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.effect.HeatedCulledRenderRegion",
        remap = false
)
public abstract class HeatedCulledRenderRegionCloakMixin {

    @Shadow
    private boolean built;

    @Shadow
    private Vec3 origin;

    @Shadow
    public abstract void build();

    @Unique
    private float aerocloakingcore$heatDitherStrength;

    @Unique
    private float aerocloakingcore$heatAlphaMultiplier = 1.0F;

    @Inject(
            method = "render",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void aerocloakingcore$prepareHotAirCloak(
            Matrix4f modelView,
            Matrix4f projectionMatrix,
            CallbackInfo ci
    ) {
        aerocloakingcore$heatDitherStrength = 0.0F;
        aerocloakingcore$heatAlphaMultiplier = 1.0F;

        if (!built) {
            build();
        }

        if (origin == null) {
            if (HotAirBalloonCloakRenderState.isLateAlphaPass()) {
                ci.cancel();
            }
            return;
        }

        ClientSubLevel subLevel = Sable.HELPER.getContainingClient(origin);

        if (subLevel == null) {
            if (HotAirBalloonCloakRenderState.isLateAlphaPass()) {
                ci.cancel();
            }
            return;
        }

        float cloakStrength = clamp(
                CloakingClient.getViewerCloakStrength(subLevel)
        );
        CloakRenderMode renderMode = CloakingClient.getRenderMode(subLevel);
        boolean fullyHidden = CloakingClient.shouldHideSubLevel(subLevel);

        if (HotAirBalloonCloakRenderState.isLateAlphaPass()) {
            if (!renderMode.isAlpha() || fullyHidden) {
                ci.cancel();
                return;
            }

            aerocloakingcore$heatAlphaMultiplier = 1.0F - cloakStrength;
            return;
        }

        if (fullyHidden) {
            ci.cancel();
            return;
        }

        if (renderMode.isAlpha()) {
            /*
             * Alpha sublevels are replayed late. Keep their heat volume in
             * the same late section so ordinary world geometry still occludes
             * the effect correctly while the ship itself fades over it.
             */
            HotAirBalloonCloakRenderState.requestLateAlphaPass();
            ci.cancel();
            return;
        }

        if (renderMode == CloakRenderMode.DITHER) {
            aerocloakingcore$heatDitherStrength = cloakStrength;
        }
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ShaderInstance;apply()V",
                    shift = At.Shift.AFTER
            ),
            remap = false
    )
    private void aerocloakingcore$applyHotAirCloak(
            Matrix4f modelView,
            Matrix4f projectionMatrix,
            CallbackInfo ci
    ) {
        int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        if (program == 0) {
            return;
        }

        float ditherStrength = clamp(aerocloakingcore$heatDitherStrength);
        float alphaMultiplier = clamp(aerocloakingcore$heatAlphaMultiplier);

        /*
         * The injected uniform is not one of Minecraft's vanilla uniforms, so
         * ShaderInstance does not reliably reset/upload it for us. Set it on
         * every region draw, including zero, to prevent one balloon leaking
         * its cloak amount into the next balloon.
         */
        int ditherLocation = GL20C.glGetUniformLocation(
                program,
                "AeroCloakDitherStrength"
        );

        if (ditherLocation >= 0) {
            GL20C.glUniform1f(ditherLocation, ditherStrength);
        } else if (ditherStrength > 0.0F) {
            /* Shader injection missing: degrade safely to an alpha fade. */
            alphaMultiplier *= 1.0F - ditherStrength;
        }

        /*
         * ShaderInstance#apply has just uploaded Aeronautics' normal
         * ColorModulator. Override only its alpha for this one draw. The next
         * region's apply() restores the ordinary value before we run again.
         */
        int colorLocation = GL20C.glGetUniformLocation(
                program,
                "ColorModulator"
        );

        if (colorLocation >= 0) {
            float[] color = RenderSystem.getShaderColor();
            GL20C.glUniform4f(
                    colorLocation,
                    color[0],
                    color[1],
                    color[2],
                    color[3] * clamp(alphaMultiplier)
            );
        }
    }

    @Unique
    private static float clamp(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}
