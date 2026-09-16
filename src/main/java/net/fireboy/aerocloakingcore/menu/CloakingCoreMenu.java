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
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

public class CloakingCoreMenu extends AbstractContainerMenu {

    public static final int FREQUENCY_FIRST_SLOT = 0;
    public static final int FREQUENCY_SECOND_SLOT = 1;

    public static final int DATA_RPM_X10 = 0;
    public static final int DATA_CORE_POTENTIAL_CAPACITY = 1;
    public static final int DATA_CORE_OPERATIONAL_CAPACITY = 2;
    public static final int DATA_ASSIGNED_BLOCKS = 3;
    public static final int DATA_SYSTEM_BLOCKS = 4;
    public static final int DATA_SYSTEM_POTENTIAL_CAPACITY = 5;
    public static final int DATA_SYSTEM_OPERATIONAL_CAPACITY = 6;
    public static final int DATA_SYSTEM_CORE_COUNT = 7;
    public static final int DATA_CURRENT_SU = 8;
    public static final int DATA_TRANSITION_CENTISECONDS = 9;
    public static final int DATA_STATUS = 10;
    public static final int DATA_COUNT = 11;

    @Nullable
    private final CloakingCoreBlockEntity core;

    private final SimpleContainer frequencies = new SimpleContainer(2);
    private final ContainerData systemData;
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
        this.systemData = new SimpleContainerData(DATA_COUNT);

        frequencies.setItem(
                FREQUENCY_FIRST_SLOT,
                ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer)
        );
        frequencies.setItem(
                FREQUENCY_SECOND_SLOT,
                ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer)
        );

        addDataSlots(systemData);
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
        this.systemData = createServerData(core);

        frequencies.setItem(
                FREQUENCY_FIRST_SLOT,
                core.getLinkFrequency(true)
        );
        frequencies.setItem(
                FREQUENCY_SECOND_SLOT,
                core.getLinkFrequency(false)
        );

        addDataSlots(systemData);
        addSlots(inventory);
    }

    private static ContainerData createServerData(CloakingCoreBlockEntity core) {
        return new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case DATA_RPM_X10 -> clampDataValue(
                            Math.round(core.getTheoreticalRpm() * 10.0F)
                    );
                    case DATA_CORE_POTENTIAL_CAPACITY -> clampDataValue(
                            core.getPotentialCloakCapacityBlocks()
                    );
                    case DATA_CORE_OPERATIONAL_CAPACITY -> clampDataValue(
                            core.getOperationalCloakCapacityBlocks()
                    );
                    case DATA_ASSIGNED_BLOCKS -> clampDataValue(
                            Math.round(core.getAssignedBlockLoad())
                    );
                    case DATA_SYSTEM_BLOCKS -> clampDataValue(
                            core.getSystemBlockCount()
                    );
                    case DATA_SYSTEM_POTENTIAL_CAPACITY -> clampDataValue(
                            core.getSystemPotentialCapacity()
                    );
                    case DATA_SYSTEM_OPERATIONAL_CAPACITY -> clampDataValue(
                            core.getSystemOperationalCapacity()
                    );
                    case DATA_SYSTEM_CORE_COUNT -> clampDataValue(
                            core.getSystemCoreCount()
                    );
                    case DATA_CURRENT_SU -> clampDataValue(
                            Math.round(core.getCurrentRequiredSu())
                    );
                    case DATA_TRANSITION_CENTISECONDS -> clampDataValue(
                            Math.round(
                                    core.getSystemTransitionDurationSeconds()
                                            * 100.0F
                            )
                    );
                    case DATA_STATUS -> clampDataValue(
                            core.getSystemStatusCode()
                    );
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                // Server values are read directly from the block entity.
            }

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        };
    }

    /**
     * Vanilla ContainerData is synchronized through signed 16-bit values.
     * The prototype HUD only needs human-readable values, so saturate rather
     * than allowing overflow to wrap negative on very large systems.
     */
    private static int clampDataValue(int value) {
        return Math.max(0, Math.min(32767, value));
    }

    private void addSlots(Inventory inventory) {
        // Two ghost frequency slots.
        addSlot(new FrequencySlot(
                frequencies,
                FREQUENCY_FIRST_SLOT,
                127,
                157,
                true
        ));

        addSlot(new FrequencySlot(
                frequencies,
                FREQUENCY_SECOND_SLOT,
                157,
                157,
                false
        ));

        // Player inventory.
        int inventoryX = 69;
        int inventoryY = 217;

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
                    275
            ));
        }
    }

    public CloakingCoreSettings getInitialSettings() {
        return initialSettings;
    }

    public float getRpm() {
        return systemData.get(DATA_RPM_X10) / 10.0F;
    }

    public int getCorePotentialCapacity() {
        return systemData.get(DATA_CORE_POTENTIAL_CAPACITY);
    }

    public int getCoreOperationalCapacity() {
        return systemData.get(DATA_CORE_OPERATIONAL_CAPACITY);
    }

    public int getAssignedBlocks() {
        return systemData.get(DATA_ASSIGNED_BLOCKS);
    }

    public int getSystemBlocks() {
        return systemData.get(DATA_SYSTEM_BLOCKS);
    }

    public int getSystemPotentialCapacity() {
        return systemData.get(DATA_SYSTEM_POTENTIAL_CAPACITY);
    }

    public int getSystemOperationalCapacity() {
        return systemData.get(DATA_SYSTEM_OPERATIONAL_CAPACITY);
    }

    public int getSystemCoreCount() {
        return systemData.get(DATA_SYSTEM_CORE_COUNT);
    }

    public int getCurrentSu() {
        return systemData.get(DATA_CURRENT_SU);
    }

    public float getTransitionSeconds() {
        return systemData.get(DATA_TRANSITION_CENTISECONDS) / 100.0F;
    }

    public int getSystemStatus() {
        return systemData.get(DATA_STATUS);
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
