package net.fireboy.aerocloakingcore.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;

import net.fireboy.aerocloakingcore.client.CloakedParticleVertexConsumer;
import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.fireboy.aerocloakingcore.client.EntityCloakRenderState;
import net.fireboy.aerocloakingcore.client.ParticleCloakShader;
import net.fireboy.aerocloakingcore.client.ParticleCloakRenderQueue;
import net.fireboy.aerocloakingcore.network.CloakingClient;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.TextureManager;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * Makes particles produced by a Sable sublevel inherit that sublevel's current
 * viewer-specific cloak strength AND render mode.
 *
 * Origin tracking is retained for the particle's whole lifetime, even after
 * the particle drifts away from the ship. Strength is looked up every render
 * frame, so existing smoke/exhaust fades in real time as the cloak changes.
 *
 * Built-in Minecraft particle batches keep their normal batching/order. A
 * small amount of per-particle cloak data is packed into unused low light-map
 * bits and decoded by Aero's dedicated particle shader:
 *
 *   DITHER        -> stable screen-space fragment discard
 *   ALPHA         -> true fragment alpha = original alpha * (1 - strength)
 *   ALPHA_SURFACE -> same true alpha behaviour for particle sprites
 *
 * ALPHA_SURFACE intentionally matches ALPHA for particles because a particle
 * is already a single camera-facing surface; the terrain-only depth pre-pass
 * has no meaningful equivalent here.
 */
@Mixin(value = ParticleEngine.class, priority = 500)
public abstract class ParticleEngineCloakMixin {

    @Shadow
    protected ClientLevel level;

    @Unique
    private final Map<Particle, ClientSubLevel>
            aerocloakingcore$particleOrigins = new WeakHashMap<>();

    /**
     * Children spawned while another particle ticks inherit the parent's
     * originating sublevel if Sable cannot identify them directly.
     */
    @Unique
    private static final ThreadLocal<ClientSubLevel>
            aerocloakingcore$tickingParticleOrigin = new ThreadLocal<>();

    @Unique
    private ParticleRenderType aerocloakingcore$currentRenderType;

    @Unique
    private boolean aerocloakingcore$currentBatchHasEncodedCloak;

    @Unique
    private boolean aerocloakingcore$currentBatchNeedsBlend;


    /** Capture before Sable performs its initial plot-space -> world-space kick. */
    @Inject(method = "add", at = @At("HEAD"))
    private void aerocloakingcore$captureParticleOriginBeforeKick(
            Particle particle,
            CallbackInfo ci
    ) {
        aerocloakingcore$rememberOriginIfPossible(particle, true);
    }


    /**
     * Back-up capture for particles whose Sable tracking becomes available
     * only during add().
     */
    @Inject(method = "add", at = @At("RETURN"))
    private void aerocloakingcore$captureParticleOriginAfterKick(
            Particle particle,
            CallbackInfo ci
    ) {
        if (aerocloakingcore$particleOrigins.containsKey(particle)) {
            return;
        }

        if (particle instanceof ParticleExtension extension) {
            extension.sable$initialKickOut();
        }

        aerocloakingcore$rememberOriginIfPossible(particle, false);
    }


    @Inject(method = "tickParticle", at = @At("HEAD"))
    private void aerocloakingcore$beginParticleTick(
            Particle particle,
            CallbackInfo ci
    ) {
        ClientSubLevel origin = aerocloakingcore$getParticleOrigin(particle);

        if (origin != null) {
            aerocloakingcore$tickingParticleOrigin.set(origin);
        } else {
            aerocloakingcore$tickingParticleOrigin.remove();
        }
    }


    @Inject(method = "tickParticle", at = @At("RETURN"))
    private void aerocloakingcore$endParticleTick(
            Particle particle,
            CallbackInfo ci
    ) {
        aerocloakingcore$tickingParticleOrigin.remove();
    }


    @Inject(method = "setLevel", at = @At("HEAD"))
    private void aerocloakingcore$clearParticleOrigins(
            ClientLevel newLevel,
            CallbackInfo ci
    ) {
        aerocloakingcore$particleOrigins.clear();
        aerocloakingcore$tickingParticleOrigin.remove();
        aerocloakingcore$currentRenderType = null;
        aerocloakingcore$currentBatchHasEncodedCloak = false;
        aerocloakingcore$currentBatchNeedsBlend = false;
    }


