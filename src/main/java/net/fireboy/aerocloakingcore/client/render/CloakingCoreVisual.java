package net.fireboy.aerocloakingcore.client.render;

import java.util.function.Consumer;

import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;

import net.fireboy.aerocloakingcore.block.CloakingCoreBlock;
import net.fireboy.aerocloakingcore.block.entity.CloakingCoreBlockEntity;

import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.jetbrains.annotations.Nullable;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Flywheel-rendered rotor for the Cloaking Core.
 *
 * Rendering this through Flywheel instead of a vanilla BlockEntityRenderer is
 * important for AeroCloakingCore: Sable gives each sublevel a Flywheel
 * embedding, and the existing cloaking shader/embedding hooks operate on those
 * Flywheel visuals. This means the rotor follows the same DITHER/ALPHA cloak
 * path as Create's own moving components.
 */
public final class CloakingCoreVisual
        extends AbstractBlockEntityVisual<CloakingCoreBlockEntity>
        implements SimpleDynamicVisual {

    private final boolean driveHalf;
    private final Direction facing;
    private final Quaternionf facingRotation;

    private final @Nullable TransformedInstance shaftInstance;
    private final @Nullable TransformedInstance paneInstance;

    public CloakingCoreVisual(
            VisualizationContext context,
            CloakingCoreBlockEntity blockEntity,
            float partialTick
    ) {
        super(context, blockEntity, partialTick);

        this.driveHalf = blockState.hasProperty(CloakingCoreBlock.CHAMBER)
                && !blockState.getValue(CloakingCoreBlock.CHAMBER);

        this.facing = blockState.hasProperty(CloakingCoreBlock.FACING)
                ? blockState.getValue(CloakingCoreBlock.FACING)
                : Direction.SOUTH;

        this.facingRotation = rotationFromSouthTo(facing);

        if (!driveHalf) {
            shaftInstance = null;
            paneInstance = null;
            return;
        }

        Model shaftModel = Models.partial(CloakingCoreModels.ROTOR_SHAFT);
        Model paneModel = Models.partial(CloakingCoreModels.ROTOR_PANES);

        shaftInstance = instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, shaftModel)
                .createInstance();

        paneInstance = instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, paneModel)
                .createInstance();

        shaftInstance.overlay(OverlayTexture.NO_OVERLAY);
        paneInstance.overlay(OverlayTexture.NO_OVERLAY);

        updateLight(partialTick);
        updateRotorTransform(partialTick);
    }

    @Override
    public void beginFrame(DynamicVisual.Context context) {
        if (!driveHalf) {
            return;
        }

        updateRotorTransform(context.partialTick());
    }

    @Override
    public void update(float partialTick) {
        if (!driveHalf) {
            return;
        }

        updateRotorTransform(partialTick);
    }

    private void updateRotorTransform(float partialTick) {
        if (shaftInstance == null || paneInstance == null) {
            return;
        }

        float angleRadians = KineticBlockEntityRenderer.getAngleForBe(
                blockEntity,
                pos,
                facing.getAxis()
        );

        /*
         * Create calculates kinetic angles around the POSITIVE direction of an
         * axis. Our Blockbench model's local +Z axis is rotated onto FACING.
         * When FACING is negative (NORTH/WEST/DOWN), invert the local angle so
         * the rotor remains phase/direction-correct with the connected shaft.
         */
        if (facing.getAxisDirection() == Direction.AxisDirection.NEGATIVE) {
            angleRadians = -angleRadians;
        }

        /*
         * The two partial models are authored with their long axis toward
         * SOUTH (+Z). They are already positioned correctly relative to the
         * drive/chamber seam in model space.
         *
         * 1) Move the model to the block's Flywheel visual position.
         * 2) Rotate local SOUTH onto the block FACING direction.
         * 3) Rotate around the shaft axis (local Z) through the drive center.
         */
        Matrix4f transform = new Matrix4f()
                .translation(
                        visualPos.getX() + 0.5F,
                        visualPos.getY() + 0.5F,
                        visualPos.getZ() + 0.5F
                )
                .rotate(facingRotation)
                .rotateZ(angleRadians)
                .translate(-0.5F, -0.5F, -0.5F);

        shaftInstance.setTransform(transform);
        paneInstance.setTransform(transform);

        shaftInstance.setChanged();
        paneInstance.setChanged();
    }

    @Override
    public void updateLight(float partialTick) {
        if (shaftInstance == null || paneInstance == null) {
            return;
        }

        relight(shaftInstance, paneInstance);
    }

    @Override
    public void collectCrumblingInstances(
            Consumer<@Nullable Instance> consumer
    ) {
        consumer.accept(shaftInstance);
        consumer.accept(paneInstance);
    }

    @Override
    protected void _delete() {
        if (shaftInstance != null) {
            shaftInstance.delete();
        }

        if (paneInstance != null) {
            paneInstance.delete();
        }
    }

    /**
     * The rotor spans the drive block and the adjacent chamber block, so use a
     * two-block frustum box instead of Flywheel's default one-block sphere.
     */
    @Override
    public boolean isVisible(FrustumIntersection frustum) {
        if (!driveHalf) {
            return false;
        }

        BlockPos chamberPos = pos.relative(facing);
        BlockPos chamberVisualPos = chamberPos.subtract(renderOrigin());

        float minX = Math.min(visualPos.getX(), chamberVisualPos.getX());
        float minY = Math.min(visualPos.getY(), chamberVisualPos.getY());
        float minZ = Math.min(visualPos.getZ(), chamberVisualPos.getZ());

        float maxX = Math.max(visualPos.getX(), chamberVisualPos.getX()) + 1.0F;
        float maxY = Math.max(visualPos.getY(), chamberVisualPos.getY()) + 1.0F;
        float maxZ = Math.max(visualPos.getZ(), chamberVisualPos.getZ()) + 1.0F;

        return frustum.testAab(
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ
        );
    }

    /**
     * The Blockbench default points SOUTH (+Z). Return a rotation that maps
     * that local axis onto the six-direction block FACING value.
     */
    private static Quaternionf rotationFromSouthTo(Direction facing) {
        return switch (facing) {
            case SOUTH -> new Quaternionf();
            case NORTH -> new Quaternionf().rotationY((float) Math.PI);
            case EAST -> new Quaternionf().rotationY((float) Math.PI / 2.0F);
            case WEST -> new Quaternionf().rotationY(-(float) Math.PI / 2.0F);
            case UP -> new Quaternionf().rotationX(-(float) Math.PI / 2.0F);
            case DOWN -> new Quaternionf().rotationX((float) Math.PI / 2.0F);
        };
    }
}
