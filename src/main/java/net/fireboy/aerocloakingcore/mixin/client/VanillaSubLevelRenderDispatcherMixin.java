package net.fireboy.aerocloakingcore.mixin.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;

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
     * ALPHA mode cannot safely draw the sublevel's normally-opaque geometry
     * during Minecraft's opaque/cutout world passes. Doing that means the
     * later world-water/translucent pass sees depth/state produced by a
     * partially-transparent ship and the water visibly changes as soon as
     * the fade begins.
     *
     * Instead, suppress the sublevel's block geometry during the earlier
     * layer calls and draw all of its block buffers when Minecraft reaches
     * the translucent pass. At that point world water has already been drawn,
     * so the alpha ship can blend over it without changing the world's water
     * render state.
     *
     * DITHER mode keeps Sable's normal per-layer rendering unchanged.
     */
    @Redirect(
            method = "renderSectionLayer",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/sublevel/render/vanilla/VanillaChunkedSubLevelRenderData;renderChunkedSubLevel(Lnet/minecraft/client/renderer/RenderType;Lnet/minecraft/client/renderer/ShaderInstance;Lorg/joml/Matrix4f;DDD)V"
            )
    )
    private void aerocloakingcore$deferAlphaRenderToTranslucentPass(
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
                ) == CloakRenderMode.ALPHA
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
         * Skip the sublevel during world solid/cutout/etc. passes.
         * We replay its block buffers once the world translucent pass arrives.
         */
        if (renderType != RenderType.translucent()) {
            return;
        }

        /*
         * Use the already-active translucent pass/shader for all block-layer
         * buffers. They share Minecraft's BLOCK vertex format, while the
         * CloakingCore render-data mixin supplies the actual cloak alpha.
         *
         * Front-to-back self-occlusion is restored because we no longer force
         * depth writes off in ALPHA mode.
         */
        renderData.renderChunkedSubLevel(
                RenderType.solid(),
                shader,
                modelView,
                cameraX,
                cameraY,
                cameraZ
        );

        renderData.renderChunkedSubLevel(
                RenderType.cutoutMipped(),
                shader,
                modelView,
                cameraX,
                cameraY,
                cameraZ
        );

        renderData.renderChunkedSubLevel(
                RenderType.cutout(),
                shader,
                modelView,
                cameraX,
                cameraY,
                cameraZ
        );

        renderData.renderChunkedSubLevel(
                RenderType.translucent(),
                shader,
                modelView,
                cameraX,
                cameraY,
                cameraZ
        );

        renderData.renderChunkedSubLevel(
                RenderType.tripwire(),
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
                    ClientSubLevel candidate = original.next();

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

                ClientSubLevel result = next;

                prepared = false;
                next = null;

                return result;
            }
        };
    }
}
