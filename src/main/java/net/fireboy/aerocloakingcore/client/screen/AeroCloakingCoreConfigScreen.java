package net.fireboy.aerocloakingcore.client.screen;

import net.fireboy.aerocloakingcore.client.CloakEasing;
import net.fireboy.aerocloakingcore.cloak.CloakingServerSettings;
import net.fireboy.aerocloakingcore.network.RequestServerConfigPayload;
import net.fireboy.aerocloakingcore.network.ServerConfigClientState;
import net.fireboy.aerocloakingcore.network.ServerConfigSnapshotPayload;
import net.fireboy.aerocloakingcore.network.UpdateServerConfigPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Locale;

/**
 * Simple server-authoritative config editor shown from
 * Mods -> Aero Cloaking Core -> Config.
 */
public final class AeroCloakingCoreConfigScreen extends Screen {

    private static final int ROW_COUNT = 8;
    private static final int ROW_HEIGHT = 28;
    private static final int CONTENT_TOP = 54;

    private final Screen parent;

    private EditBox transitionDuration;
    private EditBox leaveGrace;
    private EditBox leaveFade;
    private EditBox fullyVisibleDistance;
    private EditBox fullyCloakedDistance;

    private Button easingButton;
    private Button visibleWhileAboardButton;
    private Button proximityRevealButton;

    private Button doneButton;
    private Button refreshButton;
    private Button saveButton;

    private CloakEasing transitionEasing = CloakEasing.SMOOTHSTEP;
    private boolean visibleWhileAboard = true;
    private boolean proximityRevealEnabled = true;

    private boolean valuesLoaded;
    private boolean canEdit;
    private long appliedRevision = -1L;

    private Component status = Component.translatable(
            "screen.aerocloakingcore.server_config.loading"
    );

    private int contentLeft;
    private int contentWidth;
    private int labelWidth;
    private int controlX;
    private int controlWidth;
    private int buttonY;
    private int contentScroll;
    private int maxScroll;

    public AeroCloakingCoreConfigScreen(Screen parent) {
        super(Component.translatable(
                "screen.aerocloakingcore.server_config.title"
        ));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        int gap = 12;
        contentWidth = Math.min(560, width - 40);
        controlWidth = Math.min(190, Math.max(140, contentWidth / 3));
        labelWidth = contentWidth - controlWidth - gap;
        contentLeft = (width - contentWidth) / 2;
        controlX = contentLeft + labelWidth + gap;

        int buttonWidth = 104;
        int buttonGap = 10;
        int buttonsWidth = buttonWidth * 3 + buttonGap * 2;
        int buttonX = (width - buttonsWidth) / 2;
        buttonY = height - 30;

        transitionDuration = createNumberBox(
                controlX,
                0,
                controlWidth,
                "screen.aerocloakingcore.server_config.transition_duration"
        );

        easingButton = addRenderableWidget(
                Button.builder(easingValueMessage(), button -> cycleEasing())
                        .bounds(controlX, 0, controlWidth, 20)
                        .build()
        );

        visibleWhileAboardButton = addRenderableWidget(
                Button.builder(
                                toggleMessage(visibleWhileAboard),
                                button -> {
                                    visibleWhileAboard = !visibleWhileAboard;
                                    refreshToggleMessages();
                                }
                        )
                        .bounds(controlX, 0, controlWidth, 20)
                        .build()
        );

        leaveGrace = createNumberBox(
                controlX,
                0,
                controlWidth,
                "screen.aerocloakingcore.server_config.leave_grace"
        );

        leaveFade = createNumberBox(
                controlX,
                0,
                controlWidth,
                "screen.aerocloakingcore.server_config.leave_fade"
        );

        proximityRevealButton = addRenderableWidget(
                Button.builder(
                                toggleMessage(proximityRevealEnabled),
                                button -> {
                                    proximityRevealEnabled = !proximityRevealEnabled;
                                    refreshToggleMessages();
                                }
                        )
                        .bounds(controlX, 0, controlWidth, 20)
                        .build()
        );

        fullyVisibleDistance = createNumberBox(
                controlX,
                0,
                controlWidth,
                "screen.aerocloakingcore.server_config.visible_distance"
        );

        fullyCloakedDistance = createNumberBox(
                controlX,
                0,
                controlWidth,
                "screen.aerocloakingcore.server_config.cloaked_distance"
        );

        doneButton = addRenderableWidget(
                Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                        .bounds(buttonX, buttonY, buttonWidth, 20)
                        .build()
        );

        refreshButton = addRenderableWidget(
                Button.builder(
                                Component.translatable(
                                        "screen.aerocloakingcore.server_config.refresh"
                                ),
                                button -> requestSnapshot()
                        )
                        .bounds(
                                buttonX + buttonWidth + buttonGap,
                                buttonY,
                                buttonWidth,
                                20
                        )
                        .build()
        );

        saveButton = addRenderableWidget(
                Button.builder(
                                Component.translatable(
                                        "screen.aerocloakingcore.server_config.save"
                                ),
                                button -> save()
                        )
                        .bounds(
                                buttonX + (buttonWidth + buttonGap) * 2,
                                buttonY,
                                buttonWidth,
                                20
                        )
                        .build()
        );

        ServerConfigClientState.Snapshot cached = ServerConfigClientState.get();

        if (cached.revision() > 0L) {
            applySnapshot(cached);
        } else {
            applySettings(CloakingServerSettings.DEFAULT);
        }

        updateScrollMetrics();
        requestSnapshot();
        updateControlState();
    }

