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
 * Initial balance:
 * - minimum speed: Create MEDIUM speed tier (normally 32 RPM)
 * - base demand at minimum speed: 256 SU
 * - additional demand at minimum speed: 2 SU per non-air sublevel block
 *
 * Create stress naturally scales with RPM. For example, at the normal 32 RPM
 * minimum a 1,000-block sublevel costs 2,256 SU. At 64 RPM that same core
 * costs twice as much, just like other Create stress consumers.
 */
public class CloakingCoreBlockEntity extends KineticBlockEntity
        implements MenuProvider {

    private static final String TAG_LINK_FIRST = "RedstoneLinkFrequencyFirst";
    private static final String TAG_LINK_SECOND = "RedstoneLinkFrequencySecond";
    private static final String TAG_MANUAL_OVERRIDE = "ManualOverride";
    private static final String TAG_LAST_REDSTONE_SIGNAL = "LastRedstoneSignal";
    private static final String TAG_SUBLEVEL_BLOCK_COUNT = "SubLevelBlockCount";

    /** SU consumed at the minimum operating RPM before ship-size scaling. */
    public static final float BASE_SU_AT_MINIMUM_RPM = 256.0F;

    /** Extra SU at the minimum operating RPM for every non-air sublevel block. */
    public static final float SU_PER_BLOCK_AT_MINIMUM_RPM = 2.0F;

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

    // Starts at 19 so a newly loaded core scans on its first server tick.
    private int checkTimer = SUBLEVEL_CHECK_INTERVAL_TICKS - 1;

    /** Last effective power state sent to CloakingManager. */
    private boolean lastOperationalState = false;

    public CloakingCoreBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CLOAKING_CORE.get(), pos, state);
    }

    // ---------------------------------------------------------------------
    // CREATE KINETICS / STRESS
    // ---------------------------------------------------------------------

    /**
     * The minimum RPM follows Create's MEDIUM speed setting.
     * With Create defaults this is 32 RPM.
     */
    public float getMinimumRequiredRpm() {
        return IRotate.SpeedLevel.MEDIUM.getSpeedValue();
    }

    /**
     * Exact block count currently used for SU scaling.
     */
    public int getSubLevelBlockCount() {
        return subLevelBlockCount;
    }

    /**
     * SU that would be consumed at the minimum operating speed.
     */
    public float getRequiredSuAtMinimumRpm() {
        return BASE_SU_AT_MINIMUM_RPM
                + SU_PER_BLOCK_AT_MINIMUM_RPM * subLevelBlockCount;
    }

    /**
     * Current theoretical stress usage in SU.
     *
     * Theoretical speed is used so an overstressed network still reports the
     * demand that caused it to become overstressed.
     */
    public float getCurrentRequiredSu() {
        return calculateStressApplied() * Math.abs(getTheoreticalSpeed());
    }

    /**
     * Create asks for stress impact in SU/RPM. We calculate that dynamically so
     * the total SU at the minimum RPM equals our base + per-block requirement.
     */
    @Override
    public float calculateStressApplied() {
        float minimumRpm = Math.max(1.0F, getMinimumRequiredRpm());
        float impact = getRequiredSuAtMinimumRpm() / minimumRpm;

        // KineticBlockEntity persists/caches this value for its network.
        this.lastStressApplied = impact;
        return impact;
    }

    /**
     * Cloaking only operates when Create considers the machine fast enough and
     * the kinetic network is not overstressed.
     */
    public boolean isOperational() {
        return !isOverStressed() && isSpeedRequirementFulfilled();
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
        syncCurrentCloakState();
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
        syncCurrentCloakState();
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
            checkTimer = SUBLEVEL_CHECK_INTERVAL_TICKS - 1;
            lastOperationalState = false;
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

        boolean operationalNow = isOperational();
        if (operationalNow != lastOperationalState) {
            lastOperationalState = operationalNow;
            syncCurrentCloakState();
        }

        checkTimer++;
        if (checkTimer >= SUBLEVEL_CHECK_INTERVAL_TICKS) {
            checkTimer = 0;
            checkSubLevel();
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
            CloakingManager.removeCloakedSubLevel(cloakedSubLevelId);
        }

        cloakedSubLevelId = newSubLevelId;

        int newBlockCount = subLevel != null
                ? countSubLevelBlocks(subLevel)
                : 0;

        if (newBlockCount != subLevelBlockCount) {
            subLevelBlockCount = newBlockCount;

            // Force Create to recalculate this consumer's dynamic stress on
            // the next kinetic tick.
            networkDirty = true;

            setChanged();
            sendData();

            AeroCloakingCore.LOGGER.debug(
                    "Cloaking Core sub-level {} now contains {} non-air blocks; "
                            + "minimum-speed demand is {} SU",
                    cloakedSubLevelId,
                    subLevelBlockCount,
                    getRequiredSuAtMinimumRpm()
            );
        }

        if (cloakedSubLevelId != null) {
            syncCurrentCloakState();

            if (subLevelChanged) {
                AeroCloakingCore.LOGGER.debug(
                        "Cloaking Core found Sable sub-level: {}",
                        cloakedSubLevelId
                );
            }
        } else if (subLevelChanged) {
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
    // CLOAK STATE
    // ---------------------------------------------------------------------

    private void syncCurrentCloakState() {
        if (level == null || level.isClientSide || cloakedSubLevelId == null) {
            return;
        }

        CloakingCoreSettings effectiveSettings = isOperational()
                ? settings
                : settings.withCloakStrength(0.0F);

        CloakingManager.setCloakedSubLevel(
                cloakedSubLevelId,
                effectiveSettings
        );
    }

    private void clearCloakedSubLevel() {
        if (cloakedSubLevelId != null) {
            CloakingManager.removeCloakedSubLevel(cloakedSubLevelId);
            cloakedSubLevelId = null;
        }

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
