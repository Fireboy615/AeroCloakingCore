package net.fireboy.aerocloakingcore.network;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;

import net.fireboy.aerocloakingcore.client.CloakEasing;
import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.fireboy.aerocloakingcore.cloak.CloakingCoreSettings;
import net.fireboy.aerocloakingcore.cloak.CloakDistanceMode;
import net.fireboy.aerocloakingcore.cloak.CloakingServerSettings;
import net.fireboy.aerocloakingcore.cloak.EntityCloakBehavior;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CloakingClient {

    private static final float FULL_CLOAK_THRESHOLD = 0.999F;

    /**
     * Nearest-real-block queries are intentionally cached for about one game
     * tick.  A reveal query can otherwise be repeated many times in a single
     * render frame (solid terrain, translucent terrain, entities, Flywheel,
     * connected child sublevels, etc.).
     */
    private static final long DISTANCE_CACHE_NANOS = 50_000_000L;

    /** Final per-entity cloak result cache for duplicate render-path queries. */
    private static final long ENTITY_VISUAL_CACHE_NANOS = 20_000_000L;

    /** Drop entity transition state after it has not been rendered for a while. */
    private static final long ENTITY_STATE_EXPIRY_NANOS = 30_000_000_000L;

    /** Per-sublevel settings sent by the server. */
    private static final Map<UUID, CloakingCoreSettings> CORE_SETTINGS =
            new ConcurrentHashMap<>();

    /** Server-authoritative fade/reveal configuration. */
    private static volatile CloakingServerSettings SERVER_SETTINGS =
            CloakingServerSettings.DEFAULT;

    /** Runtime transition duration calculated per sublevel from cloak-system RPM. */
    private static final Map<UUID, Float> TRANSITION_DURATIONS =
            new ConcurrentHashMap<>();

    /** Runtime ship-size/RPM-adjusted distance where proximity reveal starts. */
    private static final Map<UUID, Double> FULLY_CLOAKED_DISTANCES =
            new ConcurrentHashMap<>();

    /** Sublevels present in the most recent server sync. */
    private static final Set<UUID> SERVER_SUBLEVELS =
            ConcurrentHashMap.newKeySet();

    /** Member sublevel -> connected cloak group UUID. */
    private static final Map<UUID, UUID> GROUP_IDS =
            new ConcurrentHashMap<>();

    /** Connected cloak group UUID -> all currently synced member sublevels. */
    private static final Map<UUID, Set<UUID>> GROUP_MEMBERS =
            new ConcurrentHashMap<>();

    /**
     * Cached nearest-real-block distance for a subject/group pair.  The AABB
     * remains the broad-phase; this cache only stores the expensive narrow
     * phase result.
     */
    private static final Map<DistanceCacheKey, DistanceSample> BLOCK_DISTANCE_CACHE =
            new ConcurrentHashMap<>();

    /**
     * Closest-face mode uses a cached convex envelope when the subject is
     * already inside a sublevel's broad-phase bounds. This handles hollow or
     * frame-built ships without doing an air flood-fill every render frame.
     *
     * The envelope is represented by support planes in 49 primitive
     * directions (integer normals up to length 2 on each axis). That is a
     * deliberately cheap approximation of the true convex hull: exact for
     * axis-aligned boxes/frames and much tighter than an AABB for irregular
     * ships, while keeping point tests extremely small.
     */
    private static final int HULL_DIRECTION_RANGE = 2;
    private static final double HULL_POINT_EPSILON = 1.0E-4;
    private static final long MAX_HULL_SCAN_VOLUME = 2_000_000L;
    private static final List<HullDirection> HULL_DIRECTIONS =
            createHullDirections();
    private static final Map<UUID, HullEnvelope> HULL_ENVELOPES =
            new ConcurrentHashMap<>();

    /**
     * Viewer-style proximity/leave transitions for entities that are near a
     * cloak group but are not yet part of that sublevel. Once an entity is
     * aboard, it directly follows the observer-visible cloak amount of the ship.
     */
    private static final Map<UUID, EntityVisibilityState> ENTITY_VISIBILITY =
            new ConcurrentHashMap<>();

    /** Duplicate getEntityRenderMode/getEntityViewerCloakStrength calls. */
    private static final Map<UUID, EntityVisualSample> ENTITY_VISUAL_CACHE =
            new ConcurrentHashMap<>();

    /** The connected cloak group the local player was aboard on the previous check. */
    private static UUID lastViewerSubLevelId = null;

    /**
     * Per-sublevel viewer reveal state.
     *
     * A value of 1 means the normal cloak is fully applied. A value of 0 means
     * the sublevel is fully revealed to this viewer. Keeping this as one
     * continuous transition means boarding/leaving can reverse cleanly from
     * whatever visibility was actually on screen at that moment.
     */
    private static final Map<UUID, ViewerVisibilityTransition> VIEWER_VISIBILITY =
            new ConcurrentHashMap<>();

    /**
     * Effective reveal factor captured at the instant the player leaves a
     * connected sublevel group.  Unlike VIEWER_VISIBILITY this includes the
     * proximity multiplier, so moving away during the grace period cannot make
     * the cloak begin returning before the grace timer has actually expired.
     */
    private static final Map<UUID, LeaveVisibilityTransition> LEAVE_VISIBILITY =
            new ConcurrentHashMap<>();

    /** Last effective viewer multiplier that was actually rendered per group. */
    private static final Map<UUID, Float> LAST_EFFECTIVE_VIEWER_STRENGTH =
            new ConcurrentHashMap<>();

    /** Client-side transition state for cloaked sublevels. */
    private static final Map<UUID, CloakTransition> TRANSITIONS =
            new ConcurrentHashMap<>();

    private CloakingClient() {
    }

    public static void setCloakStates(
            List<CloakingSyncPayload.Entry> entries,
            CloakingServerSettings serverSettings
    ) {
        SERVER_SETTINGS = serverSettings != null
                ? serverSettings.normalized()
                : CloakingServerSettings.DEFAULT;

        Set<UUID> incoming = new HashSet<>();
        Set<UUID> known = new HashSet<>(TRANSITIONS.keySet());
        known.addAll(CORE_SETTINGS.keySet());
        known.addAll(TRANSITION_DURATIONS.keySet());
        known.addAll(FULLY_CLOAKED_DISTANCES.keySet());

        Map<UUID, Set<UUID>> incomingGroups = new ConcurrentHashMap<>();

        for (CloakingSyncPayload.Entry entry : entries) {
            UUID id = entry.subLevelId();
            UUID groupId = entry.groupId() != null
                    ? entry.groupId()
                    : id;
            CloakingCoreSettings settings = entry.settings().normalized();

            incoming.add(id);
            GROUP_IDS.put(id, groupId);
            incomingGroups
                    .computeIfAbsent(groupId, ignored -> ConcurrentHashMap.newKeySet())
                    .add(id);
            CORE_SETTINGS.put(id, settings);
            TRANSITION_DURATIONS.put(
                    id,
                    Math.max(0.0F, entry.transitionDurationSeconds())
            );
            FULLY_CLOAKED_DISTANCES.put(
                    id,
                    Math.max(0.0, entry.fullyCloakedDistance())
            );

            setTargetStrength(id, settings.cloakStrength());
        }

        SERVER_SUBLEVELS.clear();
        SERVER_SUBLEVELS.addAll(incoming);

        GROUP_MEMBERS.clear();
        for (Map.Entry<UUID, Set<UUID>> group : incomingGroups.entrySet()) {
            Set<UUID> members = ConcurrentHashMap.newKeySet();
            members.addAll(group.getValue());
            GROUP_MEMBERS.put(group.getKey(), members);
        }

        GROUP_IDS.keySet().removeIf(id -> !incoming.contains(id));
        HULL_ENVELOPES.keySet().removeIf(id -> !incoming.contains(id));

        for (UUID id : known) {
            if (!incoming.contains(id)) {
                setTargetStrength(id, 0.0F);
            }
        }
    }

    /** Backwards-friendly helper used by older call sites if any remain. */
    public static void setCloakedSubLevels(List<UUID> ids) {
        setCloakStates(
                ids.stream()
                        .map(id -> new CloakingSyncPayload.Entry(
                                id,
                                id,
                                CloakingCoreSettings.DEFAULT.withCloakStrength(1.0F),
                                SERVER_SETTINGS.transitionDurationSeconds(),
                                defaultFullyCloakedDistance()
                        ))
                        .toList(),
                SERVER_SETTINGS
        );
    }

    public static void setTargetStrength(
            UUID subLevelId,
            float targetStrength
    ) {
        targetStrength = clamp(targetStrength);

        long now = System.nanoTime();
        CloakTransition existing = TRANSITIONS.get(subLevelId);

        if (existing != null
                && Math.abs(existing.targetStrength - targetStrength) < 0.0001F) {
            return;
        }

        float currentStrength =
                existing != null
                        ? existing.getStrength(now)
                        : 0.0F;

        if (currentStrength <= 0.0F && targetStrength <= 0.0F) {
            TRANSITIONS.remove(subLevelId);
            return;
        }

        TRANSITIONS.put(
                subLevelId,
                new CloakTransition(
                        currentStrength,
                        targetStrength,
                        now,
                        getTransitionDurationSeconds(subLevelId),
                        getTransitionEasing(subLevelId)
                )
        );
    }

    public static float getCloakStrength(UUID subLevelId) {
        CloakTransition transition = TRANSITIONS.get(subLevelId);

        if (transition == null) {
            return 0.0F;
        }

        long now = System.nanoTime();
        float strength = transition.getStrength(now);

        if (transition.targetStrength <= 0.0F
                && transition.isFinished(now)) {
            TRANSITIONS.remove(subLevelId, transition);

            // If the server no longer has a state for this sublevel, a future
            // sync will not reference it. Keeping stale visual overrides around
            // is unnecessary once the fade-out has completed.
            if (!SERVER_SUBLEVELS.contains(subLevelId)) {
                CORE_SETTINGS.remove(subLevelId);
                TRANSITION_DURATIONS.remove(subLevelId);
                FULLY_CLOAKED_DISTANCES.remove(subLevelId);
                GROUP_IDS.remove(subLevelId);
            }

            return 0.0F;
        }

        return strength;
    }

    public static CloakRenderMode getRenderMode(UUID subLevelId) {
        CloakingCoreSettings settings = CORE_SETTINGS.get(subLevelId);

        return settings != null
                ? settings.renderMode()
                : CloakRenderMode.DITHER;
    }

    public static CloakRenderMode getRenderMode(ClientSubLevel subLevel) {
        return getRenderMode(subLevel.getUniqueId());
    }

    public static CloakRenderMode getEntityRenderMode(Entity entity) {
        return getEntityCloakVisual(entity).renderMode();
    }

    public static float getTransitionDurationSeconds(UUID subLevelId) {
        return TRANSITION_DURATIONS.getOrDefault(
                subLevelId,
                SERVER_SETTINGS.transitionDurationSeconds()
        );
    }

    public static CloakEasing getTransitionEasing(UUID subLevelId) {
        return SERVER_SETTINGS.transitionEasing();
    }

    public static float getViewerCloakStrength(UUID subLevelId) {
        long now = System.nanoTime();
        SubLevel viewerSubLevel = getViewerSubLevel();

        updateViewerVisibilityState(viewerSubLevel, now);

        float baseStrength = getCloakStrength(subLevelId);
        if (baseStrength <= 0.0F) {
            return 0.0F;
        }

        UUID key = visibilityKey(subLevelId);

        // UUID-only callers do not have a ClientSubLevel from which to compute
        // proximity. They still honour the leave freeze so no render path can
        // snap back to full cloak during the grace period.
        LeaveVisibilityTransition leaving = LEAVE_VISIBILITY.get(key);
        if (leaving != null && !key.equals(lastViewerSubLevelId)) {
            float factor = leaving.getStrength(now, 1.0F);
            LAST_EFFECTIVE_VIEWER_STRENGTH.put(key, factor);

            if (leaving.isFinished(now)) {
                LEAVE_VISIBILITY.remove(key, leaving);
            }

            return clamp(baseStrength * factor);
        }

        if (!visibleWhileAboard(subLevelId)) {
            return baseStrength;
        }

        float viewerStrength = getViewerVisibilityStrength(subLevelId, now);
        LAST_EFFECTIVE_VIEWER_STRENGTH.put(key, viewerStrength);
        return clamp(baseStrength * viewerStrength);
    }

    public static float getViewerCloakStrength(ClientSubLevel subLevel) {
        UUID subLevelId = subLevel.getUniqueId();
        long now = System.nanoTime();
        SubLevel viewerSubLevel = getViewerSubLevel();

        updateViewerVisibilityState(viewerSubLevel, now);

        float baseStrength = getCloakStrength(subLevelId);
        if (baseStrength <= 0.0F) {
            return 0.0F;
        }

        UUID key = visibilityKey(subLevelId);
        float distanceStrength = getDistanceCloakStrength(subLevel);
        float viewerStrength = visibleWhileAboard(subLevelId)
                ? getViewerVisibilityStrength(subLevelId, now)
                : 1.0F;
        float normalEffectiveStrength = clamp(
                distanceStrength * viewerStrength
        );

        /*
         * Leaving a sublevel must freeze the exact visibility that was on
         * screen.  In the old path only the aboard multiplier was delayed; the
         * proximity multiplier began changing immediately as the player jumped
         * away, which made the ship partially cloak during the grace period.
         *
         * Hold the complete effective multiplier through Leave Grace, then
         * blend from that frozen value to the normal off-board proximity value
         * over Leave Fade.
         */
        LeaveVisibilityTransition leaving = LEAVE_VISIBILITY.get(key);
        float effectiveStrength;

        if (leaving != null && !key.equals(lastViewerSubLevelId)) {
            effectiveStrength = leaving.getStrength(
                    now,
                    distanceStrength
            );

            if (leaving.isFinished(now)) {
                LEAVE_VISIBILITY.remove(key, leaving);
                effectiveStrength = distanceStrength;
            }
        } else {
            effectiveStrength = normalEffectiveStrength;
        }

        effectiveStrength = clamp(effectiveStrength);
        LAST_EFFECTIVE_VIEWER_STRENGTH.put(key, effectiveStrength);

        return clamp(baseStrength * effectiveStrength);
    }

    /**
     * Detects viewer boarding/leaving.
     *
     * Boarding: current viewer factor -> 0 over Aboard Fade.
     * Leaving: freeze the exact rendered factor through Leave Grace, then
     * return toward normal off-board visibility over Leave Fade.
     */
    private static void updateViewerVisibilityState(
            SubLevel viewerSubLevel,
            long now
    ) {
        UUID currentViewerSubLevelId =
                viewerSubLevel != null
                        ? visibilityKey(viewerSubLevel.getUniqueId())
                        : null;

        if (!SERVER_SETTINGS.visibleWhileAboard()) {
            VIEWER_VISIBILITY.clear();
            LEAVE_VISIBILITY.clear();
            LAST_EFFECTIVE_VIEWER_STRENGTH.clear();
            lastViewerSubLevelId = currentViewerSubLevelId;
            return;
        }

        if (java.util.Objects.equals(
                currentViewerSubLevelId,
                lastViewerSubLevelId
        )) {
            return;
        }

        if (lastViewerSubLevelId != null) {
            UUID leftGroupId = lastViewerSubLevelId;

            float frozenEffectiveStrength =
                    LAST_EFFECTIVE_VIEWER_STRENGTH.getOrDefault(
                            leftGroupId,
                            getViewerVisibilityStrength(leftGroupId, now)
                    );

            LEAVE_VISIBILITY.put(
                    leftGroupId,
                    new LeaveVisibilityTransition(
                            frozenEffectiveStrength,
                            now,
                            leaveGraceSeconds(leftGroupId),
                            leaveFadeSeconds(leftGroupId)
                    )
            );

            // The dedicated leave transition now owns this group's return to
            // normal visibility.  Removing the aboard transition means that
            // after Leave Fade completes the default multiplier is cleanly 1.
            VIEWER_VISIBILITY.remove(leftGroupId);
        }

        if (currentViewerSubLevelId != null) {
            LeaveVisibilityTransition interruptedLeave =
                    LEAVE_VISIBILITY.remove(currentViewerSubLevelId);

            float currentStrength;
            if (interruptedLeave != null) {
                currentStrength = interruptedLeave.getStrength(now, 1.0F);
            } else {
                currentStrength = getViewerVisibilityStrength(
                        currentViewerSubLevelId,
                        now
                );
            }

            VIEWER_VISIBILITY.put(
                    currentViewerSubLevelId,
                    new ViewerVisibilityTransition(
                            currentStrength,
                            0.0F,
                            now,
                            0.0F,
                            aboardFadeSeconds(currentViewerSubLevelId)
                    )
            );
        }

        lastViewerSubLevelId = currentViewerSubLevelId;
    }

    /**
     * Returns the viewer-specific aboard cloak multiplier for a sublevel.
     *
     * 1 = normal/full cloak strength, 0 = fully revealed.
     */
    private static float getViewerVisibilityStrength(
            UUID subLevelId,
            long now
    ) {
        UUID visibilityKey = visibilityKey(subLevelId);
        ViewerVisibilityTransition transition =
                VIEWER_VISIBILITY.get(visibilityKey);

        if (transition == null) {
            return 1.0F;
        }

        float strength = transition.getStrength(now);

        // Boarding transitions finish at zero and stay there until the player
        // leaves.  Any future transition back to one is handled by the separate
        // effective leave transition above.
        if (transition.targetStrength >= 1.0F
                && transition.isFinished(now)) {
            VIEWER_VISIBILITY.remove(visibilityKey, transition);
            return 1.0F;
        }

        return strength;
    }

    private static float getDistanceCloakStrength(
            ClientSubLevel subLevel
    ) {
        UUID subLevelId = subLevel.getUniqueId();

        if (!proximityRevealEnabled(subLevelId)) {
            return 1.0F;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null) {
            return 1.0F;
        }

        return getDistanceCloakStrength(
                subLevelId,
                subLevel,
                minecraft.player.getUUID(),
                minecraft.player.getX(),
                minecraft.player.getY(),
                minecraft.player.getZ()
        );
    }

    /**
     * Returns the proximity portion of the cloak for an arbitrary subject.
     *
     * 1 = far enough away that the ship keeps its normal cloak.
     * 0 = close enough that the ship is fully revealed to that subject.
     */
    private static float getDistanceCloakStrength(
            UUID subLevelId,
            ClientSubLevel fallbackSubLevel,
            UUID subjectId,
            double x,
            double y,
            double z
    ) {
        if (!proximityRevealEnabled(subLevelId)) {
            return 1.0F;
        }

        double fullyVisibleDistance = fullyVisibleDistance(subLevelId);
        double fullyCloakedDistance = fullyCloakedDistance(subLevelId);

        double distance = distanceToCloakGroupBlocks(
                subLevelId,
                fallbackSubLevel,
                subjectId,
                x,
                y,
                z,
                fullyCloakedDistance
        );

        return cloakMultiplierForDistance(
                distance,
                fullyVisibleDistance,
                fullyCloakedDistance
        );
    }

    private static float cloakMultiplierForDistance(
            double distance,
            double fullyVisibleDistance,
            double fullyCloakedDistance
    ) {
        if (distance <= fullyVisibleDistance) {
            return 0.0F;
        }

        if (fullyCloakedDistance <= fullyVisibleDistance) {
            return 1.0F;
        }

        if (distance >= fullyCloakedDistance) {
            return 1.0F;
        }

        float progress = (float) (
                (distance - fullyVisibleDistance)
                        / (fullyCloakedDistance - fullyVisibleDistance)
        );

        progress = clamp(progress);
        return progress * progress * (3.0F - 2.0F * progress);
    }

    /**
     * Hybrid distance test.  The sublevel AABB is kept as the broad phase, but
     * once the subject is inside reveal range the final distance is measured
     * against actual non-air block cubes in sublevel-local space.
     */
    private static double distanceToCloakGroupBlocks(
            UUID subLevelId,
            ClientSubLevel fallbackSubLevel,
            UUID subjectId,
            double x,
            double y,
            double z,
            double maxDistance
    ) {
        return proximityDistanceMode(subLevelId) == CloakDistanceMode.BOUNDING_BOX
                ? distanceToCloakGroupBounds(
                        subLevelId,
                        fallbackSubLevel,
                        x,
                        y,
                        z,
                        maxDistance
                )
                : distanceToCloakGroupClosestFace(
                        subLevelId,
                        fallbackSubLevel,
                        subjectId,
                        x,
                        y,
                        z,
                        maxDistance
                );
    }

    private static double distanceToCloakGroupBounds(
            UUID subLevelId,
            ClientSubLevel fallbackSubLevel,
            double x,
            double y,
            double z,
            double maxDistance
    ) {
        UUID groupId = visibilityKey(subLevelId);
        Minecraft minecraft = Minecraft.getInstance();
        SubLevelContainer container = minecraft.level != null
                ? SubLevelContainer.getContainer(minecraft.level)
                : null;

        Set<UUID> members = GROUP_MEMBERS.get(groupId);
        double best = Math.max(0.0, maxDistance);
        boolean testedAny = false;

        if (members != null && container != null) {
            for (UUID memberId : members) {
                SubLevel member = container.getSubLevel(memberId);
                if (!(member instanceof ClientSubLevel clientSubLevel)
                        || clientSubLevel.isRemoved()) {
                    continue;
                }

                testedAny = true;
                best = Math.min(
                        best,
                        distanceToBounds(x, y, z, clientSubLevel.boundingBox())
                );

                if (best <= 0.0) {
                    break;
                }
            }
        }

        if (!testedAny
                && fallbackSubLevel != null
                && !fallbackSubLevel.isRemoved()) {
            best = Math.min(
                    best,
                    distanceToBounds(x, y, z, fallbackSubLevel.boundingBox())
            );
        }

        return best;
    }

    private static double distanceToCloakGroupClosestFace(
            UUID subLevelId,
            ClientSubLevel fallbackSubLevel,
            UUID subjectId,
            double x,
            double y,
            double z,
            double maxDistance
    ) {
        UUID groupId = visibilityKey(subLevelId);
        DistanceCacheKey cacheKey = new DistanceCacheKey(groupId, subjectId);
        long now = System.nanoTime();

        DistanceSample cached = BLOCK_DISTANCE_CACHE.get(cacheKey);
        if (cached != null
                && now - cached.sampleTimeNanos() <= DISTANCE_CACHE_NANOS
                && Math.abs(cached.x() - x) <= 1.0E-4
                && Math.abs(cached.y() - y) <= 1.0E-4
                && Math.abs(cached.z() - z) <= 1.0E-4
                && Math.abs(cached.maxDistance() - maxDistance) <= 1.0E-4) {
            return cached.distance();
        }

        Minecraft minecraft = Minecraft.getInstance();
        SubLevelContainer container = minecraft.level != null
                ? SubLevelContainer.getContainer(minecraft.level)
                : null;

        Set<UUID> members = GROUP_MEMBERS.get(groupId);
        double best = Math.max(0.0, maxDistance);
        boolean testedAny = false;

        if (members != null && container != null) {
            for (UUID memberId : members) {
                SubLevel member = container.getSubLevel(memberId);
                if (!(member instanceof ClientSubLevel clientSubLevel)
                        || clientSubLevel.isRemoved()) {
                    continue;
                }

                double broadPhase = distanceToBounds(
                        x, y, z, clientSubLevel.boundingBox()
                );

                if (broadPhase >= best) {
                    continue;
                }

                testedAny = true;

                if (broadPhase <= HULL_POINT_EPSILON
                        && isInsideCachedConvexEnvelope(
                                clientSubLevel, x, y, z
                        )) {
                    best = 0.0;
                    break;
                }

                best = Math.min(
                        best,
                        distanceToNearestRealBlock(
                                clientSubLevel,
                                x, y, z,
                                best
                        )
                );

                if (best <= 0.0) {
                    break;
                }
            }
        }

        if (!testedAny
                && fallbackSubLevel != null
                && !fallbackSubLevel.isRemoved()) {
            double broadPhase = distanceToBounds(
                    x, y, z, fallbackSubLevel.boundingBox()
            );

            if (broadPhase < best) {
                if (broadPhase <= HULL_POINT_EPSILON
                        && isInsideCachedConvexEnvelope(
                                fallbackSubLevel, x, y, z
                        )) {
                    best = 0.0;
                } else {
                    best = Math.min(
                            best,
                            distanceToNearestRealBlock(
                                    fallbackSubLevel,
                                    x, y, z,
                                    best
                            )
                    );
                }
            }
        }

        DistanceSample sample = new DistanceSample(
                x, y, z, maxDistance, best, now
        );
        BLOCK_DISTANCE_CACHE.put(cacheKey, sample);
        return best;
    }

    /**
     * Tests whether the world-space point lies inside the cached convex
     * envelope of this sublevel. The point is transformed into the same local
     * plot coordinates used by Sable's blocks before the cheap plane test.
     */
    private static boolean isInsideCachedConvexEnvelope(
            ClientSubLevel subLevel,
            double worldX,
            double worldY,
            double worldZ
    ) {
        var plotBounds = subLevel.getPlot().getBoundingBox();
        if (plotBounds == null) {
            return false;
        }

        int minX = plotBounds.minX();
        int minY = plotBounds.minY();
        int minZ = plotBounds.minZ();
        int maxX = plotBounds.maxX();
        int maxY = plotBounds.maxY();
        int maxZ = plotBounds.maxZ();

        if (maxX < minX || maxY < minY || maxZ < minZ) {
            return false;
        }

        Vector3d local = new Vector3d(worldX, worldY, worldZ);
        subLevel.renderPose().transformPositionInverse(local);

        // The swept/global AABB can contain large empty corners on a rotated
        // ship. Avoid building a hull at all unless the point is inside the
        // local plot bounds first.
        if (local.x < minX - HULL_POINT_EPSILON
                || local.x > maxX + 1.0 + HULL_POINT_EPSILON
                || local.y < minY - HULL_POINT_EPSILON
                || local.y > maxY + 1.0 + HULL_POINT_EPSILON
                || local.z < minZ - HULL_POINT_EPSILON
                || local.z > maxZ + 1.0 + HULL_POINT_EPSILON) {
            return false;
        }

        HullBoundsKey boundsKey = new HullBoundsKey(
                minX, minY, minZ, maxX, maxY, maxZ
        );
        UUID subLevelId = subLevel.getUniqueId();
        HullEnvelope envelope = HULL_ENVELOPES.get(subLevelId);

        if (envelope == null || !envelope.bounds().equals(boundsKey)) {
            envelope = buildHullEnvelope(subLevel, boundsKey);
            if (envelope == null) {
                HULL_ENVELOPES.remove(subLevelId);
                return false;
            }
            HULL_ENVELOPES.put(subLevelId, envelope);
        }

        return envelope.contains(local.x, local.y, local.z);
    }

    private static HullEnvelope buildHullEnvelope(
            ClientSubLevel subLevel,
            HullBoundsKey bounds
    ) {
        long sizeX = (long) bounds.maxX() - bounds.minX() + 1L;
        long sizeY = (long) bounds.maxY() - bounds.minY() + 1L;
        long sizeZ = (long) bounds.maxZ() - bounds.minZ() + 1L;

        if (sizeX <= 0L || sizeY <= 0L || sizeZ <= 0L) {
            return null;
        }

        long volume;
        try {
            volume = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
        } catch (ArithmeticException ignored) {
            volume = Long.MAX_VALUE;
        }

        /*
         * Very large plots get a safe AABB envelope instead of a multi-million
         * block scan. This preserves the important "inside the overall ship"
         * behaviour without risking a giant first-frame hitch.
         */
        if (volume > MAX_HULL_SCAN_VOLUME) {
            return HullEnvelope.fromBounds(bounds);
        }

        int directionCount = HULL_DIRECTIONS.size();
        double[] minimum = new double[directionCount];
        double[] maximum = new double[directionCount];
        java.util.Arrays.fill(minimum, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(maximum, Double.NEGATIVE_INFINITY);

        Level level = subLevel.getLevel();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int occupied = 0;

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    pos.set(x, y, z);
                    if (level.getBlockState(pos).isAir()) {
                        continue;
                    }

                    occupied++;
                    for (int i = 0; i < directionCount; i++) {
                        HullDirection direction = HULL_DIRECTIONS.get(i);

                        double minDot = direction.dx() * (direction.dx() >= 0 ? x : x + 1.0)
                                + direction.dy() * (direction.dy() >= 0 ? y : y + 1.0)
                                + direction.dz() * (direction.dz() >= 0 ? z : z + 1.0);
                        double maxDot = direction.dx() * (direction.dx() >= 0 ? x + 1.0 : x)
                                + direction.dy() * (direction.dy() >= 0 ? y + 1.0 : y)
                                + direction.dz() * (direction.dz() >= 0 ? z + 1.0 : z);

                        if (minDot < minimum[i]) {
                            minimum[i] = minDot;
                        }
                        if (maxDot > maximum[i]) {
                            maximum[i] = maxDot;
                        }
                    }
                }
            }
        }

        if (occupied == 0) {
            return null;
        }

        return new HullEnvelope(bounds, minimum, maximum);
    }

    private static List<HullDirection> createHullDirections() {
        Set<HullDirection> unique = new HashSet<>();

        for (int x = -HULL_DIRECTION_RANGE; x <= HULL_DIRECTION_RANGE; x++) {
            for (int y = -HULL_DIRECTION_RANGE; y <= HULL_DIRECTION_RANGE; y++) {
                for (int z = -HULL_DIRECTION_RANGE; z <= HULL_DIRECTION_RANGE; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }

                    int divisor = gcd(gcd(Math.abs(x), Math.abs(y)), Math.abs(z));
                    int dx = x / divisor;
                    int dy = y / divisor;
                    int dz = z / divisor;

                    // Store only one of n / -n; each direction already keeps
                    // both its minimum and maximum support plane.
                    if (dx < 0
                            || (dx == 0 && dy < 0)
                            || (dx == 0 && dy == 0 && dz < 0)) {
                        dx = -dx;
                        dy = -dy;
                        dz = -dz;
                    }

                    unique.add(new HullDirection(dx, dy, dz));
                }
            }
        }

        return List.copyOf(new ArrayList<>(unique));
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int next = a % b;
            a = b;
            b = next;
        }
        return Math.max(1, a);
    }

    /**
     * Searches outward from the transformed subject position until the closest
     * non-air block cube is known.  Because renderPose is a rigid transform,
     * distances in this local coordinate system are the same as world-space
     * distances.
     */
    private static double distanceToNearestRealBlock(
            ClientSubLevel subLevel,
            double worldX,
            double worldY,
            double worldZ,
            double maxDistance
    ) {
        if (maxDistance <= 0.0) {
            return 0.0;
        }

        double broadPhase = distanceToBounds(
                worldX, worldY, worldZ, subLevel.boundingBox()
        );
        if (broadPhase >= maxDistance) {
            return maxDistance;
        }

        Vector3d local = new Vector3d(worldX, worldY, worldZ);
        subLevel.renderPose().transformPositionInverse(local);

        Level level = subLevel.getLevel();
        int centerX = floorToInt(local.x);
        int centerY = floorToInt(local.y);
        int centerZ = floorToInt(local.z);
        int maxShell = (int) Math.ceil(maxDistance) + 1;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double bestSquared = maxDistance * maxDistance;
        boolean found = false;

        for (int shell = 0; shell <= maxShell; shell++) {
            for (int dx = -shell; dx <= shell; dx++) {
                for (int dy = -shell; dy <= shell; dy++) {
                    for (int dz = -shell; dz <= shell; dz++) {
                        if (shell > 0
                                && Math.abs(dx) != shell
                                && Math.abs(dy) != shell
                                && Math.abs(dz) != shell) {
                            continue;
                        }

                        int blockX = centerX + dx;
                        int blockY = centerY + dy;
                        int blockZ = centerZ + dz;

                        double candidateSquared = distanceToUnitBlockSquared(
                                local.x, local.y, local.z,
                                blockX, blockY, blockZ
                        );

                        if (candidateSquared > bestSquared) {
                            continue;
                        }

                        pos.set(blockX, blockY, blockZ);
                        if (level.getBlockState(pos).isAir()) {
                            continue;
                        }

                        found = true;
                        bestSquared = candidateSquared;

                        if (bestSquared <= 1.0E-8) {
                            return 0.0;
                        }
                    }
                }
            }

            /*
             * Every cube in the next Chebyshev shell is at least `shell`
             * blocks away along one axis. Once the best hit is no farther than
             * that, no later shell can beat it.
             */
            if (found && bestSquared <= (double) shell * shell) {
                break;
            }
        }

        return found ? Math.sqrt(bestSquared) : maxDistance;
    }

    private static double distanceToUnitBlockSquared(
            double x,
            double y,
            double z,
            int blockX,
            int blockY,
            int blockZ
    ) {
        double dx = axisDistance(x, blockX, blockX + 1.0);
        double dy = axisDistance(y, blockY, blockY + 1.0);
        double dz = axisDistance(z, blockZ, blockZ + 1.0);
        return dx * dx + dy * dy + dz * dz;
    }

    private static int floorToInt(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private static UUID visibilityKey(UUID subLevelId) {
        return GROUP_IDS.getOrDefault(subLevelId, subLevelId);
    }

    private static double distanceToBounds(
            double x,
            double y,
            double z,
            BoundingBox3dc bounds
    ) {
        double dx = axisDistance(x, bounds.minX(), bounds.maxX());
        double dy = axisDistance(y, bounds.minY(), bounds.maxY());
        double dz = axisDistance(z, bounds.minZ(), bounds.maxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean visibleWhileAboard(UUID subLevelId) {
        return SERVER_SETTINGS.visibleWhileAboard();
    }

    private static float aboardFadeSeconds(UUID subLevelId) {
        return SERVER_SETTINGS.aboardFadeSeconds();
    }

    private static float leaveGraceSeconds(UUID subLevelId) {
        return SERVER_SETTINGS.leaveGraceSeconds();
    }

    private static float leaveFadeSeconds(UUID subLevelId) {
        return SERVER_SETTINGS.leaveFadeSeconds();
    }

    private static boolean proximityRevealEnabled(UUID subLevelId) {
        return SERVER_SETTINGS.proximityRevealEnabled();
    }

    public static net.fireboy.aerocloakingcore.cloak.RopeCloakBehavior getRopeCloakBehavior() {
        return SERVER_SETTINGS.ropeCloakBehavior();
    }

    private static CloakDistanceMode proximityDistanceMode(UUID subLevelId) {
        return SERVER_SETTINGS.cloakDistanceMode();
    }

    private static double fullyVisibleDistance(UUID subLevelId) {
        return SERVER_SETTINGS.fullyVisibleDistance();
    }

    private static double fullyCloakedDistance(UUID subLevelId) {
        return FULLY_CLOAKED_DISTANCES.getOrDefault(
                subLevelId,
                defaultFullyCloakedDistance()
        );
    }

    private static double defaultFullyCloakedDistance() {
        return SERVER_SETTINGS.fullyVisibleDistance()
                + CloakingServerSettings.BASE_REVEAL_GAP_BLOCKS
                * SERVER_SETTINGS.revealDistanceMultiplier();
    }

    private static double axisDistance(
            double value,
            double min,
            double max
    ) {
        if (value < min) {
            return min - value;
        }

        if (value > max) {
            return value - max;
        }

        return 0.0;
    }

    public static float getRenderAlpha(UUID subLevelId) {
        return 1.0F - getViewerCloakStrength(subLevelId);
    }

    public static float getRenderAlpha(ClientSubLevel subLevel) {
        return 1.0F - getViewerCloakStrength(subLevel);
    }

    public static boolean shouldHideSubLevel(UUID subLevelId) {
        return getViewerCloakStrength(subLevelId)
                >= FULL_CLOAK_THRESHOLD;
    }

    public static boolean shouldHideSubLevel(ClientSubLevel subLevel) {
        return getViewerCloakStrength(subLevel)
                >= FULL_CLOAK_THRESHOLD;
    }

    public static boolean isCloaked(UUID subLevelId) {
        return getCloakStrength(subLevelId) > 0.0F;
    }

    public static SubLevel getViewerSubLevel() {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null) {
            return null;
        }

        return Sable.HELPER.getTrackingOrVehicleSubLevel(
                minecraft.player
        );
    }

    public static ClientSubLevel getEntityClientSubLevel(Entity entity) {
        SubLevel subLevel = Sable.HELPER.getContaining(entity);

        if (!(subLevel instanceof ClientSubLevel)) {
            subLevel = Sable.HELPER.getTrackingOrVehicleSubLevel(entity);
        }

        return subLevel instanceof ClientSubLevel clientSubLevel
                ? clientSubLevel
                : null;
    }

    public static boolean usesEntityOcclusionMask() {
        return SERVER_SETTINGS.entityCloakBehavior()
                == EntityCloakBehavior.OCCLUDED_ONLY;
    }

    /**
     * True when this sublevel should contribute invisible geometry to the
     * temporary depth buffer used while normal entities are rendered.
     */
    public static boolean shouldWriteEntityOcclusionDepth(
            ClientSubLevel subLevel
    ) {
        return subLevel != null
                && usesEntityOcclusionMask()
                && getViewerCloakStrength(subLevel) > 0.001F;
    }

    public static float getEntityViewerCloakStrength(Entity entity) {
        return getEntityCloakVisual(entity).cloakStrength();
    }

    /**
     * Computes entity cloaking in two stages.
     *
     * Entities already belonging to a sublevel use exactly the same
     * viewer-specific cloak amount as that ship. Entities that are only
     * approaching a cloak group keep the proximity-transfer behaviour: as the
     * target begins revealing the hidden ship to itself, it progressively
     * cloaks to outside observers.
     */
    private static EntityCloakVisual getEntityCloakVisual(Entity entity) {
        if (entity == null) {
            return EntityCloakVisual.VISIBLE;
        }

        /*
         * Never cloak the local player's own model. In third person the local
         * player is rendered through the same entity path as remote players,
         * but the transferred cloak is only meant to affect what OTHER
         * observers see. From your own client, your body stays fully opaque.
         */
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null
                && entity.getUUID().equals(minecraft.player.getUUID())) {
            return EntityCloakVisual.VISIBLE;
        }

        EntityCloakBehavior entityBehavior = SERVER_SETTINGS.entityCloakBehavior();
        if (entityBehavior == EntityCloakBehavior.ALWAYS_VISIBLE) {
            return EntityCloakVisual.VISIBLE;
        }

        if (entityBehavior == EntityCloakBehavior.OCCLUDED_ONLY) {
            /*
             * Render-mask mode never changes the entity itself. The entity is
             * rendered at full opacity and cloaked sublevel terrain writes a
             * temporary entity-only depth mask instead. That gives true
             * per-pixel occlusion at windows, railings, doorways and corners.
             */
            return EntityCloakVisual.VISIBLE;
        }

        long now = System.nanoTime();
        UUID entityId = entity.getUUID();

        EntityVisualSample cached = ENTITY_VISUAL_CACHE.get(entityId);
        if (cached != null
                && now - cached.sampleTimeNanos() <= ENTITY_VISUAL_CACHE_NANOS
                && Math.abs(cached.x() - entity.getX()) <= 1.0E-4
                && Math.abs(cached.y() - entity.getY()) <= 1.0E-4
                && Math.abs(cached.z() - entity.getZ()) <= 1.0E-4) {
            return cached.visual();
        }

        ClientSubLevel entitySubLevel = getEntityClientSubLevel(entity);

        /*
         * Once an entity actually belongs to a sublevel, it is part of that
         * ship's visible silhouette. Render it with EXACTLY the same
         * viewer-specific cloak amount as the ship itself.
         *
         * This deliberately uses the local observer's ship strength here:
         *   ship 100% cloaked -> onboard entity 100% cloaked
         *   ship  50% cloaked -> onboard entity  50% cloaked
         *   ship fully revealed -> onboard entity fully visible
         *
         * getViewerCloakStrength(ClientSubLevel) already includes proximity,
         * aboard reveal, leave grace/fade, connected groups, and the current
         * cloak transition, so the entity and hull stay visually locked.
         */
        if (entitySubLevel != null) {
            float shipStrength = getViewerCloakStrength(entitySubLevel);

            EntityCloakVisual visual = shipStrength <= 0.001F
                    ? EntityCloakVisual.VISIBLE
                    : new EntityCloakVisual(
                            shipStrength,
                            getRenderMode(entitySubLevel)
                    );

            ENTITY_VISUAL_CACHE.put(
                    entityId,
                    new EntityVisualSample(
                            entity.getX(),
                            entity.getY(),
                            entity.getZ(),
                            now,
                            visual
                    )
            );

            return visual;
        }

        UUID entityAboardGroup = null;

        EntityVisibilityState state = ENTITY_VISIBILITY.computeIfAbsent(
                entityId,
                ignored -> new EntityVisibilityState()
        );
        updateEntityVisibilityState(state, entityAboardGroup, now);

        float bestStrength = 0.0F;
        CloakRenderMode bestMode = CloakRenderMode.DITHER;

        SubLevelContainer container = minecraft.level != null
                ? SubLevelContainer.getContainer(minecraft.level)
                : null;

        for (Map.Entry<UUID, Set<UUID>> groupEntry : GROUP_MEMBERS.entrySet()) {
            UUID groupId = groupEntry.getKey();
            Set<UUID> memberIds = groupEntry.getValue();

            UUID representativeId = null;
            ClientSubLevel representativeSubLevel = null;
            float baseStrength = 0.0F;
            CloakRenderMode renderMode = CloakRenderMode.DITHER;

            for (UUID memberId : memberIds) {
                float memberStrength = getCloakStrength(memberId);

                if (representativeId == null || memberStrength > baseStrength) {
                    representativeId = memberId;
                    baseStrength = memberStrength;
                    renderMode = getRenderMode(memberId);
                }

                if (representativeSubLevel == null && container != null) {
                    SubLevel member = container.getSubLevel(memberId);
                    if (member instanceof ClientSubLevel clientSubLevel
                            && !clientSubLevel.isRemoved()) {
                        representativeSubLevel = clientSubLevel;
                    }
                }
            }

            if (representativeId == null || baseStrength <= 0.001F) {
                continue;
            }

            float distanceStrength = getDistanceCloakStrength(
                    representativeId,
                    representativeSubLevel,
                    entityId,
                    entity.getX(),
                    entity.getY(),
                    entity.getZ()
            );

            float targetShipMultiplier = getEntityEffectiveShipMultiplier(
                    state,
                    groupId,
                    representativeId,
                    distanceStrength,
                    now
            );

            /*
             * The target's proximity decides how much cloak is transferred
             * from the ship onto that entity.  The observer's proximity must
             * then decide how much of that transferred cloak they can still
             * see.  Otherwise an entity that is close to the ship stays fully
             * invisible until the observer is formally assigned to the
             * sublevel, even while the observer has already revealed the hull
             * by walking into its proximity range.
             *
             * Using the observer-visible ship strength gives the desired
             * relationship directly:
             *   target far, observer far   -> no transferred entity cloak
             *   target close, observer far -> entity can be fully cloaked
             *   target close, observer 50% -> entity is 50% cloaked
             *   target close, observer near/aboard -> entity is visible
             *
             * This also avoids using the raw sublevel bounding box as an
             * "inside ship" test. getViewerCloakStrength(ClientSubLevel) uses
             * the closest-real-block distance path before aboard state takes
             * over.
             */
            float observerVisibleShipStrength = representativeSubLevel != null
                    ? getViewerCloakStrength(representativeSubLevel)
                    : baseStrength;

            float transferredStrength = clamp(
                    observerVisibleShipStrength
                            * (1.0F - targetShipMultiplier)
            );

            if (transferredStrength > bestStrength) {
                bestStrength = transferredStrength;
                bestMode = renderMode;
            }
        }

        EntityCloakVisual visual = bestStrength <= 0.001F
                ? EntityCloakVisual.VISIBLE
                : new EntityCloakVisual(bestStrength, bestMode);

        ENTITY_VISUAL_CACHE.put(
                entityId,
                new EntityVisualSample(
                        entity.getX(),
                        entity.getY(),
                        entity.getZ(),
                        now,
                        visual
                )
        );

        pruneOldEntityVisibilityStates(now);
        return visual;
    }

    /**
     * Entity mode where cloak never transfers directly onto the entity.
     * Instead, actual cloaked sublevel blocks act as line-of-sight occluders.
     *
     * Several points are sampled across the entity. If even one point has a
     * clear path from the camera, the entity stays visible. This makes a player
     * on an exposed deck visible while a player completely inside a cloaked
     * room is hidden, without needing a special depth-buffer pass.
     */
    private static EntityCloakVisual getOccludedOnlyEntityVisual(
            Entity entity,
            Minecraft minecraft
    ) {
        if (minecraft.level == null || minecraft.gameRenderer == null) {
            return EntityCloakVisual.VISIBLE;
        }

        var camera = minecraft.gameRenderer.getMainCamera();
        if (camera == null) {
            return EntityCloakVisual.VISIBLE;
        }

        var cameraPosition = camera.getPosition();
        double cameraX = cameraPosition.x;
        double cameraY = cameraPosition.y;
        double cameraZ = cameraPosition.z;

        List<EntityOccluder> occluders = collectEntityOccluders(minecraft);
        if (occluders.isEmpty()) {
            return EntityCloakVisual.VISIBLE;
        }

        double width = Math.max(0.2, entity.getBbWidth());
        double height = Math.max(0.2, entity.getBbHeight());
        double side = width * 0.35;
        double baseX = entity.getX();
        double baseY = entity.getY();
        double baseZ = entity.getZ();
        double torsoY = baseY + height * 0.58;

        double[][] samples = {
                {baseX, baseY + height * 0.90, baseZ},
                {baseX, torsoY, baseZ},
                {baseX, baseY + height * 0.14, baseZ},
                {baseX + side, torsoY, baseZ},
                {baseX - side, torsoY, baseZ},
                {baseX, torsoY, baseZ + side},
                {baseX, torsoY, baseZ - side}
        };

        float weakestBlockedStrength = 1.0F;
        CloakRenderMode weakestBlockedMode = CloakRenderMode.DITHER;

        for (double[] sample : samples) {
            EntityCloakVisual blockedBy = getCloakedOccluderForRay(
                    occluders,
                    cameraX,
                    cameraY,
                    cameraZ,
                    sample[0],
                    sample[1],
                    sample[2]
            );

            // One exposed sample is enough to consider the entity exposed.
            if (blockedBy.cloakStrength() <= 0.001F) {
                return EntityCloakVisual.VISIBLE;
            }

            if (blockedBy.cloakStrength() < weakestBlockedStrength) {
                weakestBlockedStrength = blockedBy.cloakStrength();
                weakestBlockedMode = blockedBy.renderMode();
            }
        }

        return weakestBlockedStrength <= 0.001F
                ? EntityCloakVisual.VISIBLE
                : new EntityCloakVisual(
                        clamp(weakestBlockedStrength),
                        weakestBlockedMode
                );
    }

    /**
     * Returns the strongest cloaked sublevel that places a real non-air block
     * between the camera and one sampled entity point.
     */
    private static List<EntityOccluder> collectEntityOccluders(
            Minecraft minecraft
    ) {
        if (minecraft.level == null) {
            return List.of();
        }

        SubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
        if (container == null) {
            return List.of();
        }

        List<EntityOccluder> occluders = new ArrayList<>();

        for (UUID subLevelId : SERVER_SUBLEVELS) {
            SubLevel subLevel = container.getSubLevel(subLevelId);
            if (!(subLevel instanceof ClientSubLevel clientSubLevel)
                    || clientSubLevel.isRemoved()) {
                continue;
            }

            float viewerStrength = getViewerCloakStrength(clientSubLevel);
            if (viewerStrength <= 0.001F) {
                continue;
            }

            occluders.add(
                    new EntityOccluder(
                            clientSubLevel,
                            viewerStrength,
                            getRenderMode(subLevelId)
                    )
            );
        }

        return occluders;
    }

    /**
     * Returns the strongest cloaked sublevel that places a real non-air block
     * between the camera and one sampled entity point.
     */
    private static EntityCloakVisual getCloakedOccluderForRay(
            List<EntityOccluder> occluders,
            double startX,
            double startY,
            double startZ,
            double endX,
            double endY,
            double endZ
    ) {
        float strongest = 0.0F;
        CloakRenderMode strongestMode = CloakRenderMode.DITHER;

        for (EntityOccluder occluder : occluders) {
            if (occluder.cloakStrength() <= strongest) {
                continue;
            }

            ClientSubLevel clientSubLevel = occluder.subLevel();

            // Cheap world-space broad phase before doing any local transforms.
            if (!segmentIntersectsBounds(
                    startX, startY, startZ,
                    endX, endY, endZ,
                    clientSubLevel.boundingBox()
            )) {
                continue;
            }

            if (!rayHitsSubLevelBlock(
                    clientSubLevel,
                    startX, startY, startZ,
                    endX, endY, endZ
            )) {
                continue;
            }

            strongest = occluder.cloakStrength();
            strongestMode = occluder.renderMode();

            if (strongest >= FULL_CLOAK_THRESHOLD) {
                break;
            }
        }

        return strongest <= 0.001F
                ? EntityCloakVisual.VISIBLE
                : new EntityCloakVisual(strongest, strongestMode);
    }

    /**
     * Exact block-grid line test in sublevel-local coordinates. The ray is
     * clipped to the Sable plot bounds first, so a far-away observer does not
     * step through hundreds of unrelated world blocks before reaching a ship.
     */
    private static boolean rayHitsSubLevelBlock(
            ClientSubLevel subLevel,
            double worldStartX,
            double worldStartY,
            double worldStartZ,
            double worldEndX,
            double worldEndY,
            double worldEndZ
    ) {
        var plotBounds = subLevel.getPlot().getBoundingBox();
        if (plotBounds == null) {
            return false;
        }

        int minX = plotBounds.minX();
        int minY = plotBounds.minY();
        int minZ = plotBounds.minZ();
        int maxX = plotBounds.maxX();
        int maxY = plotBounds.maxY();
        int maxZ = plotBounds.maxZ();

        if (maxX < minX || maxY < minY || maxZ < minZ) {
            return false;
        }

        Vector3d start = new Vector3d(worldStartX, worldStartY, worldStartZ);
        Vector3d end = new Vector3d(worldEndX, worldEndY, worldEndZ);
        subLevel.renderPose().transformPositionInverse(start);
        subLevel.renderPose().transformPositionInverse(end);

        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double dz = end.z - start.z;

        double[] interval = clipSegmentToBox(
                start.x, start.y, start.z,
                dx, dy, dz,
                minX, minY, minZ,
                maxX + 1.0, maxY + 1.0, maxZ + 1.0
        );

        if (interval == null) {
            return false;
        }

        double tStart = Math.max(0.0, interval[0]);
        double tEnd = Math.min(1.0, interval[1]);
        if (tEnd < tStart) {
            return false;
        }

        // Nudge inside the clipped volume to avoid boundary ambiguity when the
        // ray enters exactly on an integer block plane.
        double epsilon = 1.0E-7;
        double sampleStartT = Math.min(tEnd, tStart + epsilon);
        double x = start.x + dx * sampleStartT;
        double y = start.y + dy * sampleStartT;
        double z = start.z + dz * sampleStartT;

        int blockX = floorToInt(x);
        int blockY = floorToInt(y);
        int blockZ = floorToInt(z);

        int stepX = Double.compare(dx, 0.0);
        int stepY = Double.compare(dy, 0.0);
        int stepZ = Double.compare(dz, 0.0);

        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);

        double tMaxX = firstVoxelBoundaryT(sampleStartT, x, blockX, dx, stepX);
        double tMaxY = firstVoxelBoundaryT(sampleStartT, y, blockY, dy, stepY);
        double tMaxZ = firstVoxelBoundaryT(sampleStartT, z, blockZ, dz, stepZ);

        double endProbeT = Math.max(tStart, tEnd - epsilon);
        int endBlockX = floorToInt(start.x + dx * endProbeT);
        int endBlockY = floorToInt(start.y + dy * endProbeT);
        int endBlockZ = floorToInt(start.z + dz * endProbeT);

        int maxSteps = Math.abs(endBlockX - blockX)
                + Math.abs(endBlockY - blockY)
                + Math.abs(endBlockZ - blockZ)
                + 4;

        Level level = subLevel.getLevel();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double currentT = sampleStartT;

        for (int steps = 0; steps < maxSteps && currentT <= tEnd + epsilon; steps++) {
            if (blockX >= minX && blockX <= maxX
                    && blockY >= minY && blockY <= maxY
                    && blockZ >= minZ && blockZ <= maxZ) {
                pos.set(blockX, blockY, blockZ);
                if (!level.getBlockState(pos).isAir()) {
                    return true;
                }
            }

            if (blockX == endBlockX
                    && blockY == endBlockY
                    && blockZ == endBlockZ) {
                break;
            }

            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                blockX += stepX;
                currentT = tMaxX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                blockY += stepY;
                currentT = tMaxY;
                tMaxY += tDeltaY;
            } else {
                blockZ += stepZ;
                currentT = tMaxZ;
                tMaxZ += tDeltaZ;
            }
        }

        return false;
    }

    private static double firstVoxelBoundaryT(
            double currentT,
            double coordinate,
            int blockCoordinate,
            double direction,
            int step
    ) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }

        double boundary = step > 0
                ? blockCoordinate + 1.0
                : blockCoordinate;
        return currentT + (boundary - coordinate) / direction;
    }

    private static boolean segmentIntersectsBounds(
            double startX,
            double startY,
            double startZ,
            double endX,
            double endY,
            double endZ,
            BoundingBox3dc bounds
    ) {
        return clipSegmentToBox(
                startX, startY, startZ,
                endX - startX,
                endY - startY,
                endZ - startZ,
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ()
        ) != null;
    }

    /** Returns {entryT, exitT} for a segment p + direction*t, t in [0,1]. */
    private static double[] clipSegmentToBox(
            double startX,
            double startY,
            double startZ,
            double directionX,
            double directionY,
            double directionZ,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ
    ) {
        double tMin = 0.0;
        double tMax = 1.0;
        double[] starts = {startX, startY, startZ};
        double[] directions = {directionX, directionY, directionZ};
        double[] minimums = {minX, minY, minZ};
        double[] maximums = {maxX, maxY, maxZ};

        for (int axis = 0; axis < 3; axis++) {
            double start = starts[axis];
            double direction = directions[axis];
            double minimum = minimums[axis];
            double maximum = maximums[axis];

            if (Math.abs(direction) <= 1.0E-12) {
                if (start < minimum || start > maximum) {
                    return null;
                }
                continue;
            }

            double near = (minimum - start) / direction;
            double far = (maximum - start) / direction;
            if (near > far) {
                double swap = near;
                near = far;
                far = swap;
            }

            tMin = Math.max(tMin, near);
            tMax = Math.min(tMax, far);
            if (tMax < tMin) {
                return null;
            }
        }

        return new double[]{tMin, tMax};
    }

    private static void updateEntityVisibilityState(
            EntityVisibilityState state,
            UUID currentGroupId,
            long now
    ) {
        state.lastTouchedNanos = now;

        if (!SERVER_SETTINGS.visibleWhileAboard()) {
            state.aboardTransitions.clear();
            state.leaveTransitions.clear();
            state.lastEffectiveStrength.clear();
            state.lastGroupId = currentGroupId;
            return;
        }

        if (java.util.Objects.equals(currentGroupId, state.lastGroupId)) {
            return;
        }

        if (state.lastGroupId != null) {
            UUID leftGroupId = state.lastGroupId;
            float frozenEffectiveStrength =
                    state.lastEffectiveStrength.getOrDefault(
                            leftGroupId,
                            0.0F
                    );

            state.leaveTransitions.put(
                    leftGroupId,
                    new LeaveVisibilityTransition(
                            frozenEffectiveStrength,
                            now,
                            leaveGraceSeconds(leftGroupId),
                            leaveFadeSeconds(leftGroupId)
                    )
            );
            state.aboardTransitions.remove(leftGroupId);
        }

        if (currentGroupId != null) {
            LeaveVisibilityTransition interruptedLeave =
                    state.leaveTransitions.remove(currentGroupId);

            float currentStrength = interruptedLeave != null
                    ? interruptedLeave.getStrength(now, 1.0F)
                    : 1.0F;

            state.aboardTransitions.put(
                    currentGroupId,
                    new ViewerVisibilityTransition(
                            currentStrength,
                            0.0F,
                            now,
                            0.0F,
                            aboardFadeSeconds(currentGroupId)
                    )
            );
        }

        state.lastGroupId = currentGroupId;
    }

    private static float getEntityEffectiveShipMultiplier(
            EntityVisibilityState state,
            UUID groupId,
            UUID settingsSubLevelId,
            float distanceStrength,
            long now
    ) {
        float aboardStrength = 1.0F;

        if (visibleWhileAboard(settingsSubLevelId)
                && groupId.equals(state.lastGroupId)) {
            ViewerVisibilityTransition transition =
                    state.aboardTransitions.get(groupId);

            if (transition != null) {
                aboardStrength = transition.getStrength(now);
            } else {
                // If this entity was first observed after it had already
                // boarded, treat the onboard reveal as complete.
                aboardStrength = 0.0F;
            }
        }

        float normalEffectiveStrength = clamp(
                distanceStrength * aboardStrength
        );

        LeaveVisibilityTransition leaving = state.leaveTransitions.get(groupId);
        float effectiveStrength;

        if (leaving != null && !groupId.equals(state.lastGroupId)) {
            effectiveStrength = leaving.getStrength(now, distanceStrength);

            if (leaving.isFinished(now)) {
                state.leaveTransitions.remove(groupId, leaving);
                effectiveStrength = distanceStrength;
            }
        } else {
            effectiveStrength = normalEffectiveStrength;
        }

        effectiveStrength = clamp(effectiveStrength);
        state.lastEffectiveStrength.put(groupId, effectiveStrength);
        return effectiveStrength;
    }

    private static void pruneOldEntityVisibilityStates(long now) {
        if (ENTITY_VISIBILITY.size() < 256) {
            return;
        }

        ENTITY_VISIBILITY.entrySet().removeIf(entry ->
                now - entry.getValue().lastTouchedNanos
                        > ENTITY_STATE_EXPIRY_NANOS
        );
        ENTITY_VISUAL_CACHE.keySet().removeIf(id ->
                !ENTITY_VISIBILITY.containsKey(id)
        );

        if (BLOCK_DISTANCE_CACHE.size() >= 1024) {
            BLOCK_DISTANCE_CACHE.entrySet().removeIf(entry ->
                    now - entry.getValue().sampleTimeNanos()
                            > ENTITY_STATE_EXPIRY_NANOS
            );
        }
    }

    public static boolean shouldHideEntity(Entity entity) {
        return getEntityViewerCloakStrength(entity)
                >= FULL_CLOAK_THRESHOLD;
    }

    public static boolean shouldHidePlayer(Player target) {
        return shouldHideEntity(target);
    }

    /** Global fallback accessor retained for older code paths. */
    public static float getTransitionDurationSeconds() {
        return SERVER_SETTINGS.transitionDurationSeconds();
    }

    /** Global fallback accessor retained for older code paths. */
    public static CloakEasing getTransitionEasing() {
        return SERVER_SETTINGS.transitionEasing();
    }

    private static float clamp(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private record HullDirection(int dx, int dy, int dz) {
        private double epsilon() {
            return HULL_POINT_EPSILON * Math.sqrt(
                    (double) dx * dx + (double) dy * dy + (double) dz * dz
            );
        }
    }

    private record HullBoundsKey(
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ
    ) {
    }

    private record HullEnvelope(
            HullBoundsKey bounds,
            double[] minimum,
            double[] maximum
    ) {
        private boolean contains(double x, double y, double z) {
            for (int i = 0; i < HULL_DIRECTIONS.size(); i++) {
                HullDirection direction = HULL_DIRECTIONS.get(i);
                double dot = direction.dx() * x
                        + direction.dy() * y
                        + direction.dz() * z;
                double epsilon = direction.epsilon();

                if (dot < minimum[i] - epsilon
                        || dot > maximum[i] + epsilon) {
                    return false;
                }
            }
            return true;
        }

        private static HullEnvelope fromBounds(HullBoundsKey bounds) {
            int directionCount = HULL_DIRECTIONS.size();
            double[] minimum = new double[directionCount];
            double[] maximum = new double[directionCount];

            for (int i = 0; i < directionCount; i++) {
                HullDirection direction = HULL_DIRECTIONS.get(i);
                int dx = direction.dx();
                int dy = direction.dy();
                int dz = direction.dz();

                minimum[i] = dx * (dx >= 0 ? bounds.minX() : bounds.maxX() + 1.0)
                        + dy * (dy >= 0 ? bounds.minY() : bounds.maxY() + 1.0)
                        + dz * (dz >= 0 ? bounds.minZ() : bounds.maxZ() + 1.0);
                maximum[i] = dx * (dx >= 0 ? bounds.maxX() + 1.0 : bounds.minX())
                        + dy * (dy >= 0 ? bounds.maxY() + 1.0 : bounds.minY())
                        + dz * (dz >= 0 ? bounds.maxZ() + 1.0 : bounds.minZ());
            }

            return new HullEnvelope(bounds, minimum, maximum);
        }
    }

    private record DistanceCacheKey(
            UUID groupId,
            UUID subjectId
    ) {
    }

    private record DistanceSample(
            double x,
            double y,
            double z,
            double maxDistance,
            double distance,
            long sampleTimeNanos
    ) {
    }

    private record EntityCloakVisual(
            float cloakStrength,
            CloakRenderMode renderMode
    ) {
        private static final EntityCloakVisual VISIBLE =
                new EntityCloakVisual(0.0F, CloakRenderMode.DITHER);
    }

    private record EntityOccluder(
            ClientSubLevel subLevel,
            float cloakStrength,
            CloakRenderMode renderMode
    ) {
    }

    private record EntityVisualSample(
            double x,
            double y,
            double z,
            long sampleTimeNanos,
            EntityCloakVisual visual
    ) {
    }

    private static final class EntityVisibilityState {
        private UUID lastGroupId;
        private long lastTouchedNanos;

        private final Map<UUID, ViewerVisibilityTransition> aboardTransitions =
                new ConcurrentHashMap<>();

        private final Map<UUID, LeaveVisibilityTransition> leaveTransitions =
                new ConcurrentHashMap<>();

        private final Map<UUID, Float> lastEffectiveStrength =
                new ConcurrentHashMap<>();
    }

    private static final class LeaveVisibilityTransition {

        private final float startStrength;
        private final long startTimeNanos;
        private final long delayNanos;
        private final long durationNanos;

        private LeaveVisibilityTransition(
                float startStrength,
                long startTimeNanos,
                float delaySeconds,
                float durationSeconds
        ) {
            this.startStrength = clamp(startStrength);
            this.startTimeNanos = startTimeNanos;
            this.delayNanos = (long) (Math.max(0.0F, delaySeconds)
                    * 1_000_000_000L);
            this.durationNanos = (long) (Math.max(0.0F, durationSeconds)
                    * 1_000_000_000L);
        }

        /**
         * targetStrength is deliberately supplied on each render because the
         * normal proximity target can continue changing while the player moves.
         * It is ignored entirely until the grace delay has elapsed.
         */
        private float getStrength(long now, float targetStrength) {
            long elapsed = Math.max(0L, now - startTimeNanos);

            if (elapsed < delayNanos) {
                return startStrength;
            }

            float target = clamp(targetStrength);
            if (durationNanos <= 0L) {
                return target;
            }

            float progress = (float) (elapsed - delayNanos)
                    / (float) durationNanos;
            progress = clamp(progress);
            float eased = progress * progress * (3.0F - 2.0F * progress);

            return startStrength + (target - startStrength) * eased;
        }

        private boolean isFinished(long now) {
            return now - startTimeNanos >= delayNanos + durationNanos;
        }
    }

    private static final class ViewerVisibilityTransition {

        private final float startStrength;
        private final float targetStrength;
        private final long startTimeNanos;
        private final long delayNanos;
        private final long durationNanos;

        private ViewerVisibilityTransition(
                float startStrength,
                float targetStrength,
                long startTimeNanos,
                float delaySeconds,
                float durationSeconds
        ) {
            this.startStrength = clamp(startStrength);
            this.targetStrength = clamp(targetStrength);
            this.startTimeNanos = startTimeNanos;
            this.delayNanos = (long) (Math.max(0.0F, delaySeconds)
                    * 1_000_000_000L);
            this.durationNanos = (long) (Math.max(0.0F, durationSeconds)
                    * 1_000_000_000L);
        }

        private float getStrength(long now) {
            long elapsed = Math.max(0L, now - startTimeNanos);

            if (elapsed < delayNanos) {
                return startStrength;
            }

            if (durationNanos <= 0L) {
                return targetStrength;
            }

            float progress = (float) (elapsed - delayNanos)
                    / (float) durationNanos;

            progress = clamp(progress);
            float eased = progress * progress * (3.0F - 2.0F * progress);

            return startStrength
                    + (targetStrength - startStrength) * eased;
        }

        private boolean isFinished(long now) {
            return now - startTimeNanos >= delayNanos + durationNanos;
        }
    }

    private static final class CloakTransition {

        private final float startStrength;
        private final float targetStrength;
        private final long startTimeNanos;
        private final long durationNanos;
        private final CloakEasing easing;

        private CloakTransition(
                float startStrength,
                float targetStrength,
                long startTimeNanos,
                float durationSeconds,
                CloakEasing easing
        ) {
            this.startStrength = clamp(startStrength);
            this.targetStrength = clamp(targetStrength);
            this.startTimeNanos = startTimeNanos;
            this.durationNanos =
                    (long) (Math.max(0.0F, durationSeconds)
                            * 1_000_000_000L);
            this.easing = easing;
        }

        private float getStrength(long now) {
            if (durationNanos <= 0L) {
                return targetStrength;
            }

            float progress =
                    (float) (now - startTimeNanos)
                            / (float) durationNanos;

            progress = clamp(progress);
            float eased = easing.apply(progress);

            return startStrength
                    + (targetStrength - startStrength) * eased;
        }

        private boolean isFinished(long now) {
            return durationNanos <= 0L
                    || now - startTimeNanos >= durationNanos;
        }
    }
}
