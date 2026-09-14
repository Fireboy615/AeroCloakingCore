package net.fireboy.aerocloakingcore.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.fireboy.aerocloakingcore.client.screen.CloakingCoreScreen;
import net.fireboy.aerocloakingcore.menu.ModMenus;
import net.fireboy.aerocloakingcore.network.CloakingClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
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
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.world.level.material.MapColor;

/** Client-only registration and render hooks. */
@Mod(value = AeroCloakingCore.MOD_ID, dist = Dist.CLIENT)
public final class AeroCloakingCoreClient {

    public AeroCloakingCoreClient(IEventBus modEventBus) {
        modEventBus.addListener(this::registerScreens);
        NeoForge.EVENT_BUS.addListener(this::onBlockHighlight);
    }

    private void registerScreens(RegisterMenuScreensEvent event) {
        event.register(
                ModMenus.CLOAKING_CORE.get(),
                CloakingCoreScreen::new
        );
    }

    /**
     * Alpha-cloaked sublevels use a custom solid-black target outline.
     *
     * Sable has already transformed both the PoseStack and Camera into
     * sublevel space before NeoForge fires RenderHighlightEvent.Block,
     * so drawing here follows the moving/rotating sublevel correctly.
     */
    private void onBlockHighlight(RenderHighlightEvent.Block event) {
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

        // Dither keeps Minecraft/Sable's normal target outline.
        if (CloakingClient.getRenderMode(subLevel)
                != CloakRenderMode.ALPHA) {
            return;
        }

        // If this core is not actually trying to cloak, use vanilla outline.
        // We intentionally use base/core strength here rather than viewer
        // strength, because proximity reveal can make viewer strength zero.
        if (CloakingClient.getCloakStrength(
                subLevel.getUniqueId()
        ) <= 0.0001F) {
            return;
        }

        /*
         * Cancel the normal selection highlight. Its translucent line pass
         * creates the see-through seams seen between neighbouring blocks in
         * ALPHA mode.
         */
        event.setCanceled(true);

        BlockState state =
                subLevel.getLevel().getBlockState(blockPos);

        CollisionContext collisionContext =
                CollisionContext.of(minecraft.player);

        VoxelShape shape =
                state.getShape(
                        subLevel.getLevel(),
                        blockPos,
                        collisionContext
                );

        if (shape.isEmpty()) {
            return;
        }

        Vec3 cameraPosition =
                event.getCamera().getPosition();

        double x = blockPos.getX() - cameraPosition.x;
        double y = blockPos.getY() - cameraPosition.y;
        double z = blockPos.getZ() - cameraPosition.z;

        VertexConsumer lines =
                event.getMultiBufferSource()
                        .getBuffer(RenderType.lines());

        /*
         * Draw a vanilla-style soft black outline.
         *
         * We cancelled the original Sable/Minecraft outline above,
         * so this is the only selection outline that gets queued.
         */
        MapColor mapColor =
                state.getMapColor(
                        subLevel.getLevel(),
                        blockPos
                );

        int rgb =
                mapColor.calculateRGBColor(
                        MapColor.Brightness.NORMAL
                );

        float red =
                ((rgb >> 16) & 0xFF) / 255.0F;

        float green =
                ((rgb >> 8) & 0xFF) / 255.0F;

        float blue =
                (rgb & 0xFF) / 255.0F;

        /*
         * Darken the block's own colour.
         *
         * This visually approximates vanilla's translucent black outline
         * without actually using transparency.
         */
        final float darken = 0.32F;

        red *= darken;
        green *= darken;
        blue *= darken;

        LevelRenderer.renderVoxelShape(
                event.getPoseStack(),
                lines,
                shape,
                x,
                y,
                z,
                red,
                green,
                blue,
                1.0F,
                false
        );
    }
}
