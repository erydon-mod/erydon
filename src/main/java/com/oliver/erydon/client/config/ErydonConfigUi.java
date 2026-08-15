package com.oliver.erydon.client.config;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

final class ErydonConfigUi {
    enum StatusTone {
        NEUTRAL,
        SUCCESS,
        WARNING,
        ERROR
    }

    static final int PANEL_WIDTH = 360;
    static final int PANEL_HEIGHT = 188;
    static final int FORM_PANEL_HEIGHT = 220;
    static final int TEXT_COLOR = 0xFF050505;
    static final int MUTED_TEXT_COLOR = 0xFF1F1F1F;
    static final int PAGE_TITLE_Y_OFFSET = 52;
    static final float TITLE_TEXT_SCALE = 1.2F;
    static final float BODY_TEXT_SCALE = 0.95F;
    static final float DESCRIPTION_TEXT_SCALE = 0.85F;
    static final float STATUS_TEXT_SCALE = 0.82F;

    private static final int PANEL_TEXTURE_WIDTH = 720;
    private static final int PANEL_TEXTURE_HEIGHT = 376;
    private static final int PANEL_HEADER_SOURCE_HEIGHT = 120;
    private static final int PANEL_FOOTER_SOURCE_HEIGHT = 16;
    private static final int PANEL_HEADER_HEIGHT = PANEL_HEADER_SOURCE_HEIGHT / 2;
    private static final int PANEL_FOOTER_HEIGHT = PANEL_FOOTER_SOURCE_HEIGHT / 2;
    private static final int PANEL_CENTER_SOURCE_WIDTH = 320;
    private static final int PANEL_CENTER_WIDTH = PANEL_CENTER_SOURCE_WIDTH / 2;
    private static final int PANEL_SIDE_SOURCE_WIDTH = (PANEL_TEXTURE_WIDTH - PANEL_CENTER_SOURCE_WIDTH) / 2;
    private static final Identifier PANEL_TEXTURE = new Identifier(
            "erydon",
            "textures/gui/glacium_config_background.png"
    );
    private static final int NERIUM_TILE_SIZE = 1024;
    private static final int NERIUM_SOURCE_SCALE = 2;
    private static final int SAMPLE_VARIATION = 96;
    private static final Identifier NERIUM_TEXTURE = new Identifier(
            "erydon",
            "textures/gui/nerium_control_background.png"
    );
    private static final Identifier CINZEL_FONT = new Identifier("erydon", "cinzel");
    private static final int BUTTON_TEXT_COLOR = 0xFFF8F3E8;
    private static final int BRONZE = 0xFFE5A01D;
    private static final int DISABLED_BUTTON_TEXT_COLOR = 0xFFB8B0A2;
    private static final int DISABLED_BODY_TEXT_COLOR = 0xFF6E6A63;
    private static final int FOCUS_COLOR = 0xFFFFE8A8;
    private static final int CONTROL_OVERLAY = 0x74000000;
    private static final int CONTROL_HOVER_OVERLAY = 0x56000000;
    private static final int CONTROL_QUIET_OVERLAY = 0x8A000000;
    private static final int CONTROL_DISABLED_OVERLAY = 0x88000000;
    private static final float TEXT_SHADOW_X = -0.5F;
    private static final float TEXT_SHADOW_Y = 0.5F;
    private static final int BUTTON_TEXT_LINE_HEIGHT = 9;
    private static final int CINZEL_BUTTON_OPTICAL_SHIFT_Y = 3;
    private static final int SLIDER_HANDLE_TEXTURE_WIDTH = 18;
    private static final int SLIDER_HANDLE_TEXTURE_HEIGHT = 40;
    private static final int SLIDER_HANDLE_WIDTH = 7;
    private static final int SLIDER_HANDLE_HEIGHT = 15;
    private static final Identifier SLIDER_HANDLE_TEXTURE = new Identifier(
            "erydon",
            "textures/gui/slider_handle_slim.png"
    );

    private ErydonConfigUi() {
    }

    static void drawBlackBackground(DrawContext context, int width, int height) {
        int sampleWidth;
        int sampleHeight;
        if (width >= height) {
            sampleWidth = NERIUM_TILE_SIZE;
            sampleHeight = Math.max(1, Math.round(NERIUM_TILE_SIZE * (height / (float) Math.max(1, width))));
        } else {
            sampleHeight = NERIUM_TILE_SIZE;
            sampleWidth = Math.max(1, Math.round(NERIUM_TILE_SIZE * (width / (float) Math.max(1, height))));
        }
        int sampleX = (NERIUM_TILE_SIZE - sampleWidth) / 2;
        int sampleY = (NERIUM_TILE_SIZE - sampleHeight) / 2;
        context.drawTexture(
                NERIUM_TEXTURE,
                0,
                0,
                width,
                height,
                sampleX,
                sampleY,
                sampleWidth,
                sampleHeight,
                NERIUM_TILE_SIZE,
                NERIUM_TILE_SIZE
        );
        context.fill(0, 0, width, height, 0xE6000000);

        // Four cheap bands create a restrained vignette without a shader or a large extra texture.
        int band = Math.max(4, Math.min(16, Math.min(width, height) / 24));
        for (int layer = 0; layer < 4; layer++) {
            int inset = layer * band;
            int alpha = 0x18 - layer * 0x04;
            int shade = alpha << 24;
            context.fill(inset, inset, width - inset, inset + band, shade);
            context.fill(inset, height - inset - band, width - inset, height - inset, shade);
            context.fill(inset, inset + band, inset + band, height - inset - band, shade);
            context.fill(width - inset - band, inset + band, width - inset, height - inset - band, shade);
        }
    }

