package net.fireboy.aerocloakingcore.client.screen;

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

public class CloakingCoreScreen extends AbstractContainerScreen<CloakingCoreMenu> {

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

        imageWidth = 300;
        imageHeight = 294;

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
                topPos + 32,
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
                                topPos + 60,
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
                        .bounds(x, topPos + 185, halfWidth, 20)
                        .build()
        );

        addRenderableWidget(
                Button.builder(
                                CommonComponents.GUI_CANCEL,
                                button -> onClose()
                        )
                        .bounds(rightX, topPos + 185, halfWidth, 20)
                        .build()
        );
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

            // Immediate client visual feedback. The server remains authoritative.
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

        drawSystemStats(guiGraphics);

        guiGraphics.drawCenteredString(
                font,
                Component.translatable(
                        "screen.aerocloakingcore.cloaking_core.link_frequency"
                ),
                imageWidth / 2,
                146,
                0xD0D0D0
        );

        guiGraphics.drawCenteredString(
                font,
                Component.translatable(
                        "screen.aerocloakingcore.cloaking_core.redstone_hint"
                ),
                imageWidth / 2,
                177,
                0xA0A0A0
        );

        guiGraphics.drawString(
                font,
                Component.translatable("container.inventory"),
                69,
                207,
                0xA0A0A0,
                false
        );
    }

    private void drawSystemStats(GuiGraphics guiGraphics) {
        int left = 20;
        int right = imageWidth - 20;

        // Small divider so the live Create-system readout is visually separate
        // from the editable cloak controls above it.
        guiGraphics.fill(left, 84, right, 85, 0xFF505050);

        guiGraphics.drawString(
                font,
                Component.literal("Status: " + statusText(menu.getSystemStatus())),
                left,
                89,
                statusColor(menu.getSystemStatus()),
                false
        );

        guiGraphics.drawString(
                font,
                Component.literal(String.format(
                        Locale.ROOT,
                        "Ship: %,d blocks   Cores: %d",
                        menu.getSystemBlocks(),
                        menu.getSystemCoreCount()
                )),
                left,
                100,
                0xD0D0D0,
                false
        );

        guiGraphics.drawString(
                font,
                Component.literal(String.format(
                        Locale.ROOT,
                        "This core: %.1f RPM   Cap: %,d   Load: %,d",
                        menu.getRpm(),
                        menu.getCorePotentialCapacity(),
                        menu.getAssignedBlocks()
                )),
                left,
                111,
                0xD0D0D0,
                false
        );

        guiGraphics.drawString(
                font,
                Component.literal(String.format(
                        Locale.ROOT,
                        "System cap: %,d / %,d   Core stress: %,d SU",
                        menu.getSystemOperationalCapacity(),
                        menu.getSystemBlocks(),
                        menu.getCurrentSu()
                )),
                left,
                122,
                0xD0D0D0,
                false
        );

        guiGraphics.drawString(
                font,
                Component.literal(String.format(
                        Locale.ROOT,
                        "Cloak transition: %.2fs",
                        menu.getTransitionSeconds()
                )),
                left,
                133,
                0xA0A0A0,
                false
        );
    }

    private static String statusText(int status) {
        return switch (status) {
            case 1 -> "Core below minimum RPM";
            case 2 -> "Core network overstressed";
            case 3 -> "Insufficient ship capacity";
            case 4 -> "Ready";
            default -> "No Sable sublevel";
        };
    }

    private static int statusColor(int status) {
        return switch (status) {
            case 4 -> 0xD0D0D0;
            case 3 -> 0xFFCC66;
            case 1, 2 -> 0xFF8888;
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

        // Frequency slot frames.
        drawSlotFrame(guiGraphics, 127, 157);
        drawSlotFrame(guiGraphics, 157, 157);

        // Player inventory slot frames.
        int inventoryX = 69;
        int inventoryY = 217;

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
                    275
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
