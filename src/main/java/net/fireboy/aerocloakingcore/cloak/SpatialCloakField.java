package net.fireboy.aerocloakingcore.cloak;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-authoritative, position-based cloak field detection.
 *
 * A sublevel may overlap a cloak field without joining the Sable connection
 * graph or the owner's Cloaking Core network. Every intersecting physical
 * block adds load; only fully contained sublevels inherit the field. The
 * existing Linear Bearing exception can inherit before full containment.
 *
 * The field approximates the solid host's convex envelope with support
 * planes, matching the client closest-face approach. An AABB broad phase
 * avoids inspecting distant ships. Geometry is cached and periodically rebuilt
 * to account for block edits, while logical poses are used fresh every scan.
 */
public final class SpatialCloakField {
    private static final int HULL_REBUILD_TICKS = 100;
    private static final int STATIONARY_RECHECK_TICKS = 20;
    private static final int SLOW_MOVING_RECHECK_TICKS = 2;
    private static final double FAST_MOTION_PER_TICK = 0.75;
    private static final double MOTION_EPSILON = 0.002;
    /** Require two consecutive evaluations before first granting inheritance.
     * Overlap load is never delayed; departures revoke inheritance at once. */
    private static final long NO_PENDING_ENTRY = Long.MIN_VALUE;
    // Discover approaching contraptions before they reach the physical hull.
    // Detailed voxel work still only happens for overlapping candidates.
    private static final double DISCOVERY_MARGIN = 4.0;
    private static final long MAX_HULL_SCAN_VOLUME = 300_000L;
    private static final double EPSILON = 1.0E-4;
    private static final int[][] DIRECTIONS = createDirections();
    private static final Map<UUID, Hull> HULL_CACHE = new HashMap<>();

    private SpatialCloakField() {
    }

    public record Intersection(int load, boolean contained, boolean linearBearing) {
    }

    /** Per-cloak-system cache, owned and cleared with the owning CloakSystem. */
    public static final class ScanCache {
        private final Map<UUID, CandidateCache> candidates = new HashMap<>();

        public void clear() {
            candidates.clear();
        }
    }

    private static final class CandidateCache {
        private double[] lastSeenPose;
        private long lastSeenTick;
        private double[] evaluatedPose;
        private long evaluatedTick;
        private Intersection intersection;
        private long pendingEntryTick = NO_PENDING_ENTRY;
    }

