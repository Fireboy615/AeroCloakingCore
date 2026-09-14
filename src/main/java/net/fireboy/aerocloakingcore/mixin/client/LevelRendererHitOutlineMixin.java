package net.fireboy.aerocloakingcore.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;

import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.fireboy.aerocloakingcore.network.CloakingClient;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses Minecraft's normal targeted-block wireframe while the targeted
 * block belongs to a partially ALPHA-cloaked Sable sublevel.
 *
 * Sable still transforms the targeting/pose normally; this hooks the final
 * LevelRenderer.renderHitOutline(...) method that actually emits the line
 * geometry, so it cannot interfere with the delayed alpha render.
 */
@Mixin(value = LevelRenderer.class, priority = 1800)
public abstract class LevelRendererHitOutlineMixin {

    @Shadow
    @Nullable
    private ClientLevel level;

    @Inject(
            method = "renderHitOutline",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void aerocloakingcore$hideTargetOutlineDuringAlphaCloak(
            PoseStack poseStack,
            VertexConsumer consumer,
            Entity entity,
            double camX,
            double camY,
            double camZ,
            BlockPos pos,
            BlockState state,
            CallbackInfo ci
    ) {
        if (level == null) {
            return;
        }

        Object containing =
                Sable.HELPER.getContaining(level, pos);

        if (!(containing instanceof ClientSubLevel subLevel)) {
            return;
        }

        if (CloakingClient.getRenderMode(subLevel)
                != CloakRenderMode.ALPHA) {
            return;
        }

        float cloakStrength =
                CloakingClient.getViewerCloakStrength(subLevel);

        // Fully visible: retain Minecraft/Sable's normal targeted-block box.
        // Once alpha cloaking starts, suppress the wireframe completely.
        if (cloakStrength > 0.0001F) {
            ci.cancel();
        }
    }
}
