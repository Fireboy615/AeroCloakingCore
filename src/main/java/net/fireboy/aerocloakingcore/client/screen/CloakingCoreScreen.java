package net.fireboy.aerocloakingcore.client.screen;

import net.fireboy.aerocloakingcore.block.entity.CloakingCoreBlockEntity;
import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.fireboy.aerocloakingcore.cloak.CloakingCoreSettings;
import net.fireboy.aerocloakingcore.menu.CloakingCoreMenu;
import net.fireboy.aerocloakingcore.network.UpdateCloakingCoreLinkFrequencyPayload;
import net.fireboy.aerocloakingcore.network.UpdateCloakingCoreSettingsPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
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
 * Compact Create-inspired control screen for the Cloaking Core.
 *
 * Settings are applied as soon as the player changes them. There is no
 * separate Done/Save action: Escape/E can simply close the screen at any time.
 */
public class CloakingCoreScreen extends AbstractContainerScreen<CloakingCoreMenu> {

    private static final int GUI_WIDTH = 176;
    private static final int GUI_HEIGHT = 222;

    private static final int CONTROL_X = 8;
    private static final int CONTROL_WIDTH = 160;

    private static final int INVENTORY_X = 7;
    private static final int INVENTORY_Y = 149;
    private static final int HOTBAR_Y = 205;

    private static final int FREQ_FIRST_X = 14;
    private static final int FREQ_SECOND_X = 36;
    private static final int FREQ_Y = 112;

    /** Prevents an older server echo from snapping the slider backwards. */
    private static final long LOCAL_EDIT_GRACE_NANOS = 300_000_000L;

    private float cloakStrength;
    private CloakRenderMode renderMode;

    private ValueSlider strengthSlider;
    private boolean draggingStrengthSlider;
    private long ignoreServerStrengthUntilNanos;

    public CloakingCoreScreen(
            CloakingCoreMenu menu,
            Inventory playerInventory,
            Component title
    ) {
        super(menu, playerInventory, title);

        imageWidth = GUI_WIDTH;
        imageHeight = GUI_HEIGHT;

        CloakingCoreSettings settings = menu.getInitialSettings();
        cloakStrength = settings.cloakStrength();
        renderMode = settings.renderMode();
    }

    @Override
    protected void init() {
        super.init();

        strengthSlider = new ValueSlider(
                leftPos + CONTROL_X,
                topPos + 24,
                CONTROL_WIDTH,
                16,
                0.0,
                1.0,
                cloakStrength,
                value -> {
                    cloakStrength = (float) value;
                    ignoreServerStrengthUntilNanos =
                            System.nanoTime() + LOCAL_EDIT_GRACE_NANOS;
                    sendSettings(true);
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
                                leftPos + CONTROL_X,
                                topPos + 44,
                                CONTROL_WIDTH,
                                16,
                                Component.literal("Render Mode"),
                                (button, value) -> {
                                    renderMode = value;
                                    sendSettings(false);
                                }
                        )
        );
    }

    @Override
    protected void containerTick() {
        super.containerTick();

        if (draggingStrengthSlider
                || strengthSlider == null
                || !menu.hasServerStats()
                || System.nanoTime() < ignoreServerStrengthUntilNanos) {
            return;
        }

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
            ignoreServerStrengthUntilNanos =
                    System.nanoTime() + LOCAL_EDIT_GRACE_NANOS;
        }

        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void sendSettings(boolean updateStrength) {
        PacketDistributor.sendToServer(
                new UpdateCloakingCoreSettingsPayload(
                        menu.containerId,
                        updateStrength,
                        cloakStrength,
                        renderMode
                )
        );
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
                6,
                0x343434
        );

        drawSystemOverview(guiGraphics);
        drawRedstoneLink(guiGraphics);

