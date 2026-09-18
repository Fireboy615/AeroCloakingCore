package net.fireboy.aerocloakingcore.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.math.OrientedBoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import dev.simulated_team.simulated.content.blocks.rope.strand.client.ClientRopePoint;
import dev.simulated_team.simulated.content.blocks.rope.strand.client.ClientRopeStrand;
import dev.simulated_team.simulated.index.SimPartialModels;
import dev.simulated_team.simulated.util.SimMathUtils;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;

import net.fireboy.aerocloakingcore.cloak.RopeCloakBehavior;
import net.fireboy.aerocloakingcore.network.CloakingClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Direction-independent cloak rendering for Simulated rope strands.
 *
 * Simulated normally renders a rope from the block entity that owns/started
 * the connection. That means the whole strand accidentally inherits that one
 * block entity's cloak state. This queue instead resolves both physical rope
 * endpoints and applies cloak strength along the strand itself.
 */
public final class RopeCloakRenderQueue {

    private static final int BUFFER_SIZE = 262_144;
    private static final double ENDPOINT_QUERY_RADIUS = 2.5;
    private static final int ENDPOINT_HOLDER_SEARCH_RADIUS = 2;

    private static final List<DeferredRope> ALPHA_QUEUE = new ArrayList<>();

    private static final ThreadLocal<ArrayDeque<BufferSlot>> BUFFER_POOL =
            ThreadLocal.withInitial(ArrayDeque::new);

    private RopeCloakRenderQueue() {
    }

    public static void beginFrame() {
        ALPHA_QUEUE.clear();
    }

    /**
     * @return true when Aero Cloaking Core handled the rope and Simulated's
     * normal whole-rope render should be cancelled.
     */
    public static boolean handle(
            SmartBlockEntity blockEntity,
            RopeStrandHolderBehavior ropeHolder,
            float partialTick,
            PoseStack poseStack
    ) {
        if (!ropeHolder.ownsRope()) {
            return false;
        }

        ClientRopeStrand strand = ropeHolder.getClientStrand();
        if (strand == null || strand.getPoints().size() <= 1) {
            return false;
        }

        EndpointState endpoints = resolveEndpointState(
                blockEntity,
                ropeHolder,
                strand,
                partialTick
        );

        if (endpoints == null || endpoints.maxStrength() <= 0.001F) {
            return false;
        }

        if (endpoints.requiresLateAlphaPass()) {
            ALPHA_QUEUE.add(new DeferredRope(
                    blockEntity,
                    ropeHolder,
                    partialTick,
                    copyPoseStack(poseStack),
                    endpoints
            ));
        } else {
            renderRope(
                    blockEntity,
                    ropeHolder,
                    partialTick,
                    poseStack,
                    endpoints
            );
        }

        return true;
    }

    public static void renderQueued() {
        if (ALPHA_QUEUE.isEmpty()) {
            return;
        }

        try {
            Minecraft.getInstance().getMainRenderTarget().bindWrite(false);

            for (DeferredRope rope : ALPHA_QUEUE) {
                renderRope(
                        rope.blockEntity(),
                        rope.ropeHolder(),
                        rope.partialTick(),
                        rope.poseStack(),
                        rope.endpoints()
                );
            }
        } finally {
            ALPHA_QUEUE.clear();
            Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
        }
    }

