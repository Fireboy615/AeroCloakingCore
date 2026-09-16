package net.fireboy.aerocloakingcore.cloak;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.fireboy.aerocloakingcore.block.entity.CloakingCoreBlockEntity;
import net.fireboy.aerocloakingcore.network.CloakingSyncPayload;

import net.minecraft.server.level.ServerPlayer;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side coordinator for all Cloaking Cores attached to Sable sublevels.
 *
 * A sublevel owns one logical cloak system. Every core on that sublevel:
 * - contributes cloak capacity based on its RPM,
 * - receives a share of the ship's block load for stress calculations, and
 * - mirrors the same cloak strength/render mode settings.
 *
 * Any core may currently change the shared cloak settings. Redstone-control
 * arbitration is still intentionally left separate from the capacity/stress
 * model so the mechanical balance can be tested first.
 */
@EventBusSubscriber(modid = AeroCloakingCore.MOD_ID)
public final class CloakingManager {

    /**
     * The global transition-duration config is treated as the duration at this
     * reference speed. Faster systems cloak more quickly with diminishing
     * returns; slower operational systems cloak more slowly.
     */
    public static final float CLOAK_SPEED_REFERENCE_RPM = 64.0F;

    /** Ship-size reveal scaling is referenced to a 256-block sublevel. */
    public static final float REVEAL_REFERENCE_BLOCKS = 256.0F;

    /** Extra concealment starts only once the average system RPM exceeds 128. */
    public static final float REVEAL_BONUS_START_RPM = 128.0F;

    /** 256 RPM is treated as the practical maximum concealment-quality speed. */
    public static final float REVEAL_BONUS_MAX_RPM = 256.0F;

    /**
     * At 256 average RPM, retain 10% of the size-based reveal gap instead of
     * collapsing it all the way onto the fully-visible distance.
     */
    public static final float MIN_REVEAL_GAP_MULTIPLIER = 0.10F;

    private static final Map<UUID, CloakSystem> SYSTEMS = new LinkedHashMap<>();

    private CloakingManager() {
    }

    // ---------------------------------------------------------------------
    // CORE REGISTRATION / SHARED SETTINGS
    // ---------------------------------------------------------------------

    public static void updateCore(
            UUID subLevelId,
            CloakingCoreBlockEntity core
    ) {
        if (subLevelId == null || core == null || core.isRemoved()) {
            return;
        }

        CloakSystem system = SYSTEMS.computeIfAbsent(
                subLevelId,
                CloakSystem::new
        );

        long key = core.getBlockPos().asLong();
        boolean newlyAdded = system.cores.put(key, core) == null;

        if (system.settings == null) {
            system.settings = core.getSettings().normalized();
        } else if (newlyAdded) {
            // A newly-added auxiliary joins the existing ship-wide settings.
            core.applySystemSettingsFromManager(system.settings);
        }

        recompute(system);
    }

    public static void setSystemSettings(
            UUID subLevelId,
            CloakingCoreBlockEntity source,
            CloakingCoreSettings settings
    ) {
        if (subLevelId == null || source == null || settings == null) {
            return;
        }

        CloakSystem system = SYSTEMS.computeIfAbsent(
                subLevelId,
                CloakSystem::new
        );

        system.cores.put(source.getBlockPos().asLong(), source);

        CloakingCoreSettings normalized = settings.normalized();
        boolean changed = !normalized.equals(system.settings);
        system.settings = normalized;

        if (changed) {
            for (CloakingCoreBlockEntity core : system.cores.values()) {
                if (isLive(core)) {
                    core.applySystemSettingsFromManager(normalized);
                }
            }
        }

        recompute(system);
    }

    public static void unregisterCore(
            UUID subLevelId,
            CloakingCoreBlockEntity core
    ) {
        if (subLevelId == null || core == null) {
            return;
        }

        CloakSystem system = SYSTEMS.get(subLevelId);
        if (system == null) {
            return;
        }

        long key = core.getBlockPos().asLong();
        CloakingCoreBlockEntity registered = system.cores.get(key);

        if (registered == core) {
            system.cores.remove(key);
        }

        pruneDeadCores(system);

        if (system.cores.isEmpty()) {
            boolean hadPublishedState = system.publishedSettings != null;
            SYSTEMS.remove(subLevelId);

            if (hadPublishedState) {
                sync();
            }
            return;
        }

        recompute(system);
    }

