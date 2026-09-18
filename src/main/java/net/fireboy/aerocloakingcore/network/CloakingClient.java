package net.fireboy.aerocloakingcore.network;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;

import net.fireboy.aerocloakingcore.client.CloakEasing;
import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.fireboy.aerocloakingcore.cloak.CloakingCoreSettings;
import net.fireboy.aerocloakingcore.cloak.CloakingServerSettings;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CloakingClient {

    private static final float FULL_CLOAK_THRESHOLD = 0.999F;

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
        ClientSubLevel subLevel = getEntityClientSubLevel(entity);

        return subLevel != null
                ? getRenderMode(subLevel)
                : CloakRenderMode.DITHER;
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

        double fullyVisibleDistance = fullyVisibleDistance(subLevelId);
        double fullyCloakedDistance = fullyCloakedDistance(subLevelId);

        double distance = distanceToBounds(
                minecraft.player.getX(),
                minecraft.player.getY(),
                minecraft.player.getZ(),
                subLevel.boundingBox()
        );

        // Reveal a connected assembly from the nearest member rather than from
        // whichever child happens to be rendering this frame.
        UUID groupId = visibilityKey(subLevelId);
        Set<UUID> members = GROUP_MEMBERS.get(groupId);

        if (members != null && minecraft.level != null) {
            SubLevelContainer container = SubLevelContainer.getContainer(
                    minecraft.level
            );

            if (container != null) {
                for (UUID memberId : members) {
                    SubLevel member = container.getSubLevel(memberId);
                    if (member == null) {
                        continue;
                    }

                    distance = Math.min(
                            distance,
                            distanceToBounds(
                                    minecraft.player.getX(),
                                    minecraft.player.getY(),
                                    minecraft.player.getZ(),
                                    member.boundingBox()
                            )
                    );
                }
            }
        }

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

    public static float getEntityViewerCloakStrength(Entity entity) {
        ClientSubLevel subLevel = getEntityClientSubLevel(entity);

        if (subLevel == null) {
            return 0.0F;
        }

        return getViewerCloakStrength(subLevel);
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
