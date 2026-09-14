package net.fireboy.aerocloakingcore.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.fireboy.aerocloakingcore.client.screen.CloakingCoreScreen;
import net.fireboy.aerocloakingcore.menu.ModMenus;
import net.fireboy.aerocloakingcore.network.CloakingClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only registration and render hooks. */
@Mod(value = AeroCloakingCore.MOD_ID, dist = Dist.CLIENT)
public final class AeroCloakingCoreClient {

    /**
     * Mesh for the current targeted block outline.
     *
     * It is built while Sable has its transformed sublevel PoseStack/camera
     * active, but deliberately drawn later after translucent block rendering.
     */
    private final ByteBufferBuilder outlineBuffer = new ByteBufferBuilder(4096);

    private MeshData pendingAlphaOutline;

    public AeroCloakingCoreClient(IEventBus modEventBus) {
        modEventBus.addListener(this::registerScreens);

        NeoForge.EVENT_BUS.addListener(this::onBlockHighlight);
        NeoForge.EVENT_BUS.addListener(this::onRenderLevelStage);
    }

    private void registerScreens(RegisterMenuScreensEvent event) {
        event.register(
                ModMenus.CLOAKING_CORE.get(),
                CloakingCoreScreen::new
        );
    }

    /**
     * Replaces the normal target outline for an actively-cloaking ALPHA
     * sublevel.
     *
     * Sable calls NeoForge's highlight hook with a transformed PoseStack and
     * sublevel camera. We use those transforms to BUILD the exact vanilla
     * outline now, but do not DRAW it now. Drawing it during the normal
     * highlight pass happens before the ALPHA sublevel's delayed translucent
     * render and is what caused the visible cracks/seams.
     */
    private void onBlockHighlight(RenderHighlightEvent.Block event) {
        clearPendingOutline();

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        BlockHitResult target = event.getTarget();
        BlockPos blockPos = target.getBlockPos();

        SubLevel containing =
                Sable.HELPER.getContaining(
                        minecraft.level,
                        blockPos
                );

        if (!(containing instanceof ClientSubLevel subLevel)) {
            return;
        }

        // DITHER keeps the normal Minecraft/Sable target outline.
        if (CloakingClient.getRenderMode(subLevel)
                != CloakRenderMode.ALPHA) {
            return;
        }

        /*
         * Use the core/base cloak strength rather than viewer strength.
         * Proximity reveal can make the viewer strength 0 while the core is
         * still actively cloaking; we still need the safe late outline path in
         * that situation.
         */
        if (CloakingClient.getCloakStrength(
                subLevel.getUniqueId()
        ) <= 0.0001F) {
            return;
        }

        // Stop the original early selection outline from being queued.
        event.setCanceled(true);

        BlockState state =
                subLevel.getLevel().getBlockState(blockPos);

        VoxelShape shape =
                state.getShape(
                        subLevel.getLevel(),
                        blockPos,
                        CollisionContext.of(minecraft.player)
                );

        if (shape.isEmpty()) {
            return;
        }

        Vec3 cameraPosition =
                event.getCamera().getPosition();

        double x = blockPos.getX() - cameraPosition.x;
        double y = blockPos.getY() - cameraPosition.y;
        double z = blockPos.getZ() - cameraPosition.z;

        outlineBuffer.clear();

        BufferBuilder builder =
                new BufferBuilder(
                        outlineBuffer,
                        VertexFormat.Mode.LINES,
                        DefaultVertexFormat.POSITION_COLOR_NORMAL
                );

        /*
         * This intentionally mirrors vanilla LevelRenderer.renderShape(),
         * rather than LevelRenderer.renderVoxelShape().
         *
         * renderVoxelShape() splits a stair/complex VoxelShape into separate
         * AABBs and outlines every AABB, which creates the extra line through
         * the middle of stair sides. forAllEdges() on the complete VoxelShape
         * gives us vanilla's actual outer selection edges.
         */
        renderVanillaShape(
                event.getPoseStack(),
                builder,
                shape,
                x,
                y,
                z,
                0.0F,
                0.0F,
                0.0F,
                0.4F
        );

        pendingAlphaOutline = builder.build();
    }

    /**
     * Draw after the ALPHA sublevel has completed its delayed translucent
     * block render. At this point the outline blends over the already-composed
     * sublevel instead of becoming a transparent-looking crack through it.
     */
    private void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage()
                != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }

        MeshData outline = pendingAlphaOutline;
        pendingAlphaOutline = null;

        if (outline == null) {
            return;
        }

        RenderType lines = RenderType.lines();

        lines.setupRenderState();

        try {
            /*
             * Keep normal depth TESTING so hidden edges stay hidden, but do not
             * let the selection outline WRITE depth into later render passes.
             */
            RenderSystem.depthMask(false);
            BufferUploader.drawWithShader(outline);
        } finally {
            RenderSystem.depthMask(true);
            lines.clearRenderState();
        }
    }

    /**
     * Copy of vanilla 1.21.1's private LevelRenderer.renderShape logic.
     * Keeping the full VoxelShape intact is important for stairs and other
     * non-cubic blocks because it avoids outlining the internal component
     * boxes individually.
     */
    private static void renderVanillaShape(
            PoseStack poseStack,
            VertexConsumer consumer,
            VoxelShape shape,
            double x,
            double y,
            double z,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        PoseStack.Pose pose = poseStack.last();

        shape.forAllEdges(
                (x1, y1, z1, x2, y2, z2) -> {
                    float normalX = (float) (x2 - x1);
                    float normalY = (float) (y2 - y1);
                    float normalZ = (float) (z2 - z1);

                    float length = Mth.sqrt(
                            normalX * normalX
                                    + normalY * normalY
                                    + normalZ * normalZ
                    );

                    if (length <= 0.000001F) {
                        return;
                    }

                    normalX /= length;
                    normalY /= length;
                    normalZ /= length;

                    consumer.addVertex(
                                    pose,
                                    (float) (x1 + x),
                                    (float) (y1 + y),
                                    (float) (z1 + z)
                            )
                            .setColor(red, green, blue, alpha)
                            .setNormal(
                                    pose,
                                    normalX,
                                    normalY,
                                    normalZ
                            );

                    consumer.addVertex(
                                    pose,
                                    (float) (x2 + x),
                                    (float) (y2 + y),
                                    (float) (z2 + z)
                            )
                            .setColor(red, green, blue, alpha)
                            .setNormal(
                                    pose,
                                    normalX,
                                    normalY,
                                    normalZ
                            );
                }
        );
    }

    private void clearPendingOutline() {
        if (pendingAlphaOutline != null) {
            pendingAlphaOutline.close();
            pendingAlphaOutline = null;
        }
    }
}