    static void drawPanelBackground(DrawContext context, int left, int top, int right, int bottom) {
        int width = right - left;
        int height = bottom - top;
        int middleHeight = Math.max(1, height - PANEL_HEADER_HEIGHT - PANEL_FOOTER_HEIGHT);
        int middleSourceHeight = PANEL_TEXTURE_HEIGHT - PANEL_HEADER_SOURCE_HEIGHT - PANEL_FOOTER_SOURCE_HEIGHT;
        int centerWidth = Math.min(PANEL_CENTER_WIDTH, width);
        int leftWidth = Math.max(0, (width - centerWidth) / 2);
        int rightWidth = Math.max(0, width - centerWidth - leftWidth);

        context.fill(left - 3, top + 2, right + 3, bottom + 4, 0x68000000);

        drawPanelRow(context, left, top, leftWidth, centerWidth, rightWidth, PANEL_HEADER_HEIGHT, 0, PANEL_HEADER_SOURCE_HEIGHT);
        drawPanelRow(
                context,
                left,
                top + PANEL_HEADER_HEIGHT,
                leftWidth,
                centerWidth,
                rightWidth,
                middleHeight,
                PANEL_HEADER_SOURCE_HEIGHT,
                middleSourceHeight
        );
        drawPanelRow(
                context,
                left,
                bottom - PANEL_FOOTER_HEIGHT,
                leftWidth,
                centerWidth,
                rightWidth,
                PANEL_FOOTER_HEIGHT,
                PANEL_TEXTURE_HEIGHT - PANEL_FOOTER_SOURCE_HEIGHT,
                PANEL_FOOTER_SOURCE_HEIGHT
        );
    }

    private static void drawPanelRow(
            DrawContext context,
            int left,
            int top,
            int leftWidth,
            int centerWidth,
            int rightWidth,
            int height,
            int sourceY,
            int sourceHeight
    ) {
        context.drawTexture(
                PANEL_TEXTURE,
                left,
                top,
                leftWidth,
                height,
                0.0F,
                sourceY,
                PANEL_SIDE_SOURCE_WIDTH,
                sourceHeight,
                PANEL_TEXTURE_WIDTH,
                PANEL_TEXTURE_HEIGHT
        );
        context.drawTexture(
                PANEL_TEXTURE,
                left + leftWidth,
                top,
                centerWidth,
                height,
                PANEL_SIDE_SOURCE_WIDTH,
                sourceY,
                PANEL_CENTER_SOURCE_WIDTH,
                sourceHeight,
                PANEL_TEXTURE_WIDTH,
                PANEL_TEXTURE_HEIGHT
        );
        context.drawTexture(
                PANEL_TEXTURE,
                left + leftWidth + centerWidth,
                top,
                rightWidth,
                height,
                PANEL_SIDE_SOURCE_WIDTH + PANEL_CENTER_SOURCE_WIDTH,
                sourceY,
                PANEL_SIDE_SOURCE_WIDTH,
                sourceHeight,
                PANEL_TEXTURE_WIDTH,
                PANEL_TEXTURE_HEIGHT
        );
    }

    static void drawNeriumControlTexture(DrawContext context, int left, int top, int right, int bottom, int sourceX, int sourceY) {
        int sourceWidth = Math.min((right - left) * NERIUM_SOURCE_SCALE, NERIUM_TILE_SIZE);
        int sourceHeight = Math.min((bottom - top) * NERIUM_SOURCE_SCALE, NERIUM_TILE_SIZE);
        int scaledSourceX = variedSource(sourceX * NERIUM_SOURCE_SCALE, left, top, sourceWidth, 17, 7);
        int scaledSourceY = variedSource(sourceY * NERIUM_SOURCE_SCALE, left, top, sourceHeight, 5, 13);
        context.drawTexture(
                NERIUM_TEXTURE,
                left,
                top,
                right - left,
                bottom - top,
                scaledSourceX,
                scaledSourceY,
                sourceWidth,
                sourceHeight,
                NERIUM_TILE_SIZE,
                NERIUM_TILE_SIZE
        );
    }

    static void drawNeriumButtonFrame(DrawContext context, int left, int top, int right, int bottom, int sourceX, int sourceY, boolean hovered) {
        drawNeriumButtonFrame(context, left, top, right, bottom, sourceX, sourceY, Button.Style.NEUTRAL, hovered, false);
    }

