package net.fireboy.aerocloakingcore.client;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;

import net.fireboy.aerocloakingcore.network.CloakingClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;

import org.joml.Matrix4f;

import org.lwjgl.opengl.GL11C;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-frame queue for ALPHA-cloaked Sable block geometry.
 *
 * Normal Sable rendering occurs inside Minecraft's real terrain-layer pass:
 *
 *   RenderType state
 *   -> layer-specific shader defaults
 *   -> Sable dynamic lighting uniforms
 *   -> renderChunkedSubLevel()
 *
 * ALPHA mode has to move the actual draw until after water/clouds/weather to
 * avoid depth-order problems. This queue captures the state of EACH terrain
 * layer during its normal pass and recreates that same pipeline at the late
 * draw point.
 */
public final class AlphaSubLevelRenderQueue {

    private static final List<DeferredSubLevelAlphaRender> QUEUE =
            new ArrayList<>();

    /**
     * Prevent accidental duplicate capture of the same render-data/layer pair
     * during one frame while still allowing every distinct terrain layer.
     */
    private static final Map<
            VanillaChunkedSubLevelRenderData,
            Set<RenderType>
            > QUEUED_LAYERS =
            new IdentityHashMap<>();

    /**
     * Surface-alpha depth is rendered separately immediately before Flywheel's
     * late alpha pass. That makes the main depth buffer available to Flywheel
     * so embedded Create/Flywheel visuals behind an opaque cloaked hull are
     * rejected instead of showing through it.
     */
    private static boolean surfaceDepthPrepassRendered;

    private AlphaSubLevelRenderQueue() {
    }

    public static void beginFrame() {
        clear();
    }

    public static void enqueue(
            VanillaChunkedSubLevelRenderData renderData,
            RenderType renderType,
            Matrix4f modelView,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        Set<RenderType> layers =
                QUEUED_LAYERS.computeIfAbsent(
                        renderData,
                        ignored -> Collections.newSetFromMap(
                                new IdentityHashMap<>()
                        )
                );

        if (!layers.add(renderType)) {
            return;
        }

        float[] shaderColor =
                RenderSystem.getShaderColor();

        float[] fogColor =
                RenderSystem.getShaderFogColor();

        QUEUE.add(
                new DeferredSubLevelAlphaRender(
                        renderData,
                        renderType,

                        new Matrix4f(modelView),
                        new Matrix4f(RenderSystem.getProjectionMatrix()),
                        new Matrix4f(RenderSystem.getTextureMatrix()),

                        cameraX,
                        cameraY,
                        cameraZ,

                        shaderColor[0],
                        shaderColor[1],
                        shaderColor[2],
                        shaderColor[3],

                        RenderSystem.getShaderGlintAlpha(),

                        RenderSystem.getShaderFogStart(),
                        RenderSystem.getShaderFogEnd(),
                        fogColor[0],
                        fogColor[1],
                        fogColor[2],
                        fogColor[3],
                        RenderSystem.getShaderFogShape()
                )
        );
    }

    public static boolean isEmpty() {
        return QUEUE.isEmpty();
    }

    /**
     * Writes only ALPHA_SURFACE's opaque/cutout shell into the main depth
     * buffer. This is intentionally separated from the colour replay so it can
     * run immediately before Flywheel's late alpha pass.
     *
     * Translucent terrain is excluded, preserving the desired behaviour where
     * glass/water can still reveal geometry behind them.
     */
    public static void renderSurfaceDepthPrepass() {
        if (surfaceDepthPrepassRendered || QUEUE.isEmpty()) {
            return;
        }

        surfaceDepthPrepassRendered = true;

        float[] lateShaderColor =
                RenderSystem.getShaderColor().clone();

        float lateShaderGlintAlpha =
                RenderSystem.getShaderGlintAlpha();

        float lateFogStart =
                RenderSystem.getShaderFogStart();

        float lateFogEnd =
                RenderSystem.getShaderFogEnd();

        float[] lateFogColor =
                RenderSystem.getShaderFogColor().clone();

        FogShape lateFogShape =
                RenderSystem.getShaderFogShape();

        Matrix4f lateTextureMatrix =
                new Matrix4f(RenderSystem.getTextureMatrix());

        try {
            for (DeferredSubLevelAlphaRender deferred : QUEUE) {
                CloakRenderMode mode = CloakingClient.getRenderMode(
                        deferred.renderData().getSubLevel()
                );

                if (!mode.usesSurfaceDepthPrepass()
                        || !isDepthOccludingLayer(deferred.renderType())) {
                    continue;
                }

                restoreCapturedGlobals(deferred);
                renderLayer(deferred, false, true);
            }
        } finally {
            restoreLateGlobals(
                    lateShaderColor,
                    lateShaderGlintAlpha,
                    lateFogStart,
                    lateFogEnd,
                    lateFogColor,
                    lateFogShape,
                    lateTextureMatrix
            );
        }
    }