    private EditBox createNumberBox(
            int x,
            int y,
            int width,
            String narrationKey
    ) {
        EditBox editBox = new EditBox(
                font,
                x,
                y,
                width,
                20,
                Component.translatable(narrationKey)
        );

        editBox.setMaxLength(12);
        editBox.setFilter(AeroCloakingCoreConfigScreen::isDecimalText);
        editBox.setTextColor(0xFFFFFF);
        editBox.setTextColorUneditable(0xA0A0A0);
        addRenderableWidget(editBox);
        return editBox;
    }

    private static boolean isDecimalText(String text) {
        if (text.isEmpty()) {
            return true;
        }

        boolean decimalSeen = false;

        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);

            if (character == '.') {
                if (decimalSeen) {
                    return false;
                }

                decimalSeen = true;
                continue;
            }

            if (!Character.isDigit(character)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public void tick() {
        super.tick();

        ServerConfigClientState.Snapshot current = ServerConfigClientState.get();
        if (current.revision() != appliedRevision) {
            applySnapshot(current);
        }
    }

    @Override
    public boolean mouseScrolled(
            double mouseX,
            double mouseY,
            double scrollX,
            double scrollY
    ) {
        if (maxScroll > 0) {
            int newScroll = clampScroll(contentScroll - (int) Math.signum(scrollY) * 16);
            if (newScroll != contentScroll) {
                contentScroll = newScroll;
                positionContentWidgets();
                return true;
            }
        }

        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void requestSnapshot() {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.getConnection() == null) {
            valuesLoaded = false;
            canEdit = false;
            status = Component.translatable(
                    "screen.aerocloakingcore.server_config.read_only"
            );
            updateControlState();
            return;
        }

        status = Component.translatable(
                "screen.aerocloakingcore.server_config.loading"
        );

        PacketDistributor.sendToServer(new RequestServerConfigPayload());
    }

    private void save() {
        if (!valuesLoaded || !canEdit) {
            return;
        }

        try {
            CloakingServerSettings settings = new CloakingServerSettings(
                    parseFloat(transitionDuration),
                    transitionEasing,
                    visibleWhileAboard,
                    parseFloat(leaveGrace),
                    parseFloat(leaveFade),
                    proximityRevealEnabled,
                    parseDouble(fullyVisibleDistance),
                    parseDouble(fullyCloakedDistance)
            ).normalized();

            status = Component.translatable(
                    "screen.aerocloakingcore.server_config.saving"
            );

            PacketDistributor.sendToServer(new UpdateServerConfigPayload(settings));
        } catch (NumberFormatException exception) {
            status = Component.translatable(
                    "screen.aerocloakingcore.server_config.invalid_number"
            );
        }
    }

    private static float parseFloat(EditBox editBox) {
        return Float.parseFloat(editBox.getValue());
    }

    private static double parseDouble(EditBox editBox) {
        return Double.parseDouble(editBox.getValue());
    }

    private void applySnapshot(ServerConfigClientState.Snapshot snapshot) {
        appliedRevision = snapshot.revision();
        valuesLoaded = true;
        canEdit = snapshot.permissionKnown() && snapshot.canEdit();

        applySettings(snapshot.settings());

        if (!snapshot.permissionKnown()) {
            status = Component.translatable(
                    "screen.aerocloakingcore.server_config.loading"
            );
        } else if (snapshot.result() == ServerConfigSnapshotPayload.Result.SAVED) {
            status = Component.translatable(
                    "screen.aerocloakingcore.server_config.saved"
            );
        } else if (snapshot.result() == ServerConfigSnapshotPayload.Result.DENIED) {
            status = Component.translatable(
                    "screen.aerocloakingcore.server_config.denied"
            );
        } else if (canEdit) {
            status = Component.translatable(
                    "screen.aerocloakingcore.server_config.operator"
            );
        } else {
            status = Component.translatable(
                    "screen.aerocloakingcore.server_config.read_only"
            );
        }

        updateControlState();
    }

    private void applySettings(CloakingServerSettings settings) {
        CloakingServerSettings normalized = settings.normalized();

        if (transitionDuration != null) {
            transitionDuration.setValue(
                    formatNumber(normalized.transitionDurationSeconds())
            );
        }

        transitionEasing = normalized.transitionEasing();
        visibleWhileAboard = normalized.visibleWhileAboard();

        if (leaveGrace != null) {
            leaveGrace.setValue(formatNumber(normalized.leaveGraceSeconds()));
        }

        if (leaveFade != null) {
            leaveFade.setValue(formatNumber(normalized.leaveFadeSeconds()));
        }

        proximityRevealEnabled = normalized.proximityRevealEnabled();

        if (fullyVisibleDistance != null) {
            fullyVisibleDistance.setValue(
                    formatNumber(normalized.fullyVisibleDistance())
            );
        }

        if (fullyCloakedDistance != null) {
            fullyCloakedDistance.setValue(
                    formatNumber(normalized.fullyCloakedDistance())
            );
        }

        refreshToggleMessages();
    }

    private void cycleEasing() {
        CloakEasing[] values = CloakEasing.values();
        int next = (transitionEasing.ordinal() + 1) % values.length;
        transitionEasing = values[next];
        easingButton.setMessage(easingValueMessage());
    }

    private Component easingValueMessage() {
        return Component.translatable(
                "screen.aerocloakingcore.server_config.easing."
                        + transitionEasing.name().toLowerCase(Locale.ROOT)
        );
    }

    private static Component toggleMessage(boolean enabled) {
        return Component.literal(enabled ? "ON" : "OFF");
    }

    private void refreshToggleMessages() {
        if (easingButton != null) {
            easingButton.setMessage(easingValueMessage());
        }

        if (visibleWhileAboardButton != null) {
            visibleWhileAboardButton.setMessage(toggleMessage(visibleWhileAboard));
        }

        if (proximityRevealButton != null) {
            proximityRevealButton.setMessage(toggleMessage(proximityRevealEnabled));
        }
    }

    private void updateControlState() {
        boolean editable = valuesLoaded && canEdit;

        setEditable(transitionDuration, editable);
        setEditable(leaveGrace, editable);
        setEditable(leaveFade, editable);
        setEditable(fullyVisibleDistance, editable);
        setEditable(fullyCloakedDistance, editable);

        if (easingButton != null) {
            easingButton.active = editable;
        }

        if (visibleWhileAboardButton != null) {
            visibleWhileAboardButton.active = editable;
        }

        if (proximityRevealButton != null) {
            proximityRevealButton.active = editable;
        }

        if (saveButton != null) {
            saveButton.active = editable;
        }
    }

    private static void setEditable(EditBox editBox, boolean editable) {
        if (editBox != null) {
            editBox.setEditable(editable);
            editBox.setFocused(false);
            editBox.active = editable;
        }
    }

    private void updateScrollMetrics() {
        int viewportHeight = Math.max(80, buttonY - CONTENT_TOP - 12);
        int contentHeight = ROW_COUNT * ROW_HEIGHT;
        maxScroll = Math.max(0, contentHeight - viewportHeight);
        contentScroll = clampScroll(contentScroll);
        positionContentWidgets();
    }

    private int clampScroll(int value) {
        return Math.max(0, Math.min(value, maxScroll));
    }

    private void positionContentWidgets() {
        positionRowWidget(transitionDuration, 0);
        positionRowWidget(easingButton, 1);
        positionRowWidget(visibleWhileAboardButton, 2);
        positionRowWidget(leaveGrace, 3);
        positionRowWidget(leaveFade, 4);
        positionRowWidget(proximityRevealButton, 5);
        positionRowWidget(fullyVisibleDistance, 6);
        positionRowWidget(fullyCloakedDistance, 7);
    }

    private void positionRowWidget(EditBox widget, int row) {
        if (widget != null) {
            widget.setX(controlX);
            widget.setY(rowY(row));
        }
    }

    private void positionRowWidget(Button widget, int row) {
        if (widget != null) {
            widget.setX(controlX);
            widget.setY(rowY(row));
            widget.setWidth(controlWidth);
        }
    }

    private int rowY(int row) {
        return CONTENT_TOP + row * ROW_HEIGHT - contentScroll;
    }

    private static String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.000001) {
            return Long.toString(Math.round(value));
        }

