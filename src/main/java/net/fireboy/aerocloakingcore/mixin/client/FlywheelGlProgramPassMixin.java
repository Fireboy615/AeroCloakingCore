package net.fireboy.aerocloakingcore.mixin.client;

import net.fireboy.aerocloakingcore.client.FlywheelAlphaRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Uploads the render-pass selector whenever Flywheel binds a program.
 *
 * <p>This targets GlProgram by string so AeroCloakingCore does not take a Java
 * module dependency on Flywheel's internal shader package.</p>
 */
@Mixin(
        targets = "dev.engine_room.flywheel.backend.gl.shader.GlProgram",
        priority = 900,
        remap = false
)
public abstract class FlywheelGlProgramPassMixin {

    @Shadow
    public abstract void setInt(String glslName, int value);

    @Inject(
            method = "bind()V",
            at = @At("RETURN"),
            remap = false
    )
    private void aerocloakingcore$uploadAlphaPassFlag(CallbackInfo ci) {
        setInt(
                "_flw_aeroLateAlphaPass",
                FlywheelAlphaRenderState.isLateAlphaPass() ? 1 : 0
        );
    }
}
