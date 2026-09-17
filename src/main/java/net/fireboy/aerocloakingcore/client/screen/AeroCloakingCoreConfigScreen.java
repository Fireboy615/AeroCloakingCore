package net.fireboy.aerocloakingcore.client.screen;

import net.fireboy.aerocloakingcore.client.CloakEasing;
import net.fireboy.aerocloakingcore.cloak.CloakingServerSettings;
import net.fireboy.aerocloakingcore.cloak.RopeCloakBehavior;
import net.fireboy.aerocloakingcore.network.RequestServerConfigPayload;
import net.fireboy.aerocloakingcore.network.ServerConfigClientState;
import net.fireboy.aerocloakingcore.network.UpdateServerConfigPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
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

    private static final int CONTENT_TOP = 54;
    private static final int ROW_HEIGHT = 28;
    private static final int SECTION_HEADER_HEIGHT = 20;
    private static final int SECTION_GAP = 8;
    private static final int BOTTOM_PADDING = 8;

    private static final int TRANSITION_HEADER = 0;
    private static final int TRANSITION_DURATION =
            TRANSITION_HEADER + SECTION_HEADER_HEIGHT;
    private static final int TRANSITION_EASING =
            TRANSITION_DURATION + ROW_HEIGHT;

    private static final int VIEWER_HEADER =
            TRANSITION_EASING + ROW_HEIGHT + SECTION_GAP;
    private static final int VISIBLE_ABOARD =
            VIEWER_HEADER + SECTION_HEADER_HEIGHT;
    private static final int ABOARD_FADE =
            VISIBLE_ABOARD + ROW_HEIGHT;
    private static final int LEAVE_GRACE =
            ABOARD_FADE + ROW_HEIGHT;
    private static final int LEAVE_FADE =
            LEAVE_GRACE + ROW_HEIGHT;

    private static final int PROXIMITY_HEADER =
            LEAVE_FADE + ROW_HEIGHT + SECTION_GAP;
    private static final int PROXIMITY_REVEAL =
            PROXIMITY_HEADER + SECTION_HEADER_HEIGHT;
    private static final int FULLY_VISIBLE_DISTANCE =
            PROXIMITY_REVEAL + ROW_HEIGHT;
    private static final int REVEAL_MULTIPLIER =
            FULLY_VISIBLE_DISTANCE + ROW_HEIGHT;

    private static final int ROPE_HEADER =
            REVEAL_MULTIPLIER + ROW_HEIGHT + SECTION_GAP;
    private static final int ROPE_BEHAVIOR =
            ROPE_HEADER + SECTION_HEADER_HEIGHT;

    private static final int CONTENT_HEIGHT =
            ROPE_BEHAVIOR + ROW_HEIGHT + BOTTOM_PADDING;

    private static final int SCROLLBAR_WIDTH = 6;
    private static final int SCROLLBAR_GAP = 8;
    private static final int MIN_THUMB_HEIGHT = 24;

    private final Screen parent;

    private EditBox transitionDuration;
    private EditBox aboardFade;
    private EditBox leaveGrace;
    private EditBox leaveFade;
    private EditBox fullyVisibleDistance;
    private EditBox revealDistanceMultiplier;

    private Button easingButton;
    private Button visibleWhileAboardButton;
    private Button proximityRevealButton;
    private Button ropeBehaviorButton;

    private Button cancelButton;
    private Button resetButton;
    private Button saveButton;

    private CloakEasing transitionEasing = CloakEasing.SMOOTHSTEP;
    private boolean visibleWhileAboard = true;
    private boolean proximityRevealEnabled = true;
    private RopeCloakBehavior ropeCloakBehavior = RopeCloakBehavior.GRADIENT;

    private boolean valuesLoaded;
    private boolean canEdit;
    private long appliedRevision = -1L;

    private CloakingServerSettings loadedSettings = CloakingServerSettings.DEFAULT;

    private int contentLeft;
    private int contentWidth;
    private int labelWidth;
    private int controlX;
    private int controlWidth;
    private int buttonY;
    private int viewportBottom;

    private int contentScroll;
    private int maxScroll;

    private int scrollBarX;
    private boolean draggingScrollBar;
    private double scrollDragOffset;

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
        contentWidth = Math.min(560, Math.max(260, width - 64));
        controlWidth = Math.min(190, Math.max(120, contentWidth * 2 / 5));
        labelWidth = contentWidth - controlWidth - gap;
        contentLeft = (width - contentWidth) / 2;
        controlX = contentLeft + labelWidth + gap;
        scrollBarX = contentLeft + contentWidth + SCROLLBAR_GAP;

        int buttonWidth = 104;
        int buttonGap = 10;
        int buttonsWidth = buttonWidth * 3 + buttonGap * 2;
        int buttonX = (width - buttonsWidth) / 2;
        buttonY = height - 30;
        viewportBottom = buttonY - 10;

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

        aboardFade = createNumberBox(
                controlX,
                0,
                controlWidth,
                "screen.aerocloakingcore.server_config.aboard_fade"
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

        revealDistanceMultiplier = createNumberBox(
                controlX,
                0,
                controlWidth,
                "screen.aerocloakingcore.server_config.reveal_distance_multiplier"
        );
        ropeBehaviorButton = addRenderableWidget(
                Button.builder(ropeBehaviorMessage(), button -> cycleRopeBehavior())
                        .bounds(controlX, 0, controlWidth, 20)
                        .build()
        );


        cancelButton = addRenderableWidget(
                Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                        .bounds(buttonX, buttonY, buttonWidth, 20)
                        .build()
        );

        resetButton = addRenderableWidget(
                Button.builder(
                                Component.translatable(
                                        "screen.aerocloakingcore.server_config.reset"
                                ),
                                button -> resetChanges()
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
            loadedSettings = CloakingServerSettings.DEFAULT.normalized();
            applySettings(loadedSettings);
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
        if (maxScroll > 0
                && mouseY >= CONTENT_TOP
                && mouseY <= viewportBottom) {
            int newScroll = clampScroll(
                    contentScroll - (int) Math.round(scrollY * ROW_HEIGHT)
            );

            if (newScroll != contentScroll) {
                setContentScroll(newScroll);
                return true;
            }
        }

        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isInsideScrollBar(mouseX, mouseY) && maxScroll > 0) {
            int thumbY = getThumbY();
            int thumbHeight = getThumbHeight();

            if (mouseY >= thumbY && mouseY <= thumbY + thumbHeight) {
                draggingScrollBar = true;
                scrollDragOffset = mouseY - thumbY;
            } else {
                draggingScrollBar = true;
                scrollDragOffset = thumbHeight / 2.0;
                setScrollFromThumb(mouseY - scrollDragOffset);
            }

            return true;
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
        if (button == 0 && draggingScrollBar) {
            setScrollFromThumb(mouseY - scrollDragOffset);
            return true;
        }

        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingScrollBar) {
            draggingScrollBar = false;
            return true;
        }

        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void requestSnapshot() {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.getConnection() == null) {
            valuesLoaded = false;
            canEdit = false;
            updateControlState();
            return;
        }

        PacketDistributor.sendToServer(new RequestServerConfigPayload());
    }

    private void resetChanges() {
        if (!valuesLoaded || !canEdit) {
            return;
        }

        applySettings(loadedSettings);
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
                    parseFloat(aboardFade),
                    parseFloat(leaveGrace),
                    parseFloat(leaveFade),
                    proximityRevealEnabled,
                    parseDouble(fullyVisibleDistance),
                    parseDouble(revealDistanceMultiplier),
                    ropeCloakBehavior
            ).normalized();

            PacketDistributor.sendToServer(new UpdateServerConfigPayload(settings));
        } catch (NumberFormatException ignored) {
            // Invalid text is simply left unsaved. The numeric filters prevent
            // almost all invalid input, including multiple decimal points.
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

        loadedSettings = snapshot.settings().normalized();
        applySettings(loadedSettings);
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

        if (aboardFade != null) {
            aboardFade.setValue(formatNumber(normalized.aboardFadeSeconds()));
        }

        if (leaveGrace != null) {
            leaveGrace.setValue(formatNumber(normalized.leaveGraceSeconds()));
        }

        if (leaveFade != null) {
            leaveFade.setValue(formatNumber(normalized.leaveFadeSeconds()));
        }

        proximityRevealEnabled = normalized.proximityRevealEnabled();
        ropeCloakBehavior = normalized.ropeCloakBehavior();

        if (fullyVisibleDistance != null) {
            fullyVisibleDistance.setValue(
                    formatNumber(normalized.fullyVisibleDistance())
            );
        }

        if (revealDistanceMultiplier != null) {
            revealDistanceMultiplier.setValue(
                    formatNumber(normalized.revealDistanceMultiplier())
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

    private void cycleRopeBehavior() {
        RopeCloakBehavior[] values = RopeCloakBehavior.values();
        int next = (ropeCloakBehavior.ordinal() + 1) % values.length;
        ropeCloakBehavior = values[next];
        if (ropeBehaviorButton != null) {
            ropeBehaviorButton.setMessage(ropeBehaviorMessage());
        }
    }

    private Component ropeBehaviorMessage() {
        return Component.translatable(
                "screen.aerocloakingcore.server_config.rope_behavior."
                        + ropeCloakBehavior.name().toLowerCase(Locale.ROOT)
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

        if (ropeBehaviorButton != null) {
            ropeBehaviorButton.setMessage(ropeBehaviorMessage());
        }
    }

    private void updateControlState() {
        boolean editable = valuesLoaded && canEdit;

        setEditable(transitionDuration, editable);
        setEditable(aboardFade, editable);
        setEditable(leaveGrace, editable);
        setEditable(leaveFade, editable);
        setEditable(fullyVisibleDistance, editable);
        setEditable(revealDistanceMultiplier, editable);

        if (easingButton != null) {
            easingButton.active = editable;
        }

        if (visibleWhileAboardButton != null) {
            visibleWhileAboardButton.active = editable;
        }

        if (proximityRevealButton != null) {
            proximityRevealButton.active = editable;
        }

        if (ropeBehaviorButton != null) {
            ropeBehaviorButton.active = editable;
        }

        if (resetButton != null) {
            resetButton.active = editable;
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
        int viewportHeight = getViewportHeight();
        maxScroll = Math.max(0, CONTENT_HEIGHT - viewportHeight);
        contentScroll = clampScroll(contentScroll);
        positionContentWidgets();
    }

    private int getViewportHeight() {
        return Math.max(40, viewportBottom - CONTENT_TOP);
    }

    private int clampScroll(int value) {
        return Math.max(0, Math.min(value, maxScroll));
    }

    private void setContentScroll(int newScroll) {
        contentScroll = clampScroll(newScroll);
        positionContentWidgets();
    }

    private void positionContentWidgets() {
        positionWidget(transitionDuration, TRANSITION_DURATION);
        positionWidget(easingButton, TRANSITION_EASING);
        positionWidget(visibleWhileAboardButton, VISIBLE_ABOARD);
        positionWidget(aboardFade, ABOARD_FADE);
        positionWidget(leaveGrace, LEAVE_GRACE);
        positionWidget(leaveFade, LEAVE_FADE);
        positionWidget(proximityRevealButton, PROXIMITY_REVEAL);
        positionWidget(fullyVisibleDistance, FULLY_VISIBLE_DISTANCE);
        positionWidget(revealDistanceMultiplier, REVEAL_MULTIPLIER);
        positionWidget(ropeBehaviorButton, ROPE_BEHAVIOR);
    }

    private void positionWidget(AbstractWidget widget, int contentY) {
        if (widget == null) {
            return;
        }

        int screenY = CONTENT_TOP + contentY - contentScroll;
        widget.setX(controlX);
        widget.setY(screenY);

        // Widgets completely outside the clipped list must not remain clickable.
        widget.visible = screenY + widget.getHeight() > CONTENT_TOP
                && screenY < viewportBottom;
    }

    private boolean isInsideScrollBar(double mouseX, double mouseY) {
        return mouseX >= scrollBarX
                && mouseX <= scrollBarX + SCROLLBAR_WIDTH
                && mouseY >= CONTENT_TOP
                && mouseY <= viewportBottom;
    }

    private int getThumbHeight() {
        int trackHeight = getViewportHeight();

        if (maxScroll <= 0 || CONTENT_HEIGHT <= 0) {
            return trackHeight;
        }

        return Math.max(
                MIN_THUMB_HEIGHT,
                Math.min(
                        trackHeight,
                        (int) Math.round(
                                trackHeight * (double) trackHeight / CONTENT_HEIGHT
                        )
                )
        );
    }

    private int getThumbY() {
        if (maxScroll <= 0) {
            return CONTENT_TOP;
        }

        int trackTravel = getViewportHeight() - getThumbHeight();
        if (trackTravel <= 0) {
            return CONTENT_TOP;
        }

        return CONTENT_TOP + (int) Math.round(
                trackTravel * (double) contentScroll / maxScroll
        );
    }

    private void setScrollFromThumb(double thumbTop) {
        if (maxScroll <= 0) {
            setContentScroll(0);
            return;
        }

        int trackTravel = getViewportHeight() - getThumbHeight();
        if (trackTravel <= 0) {
            setContentScroll(0);
            return;
        }

        double clampedThumbTop = Math.max(
                CONTENT_TOP,
                Math.min(CONTENT_TOP + trackTravel, thumbTop)
        );

        double fraction = (clampedThumbTop - CONTENT_TOP) / trackTravel;
        setContentScroll((int) Math.round(fraction * maxScroll));
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
        // Do not call renderBackground() or super.render() here. Both routes
        // activate Minecraft's pause-menu blur. The config intentionally uses
        // only a dark overlay so all text stays sharp.
        guiGraphics.fill(0, 0, width, height, 0xB0101010);

        guiGraphics.drawCenteredString(
                font,
                title,
                width / 2,
                22,
                0xFFFFFF
        );

        int scissorLeft = contentLeft - 2;
        int scissorTop = CONTENT_TOP;
        int scissorRight = contentLeft + contentWidth + 2;
        int scissorBottom = viewportBottom;

        guiGraphics.enableScissor(
                scissorLeft,
                scissorTop,
                scissorRight,
                scissorBottom
        );

        drawSectionHeader(
                guiGraphics,
                TRANSITION_HEADER,
                "screen.aerocloakingcore.server_config.section.transition"
        );
        drawRowLabel(
                guiGraphics,
                TRANSITION_DURATION,
                "screen.aerocloakingcore.server_config.transition_duration"
        );
        drawRowLabel(
                guiGraphics,
                TRANSITION_EASING,
                "screen.aerocloakingcore.server_config.easing"
        );

        drawSectionHeader(
                guiGraphics,
                VIEWER_HEADER,
                "screen.aerocloakingcore.server_config.section.viewer"
        );
        drawRowLabel(
                guiGraphics,
                VISIBLE_ABOARD,
                "screen.aerocloakingcore.server_config.visible_aboard"
        );
        drawRowLabel(
                guiGraphics,
                ABOARD_FADE,
                "screen.aerocloakingcore.server_config.aboard_fade"
        );
        drawRowLabel(
                guiGraphics,
                LEAVE_GRACE,
                "screen.aerocloakingcore.server_config.leave_grace"
        );
        drawRowLabel(
                guiGraphics,
                LEAVE_FADE,
                "screen.aerocloakingcore.server_config.leave_fade"
        );

        drawSectionHeader(
                guiGraphics,
                PROXIMITY_HEADER,
                "screen.aerocloakingcore.server_config.section.proximity"
        );
        drawRowLabel(
                guiGraphics,
                PROXIMITY_REVEAL,
                "screen.aerocloakingcore.server_config.proximity_reveal"
        );
        drawRowLabel(
                guiGraphics,
                FULLY_VISIBLE_DISTANCE,
                "screen.aerocloakingcore.server_config.visible_distance"
        );
        drawRowLabel(
                guiGraphics,
                REVEAL_MULTIPLIER,
                "screen.aerocloakingcore.server_config.reveal_distance_multiplier"
        );

        drawSectionHeader(
                guiGraphics,
                ROPE_HEADER,
                "screen.aerocloakingcore.server_config.section.ropes"
        );
        drawRowLabel(
                guiGraphics,
                ROPE_BEHAVIOR,
                "screen.aerocloakingcore.server_config.rope_behavior"
        );

        renderContentWidget(transitionDuration, guiGraphics, mouseX, mouseY, partialTick);
        renderContentWidget(easingButton, guiGraphics, mouseX, mouseY, partialTick);
        renderContentWidget(visibleWhileAboardButton, guiGraphics, mouseX, mouseY, partialTick);
        renderContentWidget(aboardFade, guiGraphics, mouseX, mouseY, partialTick);
        renderContentWidget(leaveGrace, guiGraphics, mouseX, mouseY, partialTick);
        renderContentWidget(leaveFade, guiGraphics, mouseX, mouseY, partialTick);
        renderContentWidget(proximityRevealButton, guiGraphics, mouseX, mouseY, partialTick);
        renderContentWidget(fullyVisibleDistance, guiGraphics, mouseX, mouseY, partialTick);
        renderContentWidget(revealDistanceMultiplier, guiGraphics, mouseX, mouseY, partialTick);
        renderContentWidget(ropeBehaviorButton, guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.disableScissor();

        drawScrollBar(guiGraphics);

        renderBottomWidget(cancelButton, guiGraphics, mouseX, mouseY, partialTick);
        renderBottomWidget(resetButton, guiGraphics, mouseX, mouseY, partialTick);
        renderBottomWidget(saveButton, guiGraphics, mouseX, mouseY, partialTick);
    }

    private void renderContentWidget(
            AbstractWidget widget,
            GuiGraphics guiGraphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        if (widget != null && widget.visible) {
            widget.render(guiGraphics, mouseX, mouseY, partialTick);
        }
    }

    private void renderBottomWidget(
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

    private void drawSectionHeader(
            GuiGraphics guiGraphics,
            int contentY,
            String translationKey
    ) {
        int y = CONTENT_TOP + contentY - contentScroll;
        Component heading = Component.translatable(translationKey);

        guiGraphics.drawString(
                font,
                heading,
                contentLeft,
                y + 4,
                0xFFFFFF,
                false
        );

        int textEnd = contentLeft + font.width(heading) + 8;
        int lineY = y + 8;
        int lineEnd = contentLeft + contentWidth;

        if (textEnd < lineEnd) {
            guiGraphics.fill(
                    textEnd,
                    lineY,
                    lineEnd,
                    lineY + 1,
                    0xFF707070
            );
        }
    }

    private void drawRowLabel(
            GuiGraphics guiGraphics,
            int contentY,
            String translationKey
    ) {
        int y = CONTENT_TOP + contentY - contentScroll;
        Component label = Component.translatable(translationKey);
        String labelText = label.getString();
        int availableWidth = Math.max(20, labelWidth - 4);

        if (font.width(labelText) > availableWidth) {
            String ellipsis = "...";
            int textWidth = Math.max(0, availableWidth - font.width(ellipsis));
            labelText = font.plainSubstrByWidth(labelText, textWidth) + ellipsis;
        }

        guiGraphics.drawString(
                font,
                labelText,
                contentLeft,
                y + 6,
                0xD0D0D0,
                false
        );
    }

    private void drawScrollBar(GuiGraphics guiGraphics) {
        int trackTop = CONTENT_TOP;
        int trackBottom = viewportBottom;

        guiGraphics.fill(
                scrollBarX,
                trackTop,
                scrollBarX + SCROLLBAR_WIDTH,
                trackBottom,
                0x70000000
        );

        int thumbY = getThumbY();
        int thumbHeight = getThumbHeight();
        int thumbColor = maxScroll > 0 ? 0xFFB0B0B0 : 0xFF686868;

        guiGraphics.fill(
                scrollBarX,
                thumbY,
                scrollBarX + SCROLLBAR_WIDTH,
                thumbY + thumbHeight,
                thumbColor
        );
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