    public static Map<UUID, Intersection> scan(
            List<SubLevel> hosts,
            Set<UUID> connectedMembers,
            ScanCache cache,
            long tick
    ) {
        if (hosts.isEmpty()) {
            return Map.of();
        }

        List<HostField> fields = new ArrayList<>();
        Map<UUID, SubLevel> candidates = new LinkedHashMap<>();

        for (SubLevel host : hosts) {
            if (host == null || host.isRemoved()) {
                continue;
            }
            BoundingBox3dc box = host.boundingBox();
            BoundingBox3d query = new BoundingBox3d(
                    box.minX() - DISCOVERY_MARGIN,
                    box.minY() - DISCOVERY_MARGIN,
                    box.minZ() - DISCOVERY_MARGIN,
                    box.maxX() + DISCOVERY_MARGIN,
                    box.maxY() + DISCOVERY_MARGIN,
                    box.maxZ() + DISCOVERY_MARGIN
            );

            // Broad-phase search runs once each server tick. Do not rebuild
            // even a cached hull when nobody is near the field.
            List<SubLevel> nearby = new ArrayList<>();
            for (SubLevel candidate : Sable.HELPER.getAllIntersecting(
                    host.getLevel(), query)) {
                if (candidate != null && !candidate.isRemoved()
                        && !connectedMembers.contains(candidate.getUniqueId())) {
                    nearby.add(candidate);
                }
            }
            if (nearby.isEmpty()) {
                continue;
            }

            Hull hull = hullFor(host);
            if (hull == null) {
                continue;
            }
            fields.add(new HostField(host, hull));
            for (SubLevel candidate : nearby) {
                candidates.putIfAbsent(candidate.getUniqueId(), candidate);
            }
        }

        // Drop stale motion samples as soon as candidates leave the near-field.
        cache.candidates.keySet().retainAll(candidates.keySet());
        if (fields.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Intersection> result = new LinkedHashMap<>();
        for (SubLevel candidate : candidates.values()) {
            UUID id = candidate.getUniqueId();
            CandidateCache previous = cache.candidates.computeIfAbsent(
                    id, ignored -> new CandidateCache()
            );
            double[] pose = relativePose(candidate, fields);
            double recentMovement = poseDisplacement(previous.lastSeenPose, pose);
            long lastSeenGap = previous.lastSeenPose == null
                    ? 1 : Math.max(1L, tick - previous.lastSeenTick);
            boolean movingFast = recentMovement >=
                    FAST_MOTION_PER_TICK * lastSeenGap;
            // Include motion since the last FULL evaluation. A contraption
            // that moves for one tick and stops must not wait 20 more ticks
            // for its new load/containment to be sampled.
            boolean moving = poseDisplacement(previous.evaluatedPose, pose)
                    > MOTION_EPSILON;
            previous.lastSeenPose = pose;
            previous.lastSeenTick = tick;

            int interval = movingFast ? 1
                    : moving ? SLOW_MOVING_RECHECK_TICKS
                    : STATIONARY_RECHECK_TICKS;
            if (previous.pendingEntryTick != NO_PENDING_ENTRY) {
                interval = 1;
            }
            // Re-evaluate a moving partially overlapping candidate each tick
            // near the containment boundary, avoiding missed load changes.
            if (moving && previous.intersection != null
                    && !previous.intersection.contained()) {
                interval = 1;
            }
            long elapsed = tick - previous.evaluatedTick;
            if (previous.evaluatedPose != null
                    && elapsed >= 0 && elapsed < interval
                    && poseDisplacement(previous.evaluatedPose, pose)
                        != Double.POSITIVE_INFINITY) {
                if (previous.intersection != null) {
                    result.put(id, previous.intersection);
                }
                continue;
            }

            // A Linear Bearing's moving carriage inherits the cloak whenever
            // its bearing sits inside the parent's bounds, even when the
            // carriage extends beyond the field's physical envelope.
            boolean bearing = false;
            for (HostField field : fields) {
                if (LinearBearingCloakCompat.hasBearingInside(
                        candidate, field.subLevel().boundingBox())) {
                    bearing = true;
                    break;
                }
            }

            // The overwhelmingly common case is a small sublevel wholly
            // inside one host's convex field. A convex envelope containing
            // all eight corners of the candidate plot also contains every
            // block in that plot, so count its solid blocks via palettes and
            // skip the expensive per-voxel position transformations.
            if (bearing || fullyInsideOneHost(candidate, fields)) {
                int total = countBlocks(candidate);
                Intersection hit = total > 0
                        ? new Intersection(total, true, bearing) : null;
                hit = confirmContainment(previous, hit, tick);
                previous.evaluatedPose = pose;
                previous.evaluatedTick = tick;
                previous.intersection = hit;
                if (hit != null) {
                    result.put(id, hit);
                }
                continue;
            }

            int total = 0;
            int intersecting = 0;
            boolean fullyContained = true;
            Vector3d world = new Vector3d();
            Vector3d local = new Vector3d();

            for (var chunkHolder : candidate.getPlot().getLoadedChunks()) {
                LevelChunk chunk = chunkHolder.getChunk();
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection[] sections = chunk.getSections();
                for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                    LevelChunkSection section = sections[sectionIndex];
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }

                    int baseX = chunk.getPos().getMinBlockX();
                    int baseY = chunk.getSectionYFromSectionIndex(sectionIndex) * 16;
                    int baseZ = chunk.getPos().getMinBlockZ();

                    int sectionCoverage = classifySection(
                            candidate, fields, baseX, baseY, baseZ
                    );
                    if (sectionCoverage != 0) {
                        int solidCount = countBlocks(section);
                        total += solidCount;
                        if (sectionCoverage > 0) {
                            intersecting += solidCount;
                        } else {
                            fullyContained = false;
                        }
                        continue;
                    }

                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                if (section.getBlockState(x, y, z).isAir()) {
                                    continue;
                                }
                                total++;
                                double bx = baseX + x;
                                double by = baseY + y;
                                double bz = baseZ + z;

                                world.set(bx + .5, by + .5, bz + .5);
                                candidate.logicalPose().transformPosition(world);

                                boolean centerInside = containsAny(fields, world, local);
                                boolean allCornersInside = centerInside;
                                boolean anyCornerInside = centerInside;
                                // Once any earlier block is outside, this
                                // candidate cannot inherit the entire cloak:
                                // from then on, only check corners for blocks
                                // whose center is outside and stop on first
                                // overlap. Enclosed cargo uses the fast path
                                // above and never enters this loop at all.
                                if (fullyContained || !centerInside) {
                                    corners:
                                    for (int cx = 0; cx <= 1; cx++) {
                                        for (int cy = 0; cy <= 1; cy++) {
                                            for (int cz = 0; cz <= 1; cz++) {
                                                world.set(bx + cx, by + cy, bz + cz);
                                                candidate.logicalPose().transformPosition(world);
                                                boolean inside = containsAny(fields, world, local);
                                                allCornersInside &= inside;
                                                anyCornerInside |= inside;
                                                if (!fullyContained && anyCornerInside) {
                                                    break corners;
                                                }
                                            }
                                        }
                                    }
                                }

                                if (anyCornerInside) {
                                    intersecting++;
                                }
                                if (!allCornersInside) {
                                    fullyContained = false;
                                }
                            }
                        }
                    }
                }
            }

            Intersection hit = total > 0 && intersecting > 0
                    ? new Intersection(
                            intersecting,
                            fullyContained && intersecting == total,
                            false
                    )
                    : null;
            hit = confirmContainment(previous, hit, tick);
            previous.evaluatedPose = pose;
            previous.evaluatedTick = tick;
            previous.intersection = hit;
            if (hit != null) {
                result.put(id, hit);
            }
        }
        return result;
    }

    /**
     * Ignore a one-tick full-containment blip at the field edge. This affects
     * only whether the child receives cloak rendering; the measured block
     * load and SU/capacity calculation always use the current intersection.
     * The Linear Bearing exception retains immediate cloak inheritance.
     */
    private static Intersection confirmContainment(
            CandidateCache previous, Intersection hit, long tick
    ) {
        if (hit == null || !hit.contained() || hit.linearBearing()) {
            previous.pendingEntryTick = NO_PENDING_ENTRY;
            return hit;
        }
        if (previous.intersection != null && previous.intersection.contained()) {
            previous.pendingEntryTick = NO_PENDING_ENTRY;
            return hit;
        }
        if (previous.pendingEntryTick != NO_PENDING_ENTRY
                && tick > previous.pendingEntryTick
                && tick - previous.pendingEntryTick <= 2) {
            previous.pendingEntryTick = NO_PENDING_ENTRY;
            return hit;
        }
        previous.pendingEntryTick = tick;
        return new Intersection(hit.load(), false, false);
    }

    /**
     * Samples a candidate's rigid pose relative to every host field. Relative
     * sampling, rather than world AABBs, lets a docked craft travel with its
     * parent ship without invalidating the expensive voxel overlap cache.
     *
     * Four non-coplanar points capture translation and rotation. The final
     * value for each host stamps the cached hull's geometry rebuild.
     */
    private static double[] relativePose(
            SubLevel candidate, List<HostField> fields
    ) {
        var bounds = candidate.getPlot().getBoundingBox();
        if (bounds == null) {
            return new double[0];
        }
        double[] signature = new double[fields.size() * 13];
        Vector3d world = new Vector3d();
        Vector3d local = new Vector3d();
        for (int h = 0; h < fields.size(); h++) {
            HostField host = fields.get(h);
            for (int corner = 0; corner < 4; corner++) {
                world.set(
                        bounds.minX() + (corner == 1 ? 1 : 0),
                        bounds.minY() + (corner == 2 ? 1 : 0),
                        bounds.minZ() + (corner == 3 ? 1 : 0)
                );
                candidate.logicalPose().transformPosition(world);
                local.set(world);
                host.subLevel().logicalPose().transformPositionInverse(local);
                int offset = h * 13 + corner * 3;
                signature[offset] = local.x;
                signature[offset + 1] = local.y;
                signature[offset + 2] = local.z;
            }
            signature[h * 13 + 12] = host.hull().builtAtTick;
        }
        return signature;
    }

    private static double poseDisplacement(double[] previous, double[] current) {
        if (previous == null || current == null
                || previous.length != current.length) {
            return Double.POSITIVE_INFINITY;
        }
        double greatest = 0.0;
        for (int i = 0; i + 12 < current.length; i += 13) {
            if (previous[i + 12] != current[i + 12]) {
                return Double.POSITIVE_INFINITY;
            }
            for (int corner = 0; corner < 4; corner++) {
                int j = i + corner * 3;
                double dx = previous[j] - current[j];
                double dy = previous[j + 1] - current[j + 1];
                double dz = previous[j + 2] - current[j + 2];
                greatest = Math.max(greatest, Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
        }
        return greatest;
    }

    public static void clear() {
        HULL_CACHE.clear();
    }

    private static int countBlocks(SubLevel subLevel) {
        int total = 0;
        for (var chunkHolder : subLevel.getPlot().getLoadedChunks()) {
            LevelChunk chunk = chunkHolder.getChunk();
            if (chunk == null) {
                continue;
            }
            for (LevelChunkSection section : chunk.getSections()) {
                if (section != null && !section.hasOnlyAir()) {
                    total += countBlocks(section);
                }
            }
        }
        return total;
    }

    private static int countBlocks(LevelChunkSection section) {
        int[] count = {0};
        section.getStates().count((state, occurrences) -> {
            if (!state.isAir()) {
                count[0] += occurrences;
            }
        });
        return count[0];
    }

    /**
     * Conservative solid-section rejection using the host's support planes.
     * +1: a single convex field encloses the entire section
     * -1: the section is separated from EVERY field
     *  0: uncertain/partial, requiring per-block checks
     *
     * The negative case is only accepted when a field support plane cleanly
     * separates all eight transformed section corners. This cannot discard
     * physically intersecting blocks.
     */
    private static int classifySection(
            SubLevel candidate, List<HostField> fields,
            int baseX, int baseY, int baseZ
    ) {
        Vector3d[] world = new Vector3d[8];
        int i = 0;
        for (int x = 0; x <= 1; x++) {
            for (int y = 0; y <= 1; y++) {
                for (int z = 0; z <= 1; z++) {
                    Vector3d point = new Vector3d(
                            baseX + x * 16.0,
                            baseY + y * 16.0,
                            baseZ + z * 16.0
                    );
                    candidate.logicalPose().transformPosition(point);
                    world[i++] = point;
                }
            }
        }

        boolean uncertain = false;
        Vector3d[] local = new Vector3d[8];
        for (HostField field : fields) {
            for (int corner = 0; corner < 8; corner++) {
                local[corner] = new Vector3d(world[corner]);
                field.subLevel().logicalPose().transformPositionInverse(local[corner]);
            }
            int coverage = field.hull().classify(local);
            if (coverage > 0) {
                return 1;
            }
            if (coverage == 0) {
                uncertain = true;
            }
        }
        return uncertain ? 0 : -1;
    }

    private static boolean fullyInsideOneHost(
            SubLevel candidate, List<HostField> fields
    ) {
        var bounds = candidate.getPlot().getBoundingBox();
        if (bounds == null || bounds.maxX() < bounds.minX()
                || bounds.maxY() < bounds.minY()
                || bounds.maxZ() < bounds.minZ()) {
            return false;
        }

        Vector3d world = new Vector3d();
        Vector3d local = new Vector3d();
        for (HostField host : fields) {
            boolean enclosed = true;
            for (int dx = 0; dx <= 1 && enclosed; dx++) {
                for (int dy = 0; dy <= 1 && enclosed; dy++) {
                    for (int dz = 0; dz <= 1; dz++) {
                        double x = dx == 0 ? bounds.minX() : bounds.maxX() + 1.0;
                        double y = dy == 0 ? bounds.minY() : bounds.maxY() + 1.0;
                        double z = dz == 0 ? bounds.minZ() : bounds.maxZ() + 1.0;
                        world.set(x, y, z);
                        candidate.logicalPose().transformPosition(world);
                        local.set(world);
                        host.subLevel().logicalPose().transformPositionInverse(local);
                        if (!host.hull().contains(local)) {
                            enclosed = false;
                            break;
                        }
                    }
                }
            }
            if (enclosed) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(
            List<HostField> fields, Vector3d world, Vector3d scratch
    ) {
        for (HostField field : fields) {
            scratch.set(world);
            field.subLevel().logicalPose().transformPositionInverse(scratch);
            if (field.hull().contains(scratch)) {
                return true;
            }
        }
        return false;
    }

    private static Hull hullFor(SubLevel host) {
        var bounds = host.getPlot().getBoundingBox();
        if (bounds == null || bounds.maxX() < bounds.minX()
                || bounds.maxY() < bounds.minY()
                || bounds.maxZ() < bounds.minZ()) {
            return null;
        }
        Level level = host.getLevel();
        long tick = level.getGameTime();
        UUID id = host.getUniqueId();
        Hull cached = HULL_CACHE.get(id);
        if (cached != null
                && cached.minX == bounds.minX() && cached.maxX == bounds.maxX()
                && cached.minY == bounds.minY() && cached.maxY == bounds.maxY()
                && cached.minZ == bounds.minZ() && cached.maxZ == bounds.maxZ()
                && tick - cached.builtAtTick < HULL_REBUILD_TICKS
                && tick >= cached.builtAtTick) {
            return cached;
        }

        Hull hull = new Hull(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ(), tick);
        long volume = (long) (hull.maxX - hull.minX + 1)
                * (hull.maxY - hull.minY + 1)
                * (hull.maxZ - hull.minZ + 1);
        if (volume > MAX_HULL_SCAN_VOLUME || volume <= 0) {
            hull.setBoundingBoxPlanes();
        } else {
            for (var chunkHolder : host.getPlot().getLoadedChunks()) {
                LevelChunk chunk = chunkHolder.getChunk();
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection[] sections = chunk.getSections();
                for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                    LevelChunkSection section = sections[sectionIndex];
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }
                    int baseX = chunk.getPos().getMinBlockX();
                    int baseY = chunk.getSectionYFromSectionIndex(sectionIndex) * 16;
                    int baseZ = chunk.getPos().getMinBlockZ();
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                if (!section.getBlockState(x, y, z).isAir()) {
                                    hull.include(baseX + x, baseY + y, baseZ + z);
                                }
                            }
                        }
                    }
                }
            }
            if (hull.occupied == 0) {
                return null;
            }
        }

        HULL_CACHE.put(id, hull);
        return hull;
    }

    private static int[][] createDirections() {
        Set<String> unique = new HashSet<>();
        List<int[]> directions = new ArrayList<>();
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }
                    int gcd = gcd(gcd(Math.abs(x), Math.abs(y)), Math.abs(z));
                    int dx = x / gcd, dy = y / gcd, dz = z / gcd;
                    // Each direction stores both min/max planes. As on the
                    // client, n and -n are redundant: keep 49, not 98.
                    if (dx < 0 || (dx == 0 && dy < 0)
                            || (dx == 0 && dy == 0 && dz < 0)) {
                        dx = -dx;
                        dy = -dy;
                        dz = -dz;
                    }
                    if (unique.add(dx + "," + dy + "," + dz)) {
                        directions.add(new int[]{dx, dy, dz});
                    }
                }
            }
        }
        return directions.toArray(new int[0][]);
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int remainder = a % b;
            a = b;
            b = remainder;
        }
        return Math.max(1, a);
    }

    private record HostField(SubLevel subLevel, Hull hull) {
    }

    private static final class Hull {
        private final int minX, minY, minZ, maxX, maxY, maxZ;
        private final long builtAtTick;
        private final double[] minimum = new double[DIRECTIONS.length];
        private final double[] maximum = new double[DIRECTIONS.length];
        private int occupied;

        private Hull(int minX, int minY, int minZ,
                     int maxX, int maxY, int maxZ, long tick) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.builtAtTick = tick;
            Arrays.fill(minimum, Double.POSITIVE_INFINITY);
            Arrays.fill(maximum, Double.NEGATIVE_INFINITY);
        }

        private void include(int x, int y, int z) {
            occupied++;
            for (int i = 0; i < DIRECTIONS.length; i++) {
                int[] d = DIRECTIONS[i];
                double lo = d[0] * (d[0] >= 0 ? x : x + 1.0)
                        + d[1] * (d[1] >= 0 ? y : y + 1.0)
                        + d[2] * (d[2] >= 0 ? z : z + 1.0);
                double hi = d[0] * (d[0] >= 0 ? x + 1.0 : x)
                        + d[1] * (d[1] >= 0 ? y + 1.0 : y)
                        + d[2] * (d[2] >= 0 ? z + 1.0 : z);
                minimum[i] = Math.min(minimum[i], lo);
                maximum[i] = Math.max(maximum[i], hi);
            }
        }

        private void setBoundingBoxPlanes() {
            for (int i = 0; i < DIRECTIONS.length; i++) {
                int[] d = DIRECTIONS[i];
                minimum[i] = d[0] * (d[0] >= 0 ? minX : maxX + 1.0)
                        + d[1] * (d[1] >= 0 ? minY : maxY + 1.0)
                        + d[2] * (d[2] >= 0 ? minZ : maxZ + 1.0);
                maximum[i] = d[0] * (d[0] >= 0 ? maxX + 1.0 : minX)
                        + d[1] * (d[1] >= 0 ? maxY + 1.0 : minY)
                        + d[2] * (d[2] >= 0 ? maxZ + 1.0 : minZ);
            }
        }

        /**
         * Classifies an oriented section against this convex field.
         * +1 fully within, -1 separated by a support plane, 0 uncertain.
         */
        private int classify(Vector3d[] corners) {
            boolean enclosed = true;
            for (int i = 0; i < DIRECTIONS.length; i++) {
                int[] d = DIRECTIONS[i];
                double lowest = Double.POSITIVE_INFINITY;
                double highest = Double.NEGATIVE_INFINITY;
                for (Vector3d point : corners) {
                    double dot = d[0] * point.x + d[1] * point.y + d[2] * point.z;
                    lowest = Math.min(lowest, dot);
                    highest = Math.max(highest, dot);
                }
                if (highest < minimum[i] - EPSILON
                        || lowest > maximum[i] + EPSILON) {
                    return -1;
                }
                if (lowest < minimum[i] - EPSILON
                        || highest > maximum[i] + EPSILON) {
                    enclosed = false;
                }
            }
            return enclosed ? 1 : 0;
        }

        private boolean contains(Vector3d point) {
            for (int i = 0; i < DIRECTIONS.length; i++) {
                int[] d = DIRECTIONS[i];
                double dot = d[0] * point.x + d[1] * point.y + d[2] * point.z;
                if (dot < minimum[i] - EPSILON
                        || dot > maximum[i] + EPSILON) {
                    return false;
                }
            }
            return true;
        }
    }
}
