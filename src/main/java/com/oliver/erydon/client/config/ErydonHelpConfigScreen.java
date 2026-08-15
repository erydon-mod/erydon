package com.oliver.erydon.client.config;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Text;

public final class ErydonHelpConfigScreen extends Screen {
    private static final String COMMUNITY_URL = "https://discord.gg/c4n38r7BBM";
    private static final String ISSUE_DISCORD_URL = "https://discord.gg/qgrK3AGA8T";
    private static final String ISSUE_TRACKER_URL = "https://github.com/erydon-mod/erydon/issues";
    private static final String WEBSITE_URL = "https://erydon.co.uk";
    private static final String RESOURCE_PACKS_URL = "https://modrinth.com/user/ERYDON";
    private static final String THEMELIOS_URL = "https://modrinth.com/mod/erydon-themelios";

    private final Screen parent;

    public ErydonHelpConfigScreen(Screen parent) {
        super(Text.translatable("screen.erydon.help.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int panelLeft = ErydonConfigUi.panelLeft(width);
        int panelTop = ErydonConfigUi.panelTop(height);
        int buttonWidth = Math.min(152, Math.max(96, (ErydonConfigUi.panelWidth(width) - 56) / 2));
        int buttonHeight = 18;
        int gapX = 8;
        int gapY = 5;
        int gridWidth = buttonWidth * 2 + gapX;
        int buttonX = panelLeft + (ErydonConfigUi.panelWidth(width) - gridWidth) / 2;
        int buttonY = panelTop + 66;

        addGridLinkButton(buttonX, buttonY, buttonWidth, buttonHeight, gapX, gapY, 0, 0, Text.translatable("button.erydon.join_community"), COMMUNITY_URL, 64, 84);
        addGridLinkButton(buttonX, buttonY, buttonWidth, buttonHeight, gapX, gapY, 1, 0, Text.translatable("button.erydon.website"), WEBSITE_URL, 188, 306);
        addGridLinkButton(buttonX, buttonY, buttonWidth, buttonHeight, gapX, gapY, 0, 1, Text.translatable("button.erydon.report_discord"), ISSUE_DISCORD_URL, 16, 42);
        addGridLinkButton(buttonX, buttonY, buttonWidth, buttonHeight, gapX, gapY, 1, 1, Text.translatable("button.erydon.report_github"), ISSUE_TRACKER_URL, 146, 172);
        addGridLinkButton(buttonX, buttonY, buttonWidth, buttonHeight, gapX, gapY, 0, 2, Text.translatable("button.erydon.axiom_tools"), null, 34, 262);
        addGridLinkButton(buttonX, buttonY, buttonWidth, buttonHeight, gapX, gapY, 1, 2, Text.translatable("button.erydon.resource_pack_64x"), RESOURCE_PACKS_URL, 226, 36);
        addGridLinkButton(buttonX, buttonY, buttonWidth, buttonHeight, gapX, gapY, 0, 3, Text.translatable("button.erydon.resource_pack_32x"), RESOURCE_PACKS_URL, 274, 122);
        addGridLinkButton(buttonX, buttonY, buttonWidth, buttonHeight, gapX, gapY, 1, 3, Text.translatable("button.erydon.themelios"), THEMELIOS_URL, 104, 216);

        addDrawableChild(new ErydonConfigUi.Button(
                panelLeft + (ErydonConfigUi.panelWidth(width) - 92) / 2,
                panelTop + ErydonConfigUi.PANEL_HEIGHT - 29,
                92,
                18,
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
        ErydonConfigUi.drawPanelBackground(context, left, top, left + ErydonConfigUi.panelWidth(width), top + ErydonConfigUi.PANEL_HEIGHT);
        ErydonConfigUi.drawPageTitle(context, title, left + ErydonConfigUi.panelWidth(width) / 2, ErydonConfigUi.pageTitleY(height));
    }

    private void addGridLinkButton(int x, int y, int width, int height, int gapX, int gapY, int column, int row, Text text, String link, int textureX, int textureY) {
        Text displayText = link == null
                ? Text.empty().append(text).append(" - ").append(Text.translatable("text.erydon.coming_soon"))
                : text;
        ErydonConfigUi.Button button = new ErydonConfigUi.Button(
                x + column * (width + gapX),
                y + row * (height + gapY),
                width,
                height,
                displayText,
                textureX,
                textureY,
                pressed -> {
                    if (link != null) {
                        ConfirmLinkScreen.open(link, this, false);
                    }
                }
        );
        button.active = link != null;
        if (link == null) {
            button.setTooltip(Tooltip.of(displayText));
        }
        addDrawableChild(button);
    }
}
