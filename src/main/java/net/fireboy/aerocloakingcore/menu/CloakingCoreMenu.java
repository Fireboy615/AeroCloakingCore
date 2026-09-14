package net.fireboy.aerocloakingcore.menu;

import net.fireboy.aerocloakingcore.block.entity.CloakingCoreBlockEntity;
import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.fireboy.aerocloakingcore.cloak.CloakingCoreSettings;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public class CloakingCoreMenu extends AbstractContainerMenu {

    @Nullable
    private final CloakingCoreBlockEntity core;

    private CloakingCoreSettings initialSettings;

    /** Client constructor. */
    public CloakingCoreMenu(
            int containerId,
            Inventory inventory,
            RegistryFriendlyByteBuf buffer
    ) {
        super(ModMenus.CLOAKING_CORE.get(), containerId);
        this.core = null;
        this.initialSettings = CloakingCoreSettings.read(buffer);
    }

    /** Server constructor. */
    public CloakingCoreMenu(
            int containerId,
            Inventory inventory,
            CloakingCoreBlockEntity core
    ) {
        super(ModMenus.CLOAKING_CORE.get(), containerId);
        this.core = core;
        this.initialSettings = core.getSettings();
    }

    public CloakingCoreSettings getInitialSettings() {
        return initialSettings;
    }

    public void applySettings(
            Player player,
            boolean updateStrength,
            float cloakStrength,
            CloakRenderMode renderMode
    ) {
        if (core == null || core.isRemoved()) {
            return;
        }

        core.setRenderMode(renderMode);

        if (updateStrength) {
            core.setManualCloakStrength(cloakStrength);
        }

        initialSettings = core.getSettings();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return core == null || !core.isRemoved();
    }
}
