package net.fireboy.aerocloakingcore.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;

/**
 * Small holder used by the ALPHA entity render-order workaround.
 *
 * This deliberately lives outside the mixin package. Mixin reserves every
 * class under net.fireboy.aerocloakingcore.mixin.* for mixin classes and will
 * reject helper/nested classes there at runtime.
 */
public record DeferredEntityRender(
        Entity entity,
        double camX,
        double camY,
        double camZ,
        float partialTick,
        PoseStack poseStack,
        MultiBufferSource bufferSource
) {
}
