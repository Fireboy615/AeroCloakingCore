package net.fireboy.aerocloakingcore.cloak;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.fireboy.aerocloakingcore.block.entity.CloakingCoreBlockEntity;
import net.fireboy.aerocloakingcore.network.CloakingSyncPayload;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side coordinator for connected Sable cloak groups.
 *
 * A Cloaking Core now contributes to one logical system made from every
 * sublevel in its resolved Sable connection graph. The graph itself is found
 * by the block entity, which allows normal connection actors and rope actors
 * to be enabled independently. Connected cores are merged into the same group,
 * member sublevels are counted once, and the resulting cloak state is published
 * against every member UUID so the existing renderer remains per-sublevel.
 */
@EventBusSubscriber(modid = AeroCloakingCore.MOD_ID)
public final class CloakingManager {

    public static final float CLOAK_SPEED_REFERENCE_RPM = 64.0F;
    public static final float REVEAL_REFERENCE_BLOCKS = 256.0F;
    public static final float REVEAL_BONUS_START_RPM = 128.0F;
    public static final float REVEAL_BONUS_MAX_RPM = 256.0F;
    public static final float MIN_REVEAL_GAP_MULTIPLIER = 0.10F;

    /** One entry per connected cloak group, keyed by a deterministic UUID. */
    private static final Map<UUID, CloakSystem> SYSTEMS = new LinkedHashMap<>();

    /** Resolves any member sublevel UUID back to its connected cloak group. */
    private static final Map<UUID, UUID> SUBLEVEL_TO_SYSTEM = new HashMap<>();

    /** Latest once-per-second connectivity/block-count snapshot from each core. */
    private static final Map<CloakingCoreBlockEntity, CoreRegistration> REGISTRATIONS =
            new IdentityHashMap<>();

    /** Most recently edited core wins if two previously separate systems merge. */
    private static final Map<CloakingCoreBlockEntity, Long> SETTINGS_REVISIONS =
            new IdentityHashMap<>();
    private static long settingsRevisionCounter = 0L;
    /** Spatial broad-phase now runs once per server tick per cloak system.
     * Candidate geometry is cached and rescanned adaptively as it moves. */

    private CloakingManager() {
    }

    // ---------------------------------------------------------------------
    // CORE REGISTRATION / SHARED SETTINGS
    // ---------------------------------------------------------------------

    public static void updateCore(
            UUID subLevelId,
            CloakingCoreBlockEntity core
    ) {
        if (subLevelId == null || core == null || !isLive(core)) {
            return;
        }

        Map<UUID, Integer> snapshot = snapshotFor(core, subLevelId);
        CoreRegistration previous = REGISTRATIONS.get(core);
        boolean topologyChanged = previous == null
                || !subLevelId.equals(previous.homeSubLevelId)
                || !snapshot.equals(previous.memberBlockCounts);

        if (topologyChanged) {
            REGISTRATIONS.put(
                    core,
                    new CoreRegistration(subLevelId, snapshot)
            );
            SETTINGS_REVISIONS.putIfAbsent(core, 0L);
            rebuildSystems();
            return;
        }

        CloakSystem system = systemForSubLevel(subLevelId);
        if (system == null || !system.cores.contains(core)) {
            rebuildSystems();
            return;
        }

        // Mechanical speed and overstress state can change every tick even when
        // the connectivity/block-count snapshot has not changed.
        recompute(system, true);
    }

