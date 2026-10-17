package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.math.Direction;

/** Moves a reversed sill back to the window's 3/16-deep frame, rather than the opposite cell edge. */
final class WindowSillTransform implements RenderContext.QuadTransform {
    private static final float OFFSET = 13 / 16.0F;
    private final float x, z;
    private SpriteFinder spriteFinder;

    WindowSillTransform(Direction facing) {
        x = facing.getOffsetX() * OFFSET;
        z = facing.getOffsetZ() * OFFSET;
    }

    @Override public boolean transform(MutableQuadView quad) {
        for (int vertex = 0; vertex < 4; vertex++)
            quad.pos(vertex, quad.x(vertex) + x, quad.y(vertex), quad.z(vertex) + z);
        Direction face = quad.lightFace();
        if (face != null && face.getAxis() == Direction.Axis.Y) {
            if (spriteFinder == null) spriteFinder = SpriteFinder.get(MinecraftClient.getInstance()
                    .getBakedModelManager().getAtlas(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE));
            HorizontalUvLock.projectFlatHorizontal(quad, spriteFinder.find(quad), face);
        }
        return true;
    }

    static BakedQuad translate(BakedQuad quad, Direction facing) {
        int[] data = quad.getVertexData().clone();
        for (int vertex = 0; vertex < 4; vertex++) {
            int at = vertex * 8;
            data[at] = Float.floatToRawIntBits(Float.intBitsToFloat(data[at]) + facing.getOffsetX() * OFFSET);
            data[at + 2] = Float.floatToRawIntBits(Float.intBitsToFloat(data[at + 2]) + facing.getOffsetZ() * OFFSET);
        }
        return HorizontalUvLock.projectFlatHorizontal(new BakedQuad(data, quad.getColorIndex(),
                quad.getFace(), quad.getSprite(), quad.hasShade()));
    }
}
