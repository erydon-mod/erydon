package com.oliver.erydon.client.config;

import com.oliver.erydon.client.ErydonConfigNetworkingClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public final class ErydonConfigScreen extends Screen {
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP_X = 10;
    private static final int BUTTON_GAP_Y = 8;
    private static final int SETTINGS_HEADING_OFFSET_Y = 49;
    private static final int SETTINGS_BUTTON_OFFSET_Y = 61;
    private static final int EXPLORE_HEADING_OFFSET_Y = 117;
    private static final int EXPLORE_BUTTON_OFFSET_Y = 129;
    private static final float SECTION_HEADING_SCALE = 0.82F;

    private final Screen parent;

    public ErydonConfigScreen(Screen parent) {
        super(Text.translatable("screen.erydon.config.title"));
        this.parent = parent;
        ErydonConfigNetworkingClient.requestServerSettings();
    }

    @Override
    protected void init() {
        int panelLeft = ErydonConfigUi.panelLeft(width);
        int panelTop = ErydonConfigUi.panelTop(height);
        int buttonWidth = Math.min(142, (ErydonConfigUi.panelWidth(width) - 74) / 2);
        int gridWidth = buttonWidth * 2 + BUTTON_GAP_X;
        int startX = panelLeft + (ErydonConfigUi.panelWidth(width) - gridWidth) / 2;
        int settingsStartY = panelTop + SETTINGS_BUTTON_OFFSET_Y;
        int exploreStartY = panelTop + EXPLORE_BUTTON_OFFSET_Y;

        // Registration order follows the visual row order for predictable keyboard navigation.
        addMenuButton(startX, settingsStartY, buttonWidth, 0, 0, Text.translatable("button.erydon.tooltips"), 64, 84, ErydonTooltipConfigScreen::new);
        addMenuButton(startX, settingsStartY, buttonWidth, 1, 0, Text.translatable("button.erydon.recalc_command"), 16, 42, ErydonRecalcCommandConfigScreen::new);
        addMenuButton(startX, settingsStartY, buttonWidth, 0, 1, Text.translatable("button.erydon.light_blocks"), 226, 36, ErydonLightBlocksConfigScreen::new);
        addMenuButton(startX, exploreStartY, buttonWidth, 0, 0, Text.translatable("button.erydon.texture_gallery"), 188, 306, ErydonTextureGalleryScreen::new);
        addMenuButton(startX, exploreStartY, buttonWidth, 1, 0, Text.translatable("button.erydon.inspiration"), 274, 122, ErydonInspirationConfigScreen::new);
        addMenuButton(startX, exploreStartY, buttonWidth, 0, 1, Text.translatable("button.erydon.help"), 104, 216, ErydonHelpConfigScreen::new);
        addDrawableChild(new ErydonConfigUi.Button(
                startX + buttonWidth + BUTTON_GAP_X,
                exploreStartY + BUTTON_HEIGHT + BUTTON_GAP_Y,
                buttonWidth,
                BUTTON_HEIGHT,
                Text.translatable("gui.back"),
                34,
                262,
                button -> close()
        ).withStyle(ErydonConfigUi.Button.Style.QUIET));
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
        int top = ErydonConfigUi.panelTop(height);
        int panelWidth = ErydonConfigUi.panelWidth(width);
        ErydonConfigUi.drawPanelBackground(context, left, top, left + panelWidth, top + ErydonConfigUi.PANEL_HEIGHT);
        ErydonConfigUi.drawCenteredScaledText(
                context,
                Text.translatable("section.erydon.config.settings"),
                left + panelWidth / 2,
                top + SETTINGS_HEADING_OFFSET_Y,
                SECTION_HEADING_SCALE,
                ErydonConfigUi.MUTED_TEXT_COLOR
        );
        ErydonConfigUi.drawCenteredScaledText(
                context,
                Text.translatable("section.erydon.config.explore"),
                left + panelWidth / 2,
                top + EXPLORE_HEADING_OFFSET_Y,
                SECTION_HEADING_SCALE,
                ErydonConfigUi.MUTED_TEXT_COLOR
        );
    }

    private void addMenuButton(int startX, int startY, int buttonWidth, int column, int row, Text text, int textureX, int textureY, ScreenFactory screenFactory) {
        addDrawableChild(new ErydonConfigUi.Button(
                startX + column * (buttonWidth + BUTTON_GAP_X),
                startY + row * (BUTTON_HEIGHT + BUTTON_GAP_Y),
                buttonWidth,
                BUTTON_HEIGHT,
                text,
                textureX,
                textureY,
                button -> {
                    if (client != null) {
                        client.setScreen(screenFactory.create(this));
                    }
                }
        ));
    }

    @FunctionalInterface
    private interface ScreenFactory {
        Screen create(Screen parent);
    }
}