    private static void drawNeriumButtonFrame(
            DrawContext context,
            int left,
            int top,
            int right,
            int bottom,
            int sourceX,
            int sourceY,
            Button.Style style,
            boolean highlighted,
            boolean pressed
    ) {
        drawNeriumControlTexture(context, left, top, right, bottom, sourceX, sourceY);
        int overlay = switch (style) {
            case PRIMARY -> highlighted ? 0x46000000 : 0x60000000;
            case NEUTRAL -> highlighted ? CONTROL_HOVER_OVERLAY : CONTROL_OVERLAY;
            case QUIET -> highlighted ? 0x70000000 : CONTROL_QUIET_OVERLAY;
        };
        context.fill(left, top, right, bottom, overlay);
        if (style == Button.Style.PRIMARY) {
            context.fill(left + 2, top + 2, right - 2, bottom - 2, highlighted ? 0x20E5A01D : 0x12E5A01D);
        }
        if (pressed) {
            context.fill(left, top, right, bottom, 0x36000000);
        }
        drawFineNeriumBevel(context, left, top, right, bottom, highlighted && !pressed);
        if (style != Button.Style.QUIET || highlighted) {
            drawInsetBronzeOutline(context, left, top, right, bottom);
        }
        if (style == Button.Style.PRIMARY) {
            drawSolidBronzeOutline(context, left, top, right, bottom);
        }
    }

    static void drawNeriumSliderTrack(DrawContext context, int left, int top, int right, int bottom, int sourceX, int sourceY) {
        drawNeriumControlTexture(context, left, top, right, bottom, sourceX, sourceY);
        context.fill(left, top, right, bottom, CONTROL_OVERLAY);
        drawFineNeriumBevel(context, left, top, right, bottom, false);
        drawInsetBronzeOutline(context, left, top, right, bottom);
    }

    private static int variedSource(int base, int left, int top, int sourceSize, int xFactor, int yFactor) {
        int max = Math.max(0, NERIUM_TILE_SIZE - sourceSize);
        int offset = Math.floorMod(left * xFactor + top * yFactor, SAMPLE_VARIATION) - SAMPLE_VARIATION / 2;
        return Math.max(0, Math.min(max, base + offset));
    }

    static void drawInsetBronzeOutline(DrawContext context, int left, int top, int right, int bottom) {
        drawInsetOutline(context, left, top, right, bottom, BRONZE);
    }

    private static void drawInsetOutline(DrawContext context, int left, int top, int right, int bottom, int color) {
        int borderLeft = (left + 2) * 2;
        int borderTop = (top + 2) * 2;
        int borderRight = (right - 2) * 2;
        int borderBottom = (bottom - 2) * 2;

        context.getMatrices().push();
        context.getMatrices().scale(0.5F, 0.5F, 1.0F);
        context.fill(borderLeft, borderTop, borderRight, borderTop + 1, color);
        context.fill(borderLeft, borderBottom - 1, borderRight, borderBottom, color);
        context.fill(borderLeft, borderTop, borderLeft + 1, borderBottom, color);
        context.fill(borderRight - 1, borderTop, borderRight, borderBottom, color);
        context.getMatrices().pop();
    }

    static void drawSolidBronzeOutline(DrawContext context, int left, int top, int right, int bottom) {
        context.fill(left, top, right, top + 1, BRONZE);
        context.fill(left, bottom - 1, right, bottom, BRONZE);
        context.fill(left, top, left + 1, bottom, BRONZE);
        context.fill(right - 1, top, right, bottom, BRONZE);
    }

    static boolean drawButtonText(DrawContext context, Text text, int left, int top, int width, int height, int color) {
        FittedText fitted = fitText(text, Math.max(1, width - 10), 1.0F);
        Text message = cinzel(fitted.text());
        int textX = left + (width - textWidth(message)) / 2;
        // Cinzel's visible capitals sit above Minecraft's nominal nine-pixel line box.
        // Centre the visible letterforms rather than the font's invisible line metrics.
        int textY = top + Math.max(
                1,
                (height - BUTTON_TEXT_LINE_HEIGHT) / 2 + 1 + CINZEL_BUTTON_OPTICAL_SHIFT_Y
        );
        drawShadowedText(context, message, textX, textY, color);
        return fitted.truncated();
    }

    static boolean drawReadableButtonText(DrawContext context, Text text, int left, int top, int width, int height, int color) {
        return drawButtonText(context, text, left, top, width, height, color);
    }

    static boolean drawButtonText(DrawContext context, Text text, int left, int top, int width, int height) {
        return drawButtonText(context, text, left, top, width, height, BUTTON_TEXT_COLOR);
    }

    static void drawButtonText(DrawContext context, Text text, int left, int top, int width) {
        drawButtonText(context, text, left, top, width, 20, BUTTON_TEXT_COLOR);
    }

    static void drawLightText(DrawContext context, Text text, int x, int y) {
        drawShadowedText(context, cinzel(text), x, y, BUTTON_TEXT_COLOR);
    }

    static void drawShadowedText(DrawContext context, Text text, int x, int y, int color) {
        context.getMatrices().push();
        context.getMatrices().translate(TEXT_SHADOW_X, TEXT_SHADOW_Y, 0.0F);
        context.drawText(textRenderer(), text, x, y, 0xA0000000, false);
        context.getMatrices().pop();
        context.drawText(textRenderer(), text, x, y, color, false);
    }