    public static void setSystemSettings(
            UUID subLevelId,
            CloakingCoreBlockEntity source,
            CloakingCoreSettings settings
    ) {
        if (subLevelId == null || source == null || settings == null) {
            return;
        }

        SETTINGS_REVISIONS.put(source, ++settingsRevisionCounter);

        if (!REGISTRATIONS.containsKey(source)) {
            REGISTRATIONS.put(
                    source,
                    new CoreRegistration(
                            subLevelId,
                            snapshotFor(source, subLevelId)
                    )
            );
            rebuildSystems();
        }

        CloakSystem system = systemForSubLevel(subLevelId);
        if (system == null) {
            rebuildSystems();
            system = systemForSubLevel(subLevelId);
        }

        if (system == null) {
            return;
        }

        CloakingCoreSettings normalized = settings.normalized();
        boolean changed = !normalized.equals(system.settings);
        system.settings = normalized;

        if (changed) {
            for (CloakingCoreBlockEntity core : system.cores) {
                if (isLive(core)) {
                    core.applySystemSettingsFromManager(normalized);
                }
            }
        }

        recompute(system, true);
    }

    /**
     * A Redstone Link frequency pair belongs to the cloak SYSTEM, not to
     * whichever Core happened to have its GUI open. Every Core retains an NBT
     * copy so saved ships preserve the pair after reloading.
     */
    public static void setSystemLinkFrequencies(
            UUID subLevelId,
            CloakingCoreBlockEntity source
    ) {
        if (subLevelId == null || !isLive(source)) {
            return;
        }

        // Preserve the player's new pair even if registering this Core causes
        // an older system to be rebuilt before we can propagate the edit.
        ItemStack chosenFirst = source.getLinkFrequency(true);
        ItemStack chosenSecond = source.getLinkFrequency(false);
        SETTINGS_REVISIONS.put(source, ++settingsRevisionCounter);
        if (!REGISTRATIONS.containsKey(source)) {
            REGISTRATIONS.put(
                    source,
                    new CoreRegistration(subLevelId, snapshotFor(source, subLevelId))
            );
            rebuildSystems();
        }

        CloakSystem system = systemForSubLevel(subLevelId);
        if (system == null || !system.cores.contains(source)) {
            rebuildSystems();
            system = systemForSubLevel(subLevelId);
        }
        if (system == null) {
            return;
        }

        system.linkFrequencyFirst = chosenFirst;
        system.linkFrequencySecond = chosenSecond;
        for (CloakingCoreBlockEntity member : system.cores) {
            if (isLive(member)) {
                member.applySystemLinkFrequenciesFromManager(
                        system.linkFrequencyFirst,
                        system.linkFrequencySecond
                );
            }
        }
    }

    public static void unregisterCore(
            UUID subLevelId,
            CloakingCoreBlockEntity core
    ) {
        if (core == null) {
            return;
        }

        if (REGISTRATIONS.remove(core) != null) {
            SETTINGS_REVISIONS.remove(core);
            rebuildSystems();
        }
    }

    private static Map<UUID, Integer> snapshotFor(
            CloakingCoreBlockEntity core,
            UUID homeSubLevelId
    ) {
        Map<UUID, Integer> resolved = core.getResolvedSubLevelBlockCounts();

        if (resolved == null || resolved.isEmpty()) {
            return Map.of(
                    homeSubLevelId,
                    Math.max(0, core.getSubLevelBlockCount())
            );
        }

        Map<UUID, Integer> normalized = new LinkedHashMap<>();
        for (Map.Entry<UUID, Integer> entry : resolved.entrySet()) {
            if (entry.getKey() != null) {
                normalized.put(
                        entry.getKey(),
                        Math.max(0, entry.getValue() == null ? 0 : entry.getValue())
                );
            }
        }

        normalized.putIfAbsent(
                homeSubLevelId,
                Math.max(0, core.getSubLevelBlockCount())
        );
        return Map.copyOf(normalized);
    }

