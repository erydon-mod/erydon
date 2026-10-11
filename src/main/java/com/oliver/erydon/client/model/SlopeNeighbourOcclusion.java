package com.oliver.erydon.client.model;

import com.oliver.erydon.block.ShallowSlopeBlock;
import com.oliver.erydon.block.SlopeVerticalBlock;
import com.oliver.erydon.block.SlopeVerticalShallowBroadBlock;
import com.oliver.erydon.block.SlopeVerticalShallowNarrowBlock;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockRenderView;

/** Rejects enclosed boundaries, including proven identical native extrusion seams. */
final class SlopeNeighbourOcclusion {
    private static final float EPSILON = 0.000001F;
    // Matches SlopeQuadUtil's deliberate displacement of partial horizontal boundaries.
    private static final float HORIZONTAL_OUTSET = 0.0005F;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final RenderContext.QuadTransform[] TRANSFORMS = createTransforms();

    private SlopeNeighbourOcclusion() {
    }

    static boolean push(BlockRenderView view, BlockState state, BlockPos pos, RenderContext context) {
        int mask = occludedNeighbourMask(view, state, pos);
        if (mask == 0) return false;
        // FRAPI applies the last pushed transform first: reject before Synapheia
        // captures the surface or generates its repeat/inlay overlays.
        context.pushTransform(transform(mask));
        return true;
    }

    static int occludedNeighbourMask(BlockRenderView view, BlockState state, BlockPos pos) {
        int mask = 0;
        int matchingFaces = matchingExtrusionFaces(state);
        boolean solidChecked = false;
        boolean solid = false;
        BlockPos.Mutable neighbourPos = pos.mutableCopy();
        for (Direction face : DIRECTIONS) {
            neighbourPos.set(pos).move(face);
            BlockState neighbour = view.getBlockState(neighbourPos);
            int bit = 1 << face.ordinal();
            if (neighbour.isOpaqueFullCube(view, neighbourPos)) {
                mask |= bit;
            } else if (neighbour == state && (matchingFaces & bit) != 0) {
                if (!solidChecked) {
                    solid = RenderLayers.getBlockLayer(state) == RenderLayer.getSolid();
                    solidChecked = true;
                }
                if (solid) mask |= bit;
            }
        }
        return mask;
    }

    private static int matchingExtrusionFaces(BlockState state) {
        Class<?> block = state.getBlock().getClass();
        Direction facing;
        if (block == ShallowSlopeBlock.class
                && ((ShallowSlopeBlock) state.getBlock()).variant() == ShallowSlopeBlock.Variant.UPPER
                && state.get(ShallowSlopeBlock.SHAPE) == ShallowSlopeBlock.SlopeShape.STRAIGHT) {
            facing = state.get(ShallowSlopeBlock.FACING);
        } else if (block == SlopeVerticalBlock.class || block == SlopeVerticalShallowBroadBlock.class
                || block == SlopeVerticalShallowNarrowBlock.class) {
            // Every native vertical prism is a constant full-height extrusion.
            return (1 << Direction.UP.ordinal()) | (1 << Direction.DOWN.ordinal());
        } else {
            // Standard, shallow-lower and steep side ends include a .001 fin
            // above the inclined roof. An equal opposing face is not sufficient
            // to prove that fin is enclosed by the neighbouring slope volume.
            return 0;
        }
        // Straight horizontal slopes are constant across their lateral axis.
        // Identical states have exactly matching opposite boundary domains;
        // neither the stepped collision shape nor a partial-neighbour mask is used.
        Direction lateral = facing.getAxis() == Direction.Axis.X ? Direction.NORTH : Direction.WEST;
        return (1 << lateral.ordinal()) | (1 << lateral.getOpposite().ordinal());
    }

    static RenderContext.QuadTransform transform(int mask) {
        return TRANSFORMS[mask];
    }

    private static RenderContext.QuadTransform[] createTransforms() {
        RenderContext.QuadTransform[] transforms = new RenderContext.QuadTransform[64];
        for (int mask = 0; mask < transforms.length; mask++) {
            int opaqueFaces = mask;
            transforms[mask] = quad -> {
                Direction face = quad.nominalFace();
                return face == null || (opaqueFaces & (1 << face.ordinal())) == 0
                        || !enclosedBoundary(quad, face);
            };
        }
        return transforms;
    }

    private static boolean enclosedBoundary(QuadView quad, Direction face) {
        Direction.Axis axis = face.getAxis();
        float boundary = face.getDirection() == Direction.AxisDirection.POSITIVE ? 1.0F : 0.0F;
        float plane = coordinate(quad, 0, axis);
        if (!near(plane, boundary)
                && !(axis == Direction.Axis.Y
                && near(plane, boundary + face.getOffsetY() * HORIZONTAL_OUTSET))) {
            return false;
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            if (!near(coordinate(quad, vertex, axis), plane)
                    || axis != Direction.Axis.X && !withinUnit(quad.x(vertex))
                    || axis != Direction.Axis.Y && !withinUnit(quad.y(vertex))
                    || axis != Direction.Axis.Z && !withinUnit(quad.z(vertex))) {
                return false;
            }
        }
        // Do not discard inward-facing surfaces or malformed/self-crossing quads.
        // Both triangles may include a duplicate vertex, as in native slope caps.
        float first = outwardArea(quad, 0, 1, 2, face);
        float second = outwardArea(quad, 0, 2, 3, face);
        return first >= 0.0F && second >= 0.0F && first + second > 0.0F;
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

    private static float coordinate(QuadView quad, int vertex, Direction.Axis axis) {
        return switch (axis) {
            case X -> quad.x(vertex);
            case Y -> quad.y(vertex);
            case Z -> quad.z(vertex);
        };
    }

    private static boolean withinUnit(float value) {
        return value >= -EPSILON && value <= 1.0F + EPSILON;
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) <= EPSILON;
    }
}