    /**
     * Replays the captured ALPHA terrain colour layers.
     *
     * Surface alpha's depth pre-pass normally ran immediately before the late
     * Flywheel pass. The fallback call here keeps Surface Alpha correct even if
     * Flywheel has nothing to render in a particular frame.
     */
    public static void renderQueued() {
        renderSurfaceDepthPrepass();

        float[] lateShaderColor =
                RenderSystem.getShaderColor().clone();

        float lateShaderGlintAlpha =
                RenderSystem.getShaderGlintAlpha();

        float lateFogStart =
                RenderSystem.getShaderFogStart();

        float lateFogEnd =
                RenderSystem.getShaderFogEnd();

        float[] lateFogColor =
                RenderSystem.getShaderFogColor().clone();

        FogShape lateFogShape =
                RenderSystem.getShaderFogShape();

        Matrix4f lateTextureMatrix =
                new Matrix4f(RenderSystem.getTextureMatrix());

        try {
            /*
             * Classic ALPHA keeps its existing depth-writing colour pass.
             * ALPHA_SURFACE leaves depth untouched here because its opaque/
             * cutout shell is already in the depth buffer.
             */
            for (DeferredSubLevelAlphaRender deferred : QUEUE) {
                restoreCapturedGlobals(deferred);

                CloakRenderMode mode = CloakingClient.getRenderMode(
                        deferred.renderData().getSubLevel()
                );

                boolean writeDepth = !mode.usesSurfaceDepthPrepass();
                renderLayer(deferred, true, writeDepth);
            }
        } finally {
            restoreLateGlobals(
                    lateShaderColor,
                    lateShaderGlintAlpha,
                    lateFogStart,
                    lateFogEnd,
                    lateFogColor,
                    lateFogShape,
                    lateTextureMatrix
            );
        }
    }

    public static void clear() {
        QUEUE.clear();
        QUEUED_LAYERS.clear();
        surfaceDepthPrepassRendered = false;
    }

    private static void restoreLateGlobals(
            float[] shaderColor,
            float shaderGlintAlpha,
            float fogStart,
            float fogEnd,
            float[] fogColor,
            FogShape fogShape,
            Matrix4f textureMatrix
    ) {
        RenderSystem.setShaderColor(
                shaderColor[0],
                shaderColor[1],
                shaderColor[2],
                shaderColor[3]
        );

        RenderSystem.setShaderGlintAlpha(shaderGlintAlpha);
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);

        RenderSystem.setShaderFogColor(
                fogColor[0],
                fogColor[1],
                fogColor[2],
                fogColor[3]
        );

        RenderSystem.setShaderFogShape(fogShape);
        RenderSystem.setTextureMatrix(textureMatrix);

        Minecraft.getInstance()
                .getMainRenderTarget()
                .bindWrite(false);

