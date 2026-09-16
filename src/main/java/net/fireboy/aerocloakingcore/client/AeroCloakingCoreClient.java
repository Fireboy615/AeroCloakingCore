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
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.fireboy.aerocloakingcore.block.entity.ModBlockEntities;
import net.fireboy.aerocloakingcore.client.render.CloakingCoreModels;
import net.fireboy.aerocloakingcore.client.render.CloakingCoreVisual;
import net.fireboy.aerocloakingcore.client.screen.AeroCloakingCoreConfigScreen;
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
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only registration and render hooks. */
@Mod(value = AeroCloakingCore.MOD_ID, dist = Dist.CLIENT)
public final class AeroCloakingCoreClient {

    /**
     * Reusable storage for the current ALPHA block outline.
     *
     * The outline is BUILT during NeoForge's highlight event while Sable's
     * transformed PoseStack/camera are active, then DRAWN later by
     * LevelRendererAlphaSubLevelMixin after the delayed ALPHA sublevel replay.
     */
    private static final ByteBufferBuilder OUTLINE_BUFFER =
            new ByteBufferBuilder(4096);

    private static MeshData pendingAlphaOutline;

    public AeroCloakingCoreClient(
            IEventBus modEventBus,
            ModContainer modContainer
    ) {
        // Load the rotor partial models on the client before they are needed.
        CloakingCoreModels.init();

        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::registerScreens);

        modContainer.registerExtensionPoint(
                IConfigScreenFactory.class,
                (container, parent) ->
                        new AeroCloakingCoreConfigScreen(parent)
        );

        NeoForge.EVENT_BUS.addListener(this::onBlockHighlight);
    }

    private void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() ->
                SimpleBlockEntityVisualizer
                        .builder(ModBlockEntities.CLOAKING_CORE.get())
                        .factory(CloakingCoreVisual::new)
                        .apply()
        );
    }

    private void registerScreens(RegisterMenuScreensEvent event) {
        event.register(
                ModMenus.CLOAKING_CORE.get(),
                CloakingCoreScreen::new
        );
    }

    /**
     * Captures the normal target outline for an actively-cloaking ALPHA
     * sublevel.
     *
     * Sable calls NeoForge's highlight hook with a transformed PoseStack and
     * sublevel camera. We use those exact transforms to build the vanilla-style
     * line mesh now, but deliberately do not draw it yet.
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
        if (!CloakingClient.getRenderMode(subLevel).isAlpha()) {
            return;
        }

        /*
         * Use the core/base cloak strength rather than viewer strength.
         *
         * Proximity reveal can make viewer strength zero while the core is
         * still actively cloaking. In that case the sublevel still uses our
         * delayed ALPHA render path, so the outline must also use the delayed
         * path.
         */
        if (CloakingClient.getCloakStrength(
                subLevel.getUniqueId()
        ) <= 0.0001F) {
            return;
        }

        // Suppress the normal early outline.
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

        OUTLINE_BUFFER.clear();

        BufferBuilder builder =
                new BufferBuilder(
                        OUTLINE_BUFFER,
                        VertexFormat.Mode.LINES,
                        DefaultVertexFormat.POSITION_COLOR_NORMAL
                );

        /*
         * Mirror vanilla LevelRenderer.renderShape() using the complete
         * VoxelShape. This avoids drawing internal component-box edges on
         * stairs and other complex shapes.
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
     * Called from LevelRendererAlphaSubLevelMixin after the delayed ALPHA
     * sublevel geometry has finished rendering.
     *
     * This is intentionally NOT a RenderLevelStageEvent.AFTER_PARTICLES hook:
     * that stage occurs too early for this mod's custom late ALPHA replay and
     * caused the sublevel geometry to paint over the selection outline.
     */
    public static void renderPendingAlphaOutline() {
        MeshData outline = pendingAlphaOutline;
        pendingAlphaOutline = null;

        if (outline == null) {
            return;
        }

        Minecraft.getInstance()
                .getMainRenderTarget()
                .bindWrite(false);

        RenderType lines = RenderType.lines();
        lines.setupRenderState();

        try {
            /*
             * Keep normal line depth testing so hidden edges remain hidden.
             * Disable only depth WRITES so the outline cannot affect later
             * debug/world rendering.
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

    private static void clearPendingOutline() {
        if (pendingAlphaOutline != null) {
            pendingAlphaOutline.close();
            pendingAlphaOutline = null;
        }
    }
}
