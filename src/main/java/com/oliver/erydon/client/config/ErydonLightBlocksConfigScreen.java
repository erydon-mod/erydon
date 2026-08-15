package com.oliver.erydon.client.config;

import com.oliver.erydon.ErydonConfig;
import com.oliver.erydon.ModBlocks;
import com.oliver.erydon.client.ErydonConfigNetworkingClient;
import com.oliver.erydon.network.ErydonConfigNetworking;
import net.minecraft.block.Block;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.function.IntConsumer;

public final class ErydonLightBlocksConfigScreen extends ErydonServerConfigScreen {
    private static final int ICON_WELL_SIZE = 48;
    private static final int LIGHT_ICON_SIZE = 28;
    private static final float LIGHT_ICON_SCALE = 1.75F;
    private static final int PREVIEW_RESOLUTION_SCALE = 2;
    private static final int ROW_HEIGHT = 50;
    private static final int LIGHT_ROW_COUNT = 6;
    private static final int SLIDER_RIGHT_GUTTER = 20;
    private static final int DESCRIPTION_TOP_OFFSET = 16;
    private static final int DESCRIPTION_MAX_LINES = 2;
    private static final int LIST_DESCRIPTION_GAP = 8;
    private static final int PREVIEW_TYPE_DEFAULT = 0;
    private static final int PREVIEW_TYPE_WALL = 1;
    private static final int PREVIEW_TYPE_PENDANT = 2;
    private static final int PREVIEW_TYPE_COUNT = 3;
    private static final int[][][] PREVIEW_PIXEL_CACHE = new int[PREVIEW_TYPE_COUNT][ErydonConfig.MAX_LIGHT_LEVEL + 1][];
    private static final String[] LIGHT_LABEL_KEYS = {
            "option.erydon.light_modern",
            "option.erydon.light_wall",
            "option.erydon.light_pendant",
            "option.erydon.light_brazier",
            "option.erydon.light_oil_burner",
            "option.erydon.light_coffered_ceiling"
    };

    private final int[] lightLevels;
    private final LightLevelSlider[] lightLevelSliders = new LightLevelSlider[LIGHT_ROW_COUNT];
    private final Block[] lightBlocks = {
            ModBlocks.SELENEPHOS_DIAPHANES_LIGHT_MODERN,
            ModBlocks.SELENEPHOS_DIAPHANES_LIGHT_WALL,
            ModBlocks.SELENEPHOS_DIAPHANES_LIGHT_PENDANT,
            ModBlocks.SELENEPHOS_BRAZIER,
            ModBlocks.SELENEPHOS_OIL_BURNER,
            ModBlocks.SELENEPHOS_CEILING_COFFERED_GEORGIAN_WHITE_SMALL
    };
    private final ItemStack[] lightIconStacks = new ItemStack[LIGHT_ROW_COUNT];
    private int scrollY;
    private ErydonConfigUi.ScrollBar scrollBar;
    private ErydonConfigUi.Button resetButton;
    private ErydonConfigUi.Button cancelButton;
    private ErydonConfigUi.Button doneButton;

    public ErydonLightBlocksConfigScreen(Screen parent) {
        this(
                parent,
                ErydonConfig.modernLightLevel(),
                ErydonConfig.wallLightLevel(),
                ErydonConfig.pendantLightLevel(),
                ErydonConfig.brazierLightLevel(),
                ErydonConfig.oilBurnerLightLevel(),
                ErydonConfig.cofferedCeilingLightLevel()
        );
    }

    private ErydonLightBlocksConfigScreen(
            Screen parent,
            int modernLightLevel,
            int wallLightLevel,
            int pendantLightLevel,
            int brazierLightLevel,
            int oilBurnerLightLevel,
            int cofferedCeilingLightLevel
    ) {
        super(parent, Text.translatable("screen.erydon.light_blocks.title"));
        this.lightLevels = new int[] {
                ErydonConfig.clampLightLevel(modernLightLevel),
                ErydonConfig.clampLightLevel(wallLightLevel),
                ErydonConfig.clampLightLevel(pendantLightLevel),
                ErydonConfig.clampLightLevel(brazierLightLevel),
                ErydonConfig.clampLightLevel(oilBurnerLightLevel),
                ErydonConfig.clampLightLevel(cofferedCeilingLightLevel)
        };
        for (int index = 0; index < lightIconStacks.length; index++) {
            lightIconStacks[index] = new ItemStack(lightBlocks[index]);
        }
    }