    private static void renderRope(
            SmartBlockEntity blockEntity,
            RopeStrandHolderBehavior ropeHolder,
            float partialTick,
            PoseStack poseStack,
            EndpointState endpoints
    ) {
        Level level = blockEntity.getLevel();
        ClientRopeStrand strand = ropeHolder.getClientStrand();

        if (level == null || strand == null) {
            return;
        }

        List<ClientRopePoint> points = strand.getPoints();
        ObjectArrayList<RopeRenderPoint> renderPoints =
                buildRenderPoints(partialTick, points);

        if (renderPoints.size() <= 1) {
            return;
        }

        BlockPos ownerPos = blockEntity.getBlockPos();

        Pose3dc containingPose = null;
        SubLevel containing = Sable.HELPER.getContaining(blockEntity);
        if (containing instanceof ClientSubLevel clientSubLevel) {
            containingPose = clientSubLevel.renderPose();
        }

        SuperByteBuffer middle = CachedBuffers.partialFacing(
                SimPartialModels.ROPE,
                AllBlocks.ROPE.getDefaultState(),
                Direction.NORTH
        );
        SuperByteBuffer knot = CachedBuffers.partialFacing(
                SimPartialModels.ROPE_KNOT,
                AllBlocks.ROPE.getDefaultState(),
                Direction.NORTH
        );

        // Use physical rope distance rather than point index for the gradient.
        // Simulated's first segment can be shorter than the others, so an
        // index-based lerp visibly changes when the strand is created from
        // the opposite endpoint.
        double totalLength = 0.0;
        for (int i = 1; i < renderPoints.size(); i++) {
            totalLength += renderPoints.get(i).position()
                    .distance(renderPoints.get(i - 1).position());
        }

        if (totalLength <= 1.0E-6) {
            return;
        }

        double distanceFromStart = 0.0;

        poseStack.pushPose();
        try {
            for (int i = 1; i < renderPoints.size(); i++) {
                RopeRenderPoint point0 = renderPoints.get(i - 1);
                RopeRenderPoint point1 = renderPoints.get(i);

                double length = point1.position().distance(point0.position());
                double segmentT = (distanceFromStart + length * 0.5) / totalLength;
                distanceFromStart += length;

                SegmentVisual segmentVisual = endpoints.visualAt(segmentT);
                float segmentStrength = segmentVisual.logicalStrength();

                if (segmentStrength >= 0.999F) {
                    continue;
                }

                Vector3d globalRenderPos = new Vector3d(point0.position());
                Vector3d renderPos = new Vector3d(point0.position());
                Quaternionf orientation = new Quaternionf(point0.orientation());

                if (containingPose != null) {
                    containingPose.transformPositionInverse(renderPos);
                    orientation.premul(
                            new Quaternionf(containingPose.orientation()).conjugate()
                    );
                }

                poseStack.pushPose();
                try {
                    poseStack.translate(
                            renderPos.x - ownerPos.getX(),
                            renderPos.y - ownerPos.getY(),
                            renderPos.z - ownerPos.getZ()
                    );
                    poseStack.mulPose(orientation);
                    poseStack.translate(-0.5, -0.5, -0.5);

                    BlockPos lightPos = BlockPos.containing(
                            globalRenderPos.x,
                            globalRenderPos.y,
                            globalRenderPos.z
                    );
                    int worldLight = LevelRenderer.getLightColor(level, lightPos);

                    BufferSlot slot = acquireBuffer();
                    EntityCloakRenderState.beginComposite(
                            segmentStrength,
                            segmentVisual.ditherStrength(),
                            segmentVisual.alphaMultiplier()
                    );
                    try {
                        VertexConsumer vb = slot.source.getBuffer(RenderType.solid());

                        if (i > 1) {
                            knot.light(worldLight).renderInto(poseStack, vb);
                        }

                        poseStack.translate(0.0, 0.5, 0.0);
                        poseStack.scale(1.0F, (float) length, 1.0F);
                        middle.light(worldLight).renderInto(poseStack, vb);

                        slot.source.endBatch();
                    } finally {
                        try {
                            slot.source.endBatch();
                        } finally {
                            EntityCloakRenderState.end();
                            releaseBuffer(slot);
                        }
                    }
                } finally {
                    poseStack.popPose();
                }
            }
        } finally {
            poseStack.popPose();
        }
    }

