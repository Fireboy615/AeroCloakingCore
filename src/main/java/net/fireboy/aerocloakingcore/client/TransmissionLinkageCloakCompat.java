package net.fireboy.aerocloakingcore.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;

import net.fireboy.aerocloakingcore.network.CloakingClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import net.neoforged.fml.ModList;

import org.joml.Vector3d;

import java.lang.reflect.Method;

/**
 * Optional alpha-gradient pass for Create Aeronautics: Transmission & Linkage.
 *
 * Its mechanical rods are generated in BERs, not Simulated's rope renderer.
 * A single BE-level cloak factor hides an entire rod at once. The adapted
 * vertex stream instead interpolates opacity over its actual world-space
 * geometry between the two linked block entity endpoints.
 *
 * We only intercept solid/cutout rod geometry and leave all other VertexFormats
 * alone. Mixed alpha/dither connections intentionally use an alpha gradient
 * on this first pass. The draw is deferred until the late world pass.
 */
public final class TransmissionLinkageCloakCompat {
    private static final String PREFIX = "com.enxv.aerouniversaljoint.content.";
    private static final String UNIVERSAL = PREFIX + "UniversalJointBlockEntity";
    private static final String HYDRAULIC = PREFIX + "HydraulicConnectionHeadBlockEntity";

    private TransmissionLinkageCloakCompat() {
    }

    public static <T extends BlockEntity> boolean enqueueIfNeeded(
            BlockEntityRenderer<T> renderer,
            T blockEntity,
            float partialTick,
            com.mojang.blaze3d.vertex.PoseStack poseStack,
            int packedLight,
            int packedOverlay
    ) {
        if (!ModList.get().isLoaded("aeronautics_utility_objects")
                || !CloakingClient.isModEnabled()) {
            return false;
        }

        String className = blockEntity.getClass().getName();
        if (!UNIVERSAL.equals(className) && !HYDRAULIC.equals(className)) {
            return false;
        }

        try {
            Method getter = blockEntity.getClass().getMethod("getLoadedLinkedEndpoint");
            Object remote = getter.invoke(blockEntity);
            if (!(remote instanceof BlockEntity partner) || partner.isRemoved()) {
                return false;
            }

            float firstCloak = strengthFor(blockEntity);
            float secondCloak = strengthFor(partner);

            if (Math.max(firstCloak, secondCloak) < 0.001F) {
                return false;
            }

            Gradient gradient = new Gradient(
                    worldCenter(blockEntity),
                    worldCenter(partner),
                    firstCloak,
                    secondCloak
            );

            BlockEntityCloakRenderQueue.enqueueLinkage(
                    renderer, blockEntity, partialTick, poseStack,
                    packedLight, packedOverlay, gradient
            );
            return true;
        } catch (ReflectiveOperationException | LinkageError ex) {
            // Never break normal linkage rendering when the optional API moves.
            return false;
        }
    }

    private static float strengthFor(BlockEntity blockEntity) {
        SubLevel subLevel = Sable.HELPER.getContaining(blockEntity);
        if (subLevel instanceof ClientSubLevel client && !client.isRemoved()) {
            return CloakingClient.getViewerCloakStrength(client);
        }
        return 0.0F;
    }

    private static Vec3 worldCenter(BlockEntity blockEntity) {
        var pos = blockEntity.getBlockPos();
        Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5,
                pos.getZ() + 0.5);

        SubLevel subLevel = Sable.HELPER.getContaining(blockEntity);
        if (subLevel instanceof ClientSubLevel client && !client.isRemoved()) {
            return client.renderPose().transformPosition(center);
        }
        return center;
    }

    public record Gradient(Vec3 start, Vec3 end, float startCloak, float endCloak) {
        public float visibleAlphaAt(double x, double y, double z) {
            double dx = end.x - start.x;
            double dy = end.y - start.y;
            double dz = end.z - start.z;
            double denom = dx * dx + dy * dy + dz * dz;
            double t = denom <= 1.0E-8 ? 0.5 :
                    ((x - start.x) * dx + (y - start.y) * dy
                            + (z - start.z) * dz) / denom;
            t = Math.max(0.0, Math.min(1.0, t));
            return (float) Math.max(0.0, Math.min(1.0,
                    1.0 - (startCloak + (endCloak - startCloak) * t)));
        }
    }

    public static MultiBufferSource fadedSource(
            MultiBufferSource delegate,
            Gradient gradient
    ) {
        Vec3 camera = Minecraft.getInstance().gameRenderer
                .getMainCamera().getPosition();

        return type -> {
            // Texture atlas rod models use the solid/cutout block vertex format.
            // Send those into a blending-enabled equivalent with identical format.
            boolean atlas = type == RenderType.solid()
                    || type == RenderType.cutout()
                    || type == RenderType.cutoutMipped();
            if (!atlas) {
                return delegate.getBuffer(type);
            }
            return new FadingVertexConsumer(
                    delegate.getBuffer(RenderType.translucent()),
                    gradient, camera
            );
        };
    }

    private static final class FadingVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final Gradient gradient;
        private final Vec3 camera;
        private float alphaFactor = 1.0F;

        private FadingVertexConsumer(
                VertexConsumer delegate, Gradient gradient, Vec3 camera
        ) {
            this.delegate = delegate;
            this.gradient = gradient;
            this.camera = camera;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            alphaFactor = gradient.visibleAlphaAt(
                    x + camera.x, y + camera.y, z + camera.z
            );
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue,
                    Math.round(alpha * alphaFactor));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }
}
