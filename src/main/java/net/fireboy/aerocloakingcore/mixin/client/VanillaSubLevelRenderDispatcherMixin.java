package net.fireboy.aerocloakingcore.mixin.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;

import net.fireboy.aerocloakingcore.client.AlphaSubLevelRenderQueue;
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

        boolean alphaCloakActive =
                CloakingClient.getRenderMode(
                        renderData.getSubLevel()
                ).isAlpha()
                        && CloakingClient.getCloakStrength(
                                renderData.getSubLevel().getUniqueId()
                        ) > 0.0001F;

        if (!alphaCloakActive) {
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

        /*
         * Capture every layer while Minecraft/Sable still have that layer's
         * proper shader, fog, lightmap, colour and matrices configured.
         */
        AlphaSubLevelRenderQueue.enqueue(
                renderData,
                renderType,
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
