package com.oliver.erydon.client.config;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public final class ErydonTextureGalleryScreen extends Screen {
    // Draw the whole CTM tile. Active resource packs replace this same texture ID with 16x or 64x PNGs.
    private static final int FULL_TILE_UV_SIZE = 32;
    private static final int BASE_TILE_DISPLAY_SIZE = 16;
    private static final int BASE_ENTRY_HEIGHT = 76;
    private static final int MIN_COMPACT_ENTRY_HEIGHT = 104;
    private static final int COMPACT_ENTRY_HEIGHT = 132;
    private static final int COMPACT_LAYOUT_WIDTH = 340;
    private static final int MAX_PANEL_WIDTH = 480;
    private static final int DROPDOWN_WIDTH = 92;
    private static final int DROPDOWN_HEIGHT = 18;
    private static final int OPTION_WIDTH = 130;
    private static final int OPTION_HEIGHT = 18;
    private static final int MAX_VISIBLE_OPTIONS = 3;
    private static final int[] CTM_PREVIEW_TILES = {0, 1, 2, 6, 7, 8, 12, 13, 14};

    private static final String[] MATERIALS = {
            "aganite", "aterzon", "borealis", "brectite", "calacattum", "chalstrom",
            "chrysonyx", "etruscus", "gelastrum", "glacium", "hesperion", "imperium",
            "kelastrion", "kylorion", "latmion", "laurentium", "mielonyx", "nerium",
            "noxoplis", "porphyros", "portorium", "psamatheon", "rosinium", "sanguenite",
            "selenephos", "solistra", "striatus"
    };
    private static final String[] DIAPHANES_MATERIALS = {
            "borealis", "gelastrum", "mielonyx", "selenephos"
    };
    private static final String[] WEAVE_FOLDERS = {
            "calacattum_portorium_weave_bronze",
            "calacattum_portorium_weave_grout",
            "chalstrom_calacattum_weave_bronze",
            "chalstrom_calacattum_weave_grout",
            "chrysonyx_glacium_weave_bronze",
            "chrysonyx_glacium_weave_grout",
            "gelastrum_etruscus_weave_bronze",
            "gelastrum_etruscus_weave_grout",
            "glacium_nerium_weave_bronze",
            "glacium_nerium_weave_grout",
            "hesperion_glacium_weave_bronze",
            "hesperion_glacium_weave_grout",
            "kylorion_glacium_weave_bronze",
            "kylorion_glacium_weave_grout",
            "laurentium_calacattum_weave_bronze",
            "laurentium_calacattum_weave_grout",
            "mielonyx_imperium_weave_bronze",
            "mielonyx_imperium_weave_grout",
            "rosinium_sanguenite_weave_bronze",
            "rosinium_sanguenite_weave_grout",
            "solistra_etruscus_weave_bronze",
            "solistra_etruscus_weave_grout",
            "striatus_nerium_weave_bronze",
            "striatus_nerium_weave_grout"
    };

    private static final List<Entry> ENTRIES = createEntries();

    private final Screen parent;
    private final int[] selectedVariationIndexes = new int[ENTRIES.size()];
    private final int[] optionScrollIndexes = new int[ENTRIES.size()];
    private final List<ErydonConfigUi.Button> selectorButtons = new ArrayList<>();
    private final List<ErydonConfigUi.Button> optionButtons = new ArrayList<>();
    private int openDropdownIndex = -1;
    private int scrollY;
    private ErydonConfigUi.ScrollBar listScrollBar;
    private ErydonConfigUi.ScrollBar optionScrollBar;

    public ErydonTextureGalleryScreen(Screen parent) {
        super(Text.translatable("screen.erydon.texture_gallery.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int buttonWidth = 92;
        int panelLeft = panelLeft();
        int panelTop = panelTop();

        selectorButtons.clear();
        optionButtons.clear();
        listScrollBar = null;
        optionScrollBar = null;

        addDrawableChild(new ErydonConfigUi.Button(
                panelLeft + (panelWidth() - buttonWidth) / 2,
                panelTop + panelHeight() - 28,
                buttonWidth,
                20,
                Text.translatable("gui.back"),
                16,
                42,
                button -> close()
        ).withStyle(ErydonConfigUi.Button.Style.QUIET));

        for (int index = 0; index < ENTRIES.size(); index++) {
            final int entryIndex = index;
            ErydonConfigUi.Button selector = new ErydonConfigUi.Button(
                    dropdownLeft(),
                    dropdownTop(index),
                    DROPDOWN_WIDTH,
                    DROPDOWN_HEIGHT,
                    selectedVariation(index).label(),
                    selectorNarration(index),
                    226,
                    36,
                    button -> toggleDropdown(entryIndex)
            ).withStyle(ErydonConfigUi.Button.Style.QUIET);
            selectorButtons.add(addDrawableChild(selector));
        }

        listScrollBar = addDrawableChild(new ErydonConfigUi.ScrollBar(
                listRight() - 7,
                listTop(),
                listBottom() - listTop(),
                () -> scrollY,
                this::maxScroll,
                this::setScrollY,
                scrollStep(),
                scrollStep()
        ));
        setScrollY(scrollY);
        if (openDropdownIndex >= 0 && openDropdownIndex < ENTRIES.size() && selectorButtons.get(openDropdownIndex).visible) {
            createDropdownWidgets();
        } else {
            openDropdownIndex = -1;
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        ErydonConfigUi.drawBlackBackground(context, width, height);
        drawPanel(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (isInsideOpenDropdownOptions(mouseX, mouseY) && openDropdownIndex >= 0) {
            int maxOptionScroll = maxOptionScroll(openDropdownIndex);
            optionScrollIndexes[openDropdownIndex] = MathHelper.clamp(
                    optionScrollIndexes[openDropdownIndex] - (int) Math.round(amount),
                    0,
                    maxOptionScroll
            );
            updateDropdownWidgetPositions();
            return true;
        }
        if (isInsideList(mouseX, mouseY)) {
            setScrollY(scrollY - (int) Math.round(amount * scrollStep()));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int clickedDropdown = openDropdownIndex;
        boolean clickedOptionArea = isInsideOpenDropdownOptions(mouseX, mouseY);
        if (super.mouseClicked(mouseX, mouseY, button)) {
            if (clickedOptionArea && openDropdownIndex == -1 && clickedDropdown >= 0 && clickedDropdown < selectorButtons.size()) {
                setFocused(selectorButtons.get(clickedDropdown));
            }
            return true;
        }
        if (button == 0 && openDropdownIndex >= 0) {
            closeDropdown(false);
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && openDropdownIndex >= 0) {
            closeDropdown(true);
            return true;
        }
        int focusedSelector = selectorButtons.indexOf(getFocused());
        if (focusedSelector >= 0 && (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT)) {
            int direction = keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 1;
            cycleVariation(focusedSelector, direction);
            return true;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_PAGE_UP -> setScrollY(scrollY - scrollStep());
            case GLFW.GLFW_KEY_PAGE_DOWN -> setScrollY(scrollY + scrollStep());
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

        context.enableScissor(listLeft(), listTop(), listRight(), listBottom());
        drawEntries(context, mouseX, mouseY);
        context.disableScissor();
    }

    private void drawEntries(DrawContext context, int mouseX, int mouseY) {
        for (int index = 0; index < ENTRIES.size(); index++) {
            int y = entryTop(index);
            if (y + entryHeight() >= listTop() && y <= listBottom()) {
                drawEntry(context, ENTRIES.get(index), index, listLeft(), y, mouseX, mouseY);
            }
        }
    }

    private void drawEntry(DrawContext context, Entry entry, int index, int x, int y, int mouseX, int mouseY) {
        int entryHeight = entryHeight();
        int cardRight = listRight() - 10;
        boolean hovered = mouseX >= x && mouseX < cardRight
                && mouseY >= Math.max(y + 2, listTop())
                && mouseY < Math.min(y + entryHeight - 2, listBottom());
        boolean selected = openDropdownIndex == index;
        context.fill(
                x,
                y + 2,
                cardRight,
                y + entryHeight - 2,
                selected ? 0x26000000 : hovered ? 0x1A000000 : 0x0D000000
        );
        if (selected) {
            ErydonConfigUi.drawSolidBronzeOutline(context, x, y + 2, cardRight, y + entryHeight - 2);
        }

        int previewSize = previewSize();
        int selectedIndex = MathHelper.clamp(selectedVariationIndexes[index], 0, entry.variations().size() - 1);
        Variation selectedVariation = entry.variations().get(selectedIndex);
        if (compactLayout()) {
            int previewX = x + 4;
            int previewY = y + 6;
            int titleX = previewX + previewSize + 8;
            int titleWidth = Math.max(1, cardRight - titleX - 4);
            int descriptionX = x + 4;
            int descriptionY = previewY + previewSize + 8;
            int descriptionWidth = Math.max(1, cardRight - descriptionX - 4);
            int descriptionLines = Math.max(
                    1,
                    (dropdownTop(index) - descriptionY - 4) / ErydonConfigUi.lineHeight(ErydonConfigUi.DESCRIPTION_TEXT_SCALE)
            );
            ErydonConfigUi.FittedText title = ErydonConfigUi.fitReadableText(
                    entry.title(),
                    titleWidth,
                    ErydonConfigUi.BODY_TEXT_SCALE
            );

            drawPreview(context, selectedVariation, previewX, previewY);
            ErydonConfigUi.drawCenteredScaledText(
                    context,
                    title.text(),
                    titleX + titleWidth / 2,
                    y + 12,
                    ErydonConfigUi.BODY_TEXT_SCALE,
                    ErydonConfigUi.TEXT_COLOR
            );
            ErydonConfigUi.drawWrappedScaledText(
                    context,
                    entry.description(),
                    descriptionX,
                    descriptionY,
                    descriptionWidth,
                    descriptionLines,
                    ErydonConfigUi.DESCRIPTION_TEXT_SCALE,
                    ErydonConfigUi.MUTED_TEXT_COLOR
            );
        } else {
            int dropdownLeft = dropdownLeft();
            int textX = x + previewSize + 12;
            int textWidth = dropdownLeft - textX - 8;
            int descriptionLines = Math.min(
                    6,
                    Math.max(1, (entryHeight - 28) / ErydonConfigUi.lineHeight(ErydonConfigUi.DESCRIPTION_TEXT_SCALE))
            );
            ErydonConfigUi.FittedText title = ErydonConfigUi.fitReadableText(
                    entry.title(),
                    textWidth,
                    ErydonConfigUi.BODY_TEXT_SCALE
            );

            drawPreview(context, selectedVariation, x, y + (entryHeight - previewSize) / 2);
            ErydonConfigUi.drawCenteredScaledText(context, title.text(), textX + textWidth / 2, y + 8, ErydonConfigUi.BODY_TEXT_SCALE, ErydonConfigUi.TEXT_COLOR);
            ErydonConfigUi.drawWrappedScaledText(context, entry.description(), textX, y + 24, textWidth, descriptionLines, ErydonConfigUi.DESCRIPTION_TEXT_SCALE, ErydonConfigUi.MUTED_TEXT_COLOR);
        }
    }

    private void drawPreview(DrawContext context, Variation variation, int x, int y) {
        int tileDisplaySize = previewTileSize();
        int previewSize = tileDisplaySize * 3;
        context.fill(x, y, x + previewSize, y + previewSize, 0xFFFFFFFF);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                Identifier texture = variation.previewTexture(row * 3 + column);
                context.drawTexture(
                        texture,
                        x + column * tileDisplaySize,
                        y + row * tileDisplaySize,
                        tileDisplaySize,
                        tileDisplaySize,
                        0.0F,
                        0.0F,
                        FULL_TILE_UV_SIZE,
                        FULL_TILE_UV_SIZE,
                        FULL_TILE_UV_SIZE,
                        FULL_TILE_UV_SIZE
                );
            }
        }
        RenderSystem.disableBlend();
        ErydonConfigUi.drawSolidBronzeOutline(context, x, y, x + previewSize, y + previewSize);
    }

    private void toggleDropdown(int index) {
        if (openDropdownIndex == index) {
            closeDropdown(true);
            return;
        }
        closeDropdown(false);
        openDropdownIndex = index;
        clampOptionScroll(index);
        createDropdownWidgets();
        focusFirstVisibleOption();
    }

    private void createDropdownWidgets() {
        if (openDropdownIndex < 0 || openDropdownIndex >= ENTRIES.size()) {
            return;
        }
        final int entryIndex = openDropdownIndex;
        Entry entry = ENTRIES.get(entryIndex);
        for (int optionIndex = 0; optionIndex < entry.variations().size(); optionIndex++) {
            final int selectedIndex = optionIndex;
            Variation variation = entry.variations().get(optionIndex);
            ErydonConfigUi.Button option = new ErydonConfigUi.Button(
                    dropdownOptionsLeft(),
                    dropdownOptionsTop(openDropdownIndex),
                    optionButtonWidth(entryIndex),
                    OPTION_HEIGHT,
                    variation.label(),
                    variation.label(),
                    34,
                    262,
                    button -> selectVariation(entryIndex, selectedIndex)
            ).withStyle(ErydonConfigUi.Button.Style.QUIET);
            optionButtons.add(addDrawableChild(option));
        }
        if (maxOptionScroll(entryIndex) > 0) {
            optionScrollBar = addDrawableChild(new ErydonConfigUi.ScrollBar(
                    dropdownOptionsLeft() + OPTION_WIDTH - 7,
                    dropdownOptionsTop(entryIndex),
                    visibleOptionCount(entryIndex) * OPTION_HEIGHT,
                    () -> optionScrollIndexes[entryIndex],
                    () -> maxOptionScroll(entryIndex),
                    value -> {
                        optionScrollIndexes[entryIndex] = value;
                        updateDropdownWidgetPositions();
                    },
                    1,
                    visibleOptionCount(entryIndex)
            ));
        }
        updateDropdownWidgetPositions();
    }

    private void updateDropdownWidgetPositions() {
        if (openDropdownIndex < 0 || optionButtons.isEmpty()) {
            return;
        }
        int firstOption = optionScrollIndexes[openDropdownIndex];
        int visibleOptions = visibleOptionCount(openDropdownIndex);
        int left = dropdownOptionsLeft();
        int top = dropdownOptionsTop(openDropdownIndex);
        for (int optionIndex = 0; optionIndex < optionButtons.size(); optionIndex++) {
            ErydonConfigUi.Button option = optionButtons.get(optionIndex);
            boolean visible = optionIndex >= firstOption && optionIndex < firstOption + visibleOptions;
            option.setX(left);
            option.setY(top + (optionIndex - firstOption) * OPTION_HEIGHT);
            option.visible = visible;
            option.active = visible;
        }
        if (optionScrollBar != null) {
            optionScrollBar.setX(left + OPTION_WIDTH - 7);
            optionScrollBar.setY(top);
            optionScrollBar.visible = true;
        }
    }

    private void closeDropdown(boolean restoreFocus) {
        int previousIndex = openDropdownIndex;
        if (getFocused() != null && (optionButtons.contains(getFocused()) || getFocused() == optionScrollBar)) {
            setFocused(null);
        }
        for (ErydonConfigUi.Button option : optionButtons) {
            remove(option);
        }
        optionButtons.clear();
        if (optionScrollBar != null) {
            remove(optionScrollBar);
            optionScrollBar = null;
        }
        openDropdownIndex = -1;
        if (restoreFocus && previousIndex >= 0 && previousIndex < selectorButtons.size()) {
            setFocused(selectorButtons.get(previousIndex));
        }
    }

    private void selectVariation(int entryIndex, int variationIndex) {
        if (entryIndex < 0 || entryIndex >= ENTRIES.size()) {
            return;
        }
        selectedVariationIndexes[entryIndex] = MathHelper.clamp(variationIndex, 0, ENTRIES.get(entryIndex).variations().size() - 1);
        updateSelectorMessage(entryIndex);
        closeDropdown(true);
    }

    private void cycleVariation(int entryIndex, int direction) {
        int size = ENTRIES.get(entryIndex).variations().size();
        selectedVariationIndexes[entryIndex] = Math.floorMod(selectedVariationIndexes[entryIndex] + direction, size);
        updateSelectorMessage(entryIndex);
    }

    private void updateSelectorMessage(int entryIndex) {
        if (entryIndex < 0 || entryIndex >= selectorButtons.size()) {
            return;
        }
        Variation selected = selectedVariation(entryIndex);
        selectorButtons.get(entryIndex).setDisplayMessage(selected.label(), selectorNarration(entryIndex));
    }

    private Variation selectedVariation(int entryIndex) {
        Entry entry = ENTRIES.get(entryIndex);
        int selectedIndex = MathHelper.clamp(selectedVariationIndexes[entryIndex], 0, entry.variations().size() - 1);
        return entry.variations().get(selectedIndex);
    }

    private Text selectorNarration(int entryIndex) {
        return Text.empty()
                .append(ENTRIES.get(entryIndex).title())
                .append(": ")
                .append(selectedVariation(entryIndex).label());
    }

    private void focusFirstVisibleOption() {
        int firstOption = openDropdownIndex < 0 ? -1 : optionScrollIndexes[openDropdownIndex];
        if (firstOption >= 0 && firstOption < optionButtons.size()) {
            setFocused(optionButtons.get(firstOption));
        }
    }

    private void updateSelectorPositions() {
        boolean openSelectorHidden = false;
        for (int index = 0; index < selectorButtons.size(); index++) {
            ErydonConfigUi.Button selector = selectorButtons.get(index);
            int top = dropdownTop(index);
            boolean visible = top >= listTop() && top + DROPDOWN_HEIGHT <= listBottom();
            selector.setX(dropdownLeft());
            selector.setY(top);
            selector.visible = visible;
            selector.active = visible;
            if (!visible && index == openDropdownIndex) {
                openSelectorHidden = true;
            }
            if (!visible && getFocused() == selector) {
                setFocused(null);
            }
        }
        if (openSelectorHidden) {
            closeDropdown(false);
        } else {
            updateDropdownWidgetPositions();
        }
    }

    private void setScrollY(int value) {
        int clampedValue = MathHelper.clamp(value, 0, maxScroll());
        if (clampedValue != scrollY && openDropdownIndex >= 0) {
            closeDropdown(false);
        }
        scrollY = clampedValue;
        updateSelectorPositions();
    }

    private int dropdownLeft() {
        if (compactLayout()) {
            return listLeft() + (listRight() - 10 - listLeft() - DROPDOWN_WIDTH) / 2;
        }
        return listRight() - 10 - DROPDOWN_WIDTH;
    }

    private int dropdownTop(int index) {
        if (compactLayout()) {
            return entryTop(index) + entryHeight() - DROPDOWN_HEIGHT - 8;
        }
        return entryTop(index) + (entryHeight() - DROPDOWN_HEIGHT) / 2;
    }

    private int dropdownOptionsLeft() {
        return dropdownLeft() + DROPDOWN_WIDTH - OPTION_WIDTH;
    }

    private int dropdownOptionsTop(int index) {
        int dropdownTop = dropdownTop(index);
        int optionHeight = visibleOptionCount(index) * OPTION_HEIGHT;
        int below = dropdownTop + DROPDOWN_HEIGHT;
        int preferredTop = below + optionHeight <= listBottom() ? below : dropdownTop - optionHeight;
        return MathHelper.clamp(preferredTop, listTop(), Math.max(listTop(), listBottom() - optionHeight));
    }

    private int visibleOptionCount(int index) {
        int selectorTop = dropdownTop(index);
        int availableBelow = Math.max(0, listBottom() - (selectorTop + DROPDOWN_HEIGHT));
        int availableAbove = Math.max(0, selectorTop - listTop());
        int viewportCapacity = Math.max(1, Math.max(availableBelow, availableAbove) / OPTION_HEIGHT);
        return Math.min(
                ENTRIES.get(index).variations().size(),
                Math.min(MAX_VISIBLE_OPTIONS, viewportCapacity)
        );
    }

    private int maxOptionScroll(int index) {
        return Math.max(0, ENTRIES.get(index).variations().size() - visibleOptionCount(index));
    }

    private int optionButtonWidth(int index) {
        return OPTION_WIDTH - (maxOptionScroll(index) > 0 ? 8 : 0);
    }

    private void clampOptionScroll(int index) {
        optionScrollIndexes[index] = MathHelper.clamp(optionScrollIndexes[index], 0, maxOptionScroll(index));
    }

    private boolean isInsideOpenDropdownOptions(double mouseX, double mouseY) {
        if (openDropdownIndex < 0) {
            return false;
        }
        int left = dropdownOptionsLeft();
        int top = dropdownOptionsTop(openDropdownIndex);
        int height = visibleOptionCount(openDropdownIndex) * OPTION_HEIGHT;
        int visibleTop = Math.max(top, listTop());
        int visibleBottom = Math.min(top + height, listBottom());
        return mouseX >= left && mouseX < left + OPTION_WIDTH && mouseY >= visibleTop && mouseY < visibleBottom;
    }

    private int entryTop(int index) {
        return listTop() - scrollY + index * entryHeight();
    }

    private int listLeft() {
        return panelLeft() + 24;
    }

    private int listRight() {
        return panelLeft() + panelWidth() - 24;
    }

    private int listTop() {
        return panelTop() + 68;
    }

    private int listBottom() {
        return panelTop() + panelHeight() - 34;
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

    private boolean isInsideList(double mouseX, double mouseY) {
        return mouseX >= listLeft() && mouseX < listRight() && mouseY >= listTop() && mouseY < listBottom();
    }

    private int contentHeight() {
        return ENTRIES.size() * entryHeight();
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - (listBottom() - listTop()));
    }

    private int panelWidth() {
        return Math.min(MAX_PANEL_WIDTH, Math.max(260, width - 32));
    }

    private int panelLeft() {
        return (width - panelWidth()) / 2;
    }

    private int previewTileSize() {
        if (panelWidth() >= 440) {
            return 24;
        }
        if (panelWidth() >= 400) {
            return 20;
        }
        return BASE_TILE_DISPLAY_SIZE;
    }

    private int previewSize() {
        return previewTileSize() * 3;
    }

    private int entryHeight() {
        if (compactLayout()) {
            int listHeight = Math.max(1, listBottom() - listTop());
            return Math.max(
                    MIN_COMPACT_ENTRY_HEIGHT,
                    Math.min(COMPACT_ENTRY_HEIGHT, listHeight + 8)
            );
        }
        return Math.max(BASE_ENTRY_HEIGHT, previewSize() + 24);
    }

    private int scrollStep() {
        int entryHeight = entryHeight();
        int listHeight = Math.max(1, listBottom() - listTop());
        if (compactLayout() && listHeight < entryHeight - 8) {
            return Math.max(DROPDOWN_HEIGHT, entryHeight / 2);
        }
        return entryHeight;
    }

    private boolean compactLayout() {
        return panelWidth() < COMPACT_LAYOUT_WIDTH;
    }

    private static List<Entry> createEntries() {
        List<Entry> entries = new ArrayList<>();
        for (String material : MATERIALS) {
            List<Variation> variations = new ArrayList<>();
            variations.add(ctm(Text.translatable("option.erydon.gallery.variant.base"), material));
            variations.add(ctm(Text.translatable("option.erydon.gallery.variant.rock"), material + "_rock"));
            variations.add(ctm(Text.translatable("option.erydon.gallery.variant.aged"), material + "_aged"));
            variations.add(ctm(Text.translatable("option.erydon.gallery.variant.ashlar"), material + "_ashlar"));
            variations.add(ctm(Text.translatable("option.erydon.gallery.variant.herringbone_bronze"), material + "_herringbone_bronze"));
            variations.add(ctm(Text.translatable("option.erydon.gallery.variant.herringbone_grout"), material + "_herringbone_grout"));
            variations.add(ctm(Text.translatable("option.erydon.gallery.variant.hewn"), material + "_hewn"));
            variations.add(ctm(Text.translatable("option.erydon.gallery.variant.rusticated"), material + "_rusticated"));
            if (hasDiaphanes(material)) {
                variations.add(ctm(Text.translatable("option.erydon.gallery.variant.diaphanes"), material + "_diaphanes"));
            }
            addWeaveVariations(material, variations);

            String materialName = titleCase(material);
            entries.add(new Entry(
                    Text.literal(materialName),
                    Text.translatable("option.erydon.gallery." + material + ".description"),
                    List.copyOf(variations)
            ));
        }
        return List.copyOf(entries);
    }

    private static boolean hasDiaphanes(String material) {
        for (String diaphanesMaterial : DIAPHANES_MATERIALS) {
            if (diaphanesMaterial.equals(material)) {
                return true;
            }
        }
        return false;
    }

    private static void addWeaveVariations(String material, List<Variation> variations) {
        for (String folder : WEAVE_FOLDERS) {
            String prefix = folder.substring(0, folder.indexOf("_weave_"));
            String[] pair = prefix.split("_");
            if (pair.length != 2 || (!pair[0].equals(material) && !pair[1].equals(material))) {
                continue;
            }

            String partner = pair[0].equals(material) ? pair[1] : pair[0];
            String finish = folder.endsWith("_bronze") ? "bronze" : "grout";
            variations.add(ctm(
                    Text.translatable("option.erydon.gallery.variant.weave_" + finish, titleCase(partner)),
                    folder
            ));
        }
    }

    private static String titleCase(String id) {
        String[] words = id.split("_");
        StringBuilder title = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!title.isEmpty()) {
                title.append(' ');
            }
            title.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                title.append(word.substring(1));
            }
        }
        return title.toString();
    }

    private static Variation ctm(Text label, String folder) {
        return new Variation(label, folder);
    }

    private record Entry(Text title, Text description, List<Variation> variations) {
    }

    private static final class Variation {
        private final Text label;
        private final Identifier[] previewTextures = new Identifier[CTM_PREVIEW_TILES.length];

        private Variation(Text label, String folder) {
            this.label = label;
            for (int index = 0; index < CTM_PREVIEW_TILES.length; index++) {
                previewTextures[index] = new Identifier(
                        "minecraft",
                        "textures/optifine/ctm/" + folder + "/" + CTM_PREVIEW_TILES[index] + ".png"
                );
            }
        }

        private Text label() {
            return label;
        }

        private Identifier previewTexture(int index) {
            return previewTextures[MathHelper.clamp(index, 0, previewTextures.length - 1)];
        }
    }
}