    /** Reset per-batch state without changing Minecraft's batch order. */
    @Redirect(
            method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
            require = 1,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/particle/ParticleRenderType;begin(Lcom/mojang/blaze3d/vertex/Tesselator;Lnet/minecraft/client/renderer/texture/TextureManager;)Lcom/mojang/blaze3d/vertex/BufferBuilder;"
            )
    )
    private BufferBuilder aerocloakingcore$beginParticleBatch(
            ParticleRenderType renderType,
            Tesselator tesselator,
            TextureManager textureManager
    ) {
        aerocloakingcore$currentRenderType = renderType;
        aerocloakingcore$currentBatchHasEncodedCloak = false;
        aerocloakingcore$currentBatchNeedsBlend = false;

        return renderType.begin(tesselator, textureManager);
    }


    /**
     * Render a particle using its origin sublevel's current strength/mode.
     *
     * The full-cloak early return is kept because zero-alpha particle geometry
     * would still write depth in several vanilla particle sheets.
     */
    @Redirect(
            method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
            require = 1,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V"
            )
    )
    private void aerocloakingcore$renderCloakedParticle(
            Particle particle,
            VertexConsumer buffer,
            Camera camera,
            float partialTick
    ) {
        ClientSubLevel origin = aerocloakingcore$getParticleOrigin(particle);

        if (origin == null) {
            particle.render(buffer, camera, partialTick);
            return;
        }

        float cloakStrength =
                CloakingClient.getViewerCloakStrength(origin);

        if (cloakStrength <= 0.001F) {
            particle.render(buffer, camera, partialTick);
            return;
        }

        if (cloakStrength >= 0.999F) {
            return;
        }

        CloakRenderMode renderMode =
                CloakingClient.getRenderMode(origin);

        boolean builtInParticleBatch =
                aerocloakingcore$usesAeroParticleShader(
                        aerocloakingcore$currentRenderType
                );

        /*
         * True-alpha particle geometry must not be submitted in Minecraft's
         * normal early/particle targets. Some sheets render before water and
         * all particles render before clouds; writing their quad depth there
         * recreates the same water/cloud hole we fixed for alpha BERs. Queue
         * built-in alpha particles for the late world pass instead. DITHER
         * remains in the normal particle pass and keeps its working ordering.
         */
        if (builtInParticleBatch && renderMode.isAlpha()) {
            ParticleCloakRenderQueue.enqueueAlpha(
                    particle,
                    aerocloakingcore$currentRenderType,
                    origin,
                    camera,
                    partialTick
            );
            return;
        }

        VertexConsumer cloakedBuffer = buffer;

        if (builtInParticleBatch) {
            cloakedBuffer = new CloakedParticleVertexConsumer(
                    buffer,
                    cloakStrength,
                    renderMode
            );

            aerocloakingcore$currentBatchHasEncodedCloak = true;

            if (renderMode.isAlpha()) {
                aerocloakingcore$currentBatchNeedsBlend = true;
            }
        }

        /*
         * CUSTOM particles often ignore the supplied BufferBuilder and issue
         * immediate RenderType draws of their own. The existing entity/block-
         * entity cloak state gives those draws the same mode as a fallback.
         * It is harmless for ordinary particles that only write vertices.
         */
        EntityCloakRenderState.begin(
                cloakStrength,
                renderMode
        );

        try {
            particle.render(
                    cloakedBuffer,
                    camera,
                    partialTick
            );
        } finally {
            EntityCloakRenderState.end();
        }
    }


    /**
     * Draw the existing particle batch with Aero's dedicated particle shader
     * only when that batch actually contains encoded cloaked particles.
     *
     * Alpha modes temporarily enable standard source-alpha blending for opaque
     * vanilla sheets. Blend factors, blend enable state, and the previously
     * selected shader are restored immediately after the draw so no particle
     * state leaks into water/cloud/terrain rendering.
     */
    @Redirect(
            method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
            require = 1,
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/BufferUploader;drawWithShader(Lcom/mojang/blaze3d/vertex/MeshData;)V"
            )
    )
    private void aerocloakingcore$drawParticleBatchWithCloak(
            MeshData meshData
    ) {
        ShaderInstance cloakShader = ParticleCloakShader.get();

        boolean useCloakShader =
                cloakShader != null
                        && aerocloakingcore$currentBatchHasEncodedCloak
                        && aerocloakingcore$usesAeroParticleShader(
                                aerocloakingcore$currentRenderType
                        );

        ShaderInstance previousShader =
                RenderSystem.getShader();

        boolean blendWasEnabled =
                GL11C.glIsEnabled(GL11C.GL_BLEND);

        boolean changedBlendState =
                aerocloakingcore$currentBatchNeedsBlend
                        && !blendWasEnabled;

        int previousBlendSrcRgb = 0;
        int previousBlendDstRgb = 0;
        int previousBlendSrcAlpha = 0;
        int previousBlendDstAlpha = 0;

        if (changedBlendState) {
            previousBlendSrcRgb =
                    GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB);
            previousBlendDstRgb =
                    GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB);
            previousBlendSrcAlpha =
                    GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA);
            previousBlendDstAlpha =
                    GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA);

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
        }

        if (useCloakShader) {
            RenderSystem.setShader(() -> cloakShader);
        }

        try {
            BufferUploader.drawWithShader(meshData);
        } finally {
            if (useCloakShader && previousShader != null) {
                RenderSystem.setShader(() -> previousShader);
            }

            if (changedBlendState) {
                RenderSystem.blendFuncSeparate(
                        previousBlendSrcRgb,
                        previousBlendDstRgb,
                        previousBlendSrcAlpha,
                        previousBlendDstAlpha
                );
                RenderSystem.disableBlend();
            }

            aerocloakingcore$currentBatchHasEncodedCloak = false;
            aerocloakingcore$currentBatchNeedsBlend = false;
        }
    }


    @Unique
    private static boolean aerocloakingcore$usesAeroParticleShader(
            ParticleRenderType renderType
    ) {
        return renderType == ParticleRenderType.TERRAIN_SHEET
                || renderType == ParticleRenderType.PARTICLE_SHEET_OPAQUE
                || renderType == ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT
                || renderType == ParticleRenderType.PARTICLE_SHEET_LIT;
    }


    @Unique
    private ClientSubLevel aerocloakingcore$getParticleOrigin(
            Particle particle
    ) {
        ClientSubLevel origin =
                aerocloakingcore$particleOrigins.get(particle);

        if (origin != null) {
            return origin;
        }

        aerocloakingcore$rememberOriginIfPossible(particle, false);
        return aerocloakingcore$particleOrigins.get(particle);
    }


    @Unique
    private void aerocloakingcore$rememberOriginIfPossible(
            Particle particle,
            boolean particleMayStillBeInPlotSpace
    ) {
        if (aerocloakingcore$particleOrigins.containsKey(particle)) {
            return;
        }

        ClientSubLevel origin =
                aerocloakingcore$getSableTrackingSubLevel(particle);

        if (origin == null) {
            origin = aerocloakingcore$tickingParticleOrigin.get();
        }

        if (origin == null && particleMayStillBeInPlotSpace) {
            origin = Sable.HELPER.getContainingClient(
                    particle.getBoundingBox().getCenter()
            );
        }

        if (origin == null && this.level != null) {
            Iterable<SubLevel> intersecting =
                    Sable.HELPER.getAllIntersecting(
                            this.level,
                            new BoundingBox3d(
                                    particle.getBoundingBox()
                            )
                    );

            for (SubLevel subLevel : intersecting) {
                if (subLevel instanceof ClientSubLevel clientSubLevel) {
                    origin = clientSubLevel;
                    break;
                }
            }
        }

        if (origin != null) {
            aerocloakingcore$particleOrigins.put(
                    particle,
                    origin
            );
        }
    }


    @Unique
    private static ClientSubLevel aerocloakingcore$getSableTrackingSubLevel(
            Particle particle
    ) {
        if (!(particle instanceof ParticleExtension extension)) {
            return null;
        }

        SubLevel subLevel = extension.sable$getTrackingSubLevel();

        return subLevel instanceof ClientSubLevel clientSubLevel
                ? clientSubLevel
                : null;
    }
}