        GL11C.glColorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        VertexBuffer.unbind();
    }

    private static void restoreCapturedGlobals(
            DeferredSubLevelAlphaRender deferred
    ) {
        RenderSystem.setShaderColor(
                deferred.shaderColorR(),
                deferred.shaderColorG(),
                deferred.shaderColorB(),
                deferred.shaderColorA()
        );

        RenderSystem.setShaderGlintAlpha(
                deferred.shaderGlintAlpha()
        );

        RenderSystem.setShaderFogStart(
                deferred.fogStart()
        );

        RenderSystem.setShaderFogEnd(
                deferred.fogEnd()
        );

        RenderSystem.setShaderFogColor(
                deferred.fogColorR(),
                deferred.fogColorG(),
                deferred.fogColorB(),
                deferred.fogColorA()
        );

        RenderSystem.setShaderFogShape(
                deferred.fogShape()
        );

        RenderSystem.setTextureMatrix(
                deferred.textureMatrix()
        );
    }

    private static void renderLayer(
            DeferredSubLevelAlphaRender deferred,
            boolean writeColor,
            boolean writeDepth
    ) {
        RenderType layer =
                deferred.renderType();

        /*
         * Restore this layer's normal texture/lightmap/blend/depth/shader
         * state first. Translucent may select Minecraft's translucent target,
         * so immediately force the actual draw back onto the already-composited
         * main target afterward.
         */
        layer.setupRenderState();

        try {
            Minecraft.getInstance()
                    .getMainRenderTarget()
                    .bindWrite(false);

            /*
             * Solid/cutout terrain normally writes depth. The translucent
             * terrain RenderType also uses the normal colour+depth write mask.
             * Keeping depth writes preserves self-occlusion within the moving
             * sublevel, which is required by the working water/cloud solution.
             */
            GL11C.glColorMask(
                    writeColor,
                    writeColor,
                    writeColor,
                    writeColor
            );
            RenderSystem.depthMask(writeDepth);

            ShaderInstance shader =
                    RenderSystem.getShader();

            if (shader == null) {
                return;
            }

            /*
             * Rebuild the exact DEFAULT terrain uniforms using values captured
             * during this layer's original world pass, rather than the
             * post-cloud/weather RenderSystem values.
             */
            shader.setDefaultUniforms(
                    VertexFormat.Mode.QUADS,
                    deferred.modelView(),
                    deferred.projection(),
                    Minecraft.getInstance().getWindow()
            );

            shader.apply();

            try {
                /*
                 * This is the important piece the old late replay skipped.
                 * Normal Sable rendering enables its sublevel normal-lighting
                 * and sky-light/shadow uniforms before renderChunkedSubLevel().
                 */
                if (shader.FOG_SHAPE != null
                        && deferred.fogShape() != FogShape.SPHERE) {

                    shader.FOG_SHAPE.set(
                            FogShape.SPHERE.getIndex()
                    );

                    shader.FOG_SHAPE.upload();
                }

                VanillaSubLevelRenderDispatcher.setupDynamicEffects(
                        shader,
                        true,
                        true
                );

                deferred.renderData()
                        .renderChunkedSubLevel(
                                layer,
                                shader,
                                deferred.modelView(),
                                deferred.cameraX(),
                                deferred.cameraY(),
                                deferred.cameraZ()
                        );

            } finally {
                /*
                 * Match Sable's normal dispatcher cleanup. These reset the
                 * shader object's CPU-side uniform state for future draws.
                 */
                if (shader.FOG_SHAPE != null
                        && deferred.fogShape() != FogShape.SPHERE) {

                    shader.FOG_SHAPE.set(
                            deferred.fogShape().getIndex()
                    );
                }

                VanillaSubLevelRenderDispatcher.setupDynamicEffects(
                        shader,
                        false,
                        false
                );

                shader.clear();
            }
        } finally {
            VertexBuffer.unbind();
            layer.clearRenderState();

            Minecraft.getInstance()
                    .getMainRenderTarget()
                    .bindWrite(false);

            GL11C.glColorMask(true, true, true, true);
            RenderSystem.depthMask(true);
        }
    }

    private static boolean isDepthOccludingLayer(RenderType layer) {
        /*
         * Known opaque/alpha-tested chunk layers. Clear glass can use a cutout
         * layer: its transparent texels are discarded by the normal shader, so
         * only visible glass texels write depth and the room remains visible
         * through the clear pixels.
         *
         * Translucent and tripwire are deliberately excluded. This keeps
         * stained glass, water and other genuinely translucent materials from
         * becoming solid depth blockers.
         */
        return layer == RenderType.solid()
                || layer == RenderType.cutout()
                || layer == RenderType.cutoutMipped();
    }
}