        return String.format(Locale.ROOT, "%.3f", value)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }

    @Override
    public void render(
            GuiGraphics guiGraphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        // Deliberately do not call renderBackground(). That method applies
        // Minecraft's in-game menu blur. A simple dark overlay keeps the world
        // visible and sharp behind the config screen.
        guiGraphics.fill(0, 0, width, height, 0xB0101010);

        guiGraphics.drawCenteredString(
                font,
                title,
                width / 2,
                22,
                0xFFFFFF
        );

        int scissorLeft = contentLeft - 2;
        int scissorTop = CONTENT_TOP - 2;
        int scissorRight = controlX + controlWidth + 2;
        int scissorBottom = buttonY - 8;

        guiGraphics.enableScissor(scissorLeft, scissorTop, scissorRight, scissorBottom);

        drawLabel(
                guiGraphics,
                CONTENT_TOP + 0 * ROW_HEIGHT - contentScroll,
                "screen.aerocloakingcore.server_config.transition_duration"
        );
        drawLabel(
                guiGraphics,
                CONTENT_TOP + 1 * ROW_HEIGHT - contentScroll,
                "screen.aerocloakingcore.server_config.easing"
        );
        drawLabel(
                guiGraphics,
                CONTENT_TOP + 2 * ROW_HEIGHT - contentScroll,
                "screen.aerocloakingcore.server_config.visible_aboard"
        );
        drawLabel(
                guiGraphics,
                CONTENT_TOP + 3 * ROW_HEIGHT - contentScroll,
                "screen.aerocloakingcore.server_config.leave_grace"
        );
        drawLabel(
                guiGraphics,
                CONTENT_TOP + 4 * ROW_HEIGHT - contentScroll,
                "screen.aerocloakingcore.server_config.leave_fade"
        );
        drawLabel(
                guiGraphics,
                CONTENT_TOP + 5 * ROW_HEIGHT - contentScroll,
                "screen.aerocloakingcore.server_config.proximity_reveal"
        );
        drawLabel(
                guiGraphics,
                CONTENT_TOP + 6 * ROW_HEIGHT - contentScroll,
                "screen.aerocloakingcore.server_config.visible_distance"
        );
        drawLabel(
                guiGraphics,
                CONTENT_TOP + 7 * ROW_HEIGHT - contentScroll,
                "screen.aerocloakingcore.server_config.cloaked_distance"
        );

        renderWidget(transitionDuration, guiGraphics, mouseX, mouseY, partialTick);
        renderWidget(easingButton, guiGraphics, mouseX, mouseY, partialTick);
        renderWidget(visibleWhileAboardButton, guiGraphics, mouseX, mouseY, partialTick);
        renderWidget(leaveGrace, guiGraphics, mouseX, mouseY, partialTick);
        renderWidget(leaveFade, guiGraphics, mouseX, mouseY, partialTick);
        renderWidget(proximityRevealButton, guiGraphics, mouseX, mouseY, partialTick);
        renderWidget(fullyVisibleDistance, guiGraphics, mouseX, mouseY, partialTick);
        renderWidget(fullyCloakedDistance, guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.disableScissor();

        renderWidget(doneButton, guiGraphics, mouseX, mouseY, partialTick);
        renderWidget(refreshButton, guiGraphics, mouseX, mouseY, partialTick);
        renderWidget(saveButton, guiGraphics, mouseX, mouseY, partialTick);
    }

    private void renderWidget(
            Button widget,
            GuiGraphics guiGraphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        if (widget != null) {
            widget.render(guiGraphics, mouseX, mouseY, partialTick);
        }
    }

    private void renderWidget(
            EditBox widget,
            GuiGraphics guiGraphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        if (widget != null) {
            widget.render(guiGraphics, mouseX, mouseY, partialTick);
        }
    }

    private void drawLabel(
            GuiGraphics guiGraphics,
            int y,
            String translationKey
    ) {
        guiGraphics.drawString(
                font,
                Component.translatable(translationKey),
                contentLeft,
                y + 6,
                0xD0D0D0,
                false
        );
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
