package net.fireboy.aerocloakingcore.network;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;

import net.fireboy.aerocloakingcore.client.CloakEasing;
import net.fireboy.aerocloakingcore.client.config.AeroCloakingCoreClientConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CloakingClient {

    private static final float FULL_CLOAK_THRESHOLD = 0.999F;

    /** The sublevel the local player was aboard/tracking on the previous render check. */
    private static UUID lastViewerSubLevelId = null;

    /** Time the local player most recently left each sublevel. */
    private static final Map<UUID, Long> VIEWER_LEFT_AT =
            new ConcurrentHashMap<>();

    /** Client-side transition state for cloaked sublevels. */
    private static final Map<UUID, CloakTransition> TRANSITIONS =
            new ConcurrentHashMap<>();

    private CloakingClient() {
    }


    public static void setCloakedSubLevels(List<UUID> ids) {

        Set<UUID> incoming = new HashSet<>(ids);
        Set<UUID> known = new HashSet<>(TRANSITIONS.keySet());

        for (UUID id : incoming) {
            setTargetStrength(id, 1.0F);
        }

        for (UUID id : known) {
            if (!incoming.contains(id)) {
                setTargetStrength(id, 0.0F);
            }
        }
    }


    /**
     * Sets the server-requested cloak strength.
     *
     * This already supports arbitrary 0..1 values for future UI and
     * Redstone Link control.
     */
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
                        getTransitionDurationSeconds(),
                        getTransitionEasing()
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
            return 0.0F;
        }

        return strength;
    }


    /**
     * Viewer-specific strength when only a UUID is available.
     *
     * This can account for whether the viewer is aboard, but distance
     * reveal needs the ClientSubLevel overload below so its bounds are known.
     */
    public static float getViewerCloakStrength(UUID subLevelId) {

        float strength = getCloakStrength(subLevelId);

        if (strength <= 0.0F) {
            return 0.0F;
        }

        if (!AeroCloakingCoreClientConfig.VISIBLE_WHILE_ABOARD.get()) {
            return strength;
        }

        SubLevel viewerSubLevel = getViewerSubLevel();

        if (viewerSubLevel != null
                && subLevelId.equals(viewerSubLevel.getUniqueId())) {
            return 0.0F;
        }

        return strength;
    }


    /**
     * Full viewer-specific cloak strength used for rendering a client sublevel.
     */
    public static float getViewerCloakStrength(ClientSubLevel subLevel) {

        UUID subLevelId = subLevel.getUniqueId();
        float baseStrength = getCloakStrength(subLevelId);

        if (baseStrength <= 0.0F) {
            return 0.0F;
        }

        long now = System.nanoTime();
        SubLevel viewerSubLevel = getViewerSubLevel();

        updateViewerLeaveState(viewerSubLevel, now);

        if (AeroCloakingCoreClientConfig.VISIBLE_WHILE_ABOARD.get()
                && viewerSubLevel != null
                && subLevelId.equals(viewerSubLevel.getUniqueId())) {
            return 0.0F;
        }

        float distanceStrength = getDistanceCloakStrength(subLevel);
        float leaveStrength = getPostLeaveCloakStrength(subLevelId, now);

        return clamp(
                baseStrength
                        * distanceStrength
                        * leaveStrength
        );
    }


    private static void updateViewerLeaveState(
            SubLevel viewerSubLevel,
            long now
    ) {

        UUID currentViewerSubLevelId =
                viewerSubLevel != null
                        ? viewerSubLevel.getUniqueId()
                        : null;

        if (Objects.equals(
                currentViewerSubLevelId,
                lastViewerSubLevelId
        )) {
            return;
        }

        if (lastViewerSubLevelId != null) {
            VIEWER_LEFT_AT.put(lastViewerSubLevelId, now);
        }

        if (currentViewerSubLevelId != null) {
            VIEWER_LEFT_AT.remove(currentViewerSubLevelId);
        }

        lastViewerSubLevelId = currentViewerSubLevelId;
    }


    private static float getPostLeaveCloakStrength(
            UUID subLevelId,
            long now
    ) {

        Long leftAt = VIEWER_LEFT_AT.get(subLevelId);

        if (leftAt == null) {
            return 1.0F;
        }

        float graceSeconds =
                AeroCloakingCoreClientConfig.LEAVE_GRACE_SECONDS
                        .get()
                        .floatValue();

        float fadeSeconds =
                AeroCloakingCoreClientConfig.LEAVE_FADE_SECONDS
                        .get()
                        .floatValue();

        float secondsSinceLeave =
                (now - leftAt) / 1_000_000_000.0F;

        if (secondsSinceLeave <= graceSeconds) {
            return 0.0F;
        }

        if (fadeSeconds <= 0.0F) {
            VIEWER_LEFT_AT.remove(subLevelId, leftAt);
            return 1.0F;
        }

        float progress =
                (secondsSinceLeave - graceSeconds)
                        / fadeSeconds;

        if (progress >= 1.0F) {
            VIEWER_LEFT_AT.remove(subLevelId, leftAt);
            return 1.0F;
        }

        progress = clamp(progress);

        return progress * progress * (3.0F - 2.0F * progress);
    }


    private static float getDistanceCloakStrength(
            ClientSubLevel subLevel
    ) {

        if (!AeroCloakingCoreClientConfig.PROXIMITY_REVEAL_ENABLED.get()) {
            return 1.0F;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null) {
            return 1.0F;
        }

        double fullyVisibleDistance =
                AeroCloakingCoreClientConfig.FULLY_VISIBLE_DISTANCE.get();

        double fullyCloakedDistance =
                AeroCloakingCoreClientConfig.FULLY_CLOAKED_DISTANCE.get();

        BoundingBox3dc bounds = subLevel.boundingBox();

        double dx = axisDistance(
                minecraft.player.getX(),
                bounds.minX(),
                bounds.maxX()
        );

        double dy = axisDistance(
                minecraft.player.getY(),
                bounds.minY(),
                bounds.maxY()
        );

        double dz = axisDistance(
                minecraft.player.getZ(),
                bounds.minZ(),
                bounds.maxZ()
        );

        double distance = Math.sqrt(
                dx * dx + dy * dy + dz * dz
        );

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


    /**
     * Finds the client sublevel an entity belongs to.
     *
     * getContaining() catches entities retained inside the sublevel plot.
     * getTrackingOrVehicleSubLevel() catches players/mobs standing on or
     * riding the moving sublevel.
     */
    public static ClientSubLevel getEntityClientSubLevel(Entity entity) {

        SubLevel subLevel = Sable.HELPER.getContaining(entity);

        if (!(subLevel instanceof ClientSubLevel)) {
            subLevel = Sable.HELPER.getTrackingOrVehicleSubLevel(entity);
        }

        return subLevel instanceof ClientSubLevel clientSubLevel
                ? clientSubLevel
                : null;
    }


    /**
     * Uses the exact same viewer-specific cloak strength as the sublevel
     * geometry, including:
     *
     * - visible while aboard
     * - leave grace period
     * - leave fade period
     * - proximity reveal distance
     */
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


    public static float getTransitionDurationSeconds() {
        return AeroCloakingCoreClientConfig.TRANSITION_DURATION_SECONDS
                .get()
                .floatValue();
    }

    public static CloakEasing getTransitionEasing() {
        return AeroCloakingCoreClientConfig.TRANSITION_EASING.get();
    }


    private static float clamp(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
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
