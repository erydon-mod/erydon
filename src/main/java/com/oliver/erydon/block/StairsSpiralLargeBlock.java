package com.oliver.erydon.block;

import com.oliver.erydon.util.ClusterRecalcSafety;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A complete large spiral-stair section owned by one pivot block.
 *
 * <p>The legacy A/B/C/D and cap properties remain in the state schema so published worlds
 * continue to load. New placements always use B, the original vertical pivot, while the
 * single world model extends across the former four-block footprint.</p>
 */
public class StairsSpiralLargeBlock extends HorizontalFacingBlock implements ClusterRebuildableBlock {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;

    public enum Part implements StringIdentifiable {
        A, B, C, D;

        @Override
        public String asString() {
            return name().toLowerCase();
        }
    }

    /** Retained only for blockstate compatibility with published worlds. */
    public static final EnumProperty<Part> PART = EnumProperty.of("part", Part.class);
    /** Retained only for blockstate compatibility with published worlds. */
    public static final BooleanProperty CAP = BooleanProperty.of("cap");

    private static final ThreadLocal<Boolean> REMOVING_LEGACY_LAYER =
            ThreadLocal.withInitial(() -> false);

    private static final VoxelShape COLLISION_A_NORTH = shape(
            0.201404375, 0, 0, 0.798595, 0.25, 1,
            0.0061875000000000124, 0.25, 0.5406249999999999, 0.3359375, 0.5, 0.6656249999999999,
            0.002437500000000037, 0.25, 0.4156249999999999, 0.3921875, 0.5, 0.5406249999999999,
            0.0004375000000000351, 0.25, 0.2906249999999999, 0.4421875, 0.5, 0.4156249999999999,
            0.004687500000000011, 0.25, 0.1656249999999999, 0.4921875, 0.5, 0.2906249999999999,
            0.005312499999999998, 0.25, 0.6656249999999999, 0.2890625, 0.5, 0.7906249999999999,
            0.0078125, 0.25, 0.7906249999999999, 0.2265625, 0.5, 0.9156249999999999,
            0, 0.25, 0.04062499999999991, 0.546875, 0.5, 0.1656249999999999,
            0, 0.25, 0.002375000000000016, 0.59375, 0.5, 0.04062499999999991,
            0, 0.5, 0.0036249999999999893, 0.375, 0.75, 0.04062499999999991,
            0, 0.5, 0.1656249999999999, 0.12262499999999998, 0.75, 0.2906249999999999,
            0, 0.5, 0.04062499999999991, 0.25, 0.75, 0.1656249999999999
    );
    private static final VoxelShape COLLISION_B_NORTH = shape(
            0.201404375, 0, 0.4375, 0.798595, 0.25, 1,
            0.25, 0, 0.25, 0.75, 1, 0.75,
            0.10625, 0.25, 0.665625, 0.7, 0.5, 0.790625,
            0.05625, 0.25, 0.790625, 0.65, 0.5, 0.915625,
            0, 0.25, 0.915625, 0.59375, 0.5, 0.999875,
            0.15625, 0.25, 0.540625, 0.5625, 0.5, 0.665625,
            0.1640625, 0.5, 0.415625, 0.4921875, 0.75, 0.540625,
            0, 0.5, 0.540625, 0.62, 0.75, 0.665625,
            0, 0.5, 0.665625, 0.620125, 0.75, 0.790625,
            0, 0.5, 0.790625, 0.493125, 0.75, 0.915625,
            0, 0.5, 0.915625, 0.369125, 0.75, 1.0006249999999999,
            0.3359375, 0.75, 0.25, 0.4609375, 1, 0.84375,
            0.2109375, 0.75, 0.29375, 0.3359375, 1, 0.8875,
            0.0859375, 0.75, 0.346875, 0.2109375, 1, 0.940625,
            0, 0.75, 0.4, 0.086, 1, 1
    );
    private static final VoxelShape COLLISION_C_NORTH = shape(
            0.6751249859333038, 0.25, 0.6656249999999999, 0.9999999859333039, 0.5, 0.7906249999999999,
            0.7421249897480011, 0.25, 0.5406249999999999, 0.9999999897480011, 0.5, 0.6656249999999999,
            0.796875, 0.25, 0.4156249999999999, 1, 0.5, 0.5406249999999999,
            0.5546875, 0.5, 0.5406249999999999, 0.7421875, 0.75, 0.6656249999999999,
            0.4234375, 0.5, 0.4156249999999999, 0.8765625, 0.75, 0.5406249999999999,
            0.296875, 0.5, 0.2906249999999999, 1, 0.75, 0.4156249999999999,
            0.18037499189376827, 0.5, 0.1656249999999999, 0.9999999918937683, 0.75, 0.2906249999999999,
            0.546875, 0.5, 0.04062499999999991, 1, 0.75, 0.1656249999999999,
            0.3359375, 0.75, 0, 0.4609375, 1, 0.2517499999999998,
            0.7109375, 0.75, 0, 0.8359375, 1, 0.09175,
            0.5859375, 0.75, 0, 0.7109375, 1, 0.141625,
            0.4609375, 0.75, 0, 0.5859375, 1, 0.19425000000000003,
            0.8359375, 0.75, 0, 0.9609375, 1, 0.04174999999999984,
            0.2109375, 0.75, 0, 0.3359375, 1, 0.30325,
            0.0859375, 0.75, 0, 0.2109375, 1, 0.35499999999999987
    );
    private static final VoxelShape COLLISION_D_NORTH = shape(
            0.9609375, 0.75, 0.4022499680519106, 1, 1, 0.9999999680519106,
            0.8359375, 0.75, 0.4492499589920046, 0.9609375, 1, 0.9999999589920044,
            0.7109375, 0.75, 0.49999997615814223, 0.8359375, 1, 0.9999999761581422,
            0.5859375, 0.75, 0.5482499957084657, 0.7109375, 1, 0.9999999957084656,
            0.4609375, 0.75, 0.6013749599456787, 0.5859375, 1, 0.9999999599456788,
            0.3359375, 0.75, 0.6582499504089357, 0.4609375, 1, 0.9999999504089356,
            0.2109375, 0.75, 0.7097500324249267, 0.3359375, 1, 1.0000000324249267,
            0.0859375, 0.75, 0.7625, 0.2109375, 1, 1
    );
    private static final VoxelShape OFFSTEP_D_NORTH = VoxelShapes.cuboid(
            0.0006249985694886107, 0.7516874974966049, -0.004249998629093199,
            0.7978749985694886, 0.999812497496605, 0.4407500013709068
    );

