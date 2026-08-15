package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

/** Keeps a horizontal face's texture aligned to world X/Z after a Y rotation. */
public final class HorizontalUvLock {
    private static final float SPRITE_UNITS = 16.0F;
    private static final float POSITION_EPSILON = 0.00001F;
    private static final float UV_BOUNDARY_EPSILON = 0.001F;

    private HorizontalUvLock() {
    }

    static void apply(MutableQuadView quad, Sprite sprite, Direction sourceFace, int turns) {
        int normalizedTurns = Math.floorMod(turns, 4);
        if (!isHorizontal(sourceFace) || normalizedTurns == 0 || !shouldLockSprite(sprite)) {
            return;
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            float u = sprite.getFrameFromU(quad.u(vertex));
            float v = sprite.getFrameFromV(quad.v(vertex));
            quad.uv(vertex,
                    sprite.getFrameU(lockedUNormalized(sourceFace, normalizedTurns, u, v)),
                    sprite.getFrameV(lockedVNormalized(sourceFace, normalizedTurns, u, v)));
        }
    }

    static void apply(int[] vertexData, Sprite sprite, Direction sourceFace, int turns) {
        int normalizedTurns = Math.floorMod(turns, 4);
        if (!isHorizontal(sourceFace) || normalizedTurns == 0 || !shouldLockSprite(sprite)) {
            return;
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * 8;
            float u = sprite.getFrameFromU(Float.intBitsToFloat(vertexData[offset + 4]));
            float v = sprite.getFrameFromV(Float.intBitsToFloat(vertexData[offset + 5]));
            vertexData[offset + 4] = Float.floatToRawIntBits(
                    sprite.getFrameU(lockedUNormalized(sourceFace, normalizedTurns, u, v)));
            vertexData[offset + 5] = Float.floatToRawIntBits(
                    sprite.getFrameV(lockedVNormalized(sourceFace, normalizedTurns, u, v)));
        }
    }

    /**
     * Projects a geometrically flat horizontal quad onto world X/Z before CTM
     * wraps it. This is the missing equivalent of uvlock for element-level Y
     * rotations, including the 22.5 and 45 degree details used by Georgian walls.
     */
    public static BakedQuad projectFlatHorizontal(BakedQuad quad) {
        Direction face = quad.getFace();
        if (!isHorizontal(face) || !shouldLockSprite(quad.getSprite())) {
            return quad;
        }

        int[] source = quad.getVertexData();
        if (source.length % 4 != 0) {
            return quad;
        }
        int stride = source.length / 4;
        if (stride < 6 || !isFlat(source, stride)) {
            return quad;
        }

        float[] projectedU = new float[4];
        float[] projectedV = new float[4];
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * stride;
            float x = Float.intBitsToFloat(source[offset]);
            float z = Float.intBitsToFloat(source[offset + 2]);
            projectedU[vertex] = projectedU(face, x, z);
            projectedV[vertex] = projectedV(face, x, z);
        }

