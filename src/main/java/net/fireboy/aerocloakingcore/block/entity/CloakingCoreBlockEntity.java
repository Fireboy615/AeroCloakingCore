package net.fireboy.aerocloakingcore.block.entity;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.fireboy.aerocloakingcore.AeroCloakingCore;
import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.fireboy.aerocloakingcore.cloak.CloakingCoreSettings;
import net.fireboy.aerocloakingcore.cloak.CloakingManager;
import net.fireboy.aerocloakingcore.menu.CloakingCoreMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

public class CloakingCoreBlockEntity extends BlockEntity implements MenuProvider {

    private CloakingCoreSettings settings = CloakingCoreSettings.DEFAULT;

    /**
     * True when a manual UI/shift-click strength is currently overriding
     * Create Redstone Link control.
     */
    private boolean manualOverride = true;

    /** Last Redstone Link signal received. Kept so future link integration can resume cleanly. */
    private int lastRedstoneSignal = 0;

    private UUID cloakedSubLevelId = null;

    // 20 ticks = 1 second
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

    /**
     * Shift-right-click behaviour.
     *
     * Converts the current effective strength to a binary manual override and
     * keeps that override until a Redstone Link signal update is received.
     */
    public float toggleManualCloak() {
        float target = settings.cloakStrength() >= 0.5F
                ? 0.0F
                : 1.0F;

        setManualCloakStrength(target);
        return target;
    }

    /**
     * Called by the block UI when the strength slider is changed.
     * This intentionally becomes a manual override of Redstone Link control.
     */
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
     * Future Create Redstone Link integration should call this ONLY when the
     * link actually receives/updates its signal.
     *
     * Any new link update cancels the manual override, exactly as requested.
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
    }

    @Override
    protected void loadAdditional(
            CompoundTag tag,
            HolderLookup.Provider registries
    ) {
        super.loadAdditional(tag, registries);

        settings = CloakingCoreSettings.load(tag);
        manualOverride = !tag.contains("ManualOverride") || tag.getBoolean("ManualOverride");
        lastRedstoneSignal = tag.contains("LastRedstoneSignal")
                ? Math.max(0, Math.min(15, tag.getInt("LastRedstoneSignal")))
                : 0;

        cloakedSubLevelId = null;
        checkTimer = 19;
    }

    public void serverTick() {
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
            clearCloakedSubLevel();
        }

        super.setRemoved();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("screen.aerocloakingcore.cloaking_core.title");
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