    /**
     * Rebuilds connected components from every core's latest graph snapshot.
     * Overlapping snapshots merge automatically, so a core on a swivel-mounted
     * child joins the same system as a core on the parent craft.
     */
    private static void rebuildSystems() {
        pruneDeadRegistrations();

        boolean hadSystems = !SYSTEMS.isEmpty();
        Map<UUID, CloakSystem> previousSystems = new HashMap<>(SYSTEMS);
        // Prefer a Core that already belonged to an existing group when a
        // brand-new Core joins with no user edit history of its own.
        Set<CloakingCoreBlockEntity> previouslyGrouped =
                Collections.newSetFromMap(new IdentityHashMap<>());
        for (CloakSystem previous : SYSTEMS.values()) {
            previouslyGrouped.addAll(previous.cores);
        }
        SYSTEMS.clear();
        SUBLEVEL_TO_SYSTEM.clear();

        if (REGISTRATIONS.isEmpty()) {
            if (hadSystems) {
                sync();
            }
            return;
        }

        UnionFind unionFind = new UnionFind();

        for (CoreRegistration registration : REGISTRATIONS.values()) {
            unionFind.add(registration.homeSubLevelId);

            for (UUID memberId : registration.memberBlockCounts.keySet()) {
                unionFind.add(memberId);
                unionFind.union(registration.homeSubLevelId, memberId);
            }
        }

        Map<UUID, List<Map.Entry<CloakingCoreBlockEntity, CoreRegistration>>>
                registrationsByRoot = new LinkedHashMap<>();

        for (Map.Entry<CloakingCoreBlockEntity, CoreRegistration> entry
                : REGISTRATIONS.entrySet()) {
            UUID root = unionFind.find(entry.getValue().homeSubLevelId);
            registrationsByRoot
                    .computeIfAbsent(root, ignored -> new ArrayList<>())
                    .add(entry);
        }

        List<CloakSystem> rebuilt = new ArrayList<>();

        for (List<Map.Entry<CloakingCoreBlockEntity, CoreRegistration>> component
                : registrationsByRoot.values()) {
            Set<UUID> memberIds = new LinkedHashSet<>();
            Map<UUID, Integer> memberBlockCounts = new LinkedHashMap<>();
            Set<CloakingCoreBlockEntity> cores = new LinkedHashSet<>();

            for (Map.Entry<CloakingCoreBlockEntity, CoreRegistration> entry
                    : component) {
                cores.add(entry.getKey());

                for (Map.Entry<UUID, Integer> member
                        : entry.getValue().memberBlockCounts.entrySet()) {
                    memberIds.add(member.getKey());
                    memberBlockCounts.merge(
                            member.getKey(),
                            Math.max(0, member.getValue()),
                            Math::max
                    );
                }
            }

            if (memberIds.isEmpty()) {
                continue;
            }

            UUID systemId = memberIds.stream()
                    .min(Comparator.comparing(UUID::toString))
                    .orElseThrow();

            CloakSystem system = new CloakSystem(systemId);
            system.memberBlockCounts.putAll(memberBlockCounts);
            system.cores.addAll(cores);

            // Rebuilding a Core group should not momentarily remove every
            // spatially enclosed child and restart its fade animation.
            // Reuse spatial state only if the physical connected membership
            // of the system has stayed the same.
            CloakSystem previous = previousSystems.get(systemId);
            if (previous != null
                    && previous.memberBlockCounts.keySet().equals(memberBlockCounts.keySet())) {
                system.spatialIntersections = previous.spatialIntersections;
                system.spatialScanCache = previous.spatialScanCache;
            }

            // Settings are already mirrored within an existing group. For a
            // newly merged group choose deterministically, then mirror that one
            // value to every core so subsequent rebuilds remain stable.
            CloakingCoreBlockEntity settingsSource = component.stream()
                    .sorted((a, b) -> {
                        long revisionA = SETTINGS_REVISIONS.getOrDefault(
                                a.getKey(), 0L
                        );
                        long revisionB = SETTINGS_REVISIONS.getOrDefault(
                                b.getKey(), 0L
                        );
                        int revisionCompare = Long.compare(revisionB, revisionA);
                        if (revisionCompare != 0) {
                            return revisionCompare;
                        }
                        boolean aExisting = previouslyGrouped.contains(a.getKey());
                        boolean bExisting = previouslyGrouped.contains(b.getKey());
                        if (aExisting != bExisting) {
                            return aExisting ? -1 : 1;
                        }
                        return compareRegistrations(a, b);
                    })
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);

            system.settings = settingsSource != null
                    ? settingsSource.getSettings().normalized()
                    : CloakingCoreSettings.DEFAULT;
            system.linkFrequencyFirst = settingsSource != null
                    ? settingsSource.getLinkFrequency(true)
                    : ItemStack.EMPTY;
            system.linkFrequencySecond = settingsSource != null
                    ? settingsSource.getLinkFrequency(false)
                    : ItemStack.EMPTY;

            for (CloakingCoreBlockEntity core : system.cores) {
                if (isLive(core)) {
                    core.applySystemSettingsFromManager(system.settings);
                    core.applySystemLinkFrequenciesFromManager(
                            system.linkFrequencyFirst,
                            system.linkFrequencySecond
                    );
                }
            }

            rebuilt.add(system);
        }

