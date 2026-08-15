package com.oliver.erydon.client.model;

import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.ModelRotation;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

final class AxiomFallbackQuads {
    private AxiomFallbackQuads() {
    }

    static List<BakedQuad> collect(BakedModel model, Direction requestedFace, Random random) {
        return collect(model, 0, requestedFace, random);
    }

    static List<BakedQuad> collect(BakedModel model, int degrees, Direction requestedFace, Random random) {
        return collect(model, 0, degrees, requestedFace, random);
    }

    static List<BakedQuad> collect(BakedModel model, int xDegrees, int yDegrees, Direction requestedFace, Random random) {
        if (model == null) {
            return List.of();
        }

        List<BakedQuad> quads = new ArrayList<>();
        add(quads, model, xDegrees, yDegrees, requestedFace, random);
        return quads;
    }

    static void add(List<BakedQuad> quads, BakedModel model, int degrees, Direction requestedFace, Random random) {
        add(quads, model, 0, degrees, requestedFace, random, false);
    }

    static void add(List<BakedQuad> quads,
                    BakedModel model,
                    int degrees,
                    Direction requestedFace,
                    Random random,
                    boolean lockHorizontalUv) {
        add(quads, model, 0, degrees, requestedFace, random, lockHorizontalUv);
    }

    static void add(List<BakedQuad> quads, BakedModel model, int xDegrees, int yDegrees, Direction requestedFace, Random random) {
        add(quads, model, xDegrees, yDegrees, requestedFace, random, false);
    }

    private static void add(List<BakedQuad> quads,
                            BakedModel model,
                            int xDegrees,
                            int yDegrees,
                            Direction requestedFace,
                            Random random,
                            boolean lockHorizontalUv) {
        if (model == null) {
            return;
        }

        ModelRotation rotation = ModelRotation.get(xDegrees, yDegrees);
        int yTurns = Math.floorMod(yDegrees / 90, 4);
        if (requestedFace == null) {
            addSourceQuads(quads, model, null, rotation, yTurns, lockHorizontalUv, random);
            for (Direction face : Direction.values()) {
                addSourceQuads(quads, model, face, rotation, yTurns, lockHorizontalUv, random);
            }
            return;
        }

        addSourceQuads(quads, model, inverseMapFace(requestedFace, rotation), rotation,
                yTurns, lockHorizontalUv, random);
    }

    static List<BakedQuad> filterByFace(List<BakedQuad> quads, Direction requestedFace) {
        if (requestedFace == null) {
            return quads;
        }

        List<BakedQuad> filtered = new ArrayList<>();
        for (BakedQuad quad : quads) {
            if (quad.getFace() == requestedFace) {
                filtered.add(quad);
            }
        }
        return filtered;
    }

    private static void addSourceQuads(List<BakedQuad> quads,
                                       BakedModel model,
                                       Direction sourceFace,
                                       ModelRotation rotation,
                                       int yTurns,
                                       boolean lockHorizontalUv,
                                       Random random) {
        for (BakedQuad quad : model.getQuads(null, sourceFace, random)) {
            quads.add(rotation == ModelRotation.X0_Y0
                    ? quad
                    : rotate(quad, rotation, yTurns, lockHorizontalUv));
        }
    }

    private static BakedQuad rotate(BakedQuad quad,
                                    ModelRotation rotation,
                                    int yTurns,
                                    boolean lockHorizontalUv) {
        int[] data = quad.getVertexData().clone();
        Matrix4f matrix = rotation.getRotation().getMatrix();
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * 8;
            Vector3f position = new Vector3f(
                    Float.intBitsToFloat(data[offset]) - 0.5F,
                    Float.intBitsToFloat(data[offset + 1]) - 0.5F,
                    Float.intBitsToFloat(data[offset + 2]) - 0.5F);
            matrix.transformPosition(position);
            data[offset] = Float.floatToRawIntBits(position.x + 0.5F);
            data[offset + 1] = Float.floatToRawIntBits(position.y + 0.5F);
            data[offset + 2] = Float.floatToRawIntBits(position.z + 0.5F);
        }

        if (lockHorizontalUv && quad.getFace() != null && quad.getFace().getAxis() == Direction.Axis.Y) {
            HorizontalUvLock.apply(data, quad.getSprite(), quad.getFace(), yTurns);
        }

        Direction face = quad.getFace() == null ? null : rotation.getDirectionTransformation().map(quad.getFace());
        return new BakedQuad(data, quad.getColorIndex(), face, quad.getSprite(), quad.hasShade());
    }

    private static Direction inverseMapFace(Direction face, ModelRotation rotation) {
        if (face == null) {
            return face;
        }

        for (Direction candidate : Direction.values()) {
            if (rotation.getDirectionTransformation().map(candidate) == face) {
                return candidate;
            }
        }
        return face;
    }
}
