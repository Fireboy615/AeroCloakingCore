package net.fireboy.aerocloakingcore.block.entity;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;

import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.fireboy.aerocloakingcore.cloak.CloakingCoreSettings;
import net.fireboy.aerocloakingcore.cloak.CloakingManager;
import net.fireboy.aerocloakingcore.menu.CloakingCoreMenu;
import net.fireboy.aerocloakingcore.redstone.CloakingReceivingLink;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Create kinetic block entity for the Cloaking Core drive half.
 *
 * Finalized multi-core balance model:
 * - hard minimum operating speed: 32 RPM
 * - capacity is linear: 2 blocks per RPM
 * - capacity stops increasing at 128 RPM / 256 blocks per core
 * - total ship capacity is the sum of every powered core's capacity
 * - ship block load is shared in proportion to each core's capacity
 * - each additional contributing core improves system SU efficiency by 5%,
 *   down to a 75% multiplier at 6+ cores
 * - variable stress scales with cloakStrength^1.2
 * - actual SU is Create-style stress impact multiplied by this core's RPM
 * - there is intentionally no hard SU ceiling; pushing a core above 128 RPM
 *   buys cloak quality, but becomes expensive very quickly
 */
public class CloakingCoreBlockEntity extends KineticBlockEntity
        implements MenuProvider {

    private static final String TAG_LINK_FIRST = "RedstoneLinkFrequencyFirst";
    private static final String TAG_LINK_SECOND = "RedstoneLinkFrequencySecond";
    private static final String TAG_MANUAL_OVERRIDE = "ManualOverride";
    private static final String TAG_LAST_REDSTONE_SIGNAL = "LastRedstoneSignal";
    private static final String TAG_SUBLEVEL_BLOCK_COUNT = "SubLevelBlockCount";

    /** Exact minimum mechanical speed required by the Cloaking Core. */
    public static final float MINIMUM_REQUIRED_RPM = 32.0F;

    /** RPM where ship-size capacity stops increasing. */
    public static final float CAPACITY_MAX_RPM = 128.0F;

    /** Every RPM contributes exactly two blocks of cloak capacity. */
    public static final float CAPACITY_BLOCKS_PER_RPM = 2.0F;

    /** Hard per-core cloak-capacity ceiling reached at 128 RPM. */
    public static final int MAX_CAPACITY_BLOCKS = 256;

    /**
     * Fixed Create stress impact in SU/RPM while the core is spinning.
     * Actual fixed SU cost therefore rises naturally with RPM.
     */
    public static final float BASE_STRESS_IMPACT = 4.0F;

    /** Additional stress impact in SU/RPM per assigned ship block. */
    public static final float STRESS_IMPACT_PER_BLOCK = 0.09F;

    /** Exponent used for analog cloak-strength stress scaling. */
    public static final float CLOAK_STRENGTH_STRESS_EXPONENT = 1.2F;

    /** Re-scan the Sable sublevel and its block count once per second. */
    private static final int SUBLEVEL_CHECK_INTERVAL_TICKS = 20;

    private CloakingCoreSettings settings = CloakingCoreSettings.DEFAULT;

    /**
     * True while UI/shift-click cloak strength is overriding Redstone Link.
     * The override is released only when a new link signal is observed.
     */
    private boolean manualOverride = true;

    /** Last observed Create Redstone Link signal, 0-15. */
    private int lastRedstoneSignal = 0;

    private ItemStack linkFrequencyFirst = ItemStack.EMPTY;
    private ItemStack linkFrequencySecond = ItemStack.EMPTY;

    private final CloakingReceivingLink receivingLink =
            new CloakingReceivingLink(this);

    private UUID cloakedSubLevelId = null;
    private int subLevelBlockCount = 0;

    /** Ship block load currently assigned to this core by CloakingManager. */
    private float assignedBlockLoad = 0.0F;

    /** Cached ship-wide values for UI/status. */
    private int systemCoreCount = 0;
    private int systemBlockCount = 0;
    private int systemPotentialCapacity = 0;
    private int systemOperationalCapacity = 0;
    private boolean systemCapacitySatisfied = false;
    private float systemEffectiveRpm = 0.0F;
    private float systemEfficiencyMultiplier = 1.0F;
    private float systemTransitionDurationSeconds = 0.0F;
    private double systemFullyCloakedDistance = 0.0;

    // Starts at 19 so a newly loaded core scans on its first server tick.
    private int checkTimer = SUBLEVEL_CHECK_INTERVAL_TICKS - 1;

    public CloakingCoreBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CLOAKING_CORE.get(), pos, state);
    }

    // ---------------------------------------------------------------------
    // CREATE KINETICS / CAPACITY / STRESS
    // ---------------------------------------------------------------------

    /** The Cloaking Core always requires a real 32 RPM minimum. */
    public float getMinimumRequiredRpm() {
        return MINIMUM_REQUIRED_RPM;
    }

    /**
     * Create's built-in speed tiers are configurable and do not represent an
     * exact 32 RPM threshold. Override the block-entity check so gameplay is
     * deterministic regardless of Create's MEDIUM/FAST server settings.
     */
    @Override
    public boolean isSpeedRequirementFulfilled() {
        return getCurrentRpm() + 0.0001F >= MINIMUM_REQUIRED_RPM;
    }

    public float getCurrentRpm() {
        return Math.abs(getSpeed());
    }

    public float getTheoreticalRpm() {
        return Math.abs(getTheoreticalSpeed());
    }

    /** Exact non-air block count observed on this core's Sable sublevel. */
    public int getSubLevelBlockCount() {
        return subLevelBlockCount;
    }

    /**
     * Capacity this core would contribute at its requested/theoretical speed.
     * This remains non-zero while its Create network is overstressed so stress
     * demand does not collapse and oscillate on/off.
     */
    public int getPotentialCloakCapacityBlocks() {
        return calculateCapacityForRpm(getTheoreticalRpm());
    }

    /** Capacity currently available to the cloak system. */
    public int getOperationalCloakCapacityBlocks() {
        if (!isOperational()) {
            return 0;
        }

        return calculateCapacityForRpm(getCurrentRpm());
    }

    public int calculateCapacityForRpm(float rpm) {
        if (rpm + 0.0001F < MINIMUM_REQUIRED_RPM) {
            return 0;
        }

        float capacityRpm = Math.min(CAPACITY_MAX_RPM, rpm);
        int capacity = (int) Math.floor(
                capacityRpm * CAPACITY_BLOCKS_PER_RPM
        );

        return Math.min(MAX_CAPACITY_BLOCKS, Math.max(0, capacity));
    }

    public float getAssignedBlockLoad() {
        return assignedBlockLoad;
    }

    /**
     * Current Create stress impact in SU/RPM.
     *
     * Only the variable ship-load portion is affected by cloak strength.
     * A ^1.2 curve keeps partial cloak meaningfully cheaper without making
     * mid-strength values almost free.
     */
    public float getCurrentStressImpact() {
        float strength = Math.max(0.0F, Math.min(1.0F, settings.cloakStrength()));
        float cloakLoadFactor = (float) Math.pow(
                strength,
                CLOAK_STRENGTH_STRESS_EXPONENT
        );

        float rawImpact = BASE_STRESS_IMPACT
                + STRESS_IMPACT_PER_BLOCK
                * assignedBlockLoad
                * cloakLoadFactor;

        return rawImpact * systemEfficiencyMultiplier;
    }

    /**
     * Current theoretical SU demand. Theoretical RPM is intentional so an
     * overstressed network still reports the demand that caused the stall.
     */
    public float getCurrentRequiredSu() {
        return getCurrentStressImpact() * getTheoreticalRpm();
    }

    /**
     * Create consumes stress impact in SU/RPM and multiplies it by network RPM.
     * This directly gives us the intended "fast compact core = expensive" tradeoff.
     */
    @Override
    public float calculateStressApplied() {
        float impact = getCurrentStressImpact();
        this.lastStressApplied = impact;
        return impact;
    }

    /**
     * RPM changes alter the dynamic SU/RPM coefficient, so explicitly mark the
     * network dirty and let Create recalculate on the next kinetic tick.
     */
    @Override
    public void onSpeedChanged(float previousSpeed) {
        super.onSpeedChanged(previousSpeed);

        if (level != null && !level.isClientSide) {
            refreshDynamicStress();
        }
    }

    /**
     * Pushes this core's current dynamic SU/RPM coefficient into Create's
     * KineticNetwork. Create caches member stress coefficients when a kinetic
     * member joins the network, so networkDirty by itself cannot update a
     * load-dependent consumer like the Cloaking Core.
     */
    private void refreshDynamicStress() {
        if (level == null || level.isClientSide) {
            return;
        }

        float impact = calculateStressApplied();

        if (hasNetwork()) {
            getOrCreateNetwork().updateStressFor(this, impact);
        } else {
            // Keep the normal Create refresh path available during initial
            // attachment/spin-up before a network exists.
            networkDirty = true;
        }
    }

    /**
     * Individual-core operating state. Ship-wide capacity is checked separately
     * by CloakingManager.
     */
    public boolean isOperational() {
        return !isOverStressed() && isSpeedRequirementFulfilled();
    }

    // ---------------------------------------------------------------------
    // SYSTEM STATS / MANAGER CALLBACKS
    // ---------------------------------------------------------------------

    public @Nullable UUID getCloakedSubLevelId() {
        return cloakedSubLevelId;
    }

    public int getSystemCoreCount() {
        return systemCoreCount;
    }

    public int getSystemBlockCount() {
        return systemBlockCount;
    }

    public int getSystemPotentialCapacity() {
        return systemPotentialCapacity;
    }

    public int getSystemOperationalCapacity() {
        return systemOperationalCapacity;
    }

    public boolean isSystemCapacitySatisfied() {
        return systemCapacitySatisfied;
    }

    public float getSystemEffectiveRpm() {
        return systemEffectiveRpm;
    }

    public float getSystemTransitionDurationSeconds() {
        return systemTransitionDurationSeconds;
    }

    public float getSystemEfficiencyMultiplier() {
        return systemEfficiencyMultiplier;
    }

    public double getSystemFullyCloakedDistance() {
        return systemFullyCloakedDistance;
    }

    /**
     * Status codes used by the menu's synced ContainerData.
     * 0 = no Sable sublevel
     * 1 = this core below minimum RPM
     * 2 = this core's Create network overstressed
     * 3 = ship-wide cloak capacity insufficient
     * 4 = ready
     * 5 = theoretical input is fast enough, but this core is not actually running
     */
    public int getSystemStatusCode() {
        if (cloakedSubLevelId == null) {
            return 0;
        }

        if (isOverStressed()) {
            return 2;
        }

        if (!isSpeedRequirementFulfilled()) {
            if (getTheoreticalRpm() + 0.0001F >= MINIMUM_REQUIRED_RPM) {
                return 5;
            }

            return 1;
        }

        if (!systemCapacitySatisfied) {
            return 3;
        }

        return 4;
    }

    /** Called only by CloakingManager on the server thread. */
    public void applySystemLoadFromManager(
            float assignedBlockLoad,
            float efficiencyMultiplier
    ) {
        float normalizedLoad = Math.max(0.0F, assignedBlockLoad);
        float normalizedEfficiency = Math.max(
                0.0F,
                Math.min(1.0F, efficiencyMultiplier)
        );

        boolean loadChanged =
                Math.abs(this.assignedBlockLoad - normalizedLoad) >= 0.01F;
        boolean efficiencyChanged =
                Math.abs(this.systemEfficiencyMultiplier - normalizedEfficiency)
                        >= 0.0001F;

        if (!loadChanged && !efficiencyChanged) {
            return;
        }

        this.assignedBlockLoad = normalizedLoad;
        this.systemEfficiencyMultiplier = normalizedEfficiency;

        // Create caches member stress coefficients. Push the new value into the
        // live network whenever block load or multi-core efficiency changes.
        refreshDynamicStress();
        setChanged();
    }

    /** Compatibility helper retained for older manager call sites. */
    public void setAssignedBlockLoadFromManager(float assignedBlockLoad) {
        applySystemLoadFromManager(
                assignedBlockLoad,
                systemEfficiencyMultiplier
        );
    }

    /** Called only by CloakingManager on the server thread. */
    public void applySystemSettingsFromManager(CloakingCoreSettings settings) {
        CloakingCoreSettings normalized = settings.normalized();

        if (normalized.equals(this.settings)) {
            return;
        }

        boolean strengthChanged = Math.abs(
                normalized.cloakStrength() - this.settings.cloakStrength()
        ) > 0.0001F;

        this.settings = normalized;

        if (strengthChanged) {
            refreshDynamicStress();
        }

        setChanged();
        sendData();
    }

    /** Called only by CloakingManager on the server thread. */
    public void applySystemStatsFromManager(
            int coreCount,
            int blockCount,
            int potentialCapacity,
            int operationalCapacity,
            boolean capacitySatisfied,
            float effectiveRpm,
            float efficiencyMultiplier,
            float transitionDurationSeconds,
            double fullyCloakedDistance
    ) {
        this.systemCoreCount = Math.max(0, coreCount);
        this.systemBlockCount = Math.max(0, blockCount);
        this.systemPotentialCapacity = Math.max(0, potentialCapacity);
        this.systemOperationalCapacity = Math.max(0, operationalCapacity);
        this.systemCapacitySatisfied = capacitySatisfied;
        this.systemEffectiveRpm = Math.max(0.0F, effectiveRpm);
        this.systemEfficiencyMultiplier = Math.max(
                0.0F,
                Math.min(1.0F, efficiencyMultiplier)
        );
        this.systemTransitionDurationSeconds = Math.max(
                0.0F,
                transitionDurationSeconds
        );
        this.systemFullyCloakedDistance = Math.max(
                0.0,
                fullyCloakedDistance
        );
    }

    // ---------------------------------------------------------------------
    // EXISTING CORE SETTINGS / REDSTONE LINK
    // ---------------------------------------------------------------------

    public CloakingCoreSettings getSettings() {
        return settings;
    }

    public boolean hasManualOverride() {
        return manualOverride;
    }

    public int getLastRedstoneSignal() {
        return lastRedstoneSignal;
    }

    public float getCloakStrength() {
        return settings.cloakStrength();
    }

    public CloakRenderMode getRenderMode() {
        return settings.renderMode();
    }

    public ItemStack getLinkFrequency(boolean first) {
        return (first ? linkFrequencyFirst : linkFrequencySecond).copy();
    }

    /**
     * Changes one of the two Create Redstone Link frequencies.
     * Frequency items are ghost values: only a one-count copy is stored.
     */
    public void setLinkFrequency(boolean first, ItemStack stack) {
        ItemStack normalized = stack.isEmpty()
                ? ItemStack.EMPTY
                : stack.copyWithCount(1);

        ItemStack previous = first
                ? linkFrequencyFirst
                : linkFrequencySecond;

        if (ItemStack.isSameItemSameComponents(previous, normalized)
                && previous.getCount() == normalized.getCount()) {
            return;
        }

        if (first) {
            linkFrequencyFirst = normalized;
        } else {
            linkFrequencySecond = normalized;
        }

        if (level != null && !level.isClientSide) {
            receivingLink.removeFromNetwork(level);
            receivingLink.setFrequencies(
                    linkFrequencyFirst,
                    linkFrequencySecond
            );
            receivingLink.addToNetwork(level);

            if (receivingLink.isConfigured()) {
                onRedstoneLinkSignalUpdated(
                        receivingLink.readNetwork(level)
                );
            }
        }

        setChanged();
    }

    /** Shift-right-click binary manual override. */
    public float toggleManualCloak() {
        float target = settings.cloakStrength() >= 0.5F
                ? 0.0F
                : 1.0F;

        setManualCloakStrength(target);
        return target;
    }

    /** UI slider manual override. */
    public void setManualCloakStrength(float strength) {
        manualOverride = true;
        setEffectiveCloakStrength(strength);
        setChanged();
    }

    public void setRenderMode(CloakRenderMode renderMode) {
        settings = settings.withRenderMode(renderMode);
        publishSettingsToSystem();
        setChanged();
    }

    /**
     * Called when Create Redstone Link input changes.
     * Signal 0-15 maps linearly to cloak strength 0.0-1.0.
     */
    public void onRedstoneLinkSignalUpdated(int signal) {
        lastRedstoneSignal = Math.max(0, Math.min(15, signal));
        manualOverride = false;

        setEffectiveCloakStrength(lastRedstoneSignal / 15.0F);
        setChanged();
    }

    private void setEffectiveCloakStrength(float strength) {
        settings = settings.withCloakStrength(strength);
        refreshDynamicStress();
        publishSettingsToSystem();
    }

    private void publishSettingsToSystem() {
        if (level == null || level.isClientSide || cloakedSubLevelId == null) {
            return;
        }

        CloakingManager.setSystemSettings(
                cloakedSubLevelId,
                this,
                settings
        );
    }

    // ---------------------------------------------------------------------
    // CREATE SMART BLOCK ENTITY NBT
    // ---------------------------------------------------------------------

    @Override
    protected void write(
            CompoundTag tag,
            HolderLookup.Provider registries,
            boolean clientPacket
    ) {
        super.write(tag, registries, clientPacket);

        settings.save(tag);
        tag.putBoolean(TAG_MANUAL_OVERRIDE, manualOverride);
        tag.putInt(TAG_LAST_REDSTONE_SIGNAL, lastRedstoneSignal);
        tag.putInt(TAG_SUBLEVEL_BLOCK_COUNT, subLevelBlockCount);

        tag.put(
                TAG_LINK_FIRST,
                linkFrequencyFirst.saveOptional(registries)
        );
        tag.put(
                TAG_LINK_SECOND,
                linkFrequencySecond.saveOptional(registries)
        );
    }

    @Override
    protected void read(
            CompoundTag tag,
            HolderLookup.Provider registries,
            boolean clientPacket
    ) {
        super.read(tag, registries, clientPacket);

        settings = CloakingCoreSettings.load(tag);
        manualOverride = !tag.contains(TAG_MANUAL_OVERRIDE)
                || tag.getBoolean(TAG_MANUAL_OVERRIDE);
        lastRedstoneSignal = tag.contains(TAG_LAST_REDSTONE_SIGNAL)
                ? Math.max(0, Math.min(15, tag.getInt(TAG_LAST_REDSTONE_SIGNAL)))
                : 0;

        subLevelBlockCount = Math.max(
                0,
                tag.getInt(TAG_SUBLEVEL_BLOCK_COUNT)
        );

        linkFrequencyFirst = tag.contains(TAG_LINK_FIRST)
                ? ItemStack.parseOptional(
                        registries,
                        tag.getCompound(TAG_LINK_FIRST)
                )
                : ItemStack.EMPTY;

        linkFrequencySecond = tag.contains(TAG_LINK_SECOND)
                ? ItemStack.parseOptional(
                        registries,
                        tag.getCompound(TAG_LINK_SECOND)
                )
                : ItemStack.EMPTY;

        receivingLink.setFrequencies(
                linkFrequencyFirst,
                linkFrequencySecond
        );

        if (!clientPacket) {
            cloakedSubLevelId = null;
            assignedBlockLoad = 0.0F;
            systemCoreCount = 0;
            systemBlockCount = 0;
            systemPotentialCapacity = 0;
            systemOperationalCapacity = 0;
            systemCapacitySatisfied = false;
            systemEffectiveRpm = 0.0F;
            systemEfficiencyMultiplier = 1.0F;
            systemTransitionDurationSeconds = 0.0F;
            systemFullyCloakedDistance = 0.0;
            checkTimer = SUBLEVEL_CHECK_INTERVAL_TICKS - 1;
        }
    }

    // ---------------------------------------------------------------------
    // TICKING
    // ---------------------------------------------------------------------

    @Override
    public void tick() {
        // Handles Create kinetic attachment, network updates, stress state,
        // speed, effects, behaviours, etc.
        super.tick();

        if (level == null || level.isClientSide) {
            return;
        }

        serverTick();
    }

    private void serverTick() {
        receivingLink.setFrequencies(
                linkFrequencyFirst,
                linkFrequencySecond
        );
        receivingLink.addToNetwork(level);

        if (receivingLink.isConfigured()) {
            int signal = receivingLink.readNetwork(level);

            // Manual override remains until the network signal actually changes.
            if (signal != lastRedstoneSignal) {
                onRedstoneLinkSignalUpdated(signal);
            }
        }

        checkTimer++;
        if (checkTimer >= SUBLEVEL_CHECK_INTERVAL_TICKS) {
            checkTimer = 0;
            checkSubLevel();
        }

        // RPM, overstress state and weighted load can all change between the
        // once-per-second block scans, so refresh the lightweight system math
        // every server tick.
        if (cloakedSubLevelId != null) {
            CloakingManager.updateCore(cloakedSubLevelId, this);
        }
    }

    // ---------------------------------------------------------------------
    // SABLE SUBLEVEL + DYNAMIC STRESS
    // ---------------------------------------------------------------------

    private void checkSubLevel() {
        if (level == null || level.isClientSide) {
            return;
        }

        SubLevel subLevel = Sable.HELPER.getContaining(this);

        UUID newSubLevelId = subLevel != null
                ? subLevel.getUniqueId()
                : null;

        boolean subLevelChanged =
                !Objects.equals(cloakedSubLevelId, newSubLevelId);

        if (subLevelChanged && cloakedSubLevelId != null) {
            CloakingManager.unregisterCore(cloakedSubLevelId, this);
        }

        cloakedSubLevelId = newSubLevelId;

        int newBlockCount = subLevel != null
                ? countSubLevelBlocks(subLevel)
                : 0;

        if (newBlockCount != subLevelBlockCount) {
            subLevelBlockCount = newBlockCount;
            setChanged();
            sendData();

            AeroCloakingCore.LOGGER.debug(
                    "Cloaking Core sub-level {} now contains {} non-air blocks",
                    cloakedSubLevelId,
                    subLevelBlockCount
            );
        }

        if (cloakedSubLevelId != null) {
            CloakingManager.updateCore(cloakedSubLevelId, this);

            if (subLevelChanged) {
                AeroCloakingCore.LOGGER.debug(
                        "Cloaking Core joined cloak system for Sable sub-level {}",
                        cloakedSubLevelId
                );
            }
        } else if (subLevelChanged) {
            assignedBlockLoad = 0.0F;
            systemCoreCount = 0;
            systemBlockCount = 0;
            systemPotentialCapacity = 0;
            systemOperationalCapacity = 0;
            systemCapacitySatisfied = false;
            systemEffectiveRpm = 0.0F;
            systemEfficiencyMultiplier = 1.0F;
            systemTransitionDurationSeconds = 0.0F;
            systemFullyCloakedDistance = 0.0;

            AeroCloakingCore.LOGGER.debug(
                    "Cloaking Core is not currently on a Sable sub-level"
            );
        }
    }

    /**
     * Counts non-air blocks using Sable's already-loaded plot chunks.
     *
     * Minecraft's PalettedContainer can count each distinct state in a chunk
     * section directly, so this avoids checking all 4,096 cells one-by-one.
     * The scan still only runs once per second.
     */
    private static int countSubLevelBlocks(SubLevel subLevel) {
        int[] count = {0};

        for (var holder : subLevel.getPlot().getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk == null) {
                continue;
            }

            for (LevelChunkSection section : chunk.getSections()) {
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }

                section.getStates().count((state, occurrences) -> {
                    if (!state.isAir()) {
                        count[0] += occurrences;
                    }
                });
            }
        }

        return count[0];
    }

    // ---------------------------------------------------------------------
    // CLEANUP
    // ---------------------------------------------------------------------

    private void clearCloakedSubLevel() {
        if (cloakedSubLevelId != null) {
            CloakingManager.unregisterCore(cloakedSubLevelId, this);
            cloakedSubLevelId = null;
        }

        assignedBlockLoad = 0.0F;
        systemCoreCount = 0;
        systemBlockCount = 0;
        systemPotentialCapacity = 0;
        systemOperationalCapacity = 0;
        systemCapacitySatisfied = false;
        systemEffectiveRpm = 0.0F;
        systemEfficiencyMultiplier = 1.0F;
        systemTransitionDurationSeconds = 0.0F;
        systemFullyCloakedDistance = 0.0;
        checkTimer = SUBLEVEL_CHECK_INTERVAL_TICKS - 1;
    }

    private void cleanupServerState() {
        if (level == null || level.isClientSide) {
            return;
        }

        receivingLink.removeFromNetwork(level);
        clearCloakedSubLevel();
    }

    /** Called when the block is actually removed/replaced. */
    @Override
    public void remove() {
        cleanupServerState();
        super.remove();
    }

    /** Also cleans up when the containing chunk/sublevel unloads. */
    @Override
    public void invalidate() {
        cleanupServerState();
        super.invalidate();
    }

    // ---------------------------------------------------------------------
    // MENU
    // ---------------------------------------------------------------------

    @Override
    public Component getDisplayName() {
        return Component.translatable(
                "screen.aerocloakingcore.cloaking_core.title"
        );
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(
            int containerId,
            Inventory playerInventory,
            Player player
    ) {
        return new CloakingCoreMenu(
                containerId,
                playerInventory,
                this
        );
    }
}
