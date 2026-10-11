package net.fireboy.aerocloakingcore.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerBlockEntity;
import dev.simulated_team.simulated.content.blocks.rope.rope_connector.RopeConnectorBlockEntity;
import dev.simulated_team.simulated.content.blocks.rope.rope_winch.RopeWinchBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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
    private static final List<DeferredLinkage> LINKAGE_QUEUE = new ArrayList<>();

    /**
     * Rendering is single-threaded, but a pool also makes recursive BER calls
     * safe without allocating/freeing a native buffer for every chest/frame.
     */
    private static final ThreadLocal<ArrayDeque<BufferSlot>> BUFFER_POOL =
            ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * Reuse profiler section names for each BE type. Only the actual
     * profiler push/pop is performed for individual renders, avoiding
     * registry lookups and string building every frame.
     */
    private static final Map<BlockEntityType<?>, String> PROFILE_NAMES =
            new IdentityHashMap<>();

    private static String profileName(BlockEntity entity) {
        return PROFILE_NAMES.computeIfAbsent(entity.getType(), type -> {
            var key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
            return key != null
                    ? "aero_be_" + key.getNamespace() + "_" + key.getPath()
                    : "aero_be_unknown";
        });
    }

    private BlockEntityCloakRenderQueue() {
    }

    public static void beginFrame() {
        QUEUE.clear();
        LINKAGE_QUEUE.clear();
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

    public static <T extends BlockEntity> void enqueueLinkage(
            BlockEntityRenderer<T> renderer,
            T blockEntity,
            float partialTick,
            PoseStack poseStack,
            int packedLight,
            int packedOverlay,
            TransmissionLinkageCloakCompat.Gradient gradient
    ) {
        LINKAGE_QUEUE.add(new DeferredLinkage(
                castRenderer(renderer), blockEntity, partialTick,
                copyPoseStack(poseStack), packedLight, packedOverlay, gradient
        ));
    }

    public static boolean isEmpty() {
        return QUEUE.isEmpty() && LINKAGE_QUEUE.isEmpty();
    }

    public static void renderQueued() {
        if (QUEUE.isEmpty() && LINKAGE_QUEUE.isEmpty()) {
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

            for (int i = 0; i < QUEUE.size();) {
                DeferredRender deferred = QUEUE.get(i);

                // Preserve queue order and shader state. Only neighbouring
                // Simulated rope BERs with identical ALPHA cloak parameters
                // can share a batch; non-rope/Burner draws remain isolated.
                if (isRopeBatchCandidate(deferred)) {
                    int end = i + 1;
                    while (end < QUEUE.size()
                            && sameRopeBatch(deferred, QUEUE.get(end))) {
                        end++;
                    }

                    if (end - i > 1) {
                        renderRopesBatched(i, end, deferred);
                        i = end;
                        continue;
                    }
                }

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
                i++;
            }

            // All optional Transmission & Linkage rods submit atlas vertices
            // through the per-endpoint fading adapter. Their gradients are
            // already baked into the vertex alpha, so a single neutral cloak
            // shader state and a single late buffer flush can serve the batch.
            if (!LINKAGE_QUEUE.isEmpty()) {
                renderLinkagesBatched();
            }
        } finally {
            QUEUE.clear();
            LINKAGE_QUEUE.clear();

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
        var profiler = Minecraft.getInstance().getProfiler();
        profiler.push(profileName(blockEntity));
        try {
            BufferSlot slot = acquireBuffer();
            EntityCloakRenderState.begin(cloakStrength, renderMode);

            try {
                profiler.push("aero_ber_geometry");
                try {
                    renderer.render(
                            blockEntity,
                            partialTick,
                            poseStack,
                            slot.source,
                            packedLight,
                            packedOverlay
                    );
                } finally {
                    profiler.pop();
                }
            } finally {
                try {
                    // One flush per isolated BE (including exceptional exits).
                    // The old path always called endBatch() twice on success.
                    // Only this private source is submitted: shared world
                    // buffers remain untouched, preserving water/rope order.
                    profiler.push("aero_ber_flush");
                    try {
                        slot.source.endBatch();
                    } finally {
                        profiler.pop();
                    }
                } finally {
                    EntityCloakRenderState.end();
                    releaseBuffer(slot);
                }
            }
        } finally {
            profiler.pop();
        }
    }

    /**
     * Simulated's connector renders a rope knot and its separate rope path;
     * the winch renders its coil/shaft and separate rope path. Both their BERs
     * use MultiBufferSource rather than direct BufferUploader calls.
     *
     * Their alpha/dither shader uniforms are sampled at buffer FLUSH time,
     * not when vertices are emitted. Never mix strengths or render modes, and
     * never move a rope BE across a non-rope BE (ordering/water/occlusion).
     * DITHER remains at its original immediate render point.
     */
    private static boolean isRopeBatchCandidate(DeferredRender deferred) {
        return deferred.renderMode().isAlpha()
                && (deferred.blockEntity() instanceof RopeConnectorBlockEntity
                        || deferred.blockEntity() instanceof RopeWinchBlockEntity);
    }

    private static boolean sameRopeBatch(
            DeferredRender first, DeferredRender next
    ) {
        return isRopeBatchCandidate(next)
                && next.renderMode() == first.renderMode()
                && Float.floatToIntBits(next.cloakStrength())
                    == Float.floatToIntBits(first.cloakStrength());
    }

    /**
     * Render consecutive, compatible Simulated rope block entities through a
     * single isolated source. Their individual rope STRANDS keep using the
     * existing endpoint-specific RopeCloakRenderQueue; only the stationary
     * connector knot and rotating winch components are combined here.
     */
    private static void renderRopesBatched(
            int first, int end, DeferredRender settings
    ) {
        var profiler = Minecraft.getInstance().getProfiler();
        profiler.push("aero_rope_ber_batch");
        try {
            BufferSlot slot = acquireBuffer();
            EntityCloakRenderState.begin(
                    settings.cloakStrength(), settings.renderMode()
            );

            try {
                for (int i = first; i < end; i++) {
                    DeferredRender deferred = QUEUE.get(i);
                    profiler.push(profileName(deferred.blockEntity()));
                    try {
                        profiler.push("aero_ber_geometry");
                        try {
                            deferred.renderer().render(
                                    deferred.blockEntity(),
                                    deferred.partialTick(),
                                    deferred.poseStack(),
                                    slot.source,
                                    deferred.packedLight(),
                                    deferred.packedOverlay()
                            );
                        } finally {
                            profiler.pop();
                        }
                    } finally {
                        profiler.pop();
                    }
                }
            } finally {
                try {
                    profiler.push("aero_rope_ber_batch_flush");
                    try {
                        slot.source.endBatch();
                    } finally {
                        profiler.pop();
                    }
                } finally {
                    EntityCloakRenderState.end();
                    releaseBuffer(slot);
                }
            }
        } finally {
            profiler.pop();
        }
    }

    /**
     * Transmission & Linkage's universal joints and hydraulic rods write
     * standard cutout-model vertices. fadedSource() reroutes those vertices to
     * the translucent atlas and multiplies their alpha independently for each
     * endpoint. A different gradient therefore does not require a different
     * draw call or a different shader uniform.
     *
     * Unlike arbitrary BERs, these two optional renderers do not perform any
     * direct BufferUploader draws. Keep them in one private buffer until all
     * connections have emitted geometry, then submit the batch once. This
     * saves a driver flush per linkage while leaving Create, ropes, burners
     * and all other isolated BERs on their existing render paths.
     */
    private static void renderLinkagesBatched() {
        var profiler = Minecraft.getInstance().getProfiler();
        profiler.push("aero_linkage_batch");
        try {
            BufferSlot slot = acquireBuffer();

            // The gradient adapter changes each vertex's alpha itself.
            // Keep the global cloak shader uniform neutral for every joint,
            // including the final flush of the shared translucent buffer.
            EntityCloakRenderState.beginComposite(0.0F, 0.0F, 1.0F);
            try {
                for (DeferredLinkage linkage : LINKAGE_QUEUE) {
                    profiler.push(profileName(linkage.blockEntity()));
                    try {
                        profiler.push("aero_ber_geometry");
                        try {
                            linkage.renderer().render(
                                    linkage.blockEntity(),
                                    linkage.partialTick(),
                                    linkage.poseStack(),
                                    TransmissionLinkageCloakCompat.fadedSource(
                                            slot.source, linkage.gradient()
                                    ),
                                    linkage.packedLight(),
                                    linkage.packedOverlay()
                            );
                        } finally {
                            profiler.pop();
                        }
                    } finally {
                        profiler.pop();
                    }
                }
            } finally {
                try {
                    // Flush even if a renderer throws, so pooled native
                    // buffers cannot retain unfinished vertices into a
                    // subsequent frame. This flush replaces the N individual
                    // per-joint flushes, not the world buffer flush.
                    profiler.push("aero_linkage_batch_flush");
                    try {
                        slot.source.endBatch();
                    } finally {
                        profiler.pop();
                    }
                } finally {
                    EntityCloakRenderState.end();
                    releaseBuffer(slot);
                }
            }
        } finally {
            profiler.pop();
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

    private record DeferredLinkage(
            BlockEntityRenderer<BlockEntity> renderer,
            BlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            int packedLight,
            int packedOverlay,
            TransmissionLinkageCloakCompat.Gradient gradient
    ) {
    }
}