    private static final VoxelShape FULL_SECTION_NORTH = makeFullSectionShape();
    private static final VoxelShape FULL_SECTION_EAST = rotateShape(Direction.NORTH, Direction.EAST, FULL_SECTION_NORTH);
    private static final VoxelShape FULL_SECTION_SOUTH = rotateShape(Direction.NORTH, Direction.SOUTH, FULL_SECTION_NORTH);
    private static final VoxelShape FULL_SECTION_WEST = rotateShape(Direction.NORTH, Direction.WEST, FULL_SECTION_NORTH);

    private static final VoxelShape COLLISION_A_EAST = rotateShape(Direction.NORTH, Direction.EAST, COLLISION_A_NORTH);
    private static final VoxelShape COLLISION_A_SOUTH = rotateShape(Direction.NORTH, Direction.SOUTH, COLLISION_A_NORTH);
    private static final VoxelShape COLLISION_A_WEST = rotateShape(Direction.NORTH, Direction.WEST, COLLISION_A_NORTH);
    private static final VoxelShape COLLISION_C_EAST = rotateShape(Direction.NORTH, Direction.EAST, COLLISION_C_NORTH);
    private static final VoxelShape COLLISION_C_SOUTH = rotateShape(Direction.NORTH, Direction.SOUTH, COLLISION_C_NORTH);
    private static final VoxelShape COLLISION_C_WEST = rotateShape(Direction.NORTH, Direction.WEST, COLLISION_C_NORTH);
    private static final VoxelShape COLLISION_D_EAST = rotateShape(Direction.NORTH, Direction.EAST, COLLISION_D_NORTH);
    private static final VoxelShape COLLISION_D_SOUTH = rotateShape(Direction.NORTH, Direction.SOUTH, COLLISION_D_NORTH);
    private static final VoxelShape COLLISION_D_WEST = rotateShape(Direction.NORTH, Direction.WEST, COLLISION_D_NORTH);

