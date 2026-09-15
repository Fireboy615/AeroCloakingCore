package net.fireboy.aerocloakingcore.mixin.client;

import java.util.List;

import net.fireboy.aerocloakingcore.client.FlywheelAlphaRenderState;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Indirect-backend counterpart to FlywheelInstancedDrawManagerAlphaMixin.
 *
 * <p>IndirectCullingGroup normally puts only materials declared
 * ORDER_INDEPENDENT into its OIT list.  While an AeroCloakingCore ALPHA fade
 * exists we force a re-sort, then fold the normal multi-draw list into OIT.
 * When the fade ends another re-sort restores Flywheel's original buckets.</p>
 */
@Mixin(
        targets = "dev.engine_room.flywheel.backend.engine.indirect.IndirectCullingGroup",
        priority = 900,
        remap = false
)
public abstract class FlywheelIndirectCullingGroupAlphaMixin {

    @Shadow
    private boolean needsDrawSort;

    @Shadow
    @Final
    @SuppressWarnings("rawtypes")
    private List multiDraws;

    @Shadow
    @Final
    @SuppressWarnings("rawtypes")
    private List oitDraws;

    @Unique
    private boolean aerocloakingcore$lastAlphaRouting;

    @Inject(method = "upload", at = @At("HEAD"), remap = false)
    private void aerocloakingcore$refreshIndirectBucketMode(CallbackInfo ci) {
        boolean alphaRouting = FlywheelAlphaRenderState.isAlphaActive();

        if (alphaRouting != aerocloakingcore$lastAlphaRouting) {
            needsDrawSort = true;
            aerocloakingcore$lastAlphaRouting = alphaRouting;
        }
    }

    @Inject(method = "sortDraws()V", at = @At("RETURN"), remap = false)
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void aerocloakingcore$routeIndirectThroughOit(CallbackInfo ci) {
        if (!FlywheelAlphaRenderState.isAlphaActive() || multiDraws.isEmpty()) {
            return;
        }

        oitDraws.addAll(multiDraws);
        multiDraws.clear();
    }
}