    static void drawCenteredText(DrawContext context, Text text, int centerX, int y, int color) {
        Text message = cinzel(text);
        context.drawText(textRenderer(), message, centerX - textWidth(message) / 2, y, color, false);
    }

    static void drawPageTitle(DrawContext context, Text text, int centerX, int y) {
        drawCenteredDisplayText(context, text, centerX, y, TITLE_TEXT_SCALE, TEXT_COLOR);
    }

    static void drawCenteredScaledText(DrawContext context, Text text, int centerX, int y, float scale, int color) {
        drawCenteredReadableText(context, text, centerX, y, scale, color);
    }

    static void drawCenteredReadableText(DrawContext context, Text text, int centerX, int y, float scale, int color) {
        Text message = cinzel(text);
        context.getMatrices().push();
        context.getMatrices().translate(centerX, y, 0.0F);
        context.getMatrices().scale(scale, scale, 1.0F);
        context.drawText(textRenderer(), message, -textWidth(message) / 2, 0, color, false);
        context.getMatrices().pop();
    }

    static void drawScaledText(DrawContext context, Text text, int x, int y, float scale, int color) {
        drawReadableScaledText(context, text, x, y, scale, color);
    }

    static void drawReadableScaledText(DrawContext context, Text text, int x, int y, float scale, int color) {
        Text message = cinzel(text);
        context.getMatrices().push();
        context.getMatrices().translate(x, y, 0.0F);
        context.getMatrices().scale(scale, scale, 1.0F);
        context.drawText(textRenderer(), message, 0, 0, color, false);
        context.getMatrices().pop();
    }

    static void drawWrappedScaledText(DrawContext context, Text text, int x, int y, int width, int maxLines, float scale, int color) {
        drawWrappedReadableText(context, text, x, y, width, maxLines, scale, color);
    }

    static void drawWrappedReadableText(DrawContext context, Text text, int x, int y, int width, int maxLines, float scale, int color) {
        List<String> lines = wrap(text.getString(), width, scale);
        int renderedLines = Math.min(lines.size(), maxLines);
        int lineHeight = lineHeight(scale);
        for (int index = 0; index < renderedLines; index++) {
            String line = lines.get(index);
            if (index == renderedLines - 1 && lines.size() > maxLines) {
                line = ellipsizeDisplay(line, width, scale);
            }
            drawReadableScaledText(context, Text.literal(line), x, y + index * lineHeight, scale, color);
        }
    }

    static FittedText fitText(Text text, int width, float scale) {
        int scaledWidth = Math.max(1, Math.round(width / scale));
        Text styled = cinzel(text);
        if (textWidth(styled) <= scaledWidth) {
            return new FittedText(text, false);
        }
        return new FittedText(Text.literal(ellipsizeDisplay(text.getString(), width, scale)), true);
    }

    static FittedText fitReadableText(Text text, int width, float scale) {
        return fitText(text, width, scale);
    }

    private static String ellipsizeDisplay(String text, int width, float scale) {
        int scaledWidth = Math.max(1, Math.round(width / scale));
        String suffix = "...";
        String fitted = text;
        while (!fitted.isEmpty() && textWidth(cinzel(Text.literal(fitted + suffix))) > scaledWidth) {
            fitted = fitted.substring(0, fitted.length() - 1);
        }
        return fitted + suffix;
    }

