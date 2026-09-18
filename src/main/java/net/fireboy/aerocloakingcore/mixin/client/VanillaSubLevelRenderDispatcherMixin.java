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
import org.spongepowered.asm.mixin.injection.Redirect;

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
    @Redirect(
            method = "renderSectionLayer",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/sublevel/render/vanilla/VanillaChunkedSubLevelRenderData;renderChunkedSubLevel(Lnet/minecraft/client/renderer/RenderType;Lnet/minecraft/client/renderer/ShaderInstance;Lorg/joml/Matrix4f;DDD)V"
            )
    )
    private void aerocloakingcore$deferAlphaRenderToEndOfWorldPass(
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

        if (cloakStrength <= 0.0001F) {
            renderData.renderChunkedSubLevel(
                    renderType,
                    shader,
                    modelView,
                    cameraX,
                    cameraY,
                    cameraZ
            );
            return;
        }

        if (renderMode == CloakRenderMode.DITHER) {
            /*
             * Keep the normal DITHER colour draw exactly where Sable put it,
             * but also remember its opaque/cutout layers for a late depth-only
             * replay. Aeronautics' burner flame is a direct draw and needs
             * that main-target depth to match the visible dithered hull.
             */
            AlphaSubLevelRenderQueue.enqueueDitherDepth(
                    renderData,
                    renderType,
                    modelView,
                    cameraX,
                    cameraY,
                    cameraZ
            );

            renderData.renderChunkedSubLevel(
                    renderType,
                    shader,
                    modelView,
                    cameraX,
                    cameraY,
                    cameraZ
            );
            return;
        }

        if (renderMode.isAlpha()) {
            /*
             * Capture every layer while Minecraft/Sable still have that
             * layer's proper shader, fog, lightmap, colour and matrices
             * configured.
             */
            AlphaSubLevelRenderQueue.enqueue(
                    renderData,
                    renderType,
                    modelView,
                    cameraX,
                    cameraY,
                    cameraZ
            );
            return;
        }

        renderData.renderChunkedSubLevel(
                renderType,
                shader,
                modelView,
                cameraX,
                cameraY,
                cameraZ
        );
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
