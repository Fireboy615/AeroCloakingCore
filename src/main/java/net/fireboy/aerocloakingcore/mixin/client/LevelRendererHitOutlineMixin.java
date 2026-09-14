package net.fireboy.aerocloakingcore.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;

import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.fireboy.aerocloakingcore.client.config.AeroCloakingCoreClientConfig;
import net.fireboy.aerocloakingcore.network.CloakingClient;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import net.neoforged.neoforge.client.ClientHooks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suppresses block hover highlighting while a Sable sublevel is partially
 * cloaked with the ALPHA renderer.
 *
 * Hooking ClientHooks.onDrawHighlight is intentionally earlier than
 * LevelRenderer.renderHitOutline: Sable transforms the sublevel highlight
 * around this NeoForge hook, and other mods may also handle the highlight
 * here before vanilla renderHitOutline is reached.
 */
@Mixin(value = ClientHooks.class, priority = 2100)
public abstract class LevelRendererHitOutlineMixin {

    @Inject(
            method = "onDrawHighlight",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void aerocloakingcore$suppressAlphaCloakOutline(
            LevelRenderer context,
            Camera camera,
            HitResult target,
            DeltaTracker deltaTracker,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            CallbackInfoReturnable<Boolean> cir
    ) {

        if (AeroCloakingCoreClientConfig.RENDER_MODE.get()
                != CloakRenderMode.ALPHA) {
            return;
        }

        if (!(target instanceof BlockHitResult blockTarget)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null) {
            return;
        }

        Object containing =
                Sable.HELPER.getContaining(
                        minecraft.level,
                        blockTarget.getBlockPos()
                );

        if (!(containing instanceof ClientSubLevel subLevel)) {
            return;
        }

        float cloakStrength =
                CloakingClient.getViewerCloakStrength(subLevel);

        if (cloakStrength <= 0.0001F) {
            return;
        }

        /*
         * Returning true means the highlight has been handled. Because this
         * injection runs before NeoForge posts RenderHighlightEvent.Block,
         * neither a modded highlight nor vanilla's fallback outline is drawn.
         */
        cir.setReturnValue(true);
    }
}
