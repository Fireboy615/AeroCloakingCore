package net.fireboy.aerocloakingcore.mixin.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;

import net.fireboy.aerocloakingcore.client.AlphaSubLevelRenderQueue;
import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.fireboy.aerocloakingcore.network.CloakingClient;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;

import org.joml.Matrix4f;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;

import java.util.Iterator;

@Mixin(
        targets = "dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher"
)
public abstract class VanillaSubLevelRenderDispatcherMixin {

    @ModifyVariable(
            method = "renderSectionLayer",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private Iterable<ClientSubLevel> aerocloakingcore$filterSectionRender(
            Iterable<ClientSubLevel> subLevels
    ) {
        return filter(subLevels);
    }

    @ModifyVariable(
            method = "renderAfterSections",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private Iterable<ClientSubLevel> aerocloakingcore$filterAfterSections(
            Iterable<ClientSubLevel> subLevels
    ) {
        return filter(subLevels);
    }

    @ModifyVariable(
            method = "renderBlockEntities",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private Iterable<ClientSubLevel> aerocloakingcore$filterBlockEntities(
            Iterable<ClientSubLevel> subLevels
    ) {
        return filter(subLevels);
    }

    @ModifyVariable(
            method = "updateCulling",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private Iterable<ClientSubLevel> aerocloakingcore$filterCulling(
            Iterable<ClientSubLevel> subLevels
    ) {
        return filter(subLevels);
    }

    /**
     * ALPHA block geometry cannot render in its normal early terrain passes,
     * because the depth it writes would incorrectly reject later world water
     * and clouds.
     *
     * Instead of capturing only the translucent call and replaying every layer
     * through one shader, capture EACH original terrain-layer call. That gives
     * AlphaSubLevelRenderQueue enough information to restore the correct
     * solid/cutout/translucent shader and Sable lighting state at the late
     * replay point.
     */
    /**
     * Intercepts Sable's terrain draw without taking ownership of the invocation.
     *
     * Using WrapWithCondition instead of Redirect is important for compatibility:
     * other mods such as Vestalihy redirect this same renderChunkedSubLevel call.
     *
     * Returning true allows Sable/the downstream redirect to perform the draw.
     * Returning false suppresses the early draw after ALPHA has been queued for
     * the late world pass.
     */
    @WrapWithCondition(
            method = "renderSectionLayer",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/sublevel/render/vanilla/VanillaChunkedSubLevelRenderData;renderChunkedSubLevel(Lnet/minecraft/client/renderer/RenderType;Lnet/minecraft/client/renderer/ShaderInstance;Lorg/joml/Matrix4f;DDD)V"
            )
    )
    private boolean aerocloakingcore$deferAlphaRenderToEndOfWorldPass(
            VanillaChunkedSubLevelRenderData renderData,
            RenderType renderType,
            ShaderInstance shader,
            Matrix4f modelView,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {

        ClientSubLevel subLevel = renderData.getSubLevel();

        float cloakStrength =
                CloakingClient.getViewerCloakStrength(subLevel);

        CloakRenderMode renderMode =
                CloakingClient.getRenderMode(subLevel);

        /*
         * No active cloak.
         *
         * Allow the original invocation to continue. If another mod such as
         * Vestalihy redirects it, that redirect can still handle the draw.
         */
        if (cloakStrength <= 0.0001F) {
            return true;
        }

        if (renderMode == CloakRenderMode.DITHER) {
            /*
             * DITHER still renders normally at Sable's original point.
             *
             * We only capture the opaque/cutout layers so they can be replayed
             * later as the depth mask used by late direct effects.
             */
            AlphaSubLevelRenderQueue.enqueueDitherDepth(
                    renderData,
                    renderType,
                    modelView,
                    cameraX,
                    cameraY,
                    cameraZ
            );

            return true;
        }

        if (renderMode.isAlpha()) {
            /*
             * ALPHA must not draw during Sable's early terrain pass.
             *
             * Capture the render state here, then suppress this invocation.
             * AlphaSubLevelRenderQueue will replay it during the late pass.
             */
            AlphaSubLevelRenderQueue.enqueue(
                    renderData,
                    renderType,
                    modelView,
                    cameraX,
                    cameraY,
                    cameraZ
            );

            return false;
        }

        return true;
    }

    private static Iterable<ClientSubLevel> filter(
            Iterable<ClientSubLevel> subLevels
    ) {
        return () -> new Iterator<>() {

            private final Iterator<ClientSubLevel> original =
                    subLevels.iterator();

            private ClientSubLevel next;
            private boolean prepared = false;

            private void prepare() {
                if (prepared) {
                    return;
                }

                while (original.hasNext()) {
                    ClientSubLevel candidate =
                            original.next();

                    if (!CloakingClient.shouldHideSubLevel(
                            candidate
                    )) {
                        next = candidate;
                        prepared = true;
                        return;
                    }
                }

                next = null;
                prepared = true;
            }

            @Override
            public boolean hasNext() {
                prepare();
                return next != null;
            }

            @Override
            public ClientSubLevel next() {
                prepare();

                ClientSubLevel result =
                        next;

                prepared = false;
                next = null;

                return result;
            }
        };
    }
}
