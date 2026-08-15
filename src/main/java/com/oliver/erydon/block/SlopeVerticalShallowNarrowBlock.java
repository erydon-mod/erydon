package com.oliver.erydon.block;

import org.jetbrains.annotations.Nullable;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Waterloggable;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;


public class SlopeVerticalShallowNarrowBlock extends HorizontalFacingBlock implements Waterloggable {

    public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;
    public static final EnumProperty<Handedness> HAND = EnumProperty.of("hand", Handedness.class);
    public static final BooleanProperty WATERLOGGED = net.minecraft.state.property.Properties.WATERLOGGED;

    // Right-hand shapes for each facing
    private static final VoxelShape NORTH_RIGHT_SHAPE;
    private static final VoxelShape EAST_RIGHT_SHAPE;
    private static final VoxelShape SOUTH_RIGHT_SHAPE;
    private static final VoxelShape WEST_RIGHT_SHAPE;

    // Left-hand shapes for each facing
    private static final VoxelShape NORTH_LEFT_SHAPE;
    private static final VoxelShape EAST_LEFT_SHAPE;
    private static final VoxelShape SOUTH_LEFT_SHAPE;
    private static final VoxelShape WEST_LEFT_SHAPE;

    public SlopeVerticalShallowNarrowBlock(AbstractBlock.Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(HAND, Handedness.RIGHT)
                .with(WATERLOGGED, false));
    }

    /**
     * Base right-hand footprint for the narrow 22.5° segment, in a NORTH-facing frame.
     * Top-down (X-Z plane), full height in Y.
     *
     * We approximate the diagonal with evenly spaced slices along Z:
     *  - Z in [0,1], divided into 16 equal bands
     *  - X runs from 0 to xMax(Z), where xMax linearly decreases from 0.5 -> 0
     *    so the wide end is at Z=0 and the point is at Z=1.
     */
    private static VoxelShape createRightNarrowBaseShape() {
        VoxelShape shape = VoxelShapes.empty();
        int steps = 16;

        for (int i = 0; i < steps; i++) {
            double zMin = i / 16.0;
            double zMax = (i + 1) / 16.0;
            double zMid = (zMin + zMax) * 0.5;

            // Wide (0.5) at Z = 0, shrinking to ~0 at Z = 1
            double xMax = 0.5 * (1.0 - zMid);
            if (xMax <= 0.0) continue;

            shape = VoxelShapes.union(shape,
                    VoxelShapes.cuboid(0.0, 0.0, zMin, xMax, 1.0, zMax));
        }

        return shape;
    }

    /**
     * Mirrors a shape along the Z axis (0..1 -> 1..0), used to create left-hand variants.
     */
    private static VoxelShape mirrorAlongZ(VoxelShape original) {
        final VoxelShape[] result = {VoxelShapes.empty()};
        original.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
            result[0] = VoxelShapes.union(result[0],
                    VoxelShapes.cuboid(
                            minX,
                            minY,
                            1.0 - maxZ,
                            maxX,
                            maxY,
                            1.0 - minZ
                    ));
        });
        return result[0];
    }

    /**
     * Rotates a NORTH-oriented shape into another horizontal facing.
     */
    private static VoxelShape rotateShape(Direction from, Direction to, VoxelShape shape) {
        if (from == to) return shape;

        VoxelShape[] buffer = new VoxelShape[]{shape};
        int times = (to.getHorizontal() - from.getHorizontal() + 4) % 4;

        for (int i = 0; i < times; i++) {
            final VoxelShape[] rotatedHolder = {VoxelShapes.empty()};
            buffer[0].forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
                // 90° rotation around Y: (x,z) -> (1 - z, x)
                rotatedHolder[0] = VoxelShapes.union(rotatedHolder[0],
                        VoxelShapes.cuboid(
                                1.0 - maxZ, minY, minX,
                                1.0 - minZ, maxY, maxX
                        ));
            });
            buffer[0] = rotatedHolder[0];
        }

        return buffer[0];
    }

    static {
        // Base right-hand narrow wedge, facing NORTH
        VoxelShape rightBase = createRightNarrowBaseShape();
        // Left-hand is just mirrored along Z
        VoxelShape leftBase = mirrorAlongZ(rightBase);

        // Right-hand rotated variants
        NORTH_RIGHT_SHAPE = rightBase;
        EAST_RIGHT_SHAPE  = rotateShape(Direction.NORTH, Direction.EAST, rightBase);
        SOUTH_RIGHT_SHAPE = rotateShape(Direction.NORTH, Direction.SOUTH, rightBase);
        WEST_RIGHT_SHAPE  = rotateShape(Direction.NORTH, Direction.WEST, rightBase);

        // Left-hand rotated variants
        NORTH_LEFT_SHAPE = leftBase;
        EAST_LEFT_SHAPE  = rotateShape(Direction.NORTH, Direction.EAST, leftBase);
        SOUTH_LEFT_SHAPE = rotateShape(Direction.NORTH, Direction.SOUTH, leftBase);
        WEST_LEFT_SHAPE  = rotateShape(Direction.NORTH, Direction.WEST, leftBase);
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, HAND, WATERLOGGED);
    }
    @Nullable
