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
import net.fireboy.aerocloakingcore.network.CloakingClient;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.TextureManager;

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
 * viewer-specific cloak strength.
 *
 * Origin tracking is retained for the particle's whole lifetime, even after
 * the particle drifts away from the ship. The actual opacity is NOT frozen at
 * spawn time: every frame it uses the sublevel's current cloak strength. This
 * prevents old smoke/exhaust from revealing a ship that has just cloaked.
 *
 * Standard particle batches keep Minecraft's existing ordering. Cloak strength
 * is packed into ignored light-map bits per particle and the particle shader
 * multiplies the final alpha. Opaque/lit particle batches are temporarily
 * blended only for their final draw when they contain a partially cloaked
 * particle, then the previous blend state is restored immediately.
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

    /** True when the current ParticleRenderType batch needs alpha blending. */
    @Unique
    private boolean aerocloakingcore$currentBatchNeedsBlend;


    /**
     * Capture before Sable performs its initial plot-space -> world-space kick.
     */
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
        aerocloakingcore$currentBatchNeedsBlend = false;
    }


    /**
     * Each particle render type owns one shared BufferBuilder. Reset the alpha
     * requirement when Minecraft begins a new batch.
     */
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
        aerocloakingcore$currentBatchNeedsBlend = false;
        return renderType.begin(tesselator, textureManager);
    }


    /**
     * Standard particles stay in their normal batch; only their vertices carry
     * the per-particle cloak strength. CUSTOM particles additionally get the
     * existing entity alpha state as a fallback for immediate RenderType draws.
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

        // Preserve the successful v3 behaviour: full/out-of-range cloak emits
        // no particle geometry at all.
        if (cloakStrength >= 0.999F) {
            return;
        }

        aerocloakingcore$currentBatchNeedsBlend = true;

        VertexConsumer cloakedBuffer =
                new CloakedParticleVertexConsumer(
                        buffer,
                        cloakStrength
                );

        if (particle.getRenderType() == ParticleRenderType.CUSTOM) {
            // Particles should fade smoothly even if terrain itself is using
            // DITHER mode, so force the already-tested entity path to ALPHA.
            EntityCloakRenderState.begin(
                    cloakStrength,
                    CloakRenderMode.ALPHA
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

            return;
        }

        particle.render(
                cloakedBuffer,
                camera,
                partialTick
        );
    }


    /**
     * Vanilla's opaque/lit particle sheets normally disable blending. If this
     * batch contains any partially cloaked particle, enable normal alpha blend
     * only around the actual GPU draw and immediately restore the old state.
     * This avoids leaking render state into water, clouds, or later passes.
     */
    @Redirect(
            method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
            require = 1,
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/BufferUploader;drawWithShader(Lcom/mojang/blaze3d/vertex/MeshData;)V"
            )
    )
    private void aerocloakingcore$drawParticleBatchWithCloakAlpha(
            MeshData meshData
    ) {
        if (!aerocloakingcore$currentBatchNeedsBlend) {
            BufferUploader.drawWithShader(meshData);
            return;
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        try {
            BufferUploader.drawWithShader(meshData);
        } finally {
            /*
             * Nothing is drawn between this call and the next particle type's
             * begin(), and ParticleEngine also disables blending when the
             * whole particle pass finishes. Restoring to disabled here keeps
             * this override tightly scoped and prevents state leaking out.
             */
            RenderSystem.disableBlend();
            aerocloakingcore$currentBatchNeedsBlend = false;
        }
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