    private static EndpointState resolveEndpointState(
            SmartBlockEntity owner,
            RopeStrandHolderBehavior ownerHolder,
            ClientRopeStrand strand,
            float partialTick
    ) {
        Level ownerLevel = owner.getLevel();
        Level rootLevel = Minecraft.getInstance().level;
        List<ClientRopePoint> points = strand.getPoints();

        if (ownerLevel == null || rootLevel == null || points.size() <= 1) {
            return null;
        }

        Vector3d firstRenderedPoint = points.getFirst().renderPos(
                partialTick,
                new Vector3d()
        );
        Vector3d lastRenderedPoint = points.getLast().renderPos(
                partialTick,
                new Vector3d()
        );

        /*
         * Do not trust ClientRopeStrand.startAttachment/endAttachment here.
         * Simulated's client packet only carries each holder's BlockPos, not
         * the sublevel UUID that BlockPos belongs to.  Resolving those positions
         * through the owner's Level is therefore inherently ambiguous for
         * world<->sublevel and sublevel<->sublevel ropes.
         *
         * The owner itself is unambiguous, so anchor one rendered endpoint to
         * the owner's real holder.  Then locate the holder at the other physical
         * endpoint in the root world and nearby client sublevels.  This lets
         * both world<->sublevel gradients and sublevel<->sublevel inheritance
         * coexist without special-casing one at the expense of the other.
         */
        ResolvedAttachment ownerAttachment = resolveOwnerAttachment(
                owner,
                ownerHolder
        );

        boolean ownerIsFirst = true;
        if (ownerAttachment.hasWorldPosition()) {
            double firstError = firstRenderedPoint.distance(
                    ownerAttachment.worldPosition()
            );
            double lastError = lastRenderedPoint.distance(
                    ownerAttachment.worldPosition()
            );
            ownerIsFirst = firstError <= lastError;
        }

        Vector3dc remoteRenderedPoint = ownerIsFirst
                ? lastRenderedPoint
                : firstRenderedPoint;

        ResolvedAttachment remoteAttachment = findPhysicalRopeHolderEndpoint(
                rootLevel,
                owner,
                remoteRenderedPoint
        );

        if (remoteAttachment == null) {
            ClientSubLevel fallback = findEndpointSubLevel(
                    rootLevel,
                    remoteRenderedPoint
            );
            remoteAttachment = new ResolvedAttachment(
                    EndpointVisual.of(fallback),
                    new Vector3d(remoteRenderedPoint),
                    true,
                    fallback != null,
                    fallback
            );
        }

        ResolvedAttachment firstResolved = ownerIsFirst
                ? ownerAttachment
                : remoteAttachment;
        ResolvedAttachment lastResolved = ownerIsFirst
                ? remoteAttachment
                : ownerAttachment;

        EndpointVisual firstVisual = firstResolved.visual();
        EndpointVisual lastVisual = lastResolved.visual();
        boolean firstIsSublevel = firstResolved.sublevelEndpoint();
        boolean lastIsSublevel = lastResolved.sublevelEndpoint();

        /*
         * World has no cloak technique of its own.  For a world<->sublevel
         * rope the world end remains strength 0, but it uses the connected
         * sublevel's rendering mode so the gradient is a pure alpha or pure
         * dither gradient.  Sublevel<->sublevel ropes retain independent modes
         * and the existing interpolation below is deliberately unchanged.
         */
        if (firstIsSublevel != lastIsSublevel) {
            if (firstIsSublevel) {
                lastVisual = new EndpointVisual(
                        lastVisual.strength(),
                        firstVisual.mode()
                );
            } else {
                firstVisual = new EndpointVisual(
                        firstVisual.strength(),
                        lastVisual.mode()
                );
            }
        }

        if (firstVisual.strength() <= 0.001F
                && lastVisual.strength() <= 0.001F) {
            return null;
        }

        return new EndpointState(
                firstVisual.strength(),
                lastVisual.strength(),
                firstVisual.mode(),
                lastVisual.mode(),
                CloakingClient.getRopeCloakBehavior()
        );
    }