@Override
public BlockState getPlacementState(ItemPlacementContext ctx) {
    Direction side = ctx.getSide();
    Direction facing;
    Handedness hand;
    boolean waterlogged = ctx.getWorld().getFluidState(ctx.getBlockPos()).getFluid() == Fluids.WATER;

    // Special logic only when placing on a horizontal face (top/bottom)
    if (side == Direction.UP || side == Direction.DOWN) {
        Vec3d hit = ctx.getHitPos();

        // Fractional coordinates within the target block: 0..1
        // x: 0 = west, 1 = east
        // z: 0 = north, 1 = south
        double fracX = hit.x - Math.floor(hit.x);
        double fracZ = hit.z - Math.floor(hit.z);

        boolean east  = fracX >= 0.5D;
        boolean south = fracZ >= 0.5D;

        // Which corner (quadrant) did we click?
        // This defines the corner where the right-angled base corner must end up.
        Direction quadrantDir;
        if (!east && !south) {
            quadrantDir = Direction.NORTH; // NW corner
        } else if (east && !south) {
            quadrantDir = Direction.EAST;  // NE corner
        } else if (east && south) {
            quadrantDir = Direction.SOUTH; // SE corner
        } else {
            quadrantDir = Direction.WEST;  // SW corner
        }

        // Decide if the click was on the "left" or "right" side of the block
        // relative to the player's facing.
        Direction playerFacing = ctx.getHorizontalPlayerFacing();
        boolean leftClick;

        switch (playerFacing) {
            case NORTH -> {
                // Looking north: left side is west (low X)
                leftClick = fracX < 0.5D;
            }
            case SOUTH -> {
                // Looking south: left side is east (high X)
                leftClick = fracX >= 0.5D;
            }
            case EAST -> {
                // Looking east: left side is north (low Z)
                leftClick = fracZ < 0.5D;
            }
            case WEST -> {
                // Looking west: left side is south (high Z)
                leftClick = fracZ >= 0.5D;
            }
            default -> leftClick = false;
        }

        if (!leftClick) {
            // Right-hand: facing matches the quadrant we clicked.
            hand = Handedness.RIGHT;
            facing = quadrantDir;
        } else {
            // Left-hand: spin facing clockwise so left-hand reference corner
            // still lands in the clicked quadrant.
            hand = Handedness.LEFT;
            facing = rotateClockwise(quadrantDir);
        }

    } else {
        // Placing on a vertical face: face away from the player, default RIGHT.
        facing = ctx.getHorizontalPlayerFacing().getOpposite();
        hand = Handedness.RIGHT;
    }

    return this.getDefaultState()
            .with(FACING, facing)
            .with(HAND, hand)
            .with(WATERLOGGED, waterlogged);
}

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state,
                                                Direction direction,
                                                BlockState neighborState,
                                                WorldAccess world,
                                                BlockPos pos,
                                                BlockPos neighborPos) {
        if (state.get(WATERLOGGED)) {
            world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.get(WATERLOGGED) ? Fluids.WATER.getStill(false) : super.getFluidState(state);
    }

    @Override
    public boolean hasSidedTransparency(BlockState state) {
        return true;
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        Direction facing = state.get(FACING);
        Handedness mirroredHand = swapHandedness(state.get(HAND));
        Direction mirroredFacing = switch (mirror) {
            case LEFT_RIGHT -> facing.getAxis() == Direction.Axis.X ? facing.getOpposite() : facing;
            case FRONT_BACK -> facing.getAxis() == Direction.Axis.Z ? facing.getOpposite() : facing;
            case NONE -> facing;
        };

        return state.with(FACING, mirroredFacing).with(HAND, mirroredHand);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state,
                                      BlockView world,
                                      BlockPos pos,
                                      ShapeContext context) {
        Handedness hand = state.get(HAND);
        Direction facing = state.get(FACING);

        return switch (hand) {
            case LEFT -> switch (facing) {
                case EAST  -> EAST_LEFT_SHAPE;
                case SOUTH -> SOUTH_LEFT_SHAPE;
                case WEST  -> WEST_LEFT_SHAPE;
                default    -> NORTH_LEFT_SHAPE;
            };
            case RIGHT -> switch (facing) {
                case EAST  -> EAST_RIGHT_SHAPE;
                case SOUTH -> SOUTH_RIGHT_SHAPE;
                case WEST  -> WEST_RIGHT_SHAPE;
                default    -> NORTH_RIGHT_SHAPE;
            };
        };
    }

    

    private static Direction rotateClockwise(Direction dir) {
        return switch (dir) {
            case NORTH -> Direction.EAST;
            case EAST  -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST  -> Direction.NORTH;
            default    -> dir;
        };
    }

    private static Handedness swapHandedness(Handedness hand) {
        return hand == Handedness.LEFT ? Handedness.RIGHT : Handedness.LEFT;
    }

public enum Handedness implements StringIdentifiable {
        LEFT("left"),
        RIGHT("right");

        private final String name;

        Handedness(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return this.name;
        }

        @Override
        public String toString() {
            return this.name;
        }
    }
}