        rebuilt.sort(Comparator.comparing(system -> system.systemId.toString()));

        for (CloakSystem system : rebuilt) {
            SYSTEMS.put(system.systemId, system);
            for (UUID memberId : system.memberBlockCounts.keySet()) {
                SUBLEVEL_TO_SYSTEM.put(memberId, system.systemId);
            }
            recompute(system, false);
        }

        // Membership itself is render-relevant even if strength and derived
        // statistics happen to be unchanged, so always publish after rebuild.
        sync();
    }

    private static int compareRegistrations(
            Map.Entry<CloakingCoreBlockEntity, CoreRegistration> a,
            Map.Entry<CloakingCoreBlockEntity, CoreRegistration> b
    ) {
        int idCompare = a.getValue().homeSubLevelId.toString()
                .compareTo(b.getValue().homeSubLevelId.toString());
        if (idCompare != 0) {
            return idCompare;
        }

        return Long.compare(
                a.getKey().getBlockPos().asLong(),
                b.getKey().getBlockPos().asLong()
        );
    }

    // ---------------------------------------------------------------------
    // SYSTEM CALCULATION
    // ---------------------------------------------------------------------

    /**
     * Discover physical overlap without introducing Sable connectivity or
     * merging the controls of two independently powered cloaking systems.
     *
     * The regular per-Core graph scan stays at 20 ticks. Spatial broad-phase
     * checks every tick; the costly voxel phase is reused or evaluated at an
     * adaptive rate. Every Core in the system shares this scan/cache.
     */
    private static boolean refreshSpatialIntersections(CloakSystem system) {
        if (system.cores.isEmpty()) {
            return false;
        }

        CloakingCoreBlockEntity source = system.cores.iterator().next();
        long tick = source.getLevel().getGameTime();
        if (system.lastSpatialScanTick == tick) {
            return false;
        }
        system.lastSpatialScanTick = tick;

        // Keep containment membership even when the cloak strength is zero.
        // Otherwise a ship that fades from 0% to 100% discovers its enclosed
        // children partway through the parent's transition. The client then
        // gives them late entrance fades, which makes the silhouettes pop.
        // Likewise, fading back to 0% must not detach an enclosed child.
        Map<UUID, SpatialCloakField.Intersection> hits = Map.of();
        SubLevel root = Sable.HELPER.getContaining(source);
        if (root != null && !root.isRemoved()) {
                SubLevelContainer container =
                        SubLevelContainer.getContainer(root.getLevel());
                List<SubLevel> hosts = new ArrayList<>();
                for (UUID memberId : system.memberBlockCounts.keySet()) {
                    SubLevel member = container != null
                            ? container.getSubLevel(memberId) : null;
                    if (member == null && memberId.equals(root.getUniqueId())) {
                        member = root;
                    }
                    if (member != null && !member.isRemoved()) {
                        hosts.add(member);
                    }
                }
                var profiler = source.getLevel().getProfiler();
                profiler.push("aero_spatial_field_scan");
                try {
                    hits = SpatialCloakField.scan(
                            hosts,
                            system.memberBlockCounts.keySet(),
                            system.spatialScanCache,
                            tick
                    );
                } finally {
                    profiler.pop();
                }
        }

        if (hits.equals(system.spatialIntersections)) {
            return false;
        }
        system.spatialIntersections = Map.copyOf(hits);
        return true;
    }

    private static void recompute(CloakSystem system, boolean syncOnChange) {
        system.cores.removeIf(core -> !isLive(core));

        if (system.cores.isEmpty()) {
            if (SYSTEMS.remove(system.systemId) != null) {
                system.memberBlockCounts.keySet().forEach(SUBLEVEL_TO_SYSTEM::remove);
                if (syncOnChange) {
                    sync();
                }
            }
            return;
        }

        if (system.settings == null) {
            system.settings = system.cores.iterator()
                    .next()
                    .getSettings()
                    .normalized();
        }

        // Spatially intersecting sublevels contribute load, but NEVER become
        // connection-graph members; they cannot merge Core settings or links.
        boolean spatialChanged = refreshSpatialIntersections(system);
        long blockCountLong = 0L;
        for (int count : system.memberBlockCounts.values()) {
            blockCountLong += Math.max(0, count);
        }
        for (SpatialCloakField.Intersection intersection
                : system.spatialIntersections.values()) {
            blockCountLong += Math.max(0, intersection.load());
        }
        int blockCount = (int) Math.min(Integer.MAX_VALUE, blockCountLong);

        int totalPotentialCapacity = 0;
        int totalOperationalCapacity = 0;
        int potentialContributingCoreCount = 0;
        int operationalContributingCoreCount = 0;

        double operationalRpmSum = 0.0;
        double potentialRpmSum = 0.0;

        for (CloakingCoreBlockEntity core : system.cores) {
            int potentialCapacity = core.getPotentialCloakCapacityBlocks();
            int operationalCapacity = core.getOperationalCloakCapacityBlocks();

            totalPotentialCapacity += potentialCapacity;
            totalOperationalCapacity += operationalCapacity;

            if (potentialCapacity > 0) {
                potentialContributingCoreCount++;
                potentialRpmSum += core.getTheoreticalRpm();
            }

            if (operationalCapacity > 0) {
                operationalContributingCoreCount++;
                operationalRpmSum += core.getCurrentRpm();
            }
        }

        int coreCount = system.cores.size();
        float efficiencyFactor = calculateEfficiencyFactor(
                potentialContributingCoreCount
        );

        for (CloakingCoreBlockEntity core : system.cores) {
            float assignedBlocks;

            if (totalPotentialCapacity > 0) {
                assignedBlocks = blockCount
                        * (core.getPotentialCloakCapacityBlocks()
                        / (float) totalPotentialCapacity);
            } else {
                assignedBlocks = coreCount > 0
                        ? blockCount / (float) coreCount
                        : 0.0F;
            }

            core.applySystemLoadFromManager(
                    assignedBlocks,
                    efficiencyFactor
            );
        }

        boolean capacitySatisfied = blockCount > 0
                && totalOperationalCapacity >= blockCount;

        float effectiveRpm;
        if (operationalContributingCoreCount > 0) {
            effectiveRpm = (float) (
                    operationalRpmSum / operationalContributingCoreCount
            );
        } else if (potentialContributingCoreCount > 0) {
            effectiveRpm = (float) (
                    potentialRpmSum / potentialContributingCoreCount
            );
        } else {
            effectiveRpm = 0.0F;
        }

        CloakingServerSettings serverSettings = CloakingServerSettings.fromConfig();

        float transitionDuration = calculateTransitionDurationSeconds(
                serverSettings.transitionDurationSeconds(),
                effectiveRpm
        );

        double fullyCloakedDistance = calculateFullyCloakedDistance(
                serverSettings.fullyVisibleDistance(),
                serverSettings.revealDistanceMultiplier(),
                blockCount,
                effectiveRpm
        );

        system.blockCount = blockCount;
        system.totalPotentialCapacity = totalPotentialCapacity;
        system.totalOperationalCapacity = totalOperationalCapacity;
        system.effectiveRpm = effectiveRpm;
        system.efficiencyFactor = efficiencyFactor;
        system.transitionDurationSeconds = transitionDuration;
        system.fullyCloakedDistance = fullyCloakedDistance;
        system.capacitySatisfied = capacitySatisfied;

        for (CloakingCoreBlockEntity core : system.cores) {
            core.applySystemStatsFromManager(
                    coreCount,
                    blockCount,
                    totalPotentialCapacity,
                    totalOperationalCapacity,
                    capacitySatisfied,
                    effectiveRpm,
                    efficiencyFactor,
                    transitionDuration,
                    fullyCloakedDistance
            );
        }

        CloakingCoreSettings effectiveSettings = capacitySatisfied
                ? system.settings
                : system.settings.withCloakStrength(0.0F);

        boolean publishedChanged = spatialChanged
                || !effectiveSettings.equals(system.publishedSettings)
                        || blockCount != system.publishedBlockCount
                        || Math.abs(
                                transitionDuration
                                        - system.publishedTransitionDurationSeconds
                        ) > 0.05F
                        || Math.abs(
                                fullyCloakedDistance
                                        - system.publishedFullyCloakedDistance
                        ) > 0.05;

        system.publishedSettings = effectiveSettings;
        system.publishedBlockCount = blockCount;
        system.publishedTransitionDurationSeconds = transitionDuration;
        system.publishedFullyCloakedDistance = fullyCloakedDistance;

        if (publishedChanged && syncOnChange) {
            sync();
        }
    }

    public static float calculateEfficiencyFactor(int contributingCoreCount) {
        if (contributingCoreCount <= 1) {
            return 1.0F;
        }

        return Math.min(
                1.25F,
                1.0F + 0.05F * (contributingCoreCount - 1)
        );
    }

    public static double calculateFullyCloakedDistance(
            double fullyVisibleDistance,
            double revealDistanceMultiplier,
            int blockCount,
            float effectiveRpm
    ) {
        double visible = Math.max(0.0, fullyVisibleDistance);
        double configuredGap = CloakingServerSettings.BASE_REVEAL_GAP_BLOCKS
                * Math.max(0.0, revealDistanceMultiplier);

        if (configuredGap <= 0.0 || blockCount <= 0) {
            return visible;
        }

        double sizeMultiplier = Math.sqrt(
                Math.max(1.0, blockCount) / REVEAL_REFERENCE_BLOCKS
        );

        float highRpmProgress = 0.0F;
        if (effectiveRpm > REVEAL_BONUS_START_RPM) {
            highRpmProgress = (effectiveRpm - REVEAL_BONUS_START_RPM)
                    / (REVEAL_BONUS_MAX_RPM - REVEAL_BONUS_START_RPM);
            highRpmProgress = Math.max(
                    0.0F,
                    Math.min(1.0F, highRpmProgress)
            );
        }

        double rpmGapMultiplier = 1.0
                - (1.0 - MIN_REVEAL_GAP_MULTIPLIER) * highRpmProgress;

        return visible
                + configuredGap
                * sizeMultiplier
                * rpmGapMultiplier;
    }

    public static float calculateTransitionDurationSeconds(
            float configuredDurationSeconds,
            float rpm
    ) {
        float base = Math.max(0.0F, configuredDurationSeconds);

        if (base <= 0.0F) {
            return 0.0F;
        }

        if (rpm <= 0.0F) {
            return base;
        }

        float multiplier = (float) Math.sqrt(
                CLOAK_SPEED_REFERENCE_RPM / rpm
        );

        multiplier = Math.max(0.35F, Math.min(2.0F, multiplier));
        return base * multiplier;
    }

    private static void pruneDeadRegistrations() {
        REGISTRATIONS.entrySet().removeIf(entry -> !isLive(entry.getKey()));
        SETTINGS_REVISIONS.keySet().removeIf(core -> !REGISTRATIONS.containsKey(core));
    }

    private static boolean isLive(CloakingCoreBlockEntity core) {
        return core != null
                && !core.isRemoved()
                && core.getLevel() != null
                && !core.getLevel().isClientSide;
    }

    private static CloakSystem systemForSubLevel(UUID subLevelId) {
        UUID systemId = SUBLEVEL_TO_SYSTEM.get(subLevelId);
        return systemId != null ? SYSTEMS.get(systemId) : null;
    }

    // ---------------------------------------------------------------------
    // CLIENT SYNC
    // ---------------------------------------------------------------------

    private static CloakingSyncPayload createPayload() {
        CloakingServerSettings serverSettings = CloakingServerSettings.fromConfig();
        List<CloakingSyncPayload.Entry> entries = new ArrayList<>();

        // Explicit Sable connection members always keep their own Core
        // system's settings. Independent spatial capture never overrides a
        // sublevel with a separate Cloaking Core.
        for (CloakSystem system : SYSTEMS.values()) {
            if (system.publishedSettings == null) {
                continue;
            }

            CloakingCoreSettings syncedSettings = serverSettings.modEnabled()
                    ? system.publishedSettings
                    : system.publishedSettings.withCloakStrength(0.0F);

            for (UUID memberId : system.memberBlockCounts.keySet()) {
                entries.add(new CloakingSyncPayload.Entry(
                        memberId,
                        system.systemId,
                        syncedSettings,
                        system.publishedTransitionDurationSeconds,
                        system.publishedFullyCloakedDistance,
                        false
                ));
            }
        }

        // Full containment (or the Linear Bearing exception) grants cloak
        // inheritance, but never Core control, kinetic capacity or linking.
        // Choose one field deterministically if fields overlap.
        Set<UUID> assigned = new LinkedHashSet<>(SUBLEVEL_TO_SYSTEM.keySet());
        for (CloakSystem system : SYSTEMS.values()) {
            if (system.publishedSettings == null) {
                continue;
            }
            CloakingCoreSettings syncedSettings = serverSettings.modEnabled()
                    ? system.publishedSettings
                    : system.publishedSettings.withCloakStrength(0.0F);
            for (Map.Entry<UUID, SpatialCloakField.Intersection> hit
                    : system.spatialIntersections.entrySet()) {
                if (!hit.getValue().contained() || !assigned.add(hit.getKey())) {
                    continue;
                }
                entries.add(new CloakingSyncPayload.Entry(
                        hit.getKey(),
                        system.systemId,
                        syncedSettings,
                        system.publishedTransitionDurationSeconds,
                        system.publishedFullyCloakedDistance,
                        true
                ));
            }
        }

        return new CloakingSyncPayload(serverSettings, entries);
    }

    public static void sync() {
        PacketDistributor.sendToAllPlayers(createPayload());
    }

    public static void syncToPlayer(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, createPayload());
    }

    // ---------------------------------------------------------------------
    // QUERY HELPERS
    // ---------------------------------------------------------------------

    public static boolean isCloaked(UUID subLevelId) {
        if (!CloakingServerSettings.fromConfig().modEnabled()) {
            return false;
        }

        CloakSystem system = systemForSubLevel(subLevelId);
        if (system != null) {
            return system.publishedSettings != null
                    && system.publishedSettings.cloakStrength() > 0.0F;
        }
        for (CloakSystem host : SYSTEMS.values()) {
            SpatialCloakField.Intersection hit =
                    host.spatialIntersections.get(subLevelId);
            if (hit != null && hit.contained()
                    && host.publishedSettings != null
                    && host.publishedSettings.cloakStrength() > 0.0F) {
                return true;
            }
        }
        return false;
    }

    public static Map<UUID, CloakingCoreSettings> getCloakedSubLevels() {
        if (!CloakingServerSettings.fromConfig().modEnabled()) {
            return Collections.emptyMap();
        }

        Map<UUID, CloakingCoreSettings> result = new HashMap<>();

        for (CloakSystem system : SYSTEMS.values()) {
            if (system.publishedSettings == null) {
                continue;
            }

            for (UUID memberId : system.memberBlockCounts.keySet()) {
                result.put(memberId, system.publishedSettings);
            }
        }
        for (CloakSystem system : SYSTEMS.values()) {
            if (system.publishedSettings == null) {
                continue;
            }
            for (Map.Entry<UUID, SpatialCloakField.Intersection> hit
                    : system.spatialIntersections.entrySet()) {
                if (hit.getValue().contained()) {
                    result.putIfAbsent(hit.getKey(), system.publishedSettings);
                }
            }
        }

        return Collections.unmodifiableMap(result);
    }

    public static void clear() {
        boolean hadSystems = !SYSTEMS.isEmpty();
        SYSTEMS.clear();
        SUBLEVEL_TO_SYSTEM.clear();
        REGISTRATIONS.clear();
        SETTINGS_REVISIONS.clear();
        settingsRevisionCounter = 0L;
        SpatialCloakField.clear();

        if (hadSystems) {
            sync();
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            syncToPlayer(player);
        }
    }

    private record CoreRegistration(
            UUID homeSubLevelId,
            Map<UUID, Integer> memberBlockCounts
    ) {
        private CoreRegistration {
            memberBlockCounts = Map.copyOf(memberBlockCounts);
        }
    }

    private static final class CloakSystem {

        private final UUID systemId;
        private final Set<CloakingCoreBlockEntity> cores = new LinkedHashSet<>();
        private final Map<UUID, Integer> memberBlockCounts = new LinkedHashMap<>();
        /** Additional load and optional inheritance, never graph membership. */
        private Map<UUID, SpatialCloakField.Intersection> spatialIntersections = Map.of();
        private SpatialCloakField.ScanCache spatialScanCache =
                new SpatialCloakField.ScanCache();
        private long lastSpatialScanTick = Long.MIN_VALUE;

        private CloakingCoreSettings settings;
        private CloakingCoreSettings publishedSettings;
        private ItemStack linkFrequencyFirst = ItemStack.EMPTY;
        private ItemStack linkFrequencySecond = ItemStack.EMPTY;

        private int blockCount;
        private int totalPotentialCapacity;
        private int totalOperationalCapacity;
        private boolean capacitySatisfied;
        private float effectiveRpm;
        private float efficiencyFactor = 1.0F;
        private float transitionDurationSeconds;
        private double fullyCloakedDistance;
        private int publishedBlockCount = -1;
        private float publishedTransitionDurationSeconds = -1.0F;
        private double publishedFullyCloakedDistance = -1.0;

        private CloakSystem(UUID systemId) {
            this.systemId = systemId;
        }
    }

    /** Small UUID disjoint-set used only when the once-per-second graph changes. */
    private static final class UnionFind {

        private final Map<UUID, UUID> parent = new HashMap<>();

        private void add(UUID id) {
            parent.putIfAbsent(id, id);
        }

        private UUID find(UUID id) {
            add(id);
            UUID p = parent.get(id);
            if (!p.equals(id)) {
                p = find(p);
                parent.put(id, p);
            }
            return p;
        }

        private void union(UUID a, UUID b) {
            UUID rootA = find(a);
            UUID rootB = find(b);

            if (rootA.equals(rootB)) {
                return;
            }

            UUID first = rootA.toString().compareTo(rootB.toString()) <= 0
                    ? rootA
                    : rootB;
            UUID second = first.equals(rootA) ? rootB : rootA;
            parent.put(second, first);
        }
    }
}
