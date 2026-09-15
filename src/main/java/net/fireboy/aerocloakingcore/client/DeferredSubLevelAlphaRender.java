package net.fireboy.aerocloakingcore.client;

import com.mojang.blaze3d.shaders.FogShape;

import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;

import net.minecraft.client.renderer.RenderType;

import org.joml.Matrix4f;

/**
 * One captured Sable chunk-layer render for an ALPHA-cloaked sublevel.
 *
 * Besides the sublevel transform/camera, this stores the world-render state
 * that Minecraft had configured for this exact terrain layer. The late replay
 * restores that state before drawing so switching into ALPHA mode does not
 * change the layer's lighting/fog/colour behaviour.
 */
public record DeferredSubLevelAlphaRender(
        VanillaChunkedSubLevelRenderData renderData,
        RenderType renderType,

        Matrix4f modelView,
        Matrix4f projection,
        Matrix4f textureMatrix,

        double cameraX,
        double cameraY,
        double cameraZ,

        float shaderColorR,
        float shaderColorG,
        float shaderColorB,
        float shaderColorA,

        float shaderGlintAlpha,

        float fogStart,
        float fogEnd,
        float fogColorR,
        float fogColorG,
        float fogColorB,
        float fogColorA,
        FogShape fogShape
) {
}
