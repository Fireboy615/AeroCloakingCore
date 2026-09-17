package net.fireboy.aerocloakingcore.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;

import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;

import net.fireboy.aerocloakingcore.client.RopeCloakRenderQueue;

import net.minecraft.client.renderer.MultiBufferSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces Simulated's whole-rope inheritance only when at least one rope
 * endpoint is actually cloaked.
 */
@Mixin(
        targets = "dev.simulated_team.simulated.content.blocks.rope.strand.client.RopeStrandRenderer",
        remap = false
)
public abstract class RopeStrandRendererCloakMixin {

    @Inject(
            method = "render",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void aerocloakingcore$renderRopeWithEndpointCloak(
            SmartBlockEntity blockEntity,
            RopeStrandHolderBehavior ropeHolder,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            CallbackInfo ci
    ) {
        if (RopeCloakRenderQueue.handle(
                blockEntity,
                ropeHolder,
                partialTick,
                poseStack
        )) {
            ci.cancel();
        }
    }
}
