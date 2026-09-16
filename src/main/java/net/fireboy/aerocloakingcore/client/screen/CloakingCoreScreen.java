package net.fireboy.aerocloakingcore.client.screen;

import net.fireboy.aerocloakingcore.block.entity.CloakingCoreBlockEntity;
import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.fireboy.aerocloakingcore.cloak.CloakingCoreSettings;
import net.fireboy.aerocloakingcore.menu.CloakingCoreMenu;
import net.fireboy.aerocloakingcore.network.UpdateCloakingCoreLinkFrequencyPayload;
import net.fireboy.aerocloakingcore.network.UpdateCloakingCoreSettingsPayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * Cloaking Core control screen.
 *
 * The layout deliberately separates controls, this core's state, ship-wide
 * state and Redstone Link configuration. The goal is to keep the important
 * numbers readable without turning the screen into a wall of debug text.
 */
public class CloakingCoreScreen extends AbstractContainerScreen<CloakingCoreMenu> {

    private static final int PANEL_LEFT = 20;
    private static final int PANEL_RIGHT = 300;

    private float cloakStrength;
    private CloakRenderMode renderMode;

    private ValueSlider strengthSlider;
    private boolean draggingStrengthSlider;
    private boolean strengthDirty;

    public CloakingCoreScreen(
            CloakingCoreMenu menu,
            Inventory playerInventory,
            Component title
    ) {
        super(menu, playerInventory, title);

        imageWidth = 320;
        imageHeight = 326;

        CloakingCoreSettings settings = menu.getInitialSettings();
        cloakStrength = settings.cloakStrength();
        renderMode = settings.renderMode();
    }

    @Override
    protected void init() {
        super.init();

        int x = leftPos + 20;
        int width = imageWidth - 40;
        int halfWidth = (width - 8) / 2;
        int rightX = x + halfWidth + 8;

        strengthSlider = new ValueSlider(
                x,
                topPos + 28,
                width,
                20,
                0.0,
                1.0,
                cloakStrength,
                value -> {
                    cloakStrength = (float) value;
                    strengthDirty = true;
                },
                value -> Component.translatable(
                        "screen.aerocloakingcore.cloaking_core.strength",
                        Math.round(value * 100.0)
                )
        );
        addRenderableWidget(strengthSlider);

        addRenderableWidget(
                CycleButton.<CloakRenderMode>builder(CloakingCoreScreen::renderModeName)
                        .withValues(CloakRenderMode.values())
                        .withInitialValue(renderMode)
                        .create(
                                x,
                                topPos + 54,
                                width,
                                20,
                                Component.translatable(
                                        "screen.aerocloakingcore.cloaking_core.render_mode"
                                ),
                                (button, value) -> renderMode = value
                        )
        );

        addRenderableWidget(
                Button.builder(
                                CommonComponents.GUI_DONE,
                                button -> saveAndClose()
                        )
                        .bounds(x, topPos + 212, halfWidth, 20)
                        .build()
        );

        addRenderableWidget(
                Button.builder(
                                CommonComponents.GUI_CANCEL,
                                button -> onClose()
                        )
                        .bounds(rightX, topPos + 212, halfWidth, 20)
                        .build()
        );
    }

    @Override
    protected void containerTick() {
        super.containerTick();

        // The opening buffer is only a snapshot. Once normal menu data arrives,
        // keep an untouched slider aligned with the server-authoritative value.
        if (!strengthDirty
                && !draggingStrengthSlider
                && strengthSlider != null
                && menu.hasServerStats()) {
            float serverStrength = Mth.clamp(
                    menu.getServerCloakStrength(),
                    0.0F,
                    1.0F
            );

            if (Math.abs(serverStrength - cloakStrength) > 0.0001F) {
                cloakStrength = serverStrength;
                strengthSlider.setActualValueSilently(serverStrength);
            }
        }
    }