    private static ResolvedAttachment resolveOwnerAttachment(
            SmartBlockEntity owner,
            RopeStrandHolderBehavior holder
    ) {
        SubLevel containing = Sable.HELPER.getContaining(owner);
        ClientSubLevel subLevel = containing instanceof ClientSubLevel client
                && !client.isRemoved()
                ? client
                : null;

        net.minecraft.world.phys.Vec3 localAttachment = holder.getAttachmentPoint();
        Vector3d worldPosition = toRootWorld(
                owner.getLevel(),
                subLevel,
                localAttachment
        );

        return new ResolvedAttachment(
                EndpointVisual.of(subLevel),
                worldPosition,
                true,
                subLevel != null,
                subLevel
        );
    }

    /**
     * Finds the actual rope-holder block entity nearest a rendered endpoint.
     * This is intentionally based on real client block entities rather than
     * Simulated's lossy client attachment BlockPos values.
     */
    private static ResolvedAttachment findPhysicalRopeHolderEndpoint(
            Level rootLevel,
            SmartBlockEntity owner,
            Vector3dc renderedEndpoint
    ) {
        HolderCandidate best = findHolderInLevel(
                rootLevel,
                null,
                owner,
                renderedEndpoint
        );

        AABB query = new AABB(
                renderedEndpoint.x() - ENDPOINT_QUERY_RADIUS,
                renderedEndpoint.y() - ENDPOINT_QUERY_RADIUS,
                renderedEndpoint.z() - ENDPOINT_QUERY_RADIUS,
                renderedEndpoint.x() + ENDPOINT_QUERY_RADIUS,
                renderedEndpoint.y() + ENDPOINT_QUERY_RADIUS,
                renderedEndpoint.z() + ENDPOINT_QUERY_RADIUS
        );

        for (SubLevel candidate : Sable.HELPER.getAllIntersecting(
                rootLevel,
                new BoundingBox3d(query)
        )) {
            if (!(candidate instanceof ClientSubLevel clientSubLevel)
                    || clientSubLevel.isRemoved()) {
                continue;
            }

            HolderCandidate sublevelCandidate = findHolderInLevel(
                    clientSubLevel.getLevel(),
                    clientSubLevel,
                    owner,
                    renderedEndpoint
            );

            if (sublevelCandidate != null
                    && (best == null
                    || sublevelCandidate.distanceSquared() < best.distanceSquared())) {
                best = sublevelCandidate;
            }
        }

        if (best == null) {
            return null;
        }

        return new ResolvedAttachment(
                EndpointVisual.of(best.subLevel()),
                best.worldPosition(),
                true,
                best.subLevel() != null,
                best.subLevel()
        );
    }

    private static HolderCandidate findHolderInLevel(
            Level candidateLevel,
            ClientSubLevel candidateSubLevel,
            SmartBlockEntity owner,
            Vector3dc renderedEndpoint
    ) {
        Vector3d localEndpoint = new Vector3d(renderedEndpoint);
        if (candidateSubLevel != null) {
            candidateSubLevel.renderPose().transformPositionInverse(localEndpoint);
        }

        BlockPos center = BlockPos.containing(
                localEndpoint.x,
                localEndpoint.y,
                localEndpoint.z
        );

        HolderCandidate best = null;

        for (int dx = -ENDPOINT_HOLDER_SEARCH_RADIUS;
             dx <= ENDPOINT_HOLDER_SEARCH_RADIUS;
             dx++) {
            for (int dy = -ENDPOINT_HOLDER_SEARCH_RADIUS;
                 dy <= ENDPOINT_HOLDER_SEARCH_RADIUS;
                 dy++) {
                for (int dz = -ENDPOINT_HOLDER_SEARCH_RADIUS;
                     dz <= ENDPOINT_HOLDER_SEARCH_RADIUS;
                     dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    var candidateBlockEntity = candidateLevel.getBlockEntity(pos);

                    if (!(candidateBlockEntity instanceof SmartBlockEntity smart)
                            || smart == owner) {
                        continue;
                    }

                    RopeStrandHolderBehavior holder = smart.getBehaviour(
                            RopeStrandHolderBehavior.TYPE
                    );

                    if (holder == null || !holder.isAttached()) {
                        continue;
                    }

                    net.minecraft.world.phys.Vec3 localAttachment =
                            holder.getAttachmentPoint();
                    Vector3d worldAttachment = toRootWorld(
                            candidateLevel,
                            candidateSubLevel,
                            localAttachment
                    );
                    double distanceSquared = worldAttachment.distanceSquared(
                            renderedEndpoint
                    );

                    if (best == null
                            || distanceSquared < best.distanceSquared()) {
                        best = new HolderCandidate(
                                candidateSubLevel,
                                worldAttachment,
                                distanceSquared
                        );
                    }
                }
            }
        }

        return best;
    }

