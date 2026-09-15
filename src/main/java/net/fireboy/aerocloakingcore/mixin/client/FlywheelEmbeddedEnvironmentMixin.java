package net.fireboy.aerocloakingcore.mixin.client;

import net.fireboy.aerocloakingcore.client.FlywheelCloakEmbedding;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carries Aero Cloaking Core's signed per-sublevel cloak signal through
 * Flywheel's EmbeddedEnvironment. Positive values are DITHER; negative values
 * are ALPHA.
 *
 * The instancing backend can use a normal uniform because Flywheel sets up each
 * embedded environment immediately before drawing it. The indirect backend is
 * handled separately by encoding strength into the normal matrix in
 * FlywheelBlockEntityStorageMixin, so no fragile extra matrix-record byte is
 * needed here anymore.
 */
@Mixin(
        targets = "dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment",
        priority = 900,
        remap = false
)
public abstract class FlywheelEmbeddedEnvironmentMixin
        implements FlywheelCloakEmbedding {

    @Unique
    private float aerocloakingcore$flywheelCloakStrength = 0.0F;

    @Override
    public void aerocloakingcore$setFlywheelCloakStrength(float strength) {
        aerocloakingcore$flywheelCloakStrength = Math.max(
                -1.0F,
                Math.min(1.0F, strength)
        );
    }

    /** Instancing backend. This path is already confirmed working in-game. */
    @Inject(
            method = "setupDraw(Ldev/engine_room/flywheel/backend/gl/shader/GlProgram;)V",
            at = @At("RETURN"),
            require = 1,
            remap = false
    )
    private void aerocloakingcore$writeInstancingCloakUniform(
            @Coerce Object program,
            CallbackInfo ci
    ) {
        ((GlProgramAccessor) program).aerocloakingcore$setFloat(
                "_flw_aeroCloakStrengthUniform",
                aerocloakingcore$flywheelCloakStrength
        );
    }
}
