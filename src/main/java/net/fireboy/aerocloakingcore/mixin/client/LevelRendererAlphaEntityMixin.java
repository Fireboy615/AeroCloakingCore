package net.fireboy.aerocloakingcore.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.fireboy.aerocloakingcore.client.DeferredEntityRender;
import net.fireboy.aerocloakingcore.network.CloakingClient;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;

import org.joml.Matrix4f;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;


/**
 * ALPHA entity rendering has the same world-water ordering problem the
 * sublevel blocks originally had: entities are normally drawn before the
 * world's translucent terrain.
 *
 * While an associated entity is partially alpha-cloaked, defer its render
 * call until the end of the world pass, after normal water/translucent terrain
 * has already been drawn. DITHER entities keep Minecraft's normal entity
 * render timing because they do not change alpha/depth behaviour.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererAlphaEntityMixin {

    @Unique
    private final List<DeferredEntityRender> aerocloakingcore$deferredAlphaEntities =
            new ArrayList<>();

    @Unique
    private boolean aerocloakingcore$replayingDeferredEntity;


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


    @Inject(
            method = "renderLevel",
            at = @At("HEAD")
    )
    private void aerocloakingcore$beginFrame(
            DeltaTracker deltaTracker,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f frustumMatrix,
            Matrix4f projectionMatrix,
            CallbackInfo ci
    ) {
        aerocloakingcore$deferredAlphaEntities.clear();
        aerocloakingcore$replayingDeferredEntity = false;
    }


    @Inject(
            method = "renderEntity",
            at = @At("HEAD"),
            cancellable = true
    )
    private void aerocloakingcore$deferPartialAlphaEntity(
            Entity entity,
            double camX,
            double camY,
            double camZ,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            CallbackInfo ci
    ) {

        if (aerocloakingcore$replayingDeferredEntity) {
            return;
        }

        if (!CloakingClient.getEntityRenderMode(entity).isAlpha()) {
            return;
        }

        float cloakStrength =
                CloakingClient.getEntityViewerCloakStrength(entity);

        // Visible entities render normally. Fully cloaked entities are handled
        // by EntityRenderDispatcherMixin and do not need to be queued.
        if (cloakStrength <= 0.001F
                || cloakStrength >= 0.999F) {
            return;
        }

        aerocloakingcore$deferredAlphaEntities.add(
                new DeferredEntityRender(
                        entity,
                        camX,
                        camY,
                        camZ,
                        partialTick,
                        poseStack,
                        bufferSource
                )
        );

        ci.cancel();
    }


    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderDebug(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/Camera;)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void aerocloakingcore$renderDeferredAlphaEntities(
            DeltaTracker deltaTracker,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f frustumMatrix,
            Matrix4f projectionMatrix,
            CallbackInfo ci
    ) {

        if (aerocloakingcore$deferredAlphaEntities.isEmpty()) {
            return;
        }

        aerocloakingcore$replayingDeferredEntity = true;

        try {
            for (DeferredEntityRender deferred :
                    aerocloakingcore$deferredAlphaEntities) {

                aerocloakingcore$invokeRenderEntity(
                        deferred.entity(),
                        deferred.camX(),
                        deferred.camY(),
                        deferred.camZ(),
                        deferred.partialTick(),
                        deferred.poseStack(),
                        deferred.bufferSource()
                );
            }
        } finally {
            aerocloakingcore$replayingDeferredEntity = false;
            aerocloakingcore$deferredAlphaEntities.clear();
        }
    }
}