    private static Vector3d toRootWorld(
            Level level,
            ClientSubLevel subLevel,
            net.minecraft.world.phys.Vec3 localPosition
    ) {
        if (subLevel == null) {
            return new Vector3d(
                    localPosition.x,
                    localPosition.y,
                    localPosition.z
            );
        }

        net.minecraft.world.phys.Vec3 projected =
                Sable.HELPER.projectOutOfSubLevel(level, localPosition);

        if (projected == null) {
            return new Vector3d(
                    localPosition.x,
                    localPosition.y,
                    localPosition.z
            );
        }

        return new Vector3d(projected.x, projected.y, projected.z);
    }

    private static ClientSubLevel findEndpointSubLevel(
            Level level,
            Vector3dc position
    ) {
        AABB query = new AABB(
                position.x() - ENDPOINT_QUERY_RADIUS,
                position.y() - ENDPOINT_QUERY_RADIUS,
                position.z() - ENDPOINT_QUERY_RADIUS,
                position.x() + ENDPOINT_QUERY_RADIUS,
                position.y() + ENDPOINT_QUERY_RADIUS,
                position.z() + ENDPOINT_QUERY_RADIUS
        );

        ClientSubLevel best = null;
        double bestDistanceSquared = Double.POSITIVE_INFINITY;

        for (SubLevel subLevel : Sable.HELPER.getAllIntersecting(
                level,
                new BoundingBox3d(query)
        )) {
            if (!(subLevel instanceof ClientSubLevel clientSubLevel)
                    || clientSubLevel.isRemoved()) {
                continue;
            }

            Vector3d local = new Vector3d(position);
            clientSubLevel.renderPose().transformPositionInverse(local);

            // Prefer the sublevel whose local-space endpoint is nearest to a
            // real rope holder.  If none can be found, use bounds proximity as
            // a last-resort classification only.
            HolderCandidate holder = findHolderInLevel(
                    clientSubLevel.getLevel(),
                    clientSubLevel,
                    null,
                    position
            );
            if (holder != null && holder.distanceSquared() < bestDistanceSquared) {
                best = clientSubLevel;
                bestDistanceSquared = holder.distanceSquared();
            } else if (best == null) {
                best = clientSubLevel;
            }
        }

        return best;
    }

