package net.fireboy.aerocloakingcore.client;

import dev.engine_room.flywheel.api.backend.RenderContext;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Public-API-only RenderContext used for AeroCloakingCore's second Flywheel
 * render after Minecraft's translucent terrain pass.
 */
public record FlywheelLateRenderContext(
        LevelRenderer renderer,
        ClientLevel level,
        RenderBuffers buffers,
        Matrix4fc modelView,
        Matrix4fc projection,
        Matrix4fc viewProjection,
        Camera camera,
        float partialTick
) implements RenderContext {

    public static FlywheelLateRenderContext create(
            LevelRenderer renderer,
            ClientLevel level,
            RenderBuffers buffers,
            Matrix4fc modelView,
            Matrix4f projection,
            Camera camera,
            float partialTick
    ) {
        Matrix4f viewProjection = new Matrix4f(projection);
        viewProjection.mul(modelView);

        return new FlywheelLateRenderContext(
                renderer,
                level,
                buffers,
                modelView,
                projection,
                viewProjection,
                camera,
                partialTick
        );
    }
}