    @Override
    public boolean mouseClicked(
            double mouseX,
            double mouseY,
            int button
    ) {
        Slot hovered = getSlotUnderMouse();

        if (hovered instanceof CloakingCoreMenu.FrequencySlot frequencySlot
                && (button == 0 || button == 1)) {

            ItemStack frequency;

            if (button == 1 || menu.getCarried().isEmpty()) {
                frequency = ItemStack.EMPTY;
            } else {
                frequency = menu.getCarried().copyWithCount(1);
            }

            frequencySlot.set(frequency.copy());

            PacketDistributor.sendToServer(
                    new UpdateCloakingCoreLinkFrequencyPayload(
                            menu.containerId,
                            frequencySlot.isFirst(),
                            frequency
                    )
            );

            return true;
        }

        if (button == 0
                && strengthSlider != null
                && strengthSlider.isMouseOver(mouseX, mouseY)) {
            draggingStrengthSlider = true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(
            double mouseX,
            double mouseY,
            int button,
            double dragX,
            double dragY
    ) {
        if (button == 0
                && draggingStrengthSlider
                && strengthSlider != null) {
            strengthSlider.setValueFromScreenMouse(mouseX);
            return true;
        }

        return super.mouseDragged(
                mouseX,
                mouseY,
                button,
                dragX,
                dragY
        );
    }

    @Override
    public boolean mouseReleased(
            double mouseX,
            double mouseY,
            int button
    ) {
        if (button == 0 && draggingStrengthSlider) {
            if (strengthSlider != null) {
                strengthSlider.setValueFromScreenMouse(mouseX);
            }

            draggingStrengthSlider = false;
        }

        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void saveAndClose() {
        PacketDistributor.sendToServer(
                new UpdateCloakingCoreSettingsPayload(
                        menu.containerId,
                        strengthDirty,
                        cloakStrength,
                        renderMode
                )
        );

        onClose();
    }

    @Override
    protected void renderLabels(
            GuiGraphics guiGraphics,
            int mouseX,
            int mouseY
    ) {
        guiGraphics.drawCenteredString(
                font,
                title,
                imageWidth / 2,
                8,
                0xFFFFFF
        );

        drawSystemOverview(guiGraphics);
        drawRedstoneLink(guiGraphics);

        guiGraphics.drawString(
                font,
                Component.translatable("container.inventory"),
                79,
                238,
                0xA0A0A0,
                false
        );
    }

    private void drawSystemOverview(GuiGraphics guiGraphics) {
        int status = menu.getSystemStatus();

        guiGraphics.fill(PANEL_LEFT, 82, PANEL_RIGHT, 83, 0xFF505050);

        guiGraphics.drawString(
                font,
                Component.literal("Cloaking System"),
                PANEL_LEFT,
                88,
                0xE0E0E0,
                false
        );

        String statusLabel = statusText(status);
        drawRightAlignedString(
                guiGraphics,
                statusLabel,
                PANEL_RIGHT,
                88,
                statusColor(status)
        );

        drawInfoPanel(guiGraphics, 20, 100, 155, 154, "THIS CORE");
        drawInfoPanel(guiGraphics, 165, 100, 300, 154, "SHIP SYSTEM");

        float currentRpm = menu.getRpm();
        float theoreticalRpm = menu.getTheoreticalRpm();

        String rpmText = Math.abs(currentRpm - theoreticalRpm) > 0.05F
                ? String.format(
                        Locale.ROOT,
                        "%.1f / %.1f",
                        currentRpm,
                        theoreticalRpm
                )
                : String.format(Locale.ROOT, "%.1f", currentRpm);

        drawKeyValue(guiGraphics, 27, 116, 148, "RPM", rpmText);
        drawKeyValue(
                guiGraphics,
                27,
                128,
                148,
                "Capacity",
                String.format(Locale.ROOT, "%,d", menu.getCorePotentialCapacity())
        );
        drawKeyValue(
                guiGraphics,
                27,
                140,
                148,
                "Assigned",
                String.format(Locale.ROOT, "%,d", menu.getAssignedBlocks())
        );
        drawKeyValue(
                guiGraphics,
                27,
                152,
                148,
                "Core SU",
                formatSu(getDisplayedSu())
        );

        drawKeyValue(
                guiGraphics,
                172,
                116,
                293,
                "Cores",
                Integer.toString(menu.getSystemCoreCount())
        );
        drawKeyValue(
                guiGraphics,
                172,
                128,
                293,
                "Average RPM",
                String.format(Locale.ROOT, "%.1f", menu.getSystemRpm())
        );
        drawKeyValue(
                guiGraphics,
                172,
                140,
                293,
                "Capacity",
                String.format(
                        Locale.ROOT,
                        "%,d",
                        menu.getSystemOperationalCapacity()
                )
        );
        drawKeyValue(
                guiGraphics,
                172,
                152,
                293,
                "Efficiency",
                String.format(
                        Locale.ROOT,
                        "%.0f%%",
                        menu.getSystemEfficiencyFactor() * 100.0F
                )
        );

        int blocks = menu.getSystemBlocks();
        int capacity = menu.getSystemOperationalCapacity();

        guiGraphics.drawString(
                font,
                Component.literal(String.format(
                        Locale.ROOT,
                        "Ship capacity: %,d / %,d blocks",
                        blocks,
                        capacity
                )),
                PANEL_LEFT,
                161,
                capacity > 0 && blocks > capacity ? 0xFF8888 : 0xC8C8C8,
                false
        );

        drawCapacityBar(
                guiGraphics,
                PANEL_LEFT,
                173,
                PANEL_RIGHT - PANEL_LEFT,
                blocks,
                capacity
        );

        guiGraphics.drawString(
                font,
                Component.literal(String.format(
                        Locale.ROOT,
                        "Transition %.2fs    Reveal starts %.1f blocks",
                        menu.getTransitionSeconds(),
                        menu.getRevealStartDistance()
                )),
                PANEL_LEFT,
                181,
                0xA8A8A8,
                false
        );
    }

    private void drawRedstoneLink(GuiGraphics guiGraphics) {
        guiGraphics.drawString(
                font,
                Component.literal("Redstone Link"),
                PANEL_LEFT,
                191,
                0xD0D0D0,
                false
        );

        guiGraphics.drawString(
                font,
                Component.literal("Signal 0-15 controls cloak strength"),
                PANEL_LEFT,
                202,
                0x888888,
                false
        );
    }

    private void drawInfoPanel(
            GuiGraphics guiGraphics,
            int left,
            int top,
            int right,
            int bottom,
            String heading
    ) {
        guiGraphics.fill(left, top, right, bottom, 0x701A1A1A);
        guiGraphics.fill(left, top, right, top + 1, 0xFF454545);
        guiGraphics.fill(left, bottom - 1, right, bottom, 0xFF303030);
        guiGraphics.fill(left, top, left + 1, bottom, 0xFF454545);
        guiGraphics.fill(right - 1, top, right, bottom, 0xFF303030);

        guiGraphics.drawString(
                font,
                Component.literal(heading),
                left + 7,
                top + 5,
                0x909090,
                false
        );
    }

    private void drawKeyValue(
            GuiGraphics guiGraphics,
            int left,
            int y,
            int right,
            String key,
            String value
    ) {
        guiGraphics.drawString(
                font,
                Component.literal(key),
                left,
                y,
                0xA0A0A0,
                false
        );

        drawRightAlignedString(
                guiGraphics,
                value,
                right,
                y,
                0xE0E0E0
        );
    }

    private void drawCapacityBar(
            GuiGraphics guiGraphics,
            int x,
            int y,
            int width,
            int used,
            int capacity
    ) {
        guiGraphics.fill(x, y, x + width, y + 5, 0xFF292929);

        float ratio = capacity <= 0
                ? 0.0F
                : Mth.clamp(used / (float) capacity, 0.0F, 1.0F);

        int filled = Math.round(width * ratio);
        int barColor = used > capacity
                ? 0xFFB85C5C
                : ratio > 0.85F
                        ? 0xFFC6A45C
                        : 0xFF6E9A72;

        if (filled > 0) {
            guiGraphics.fill(x, y, x + filled, y + 5, barColor);
        }
    }

    private void drawRightAlignedString(
            GuiGraphics guiGraphics,
            String text,
            int right,
            int y,
            int color
    ) {
        guiGraphics.drawString(
                font,
                Component.literal(text),
                right - font.width(text),
                y,
                color,
                false
        );
    }

    private static String formatSu(float su) {
        if (Math.abs(su - Math.round(su)) < 0.01F) {
            return String.format(Locale.ROOT, "%,d", Math.round(su));
        }

        return String.format(Locale.ROOT, "%,.2f", su);
    }

    /**
     * Predict the SU number locally from the slider's current value so the menu
     * reacts immediately while dragging. The server remains authoritative and
     * receives the chosen strength when Done is pressed.
     */
    private float getDisplayedSu() {
        if (!strengthDirty && menu.hasServerStats()) {
            return menu.getAuthoritativeDisplayedSu();
        }

        float rpm = menu.getRpm() > 0.001F
                ? menu.getRpm()
                : menu.getTheoreticalRpm();

        return Math.max(
                0.0F,
                CloakingCoreBlockEntity.calculateRequiredSu(
                        menu.getAssignedBlockLoad(),
                        Mth.clamp(cloakStrength, 0.0F, 1.0F),
                        menu.getSystemEfficiencyFactor(),
                        rpm
                )
        );
    }

    private static String statusText(int status) {
        return switch (status) {
            case 1 -> "Below 32 RPM";
            case 2 -> "Overstressed";
            case 3 -> "Insufficient Capacity";
            case 4 -> "Ready";
            case 5 -> "Input Stalled";
            default -> "No Sublevel";
        };
    }

    private static int statusColor(int status) {
        return switch (status) {
            case 4 -> 0x88CC88;
            case 3 -> 0xFFCC66;
            case 1, 2, 5 -> 0xFF8888;
            default -> 0xA0A0A0;
        };
    }

    @Override
    protected void renderBg(
            GuiGraphics guiGraphics,
            float partialTick,
            int mouseX,
            int mouseY
    ) {
        guiGraphics.fill(
                leftPos - 1,
                topPos - 1,
                leftPos + imageWidth + 1,
                topPos + imageHeight + 1,
                0xFF404040
        );

        guiGraphics.fill(
                leftPos,
                topPos,
                leftPos + imageWidth,
                topPos + imageHeight,
                0xE0101010
        );

        drawSlotFrame(guiGraphics, 244, 188);
        drawSlotFrame(guiGraphics, 272, 188);

        int inventoryX = 79;
        int inventoryY = 248;

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                drawSlotFrame(
                        guiGraphics,
                        inventoryX + column * 18,
                        inventoryY + row * 18
                );
            }
        }

        for (int column = 0; column < 9; column++) {
            drawSlotFrame(
                    guiGraphics,
                    inventoryX + column * 18,
                    306
            );
        }
    }

    private void drawSlotFrame(GuiGraphics guiGraphics, int x, int y) {
        int screenX = leftPos + x;
        int screenY = topPos + y;

        guiGraphics.fill(
                screenX - 1,
                screenY - 1,
                screenX + 17,
                screenY + 17,
                0xFF5A5A5A
        );
        guiGraphics.fill(
                screenX,
                screenY,
                screenX + 16,
                screenY + 16,
                0xFF151515
        );
    }

    private static Component renderModeName(CloakRenderMode mode) {
        return switch (mode) {
            case DITHER -> Component.literal("Dither");
            case ALPHA -> Component.literal("Alpha (Classic)");
            case ALPHA_SURFACE -> Component.literal("Alpha (Surface)");
        };
    }

    private static final class ValueSlider extends AbstractSliderButton {

        private final double min;
        private final double max;
        private final DoubleConsumer setter;
        private final DoubleFunction<Component> formatter;

        private ValueSlider(
                int x,
                int y,
                int width,
                int height,
                double min,
                double max,
                double initialValue,
                DoubleConsumer setter,
                DoubleFunction<Component> formatter
        ) {
            super(
                    x,
                    y,
                    width,
                    height,
                    Component.empty(),
                    normalize(initialValue, min, max)
            );

            this.min = min;
            this.max = max;
            this.setter = setter;
            this.formatter = formatter;
            updateMessage();
        }

        private double actualValue() {
            return min + value * (max - min);
        }

        @Override
        protected void updateMessage() {
            setMessage(formatter.apply(actualValue()));
        }

        @Override
        protected void applyValue() {
            setter.accept(actualValue());
        }

        private void setValueFromScreenMouse(double mouseX) {
            double oldValue = value;

            value = Mth.clamp(
                    (mouseX - (getX() + 4.0))
                            / (getWidth() - 8.0),
                    0.0,
                    1.0
            );

            if (oldValue != value) {
                applyValue();
            }

            updateMessage();
        }

        private void setActualValueSilently(double actualValue) {
            value = normalize(actualValue, min, max);
            updateMessage();
        }

        private static double normalize(
                double value,
                double min,
                double max
        ) {
            if (max <= min) {
                return 0.0;
            }

            return Math.max(
                    0.0,
                    Math.min(1.0, (value - min) / (max - min))
            );
        }
    }
}
