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
 * This deliberately keeps the first multi-core implementation simple: any
 * core may change the shared settings. Redstone-control arbitration can be
 * refined later once the pooled capacity/stress behaviour has been tuned.
 */
@EventBusSubscriber(modid = AeroCloakingCore.MOD_ID)
public final class CloakingManager {

    /**
     * The global transition-duration config is treated as the duration at this
     * reference speed. Faster systems cloak more quickly with diminishing
     * returns; slower operational systems cloak more slowly.
     */
    public static final float CLOAK_SPEED_REFERENCE_RPM = 64.0F;

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

        double operationalRpmWeightedSum = 0.0;
        double potentialRpmWeightedSum = 0.0;

        for (CloakingCoreBlockEntity core : system.cores.values()) {
            blockCount = Math.max(blockCount, core.getSubLevelBlockCount());

            int potentialCapacity = core.getPotentialCloakCapacityBlocks();
            int operationalCapacity = core.getOperationalCloakCapacityBlocks();

            totalPotentialCapacity += potentialCapacity;
            totalOperationalCapacity += operationalCapacity;

            float theoreticalRpm = core.getTheoreticalRpm();
            float runningRpm = core.getCurrentRpm();

            if (potentialCapacity > 0) {
                potentialRpmWeightedSum += theoreticalRpm * potentialCapacity;
            }

            if (operationalCapacity > 0) {
                operationalRpmWeightedSum += runningRpm * operationalCapacity;
            }
        }

        int coreCount = system.cores.size();

        /*
         * Stress load is distributed by potential cloak capacity. A faster core
         * therefore takes a larger share of the ship and pays a larger variable
         * stress cost. If every core is below minimum RPM, divide the load evenly
         * so a large ship still presents a meaningful spin-up load.
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

            core.setAssignedBlockLoadFromManager(assignedBlocks);
        }

        boolean capacitySatisfied = blockCount > 0
                && totalOperationalCapacity >= blockCount;

        float effectiveRpm;
        if (totalOperationalCapacity > 0) {
            effectiveRpm = (float) (
                    operationalRpmWeightedSum / totalOperationalCapacity
            );
        } else if (totalPotentialCapacity > 0) {
            effectiveRpm = (float) (
                    potentialRpmWeightedSum / totalPotentialCapacity
            );
        } else {
            effectiveRpm = 0.0F;
        }

        float transitionDuration = calculateTransitionDurationSeconds(
                CloakingServerSettings.fromConfig().transitionDurationSeconds(),
                effectiveRpm
        );

        system.blockCount = blockCount;
        system.totalPotentialCapacity = totalPotentialCapacity;
        system.totalOperationalCapacity = totalOperationalCapacity;
        system.effectiveRpm = effectiveRpm;
        system.transitionDurationSeconds = transitionDuration;
        system.capacitySatisfied = capacitySatisfied;

        for (CloakingCoreBlockEntity core : system.cores.values()) {
            core.applySystemStatsFromManager(
                    coreCount,
                    blockCount,
                    totalPotentialCapacity,
                    totalOperationalCapacity,
                    capacitySatisfied,
                    effectiveRpm,
                    transitionDuration
            );
        }

        CloakingCoreSettings effectiveSettings = capacitySatisfied
                ? system.settings
                : system.settings.withCloakStrength(0.0F);

        boolean publishedChanged =
                !effectiveSettings.equals(system.publishedSettings)
                        || Math.abs(
                                transitionDuration
                                        - system.publishedTransitionDurationSeconds
                        ) > 0.05F;

        system.publishedSettings = effectiveSettings;
        system.publishedTransitionDurationSeconds = transitionDuration;

        if (publishedChanged) {
            sync();
        }
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
                        calculateTransitionDurationSeconds(
                                serverSettings.transitionDurationSeconds(),
                                system.effectiveRpm
                        )
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
        private float transitionDurationSeconds;
        private float publishedTransitionDurationSeconds = -1.0F;

        private CloakSystem(UUID subLevelId) {
            this.subLevelId = subLevelId;
        }
    }
}
