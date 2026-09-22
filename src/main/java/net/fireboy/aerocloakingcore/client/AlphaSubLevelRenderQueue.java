package net.fireboy.aerocloakingcore.client;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
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
     * DITHER terrain is still drawn at Sable's normal time, but Aeronautics'
     * burner flame is a direct late draw. Some Sable render paths do not
     * leave the moving sublevel's depth in Minecraft's main depth buffer, so
     * the late flame can otherwise draw over the hull. Capture the same
     * opaque/cutout terrain layers for a late depth-only replay.
     */
    private static final List<DeferredSubLevelAlphaRender> DITHER_DEPTH_QUEUE =
            new ArrayList<>();

    /**
     * One base capture per cloaked sublevel used to build a temporary full
     * terrain depth mask while normal entities render in OCCLUDED_ONLY mode.
     */
    private static final List<DeferredSubLevelAlphaRender> ENTITY_OCCLUSION_QUEUE =
            new ArrayList<>();

    private static final Set<VanillaChunkedSubLevelRenderData> ENTITY_OCCLUSION_QUEUED =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Prevent accidental duplicate capture of the same render-data/layer pair
     * during one frame while still allowing every distinct terrain layer.
     */
    private static final Map<
            VanillaChunkedSubLevelRenderData,
            Set<RenderType>
            > QUEUED_LAYERS =
            new IdentityHashMap<>();

    private static final Map<
            VanillaChunkedSubLevelRenderData,
            Set<RenderType>
            > DITHER_DEPTH_QUEUED_LAYERS =
            new IdentityHashMap<>();

    /**
     * Surface-alpha depth is rendered separately immediately before Flywheel's
     * late alpha pass. That makes the main depth buffer available to Flywheel
     * so embedded Create/Flywheel visuals behind an opaque cloaked hull are
     * rejected instead of showing through it.
     */
    private static boolean surfaceDepthPrepassRendered;

    private static boolean ditherDepthPrepassRendered;

    /**
     * True only while replaying DITHER terrain as an occlusion mask for late
     * direct effects such as the burner flame.  The colour hull stays dithered,
     * but this depth pass deliberately writes the complete solid/cutout shell
     * so interior effects cannot leak through dither holes.
     */
    private static boolean ditherOcclusionDepthPass;
    private static boolean entityOcclusionDepthPass;

    private AlphaSubLevelRenderQueue() {
    }

    public static void beginFrame() {
        QUEUE.clear();
        QUEUED_LAYERS.clear();
        DITHER_DEPTH_QUEUE.clear();
        DITHER_DEPTH_QUEUED_LAYERS.clear();
        ENTITY_OCCLUSION_QUEUE.clear();
        ENTITY_OCCLUSION_QUEUED.clear();
        surfaceDepthPrepassRendered = false;
        ditherDepthPrepassRendered = false;
        ditherOcclusionDepthPass = false;
        entityOcclusionDepthPass = false;
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

    public static void enqueueDitherDepth(
            VanillaChunkedSubLevelRenderData renderData,
            RenderType renderType,
            Matrix4f modelView,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        if (!isDepthOccludingLayer(renderType)) {
            return;
        }

        Set<RenderType> layers =
                DITHER_DEPTH_QUEUED_LAYERS.computeIfAbsent(
                        renderData,
                        ignored -> Collections.newSetFromMap(
                                new IdentityHashMap<>()
                        )
                );

        if (!layers.add(renderType)) {
            return;
        }

        float[] shaderColor = RenderSystem.getShaderColor();
        float[] fogColor = RenderSystem.getShaderFogColor();

        DITHER_DEPTH_QUEUE.add(
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

    public static void enqueueEntityOcclusion(
            VanillaChunkedSubLevelRenderData renderData,
            RenderType renderType,
            Matrix4f modelView,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        if (!CloakingClient.shouldWriteEntityOcclusionDepth(renderData.getSubLevel())
                || !ENTITY_OCCLUSION_QUEUED.add(renderData)) {
            return;
        }

        float[] shaderColor = RenderSystem.getShaderColor();
        float[] fogColor = RenderSystem.getShaderFogColor();

        ENTITY_OCCLUSION_QUEUE.add(
                new DeferredSubLevelAlphaRender(
                        renderData,
                        renderType,
                        new Matrix4f(modelView),
                        new Matrix4f(RenderSystem.getProjectionMatrix()),
                        new Matrix4f(RenderSystem.getTextureMatrix()),
                        cameraX,
                        cameraY,
                        cameraZ,
                        shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3],
                        RenderSystem.getShaderGlintAlpha(),
                        RenderSystem.getShaderFogStart(),
                        RenderSystem.getShaderFogEnd(),
                        fogColor[0], fogColor[1], fogColor[2], fogColor[3],
                        RenderSystem.getShaderFogShape()
                )
        );
    }

    public static boolean hasEntityOcclusionDepth() {
        return !ENTITY_OCCLUSION_QUEUE.isEmpty();
    }

    public static boolean isEntityOcclusionDepthPass() {
        return entityOcclusionDepthPass;
    }

    /**
     * Draws the complete block shell into depth only. This pass is temporary:
     * EntityOcclusionDepthMask restores the original world depth immediately
     * after the entity batch has been flushed.
     */
    public static void renderEntityOcclusionDepthPrepass() {
        if (ENTITY_OCCLUSION_QUEUE.isEmpty()) {
            return;
        }

        float[] lateShaderColor = RenderSystem.getShaderColor().clone();
        float lateShaderGlintAlpha = RenderSystem.getShaderGlintAlpha();
        float lateFogStart = RenderSystem.getShaderFogStart();
        float lateFogEnd = RenderSystem.getShaderFogEnd();
        float[] lateFogColor = RenderSystem.getShaderFogColor().clone();
        FogShape lateFogShape = RenderSystem.getShaderFogShape();
        Matrix4f lateTextureMatrix = new Matrix4f(RenderSystem.getTextureMatrix());

        entityOcclusionDepthPass = true;
        ditherOcclusionDepthPass = true;
        try {
            RenderType[] layers = {
                    RenderType.solid(),
                    RenderType.cutoutMipped(),
                    RenderType.cutout(),
                    RenderType.translucent(),
                    RenderType.tripwire()
            };

            for (DeferredSubLevelAlphaRender base : ENTITY_OCCLUSION_QUEUE) {
                if (!CloakingClient.shouldWriteEntityOcclusionDepth(
                        base.renderData().getSubLevel())) {
                    continue;
                }

                for (RenderType layer : layers) {
                    DeferredSubLevelAlphaRender deferred = withRenderType(base, layer);
                    restoreCapturedGlobals(deferred);
                    renderLayer(deferred, false, true);
                }
            }
        } finally {
            ditherOcclusionDepthPass = false;
            entityOcclusionDepthPass = false;
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

    private static DeferredSubLevelAlphaRender withRenderType(
            DeferredSubLevelAlphaRender base,
            RenderType renderType
    ) {
        return new DeferredSubLevelAlphaRender(
                base.renderData(), renderType,
                base.modelView(), base.projection(), base.textureMatrix(),
                base.cameraX(), base.cameraY(), base.cameraZ(),
                base.shaderColorR(), base.shaderColorG(), base.shaderColorB(), base.shaderColorA(),
                base.shaderGlintAlpha(),
                base.fogStart(), base.fogEnd(),
                base.fogColorR(), base.fogColorG(), base.fogColorB(), base.fogColorA(),
                base.fogShape()
        );
    }

    public static boolean isEmpty() {
        return QUEUE.isEmpty() && DITHER_DEPTH_QUEUE.isEmpty();
    }

    public static boolean isDitherOcclusionDepthPass() {
        return ditherOcclusionDepthPass;
    }

    /**
     * Replays DITHER solid/cutout terrain into the main depth buffer only.
     * Cloak dithering is intentionally disabled for this depth replay: the
     * visible hull remains dithered, while late interior effects see the full
     * physical shell as an occluder. This prevents a direct effect such as the
     * burner flame from leaking through dither holes in the ship's blocks.
     */
    public static void renderDitherDepthPrepass() {
        if (ditherDepthPrepassRendered || DITHER_DEPTH_QUEUE.isEmpty()) {
            return;
        }

        ditherDepthPrepassRendered = true;

        float[] lateShaderColor = RenderSystem.getShaderColor().clone();
        float lateShaderGlintAlpha = RenderSystem.getShaderGlintAlpha();
        float lateFogStart = RenderSystem.getShaderFogStart();
        float lateFogEnd = RenderSystem.getShaderFogEnd();
        float[] lateFogColor = RenderSystem.getShaderFogColor().clone();
        FogShape lateFogShape = RenderSystem.getShaderFogShape();
        Matrix4f lateTextureMatrix =
                new Matrix4f(RenderSystem.getTextureMatrix());

        ditherOcclusionDepthPass = true;
        try {
            for (DeferredSubLevelAlphaRender deferred : DITHER_DEPTH_QUEUE) {
                var subLevel = deferred.renderData().getSubLevel();

                if (CloakingClient.getRenderMode(subLevel)
                        != CloakRenderMode.DITHER
                        || CloakingClient.shouldHideSubLevel(subLevel)
                        || !isDepthOccludingLayer(deferred.renderType())) {
                    continue;
                }

                float strength = CloakingClient.getViewerCloakStrength(subLevel);
                if (strength <= 0.0001F || strength >= 0.9999F) {
                    continue;
                }

                restoreCapturedGlobals(deferred);
                renderLayer(deferred, false, true);
            }
        } finally {
            ditherOcclusionDepthPass = false;
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
     * Replays only the requested DITHER sublevel's opaque/cutout shell into
     * the main depth buffer. Unlike {@link #renderDitherDepthPrepass()}, this
     * intentionally ignores the once-per-frame guard so a direct renderer can
     * guarantee that its owner's depth is present immediately before drawing.
     *
     * <p>The burner flame needs this because it bypasses MultiBufferSource and
     * draws directly. Calling this immediately before Aeronautics binds the
     * burner shader gives the flame a complete solid/cutout hull depth mask,
     * regardless of late-render mixin ordering.</p>
     */
    public static void renderDitherDepthPrepassFor(
            ClientSubLevel targetSubLevel
    ) {
        if (targetSubLevel == null || DITHER_DEPTH_QUEUE.isEmpty()) {
            return;
        }

        if (CloakingClient.getRenderMode(targetSubLevel)
                != CloakRenderMode.DITHER
                || CloakingClient.shouldHideSubLevel(targetSubLevel)) {
            return;
        }

        float targetStrength =
                CloakingClient.getViewerCloakStrength(targetSubLevel);

        if (targetStrength <= 0.0001F || targetStrength >= 0.9999F) {
            return;
        }

        float[] lateShaderColor = RenderSystem.getShaderColor().clone();
        float lateShaderGlintAlpha = RenderSystem.getShaderGlintAlpha();
        float lateFogStart = RenderSystem.getShaderFogStart();
        float lateFogEnd = RenderSystem.getShaderFogEnd();
        float[] lateFogColor = RenderSystem.getShaderFogColor().clone();
        FogShape lateFogShape = RenderSystem.getShaderFogShape();
        Matrix4f lateTextureMatrix =
                new Matrix4f(RenderSystem.getTextureMatrix());

        ditherOcclusionDepthPass = true;
        try {
            for (DeferredSubLevelAlphaRender deferred : DITHER_DEPTH_QUEUE) {
                if (deferred.renderData().getSubLevel() != targetSubLevel
                        || !isDepthOccludingLayer(deferred.renderType())) {
                    continue;
                }

                restoreCapturedGlobals(deferred);
                renderLayer(deferred, false, true);
            }
        } finally {
            ditherOcclusionDepthPass = false;
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
        renderDitherDepthPrepass();
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

        /*
         * Keep DITHER depth captures alive until the next renderLevel HEAD.
         * The late ALPHA sublevel callback can run before the late block-entity
         * callback at the same renderDebug injection point. A burner rendered
         * by that later callback still needs its owning hull data for the
         * just-in-time depth replay. beginFrame() clears these captures before
         * any data from the next frame can be queued.
         */
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

