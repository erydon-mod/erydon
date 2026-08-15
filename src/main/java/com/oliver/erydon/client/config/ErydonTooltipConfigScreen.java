package com.oliver.erydon.client.config;

import com.oliver.erydon.ErydonConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.function.IntConsumer;

public final class ErydonTooltipConfigScreen extends Screen {
    private final Screen parent;
    private boolean tooltipsEnabled;
    private int tooltipDelayMs;
    private TooltipDelaySlider tooltipDelaySlider;
    private boolean saveFailed;

    public ErydonTooltipConfigScreen(Screen parent) {
        this(parent, ErydonConfig.tooltipsEnabled(), ErydonConfig.tooltipDelayMs());
    }

    private ErydonTooltipConfigScreen(Screen parent, boolean tooltipsEnabled, int tooltipDelayMs) {
        super(Text.translatable("screen.erydon.tooltips.title"));
        this.parent = parent;
        this.tooltipsEnabled = tooltipsEnabled;
        this.tooltipDelayMs = ErydonConfig.clampTooltipDelay(tooltipDelayMs);
    }

    @Override
    protected void init() {
        int panelLeft = ErydonConfigUi.panelLeft(width);
        int panelTop = ErydonConfigUi.panelTop(height, ErydonConfigUi.FORM_PANEL_HEIGHT);
        int contentLeft = panelLeft + 24;
        int contentWidth = ErydonConfigUi.panelWidth(width) - 48;
        int buttonY = panelTop + ErydonConfigUi.FORM_PANEL_HEIGHT - 29;
        int buttonWidth = (contentWidth - 16) / 3;

        addDrawableChild(new ErydonConfigUi.Button(
                contentLeft,
                panelTop + 78,
                112,
                20,
                Text.translatable(tooltipsEnabled ? "button.erydon.tooltips_on" : "button.erydon.tooltips_off"),
                64,
                84,
                button -> {
                    if (client != null) {
                        client.setScreen(new ErydonTooltipConfigScreen(parent, !tooltipsEnabled, currentDelayMs()));
                    }
                }
        ));

        if (tooltipsEnabled) {
            tooltipDelaySlider = addDrawableChild(new TooltipDelaySlider(
                    contentLeft,
                    panelTop + 116,
                    contentWidth,
                    20,
                    tooltipDelayMs,
                    value -> this.tooltipDelayMs = value
            ));
        }

        addDrawableChild(new ErydonConfigUi.Button(contentLeft, buttonY, buttonWidth, 20, Text.translatable("button.erydon.reset"), 16, 42, button -> {
            if (client != null) {
                client.setScreen(new ErydonTooltipConfigScreen(parent, true, ErydonConfig.DEFAULT_TOOLTIP_DELAY_MS));
            }
        }).withStyle(ErydonConfigUi.Button.Style.QUIET));

        addDrawableChild(new ErydonConfigUi.Button(
                contentLeft + buttonWidth + 8,
                buttonY,
                buttonWidth,
                20,
                Text.translatable("gui.cancel"),
                146,
                172,
                button -> close()
        ));

        addDrawableChild(new ErydonConfigUi.Button(
                contentLeft + (buttonWidth + 8) * 2,
                buttonY,
                buttonWidth,
                20,
                Text.translatable("gui.done"),
                188,
                306,
                button -> saveAndClose()
        ).withStyle(ErydonConfigUi.Button.Style.PRIMARY));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        ErydonConfigUi.drawBlackBackground(context, width, height);
        drawPanel(context);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    private void drawPanel(DrawContext context) {
        int left = ErydonConfigUi.panelLeft(width);
        int top = ErydonConfigUi.panelTop(height, ErydonConfigUi.FORM_PANEL_HEIGHT);
        int right = left + ErydonConfigUi.panelWidth(width);
        int bottom = top + ErydonConfigUi.FORM_PANEL_HEIGHT;
        int contentLeft = left + 24;

        ErydonConfigUi.drawPanelBackground(context, left, top, right, bottom);
        ErydonConfigUi.drawPageTitle(context, title, left + ErydonConfigUi.panelWidth(width) / 2, top + ErydonConfigUi.PAGE_TITLE_Y_OFFSET);

        if (saveFailed) {
            ErydonConfigUi.drawStatusStrip(
                    context,
                    Text.translatable("message.erydon.config.status.save_failed"),
                    contentLeft,
                    bottom - 50,
                    right - contentLeft - 24,
                    ErydonConfigUi.StatusTone.ERROR
            );
        }

        context.drawText(
                textRenderer,
                ErydonConfigUi.cinzel(Text.translatable("option.erydon.tooltips_enabled")),
                contentLeft,
                top + 64,
                ErydonConfigUi.TEXT_COLOR,
                false
        );
        if (tooltipsEnabled) {
            context.drawText(
                    textRenderer,
                    ErydonConfigUi.cinzel(Text.translatable("option.erydon.tooltip_delay")),
                    contentLeft,
                    top + 106,
                    ErydonConfigUi.TEXT_COLOR,
                    false
            );
            ErydonConfigUi.drawWrappedScaledText(
                    context,
                    Text.translatable("option.erydon.tooltip_delay.description"),
                    contentLeft,
                    top + 140,
                    right - contentLeft - 24,
                    2,
                    ErydonConfigUi.DESCRIPTION_TEXT_SCALE,
                    ErydonConfigUi.MUTED_TEXT_COLOR
            );
        } else {
            ErydonConfigUi.drawWrappedScaledText(
                    context,
                    Text.translatable("option.erydon.tooltips_disabled.description"),
                    contentLeft,
                    top + 112,
                    right - contentLeft - 24,
                    2,
                    ErydonConfigUi.DESCRIPTION_TEXT_SCALE,
                    ErydonConfigUi.MUTED_TEXT_COLOR
            );
        }
    }

    private int currentDelayMs() {
        return tooltipDelaySlider == null ? tooltipDelayMs : tooltipDelaySlider.getDelayMs();
    }

    private void saveAndClose() {
        if (ErydonConfig.replaceClientSettings(new ErydonConfig.ClientSnapshot(
                tooltipsEnabled,
                currentDelayMs()
        ))) {
            close();
        } else {
            saveFailed = true;
        }
    }

    private static final class TooltipDelaySlider extends ErydonConfigUi.Slider {
        private int delayMs;
        private final IntConsumer changeListener;

        private TooltipDelaySlider(int x, int y, int width, int height, int delayMs, IntConsumer changeListener) {
            super(x, y, width, height, Text.empty(), normalizedValue(delayMs));
            this.delayMs = ErydonConfig.clampTooltipDelay(delayMs);
            this.changeListener = changeListener;
            updateMessage();
        }

        private int getDelayMs() {
            return delayMs;
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.translatable("option.erydon.tooltip_delay.value", delayMs));
        }

        @Override
        protected void applyValue() {
            int rawValue = (int) Math.round(value * ErydonConfig.MAX_TOOLTIP_DELAY_MS);
            delayMs = MathHelper.clamp(
                    Math.round((float) rawValue / ErydonConfig.TOOLTIP_DELAY_STEP_MS) * ErydonConfig.TOOLTIP_DELAY_STEP_MS,
                    ErydonConfig.MIN_TOOLTIP_DELAY_MS,
                    ErydonConfig.MAX_TOOLTIP_DELAY_MS
            );
            value = normalizedValue(delayMs);
            changeListener.accept(delayMs);
        }

        private static double normalizedValue(int delayMs) {
            return ErydonConfig.clampTooltipDelay(delayMs) / (double) ErydonConfig.MAX_TOOLTIP_DELAY_MS;
        }
    }
}
