package net.fireboy.aerocloakingcore.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;

import net.fireboy.aerocloakingcore.client.AlphaSubLevelRenderQueue;
import net.fireboy.aerocloakingcore.client.EntityOcclusionDepthMask;
import net.fireboy.aerocloakingcore.client.RopeCloakRenderQueue;
import net.fireboy.aerocloakingcore.network.CloakingClient;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.world.entity.Entity;

import org.joml.Matrix4f;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Implements OCCLUDED_ONLY as a late, depth-masked entity pass.
 *
 * <p>The first implementation temporarily inserted the invisible hull into the
 * main depth buffer during vanilla's normal entity phase and restored the old
 * depth afterward. That restore also discarded depth written by entities (and
 * could discard Flywheel depth depending on injection ordering), so water and
 * other later translucent world passes were free to draw over them.</p>
 *
 * <p>Now remote entities are deferred while a cloak hull is active. Minecraft
 * finishes its normal world in the original order first: block entities,
 * Flywheel, translucent terrain/water, particles, clouds, weather, Fabulous
 * composition, and Aero's own late ALPHA ship replay. Immediately after
 * renderDebug -- while the correct camera model-view matrix is still active --
 * the completed world depth is saved, the invisible cloak hull is added, the
 * deferred entities are rendered fully visible through that depth mask, and
 * the completed world depth is restored.</p>
 */
@Mixin(value = LevelRenderer.class, priority = 1090)
public abstract class LevelRendererEntityOcclusionMaskMixin {

    @Unique
    private final List<DeferredOccludedEntity> aerocloakingcore$deferredOccludedEntities =
            new ArrayList<>();

    @Unique
    private boolean aerocloakingcore$replayingOccludedEntities;

    @Invoker("renderEntity")
    protected abstract void aerocloakingcore$invokeRenderEntity(
            Entity entity,
            double camX,
            double camY,
            double camZ,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource
    );

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void aerocloakingcore$beginEntityOcclusionFrame(
            DeltaTracker deltaTracker,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f frustumMatrix,
            Matrix4f projectionMatrix,
            CallbackInfo ci
    ) {
        aerocloakingcore$deferredOccludedEntities.clear();
        aerocloakingcore$replayingOccludedEntities = false;

        // Defensive cleanup if a previous frame was interrupted by an exception.
        EntityOcclusionDepthMask.end();
    }

    /**
     * The local player's own third-person model stays on the normal render path
     * so the existing "always visible to yourself" rule remains intact.
     */
    @Inject(
            method = "renderEntity",
            at = @At("HEAD"),
            cancellable = true
    )
    private void aerocloakingcore$deferEntitiesForLateOcclusion(
            Entity entity,
            double camX,
            double camY,
            double camZ,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            CallbackInfo ci
    ) {
        if (aerocloakingcore$replayingOccludedEntities
                || !CloakingClient.usesEntityOcclusionMask()) {
            return;
        }

        boolean hasTerrainDepth =
                AlphaSubLevelRenderQueue.hasEntityOcclusionDepth();
        boolean hasRopeDepth =
                RopeCloakRenderQueue.hasEntityOcclusionDepth();

        if (!hasTerrainDepth && !hasRopeDepth) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null
                && entity.getUUID().equals(minecraft.player.getUUID())) {
            return;
        }

        aerocloakingcore$deferredOccludedEntities.add(
                new DeferredOccludedEntity(
                        entity,
                        camX,
                        camY,
                        camZ,
                        partialTick,
                        bufferSource
                )
        );

        ci.cancel();
    }

    /**
     * In Fabulous graphics, several entity RenderTypes normally target the
     * item-entity framebuffer. By the time this late replay runs, vanilla has
     * already composited that framebuffer. Route those layers directly to the
     * main target during this replay so they appear in the current frame.
     */
    @Inject(
            method = "getItemEntityTarget",
            at = @At("HEAD"),
            cancellable = true
    )
    private void aerocloakingcore$routeLateItemEntityLayersToMain(
            CallbackInfoReturnable<RenderTarget> cir
    ) {
        if (aerocloakingcore$replayingOccludedEntities) {
            cir.setReturnValue(Minecraft.getInstance().getMainRenderTarget());
        }
    }

    /**
     * Run after renderDebug, but before vanilla pops the world model-view stack.
     * All normal and Aero late world passes are complete at this point.
     */
    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderDebug(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/Camera;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void aerocloakingcore$renderLateOccludedEntities(
            DeltaTracker deltaTracker,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f frustumMatrix,
            Matrix4f projectionMatrix,
            CallbackInfo ci
    ) {
        if (aerocloakingcore$deferredOccludedEntities.isEmpty()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        /*
         * renderDebug can leave buffered lines waiting for vanilla's following
         * endLastBatch(). Submit those against the normal world depth before we
         * temporarily add the cloak hull, otherwise the hull would mask debug
         * geometry too.
         */
        minecraft.renderBuffers().bufferSource().endBatch();

        EntityOcclusionDepthMask.begin();

        try {
            aerocloakingcore$replayingOccludedEntities = true;

            for (DeferredOccludedEntity deferred
                    : aerocloakingcore$deferredOccludedEntities) {
                /*
                 * Vanilla's glowing path passes an OutlineBufferSource whose
                 * outline batch was already consumed earlier in renderLevel.
                 * Reusing it here would leave one-frame-late outline vertices.
                 * The normal entity colour still renders through the ordinary
                 * buffer source; the outline itself is intentionally omitted
                 * from this late masked replay.
                 */
                MultiBufferSource replaySource =
                        deferred.bufferSource() instanceof OutlineBufferSource
                                ? minecraft.renderBuffers().bufferSource()
                                : deferred.bufferSource();

                aerocloakingcore$invokeRenderEntity(
                        deferred.entity(),
                        deferred.camX(),
                        deferred.camY(),
                        deferred.camZ(),
                        deferred.partialTick(),
                        new PoseStack(),
                        replaySource
                );
            }

            /*
             * Entity RenderTypes are buffered. They MUST be submitted while the
             * invisible hull depth is still present.
             */
            minecraft.renderBuffers().bufferSource().endBatch();
        } finally {
            aerocloakingcore$replayingOccludedEntities = false;
            aerocloakingcore$deferredOccludedEntities.clear();

            /*
             * Restores the already-completed world's depth. No entity/Flywheel
             * depth needed by water can be erased here because all those world
             * passes finished before this temporary mask began.
             */
            EntityOcclusionDepthMask.end();
        }
    }

    @Unique
    private record DeferredOccludedEntity(
            Entity entity,
            double camX,
            double camY,
            double camZ,
            float partialTick,
            MultiBufferSource bufferSource
    ) {
    }
}
