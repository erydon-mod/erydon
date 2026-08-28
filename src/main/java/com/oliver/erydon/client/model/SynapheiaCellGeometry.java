package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.minecraft.util.math.Direction;

/** Fast test and UV projection for a quad contained in one world texture cell. */
final class SynapheiaCellGeometry {
    private SynapheiaCellGeometry() {
    }

    static Cell singleCell(Direction face, QuadView quad) {
        if (face == null) {
            return null;
        }
        float minS = Float.POSITIVE_INFINITY;
        float maxS = Float.NEGATIVE_INFINITY;
        float minT = Float.POSITIVE_INFINITY;
        float maxT = Float.NEGATIVE_INFINITY;
        for (int vertex = 0; vertex < 4; vertex++) {
            float s = SpiralStairCtmGeometry.snapToCell(SpiralStairCtmGeometry.textureS(
                    face, quad.x(vertex), quad.y(vertex), quad.z(vertex)));
            float t = SpiralStairCtmGeometry.snapToCell(SpiralStairCtmGeometry.textureT(
                    face, quad.x(vertex), quad.y(vertex), quad.z(vertex)));
            minS = Math.min(minS, s);
            maxS = Math.max(maxS, s);
            minT = Math.min(minT, t);
            maxT = Math.max(maxT, t);
        }

        int minCellS = SpiralStairCtmGeometry.floorCell(minS);
        int maxCellS = SpiralStairCtmGeometry.floorCell(
                maxS - SpiralStairCtmGeometry.RANGE_EPSILON);
        int minCellT = SpiralStairCtmGeometry.floorCell(minT);
        int maxCellT = SpiralStairCtmGeometry.floorCell(
                maxT - SpiralStairCtmGeometry.RANGE_EPSILON);
        if (maxCellS < minCellS) {
            maxCellS = minCellS;
        }
        if (maxCellT < minCellT) {
            maxCellT = minCellT;
        }
        return minCellS == maxCellS && minCellT == maxCellT
                ? new Cell(minCellS, minCellT) : null;
    }

    static float u(Direction face, QuadView quad, int vertex, Cell cell) {
        float localS = SpiralStairCtmGeometry.clamp01(
                SpiralStairCtmGeometry.snapToCell(SpiralStairCtmGeometry.textureS(
                        face, quad.x(vertex), quad.y(vertex), quad.z(vertex))) - cell.cellS);
        return SpiralStairCtmGeometry.u(face, localS);
    }

    static float v(Direction face, QuadView quad, int vertex, Cell cell) {
        float localT = SpiralStairCtmGeometry.clamp01(
                SpiralStairCtmGeometry.snapToCell(SpiralStairCtmGeometry.textureT(
                        face, quad.x(vertex), quad.y(vertex), quad.z(vertex))) - cell.cellT);
        return SpiralStairCtmGeometry.v(face, localT);
    }

    record Cell(int cellS, int cellT) {
        int offsetX(Direction face) {
            return SpiralStairCtmGeometry.offsetX(face, cellS, cellT);
        }

        int offsetY(Direction face) {
            return SpiralStairCtmGeometry.offsetY(face, cellS, cellT);
        }

        int offsetZ(Direction face) {
            return SpiralStairCtmGeometry.offsetZ(face, cellS, cellT);
        }
    }
}
