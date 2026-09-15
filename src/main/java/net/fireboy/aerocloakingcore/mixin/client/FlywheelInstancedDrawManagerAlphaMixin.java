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
 * While an ALPHA cloak is active, temporarily route every Flywheel instancing
 * draw through Flywheel's native OIT path.  The fragment shader suppresses the
 * alpha-cloaked sublevel in the normal pass and suppresses everything else in
 * the late pass.
 *
 * <p>Routing all draws only while alpha is active avoids depending on
 * Flywheel's internal InstancedDraw/GroupKey Java types and keeps DITHER's
 * normal fast path unchanged whenever no alpha fade is happening.</p>
 */
@Mixin(
        targets = "dev.engine_room.flywheel.backend.engine.instancing.InstancedDrawManager",
        priority = 900,
        remap = false
)
public abstract class FlywheelInstancedDrawManagerAlphaMixin {

    @Shadow
    private boolean needSort;

    @Shadow
    @Final
    @SuppressWarnings("rawtypes")
    private List draws;

    @Shadow
    @Final
    @SuppressWarnings("rawtypes")
    private List oitDraws;

    @Unique
    private boolean aerocloakingcore$lastAlphaRouting;

    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void aerocloakingcore$refreshInstancingBucketMode(CallbackInfo ci) {
        boolean alphaRouting = FlywheelAlphaRenderState.isAlphaActive();

        if (alphaRouting != aerocloakingcore$lastAlphaRouting) {
            // Let Flywheel rebuild its normal material buckets first.  Our
            // second hook then folds the solid bucket into OIT when required.
            needSort = true;
            aerocloakingcore$lastAlphaRouting = alphaRouting;
        }
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/engine_room/flywheel/backend/engine/MeshPool;flush()V",
                    shift = At.Shift.BEFORE
            ),
            remap = false
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void aerocloakingcore$routeInstancingThroughOit(CallbackInfo ci) {
        if (!FlywheelAlphaRenderState.isAlphaActive() || draws.isEmpty()) {
            return;
        }

        oitDraws.addAll(draws);
        draws.clear();
    }
}
