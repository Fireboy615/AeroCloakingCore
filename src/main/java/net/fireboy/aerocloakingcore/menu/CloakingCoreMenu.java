package net.fireboy.aerocloakingcore.menu;

import net.fireboy.aerocloakingcore.block.entity.CloakingCoreBlockEntity;
import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.fireboy.aerocloakingcore.cloak.CloakingCoreSettings;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public class CloakingCoreMenu extends AbstractContainerMenu {

    public static final int FREQUENCY_FIRST_SLOT = 0;
    public static final int FREQUENCY_SECOND_SLOT = 1;

    @Nullable
    private final CloakingCoreBlockEntity core;

    private final SimpleContainer frequencies = new SimpleContainer(2);
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

        frequencies.setItem(
                FREQUENCY_FIRST_SLOT,
                ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer)
        );
        frequencies.setItem(
                FREQUENCY_SECOND_SLOT,
                ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer)
        );

        addSlots(inventory);
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

        frequencies.setItem(
                FREQUENCY_FIRST_SLOT,
                core.getLinkFrequency(true)
        );
        frequencies.setItem(
                FREQUENCY_SECOND_SLOT,
                core.getLinkFrequency(false)
        );

        addSlots(inventory);
    }

    private void addSlots(Inventory inventory) {
        // Two ghost frequency slots.
        addSlot(new FrequencySlot(
                frequencies,
                FREQUENCY_FIRST_SLOT,
                127,
                96,
                true
        ));

        addSlot(new FrequencySlot(
                frequencies,
                FREQUENCY_SECOND_SLOT,
                157,
                96,
                false
        ));

        // Player inventory.
        int inventoryX = 69;
        int inventoryY = 151;

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(
                        inventory,
                        column + row * 9 + 9,
                        inventoryX + column * 18,
                        inventoryY + row * 18
                ));
            }
        }

        // Hotbar.
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(
                    inventory,
                    column,
                    inventoryX + column * 18,
                    209
            ));
        }
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

    public void applyFrequency(
            Player player,
            boolean first,
            ItemStack stack
    ) {
        if (core == null || core.isRemoved()) {
            return;
        }

        ItemStack normalized = stack.isEmpty()
                ? ItemStack.EMPTY
                : stack.copyWithCount(1);

        core.setLinkFrequency(first, normalized);

        frequencies.setItem(
                first ? FREQUENCY_FIRST_SLOT : FREQUENCY_SECOND_SLOT,
                normalized
        );

        broadcastChanges();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // Ghost frequency slots should never consume items, and shift-click is
        // intentionally disabled in this first link UI pass.
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return core == null || !core.isRemoved();
    }

    public static final class FrequencySlot extends Slot {

        private final boolean first;

        public FrequencySlot(
                Container container,
                int slot,
                int x,
                int y,
                boolean first
        ) {
            super(container, slot, x, y);
            this.first = first;
        }

        public boolean isFirst() {
            return first;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}