        if (!fitIntoSprite(projectedU) || !fitIntoSprite(projectedV)) {
            return quad;
        }
        shrinkTowardCentre(projectedU, quad.getSprite().getAnimationFrameDelta());
        shrinkTowardCentre(projectedV, quad.getSprite().getAnimationFrameDelta());

        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * stride;
            int u = Float.floatToRawIntBits(quad.getSprite().getFrameU(projectedU[vertex]));
            int v = Float.floatToRawIntBits(quad.getSprite().getFrameV(projectedV[vertex]));
            source[offset + 4] = u;
            source[offset + 5] = v;
        }
        return quad;
    }

    public static boolean shouldProjectAtBake(Identifier modelId) {
        if (modelId == null || !"erydon".equals(modelId.getNamespace())) {
            return false;
        }
        String path = modelId.getPath();
        if (path.endsWith("_icon") || path.endsWith("_item") || path.endsWith("_item_icon")) {
            return false;
        }
        if (path.startsWith("block/surround/")
                || path.startsWith("block/cornice/")
                || path.startsWith("block/arch/modern/")
                || path.startsWith("block/arch/romanesque/")
                || path.startsWith("block/arch/gothic/")
                || path.startsWith("block/column/circular/")
                || path.startsWith("block/column/square/")
                || path.startsWith("block/window/arch/")
                || path.startsWith("block/window/french_georgian/")) {
            return true;
        }
        return path.startsWith("block/wall/georgian/")
                && (path.endsWith("_post")
                || path.endsWith("_side")
                || path.endsWith("_pier")
                || path.endsWith("_pier_stub"));
    }

    /** Leaves cropped/directional non-material sprites such as glazing and lead untouched. */
    static boolean shouldLockTexture(Identifier textureId) {
        if (textureId == null || !"erydon".equals(textureId.getNamespace())) {
            return false;
        }
        String path = textureId.getPath();
        return path.startsWith("block/") && (path.endsWith("_block") || path.endsWith("_block_aged"));
    }

    private static boolean shouldLockSprite(Sprite sprite) {
        return sprite != null && shouldLockTexture(sprite.getContents().getId());
    }

    static float projectedU(Direction face, float x, float z) {
        return isHorizontal(face) ? x * SPRITE_UNITS : 0.0F;
    }

    static float projectedV(Direction face, float x, float z) {
        if (face == Direction.UP) {
            return z * SPRITE_UNITS;
        }
        if (face == Direction.DOWN) {
            return (1.0F - z) * SPRITE_UNITS;
        }
        return 0.0F;
    }

    static boolean fitIntoSprite(float[] values) {
        for (int i = 0; i < values.length; i++) {
            float value = values[i];
            if (!Float.isFinite(value)) {
                return false;
            }
            if (value < -UV_BOUNDARY_EPSILON || value > SPRITE_UNITS + UV_BOUNDARY_EPSILON) {
                return false;
            }
            values[i] = Math.max(0.0F, Math.min(SPRITE_UNITS, value));
        }
        return true;
    }

    private static boolean isFlat(int[] vertexData, int stride) {
        float y = Float.intBitsToFloat(vertexData[1]);
        for (int vertex = 1; vertex < 4; vertex++) {
            if (Math.abs(Float.intBitsToFloat(vertexData[vertex * stride + 1]) - y) > POSITION_EPSILON) {
                return false;
            }
        }
        return true;
    }

    private static void shrinkTowardCentre(float[] values, float delta) {
        float centre = 0.0F;
        for (float value : values) {
            centre += value;
        }
        centre /= values.length;
        for (int i = 0; i < values.length; i++) {
            values[i] += delta * (centre - values[i]);
        }
    }

    static float lockedU(Direction sourceFace, int turns, float u, float v) {
        return lockedUNormalized(sourceFace, Math.floorMod(turns, 4), u, v);
    }

    private static float lockedUNormalized(Direction sourceFace, int normalizedTurns, float u, float v) {
        if (sourceFace == Direction.UP) {
            return switch (normalizedTurns) {
                case 1 -> SPRITE_UNITS - v;
                case 2 -> SPRITE_UNITS - u;
                case 3 -> v;
                default -> u;
            };
        }
        if (sourceFace == Direction.DOWN) {
            return switch (normalizedTurns) {
                case 1 -> v;
                case 2 -> SPRITE_UNITS - u;
                case 3 -> SPRITE_UNITS - v;
                default -> u;
            };
        }
        return u;
    }

    static float lockedV(Direction sourceFace, int turns, float u, float v) {
        return lockedVNormalized(sourceFace, Math.floorMod(turns, 4), u, v);
    }

    private static float lockedVNormalized(Direction sourceFace, int normalizedTurns, float u, float v) {
        if (sourceFace == Direction.UP) {
            return switch (normalizedTurns) {
                case 1 -> u;
                case 2 -> SPRITE_UNITS - v;
                case 3 -> SPRITE_UNITS - u;
                default -> v;
            };
        }
        if (sourceFace == Direction.DOWN) {
            return switch (normalizedTurns) {
                case 1 -> SPRITE_UNITS - u;
                case 2 -> SPRITE_UNITS - v;
                case 3 -> u;
                default -> v;
            };
        }
        return v;
    }

    private static boolean isHorizontal(Direction face) {
        return face != null && face.getAxis() == Direction.Axis.Y;
    }
}
