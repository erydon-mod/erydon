package com.oliver.erydon.client.config;

import com.oliver.erydon.ErydonConfig;
import com.oliver.erydon.client.ErydonConfigNetworkingClient;
import com.oliver.erydon.network.ErydonConfigNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.function.IntConsumer;

public final class ErydonRecalcCommandConfigScreen extends ErydonServerConfigScreen {
    private static final int DESCRIPTION_TOP_OFFSET = 16;
    private static final int DESCRIPTION_MAX_LINES = 2;
    private static final int TOGGLE_HEIGHT = 22;
    private static final int SLIDER_HEIGHT = 20;
    private static final int ROW_GAP = 3;

    private boolean shortCommandEnabled;
    private int defaultRadius;
    private int maxRadius;
    private RadiusSlider defaultRadiusSlider;
    private RadiusSlider maxRadiusSlider;
    private ErydonConfigUi.Toggle shortcutToggle;
    private ErydonConfigUi.Button resetButton;
    private ErydonConfigUi.Button cancelButton;
    private ErydonConfigUi.Button doneButton;

    public ErydonRecalcCommandConfigScreen(Screen parent) {
        this(parent, ErydonConfig.recalcShortCommandEnabled(), ErydonConfig.recalcDefaultRadius(), ErydonConfig.recalcMaxRadius());
    }

    private ErydonRecalcCommandConfigScreen(Screen parent, boolean shortCommandEnabled, int defaultRadius, int maxRadius) {
        super(parent, Text.translatable("screen.erydon.recalc_command.title"));
        this.shortCommandEnabled = shortCommandEnabled;
        this.maxRadius = ErydonConfig.clampRecalcRadius(maxRadius);
        this.defaultRadius = Math.min(ErydonConfig.clampRecalcRadius(defaultRadius), this.maxRadius);
    }

