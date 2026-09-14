package net.fireboy.aerocloakingcore.block.entity;

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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

public class CloakingCoreBlockEntity extends BlockEntity implements MenuProvider {

    private static final String TAG_LINK_FIRST = "RedstoneLinkFrequencyFirst";
    private static final String TAG_LINK_SECOND = "RedstoneLinkFrequencySecond";

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

    // Sable sublevel scan timer. 20 ticks = 1 second.
    private int checkTimer = 19;

    public CloakingCoreBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CLOAKING_CORE.get(), pos, state);
    }

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

            // Changing frequencies is itself a fresh link input selection.
            // If a network exists, let it immediately retake control even if
            // the new signal happens to equal the previously stored signal.
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

        setEffectiveCloakStrength(
                lastRedstoneSignal / 15.0F
        );

        setChanged();
    }

    private void setEffectiveCloakStrength(float strength) {
        settings = settings.withCloakStrength(strength);
        syncCurrentCloakState();
    }

    @Override
    protected void saveAdditional(
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        super.saveAdditional(tag, registries);

        settings.save(tag);
        tag.putBoolean("ManualOverride", manualOverride);
        tag.putInt("LastRedstoneSignal", lastRedstoneSignal);

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
    protected void loadAdditional(
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        super.loadAdditional(tag, registries);

        settings = CloakingCoreSettings.load(tag);
        manualOverride = !tag.contains("ManualOverride")
                || tag.getBoolean("ManualOverride");
        lastRedstoneSignal = tag.contains("LastRedstoneSignal")
                ? Math.max(0, Math.min(15, tag.getInt("LastRedstoneSignal")))
                : 0;

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

        cloakedSubLevelId = null;
        checkTimer = 19;
    }

    public void serverTick() {
        if (level == null || level.isClientSide) {
            return;
        }

        // addToNetwork() is internally guarded, so this is safe every tick and
        // also handles freshly-loaded block entities after NBT was read.
        receivingLink.setFrequencies(
                linkFrequencyFirst,
                linkFrequencySecond
        );
        receivingLink.addToNetwork(level);

        if (receivingLink.isConfigured()) {
            int signal = receivingLink.readNetwork(level);

            // A manual override remains in force while the network stays at
            // exactly the same value. Only an actual link update retakes control.
            if (signal != lastRedstoneSignal) {
                onRedstoneLinkSignalUpdated(signal);
            }
        }

        checkTimer++;

        if (checkTimer >= 20) {
            checkTimer = 0;
            checkSubLevel();
        }
    }

    private void checkSubLevel() {
        if (level == null || level.isClientSide) {
            return;
        }

        SubLevel subLevel = Sable.HELPER.getContaining(this);

        UUID newSubLevelId = subLevel != null
                ? subLevel.getUniqueId()
                : null;

        if (Objects.equals(cloakedSubLevelId, newSubLevelId)) {
            syncCurrentCloakState();
            return;
        }

        if (cloakedSubLevelId != null) {
            CloakingManager.removeCloakedSubLevel(cloakedSubLevelId);
        }

        cloakedSubLevelId = newSubLevelId;

        if (cloakedSubLevelId != null) {
            syncCurrentCloakState();

            AeroCloakingCore.LOGGER.debug(
                    "Cloaking Core found Sable sub-level: {}",
                    cloakedSubLevelId
            );
        } else {
            AeroCloakingCore.LOGGER.debug(
                    "Cloaking Core is not currently on a Sable sub-level"
            );
        }
    }

    private void syncCurrentCloakState() {
        if (level == null || level.isClientSide || cloakedSubLevelId == null) {
            return;
        }

        CloakingManager.setCloakedSubLevel(
                cloakedSubLevelId,
                settings
        );
    }

    private void clearCloakedSubLevel() {
        if (cloakedSubLevelId != null) {
            CloakingManager.removeCloakedSubLevel(cloakedSubLevelId);
            cloakedSubLevelId = null;
        }

        checkTimer = 19;
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            receivingLink.removeFromNetwork(level);
            clearCloakedSubLevel();
        }

        super.setRemoved();
    }

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
