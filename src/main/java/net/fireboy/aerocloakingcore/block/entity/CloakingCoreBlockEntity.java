package net.fireboy.aerocloakingcore.block.entity;

import com.simibubi.create.content.kinetics.base.IRotate;
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
 * Multi-core prototype balance:
 * - minimum operating speed: Create MEDIUM speed tier (normally 32 RPM)
 * - one core at minimum RPM contributes 512 blocks of cloak capacity
 * - capacity scales with sqrt(RPM / minimum RPM), giving diminishing returns
 * - ship block load is shared between all cores on the same Sable sublevel
 * - each core pays 256 base SU at minimum RPM plus 2 SU per assigned block
 * - the per-block stress portion scales with cloakStrength^2
 * - Create then naturally multiplies stress impact by actual RPM
 */
public class CloakingCoreBlockEntity extends KineticBlockEntity
        implements MenuProvider {

    private static final String TAG_LINK_FIRST = "RedstoneLinkFrequencyFirst";
    private static final String TAG_LINK_SECOND = "RedstoneLinkFrequencySecond";
    private static final String TAG_MANUAL_OVERRIDE = "ManualOverride";
    private static final String TAG_LAST_REDSTONE_SIGNAL = "LastRedstoneSignal";
    private static final String TAG_SUBLEVEL_BLOCK_COUNT = "SubLevelBlockCount";

    /** SU consumed at minimum RPM before ship-load scaling. */
    public static final float BASE_SU_AT_MINIMUM_RPM = 256.0F;

    /** Extra SU at minimum RPM per assigned ship block at 100% cloak strength. */
    public static final float SU_PER_BLOCK_AT_MINIMUM_RPM = 2.0F;

    /** Cloak capacity supplied by one core exactly at minimum operating RPM. */
    public static final int CAPACITY_BLOCKS_AT_MINIMUM_RPM = 512;

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
    private float systemTransitionDurationSeconds = 0.0F;

    // Starts at 19 so a newly loaded core scans on its first server tick.
    private int checkTimer = SUBLEVEL_CHECK_INTERVAL_TICKS - 1;

    public CloakingCoreBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CLOAKING_CORE.get(), pos, state);
    }

    // ---------------------------------------------------------------------
    // CREATE KINETICS / CAPACITY / STRESS
    // ---------------------------------------------------------------------

    /**
     * The minimum RPM follows Create's MEDIUM speed setting.
     * With Create defaults this is 32 RPM.
     */
    public float getMinimumRequiredRpm() {
        return IRotate.SpeedLevel.MEDIUM.getSpeedValue();
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
        float minimumRpm = Math.max(1.0F, getMinimumRequiredRpm());

        if (rpm + 0.0001F < minimumRpm) {
            return 0;
        }

        double multiplier = Math.sqrt(rpm / minimumRpm);
        return Math.max(
                0,
                (int) Math.floor(
                        CAPACITY_BLOCKS_AT_MINIMUM_RPM * multiplier
                )
        );
    }

    public float getAssignedBlockLoad() {
        return assignedBlockLoad;
    }

    /**
     * SU that this core would consume at the minimum operating RPM.
     *
     * Only the variable block-load portion scales with cloak strength. Using a
     * squared curve makes partial analog cloak values meaningfully cheaper:
     * 50% cloak pays 25% of the variable block cost, 75% pays 56.25%, etc.
     */
    public float getRequiredSuAtMinimumRpm() {
        float strength = settings.cloakStrength();
        float cloakLoadFactor = strength * strength;

        return BASE_SU_AT_MINIMUM_RPM
                + SU_PER_BLOCK_AT_MINIMUM_RPM
                * assignedBlockLoad
                * cloakLoadFactor;
    }

    /**
     * Current theoretical stress usage in SU.
     *
     * Theoretical speed is used so an overstressed network still reports the
     * demand that caused it to become overstressed.
     */
    public float getCurrentRequiredSu() {
        float minimumRpm = Math.max(1.0F, getMinimumRequiredRpm());
        float impact = getRequiredSuAtMinimumRpm() / minimumRpm;
        return impact * getTheoreticalRpm();
    }

    /**
     * Create asks for stress impact in SU/RPM. The actual network demand is then
     * this impact multiplied by RPM, so higher speed provides more cloak
     * capacity but is naturally more expensive to run.
     */
    @Override
    public float calculateStressApplied() {
        float minimumRpm = Math.max(1.0F, getMinimumRequiredRpm());
        float impact = getRequiredSuAtMinimumRpm() / minimumRpm;

        this.lastStressApplied = impact;
        return impact;
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

    /**
     * Status codes used by the menu's synced ContainerData.
     * 0 = no Sable sublevel
     * 1 = this core below minimum RPM
     * 2 = this core's Create network overstressed
     * 3 = ship-wide cloak capacity insufficient
     * 4 = ready
     */
    public int getSystemStatusCode() {
        if (cloakedSubLevelId == null) {
            return 0;
        }

        if (isOverStressed()) {
            return 2;
        }

        if (getPotentialCloakCapacityBlocks() <= 0) {
            return 1;
        }

        if (!systemCapacitySatisfied) {
            return 3;
        }

        return 4;
    }

    /** Called only by CloakingManager on the server thread. */
    public void setAssignedBlockLoadFromManager(float assignedBlockLoad) {
        float normalized = Math.max(0.0F, assignedBlockLoad);

        if (Math.abs(this.assignedBlockLoad - normalized) < 0.01F) {
            return;
        }

        this.assignedBlockLoad = normalized;

        // Stress impact changed, so force Create to rebuild/recalculate it.
        networkDirty = true;
        setChanged();
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
            networkDirty = true;
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
            float transitionDurationSeconds
    ) {
        this.systemCoreCount = Math.max(0, coreCount);
        this.systemBlockCount = Math.max(0, blockCount);
        this.systemPotentialCapacity = Math.max(0, potentialCapacity);
        this.systemOperationalCapacity = Math.max(0, operationalCapacity);
        this.systemCapacitySatisfied = capacitySatisfied;
        this.systemEffectiveRpm = Math.max(0.0F, effectiveRpm);
        this.systemTransitionDurationSeconds = Math.max(
                0.0F,
                transitionDurationSeconds
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
        networkDirty = true;
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
            systemTransitionDurationSeconds = 0.0F;
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
            systemTransitionDurationSeconds = 0.0F;

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
        systemTransitionDurationSeconds = 0.0F;
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