    @Override
    protected void init() {
        int panelLeft = ErydonConfigUi.panelLeft(width);
        int panelTop = ErydonConfigUi.panelTop(height, ErydonConfigUi.FORM_PANEL_HEIGHT);
        int contentLeft = panelLeft + 24;
        int contentWidth = ErydonConfigUi.panelWidth(width) - 48;
        int buttonY = panelTop + ErydonConfigUi.FORM_PANEL_HEIGHT - 29;
        int buttonWidth = (contentWidth - 16) / 3;

        shortcutToggle = addDrawableChild(new ErydonConfigUi.Toggle(
                contentLeft,
                shortcutToggleTop(),
                contentWidth,
                TOGGLE_HEIGHT,
                Text.translatable("option.erydon.recalc.short_command"),
                Text.translatable("option.erydon.recalc.short_command.description"),
                () -> shortCommandEnabled,
                value -> shortCommandEnabled = value
        ));

        defaultRadiusSlider = addDrawableChild(new RadiusSlider(
                contentLeft,
                defaultRadiusSliderTop(),
                contentWidth,
                SLIDER_HEIGHT,
                Text.translatable("option.erydon.recalc.default_radius"),
                defaultRadius,
                this::onDefaultRadiusChanged
        ));
        maxRadiusSlider = addDrawableChild(new RadiusSlider(
                contentLeft,
                maxRadiusSliderTop(),
                contentWidth,
                SLIDER_HEIGHT,
                Text.translatable("option.erydon.recalc.max_radius"),
                maxRadius,
                this::onMaxRadiusChanged
        ));

        resetButton = addDrawableChild(new ErydonConfigUi.Button(contentLeft, buttonY, buttonWidth, 20, Text.translatable("button.erydon.reset"), 16, 42, button -> {
            shortCommandEnabled = true;
            maxRadius = ErydonConfig.DEFAULT_RECALC_MAX_RADIUS;
            defaultRadius = Math.min(ErydonConfig.DEFAULT_RECALC_RADIUS, maxRadius);
            if (maxRadiusSlider != null) {
                maxRadiusSlider.setRadius(maxRadius);
            }
            if (defaultRadiusSlider != null) {
                defaultRadiusSlider.setRadius(defaultRadius);
            }
        }).withStyle(ErydonConfigUi.Button.Style.QUIET));

        cancelButton = addDrawableChild(new ErydonConfigUi.Button(
                contentLeft + buttonWidth + 8,
                buttonY,
                buttonWidth,
                20,
                Text.translatable("gui.cancel"),
                146,
                172,
                button -> close()
        ));

        doneButton = addDrawableChild(new ErydonConfigUi.Button(
                contentLeft + (buttonWidth + 8) * 2,
                buttonY,
                buttonWidth,
                20,
                Text.translatable("gui.done"),
                188,
                306,
                button -> saveAndClose()
        ).withStyle(ErydonConfigUi.Button.Style.PRIMARY));
        finishServerConfigInit();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        ErydonConfigUi.drawBlackBackground(context, width, height);
        drawPanel(context);
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawPanel(DrawContext context) {
        int left = ErydonConfigUi.panelLeft(width);
        int top = ErydonConfigUi.panelTop(height, ErydonConfigUi.FORM_PANEL_HEIGHT);
        int right = left + ErydonConfigUi.panelWidth(width);
        int bottom = top + ErydonConfigUi.FORM_PANEL_HEIGHT;
        int contentLeft = left + 24;
        int contentWidth = ErydonConfigUi.panelWidth(width) - 48;

        ErydonConfigUi.drawPanelBackground(context, left, top, right, bottom);
        ErydonConfigUi.drawPageTitle(context, title, left + ErydonConfigUi.panelWidth(width) / 2, top + ErydonConfigUi.PAGE_TITLE_Y_OFFSET);
        ErydonConfigUi.drawWrappedScaledText(
                context,
                Text.translatable("option.erydon.recalc.description"),
                contentLeft,
                descriptionTop(),
                contentWidth,
                DESCRIPTION_MAX_LINES,
                ErydonConfigUi.DESCRIPTION_TEXT_SCALE,
                ErydonConfigUi.MUTED_TEXT_COLOR
        );
        drawServerStatus(context, contentLeft, bottom - 50, contentWidth);
    }

    private int currentDefaultRadius() {
        return defaultRadiusSlider == null ? defaultRadius : defaultRadiusSlider.getRadius();
    }

    private int currentMaxRadius() {
        return maxRadiusSlider == null ? maxRadius : maxRadiusSlider.getRadius();
    }

    private int descriptionTop() {
        return ErydonConfigUi.panelTop(height, ErydonConfigUi.FORM_PANEL_HEIGHT)
                + ErydonConfigUi.PAGE_TITLE_Y_OFFSET
                + DESCRIPTION_TOP_OFFSET;
    }

    private int descriptionHeight() {
        int lineCount = Math.min(
                DESCRIPTION_MAX_LINES,
                Math.max(
                        1,
                        ErydonConfigUi.wrap(
                                Text.translatable("option.erydon.recalc.description").getString(),
                                ErydonConfigUi.panelWidth(width) - 48,
                                ErydonConfigUi.DESCRIPTION_TEXT_SCALE
                        ).size()
                )
        );
        return lineCount * ErydonConfigUi.lineHeight(ErydonConfigUi.DESCRIPTION_TEXT_SCALE);
    }

    private int shortcutToggleTop() {
        return descriptionTop() + descriptionHeight() + 4;
    }

    private int defaultRadiusSliderTop() {
        return shortcutToggleTop() + TOGGLE_HEIGHT + ROW_GAP;
    }

    private int maxRadiusSliderTop() {
        return defaultRadiusSliderTop() + SLIDER_HEIGHT + ROW_GAP;
    }

    private void onDefaultRadiusChanged(int value) {
        defaultRadius = Math.min(value, currentMaxRadius());
        if (defaultRadiusSlider != null && defaultRadius != value) {
            defaultRadiusSlider.setRadius(defaultRadius);
        }
    }

    private void onMaxRadiusChanged(int value) {
        maxRadius = value;
        if (currentDefaultRadius() > maxRadius) {
            defaultRadius = maxRadius;
            if (defaultRadiusSlider != null) {
                defaultRadiusSlider.setRadius(defaultRadius);
            }
        }
    }

    private void saveAndClose() {
        int savedMaxRadius = currentMaxRadius();
        ErydonConfig.ServerSnapshot snapshot = ErydonConfigNetworkingClient.settings()
                .withRecalcShortCommandEnabled(shortCommandEnabled)
                .withRecalcMaxRadius(savedMaxRadius)
                .withRecalcDefaultRadius(Math.min(currentDefaultRadius(), savedMaxRadius));
        submitServerSettings(snapshot, ErydonConfigNetworking.UPDATE_RECALC_SETTINGS);
    }

    @Override
    protected void setServerControlsActive(boolean active) {
        if (shortcutToggle != null) {
            shortcutToggle.active = active;
        }
        if (defaultRadiusSlider != null) {
            defaultRadiusSlider.active = active;
        }
        if (maxRadiusSlider != null) {
            maxRadiusSlider.active = active;
        }
        if (resetButton != null) {
            resetButton.active = active;
        }
        if (doneButton != null) {
            doneButton.active = active;
        }
        if (cancelButton != null) {
            cancelButton.active = !serverSaveInProgress();
        }
    }

    @Override
    protected void applyServerSnapshot(ErydonConfig.ServerSnapshot snapshot) {
        shortCommandEnabled = snapshot.recalcShortCommandEnabled();
        maxRadius = ErydonConfig.clampRecalcRadius(snapshot.recalcMaxRadius());
        defaultRadius = Math.min(ErydonConfig.clampRecalcRadius(snapshot.recalcDefaultRadius()), maxRadius);
        if (maxRadiusSlider != null) {
            maxRadiusSlider.setRadius(maxRadius);
        }
        if (defaultRadiusSlider != null) {
            defaultRadiusSlider.setRadius(defaultRadius);
        }
    }

    private static final class RadiusSlider extends ErydonConfigUi.Slider {
        private final Text label;
        private final IntConsumer changeListener;
        private int radius;

        private RadiusSlider(int x, int y, int width, int height, Text label, int radius, IntConsumer changeListener) {
            super(x, y, width, height, Text.empty(), normalizedValue(radius));
            this.label = label;
            this.changeListener = changeListener;
            this.radius = ErydonConfig.clampRecalcRadius(radius);
            updateMessage();
        }

        private int getRadius() {
            return radius;
        }

        private void setRadius(int radius) {
            this.radius = ErydonConfig.clampRecalcRadius(radius);
            value = normalizedValue(this.radius);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.translatable("option.erydon.recalc.radius.value", label, radius));
        }

        @Override
        protected void applyValue() {
            int range = ErydonConfig.MAX_RECALC_RADIUS - ErydonConfig.MIN_RECALC_RADIUS;
            int rawValue = ErydonConfig.MIN_RECALC_RADIUS + (int) Math.round(value * range);
            radius = ErydonConfig.clampRecalcRadius(rawValue);
            value = normalizedValue(radius);
            changeListener.accept(radius);
        }

        private static double normalizedValue(int radius) {
            int clamped = ErydonConfig.clampRecalcRadius(radius);
            int range = ErydonConfig.MAX_RECALC_RADIUS - ErydonConfig.MIN_RECALC_RADIUS;
            return (clamped - ErydonConfig.MIN_RECALC_RADIUS) / (double) range;
        }
    }
}