    private static ObjectArrayList<RopeRenderPoint> buildRenderPoints(
            float partialTick,
            List<ClientRopePoint> inputPoints
    ) {
        ObjectArrayList<RopeRenderPoint> renderPoints = new ObjectArrayList<>();
        ObjectArrayList<ClientRopePoint> points = new ObjectArrayList<>(inputPoints);

        while (points.size() >= 2
                && points.getFirst().position().distanceSquared(
                        points.get(1).position()
                ) < 1.0E-3) {
            points.removeFirst();
        }

        if (points.size() <= 1) {
            return renderPoints;
        }

        Vector3dc pointZeroPosition = points.get(0).renderPos(
                partialTick,
                new Vector3d()
        );
        Vector3dc pointOnePosition = points.get(1).renderPos(
                partialTick,
                new Vector3d()
        );

        Vector3d normal = pointOnePosition
                .sub(pointZeroPosition, new Vector3d())
                .normalize();

        Quaternionf runningRotation;
        if (normal.dot(OrientedBoundingBox3d.UP) < 0) {
            runningRotation = SimMathUtils.getQuaternionfFromVectorRotation(
                    new Vector3d(0, -1, 0),
                    normal
            );
            runningRotation.rotateZ((float) Math.PI);
        } else {
            runningRotation = SimMathUtils.getQuaternionfFromVectorRotation(
                    new Vector3d(0, 1, 0),
                    normal
            );
        }

        renderPoints.add(new RopeRenderPoint(
                new Quaternionf(runningRotation),
                new Vector3d(pointZeroPosition)
        ));

        Vector3d runningNormal = new Vector3d();
        Vector3d bPos = new Vector3d();
        Vector3d aPos = new Vector3d();

        for (int i = 2; i < points.size(); i++) {
            ClientRopePoint pointA = points.get(i - 1);
            ClientRopePoint pointB = points.get(i);

            runningNormal.set(pointB.renderPos(partialTick, bPos))
                    .sub(pointA.renderPos(partialTick, aPos))
                    .normalize();

            if (runningNormal.dot(OrientedBoundingBox3d.UP) < -0.15) {
                runningRotation.set(
                        SimMathUtils.getQuaternionfFromVectorRotation(
                                new Vector3d(0, -1, 0),
                                runningNormal
                        )
                );
                runningRotation.rotateZ((float) Math.PI);
            } else {
                runningRotation.set(
                        SimMathUtils.getQuaternionfFromVectorRotation(
                                new Vector3d(0, 1, 0),
                                runningNormal
                        )
                );
            }

            renderPoints.add(new RopeRenderPoint(
                    new Quaternionf(runningRotation),
                    pointA.renderPos(partialTick, new Vector3d())
            ));
            normal.set(runningNormal);
        }

        renderPoints.add(new RopeRenderPoint(
                new Quaternionf(runningRotation),
                points.getLast().renderPos(partialTick, new Vector3d())
        ));

        return renderPoints;
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

    private record RopeRenderPoint(
            Quaternionf orientation,
            Vector3d position
    ) {
    }

    private record HolderCandidate(
            ClientSubLevel subLevel,
            Vector3d worldPosition,
            double distanceSquared
    ) {
    }

    private record ResolvedAttachment(
            EndpointVisual visual,
            Vector3d worldPosition,
            boolean hasWorldPosition,
            boolean sublevelEndpoint,
            ClientSubLevel subLevel
    ) {
    }

    private record EndpointVisual(
            float strength,
            CloakRenderMode mode
    ) {
        static EndpointVisual of(ClientSubLevel subLevel) {
            if (subLevel == null) {
                return new EndpointVisual(0.0F, CloakRenderMode.DITHER);
            }

            return new EndpointVisual(
                    CloakingClient.getViewerCloakStrength(subLevel),
                    CloakingClient.getRenderMode(subLevel)
            );
        }
    }

    /**
     * The two physical rope ends keep their own render modes.  This is
     * important for DITHER <-> ALPHA ropes: forcing the whole rope into one
     * mode made the endpoint on the other sublevel look wrong.
     */
    private record EndpointState(
            float startStrength,
            float endStrength,
            CloakRenderMode startMode,
            CloakRenderMode endMode,
            RopeCloakBehavior behavior
    ) {
        float maxStrength() {
            return Math.max(startStrength, endStrength);
        }

        boolean requiresLateAlphaPass() {
            if (behavior == RopeCloakBehavior.INHERIT_STRONGEST) {
                return strongestVisual().mode().isAlpha();
            }

            // If either endpoint is alpha, at least part of the gradient can
            // have alpha blending and therefore belongs in the late pass.
            return startMode.isAlpha() || endMode.isAlpha();
        }

        SegmentVisual visualAt(double progress) {
            if (behavior == RopeCloakBehavior.INHERIT_STRONGEST) {
                EndpointVisual strongest = strongestVisual();
                return pureVisual(strongest.strength(), strongest.mode());
            }

            double t = Math.max(0.0, Math.min(1.0, progress));
            float strength = (float) (
                    startStrength
                            + (endStrength - startStrength) * t
            );

            float startAlphaMix = startMode.isAlpha() ? 1.0F : 0.0F;
            float endAlphaMix = endMode.isAlpha() ? 1.0F : 0.0F;
            float alphaMix = (float) (
                    startAlphaMix
                            + (endAlphaMix - startAlphaMix) * t
            );

            return compositeVisual(strength, alphaMix);
        }

        private EndpointVisual strongestVisual() {
            if (endStrength > startStrength + 1.0E-4F) {
                return new EndpointVisual(endStrength, endMode);
            }

            if (startStrength > endStrength + 1.0E-4F) {
                return new EndpointVisual(startStrength, startMode);
            }

            // Equal strength: prefer alpha if only one endpoint is alpha so
            // INHERIT_STRONGEST is deterministic and never placement-order
            // dependent.  Otherwise the modes are equivalent for ropes.
            if (endMode.isAlpha() && !startMode.isAlpha()) {
                return new EndpointVisual(endStrength, endMode);
            }

            return new EndpointVisual(startStrength, startMode);
        }

        private static SegmentVisual pureVisual(
                float strength,
                CloakRenderMode mode
        ) {
            float clampedStrength = clamp01(strength);

            if (mode.isAlpha()) {
                return new SegmentVisual(
                        clampedStrength,
                        0.0F,
                        1.0F - clampedStrength
                );
            }

            return new SegmentVisual(
                    clampedStrength,
                    clampedStrength,
                    1.0F
            );
        }

        /**
         * Smoothly morphs the cloak technique while keeping the rope's
         * expected visibility equal to 1-strength.
         *
         * alphaMix=0 -> pure dither
         * alphaMix=1 -> pure alpha
         * values between use both.  We reduce dither coverage as alpha takes
         * over, then choose alpha so (surviving coverage * alpha) still equals
         * the requested visibility.  This avoids a dark/double-faded band in
         * the middle of a DITHER <-> ALPHA rope.
         */
        private static SegmentVisual compositeVisual(
                float strength,
                float alphaMix
        ) {
            float s = clamp01(strength);
            float mix = clamp01(alphaMix);

            if (mix <= 0.001F) {
                return pureVisual(s, CloakRenderMode.DITHER);
            }

            if (mix >= 0.999F) {
                return pureVisual(s, CloakRenderMode.ALPHA);
            }

            float ditherStrength = s * (1.0F - mix);
            float survivingCoverage = 1.0F - ditherStrength;
            float targetVisibility = 1.0F - s;

            float alphaMultiplier = survivingCoverage <= 1.0E-5F
                    ? 0.0F
                    : targetVisibility / survivingCoverage;

            return new SegmentVisual(
                    s,
                    clamp01(ditherStrength),
                    clamp01(alphaMultiplier)
            );
        }

        private static float clamp01(float value) {
            return Math.max(0.0F, Math.min(1.0F, value));
        }
    }

    private record SegmentVisual(
            float logicalStrength,
            float ditherStrength,
            float alphaMultiplier
    ) {
    }

    private static final class BufferSlot {
        private final ByteBufferBuilder backing =
                new ByteBufferBuilder(BUFFER_SIZE);

        private final MultiBufferSource.BufferSource source =
                MultiBufferSource.immediate(backing);
    }

    private record DeferredRope(
            SmartBlockEntity blockEntity,
            RopeStrandHolderBehavior ropeHolder,
            float partialTick,
            PoseStack poseStack,
            EndpointState endpoints
    ) {
    }
}
