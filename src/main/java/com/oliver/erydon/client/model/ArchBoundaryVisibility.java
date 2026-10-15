package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockRenderView;

/** Private visibility proof; never changes the renderer's cull or lighting metadata. */
final class ArchBoundaryVisibility {
    private static final Direction[] FACES = Direction.values();

    private ArchBoundaryVisibility() {}

    static Direction eligibleFace(QuadView quad, Direction authoredFace) {
        if (authoredFace == null) return null;
        Direction.Axis axis = authoredFace.getAxis();
        float boundary = authoredFace.getDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
        for (int vertex = 0; vertex < 4; vertex++) {
            float x = quad.x(vertex), y = quad.y(vertex), z = quad.z(vertex);
            if (!withinUnit(x) || !withinUnit(y) || !withinUnit(z)) return null;
            float coordinate = axis == Direction.Axis.X ? x : axis == Direction.Axis.Y ? y : z;
            if (coordinate != boundary) return null;
        }
        float first = outwardArea(quad, 0, 1, 2, authoredFace);
        float second = outwardArea(quad, 0, 2, 3, authoredFace);
        return first >= 0 && second >= 0 && first + second > 0 ? authoredFace : null;
    }

    static int opaqueNeighbourMask(BlockRenderView view, BlockPos pos, int eligibleFaces) {
        if (eligibleFaces == 0) return 0;
        int result = 0;
        BlockPos.Mutable neighbourPos = pos.mutableCopy();
        for (Direction face : FACES) {
            int bit = 1 << face.ordinal();
            if ((eligibleFaces & bit) == 0) continue;
            neighbourPos.set(pos).move(face);
            BlockState neighbour = view.getBlockState(neighbourPos);
            if (neighbour.isOpaqueFullCube(view, neighbourPos)) result |= bit;
        }
        return result;
    }

    static Direction finalFace(Direction authoredFace, SpiralStairCtmGeometry.CellVertex[] vertices) {
        if (authoredFace == null) return null;
        Direction.Axis axis = authoredFace.getAxis();
        float boundary = authoredFace.getDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
        for (var cell : vertices) {
            var vertex = cell.vertex();
            if (!withinUnit(vertex.x()) || !withinUnit(vertex.y()) || !withinUnit(vertex.z())) return null;
            float coordinate = axis == Direction.Axis.X ? vertex.x() : axis == Direction.Axis.Y ? vertex.y() : vertex.z();
            if (coordinate != boundary) return null;
        }
        return authoredFace;
    }

    private static boolean withinUnit(float coordinate) {
        return coordinate >= 0 && coordinate <= 1;
    }

    private static float outwardArea(QuadView quad, int a, int b, int c, Direction face) {
        float abX = quad.x(b) - quad.x(a), abY = quad.y(b) - quad.y(a), abZ = quad.z(b) - quad.z(a);
        float acX = quad.x(c) - quad.x(a), acY = quad.y(c) - quad.y(a), acZ = quad.z(c) - quad.z(a);
        return switch (face.getAxis()) {
            case X -> (abY * acZ - abZ * acY) * face.getOffsetX();
            case Y -> (abZ * acX - abX * acZ) * face.getOffsetY();
            case Z -> (abX * acY - abY * acX) * face.getOffsetZ();
        };
    }
}