    @Override
    protected void init() {
        int panelLeft = ErydonConfigUi.panelLeft(width);
        int panelTop = panelTop();
        int contentLeft = panelLeft + 24;
        int contentWidth = ErydonConfigUi.panelWidth(width) - 48;
        int buttonY = panelTop + panelHeight() - 29;
        int buttonWidth = (contentWidth - 16) / 3;

        for (int index = 0; index < LIGHT_ROW_COUNT; index++) {
            final int lightIndex = index;
            lightLevelSliders[index] = addLightLevelSlider(
                    contentLeft + 70,
                    rowTop(index) + 24,
                    contentWidth - 70 - SLIDER_RIGHT_GUTTER,
                    lightLevels[index],
                    value -> lightLevels[lightIndex] = value
            );
        }

        scrollBar = addDrawableChild(new ErydonConfigUi.ScrollBar(
                listRight() - 7,
                listTop(),
                listBottom() - listTop(),
                () -> scrollY,
                this::maxScroll,
                this::setScrollY,
                ROW_HEIGHT,
                Math.max(ROW_HEIGHT, listBottom() - listTop())
        ));

        resetButton = addDrawableChild(new ErydonConfigUi.Button(contentLeft, buttonY, buttonWidth, 20, Text.translatable("button.erydon.reset"), 16, 42, button -> {
            for (int index = 0; index < LIGHT_ROW_COUNT; index++) {
                setLightLevel(index, ErydonConfig.DEFAULT_LIGHT_LEVEL);
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

        scrollY = MathHelper.clamp(scrollY, 0, maxScroll());
        updateSliderPositions();
        finishServerConfigInit();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        updateSliderPositions();
        ErydonConfigUi.drawBlackBackground(context, width, height);
        drawPanel(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (isInsideList(mouseX, mouseY) && maxScroll() > 0) {
            setScrollY(scrollY - (int) Math.round(amount * ROW_HEIGHT));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_PAGE_UP -> setScrollY(scrollY - Math.max(ROW_HEIGHT, listBottom() - listTop()));
            case GLFW.GLFW_KEY_PAGE_DOWN -> setScrollY(scrollY + Math.max(ROW_HEIGHT, listBottom() - listTop()));
            case GLFW.GLFW_KEY_HOME -> setScrollY(0);
            case GLFW.GLFW_KEY_END -> setScrollY(maxScroll());
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        return true;
    }

    private void drawPanel(DrawContext context, int mouseX, int mouseY, float delta) {
        int left = ErydonConfigUi.panelLeft(width);
        int top = panelTop();
        int right = left + ErydonConfigUi.panelWidth(width);
        int bottom = top + panelHeight();
        int contentLeft = left + 24;
        int contentWidth = ErydonConfigUi.panelWidth(width) - 48;

        ErydonConfigUi.drawPanelBackground(context, left, top, right, bottom);
        ErydonConfigUi.drawPageTitle(context, title, left + ErydonConfigUi.panelWidth(width) / 2, pageTitleY());
        ErydonConfigUi.drawWrappedScaledText(
                context,
                Text.translatable("option.erydon.light_blocks.description"),
                contentLeft,
                descriptionTop(),
                contentWidth,
                DESCRIPTION_MAX_LINES,
                ErydonConfigUi.BODY_TEXT_SCALE,
                ErydonConfigUi.MUTED_TEXT_COLOR
        );

        context.enableScissor(listLeft(), listTop(), listRight(), listBottom());
        for (int index = 0; index < LIGHT_ROW_COUNT; index++) {
            int rowTop = rowTop(index);
            if (rowTop + ROW_HEIGHT >= listTop() && rowTop <= listBottom()) {
                drawLightRow(
                        context,
                        lightBlocks[index],
                        lightIconStacks[index],
                        Text.translatable(LIGHT_LABEL_KEYS[index]),
                        currentLightLevel(index),
                        contentLeft,
                        rowTop
                );
            }
        }
        for (LightLevelSlider slider : lightLevelSliders) {
            if (slider != null && slider.visible) {
                slider.render(context, mouseX, mouseY, delta);
            }
        }
        context.disableScissor();
        drawServerStatus(context, contentLeft, bottom - 50, contentWidth);
    }

    private void drawLightRow(DrawContext context, Block block, ItemStack iconStack, Text label, int lightLevel, int contentLeft, int rowTop) {
        int iconWellLeft = contentLeft + 3;
        int lightIconLeft = iconWellLeft + (ICON_WELL_SIZE - LIGHT_ICON_SIZE) / 2;
        int lightIconTop = rowTop + (ICON_WELL_SIZE - LIGHT_ICON_SIZE) / 2;

        drawWallPreview(context, block, iconWellLeft, rowTop, lightLevel);
        ErydonConfigUi.drawSolidBronzeOutline(context, iconWellLeft, rowTop, iconWellLeft + ICON_WELL_SIZE, rowTop + ICON_WELL_SIZE);
        drawLightIcon(context, iconStack, lightIconLeft, lightIconTop);

        ErydonConfigUi.drawScaledText(
                context,
                label,
                contentLeft + 70,
                rowTop + 4,
                ErydonConfigUi.BODY_TEXT_SCALE,
                ErydonConfigUi.TEXT_COLOR
        );
    }

    private void drawLightIcon(DrawContext context, ItemStack iconStack, int x, int y) {
        context.getMatrices().push();
        context.getMatrices().translate(x, y, 100.0F);
        context.getMatrices().scale(LIGHT_ICON_SCALE, LIGHT_ICON_SCALE, 1.0F);
        context.drawItem(iconStack, 0, 0);
        context.getMatrices().pop();
    }

    private static void drawWallPreview(DrawContext context, Block block, int left, int top, int lightLevel) {
        int brightness = ErydonConfig.clampLightLevel(lightLevel);
        int right = left + ICON_WELL_SIZE;
        int bottom = top + ICON_WELL_SIZE;

        context.fill(left, top, right, bottom, 0xFF010101);
        if (brightness > 0) {
            double level = brightness / (double) ErydonConfig.MAX_LIGHT_LEVEL;
            drawPreviewGlowPixels(context, left, top, previewType(block), brightness, level);
        }
        drawPreviewVignette(context, left, top);
    }

    private static int previewType(Block block) {
        if (block == ModBlocks.SELENEPHOS_DIAPHANES_LIGHT_WALL) {
            return PREVIEW_TYPE_WALL;
        }
        if (block == ModBlocks.SELENEPHOS_DIAPHANES_LIGHT_PENDANT) {
            return PREVIEW_TYPE_PENDANT;
        }
        return PREVIEW_TYPE_DEFAULT;
    }

    private static double previewSourceX(int previewType) {
        return previewType == PREVIEW_TYPE_WALL ? 29.0D : 24.0D;
    }

    private static double previewSourceY(int previewType) {
        if (previewType == PREVIEW_TYPE_WALL) {
            return 16.5D;
        }
        return previewType == PREVIEW_TYPE_PENDANT ? 24.0D : 23.0D;
    }

    private static int argb(int alpha, int red, int green, int blue) {
        return (MathHelper.clamp(alpha, 0, 255) << 24)
                | (MathHelper.clamp(red, 0, 255) << 16)
                | (MathHelper.clamp(green, 0, 255) << 8)
                | MathHelper.clamp(blue, 0, 255);
    }

    private static void drawPreviewGlowPixels(DrawContext context, int left, int top, int previewType, int lightLevel, double level) {
        int[] pixels = previewPixels(previewType, lightLevel, level);
        for (int y = 0; y < ICON_WELL_SIZE; y++) {
            for (int x = 0; x < ICON_WELL_SIZE; x++) {
                int color = pixels[y * ICON_WELL_SIZE + x];
                if ((color >>> 24) > 1) {
                    context.fill(left + x, top + y, left + x + 1, top + y + 1, color);
                }
            }
        }
    }

    private static int[] previewPixels(int previewType, int lightLevel, double level) {
        int clampedType = MathHelper.clamp(previewType, 0, PREVIEW_TYPE_COUNT - 1);
        int clampedLevel = ErydonConfig.clampLightLevel(lightLevel);
        int[] cached = PREVIEW_PIXEL_CACHE[clampedType][clampedLevel];
        if (cached != null) {
            return cached;
        }

        int[] pixels = new int[ICON_WELL_SIZE * ICON_WELL_SIZE];
        double scale = PREVIEW_RESOLUTION_SCALE;
        double centerX = previewSourceX(clampedType) * scale;
        double centerY = previewSourceY(clampedType) * scale;
        double broadRadiusX = (6.0D + level * 39.0D) * scale;
        double broadRadiusY = (5.0D + level * 33.0D) * scale;
        double coreRadiusX = (1.2D + level * 9.0D) * scale;
        double coreRadiusY = (1.0D + level * 7.5D) * scale;

        for (int y = 0; y < ICON_WELL_SIZE; y++) {
            for (int x = 0; x < ICON_WELL_SIZE; x++) {
                int alpha = 0;
                int red = 0;
                int green = 0;
                int blue = 0;
                for (int sampleY = 0; sampleY < PREVIEW_RESOLUTION_SCALE; sampleY++) {
                    for (int sampleX = 0; sampleX < PREVIEW_RESOLUTION_SCALE; sampleX++) {
                        int sample = previewSample(
                                x * PREVIEW_RESOLUTION_SCALE + sampleX,
                                y * PREVIEW_RESOLUTION_SCALE + sampleY,
                                centerX,
                                centerY,
                                broadRadiusX,
                                broadRadiusY,
                                coreRadiusX,
                                coreRadiusY,
                                level,
                                scale
                        );
                        alpha += sample >>> 24;
                        red += (sample >>> 16) & 0xFF;
                        green += (sample >>> 8) & 0xFF;
                        blue += sample & 0xFF;
                    }
                }
                int sampleCount = PREVIEW_RESOLUTION_SCALE * PREVIEW_RESOLUTION_SCALE;
                pixels[y * ICON_WELL_SIZE + x] = argb(
                        alpha / sampleCount,
                        red / sampleCount,
                        green / sampleCount,
                        blue / sampleCount
                );
            }
        }
        PREVIEW_PIXEL_CACHE[clampedType][clampedLevel] = pixels;
        return pixels;
    }

    private static int previewSample(
            int x,
            int y,
            double centerX,
            double centerY,
            double broadRadiusX,
            double broadRadiusY,
            double coreRadiusX,
            double coreRadiusY,
            double level,
            double scale
    ) {
        double dx = x + 0.5D - centerX;
        double dy = y + 0.5D - centerY;
        double broad = Math.exp(-((dx * dx) / (broadRadiusX * broadRadiusX)
                + (dy * dy) / (broadRadiusY * broadRadiusY)) * 1.92D);
        double core = Math.exp(-((dx * dx) / (coreRadiusX * coreRadiusX)
                + (dy * dy) / (coreRadiusY * coreRadiusY)) * 2.55D);
        double spill = Math.exp(-Math.abs(dy) / ((14.0D + level * 18.0D) * scale))
                * Math.exp(-Math.abs(dx) / ((20.0D + level * 18.0D) * scale));
        double intensity = clamp(level * (broad * 0.98D + core * 0.38D + spill * 0.12D), 0.0D, 1.0D);
        int alpha = (int) Math.round(210.0D * intensity);
        if (alpha <= 1) {
            return 0;
        }
        double heat = clamp(core * 1.15D + intensity * 0.28D, 0.0D, 1.0D);
        return argb(
                alpha,
                mix(214, 255, heat),
                mix(118, 226, heat),
                mix(32, 150, heat)
        );
    }

    private static void drawPreviewVignette(DrawContext context, int left, int top) {
        int right = left + ICON_WELL_SIZE;
        int bottom = top + ICON_WELL_SIZE;
        context.fill(left, top, right, top + 2, 0x90000000);
        context.fill(left, bottom - 2, right, bottom, 0x9A000000);
        context.fill(left, top, left + 2, bottom, 0x8A000000);
        context.fill(right - 2, top, right, bottom, 0x8A000000);
    }

    private static int mix(int from, int to, double amount) {
        return (int) Math.round(from + (to - from) * clamp(amount, 0.0D, 1.0D));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private LightLevelSlider addLightLevelSlider(int x, int y, int width, int lightLevel, IntConsumer changeListener) {
        return addSelectableChild(new LightLevelSlider(x, y, width, 20, lightLevel, changeListener));
    }

    private void updateSliderPositions() {
        for (int index = 0; index < LIGHT_ROW_COUNT; index++) {
            updateSliderPosition(lightLevelSliders[index], rowTop(index));
        }
    }

    private void updateSliderPosition(LightLevelSlider slider, int rowTop) {
        if (slider == null) {
            return;
        }

        int sliderTop = rowTop + 24;
        boolean visible = sliderTop >= listTop() && sliderTop + slider.getHeight() <= listBottom();
        slider.setY(sliderTop);
        slider.visible = visible;
        slider.active = visible && serverControlsEditable();
        if (!visible && getFocused() == slider) {
            setFocused(null);
        }
    }

    private int rowTop(int index) {
        return listTop() - scrollY + ROW_HEIGHT * index;
    }

    private int listLeft() {
        return ErydonConfigUi.panelLeft(width) + 24;
    }

    private int listRight() {
        return ErydonConfigUi.panelLeft(width) + ErydonConfigUi.panelWidth(width) - 24;
    }

    private int listTop() {
        return descriptionTop() + descriptionHeight() + LIST_DESCRIPTION_GAP;
    }

    private int listBottom() {
        return panelTop() + panelHeight() - 53;
    }

    private boolean isInsideList(double mouseX, double mouseY) {
        return mouseX >= listLeft() && mouseX < listRight() && mouseY >= listTop() && mouseY < listBottom();
    }

    private int contentHeight() {
        return LIGHT_ROW_COUNT * ROW_HEIGHT;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - (listBottom() - listTop()));
    }

    private void setScrollY(int value) {
        scrollY = MathHelper.clamp(value, 0, maxScroll());
        updateSliderPositions();
    }

    private int currentLightLevel(int index) {
        LightLevelSlider slider = lightLevelSliders[index];
        return slider == null ? lightLevels[index] : slider.getLightLevel();
    }

    private int descriptionTop() {
        return pageTitleY() + DESCRIPTION_TOP_OFFSET;
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

    private int descriptionHeight() {
        int lineCount = Math.min(
                DESCRIPTION_MAX_LINES,
                Math.max(
                        1,
                        ErydonConfigUi.wrap(
                                Text.translatable("option.erydon.light_blocks.description").getString(),
                                ErydonConfigUi.panelWidth(width) - 48,
                                ErydonConfigUi.BODY_TEXT_SCALE
                        ).size()
                )
        );
        return lineCount * ErydonConfigUi.lineHeight(ErydonConfigUi.BODY_TEXT_SCALE);
    }

    private void saveAndClose() {
        ErydonConfig.ServerSnapshot snapshot = ErydonConfigNetworkingClient.settings()
                .withModernLightLevel(currentLightLevel(0))
                .withWallLightLevel(currentLightLevel(1))
                .withPendantLightLevel(currentLightLevel(2))
                .withBrazierLightLevel(currentLightLevel(3))
                .withOilBurnerLightLevel(currentLightLevel(4))
                .withCofferedCeilingLightLevel(currentLightLevel(5));
        submitServerSettings(snapshot, ErydonConfigNetworking.UPDATE_LIGHT_SETTINGS);
    }

    @Override
    protected void setServerControlsActive(boolean active) {
        if (resetButton != null) {
            resetButton.active = active;
        }
        if (doneButton != null) {
            doneButton.active = active;
        }
        if (cancelButton != null) {
            cancelButton.active = !serverSaveInProgress();
        }
        updateSliderPositions();
    }

    @Override
    protected void applyServerSnapshot(ErydonConfig.ServerSnapshot snapshot) {
        setLightLevel(0, snapshot.modernLightLevel());
        setLightLevel(1, snapshot.wallLightLevel());
        setLightLevel(2, snapshot.pendantLightLevel());
        setLightLevel(3, snapshot.brazierLightLevel());
        setLightLevel(4, snapshot.oilBurnerLightLevel());
        setLightLevel(5, snapshot.cofferedCeilingLightLevel());
    }

    private void setLightLevel(int index, int value) {
        lightLevels[index] = ErydonConfig.clampLightLevel(value);
        LightLevelSlider slider = lightLevelSliders[index];
        if (slider != null) {
            slider.setLightLevel(lightLevels[index]);
        }
    }

    private static final class LightLevelSlider extends ErydonConfigUi.Slider {
        private int lightLevel;
        private final IntConsumer changeListener;

        private LightLevelSlider(int x, int y, int width, int height, int lightLevel, IntConsumer changeListener) {
            super(x, y, width, height, Text.empty(), normalizedValue(lightLevel));
            this.lightLevel = ErydonConfig.clampLightLevel(lightLevel);
            this.changeListener = changeListener;
            updateMessage();
        }

        private int getLightLevel() {
            return lightLevel;
        }

        private void setLightLevel(int lightLevel) {
            this.lightLevel = ErydonConfig.clampLightLevel(lightLevel);
            value = normalizedValue(this.lightLevel);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.translatable("option.erydon.light_level.value", lightLevel));
        }

        @Override
        protected void applyValue() {
            lightLevel = MathHelper.clamp(
                    (int) Math.round(value * ErydonConfig.MAX_LIGHT_LEVEL),
                    ErydonConfig.MIN_LIGHT_LEVEL,
                    ErydonConfig.MAX_LIGHT_LEVEL
            );
            value = normalizedValue(lightLevel);
            changeListener.accept(lightLevel);
        }

        private static double normalizedValue(int lightLevel) {
            return ErydonConfig.clampLightLevel(lightLevel) / (double) ErydonConfig.MAX_LIGHT_LEVEL;
        }
    }
}
