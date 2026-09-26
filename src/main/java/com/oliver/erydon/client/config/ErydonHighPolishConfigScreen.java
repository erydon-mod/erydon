package com.oliver.erydon.client.config;

import com.oliver.erydon.ErydonConfig;
import com.oliver.erydon.HighPolishSettings;
import com.oliver.erydon.HighPolishSettings.Choice;
import com.oliver.erydon.HighPolishSettings.Level;
import com.oliver.erydon.HighPolishSettings.Stone;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Text;

/** Draft settings stay local to this screen until Save; rendering changes only on restart. */
public final class ErydonHighPolishConfigScreen extends Screen {
    private final Screen parent;
    private HighPolishSettings draft = ErydonConfig.clientSettings().highPolish();
    private String selectedStone;
    private boolean glassPage;
    private int page;
    private int pageCount;
    private boolean saveFailed;

    public ErydonHighPolishConfigScreen(Screen parent) {
        super(Text.translatable("screen.erydon.high_polish.title"));
        this.parent = parent;
    }

    private int panelWidth() { return Math.min(540, Math.max(260, width - 24)); }
    private int panelHeight() { return Math.max(238, Math.min(400, height - 16)); }
    private int stoneGridOffset() { return panelHeight() >= 264 ? 148 : 122; }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return Math.max(0, (height - panelHeight()) / 2); }

    @Override
    protected void init() {
        int x = left() + 20;
        int y = top();
        int w = panelWidth() - 40;
        int half = (w - 8) / 2;
        if (selectedStone != null) {
            button(x, y + 70, w, Text.translatable("button.erydon.polish.back"), () -> {
                selectedStone = null;
                rebuild();
            });
            Stone stone = draft.stones().get(selectedStone);
            button(x, y + 96, w, Text.translatable("option.erydon.polish.plain").append(": ")
                    .append(Text.translatable("option.erydon.polish." + stone.base().key())), () -> {
                setStone(new Stone(stone.base().next(), stone.herringbone(), stone.weave(), stone.inlays()));
            }).withMuted(!draft.enabled()).setTooltip(Tooltip.of(Text.translatable("option.erydon.polish.level_hint")));
            choiceButton(x, y + 124, half, "herringbone", stone.herringbone(), () ->
                    setStone(new Stone(stone.base(), stone.herringbone().next(), stone.weave(), stone.inlays())));
            choiceButton(x + half + 8, y + 124, half, "weave", stone.weave(), () ->
                    setStone(new Stone(stone.base(), stone.herringbone(), stone.weave().next(), stone.inlays())));
            choiceButton(x, y + 150, w, "inlays", stone.inlays(), () ->
                    setStone(new Stone(stone.base(), stone.herringbone(), stone.weave(), stone.inlays().next())));
        } else {
            button(x, y + 70, w, label("master", draft.enabled()), () -> {
                draft = draft.withEnabled(!draft.enabled());
                rebuild();
            }).setTooltip(Tooltip.of(Text.translatable("option.erydon.high_polish.description")));
            var stonesTab = button(x, y + 96, half, Text.translatable("button.erydon.polish.stones"), () -> {
                glassPage = false;
                rebuild();
            });
            var glassTab = button(x + half + 8, y + 96, half, Text.translatable("button.erydon.polish.glass"), () -> {
                glassPage = true;
                rebuild();
            });
            stonesTab.withSelection(!glassPage);
            glassTab.withSelection(glassPage);
            if (glassPage) {
                button(x, y + 122, w, label("glazing", draft.glazing()), () -> {
                    draft = draft.withGlass(!draft.glazing(), draft.twoWay());
                    rebuild();
                }).withMuted(!draft.enabled()).setTooltip(Tooltip.of(Text.translatable("option.erydon.polish.glazing_hint")));
                button(x, y + 148, w, label("two_way", draft.twoWay()), () -> {
                    draft = draft.withGlass(draft.glazing(), !draft.twoWay());
                    rebuild();
                }).withMuted(!draft.enabled()).setTooltip(Tooltip.of(Text.translatable("option.erydon.polish.two_way_hint")));
            } else {
                int presetWidth = (w - 12) / 3;
                for (Level level : panelHeight() >= 264 ? Level.values() : new Level[0]) {
                    boolean selected = draft.stones().values().stream().allMatch(stone ->
                            java.util.Arrays.stream(HighPolishSettings.Finish.values()).allMatch(finish -> stone.level(finish) == level));
                    button(x + level.ordinal() * (presetWidth + 6), y + 122, presetWidth,
                            Text.translatable("option.erydon.polish.all", Text.translatable("option.erydon.polish." + level.key())), () -> {
                                draft = draft.withAllStones(level);
                                rebuild();
                            }).withSelection(selected).withMuted(!draft.enabled())
                            .setTooltip(Tooltip.of(Text.translatable("option.erydon.polish.all_hint")));
                }
                int columns = w >= 440 ? 4 : w >= 330 ? 3 : 2;
                int rows = Math.max(1, (panelHeight() - stoneGridOffset() - 80) / 22);
                int perPage = rows * columns;
                int cellWidth = (w - (columns - 1) * 6) / columns;
                pageCount = (HighPolishSettings.MATERIALS.size() + perPage - 1) / perPage;
                page = Math.min(page, pageCount - 1);
                for (int i = 0; i < perPage && page * perPage + i < HighPolishSettings.MATERIALS.size(); i++) {
                    String material = HighPolishSettings.MATERIALS.get(page * perPage + i);
                    Stone stone = draft.stones().get(material);
                    boolean mixed = java.util.Arrays.stream(HighPolishSettings.Finish.values())
                            .anyMatch(finish -> stone.level(finish) != stone.base());
                    Text status = Text.translatable(mixed ? "option.erydon.polish.mixed" :
                            "option.erydon.polish." + stone.base().key());
                    button(x + (i % columns) * (cellWidth + 6), y + stoneGridOffset() + (i / columns) * 22, cellWidth,
                            Text.literal(materialName(material) + ": ").append(status), () -> {
                                selectedStone = material;
                                rebuild();
                            }).withStoneTexture(material).withMuted(!draft.enabled()).setTooltip(Tooltip.of(Text.literal(materialName(material) + ": ").append(status)
                                    .append("\n").append(Text.translatable("option.erydon.polish.stone_hint"))));
                }
                int pagerY = y + panelHeight() - 76;
                button(x, pagerY, 80, Text.translatable("button.erydon.polish.previous"), () -> changePage(-1)).active = page > 0;
                button(x + w - 80, pagerY, 80, Text.translatable("button.erydon.polish.next"), () -> changePage(1)).active = page + 1 < pageCount;
            }
        }
        int bottomY = y + panelHeight() - 34;
        button(x, bottomY, half, Text.translatable("gui.cancel"), this::close);
        button(x + half + 8, bottomY, half, Text.translatable("button.erydon.polish.save"), () -> {
            var current = ErydonConfig.clientSettings();
            if (ErydonConfig.replaceClientSettings(new ErydonConfig.ClientSnapshot(
                    current.tooltipsEnabled(), current.tooltipDelayMs(), draft))) close();
            else saveFailed = true;
        }).withStyle(ErydonConfigUi.Button.Style.PRIMARY);
    }

    private ErydonConfigUi.Button button(int x, int y, int w, Text text, Runnable action) {
        return addDrawableChild(new ErydonConfigUi.Button(x, y, w, 20, text, 64, 84, button -> action.run()));
    }

    private void choiceButton(int x, int y, int w, String key, Choice choice, Runnable action) {
        button(x, y, w, Text.translatable("option.erydon.polish." + key).append(": ")
                .append(Text.translatable("option.erydon.polish." + choice.key())), action)
                .withMuted(!draft.enabled())
                .setTooltip(Tooltip.of(Text.translatable("option.erydon.polish.inherit_hint")));
    }

    private Text label(String key, boolean on) {
        return Text.translatable("option.erydon.polish." + key).append(": ")
                .append(Text.translatable(on ? "option.erydon.polish.on" : "option.erydon.polish.off"));
    }

    private void setStone(Stone stone) {
        draft = draft.withStone(selectedStone, stone);
        rebuild();
    }

    private void rebuild() { setFocused(null); clearChildren(); init(); }
    private void changePage(int step) { page = Math.max(0, Math.min(pageCount - 1, page + step)); rebuild(); }
    private static String materialName(String material) { return Character.toUpperCase(material.charAt(0)) + material.substring(1); }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        if (selectedStone == null && !glassPage && x >= left() && x <= left() + panelWidth()
                && y >= top() + stoneGridOffset() && y < top() + panelHeight() - 76 && amount != 0) {
            changePage(amount < 0 ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(x, y, amount);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        ErydonConfigUi.drawBlackBackground(context, width, height);
        int x = left(), y = top(), w = panelWidth(), h = panelHeight();
        ErydonConfigUi.drawPanelBackground(context, x, y, x + w, y + h);
        ErydonConfigUi.drawPageTitle(context, selectedStone == null ? title : Text.literal(materialName(selectedStone)),
                x + w / 2, y + ErydonConfigUi.PAGE_TITLE_Y_OFFSET);
        if (selectedStone == null && !glassPage) {
            ErydonConfigUi.drawCenteredScaledText(context, Text.translatable("option.erydon.polish.page", page + 1, pageCount),
                    x + w / 2, y + h - 71, 0.85F, ErydonConfigUi.TEXT_COLOR);
        }
        if (selectedStone != null && h >= 280) {
            ErydonConfigUi.drawWrappedScaledText(context, Text.translatable("option.erydon.polish.inherit_hint"),
                    x + 20, y + 181, w - 40, 3, 0.85F, ErydonConfigUi.MUTED_TEXT_COLOR);
        } else if (glassPage && h >= 280) {
            ErydonConfigUi.drawWrappedScaledText(context, Text.translatable("option.erydon.polish.glass_hint"),
                    x + 20, y + 184, w - 40, 3, 0.85F, ErydonConfigUi.MUTED_TEXT_COLOR);
        }
        ErydonConfigUi.drawWrappedScaledText(context, Text.translatable(saveFailed
                        ? "message.erydon.config.status.save_failed" : "option.erydon.high_polish.restart"),
                x + 20, y + h - 52, w - 40, 2, 0.85F,
                saveFailed ? 0xFF8B1111 : ErydonConfigUi.TEXT_COLOR);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() { if (client != null) client.setScreen(parent); }
}