    // ---------------------------------------------------------------------
    // SYSTEM CALCULATION
    // ---------------------------------------------------------------------

    private static void recompute(CloakSystem system) {
        pruneDeadCores(system);

        if (system.cores.isEmpty()) {
            SYSTEMS.remove(system.subLevelId);
            sync();
            return;
        }

        if (system.settings == null) {
            system.settings = system.cores.values()
                    .iterator()
                    .next()
                    .getSettings()
                    .normalized();
        }

        int blockCount = 0;
        int totalPotentialCapacity = 0;
        int totalOperationalCapacity = 0;
        int potentialContributingCoreCount = 0;
        int operationalContributingCoreCount = 0;

        double operationalRpmSum = 0.0;
        double potentialRpmSum = 0.0;

        for (CloakingCoreBlockEntity core : system.cores.values()) {
            blockCount = Math.max(blockCount, core.getSubLevelBlockCount());

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

        /*
         * Multi-core SU efficiency is intentionally based on powered/potential
         * contributors, not just blocks physically placed on the ship. A dead
         * 0-RPM core therefore cannot be spammed for a free efficiency bonus.
         *
         * 1 core  = 100%
         * 2 cores = 95%
         * 3 cores = 90%
         * 4 cores = 85%
         * 5 cores = 80%
         * 6+      = 75%
         */
        float efficiencyMultiplier = calculateEfficiencyMultiplier(
                potentialContributingCoreCount
        );

        /*
         * Load is distributed in proportion to each core's capacity. Because
         * capacity itself is linear up to 128 RPM, faster cores naturally take
         * more of the ship load until their 256-block cap is reached.
         *
         * If capacity is insufficient, assigned load may exceed a core's own
         * capacity. That is deliberate: an oversized ship still presents the
         * full attempted cloak stress instead of becoming cheaper just because
         * it cannot currently be cloaked.
         */
        for (CloakingCoreBlockEntity core : system.cores.values()) {
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
                    efficiencyMultiplier
            );
        }

        boolean capacitySatisfied = blockCount > 0
                && totalOperationalCapacity >= blockCount;

        /*
         * Cloak quality uses the plain arithmetic mean RPM of every operational
         * contributing core. This deliberately prevents one 256-RPM core from
         * granting high-RPM cloak bonuses to a bank of slow 32-RPM auxiliaries.
         */
        float effectiveRpm;
        if (operationalContributingCoreCount > 0) {
            effectiveRpm = (float) (
                    operationalRpmSum / operationalContributingCoreCount
            );
        } else if (potentialContributingCoreCount > 0) {
            // Keep useful diagnostics while the network is stalled/overstressed.
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
                serverSettings.fullyCloakedDistance(),
                blockCount,
                effectiveRpm
        );

        system.blockCount = blockCount;
        system.totalPotentialCapacity = totalPotentialCapacity;
        system.totalOperationalCapacity = totalOperationalCapacity;
        system.effectiveRpm = effectiveRpm;
        system.efficiencyMultiplier = efficiencyMultiplier;
        system.transitionDurationSeconds = transitionDuration;
        system.fullyCloakedDistance = fullyCloakedDistance;
        system.capacitySatisfied = capacitySatisfied;

        for (CloakingCoreBlockEntity core : system.cores.values()) {
            core.applySystemStatsFromManager(
                    coreCount,
                    blockCount,
                    totalPotentialCapacity,
                    totalOperationalCapacity,
                    capacitySatisfied,
                    effectiveRpm,
                    efficiencyMultiplier,
                    transitionDuration,
                    fullyCloakedDistance
            );
        }

        CloakingCoreSettings effectiveSettings = capacitySatisfied
                ? system.settings
                : system.settings.withCloakStrength(0.0F);

        boolean publishedChanged =
                !effectiveSettings.equals(system.publishedSettings)
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

        if (publishedChanged) {
            sync();
        }
    }

    public static float calculateEfficiencyMultiplier(int contributingCoreCount) {
        if (contributingCoreCount <= 1) {
            return 1.0F;
        }

        return Math.max(
                0.75F,
                1.0F - 0.05F * (contributingCoreCount - 1)
        );
    }