        guiGraphics.drawString(
                font,
                Component.translatable("container.inventory"),
                INVENTORY_X,
                138,
                0x404040,
                false
        );
    }

    private void drawSystemOverview(GuiGraphics guiGraphics) {
        int status = menu.getSystemStatus();

        guiGraphics.drawString(
                font,
                Component.literal("Cloaking System"),
                10,
                65,
                0x4A3D2B,
                false
        );

        drawStatusIndicator(guiGraphics, status, 164, 68);
        drawRightAlignedString(
                guiGraphics,
                statusText(status),
                158,
                65,
                statusColor(status)
        );

        int leftX = 10;
        int rightX = 90;
        int rightEdge = 166;

        guiGraphics.drawString(
                font,
                Component.literal("CORE"),
                leftX,
                76,
                0x765F37,
                false
        );
        guiGraphics.drawString(
                font,
                Component.literal("SHIP"),
                rightX,
                76,
                0x765F37,
                false
        );

        guiGraphics.drawString(
                font,
                Component.literal(String.format(
                        Locale.ROOT,
                        "%.0f RPM",
                        menu.getRpm()
                )),
                leftX,
                86,
                0x343434,
                false
        );

        String shipLine = String.format(
                Locale.ROOT,
                "%d core%s  %.0f avg",
                menu.getSystemCoreCount(),
                menu.getSystemCoreCount() == 1 ? "" : "s",
                menu.getSystemRpm()
        );
        drawScaledRightAlignedString(
                guiGraphics,
                shipLine,
                rightEdge,
                86,
                0.78F,
                0x343434
        );

        String coreCapacity = String.format(
                Locale.ROOT,
                "%d / %d",
                menu.getAssignedBlocks(),
                menu.getCorePotentialCapacity()
        );
        String shipCapacity = String.format(
                Locale.ROOT,
                "%d / %d",
                menu.getSystemBlocks(),
                menu.getSystemOperationalCapacity()
        );

        guiGraphics.drawString(
                font,
                Component.literal(coreCapacity),
                leftX,
                95,
                0x565656,
                false
        );
        drawRightAlignedString(
                guiGraphics,
                shipCapacity,
                rightEdge,
                95,
                0x565656
        );

        drawCapacityBar(
                guiGraphics,
                leftX,
                105,
                70,
                menu.getAssignedBlocks(),
                menu.getCorePotentialCapacity()
        );
        drawCapacityBar(
                guiGraphics,
                rightX,
                105,
                rightEdge - rightX,
                menu.getSystemBlocks(),
                menu.getSystemOperationalCapacity()
        );

        String metrics = String.format(
                Locale.ROOT,
                "%s SU   %d%% eff   R %.1f   T %.1fs",
                formatSu(getDisplayedSu()),
                Math.round(menu.getSystemEfficiencyFactor() * 100.0F),
                menu.getRevealStartDistance(),
                menu.getTransitionSeconds()
        );
        drawScaledString(guiGraphics, metrics, 10, 110, 0.69F, 0x5E5140);
    }

    private void drawRedstoneLink(GuiGraphics guiGraphics) {
        guiGraphics.drawString(
                font,
                Component.literal("Redstone Link"),
                60,
                116,
                0x40372D,
                false
        );
        drawScaledString(
                guiGraphics,
                "0-15 -> cloak strength",
                60,
                125,
                0.68F,
                0x6C5A47
        );
    }

    private void drawStatusIndicator(
            GuiGraphics guiGraphics,
            int status,
            int centerX,
            int centerY
    ) {
        int color = statusColor(status);
        guiGraphics.fill(
                centerX - 2,
                centerY - 2,
                centerX + 3,
                centerY + 3,
                0xFF4B4338
        );
        guiGraphics.fill(
                centerX - 1,
                centerY - 1,
                centerX + 2,
                centerY + 2,
                0xFF000000 | color
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
        guiGraphics.fill(x, y, x + width, y + 4, 0xFF5F5447);
        guiGraphics.fill(x + 1, y + 1, x + width - 1, y + 3, 0xFFBBA989);

        float ratio = capacity <= 0
                ? 0.0F
                : Mth.clamp(used / (float) capacity, 0.0F, 1.0F);

        int filled = Math.round((width - 2) * ratio);
        int barColor = used > capacity
                ? 0xFFC15C54
                : ratio > 0.85F
                        ? 0xFFD0A44E
                        : 0xFF709B6E;

        if (filled > 0) {
            guiGraphics.fill(x + 1, y + 1, x + 1 + filled, y + 3, barColor);
        }
    }

    private void drawScaledString(
            GuiGraphics guiGraphics,
            String text,
            int x,
            int y,
            float scale,
            int color
    ) {
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, y, 0.0F);
        guiGraphics.pose().scale(scale, scale, 1.0F);
        guiGraphics.drawString(font, Component.literal(text), 0, 0, color, false);
        guiGraphics.pose().popPose();
    }

    private void drawScaledRightAlignedString(
            GuiGraphics guiGraphics,
            String text,
            int right,
            int y,
            float scale,
            int color
    ) {
        int width = font.width(text);

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(right, y, 0.0F);
        guiGraphics.pose().scale(scale, scale, 1.0F);
        guiGraphics.drawString(
                font,
                Component.literal(text),
                -width,
                0,
                color,
                false
        );
        guiGraphics.pose().popPose();
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

        return String.format(Locale.ROOT, "%,.1f", su);
    }

    /**
     * While the player drags the slider, predict SU locally. As soon as the
     * server echo arrives the authoritative Create stress value takes over.
     */
    private float getDisplayedSu() {
        if (!draggingStrengthSlider
                && System.nanoTime() >= ignoreServerStrengthUntilNanos
                && menu.hasServerStats()) {
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
            case 1 -> "Low RPM";
            case 2 -> "Overstressed";
            case 3 -> "No Capacity";
            case 4 -> "Ready";
            case 5 -> "Stalled";
            default -> "No Ship";
        };
    }

    private static int statusColor(int status) {
        return switch (status) {
            case 4 -> 0x4A8A50;
            case 3 -> 0xA97922;
            case 1, 2, 5 -> 0xA94E4E;
            default -> 0x6F6F6F;
        };
    }

    @Override
    protected void renderBg(
            GuiGraphics guiGraphics,
            float partialTick,
            int mouseX,
            int mouseY
    ) {
        drawCreateControlPanel(guiGraphics);
        drawInventoryPanel(guiGraphics);

        drawFrequencySlot(
                guiGraphics,
                FREQ_FIRST_X,
                FREQ_Y,
                0xFFB64A4A,
                0xFF7E3939,
                0xFFB7877F
        );
        drawFrequencySlot(
                guiGraphics,
                FREQ_SECOND_X,
                FREQ_Y,
                0xFF4E70B8,
                0xFF3B5687,
                0xFF8799B9
        );

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                drawInventorySlot(
                        guiGraphics,
                        INVENTORY_X + column * 18,
                        INVENTORY_Y + row * 18
                );
            }
        }

        for (int column = 0; column < 9; column++) {
            drawInventorySlot(
                    guiGraphics,
                    INVENTORY_X + column * 18,
                    HOTBAR_Y
            );
        }
    }

    private void drawCreateControlPanel(GuiGraphics guiGraphics) {
        int left = leftPos;
        int top = topPos;
        int right = leftPos + imageWidth;

        // Dark Create-style outline and grey title rail.
        guiGraphics.fill(left - 2, top - 2, right + 2, top + 132, 0xFF282828);
        guiGraphics.fill(left, top, right, top + 20, 0xFFAEB1AE);
        guiGraphics.fill(left + 2, top + 2, right - 2, top + 18, 0xFFBFC1BE);
        guiGraphics.fill(left, top + 18, right, top + 21, 0xFF555753);

        // Warm Create brass/canvas body.
        guiGraphics.fill(left, top + 21, right, top + 130, 0xFFCBB595);
        drawCreatePattern(guiGraphics, left + 2, top + 22, right - 2, top + 129);

        // Lower rail of the control module.
        guiGraphics.fill(left, top + 130, right, top + 132, 0xFF78756F);
    }

    private void drawCreatePattern(
            GuiGraphics guiGraphics,
            int left,
            int top,
            int right,
            int bottom
    ) {
        for (int y = top; y < bottom; y += 6) {
            int offset = ((y - top) / 6 & 1) == 0 ? 0 : 3;

            for (int x = left + offset; x < right; x += 6) {
                guiGraphics.fill(
                        x,
                        y,
                        Math.min(x + 2, right),
                        Math.min(y + 2, bottom),
                        0x18FFFFFF
                );
            }
        }
    }

    private void drawInventoryPanel(GuiGraphics guiGraphics) {
        int left = leftPos;
        int top = topPos + 134;
        int right = leftPos + imageWidth;
        int bottom = topPos + imageHeight;

        guiGraphics.fill(left - 2, top - 2, right + 2, bottom + 2, 0xFF303030);
        guiGraphics.fill(left, top, right, bottom, 0xFFC6C6C6);
        guiGraphics.fill(left + 1, top + 1, right - 1, top + 2, 0xFFF0F0F0);
        guiGraphics.fill(left + 1, top + 1, left + 2, bottom - 1, 0xFFF0F0F0);
        guiGraphics.fill(right - 2, top + 2, right - 1, bottom - 1, 0xFF767676);
        guiGraphics.fill(left + 2, bottom - 2, right - 1, bottom - 1, 0xFF767676);
    }

    private void drawInventorySlot(GuiGraphics guiGraphics, int x, int y) {
        int screenX = leftPos + x;
        int screenY = topPos + y;

        guiGraphics.fill(screenX - 1, screenY - 1, screenX + 17, screenY + 17, 0xFF6B6B6B);
        guiGraphics.fill(screenX, screenY, screenX + 16, screenY + 16, 0xFF939393);
        guiGraphics.fill(screenX, screenY, screenX + 16, screenY + 1, 0xFF505050);
        guiGraphics.fill(screenX, screenY, screenX + 1, screenY + 16, 0xFF505050);
        guiGraphics.fill(screenX, screenY + 15, screenX + 16, screenY + 16, 0xFFE8E8E8);
        guiGraphics.fill(screenX + 15, screenY, screenX + 16, screenY + 16, 0xFFE8E8E8);
    }

    private void drawFrequencySlot(
            GuiGraphics guiGraphics,
            int x,
            int y,
            int border,
            int background,
            int emptyTint
    ) {
        int screenX = leftPos + x;
        int screenY = topPos + y;

        guiGraphics.fill(screenX - 2, screenY - 2, screenX + 18, screenY + 18, border);
        guiGraphics.fill(screenX - 1, screenY - 1, screenX + 17, screenY + 17, background);
        guiGraphics.fill(screenX, screenY, screenX + 16, screenY + 16, emptyTint);
        guiGraphics.fill(screenX + 2, screenY + 2, screenX + 14, screenY + 14, 0xFF8F8F8F);
    }

    private static Component renderModeName(CloakRenderMode mode) {
        return switch (mode) {
            case DITHER -> Component.literal("Dither");
            case ALPHA -> Component.literal("Alpha (Classic)");
            case ALPHA_SURFACE -> Component.literal("Alpha (Surface)");
        };
    }

    /** Create-themed light slider instead of the very dark vanilla slider. */
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

        @Override
        public void renderWidget(
                GuiGraphics guiGraphics,
                int mouseX,
                int mouseY,
                float partialTick
        ) {
            int x = getX();
            int y = getY();
            int width = getWidth();
            int height = getHeight();

            guiGraphics.fill(x, y, x + width, y + height, 0xFF4A4A47);
            guiGraphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFFB8B8B2);
            guiGraphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, 0xFFD0CEC5);

            int trackLeft = x + 5;
            int trackRight = x + width - 5;
            int trackY = y + height - 5;

            guiGraphics.fill(trackLeft, trackY, trackRight, trackY + 2, 0xFF695D4E);

            int knobX = trackLeft + (int) Math.round((trackRight - trackLeft) * value);
            guiGraphics.fill(knobX - 2, y + 2, knobX + 3, y + height - 2, 0xFF6A5A43);
            guiGraphics.fill(knobX - 1, y + 3, knobX + 2, y + height - 3, 0xFFB89B62);

            Minecraft minecraft = Minecraft.getInstance();
            guiGraphics.drawCenteredString(
                    minecraft.font,
                    getMessage(),
                    x + width / 2,
                    y + 4,
                    active ? 0x303030 : 0x777777
            );
        }

        private void setValueFromScreenMouse(double mouseX) {
            double oldValue = value;

            value = Mth.clamp(
                    (mouseX - (getX() + 5.0))
                            / (getWidth() - 10.0),
                    0.0,
                    1.0
            );

            if (Math.abs(oldValue - value) > 0.000001) {
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
