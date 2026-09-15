package net.fireboy.aerocloakingcore.client;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;

import org.joml.Matrix4f;

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
     * Replays each captured layer using the same type of state Minecraft and
     * Sable would have used at the original terrain pass.
     */
    public static void renderQueued() {

        // Preserve the late-frame globals so clouds/weather/debug rendering
        // continue with exactly the state they had before our replay.
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
                restoreCapturedGlobals(deferred);
                renderLayer(deferred);
            }
        } finally {
            /*
             * Restore the late-frame RenderSystem globals. Shader instances
             * themselves are cleared per layer below.
             */
            RenderSystem.setShaderColor(
                    lateShaderColor[0],
                    lateShaderColor[1],
                    lateShaderColor[2],
                    lateShaderColor[3]
            );

            RenderSystem.setShaderGlintAlpha(
                    lateShaderGlintAlpha
            );

            RenderSystem.setShaderFogStart(
                    lateFogStart
            );

            RenderSystem.setShaderFogEnd(
                    lateFogEnd
            );

            RenderSystem.setShaderFogColor(
                    lateFogColor[0],
                    lateFogColor[1],
                    lateFogColor[2],
                    lateFogColor[3]
            );

            RenderSystem.setShaderFogShape(
                    lateFogShape
            );

            RenderSystem.setTextureMatrix(
                    lateTextureMatrix
            );

            Minecraft.getInstance()
                    .getMainRenderTarget()
                    .bindWrite(false);

            RenderSystem.depthMask(true);
            VertexBuffer.unbind();
        }
    }

    public static void clear() {
        QUEUE.clear();
        QUEUED_LAYERS.clear();
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
            DeferredSubLevelAlphaRender deferred
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
            RenderSystem.depthMask(true);

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

            RenderSystem.depthMask(true);
        }
    }
}
