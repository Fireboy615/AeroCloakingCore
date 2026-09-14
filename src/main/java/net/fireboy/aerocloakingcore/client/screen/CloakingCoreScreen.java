package net.fireboy.aerocloakingcore.client.screen;

import net.fireboy.aerocloakingcore.client.CloakRenderMode;
import net.fireboy.aerocloakingcore.cloak.CloakingCoreSettings;
import net.fireboy.aerocloakingcore.menu.CloakingCoreMenu;
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
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

public class CloakingCoreScreen extends AbstractContainerScreen<CloakingCoreMenu> {

    private float cloakStrength;
    private CloakRenderMode renderMode;

    private ValueSlider strengthSlider;
    private boolean draggingStrengthSlider = false;

    /**
     * Changing render mode alone must not take control away from Redstone Link.
     * The strength slider only becomes a manual override after the user moves it.
     */
    private boolean strengthDirty = false;

    public CloakingCoreScreen(
            CloakingCoreMenu menu,
            Inventory playerInventory,
            Component title
    ) {
        super(menu, playerInventory, title);

        imageWidth = 300;
        imageHeight = 150;

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
                topPos + 38,
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
                                topPos + 68,
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
                        .bounds(x, topPos + 112, halfWidth, 20)
                        .build()
        );

        addRenderableWidget(
                Button.builder(
                                CommonComponents.GUI_CANCEL,
                                button -> onClose()
                        )
                        .bounds(rightX, topPos + 112, halfWidth, 20)
                        .build()
        );
    }


    @Override
    public boolean mouseClicked(
            double mouseX,
            double mouseY,
            int button
    ) {
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

        guiGraphics.drawCenteredString(
                font,
                Component.translatable(
                        "screen.aerocloakingcore.cloaking_core.redstone_hint"
                ),
                imageWidth / 2,
                94,
                0xA0A0A0
        );
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
    }

    private static Component renderModeName(CloakRenderMode mode) {
        return switch (mode) {
            case DITHER -> Component.literal("Dither");
            case ALPHA -> Component.literal("Alpha");
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
