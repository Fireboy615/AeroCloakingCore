package net.fireboy.aerocloakingcore.network;

import dev.ryanhcode.sable.Sable;
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
import java.util.Objects;
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

    /** Sublevels present in the most recent server sync. */
    private static final Set<UUID> SERVER_SUBLEVELS =
            ConcurrentHashMap.newKeySet();

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

        for (CloakingSyncPayload.Entry entry : entries) {
            UUID id = entry.subLevelId();
            CloakingCoreSettings settings = entry.settings().normalized();

            incoming.add(id);
            CORE_SETTINGS.put(id, settings);

            setTargetStrength(
                    id,
                    settings.cloakStrength()
            );
        }

        SERVER_SUBLEVELS.clear();
        SERVER_SUBLEVELS.addAll(incoming);

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
                                CloakingCoreSettings.DEFAULT.withCloakStrength(1.0F)
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
        return SERVER_SETTINGS.transitionDurationSeconds();
    }

    public static CloakEasing getTransitionEasing(UUID subLevelId) {
        return SERVER_SETTINGS.transitionEasing();
    }

    public static float getViewerCloakStrength(UUID subLevelId) {
        float strength = getCloakStrength(subLevelId);

        if (strength <= 0.0F) {
            return 0.0F;
        }

        if (!visibleWhileAboard(subLevelId)) {
            return strength;
        }

        SubLevel viewerSubLevel = getViewerSubLevel();

        if (viewerSubLevel != null
                && subLevelId.equals(viewerSubLevel.getUniqueId())) {
            return 0.0F;
        }

        return strength;
    }

    public static float getViewerCloakStrength(ClientSubLevel subLevel) {
        UUID subLevelId = subLevel.getUniqueId();
        float baseStrength = getCloakStrength(subLevelId);

        if (baseStrength <= 0.0F) {
            return 0.0F;
        }

        long now = System.nanoTime();
        SubLevel viewerSubLevel = getViewerSubLevel();

        updateViewerLeaveState(viewerSubLevel, now);

        if (visibleWhileAboard(subLevelId)
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

        float graceSeconds = leaveGraceSeconds(subLevelId);
        float fadeSeconds = leaveFadeSeconds(subLevelId);

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

    private static boolean visibleWhileAboard(UUID subLevelId) {
        return SERVER_SETTINGS.visibleWhileAboard();
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

    private static double fullyVisibleDistance(UUID subLevelId) {
        return SERVER_SETTINGS.fullyVisibleDistance();
    }

    private static double fullyCloakedDistance(UUID subLevelId) {
        return SERVER_SETTINGS.fullyCloakedDistance();
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