    /**
     * Calculates the distance where a fully cloaked ship begins to reveal.
     *
     * The configured fully-visible distance remains fixed. The configured gap
     * between fully-visible and fully-cloaked distances is the 256-block
     * reference gap at <=128 system RPM. Larger ships expand that gap by sqrt
     * of block count. Above 128 average RPM the gap is linearly compressed,
     * reaching only 10% of its size-scaled value at 256 RPM.
     */
    public static double calculateFullyCloakedDistance(
            double configuredFullyVisibleDistance,
            double configuredFullyCloakedDistance,
            int blockCount,
            float effectiveRpm
    ) {
        double visible = Math.max(0.0, configuredFullyVisibleDistance);
        double configuredGap = Math.max(
                0.0,
                configuredFullyCloakedDistance - visible
        );

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

        // Create normally tops out around 256 RPM, but clamp the effect anyway
        // so unusual modded speeds cannot make transitions effectively instant.
        multiplier = Math.max(0.35F, Math.min(2.0F, multiplier));
        return base * multiplier;
    }

    private static void pruneDeadCores(CloakSystem system) {
        Iterator<Map.Entry<Long, CloakingCoreBlockEntity>> iterator =
                system.cores.entrySet().iterator();

        while (iterator.hasNext()) {
            CloakingCoreBlockEntity core = iterator.next().getValue();
            if (!isLive(core)) {
                iterator.remove();
            }
        }
    }

    private static boolean isLive(CloakingCoreBlockEntity core) {
        return core != null
                && !core.isRemoved()
                && core.getLevel() != null
                && !core.getLevel().isClientSide;
    }

    // ---------------------------------------------------------------------
    // CLIENT SYNC
    // ---------------------------------------------------------------------

    private static CloakingSyncPayload createPayload() {
        CloakingServerSettings serverSettings = CloakingServerSettings.fromConfig();

        List<CloakingSyncPayload.Entry> entries = SYSTEMS
                .values()
                .stream()
                .filter(system -> system.publishedSettings != null)
                .map(system -> new CloakingSyncPayload.Entry(
                        system.subLevelId,
                        system.publishedSettings,
                        system.publishedTransitionDurationSeconds,
                        system.publishedFullyCloakedDistance
                ))
                .toList();

        return new CloakingSyncPayload(
                serverSettings,
                entries
        );
    }

    public static void sync() {
        PacketDistributor.sendToAllPlayers(createPayload());
    }

    public static void syncToPlayer(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, createPayload());
    }

    // ---------------------------------------------------------------------
    // LEGACY / QUERY HELPERS
    // ---------------------------------------------------------------------

    public static boolean isCloaked(UUID subLevelId) {
        CloakSystem system = SYSTEMS.get(subLevelId);
        return system != null
                && system.publishedSettings != null
                && system.publishedSettings.cloakStrength() > 0.0F;
    }

    public static Map<UUID, CloakingCoreSettings> getCloakedSubLevels() {
        Map<UUID, CloakingCoreSettings> result = new HashMap<>();

        for (CloakSystem system : SYSTEMS.values()) {
            if (system.publishedSettings != null) {
                result.put(system.subLevelId, system.publishedSettings);
            }
        }

        return Collections.unmodifiableMap(result);
    }

    public static void clear() {
        boolean hadSystems = !SYSTEMS.isEmpty();
        SYSTEMS.clear();

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

    private static final class CloakSystem {

        private final UUID subLevelId;
        private final Map<Long, CloakingCoreBlockEntity> cores =
                new LinkedHashMap<>();

        private CloakingCoreSettings settings;
        private CloakingCoreSettings publishedSettings;

        private int blockCount;
        private int totalPotentialCapacity;
        private int totalOperationalCapacity;
        private boolean capacitySatisfied;
        private float effectiveRpm;
        private float efficiencyMultiplier = 1.0F;
        private float transitionDurationSeconds;
        private double fullyCloakedDistance;
        private int publishedBlockCount = -1;
        private float publishedTransitionDurationSeconds = -1.0F;
        private double publishedFullyCloakedDistance = -1.0;

        private CloakSystem(UUID subLevelId) {
            this.subLevelId = subLevelId;
        }
    }
}