    public StairsSpiralLargeBlock(AbstractBlock.Settings settings) {
        super(settings);
        setDefaultState(stateManager.getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(PART, Part.B)
                .with(CAP, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART, CAP);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockPos pos = context.getBlockPos();
        BlockState below = context.getWorld().getBlockState(pos.down());
        BlockState above = context.getWorld().getBlockState(pos.up());

        Direction facing;
        if (isModernAnchor(below)) {
            facing = stackedFacing(below.get(FACING), true);
        } else if (isModernAnchor(above)) {
            facing = stackedFacing(above.get(FACING), false);
        } else {
            facing = context.getHorizontalPlayerFacing();
        }
        return getDefaultState().with(FACING, facing).with(PART, Part.B).with(CAP, false);
    }

    static Direction stackedFacing(Direction adjacentFacing, boolean placingAbove) {
        return placingAbove ? adjacentFacing.rotateYClockwise() : adjacentFacing.rotateYCounterclockwise();
    }

    private boolean isModernAnchor(BlockState state) {
        return state.isOf(this) && state.get(PART) == Part.B;
    }

    @Override
    public ClusterRecalcResult recalcCluster(World world, BlockPos seed) {
        BlockState seedState = ClusterRecalcSafety.getBlockState(world, seed);
        if (!seedState.isOf(this)) {
            return ClusterRecalcResult.none();
        }

        BlockPos anchor = legacyAnchorPos(seed, seedState);
        BlockState anchorState = ClusterRecalcSafety.getBlockState(world, anchor);
        if (!isModernAnchor(anchorState)) {
            return ClusterRecalcResult.none();
        }

        BlockPos lowest = anchor;
        int layers = 1;
        while (isModernAnchor(ClusterRecalcSafety.getBlockState(world, lowest.down()))) {
            if (ClusterRecalcSafety.isActive() && layers >= ClusterRecalcSafety.MAX_SPIRAL_LAYERS) {
                ClusterRecalcSafety.markTooLarge();
                break;
            }
            lowest = lowest.down();
            layers++;
        }

        List<BlockPos> anchors = new ArrayList<>();
        BlockPos cursor = lowest;
        while (isModernAnchor(ClusterRecalcSafety.getBlockState(world, cursor))) {
            if (ClusterRecalcSafety.isActive() && anchors.size() >= ClusterRecalcSafety.MAX_SPIRAL_LAYERS) {
                ClusterRecalcSafety.markTooLarge();
                break;
            }
            anchors.add(cursor.toImmutable());
            cursor = cursor.up();
        }

        Set<BlockPos> touched = new LinkedHashSet<>();
        for (BlockPos pos : anchors) {
            if (!ClusterRecalcSafety.claim(pos)) {
                break;
            }
            touched.add(pos);
        }
        ClusterRecalcResult unsafe = ClusterRecalcSafety.unsafeResult(touched);
        if (unsafe != null) {
            return unsafe;
        }

        Direction facing = ClusterRecalcSafety.getBlockState(world, lowest).get(FACING);
        for (BlockPos pos : anchors) {
            BlockState current = ClusterRecalcSafety.getBlockState(world, pos);
            BlockState target = current.with(FACING, facing).with(PART, Part.B).with(CAP, false);
            if (current != target) {
                world.setBlockState(pos, target, ClusterRecalcSafety.updateFlags(Block.NOTIFY_ALL));
            }
            facing = stackedFacing(facing, true);
        }
        return new ClusterRecalcResult(touched, true);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getCollisionShape(state, world, pos, context);
    }

    @Override
    public VoxelShape getRaycastShape(BlockState state, BlockView world, BlockPos pos) {
        return collisionShape(state);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return collisionShape(state);
    }

    private static VoxelShape collisionShape(BlockState state) {
        Direction facing = state.get(FACING).rotateYClockwise();
        if (state.get(PART) == Part.B) {
            return orientedShape(facing, FULL_SECTION_NORTH, FULL_SECTION_EAST,
                    FULL_SECTION_SOUTH, FULL_SECTION_WEST);
        }
        return switch (state.get(PART)) {
            case A -> orientedShape(facing, COLLISION_A_NORTH, COLLISION_A_EAST,
                    COLLISION_A_SOUTH, COLLISION_A_WEST);
            case C -> orientedShape(facing, COLLISION_C_NORTH, COLLISION_C_EAST,
                    COLLISION_C_SOUTH, COLLISION_C_WEST);
            case D -> orientedShape(facing, COLLISION_D_NORTH, COLLISION_D_EAST,
                    COLLISION_D_SOUTH, COLLISION_D_WEST);
            default -> FULL_SECTION_NORTH;
        };
    }

    private static VoxelShape orientedShape(Direction facing,
                                             VoxelShape north,
                                             VoxelShape east,
                                             VoxelShape south,
                                             VoxelShape west) {
        return switch (facing) {
            case EAST -> east;
            case SOUTH -> south;
            case WEST -> west;
            default -> north;
        };
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!world.isClient && state.getBlock() != newState.getBlock()
                && !REMOVING_LEGACY_LAYER.get() && isLegacyLayer(world, pos, state)) {
            removeLegacyLayer(world, pos, state);
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }

    @Override
    public void onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (!world.isClient && isLegacyLayer(world, pos, state)) {
            BlockPos anchor = legacyAnchorPos(pos, state);
            BlockState anchorState = world.getBlockState(anchor);
            removeLegacyLayer(world, pos, state);
            if (!player.isCreative() && anchorState.isOf(this)) {
                Block.dropStacks(anchorState, world, anchor, null, player, player.getMainHandStack());
            }
            return;
        }
        super.onBreak(world, pos, state, player);
    }

