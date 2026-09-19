package com.oliver.erydon.client.config;

import com.oliver.erydon.ErydonConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** A local visual preference, saved on Done and applied on the next client start. */
public final class ErydonHighPolishConfigScreen extends Screen {
    private final Screen parent;
    private boolean enabled;
    private boolean saveFailed;

    public ErydonHighPolishConfigScreen(Screen parent) {
        super(Text.translatable("screen.erydon.high_polish.title"));
        this.parent = parent;
        enabled = ErydonConfig.clientSettings().highPolishEnabled();
    }

    @Override
    protected void init() {
        int left = ErydonConfigUi.panelLeft(width) + 24;
        int top = ErydonConfigUi.panelTop(height, ErydonConfigUi.FORM_PANEL_HEIGHT);
        int contentWidth = ErydonConfigUi.panelWidth(width) - 48;
        addDrawableChild(new ErydonConfigUi.Button(left, top + 62, contentWidth, 20,
                toggleLabel(), 64, 84, button -> {
                    enabled = !enabled;
                    button.setMessage(toggleLabel());
                }));
        int buttonY = top + ErydonConfigUi.FORM_PANEL_HEIGHT - 29;
        int buttonWidth = (contentWidth - 8) / 2;
        addDrawableChild(new ErydonConfigUi.Button(left, buttonY, buttonWidth, 20,
                Text.translatable("gui.cancel"), 146, 172, button -> close()));
        addDrawableChild(new ErydonConfigUi.Button(left + buttonWidth + 8, buttonY, buttonWidth, 20,
                Text.translatable("gui.done"), 188, 306, button -> {
                    var current = ErydonConfig.clientSettings();
                    if (ErydonConfig.replaceClientSettings(new ErydonConfig.ClientSnapshot(
                            current.tooltipsEnabled(), current.tooltipDelayMs(), enabled))) {
                        close();
                    } else {
                        saveFailed = true;
                    }
                }).withStyle(ErydonConfigUi.Button.Style.PRIMARY));
    }

    private Text toggleLabel() {
        return Text.translatable(enabled ? "button.erydon.high_polish_on" : "button.erydon.high_polish_off");
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        ErydonConfigUi.drawBlackBackground(context, width, height);
        int left = ErydonConfigUi.panelLeft(width);
        int top = ErydonConfigUi.panelTop(height, ErydonConfigUi.FORM_PANEL_HEIGHT);
        int panelWidth = ErydonConfigUi.panelWidth(width);
        ErydonConfigUi.drawPanelBackground(context, left, top, left + panelWidth,
                top + ErydonConfigUi.FORM_PANEL_HEIGHT);
        ErydonConfigUi.drawPageTitle(context, title, left + panelWidth / 2,
                top + ErydonConfigUi.PAGE_TITLE_Y_OFFSET);
        ErydonConfigUi.drawWrappedScaledText(context, Text.translatable("option.erydon.high_polish.description"),
                left + 24, top + 94, panelWidth - 48, 5,
                ErydonConfigUi.DESCRIPTION_TEXT_SCALE, ErydonConfigUi.TEXT_COLOR);
        ErydonConfigUi.drawWrappedScaledText(context, Text.translatable("option.erydon.high_polish.restart"),
                left + 24, top + 145, panelWidth - 48, 2,
                ErydonConfigUi.DESCRIPTION_TEXT_SCALE, ErydonConfigUi.MUTED_TEXT_COLOR);
        if (saveFailed) {
            ErydonConfigUi.drawStatusStrip(context, Text.translatable("message.erydon.config.status.save_failed"),
                    left + 24, top + ErydonConfigUi.FORM_PANEL_HEIGHT - 50, panelWidth - 48,
                    ErydonConfigUi.StatusTone.ERROR);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
