package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.render.model.ModelRotation;
import net.minecraft.util.math.Direction;
import org.joml.Matrix4f;

/** Immutable scalar form of the eight rotations supported by ERYDON slopes. */
final class FixedSlopeRotation implements RenderContext.QuadTransform {
    private static final FixedSlopeRotation[] VALUES = createValues();

    private final ModelRotation rotation;
    private final float m00;
    private final float m01;
    private final float m02;
    private final float m10;
    private final float m11;
    private final float m12;
    private final float m20;
    private final float m21;
    private final float m22;
    private final float m30;
    private final float m31;
    private final float m32;

    private FixedSlopeRotation(int xDegrees, int yDegrees) {
        rotation = ModelRotation.get(xDegrees, yDegrees);
        Matrix4f matrix = rotation.getRotation().getMatrix();
        m00 = matrix.m00();
        m01 = matrix.m01();
        m02 = matrix.m02();
        m10 = matrix.m10();
        m11 = matrix.m11();
        m12 = matrix.m12();
        m20 = matrix.m20();
        m21 = matrix.m21();
        m22 = matrix.m22();
        m30 = matrix.m30();
        m31 = matrix.m31();
        m32 = matrix.m32();
    }

    static FixedSlopeRotation of(int xDegrees, int yDegrees) {
        int x = Math.floorMod(xDegrees, 360);
        int y = Math.floorMod(yDegrees, 360);
        if ((x != 0 && x != 180) || y % 90 != 0) {
            throw new IllegalArgumentException("Unsupported slope rotation x=" + xDegrees + ", y=" + yDegrees);
        }
        return VALUES[(x == 180 ? 4 : 0) + y / 90];
    }

    Direction mapFace(Direction face) {
        return face == null ? null : rotation.getDirectionTransformation().map(face);
    }

    float positionX(float x, float y, float z) {
        float centeredX = x - 0.5F;
        float centeredY = y - 0.5F;
        float centeredZ = z - 0.5F;
        return Math.fma(m00, centeredX, Math.fma(m10, centeredY, Math.fma(m20, centeredZ, m30))) + 0.5F;
    }

    float positionY(float x, float y, float z) {
        float centeredX = x - 0.5F;
        float centeredY = y - 0.5F;
        float centeredZ = z - 0.5F;
        return Math.fma(m01, centeredX, Math.fma(m11, centeredY, Math.fma(m21, centeredZ, m31))) + 0.5F;
    }

    float positionZ(float x, float y, float z) {
        float centeredX = x - 0.5F;
        float centeredY = y - 0.5F;
        float centeredZ = z - 0.5F;
        return Math.fma(m02, centeredX, Math.fma(m12, centeredY, Math.fma(m22, centeredZ, m32))) + 0.5F;
    }

    float directionX(float x, float y, float z) {
        return Math.fma(m00, x, Math.fma(m10, y, m20 * z));
    }

    float directionY(float x, float y, float z) {
        return Math.fma(m01, x, Math.fma(m11, y, m21 * z));
    }

    float directionZ(float x, float y, float z) {
        return Math.fma(m02, x, Math.fma(m12, y, m22 * z));
    }

    void writePosition(MutableQuadView quad, int vertex, float x, float y, float z) {
        quad.pos(vertex, positionX(x, y, z), positionY(x, y, z), positionZ(x, y, z));
    }

    void writeNormal(MutableQuadView quad, int vertex, float x, float y, float z) {
        quad.normal(vertex, directionX(x, y, z), directionY(x, y, z), directionZ(x, y, z));
    }

    void packPosition(int[] data, int vertex, float x, float y, float z) {
        int offset = vertex * 8;
        data[offset] = Float.floatToRawIntBits(positionX(x, y, z));
        data[offset + 1] = Float.floatToRawIntBits(positionY(x, y, z));
        data[offset + 2] = Float.floatToRawIntBits(positionZ(x, y, z));
    }

    @Override
    public boolean transform(MutableQuadView quad) {
        for (int vertex = 0; vertex < 4; vertex++) {
            float x = quad.x(vertex);
            float y = quad.y(vertex);
            float z = quad.z(vertex);
            writePosition(quad, vertex, x, y, z);

            if (quad.hasNormal(vertex)) {
                float normalX = quad.normalX(vertex);
                float normalY = quad.normalY(vertex);
                float normalZ = quad.normalZ(vertex);
                writeNormal(quad, vertex, normalX, normalY, normalZ);
            }
        }

        quad.cullFace(mapFace(quad.cullFace()));
        quad.nominalFace(mapFace(quad.nominalFace()));
        return true;
    }

    private static FixedSlopeRotation[] createValues() {
        FixedSlopeRotation[] values = new FixedSlopeRotation[8];
        for (int xIndex = 0; xIndex < 2; xIndex++) {
            for (int yIndex = 0; yIndex < 4; yIndex++) {
                values[xIndex * 4 + yIndex] = new FixedSlopeRotation(xIndex * 180, yIndex * 90);
            }
        }
        return values;
    }
}
