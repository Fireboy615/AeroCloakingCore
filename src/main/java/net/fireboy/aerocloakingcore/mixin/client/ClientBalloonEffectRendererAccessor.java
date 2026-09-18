package net.fireboy.aerocloakingcore.mixin.client;

import dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.map.BalloonMap;

import org.joml.Matrix4fc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Gives the late cloak pass access to Aeronautics' existing balloon-effect
 * renderer without duplicating its Veil framebuffer/post-processing code.
 */
@Mixin(
        targets = "dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.effect.ClientBalloonEffectRenderer",
        remap = false
)
public interface ClientBalloonEffectRendererAccessor {

    @Invoker(value = "renderBalloonEffects", remap = false)
    static void aerocloakingcore$renderBalloonEffects(
            BalloonMap balloonMap,
            Matrix4fc frustumMatrix,
            Matrix4fc projectionMatrix,
            int renderTick
    ) {
        throw new AssertionError("Mixin invoker was not applied");
    }
}
