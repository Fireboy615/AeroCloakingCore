package net.fireboy.aerocloakingcore.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Bridge to Flywheel's package-private GlProgram#setFloat(String, float).
 *
 * GlProgram itself is package-private in Flywheel, so Aero Cloaking Core must
 * not reference its Java type directly. Targeting it by internal class name
 * avoids Java/module access checks while Mixin still applies this interface to
 * the real class at runtime.
 */
@Mixin(
        targets = "dev.engine_room.flywheel.backend.gl.shader.GlProgram",
        remap = false
)
public interface GlProgramAccessor {

    @Invoker("setFloat")
    void aerocloakingcore$setFloat(String name, float value);
}