    private boolean isLegacyLayer(World world, BlockPos pos, BlockState state) {
        Direction facing = state.get(FACING);
        BlockPos originA = legacyOriginA(pos, facing, state.get(PART));
        BlockPos anchor = legacyPartPos(originA, facing, Part.B);
        BlockState anchorState = world.getBlockState(anchor);
        boolean anchorPresent = (anchor.equals(pos) && state.get(PART) == Part.B)
                || (anchorState.isOf(this) && anchorState.get(PART) == Part.B
                && anchorState.get(FACING) == facing);
        if (!anchorPresent) {
            return false;
        }

        for (Part part : List.of(Part.A, Part.C, Part.D)) {
            BlockState helper = world.getBlockState(legacyPartPos(originA, facing, part));
            if (helper.isOf(this) && helper.get(PART) == part && helper.get(FACING) == facing) {
                return true;
            }
        }
        return false;
    }

    private void removeLegacyLayer(World world, BlockPos pos, BlockState state) {
        Direction facing = state.get(FACING);
        BlockPos originA = legacyOriginA(pos, facing, state.get(PART));
        REMOVING_LEGACY_LAYER.set(true);
        try {
            for (Part part : Part.values()) {
                BlockPos partPos = legacyPartPos(originA, facing, part);
                BlockState existing = world.getBlockState(partPos);
                if (existing.isOf(this) && existing.get(PART) == part
                        && existing.get(FACING) == facing) {
                    world.setBlockState(partPos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        } finally {
            REMOVING_LEGACY_LAYER.set(false);
        }
    }

    private static BlockPos legacyAnchorPos(BlockPos pos, BlockState state) {
        Direction facing = state.get(FACING);
        BlockPos originA = legacyOriginA(pos, facing, state.get(PART));
        return legacyPartPos(originA, facing, Part.B);
    }

    private static BlockPos legacyOriginA(BlockPos pos, Direction facing, Part part) {
        Vec2i offset = legacyOffset(part);
        Vec2i rotated = rotateOffset(offset.x, offset.z, facing);
        return pos.add(-rotated.x, 0, -rotated.z);
    }

    private static BlockPos legacyPartPos(BlockPos originA, Direction facing, Part part) {
        Vec2i offset = legacyOffset(part);
        Vec2i rotated = rotateOffset(offset.x, offset.z, facing);
        return originA.add(rotated.x, 0, rotated.z);
    }

    private static Vec2i legacyOffset(Part part) {
        return switch (part) {
            case A -> new Vec2i(0, 0);
            case B -> new Vec2i(1, 0);
            case C -> new Vec2i(0, -1);
            case D -> new Vec2i(1, -1);
        };
    }

    private static Vec2i rotateOffset(int x, int z, Direction facing) {
        return switch (facing) {
            case NORTH -> new Vec2i(x, z);
            case EAST -> new Vec2i(-z, x);
            case SOUTH -> new Vec2i(-x, -z);
            case WEST -> new Vec2i(z, -x);
            default -> new Vec2i(x, z);
        };
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        return state.rotate(mirror.getRotation(state.get(FACING)));
    }

    private static VoxelShape makeFullSectionShape() {
        VoxelShape shape = COLLISION_B_NORTH;
        shape = VoxelShapes.union(shape, COLLISION_A_NORTH.offset(0.0D, 0.0D, 1.0D));
        shape = VoxelShapes.union(shape, COLLISION_C_NORTH.offset(-1.0D, 0.0D, 1.0D));
        shape = VoxelShapes.union(shape, COLLISION_D_NORTH.offset(-1.0D, 0.0D, 0.0D));
        shape = VoxelShapes.union(shape, OFFSTEP_D_NORTH);
        return VoxelShapes.union(shape, OFFSTEP_D_NORTH.offset(-1.0D, 0.0D, 0.0D));
    }

    private static VoxelShape shape(double... boxes) {
        if (boxes.length % 6 != 0) {
            throw new IllegalArgumentException("Collision coordinates must be groups of six.");
        }
        VoxelShape shape = VoxelShapes.empty();
        for (int index = 0; index < boxes.length; index += 6) {
            shape = VoxelShapes.union(shape, VoxelShapes.cuboid(
                    boxes[index], boxes[index + 1], boxes[index + 2],
                    boxes[index + 3], boxes[index + 4], boxes[index + 5]
            ));
        }
        return shape;
    }

    private static VoxelShape rotateShape(Direction from, Direction to, VoxelShape shape) {
        if (from == to) {
            return shape;
        }
        VoxelShape[] buffer = new VoxelShape[]{shape, VoxelShapes.empty()};
        int rotations = (to.getHorizontal() - from.getHorizontal() + 4) % 4;
        for (int index = 0; index < rotations; index++) {
            VoxelShape current = buffer[0];
            buffer[1] = VoxelShapes.empty();
            current.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
                double newMinX = 1 - maxZ;
                double newMinZ = minX;
                double newMaxX = 1 - minZ;
                double newMaxZ = maxX;
                buffer[1] = VoxelShapes.union(buffer[1],
                        VoxelShapes.cuboid(newMinX, minY, newMinZ, newMaxX, maxY, newMaxZ));
            });
            buffer[0] = buffer[1];
        }
        return buffer[0];
    }

    private record Vec2i(int x, int z) {
    }
}
