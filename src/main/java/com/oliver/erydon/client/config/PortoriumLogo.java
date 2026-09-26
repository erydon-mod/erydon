package com.oliver.erydon.client.config;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

/** Pre-rendered branding; one cached texture and one draw, only while a menu is visible. */
final class PortoriumLogo {
    private static final Identifier TEXTURE = new Identifier("erydon", "textures/gui/portorium_logo.png");
    private static final int FRAME_WIDTH = 256;
    private static final int FRAME_HEIGHT = 106;
    private static final int COLUMNS = 8;
    private static final int FRAME_COUNT = 120;
    private static final int TEXTURE_HEIGHT = 1590;

    private PortoriumLogo() { }

    static void draw(DrawContext context, int centerX, int top, int availableWidth) {
        int height = Math.min(42, Math.max(1, availableWidth * FRAME_HEIGHT / FRAME_WIDTH));
        int width = Math.max(1, height * FRAME_WIDTH / FRAME_HEIGHT);
        int frame = (int) ((Util.getMeasuringTimeMs() / 50L) % FRAME_COUNT);
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1, 1, 1, 1);
        context.drawTexture(TEXTURE, centerX - width / 2, top, width, height,
                (float) ((frame % COLUMNS) * FRAME_WIDTH), (float) ((frame / COLUMNS) * FRAME_HEIGHT),
                FRAME_WIDTH, FRAME_HEIGHT, FRAME_WIDTH * COLUMNS, TEXTURE_HEIGHT);
    }
}
