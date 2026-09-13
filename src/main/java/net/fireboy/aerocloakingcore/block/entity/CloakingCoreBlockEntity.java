package net.fireboy.aerocloakingcore.block.entity;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.fireboy.aerocloakingcore.cloak.CloakingManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;
import java.util.UUID;

public class CloakingCoreBlockEntity extends BlockEntity {

    private boolean active = false;

    private UUID cloakedSubLevelId = null;

    // 20 ticks = 1 second
    private int checkTimer = 0;

    public CloakingCoreBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CLOAKING_CORE.get(), pos, state);
    }

    public boolean isActive() {
        return active;
    }

    public void toggle() {
        active = !active;

        if (active) {
            // Check immediately when turned on.
            checkSubLevel();
        } else {
            // Immediately stop cloaking when turned off.
            clearCloakedSubLevel();
        }

        setChanged();
    }

    public void serverTick() {
        if (!active) {
            return;
        }

        checkTimer++;

        // Only check once every 20 ticks.
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

        // Nothing changed, so don't do anything.
        if (Objects.equals(cloakedSubLevelId, newSubLevelId)) {
            return;
        }

        // If we were previously cloaking another sublevel,
        // stop cloaking that one.
        if (cloakedSubLevelId != null) {
            CloakingManager.removeCloakedSubLevel(cloakedSubLevelId);
        }

        cloakedSubLevelId = newSubLevelId;

        // Start cloaking the new sublevel.
        if (cloakedSubLevelId != null) {

            CloakingManager.addCloakedSubLevel(
                    cloakedSubLevelId
            );

            System.out.println(
                    "[Aero Cloaking Core] Found Sable sub-level: "
                            + cloakedSubLevelId
            );

        } else {

            System.out.println(
                    "[Aero Cloaking Core] No Sable sub-level found."
            );
        }
    }

    private void clearCloakedSubLevel() {

        if (cloakedSubLevelId != null) {
            CloakingManager.removeCloakedSubLevel(
                    cloakedSubLevelId
            );

            cloakedSubLevelId = null;
        }

        checkTimer = 0;
    }

    @Override
    public void setRemoved() {

        if (level != null && !level.isClientSide) {
            clearCloakedSubLevel();
        }

        super.setRemoved();
    }
}