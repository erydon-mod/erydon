package com.oliver.erydon.client.config;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public final class ErydonInspirationConfigScreen extends Screen {
    private static final String COMMUNITY_SHOWCASE_URL = "https://github.com/erydon-mod/erydon/discussions";
    private static final String DISCORD_URL = "https://discord.gg/qRF8yZX8hJ";
    private static final int BACK_BUTTON_WIDTH = 54;
    private static final int BUTTON_HEIGHT = 18;
    private static final int DESCRIPTION_MAX_LINES = 6;
    private static final int LIST_SCROLL_GUTTER = 12;
    private static final int ENTRY_HEIGHT = 86;
    private static final int ENTRY_IMAGE_AREA_WIDTH = 150;
    private static final int ENTRY_IMAGE_MAX_HEIGHT = 78;
    private static final int ENTRY_IMAGE_TEXT_GAP = 9;
    private static final int ENTRY_TOP_PADDING = 4;
    private static final int LINK_TEXT_SPACING = 4;
    private static final int MAX_PANEL_WIDTH = 480;

    private static final List<Entry> ENTRIES = List.of(
            new Entry(
                    new Identifier("minecraft", "textures/optifine/ctm/chalstrom/0.png"),
                    16,
                    16,
                    Text.translatable("option.erydon.inspiration.4300.description")
            ),
            new Entry(
                    new Identifier("minecraft", "textures/optifine/ctm/striatus/0.png"),
                    16,
                    16,
                    Text.translatable("option.erydon.inspiration.2806.description")
            ),
            new Entry(
                    new Identifier("minecraft", "textures/optifine/ctm/glacium/0.png"),
                    16,
                    16,
                    Text.translatable("option.erydon.inspiration.1440.description")
            )
    );

    private final Screen parent;
    private int scrollY;
    private ErydonConfigUi.TextLink showcaseLink;
    private ErydonConfigUi.TextLink discordLink;
    private ErydonConfigUi.ScrollBar scrollBar;

    public ErydonInspirationConfigScreen(Screen parent) {
        super(Text.translatable("screen.erydon.inspiration.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int panelLeft = panelLeft();
        int panelTop = panelTop();
        int contentLeft = panelLeft + 24;

        addDrawableChild(new ErydonConfigUi.Button(
                contentLeft,
                panelTop + 22,
                BACK_BUTTON_WIDTH,
                BUTTON_HEIGHT,
                Text.translatable("gui.back"),
                16,
                42,
                button -> close()
        ).withStyle(ErydonConfigUi.Button.Style.QUIET));

        showcaseLink = addDrawableChild(new ErydonConfigUi.TextLink(
                showcaseTextLeft() - 2,
                rewardLinksTop() - 3,
                scaledTextWidth(Text.translatable("button.erydon.showcase")) + 4,
                BUTTON_HEIGHT,
                Text.translatable("button.erydon.showcase"),
                button -> ConfirmLinkScreen.open(COMMUNITY_SHOWCASE_URL, this, false)
        ));
        showcaseLink.setTooltip(Tooltip.of(Text.literal(COMMUNITY_SHOWCASE_URL)));
        discordLink = addDrawableChild(new ErydonConfigUi.TextLink(
                discordTextLeft() - 2,
                rewardLinksTop() - 3,
                scaledTextWidth(Text.translatable("button.erydon.discord")) + 4,
                BUTTON_HEIGHT,
                Text.translatable("button.erydon.discord"),
                button -> ConfirmLinkScreen.open(DISCORD_URL, this, false)
        ));
        discordLink.setTooltip(Tooltip.of(Text.literal(DISCORD_URL)));

        scrollBar = addDrawableChild(new ErydonConfigUi.ScrollBar(
                listRight() - 7,
                listTop(),
                listBottom() - listTop(),
                () -> scrollY,
                this::maxScroll,
                this::setScrollY,
                56,
                Math.max(56, listBottom() - listTop())
        ));

        scrollY = MathHelper.clamp(scrollY, 0, maxScroll());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        ErydonConfigUi.drawBlackBackground(context, width, height);
        drawPanel(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (isInsideList(mouseX, mouseY)) {
            setScrollY(scrollY - (int) Math.round(amount * 56.0D));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_PAGE_UP -> setScrollY(scrollY - Math.max(56, listBottom() - listTop()));
            case GLFW.GLFW_KEY_PAGE_DOWN -> setScrollY(scrollY + Math.max(56, listBottom() - listTop()));
            case GLFW.GLFW_KEY_HOME -> setScrollY(0);
            case GLFW.GLFW_KEY_END -> setScrollY(maxScroll());
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        return true;
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    private void drawPanel(DrawContext context, int mouseX, int mouseY) {
        int left = panelLeft();
        int top = panelTop();
        int right = left + panelWidth();
        int bottom = top + panelHeight();

        ErydonConfigUi.drawPanelBackground(context, left, top, right, bottom);
        ErydonConfigUi.drawPageTitle(context, title, left + panelWidth() / 2, pageTitleY());
        ErydonConfigUi.drawWrappedScaledText(
                context,
                Text.translatable("screen.erydon.inspiration.subtitle"),
                listLeft(),
                subtitleTop(),
                listWidth(),
                2,
                ErydonConfigUi.BODY_TEXT_SCALE,
                ErydonConfigUi.MUTED_TEXT_COLOR
        );
        drawRewardLine(context);

        context.enableScissor(listLeft(), listTop(), listRight(), listBottom());
        drawEntries(context);
        context.disableScissor();

    }

    private void drawEntries(DrawContext context) {
        for (int index = 0; index < ENTRIES.size(); index++) {
            Entry entry = ENTRIES.get(index);
            int top = entryTop(index);
            int height = entryHeight(entry);
            if (top + height >= listTop() && top <= listBottom()) {
                drawEntry(context, entry, top);
            }
        }
    }

    private void drawEntry(DrawContext context, Entry entry, int top) {
        int entryHeight = entryHeight(entry);
        int cardRight = listRight() - LIST_SCROLL_GUTTER;
        context.fill(
                listLeft(),
                top + 2,
                cardRight,
                top + entryHeight - 2,
                0x0D000000
        );

        int imageWidth = imageWidth(entry);
        int imageHeight = imageHeight(entry);
        int imageAreaWidth = imageAreaWidth();
        int imageX;
        int imageY;
        int textX;
        int textY;
        int textWidth;
        if (stackedLayout()) {
            imageX = listLeft() + (listWidth() - LIST_SCROLL_GUTTER - imageWidth) / 2;
            imageY = top + ENTRY_TOP_PADDING;
            textX = listLeft() + 4;
            textY = imageY + imageHeight + 5;
            textWidth = Math.max(1, listWidth() - LIST_SCROLL_GUTTER - 8);
        } else {
            imageX = listLeft() + (imageAreaWidth - imageWidth) / 2;
            imageY = top + ENTRY_TOP_PADDING + (entryImageMaxHeight() - imageHeight) / 2;
            textX = listLeft() + imageAreaWidth + ENTRY_IMAGE_TEXT_GAP;
            textY = top + 10;
            textWidth = Math.max(1, listRight() - LIST_SCROLL_GUTTER - textX);
        }

        context.drawTexture(
                entry.texture(),
                imageX,
                imageY,
                imageWidth,
                imageHeight,
                0.0F,
                0.0F,
                entry.textureWidth(),
                entry.textureHeight(),
                entry.textureWidth(),
                entry.textureHeight()
        );
        ErydonConfigUi.drawSolidBronzeOutline(context, imageX, imageY, imageX + imageWidth, imageY + imageHeight);
        ErydonConfigUi.drawWrappedScaledText(
                context,
                entry.description(),
                textX,
                textY,
                textWidth,
                DESCRIPTION_MAX_LINES,
                ErydonConfigUi.DESCRIPTION_TEXT_SCALE,
                ErydonConfigUi.MUTED_TEXT_COLOR
        );
    }

    private int entryTop(int targetIndex) {
        int top = listTop() - scrollY;
        for (int index = 0; index < targetIndex; index++) {
            top += entryHeight(ENTRIES.get(index));
        }
        return top;
    }

    private int entryHeight(Entry entry) {
        if (!stackedLayout()) {
            return Math.max(ENTRY_HEIGHT, entryImageMaxHeight() + ENTRY_TOP_PADDING * 2);
        }
        int textWidth = Math.max(1, listWidth() - LIST_SCROLL_GUTTER - 8);
        int descriptionLines = Math.min(
                DESCRIPTION_MAX_LINES,
                Math.max(1, ErydonConfigUi.wrap(entry.description().getString(), textWidth, ErydonConfigUi.DESCRIPTION_TEXT_SCALE).size())
        );
        return ENTRY_TOP_PADDING
                + imageHeight(entry)
                + 5
                + descriptionLines * ErydonConfigUi.lineHeight(ErydonConfigUi.DESCRIPTION_TEXT_SCALE)
                + 8;
    }

    private int imageWidth(Entry entry) {
        return Math.max(1, Math.round(entry.textureWidth() * imageScale(entry)));
    }

    private int imageHeight(Entry entry) {
        return Math.max(1, Math.round(entry.textureHeight() * imageScale(entry)));
    }

    private float imageScale(Entry entry) {
        int maxWidth = stackedLayout()
                ? Math.max(1, listWidth() - LIST_SCROLL_GUTTER - 8)
                : Math.min(imageAreaWidth(), Math.max(1, listWidth() - LIST_SCROLL_GUTTER - ENTRY_IMAGE_TEXT_GAP - 90));
        return Math.min(
                maxWidth / (float) entry.textureWidth(),
                entryImageMaxHeight() / (float) entry.textureHeight()
        );
    }

    private int imageAreaWidth() {
        int maxImageAreaWidth = panelWidth() > ErydonConfigUi.PANEL_WIDTH ? 220 : ENTRY_IMAGE_AREA_WIDTH;
        return Math.min(maxImageAreaWidth, Math.max(90, listWidth() / 2));
    }

    private boolean stackedLayout() {
        return listWidth() < 280;
    }

    private void drawRewardLine(DrawContext context) {
        int y = rewardLineTop();
        int linksY = rewardLinksTop();
        int prefixX = listLeft();
        Text prefix = Text.translatable("screen.erydon.inspiration.reward");
        Text divider = Text.translatable("text.erydon.or");

        ErydonConfigUi.drawWrappedScaledText(
                context,
                prefix,
                prefixX,
                y,
                listWidth(),
                2,
                ErydonConfigUi.BODY_TEXT_SCALE,
                ErydonConfigUi.MUTED_TEXT_COLOR
        );
        ErydonConfigUi.drawScaledText(context, divider, dividerLeft(), linksY, ErydonConfigUi.BODY_TEXT_SCALE, ErydonConfigUi.MUTED_TEXT_COLOR);
    }

    private int subtitleTop() {
        return pageTitleY() + 14;
    }

    private int rewardLineTop() {
        return subtitleTop() + subtitleLineCount() * ErydonConfigUi.lineHeight(ErydonConfigUi.BODY_TEXT_SCALE);
    }

    private int rewardLinksTop() {
        return rewardLineTop() + (rewardLinksOnSecondLine()
                ? rewardPrefixLineCount() * ErydonConfigUi.lineHeight(ErydonConfigUi.BODY_TEXT_SCALE)
                : 0);
    }

    private int subtitleLineCount() {
        return Math.min(2, Math.max(1, ErydonConfigUi.wrap(
                Text.translatable("screen.erydon.inspiration.subtitle").getString(),
                listWidth(),
                ErydonConfigUi.BODY_TEXT_SCALE
        ).size()));
    }

    private int rewardPrefixLineCount() {
        return Math.min(2, Math.max(1, ErydonConfigUi.wrap(
                Text.translatable("screen.erydon.inspiration.reward").getString(),
                listWidth(),
                ErydonConfigUi.BODY_TEXT_SCALE
        ).size()));
    }

    private boolean rewardLinksOnSecondLine() {
        int fullLineWidth = scaledTextWidth(Text.translatable("screen.erydon.inspiration.reward"))
                + LINK_TEXT_SPACING
                + scaledTextWidth(Text.translatable("button.erydon.showcase"))
                + LINK_TEXT_SPACING
                + scaledTextWidth(Text.translatable("text.erydon.or"))
                + LINK_TEXT_SPACING
                + scaledTextWidth(Text.translatable("button.erydon.discord"));
        return fullLineWidth > listWidth();
    }

    private int showcaseTextLeft() {
        return listLeft()
                + (rewardLinksOnSecondLine() ? 0 : scaledTextWidth(Text.translatable("screen.erydon.inspiration.reward")) + LINK_TEXT_SPACING);
    }

    private int dividerLeft() {
        return showcaseTextLeft()
                + scaledTextWidth(Text.translatable("button.erydon.showcase"))
                + LINK_TEXT_SPACING;
    }

    private int discordTextLeft() {
        return dividerLeft()
                + scaledTextWidth(Text.translatable("text.erydon.or"))
                + LINK_TEXT_SPACING;
    }

    private int scaledTextWidth(Text text) {
        return Math.round(ErydonConfigUi.textWidth(ErydonConfigUi.cinzel(text)) * ErydonConfigUi.BODY_TEXT_SCALE);
    }

    private int listLeft() {
        return panelLeft() + 24;
    }

    private int listRight() {
        return panelLeft() + panelWidth() - 24;
    }

    private int listWidth() {
        return listRight() - listLeft();
    }

    private int listTop() {
        return rewardLinksTop() + ErydonConfigUi.lineHeight(ErydonConfigUi.BODY_TEXT_SCALE) + 4;
    }

    private int listBottom() {
        return panelTop() + panelHeight() - 10;
    }

    private boolean isInsideList(double mouseX, double mouseY) {
        return mouseX >= listLeft() && mouseX < listRight() && mouseY >= listTop() && mouseY < listBottom();
    }

    private int contentHeight() {
        int height = 0;
        for (Entry entry : ENTRIES) {
            height += entryHeight(entry);
        }
        return height;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - (listBottom() - listTop()));
    }

    private void setScrollY(int value) {
        scrollY = MathHelper.clamp(value, 0, maxScroll());
    }

    private int panelHeight() {
        return ErydonConfigUi.responsivePanelHeight(height);
    }

    private int panelTop() {
        return ErydonConfigUi.panelTop(height, panelHeight());
    }

    private int pageTitleY() {
        return panelTop() + ErydonConfigUi.PAGE_TITLE_Y_OFFSET;
    }

    private int panelWidth() {
        return Math.min(MAX_PANEL_WIDTH, Math.max(260, width - 32));
    }

    private int panelLeft() {
        return (width - panelWidth()) / 2;
    }

    private int entryImageMaxHeight() {
        if (panelWidth() >= 440) {
            return 112;
        }
        if (panelWidth() >= 400) {
            return 94;
        }
        return ENTRY_IMAGE_MAX_HEIGHT;
    }

    private record Entry(Identifier texture, int textureWidth, int textureHeight, Text description) {
    }
}
