package net.fireboy.aerocloakingcore.client.render;

import java.util.function.Consumer;

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
 * Flywheel-rendered mirror assembly for the Cloaking Core.
 *
 * Rendering this through Flywheel instead of a vanilla BlockEntityRenderer is
 * important for AeroCloakingCore: Sable gives each sublevel a Flywheel
 * embedding, and the existing cloaking shader/embedding hooks operate on those
 * Flywheel visuals. This means the mirrors follow the same DITHER/ALPHA cloak
 * path as Create's own moving components.
 */
public final class CloakingCoreVisual
        extends AbstractBlockEntityVisual<CloakingCoreBlockEntity>
        implements SimpleDynamicVisual {

    private final boolean driveHalf;
    private final Direction facing;
    private final Quaternionf facingRotation;

    private final @Nullable TransformedInstance mirrorInstance;
    private final @Nullable TransformedInstance inputShaftInstance;

    /** Mirror speed relative to the connected Create input shaft. */
    private static final double MIRROR_SPEED_RATIO = 0.5;

    /** The short input shaft stub rotates at the actual network shaft speed. */
    private static final double INPUT_SHAFT_SPEED_RATIO = 1.0;

    /**
     * Offset the mirror assembly one full block forward so it sits out in the
     * chamber instead of inside the drive block.
     */
    private static final float MIRROR_FORWARD_OFFSET_PIXELS = 9.0F;

    /** Continuous client-side mirror phase, in radians. */
    private double mirrorAngleRadians = 0.0;
    private double lastRenderTick = Double.NaN;

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
            mirrorInstance = null;
            inputShaftInstance = null;
            return;
        }

        Model mirrorModel = Models.partial(CloakingCoreModels.MIRRORS);
        Model inputShaftModel = Models.partial(CloakingCoreModels.INPUT_SHAFT);

        mirrorInstance = instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, mirrorModel)
                .createInstance();

        inputShaftInstance = instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, inputShaftModel)
                .createInstance();

        mirrorInstance.overlay(OverlayTexture.NO_OVERLAY);
        inputShaftInstance.overlay(OverlayTexture.NO_OVERLAY);

        updateLight(partialTick);
        updateMirrorTransform(partialTick);
    }

    @Override
    public void beginFrame(DynamicVisual.Context context) {
        if (!driveHalf) {
            return;
        }

        updateMirrorTransform(context.partialTick());
    }

    @Override
    public void update(float partialTick) {
        if (!driveHalf) {
            return;
        }

        updateMirrorTransform(partialTick);
    }

    private void updateMirrorTransform(float partialTick) {
        if (mirrorInstance == null || inputShaftInstance == null) {
            return;
        }

        double renderTick = blockEntity.getLevel() != null
                ? blockEntity.getLevel().getGameTime() + partialTick
                : partialTick;

        if (Double.isNaN(lastRenderTick)) {
            lastRenderTick = renderTick;
        }

        double deltaTicks = renderTick - lastRenderTick;
        lastRenderTick = renderTick;

        // Ignore large discontinuities caused by world/chunk reloads.
        if (deltaTicks < 0.0 || deltaTicks > 5.0) {
            deltaTicks = 0.0;
        }

        // 1 RPM = 2*pi radians / 1200 game ticks.
        double baseRadiansPerTick = blockEntity.getSpeed()
                * (Math.PI * 2.0 / 1200.0);

        double mirrorRadiansPerTick = baseRadiansPerTick * MIRROR_SPEED_RATIO;
        mirrorAngleRadians += mirrorRadiansPerTick * deltaTicks;

        // Keep the accumulators numerically tidy while preserving continuity.
        if (Math.abs(mirrorAngleRadians) > Math.PI * 4096.0) {
            mirrorAngleRadians %= Math.PI * 2.0;
        }

        float mirrorAngle = (float) mirrorAngleRadians;
        float inputShaftAngle = (float) ((mirrorAngleRadians / MIRROR_SPEED_RATIO) * INPUT_SHAFT_SPEED_RATIO);

        /*
         * Create's kinetic sign is axis-relative. Our Blockbench model's local
         * +Z axis is rotated onto FACING, so invert negative axis directions to
         * keep the visible rotation consistent with the connected input shaft.
         */
        if (facing.getAxisDirection() == Direction.AxisDirection.NEGATIVE) {
            mirrorAngle = -mirrorAngle;
            inputShaftAngle = -inputShaftAngle;
        }

        /*
         * The Blockbench model is authored with its long axis toward SOUTH
         * (+Z). Shift the mirror pack forward so it sits out in the chamber,
         * while a separate 2 px shaft stub remains at the rear input face.
         */
        Matrix4f mirrorTransform = new Matrix4f()
                .translation(
                        visualPos.getX() + 0.5F,
                        visualPos.getY() + 0.5F,
                        visualPos.getZ() + 0.5F
                )
                .rotate(facingRotation)
                .translate(0.0F, 0.0F, MIRROR_FORWARD_OFFSET_PIXELS / 16.0F)
                .rotateZ(mirrorAngle)
                .translate(-0.5F, -0.5F, -0.5F);

        Matrix4f inputShaftTransform = new Matrix4f()
                .translation(
                        visualPos.getX() + 0.5F,
                        visualPos.getY() + 0.5F,
                        visualPos.getZ() + 0.5F
                )
                .rotate(facingRotation)
                .rotateZ(inputShaftAngle)
                .translate(-0.5F, -0.5F, -0.5F);

        mirrorInstance.setTransform(mirrorTransform);
        mirrorInstance.setChanged();
        inputShaftInstance.setTransform(inputShaftTransform);
        inputShaftInstance.setChanged();
    }

    @Override
    public void updateLight(float partialTick) {
        if (mirrorInstance == null || inputShaftInstance == null) {
            return;
        }

        // The mirror model sits out in the open chamber and looked too dark
        // when lit from the chamber block position itself, so sample from the
        // open air block beyond the chamber.
        relight(pos.relative(facing, 2), mirrorInstance);

        // The short input shaft lives right at the rear opening. Light it from
        // the adjacent input-side air block instead of from inside the casing.
        relight(pos.relative(facing.getOpposite()), inputShaftInstance);
    }

    @Override
    public void collectCrumblingInstances(
            Consumer<@Nullable Instance> consumer
    ) {
        consumer.accept(mirrorInstance);
        consumer.accept(inputShaftInstance);
    }

    @Override
    protected void _delete() {
        if (mirrorInstance != null) {
            mirrorInstance.delete();
        }

        if (inputShaftInstance != null) {
            inputShaftInstance.delete();
        }
    }

    /**
     * The shifted mirror model occupies the chamber block and protrudes a few
     * pixels beyond it, so use a three-block frustum box instead of Flywheel's
     * default one-block sphere.
     */
    @Override
    public boolean isVisible(FrustumIntersection frustum) {
        if (!driveHalf) {
            return false;
        }

        BlockPos chamberPos = pos.relative(facing);
        BlockPos farPos = pos.relative(facing, 2);
        BlockPos chamberVisualPos = chamberPos.subtract(renderOrigin());
        BlockPos farVisualPos = farPos.subtract(renderOrigin());

        float minX = Math.min(
                visualPos.getX(),
                Math.min(chamberVisualPos.getX(), farVisualPos.getX())
        );
        float minY = Math.min(
                visualPos.getY(),
                Math.min(chamberVisualPos.getY(), farVisualPos.getY())
        );
        float minZ = Math.min(
                visualPos.getZ(),
                Math.min(chamberVisualPos.getZ(), farVisualPos.getZ())
        );

        float maxX = Math.max(
                visualPos.getX(),
                Math.max(chamberVisualPos.getX(), farVisualPos.getX())
        ) + 1.0F;
        float maxY = Math.max(
                visualPos.getY(),
                Math.max(chamberVisualPos.getY(), farVisualPos.getY())
        ) + 1.0F;
        float maxZ = Math.max(
                visualPos.getZ(),
                Math.max(chamberVisualPos.getZ(), farVisualPos.getZ())
        ) + 1.0F;

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