    static List<String> wrap(String text, int width, float scale) {
        int scaledWidth = Math.max(1, Math.round(width / scale));
        List<String> lines = new ArrayList<>();
        StringBuilder currentLine = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = currentLine.isEmpty() ? word : currentLine + " " + word;
            if (!currentLine.isEmpty() && textWidth(cinzel(Text.literal(candidate))) > scaledWidth) {
                lines.add(currentLine.toString());
                currentLine = new StringBuilder(word);
            } else {
                currentLine = new StringBuilder(candidate);
            }
        }
        if (!currentLine.isEmpty()) {
            lines.add(currentLine.toString());
        }
        return lines;
    }

    static int lineHeight(float scale) {
        return Math.max(7, Math.round(10.0F * scale));
    }

    static Text cinzel(Text text) {
        return text.copy().styled(style -> style.withFont(CINZEL_FONT));
    }

    private static void drawCenteredDisplayText(DrawContext context, Text text, int centerX, int y, float scale, int color) {
        Text message = cinzel(text);
        context.getMatrices().push();
        context.getMatrices().translate(centerX, y, 0.0F);
        context.getMatrices().scale(scale, scale, 1.0F);
        context.drawText(textRenderer(), message, -textWidth(message) / 2, 0, color, false);
        context.getMatrices().pop();
    }

    static int panelWidth(int screenWidth) {
        return Math.min(PANEL_WIDTH, Math.max(260, screenWidth - 32));
    }

    static int panelLeft(int screenWidth) {
        return (screenWidth - panelWidth(screenWidth)) / 2;
    }

    static int panelTop(int screenHeight) {
        return Math.max(24, (screenHeight - PANEL_HEIGHT) / 2);
    }

    static int responsivePanelHeight(int screenHeight) {
        return Math.min(320, Math.max(PANEL_HEIGHT, screenHeight - 32));
    }

    static int panelTop(int screenHeight, int panelHeight) {
        return Math.max(16, (screenHeight - panelHeight) / 2);
    }

    static int pageTitleY(int screenHeight) {
        return panelTop(screenHeight) + PAGE_TITLE_Y_OFFSET;
    }

    static TextRenderer textRenderer() {
        return MinecraftClient.getInstance().textRenderer;
    }

    static int textWidth(Text text) {
        return textRenderer().getWidth(text);
    }

    static void drawFocusOutline(DrawContext context, int left, int top, int right, int bottom) {
        context.fill(left - 1, top - 1, right + 1, top, 0xB0000000);
        context.fill(left - 1, bottom, right + 1, bottom + 1, 0xB0000000);
        context.fill(left - 1, top, left, bottom, 0xB0000000);
        context.fill(right, top, right + 1, bottom, 0xB0000000);
        context.fill(left, top, right, top + 1, FOCUS_COLOR);
        context.fill(left, bottom - 1, right, bottom, FOCUS_COLOR);
        context.fill(left, top, left + 1, bottom, FOCUS_COLOR);
        context.fill(right - 1, top, right, bottom, FOCUS_COLOR);
    }

    static void drawStatusStrip(DrawContext context, Text text, int x, int y, int width, StatusTone tone) {
        StatusTone safeTone = tone == null ? StatusTone.NEUTRAL : tone;
        int accent = switch (safeTone) {
            case NEUTRAL -> 0xFF756B5C;
            case SUCCESS -> 0xFF3F8151;
            case WARNING -> 0xFF9A6817;
            case ERROR -> 0xFF9B3F3F;
        };
        int height = 20;
        context.fill(x, y, x + width, y + height, withAlpha(accent, 0x18));
        context.fill(x, y, x + 2, y + height, accent);
        context.fill(x + 2, y, x + width, y + 1, withAlpha(accent, 0x60));
        context.fill(x + 2, y + height - 1, x + width, y + height, withAlpha(accent, 0x60));
        drawStatusIcon(context, x + 5, y + 6, safeTone, accent);
        drawWrappedReadableText(
                context,
                text,
                x + 18,
                y + 2,
                Math.max(1, width - 22),
                2,
                STATUS_TEXT_SCALE,
                TEXT_COLOR
        );
    }

    private static void drawStatusIcon(DrawContext context, int x, int y, StatusTone tone, int color) {
        switch (tone) {
            case NEUTRAL -> {
                context.fill(x + 2, y, x + 4, y + 2, color);
                context.fill(x + 1, y + 2, x + 5, y + 4, color);
                context.fill(x + 2, y + 4, x + 4, y + 6, color);
            }
            case SUCCESS -> {
                context.fill(x, y + 3, x + 2, y + 5, color);
                context.fill(x + 1, y + 4, x + 3, y + 6, color);
                context.fill(x + 3, y + 2, x + 5, y + 5, color);
                context.fill(x + 4, y + 1, x + 6, y + 3, color);
            }
            case WARNING -> {
                context.fill(x + 2, y, x + 4, y + 4, color);
                context.fill(x + 2, y + 5, x + 4, y + 7, color);
            }
            case ERROR -> {
                context.fill(x, y, x + 2, y + 2, color);
                context.fill(x + 4, y, x + 6, y + 2, color);
                context.fill(x + 1, y + 1, x + 5, y + 5, color);
                context.fill(x, y + 4, x + 2, y + 6, color);
                context.fill(x + 4, y + 4, x + 6, y + 6, color);
            }
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (MathHelper.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    static class Button extends ButtonWidget {
        enum Style {
            PRIMARY,
            NEUTRAL,
            QUIET
        }

        private final int textureX;
        private final int textureY;
        private Text displayMessage;
        private Style style = Style.NEUTRAL;
        private boolean overflowTooltip;
        private long pressedUntilMs;

        Button(int x, int y, int width, int height, Text message, int textureX, int textureY, PressAction onPress) {
            this(x, y, width, height, message, message, textureX, textureY, onPress);
        }

        Button(int x, int y, int width, int height, Text displayMessage, Text narrationMessage, int textureX, int textureY, PressAction onPress) {
            super(x, y, width, height, narrationMessage, onPress, DEFAULT_NARRATION_SUPPLIER);
            this.textureX = textureX;
            this.textureY = textureY;
            this.displayMessage = displayMessage;
        }

        Button withStyle(Style style) {
            this.style = style == null ? Style.NEUTRAL : style;
            return this;
        }

        void setDisplayMessage(Text displayMessage, Text narrationMessage) {
            this.displayMessage = displayMessage;
            setMessage(narrationMessage);
            if (overflowTooltip) {
                setTooltip(null);
                overflowTooltip = false;
            }
        }

        @Override
        public void onPress() {
            pressedUntilMs = Util.getMeasuringTimeMs() + 120L;
            super.onPress();
        }

        @Override
        protected void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
            int left = getX();
            int top = getY();
            int right = left + width;
            int bottom = top + height;
            boolean hovered = isMouseOver(mouseX, mouseY);
            boolean highlighted = hovered || isFocused();
            boolean pressed = active && Util.getMeasuringTimeMs() < pressedUntilMs;

            drawNeriumButtonFrame(context, left, top, right, bottom, textureX, textureY, style, highlighted, pressed);
            if (!active) {
                context.fill(left, top, right, bottom, CONTROL_DISABLED_OVERLAY);
            }
            int textTop = pressed ? top + 1 : top;
            int textHeight = pressed ? Math.max(1, height - 1) : height;
            boolean truncated = style == Style.QUIET
                    ? drawReadableButtonText(
                            context,
                            displayMessage,
                            left,
                            textTop,
                            width,
                            textHeight,
                            active ? BUTTON_TEXT_COLOR : DISABLED_BUTTON_TEXT_COLOR
                    )
                    : drawButtonText(
                            context,
                            displayMessage,
                            left,
                            textTop,
                            width,
                            textHeight,
                            active ? BUTTON_TEXT_COLOR : DISABLED_BUTTON_TEXT_COLOR
                    );
            if (truncated && getTooltip() == null) {
                setTooltip(Tooltip.of(displayMessage));
                overflowTooltip = true;
            }
            if (isFocused()) {
                drawFocusOutline(context, left, top, right, bottom);
            }
        }
    }

    static final class Toggle extends ButtonWidget {
        private static final float TOGGLE_LABEL_SCALE = 0.9F;
        private static final float TOGGLE_DESCRIPTION_SCALE = 0.8F;
        private static final float TOGGLE_STATE_SCALE = 0.8F;
        private static final int CHECKBOX_SIZE = 13;

        private final Text label;
        private final Text description;
        private final BooleanSupplier valueSupplier;
        private final Consumer<Boolean> valueConsumer;

        Toggle(int x, int y, int width, int height, Text label, Text description, BooleanSupplier valueSupplier, Consumer<Boolean> valueConsumer) {
            super(x, y, width, height, narration(label, valueSupplier.getAsBoolean()), button -> { }, DEFAULT_NARRATION_SUPPLIER);
            this.label = label;
            this.description = description;
            this.valueSupplier = valueSupplier;
            this.valueConsumer = valueConsumer;
            setTooltip(Tooltip.of(description));
        }

        @Override
        public void onPress() {
            boolean value = !valueSupplier.getAsBoolean();
            valueConsumer.accept(value);
            setMessage(narration(label, value));
        }

        @Override
        protected void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
            int left = getX();
            int top = getY();
            int right = left + width;
            int bottom = top + height;
            boolean enabled = valueSupplier.getAsBoolean();
            int checkboxLeft = right - CHECKBOX_SIZE - 5;
            int checkboxTop = top + (height - CHECKBOX_SIZE) / 2;
            Text stateText = Text.translatable(enabled ? "options.on" : "options.off");
            int stateWidth = Math.round(textWidth(stateText) * TOGGLE_STATE_SCALE);
            boolean showState = width >= 176;
            int stateLeft = checkboxLeft - stateWidth - 5;
            int textRight = showState ? stateLeft - 4 : checkboxLeft - 5;
            int availableTextWidth = Math.max(1, textRight - left - 4);

            context.fill(left, top, right, bottom, 0x0E000000);
            if (isMouseOver(mouseX, mouseY) || isFocused()) {
                context.fill(left, top, right, bottom, 0x16E5A01D);
            } else if (enabled) {
                context.fill(left, top, right, bottom, 0x18000000);
            }
            FittedText fittedLabel = fitReadableText(label, availableTextWidth, TOGGLE_LABEL_SCALE);
            FittedText fittedDescription = fitReadableText(description, availableTextWidth, TOGGLE_DESCRIPTION_SCALE);
            drawReadableScaledText(context, fittedLabel.text(), left + 4, top + 3, TOGGLE_LABEL_SCALE, active ? TEXT_COLOR : DISABLED_BODY_TEXT_COLOR);
            drawReadableScaledText(context, fittedDescription.text(), left + 4, top + 12, TOGGLE_DESCRIPTION_SCALE, active ? MUTED_TEXT_COLOR : DISABLED_BODY_TEXT_COLOR);
            if (showState) {
                drawReadableScaledText(
                        context,
                        stateText,
                        stateLeft,
                        top + Math.max(1, (height - lineHeight(TOGGLE_STATE_SCALE)) / 2),
                        TOGGLE_STATE_SCALE,
                        active ? MUTED_TEXT_COLOR : DISABLED_BODY_TEXT_COLOR
                );
            }
            context.fill(checkboxLeft, checkboxTop, checkboxLeft + CHECKBOX_SIZE, checkboxTop + CHECKBOX_SIZE, 0xFF1B1B1B);
            drawSolidBronzeOutline(context, checkboxLeft, checkboxTop, checkboxLeft + CHECKBOX_SIZE, checkboxTop + CHECKBOX_SIZE);
            if (enabled) {
                context.fill(checkboxLeft + 2, checkboxTop + 2, checkboxLeft + CHECKBOX_SIZE - 2, checkboxTop + CHECKBOX_SIZE - 2, BRONZE);
                drawTick(context, checkboxLeft, checkboxTop);
            }
            if (!active) {
                context.fill(left, top, right, bottom, 0x52000000);
            }
            if (isFocused()) {
                drawFocusOutline(context, left, top, right, bottom);
            }
        }

        private static Text narration(Text label, boolean value) {
            return Text.empty()
                    .append(label)
                    .append(": ")
                    .append(Text.translatable(value ? "options.on" : "options.off"));
        }

        private static void drawTick(DrawContext context, int left, int top) {
            int ink = 0xFF1A1209;
            context.fill(left + 3, top + 6, left + 5, top + 8, ink);
            context.fill(left + 4, top + 7, left + 6, top + 9, ink);
            context.fill(left + 5, top + 8, left + 7, top + 10, ink);
            context.fill(left + 6, top + 6, left + 8, top + 9, ink);
            context.fill(left + 7, top + 4, left + 9, top + 8, ink);
            context.fill(left + 8, top + 3, left + 10, top + 6, ink);
        }
    }

    abstract static class Slider extends SliderWidget {
        private boolean overflowTooltip;

        Slider(int x, int y, int width, int height, Text message, double value) {
            super(x, y, width, height, message, value);
        }

        @Override
        public final void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
            int left = getX();
            int top = getY();
            int right = left + width;
            int bottom = top + height;
            int knobCenter = left + 8 + (int) Math.round((width - 16) * value);
            int knobLeft = knobCenter - SLIDER_HANDLE_WIDTH / 2;
            int knobTop = top + (height - SLIDER_HANDLE_HEIGHT) / 2;

            drawNeriumSliderTrack(context, left, top, right, bottom, 48, 330);
            if (isMouseOver(mouseX, mouseY) || isFocused()) {
                context.fill(left + 2, top + 2, right - 2, bottom - 2, 0x12E5A01D);
            }
            int progressY = bottom - 5;
            context.fill(left + 5, progressY, right - 5, progressY + 1, 0xA018120B);
            context.fill(left + 5, progressY, MathHelper.clamp(knobCenter, left + 5, right - 5), progressY + 1, BRONZE);
            context.fill(knobLeft + 1, knobTop + 1, knobLeft + SLIDER_HANDLE_WIDTH + 1, knobTop + SLIDER_HANDLE_HEIGHT + 1, 0x88000000);
            context.drawTexture(
                    SLIDER_HANDLE_TEXTURE,
                    knobLeft,
                    knobTop,
                    SLIDER_HANDLE_WIDTH,
                    SLIDER_HANDLE_HEIGHT,
                    0.0F,
                    0.0F,
                    SLIDER_HANDLE_TEXTURE_WIDTH,
                    SLIDER_HANDLE_TEXTURE_HEIGHT,
                    SLIDER_HANDLE_TEXTURE_WIDTH,
                    SLIDER_HANDLE_TEXTURE_HEIGHT
            );
            if (!active) {
                context.fill(left, top, right, bottom, CONTROL_DISABLED_OVERLAY);
            }
            boolean truncated = drawReadableButtonText(
                    context,
                    getMessage(),
                    left,
                    top,
                    width,
                    height,
                    active ? BUTTON_TEXT_COLOR : DISABLED_BUTTON_TEXT_COLOR
            );
            if (truncated && getTooltip() == null) {
                setTooltip(Tooltip.of(getMessage()));
                overflowTooltip = true;
            } else if (!truncated && overflowTooltip) {
                setTooltip(null);
                overflowTooltip = false;
            }
            if (isFocused()) {
                drawFocusOutline(context, left, top, right, bottom);
            }
        }
    }

    static final class ScrollBar extends ClickableWidget {
        private final IntSupplier valueSupplier;
        private final IntSupplier maxSupplier;
        private final IntConsumer valueConsumer;
        private final int step;
        private final int page;

        ScrollBar(int x, int y, int height, IntSupplier valueSupplier, IntSupplier maxSupplier, IntConsumer valueConsumer, int step, int page) {
            super(x, y, SLIDER_HANDLE_WIDTH, height, Text.translatable("text.erydon.scrollbar"));
            this.valueSupplier = valueSupplier;
            this.maxSupplier = maxSupplier;
            this.valueConsumer = valueConsumer;
            this.step = Math.max(1, step);
            this.page = Math.max(this.step, page);
            setTooltip(Tooltip.of(Text.translatable("text.erydon.scrollbar.description")));
        }

        @Override
        protected void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
            int max = max();
            active = max > 0;
            int handleHeight = handleHeight();
            int handleTop = getY() + (max > 0 ? (height - handleHeight) * value() / max : (height - handleHeight) / 2);
            int trackX = getX() + width / 2;
            boolean highlighted = isMouseOver(mouseX, mouseY) || isFocused();

            context.fill(trackX - 1, getY(), trackX + 1, getY() + height, max > 0 ? 0x66000000 : 0x33000000);
            if (highlighted && max > 0) {
                context.fill(trackX, getY(), trackX + 1, getY() + height, BRONZE);
            }
            context.drawTexture(
                    SLIDER_HANDLE_TEXTURE,
                    getX(),
                    handleTop,
                    width,
                    handleHeight,
                    0.0F,
                    0.0F,
                    SLIDER_HANDLE_TEXTURE_WIDTH,
                    SLIDER_HANDLE_TEXTURE_HEIGHT,
                    SLIDER_HANDLE_TEXTURE_WIDTH,
                    SLIDER_HANDLE_TEXTURE_HEIGHT
            );
            if (highlighted && active) {
                drawInsetOutline(context, getX() - 1, handleTop, getX() + width + 1, handleTop + handleHeight, FOCUS_COLOR);
            }
            if (!active) {
                context.fill(getX(), handleTop, getX() + width, handleTop + handleHeight, 0x66000000);
            }
            if (isFocused()) {
                drawFocusOutline(context, getX(), getY(), getX() + width, getY() + height);
            }
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            setFromMouse(mouseY);
        }

        @Override
        protected void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
            setFromMouse(mouseY);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
            if (!active || !isMouseOver(mouseX, mouseY)) {
                return false;
            }
            setValue(value() - (int) Math.round(amount * step));
            return true;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (!active || !isFocused()) {
                return false;
            }
            switch (keyCode) {
                case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_LEFT -> setValue(value() - step);
                case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_RIGHT -> setValue(value() + step);
                case GLFW.GLFW_KEY_PAGE_UP -> setValue(value() - page);
                case GLFW.GLFW_KEY_PAGE_DOWN -> setValue(value() + page);
                case GLFW.GLFW_KEY_HOME -> setValue(0);
                case GLFW.GLFW_KEY_END -> setValue(max());
                default -> {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            appendDefaultNarrations(builder);
            int percent = max() == 0 ? 0 : Math.round(value() * 100.0F / max());
            builder.put(NarrationPart.POSITION, Text.translatable("options.percent_value", percent));
        }

        private void setFromMouse(double mouseY) {
            int max = max();
            int handleHeight = handleHeight();
            int travel = Math.max(1, height - handleHeight);
            double relative = (mouseY - getY() - handleHeight / 2.0D) / travel;
            setValue((int) Math.round(MathHelper.clamp(relative, 0.0D, 1.0D) * max));
        }

        private int handleHeight() {
            int contentHeight = height + max();
            return Math.min(height, Math.max(SLIDER_HANDLE_HEIGHT, height * height / Math.max(1, contentHeight)));
        }

        private int value() {
            return MathHelper.clamp(valueSupplier.getAsInt(), 0, max());
        }

        private int max() {
            return Math.max(0, maxSupplier.getAsInt());
        }

        private void setValue(int value) {
            valueConsumer.accept(MathHelper.clamp(value, 0, max()));
        }
    }

    static final class TextLink extends ButtonWidget {
        TextLink(int x, int y, int width, int height, Text message, PressAction onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION_SUPPLIER);
        }

        @Override
        protected void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
            int color = isSelected() ? 0xFF173B63 : 0xFF244F78;
            int textWidth = Math.min(width - 4, Math.round(ErydonConfigUi.textWidth(cinzel(getMessage())) * BODY_TEXT_SCALE));
            int textX = getX() + 2;
            int textY = getY() + Math.max(1, (height - lineHeight(BODY_TEXT_SCALE)) / 2);
            if (isSelected()) {
                context.fill(getX(), getY(), getX() + width, getY() + height, 0x10000000);
            }
            drawReadableScaledText(context, getMessage(), textX, textY, BODY_TEXT_SCALE, color);
            int underlineY = textY + lineHeight(BODY_TEXT_SCALE) - 1;
            context.fill(textX, underlineY, textX + textWidth, underlineY + 1, color);
            if (isFocused()) {
                drawFocusOutline(context, getX(), getY(), getX() + width, getY() + height);
            }
        }
    }

    record FittedText(Text text, boolean truncated) {
    }

    private static void drawFineNeriumBevel(DrawContext context, int left, int top, int right, int bottom, boolean hovered) {
        int highlight = hovered ? 0x55C99B56 : 0x40B8894D;
        int innerHighlight = hovered ? 0x30E0BB7B : 0x24C09964;
        int shadow = hovered ? 0x8A150A02 : 0x74150A02;
        int innerShadow = hovered ? 0x55150A02 : 0x44150A02;

        context.getMatrices().push();
        context.getMatrices().scale(0.5F, 0.5F, 1.0F);

        int l = left * 2;
        int t = top * 2;
        int r = right * 2;
        int b = bottom * 2;

        context.fill(l + 2, t + 1, r - 2, t + 2, highlight);
        context.fill(l + 4, t + 3, r - 4, t + 4, innerHighlight);
        context.fill(l + 1, t + 2, l + 2, b - 2, highlight);
        context.fill(l + 3, t + 4, l + 4, b - 4, innerHighlight);

        context.fill(l + 2, b - 2, r - 2, b - 1, shadow);
        context.fill(l + 4, b - 4, r - 4, b - 3, innerShadow);
        context.fill(r - 2, t + 2, r - 1, b - 2, shadow);
        context.fill(r - 4, t + 4, r - 3, b - 4, innerShadow);

        context.fill(l, t, l + 3, t + 3, 0x5A000000);
        context.fill(r - 3, t, r, t + 3, 0x5A000000);
        context.fill(l, b - 3, l + 3, b, 0x5A000000);
        context.fill(r - 3, b - 3, r, b, 0x5A000000);

        context.getMatrices().pop();
    }
}
