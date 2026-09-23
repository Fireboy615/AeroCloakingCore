package net.fireboy.aerocloakingcore.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.fireboy.aerocloakingcore.block.CloakingCoreBlock;
import net.fireboy.aerocloakingcore.block.entity.CloakingCoreBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.model.data.ModelData;

public final class CloakingCoreBlockEntityRenderer
        implements BlockEntityRenderer<CloakingCoreBlockEntity> {

    public static final ModelResourceLocation ROTOR_MODEL =
            ModelResourceLocation.standalone(
                    AeroCloakingCore.path("block/cloaking_core_rotor")
            );

    /**
     * Temporary test speed while we prove the whole renderer pipeline.
     * 18 degrees/tick at 20 TPS = one full revolution per second = 60 RPM.
     */
    private static final float TEST_DEGREES_PER_TICK = 18.0F;

    private final BlockRenderDispatcher blockRenderer;

    public CloakingCoreBlockEntityRenderer(
            BlockEntityRendererProvider.Context context
    ) {
        this.blockRenderer = context.getBlockRenderDispatcher();
    }

    @Override
    public void render(
            CloakingCoreBlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            int packedOverlay
    ) {
        BlockState state = blockEntity.getBlockState();

        // The chamber half uses the same block entity type, but the rotor is
        // owned and rendered only by the drive half.
        if (!state.hasProperty(CloakingCoreBlock.CHAMBER)
                || state.getValue(CloakingCoreBlock.CHAMBER)) {
            return;
        }

        if (!state.hasProperty(CloakingCoreBlock.FACING)) {
            return;
        }

        if (blockEntity.getLevel() == null) {
            return;
        }

        BakedModel model = Minecraft.getInstance()
                .getModelManager()
                .getModel(ROTOR_MODEL);

        if (model == Minecraft.getInstance().getModelManager().getMissingModel()) {
            return;
        }

        Direction facing = state.getValue(CloakingCoreBlock.FACING);

        float angle = (float) (
                (blockEntity.getLevel().getGameTime() + partialTick)
                        * TEST_DEGREES_PER_TICK
                        % 360.0D
        );

        poseStack.pushPose();

        /*
         * The Blockbench rotor model is authored with its long axis pointing
         * SOUTH (+Z). Rotate that local +Z axis onto the block's FACING.
         *
         * We rotate around the centre of the drive block. The rotor itself is
         * allowed to extend into the neighbouring chamber block.
         */
        poseStack.translate(0.5D, 0.5D, 0.5D);
        applyFacingRotation(poseStack, facing);

        // After the facing transform, local +Z is the machine shaft axis.
        poseStack.mulPose(Axis.ZP.rotationDegrees(angle));
        poseStack.translate(-0.5D, -0.5D, -0.5D);

        RandomSource random = RandomSource.create(42L);
        ModelData modelData = ModelData.EMPTY;

        for (RenderType renderType : model.getRenderTypes(
                state,
                random,
                modelData
        )) {
            blockRenderer.getModelRenderer().renderModel(
                    poseStack.last(),
                    bufferSource.getBuffer(
                            RenderTypeHelper.getEntityRenderType(
                                    renderType,
                                    false
                            )
                    ),
                    state,
                    model,
                    1.0F,
                    1.0F,
                    1.0F,
                    packedLight,
                    packedOverlay,
                    modelData,
                    renderType
            );
        }

        poseStack.popPose();
    }

    private static void applyFacingRotation(
            PoseStack poseStack,
            Direction facing
    ) {
        switch (facing) {
            case SOUTH -> {
                // Blockbench default: +Z.
            }
            case NORTH -> poseStack.mulPose(
                    Axis.YP.rotationDegrees(180.0F)
            );
            case EAST -> poseStack.mulPose(
                    Axis.YP.rotationDegrees(90.0F)
            );
            case WEST -> poseStack.mulPose(
                    Axis.YP.rotationDegrees(-90.0F)
            );
            case UP -> poseStack.mulPose(
                    Axis.XP.rotationDegrees(-90.0F)
            );
            case DOWN -> poseStack.mulPose(
                    Axis.XP.rotationDegrees(90.0F)
            );
        }
    }

    @Override
    public AABB getRenderBoundingBox(
            CloakingCoreBlockEntity blockEntity
    ) {
        BlockState state = blockEntity.getBlockState();
        BlockPos drivePos = blockEntity.getBlockPos();

        if (!state.hasProperty(CloakingCoreBlock.FACING)) {
            return new AABB(drivePos);
        }

        Direction facing = state.getValue(CloakingCoreBlock.FACING);
        BlockPos chamberPos = drivePos.relative(facing);
        BlockPos farPos = drivePos.relative(facing, 2);

        return new AABB(drivePos)
                .minmax(new AABB(chamberPos))
                .minmax(new AABB(farPos));
    }
}
