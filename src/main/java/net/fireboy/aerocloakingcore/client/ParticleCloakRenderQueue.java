package net.fireboy.aerocloakingcore.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;

import net.fireboy.aerocloakingcore.network.CloakingClient;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureManager;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Late render queue for partially cloaked ALPHA particle sprites.
 *
 * NeoForge intentionally renders solid particle sheets before translucent
 * chunk geometry. That is correct for ordinary opaque particles, but it is
 * wrong once Aero turns one of those particles translucent: its quad can write
 * depth before water/clouds are drawn, making those later passes disappear
 * behind an otherwise transparent particle.
 *
 * DITHER particles stay in Minecraft's normal particle pass. ALPHA and
 * ALPHA_SURFACE particles are collected here and replayed near the end of
 * LevelRenderer, after translucent terrain, particles, clouds and weather.
 * This mirrors the existing late-alpha block-entity path and prevents the
 * particle quad from poisoning water/cloud depth while preserving true alpha.
 */
public final class ParticleCloakRenderQueue {

    private static final Map<ParticleRenderType, List<DeferredParticle>> QUEUE =
            new LinkedHashMap<>();

    private ParticleCloakRenderQueue() {
    }

    public static void beginFrame() {
        QUEUE.clear();
    }

    public static void enqueueAlpha(
            Particle particle,
            ParticleRenderType renderType,
            ClientSubLevel origin,
            Camera camera,
            float partialTick
    ) {
        QUEUE.computeIfAbsent(
                renderType,
                ignored -> new ArrayList<>()
        ).add(
                new DeferredParticle(
                        particle,
                        origin,
                        camera,
                        partialTick
                )
        );
    }

    public static boolean isEmpty() {
        return QUEUE.isEmpty();
    }

    public static void renderQueued(LightTexture lightTexture) {
        if (QUEUE.isEmpty()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        TextureManager textureManager = minecraft.getTextureManager();
        ShaderInstance cloakShader = ParticleCloakShader.get();

        if (cloakShader == null) {
            QUEUE.clear();
            return;
        }

        ShaderInstance previousShader = RenderSystem.getShader();
        boolean blendWasEnabled = GL11C.glIsEnabled(GL11C.GL_BLEND);
        boolean depthMaskWasEnabled = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);

        int previousBlendSrcRgb = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB);
        int previousBlendDstRgb = GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB);
        int previousBlendSrcAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA);
        int previousBlendDstAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA);

        try {
            minecraft.getMainRenderTarget().bindWrite(false);
            lightTexture.turnOnLightLayer();
            RenderSystem.enableDepthTest();

            for (Map.Entry<ParticleRenderType, List<DeferredParticle>> entry
                    : QUEUE.entrySet()) {
                renderBatch(
                        entry.getKey(),
                        entry.getValue(),
                        textureManager,
                        cloakShader
                );
            }
        } finally {
            QUEUE.clear();

            lightTexture.turnOffLightLayer();

            RenderSystem.blendFuncSeparate(
                    previousBlendSrcRgb,
                    previousBlendDstRgb,
                    previousBlendSrcAlpha,
                    previousBlendDstAlpha
            );

            if (blendWasEnabled) {
                RenderSystem.enableBlend();
            } else {
                RenderSystem.disableBlend();
            }

            RenderSystem.depthMask(depthMaskWasEnabled);

            if (previousShader != null) {
                RenderSystem.setShader(() -> previousShader);
            }

            minecraft.getMainRenderTarget().bindWrite(false);
        }
    }

    private static void renderBatch(
            ParticleRenderType renderType,
            List<DeferredParticle> particles,
            TextureManager textureManager,
            ShaderInstance cloakShader
    ) {
        if (particles.isEmpty()) {
            return;
        }

        /*
         * Match ParticleEngine's normal setup first so the correct particle or
         * block atlas is bound. We replace only the draw shader/blend state.
         */
        RenderSystem.setShader(GameRenderer::getParticleShader);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = renderType.begin(tesselator, textureManager);

        if (buffer == null) {
            return;
        }

        for (DeferredParticle deferred : particles) {
            float cloakStrength =
                    CloakingClient.getViewerCloakStrength(deferred.origin());

            if (cloakStrength >= 0.999F) {
                continue;
            }

            CloakRenderMode renderMode =
                    CloakingClient.getRenderMode(deferred.origin());

            /*
             * If the mode was changed while the frame was being built, only
             * replay entries that are still alpha. A newly selected DITHER
             * mode will take over on the next frame in the normal particle
             * pass rather than drawing stale alpha geometry here.
             */
            if (!renderMode.isAlpha()) {
                continue;
            }

            VertexConsumer cloakedBuffer =
                    cloakStrength <= 0.001F
                            ? buffer
                            : new CloakedParticleVertexConsumer(
                                    buffer,
                                    cloakStrength,
                                    renderMode
                            );

            EntityCloakRenderState.begin(
                    cloakStrength,
                    renderMode
            );

            try {
                deferred.particle().render(
                        cloakedBuffer,
                        deferred.camera(),
                        deferred.partialTick()
                );
            } finally {
                EntityCloakRenderState.end();
            }
        }

        MeshData meshData = buffer.build();

        if (meshData == null) {
            return;
        }

        /*
         * We are now after water/cloud rendering, so writing particle depth is
         * safe again and keeps overlapping particles/world geometry sensible.
         */
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.setShader(() -> cloakShader);

        BufferUploader.drawWithShader(meshData);
    }

    private record DeferredParticle(
            Particle particle,
            ClientSubLevel origin,
            Camera camera,
            float partialTick
    ) {
    }
}
