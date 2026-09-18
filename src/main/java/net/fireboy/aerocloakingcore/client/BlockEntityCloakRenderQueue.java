package net.fireboy.aerocloakingcore.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;

import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Isolated render path for partially cloaked block entities.
 *
 * DITHER block entities are normally rendered immediately into a private
 * MultiBufferSource so their flush cannot disturb Minecraft/Sable's shared
 * world buffers. Aeronautics' burner is the exception because its flame draws
 * directly and needs the terrain depth buffer to be complete first, so that
 * one BER is replayed in the late queue. ALPHA block entities are also queued
 * and replayed at the end of the world pass.
 */
public final class BlockEntityCloakRenderQueue {

    private static final int BUFFER_SIZE = 786_432;

    private static final List<DeferredRender> QUEUE = new ArrayList<>();

    /**
     * Rendering is single-threaded, but a pool also makes recursive BER calls
     * safe without allocating/freeing a native buffer for every chest/frame.
     */
    private static final ThreadLocal<ArrayDeque<BufferSlot>> BUFFER_POOL =
            ThreadLocal.withInitial(ArrayDeque::new);

    private BlockEntityCloakRenderQueue() {
    }

    public static void beginFrame() {
        QUEUE.clear();
    }

    public static <T extends BlockEntity> void renderDitherNow(
            BlockEntityRenderer<T> renderer,
            T blockEntity,
            float partialTick,
            PoseStack poseStack,
            int packedLight,
            int packedOverlay,
            float cloakStrength
    ) {
        /*
         * Aeronautics' burner flame bypasses MultiBufferSource and calls
         * BufferUploader.drawWithShader() directly from inside its BER.  At
         * the normal block-entity point, nearby/sublevel terrain can still be
         * sitting in Minecraft's shared buffers and therefore has not written
         * its depth yet.  The direct flame then appears through the dithered
         * hull even though its own dither mask is correct.
         *
         * ALPHA already avoids this because alpha BERs are replayed late. Do
         * the same only for the burner in DITHER mode. By the late pass the
         * world/sublevel geometry has populated the main depth buffer, so the
         * flame is depth-culled correctly without force-flushing shared world
         * buffers or disturbing every other dithered block entity.
         */
        if (blockEntity instanceof HotAirBurnerBlockEntity) {
            QUEUE.add(
                    new DeferredRender(
                            castRenderer(renderer),
                            blockEntity,
                            partialTick,
                            copyPoseStack(poseStack),
                            packedLight,
                            packedOverlay,
                            cloakStrength,
                            CloakRenderMode.DITHER
                    )
            );
            return;
        }

        renderIsolated(
                renderer,
                blockEntity,
                partialTick,
                poseStack,
                packedLight,
                packedOverlay,
                cloakStrength,
                CloakRenderMode.DITHER
        );
    }

    public static <T extends BlockEntity> void enqueueAlpha(
            BlockEntityRenderer<T> renderer,
            T blockEntity,
            float partialTick,
            PoseStack poseStack,
            int packedLight,
            int packedOverlay,
            float cloakStrength,
            CloakRenderMode renderMode
    ) {
        QUEUE.add(
                new DeferredRender(
                        castRenderer(renderer),
                        blockEntity,
                        partialTick,
                        copyPoseStack(poseStack),
                        packedLight,
                        packedOverlay,
                        cloakStrength,
                        renderMode
                )
        );
    }

    public static boolean isEmpty() {
        return QUEUE.isEmpty();
    }

    public static void renderQueued() {
        if (QUEUE.isEmpty()) {
            return;
        }

        try {
            /*
             * The existing late alpha terrain pass restores the main target,
             * but bind it here as well so this queue is independently safe if
             * mixin ordering changes or there was no alpha terrain this frame.
             */
            Minecraft.getInstance()
                    .getMainRenderTarget()
                    .bindWrite(false);

            RenderSystem.depthMask(true);

            for (DeferredRender deferred : QUEUE) {
                renderIsolated(
                        deferred.renderer(),
                        deferred.blockEntity(),
                        deferred.partialTick(),
                        deferred.poseStack(),
                        deferred.packedLight(),
                        deferred.packedOverlay(),
                        deferred.cloakStrength(),
                        deferred.renderMode()
                );
            }
        } finally {
            QUEUE.clear();

            Minecraft.getInstance()
                    .getMainRenderTarget()
                    .bindWrite(false);

            RenderSystem.depthMask(true);
        }
    }

    private static <T extends BlockEntity> void renderIsolated(
            BlockEntityRenderer<T> renderer,
            T blockEntity,
            float partialTick,
            PoseStack poseStack,
            int packedLight,
            int packedOverlay,
            float cloakStrength,
            CloakRenderMode renderMode
    ) {
        BufferSlot slot = acquireBuffer();

        EntityCloakRenderState.begin(
                cloakStrength,
                renderMode
        );

        try {
            renderer.render(
                    blockEntity,
                    partialTick,
                    poseStack,
                    slot.source,
                    packedLight,
                    packedOverlay
            );

            /*
             * Only this BER's private geometry is flushed. Nothing queued in
             * Minecraft.renderBuffers().bufferSource() is touched here.
             */
            slot.source.endBatch();
        } finally {
            try {
                /* Safe when the successful path already emptied the source. */
                slot.source.endBatch();
            } finally {
                EntityCloakRenderState.end();
                releaseBuffer(slot);
            }
        }
    }

    private static BufferSlot acquireBuffer() {
        ArrayDeque<BufferSlot> pool = BUFFER_POOL.get();
        BufferSlot slot = pool.pollFirst();
        return slot != null ? slot : new BufferSlot();
    }

    private static void releaseBuffer(BufferSlot slot) {
        BUFFER_POOL.get().addFirst(slot);
    }

    private static PoseStack copyPoseStack(PoseStack source) {
        PoseStack copy = new PoseStack();
        copy.setIdentity();
        copy.mulPose(new Matrix4f(source.last().pose()));
        return copy;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockEntityRenderer<BlockEntity> castRenderer(
            BlockEntityRenderer<?> renderer
    ) {
        return (BlockEntityRenderer) renderer;
    }

    private static final class BufferSlot {
        private final ByteBufferBuilder backing =
                new ByteBufferBuilder(BUFFER_SIZE);

        private final MultiBufferSource.BufferSource source =
                MultiBufferSource.immediate(backing);
    }

    private record DeferredRender(
            BlockEntityRenderer<BlockEntity> renderer,
            BlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            int packedLight,
            int packedOverlay,
            float cloakStrength,
            CloakRenderMode renderMode
    ) {
    }
}
