package com.oliver.erydon.block;

import com.oliver.erydon.ErydonConfig;
import com.oliver.erydon.util.ClusterRecalcSafety;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.Waterloggable;
import net.minecraft.entity.LivingEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class CeilingBlock extends Block implements Waterloggable, ClusterRebuildableBlock {

    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;
    public static final EnumProperty<CeilingLight> LIGHT = EnumProperty.of("light", CeilingLight.class);
    public static final EnumProperty<CeilingFinish> FINISH = EnumProperty.of("finish", CeilingFinish.class);
    public static final EnumProperty<CeilingHiddenState> UNUSED =
            EnumProperty.of("unused", CeilingHiddenState.class);

    private static final int NORTH_WALL_MASK = 1 << 0;
    private static final int EAST_WALL_MASK = 1 << 1;
    private static final int SOUTH_WALL_MASK = 1 << 2;
    private static final int WEST_WALL_MASK = 1 << 3;
    private static final int OUTER_NE_MASK = 1 << 4;
    private static final int OUTER_SE_MASK = 1 << 5;
    private static final int OUTER_SW_MASK = 1 << 6;
    private static final int OUTER_NW_MASK = 1 << 7;

    private static final VoxelShape SHAPE = makeShape();

    public CeilingBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(WATERLOGGED, false)
                .with(LIGHT, CeilingLight.NONE)
                .with(FINISH, CeilingFinish.MATTE)
                .with(UNUSED, CeilingHiddenState.U00));
    }

    public static int luminance(BlockState state) {
        return state.get(LIGHT) == CeilingLight.NONE ? 0 : ErydonConfig.cofferedCeilingLightLevel();
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        FluidState fluid = ctx.getWorld().getFluidState(ctx.getBlockPos());
        BlockState placed = this.resolveAttachments(
                this.getDefaultState().with(WATERLOGGED, fluid.getFluid() == Fluids.WATER),
                ctx.getWorld(),
                ctx.getBlockPos()
        );

        return placed.canPlaceAt(ctx.getWorld(), ctx.getBlockPos()) ? placed : null;
    }

    @Override
    public boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        BlockPos supportPos = pos.up();
        BlockState support = world.getBlockState(supportPos);
        return support.isSideSolidFullSquare(world, supportPos, Direction.DOWN);
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (state.get(WATERLOGGED)) {
            world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
        }

        if (direction == Direction.UP && !state.canPlaceAt(world, pos)) {
            return net.minecraft.block.Blocks.AIR.getDefaultState();
        }

        if (direction.getAxis().isHorizontal()) {
            return this.resolveAttachments(state, world, pos);
        }

        return state;
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.get(WATERLOGGED) ? Fluids.WATER.getStill(false) : super.getFluidState(state);
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        int rotatedMask = rotateMask(state.get(UNUSED).mask(), rotation);
        return state.with(UNUSED, CeilingHiddenState.fromMask(rotatedMask));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        int mirroredMask = mirrorMask(state.get(UNUSED).mask(), mirror);
        return state.with(UNUSED, CeilingHiddenState.fromMask(mirroredMask));
    }

    @Override
    public int getOpacity(BlockState state, BlockView world, BlockPos pos) {
        return 0;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return SHAPE;
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);
        if (world.isClient) {
            return;
        }

        refreshCeilingAt(world, pos);
        refreshNearbyCeilings(world, pos);
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!world.isClient) {
            if (state.getBlock() instanceof CeilingBlock) {
                refreshNearbyCeilings(world, pos);
            }

            if (newState.getBlock() instanceof CeilingBlock) {
                refreshCeilingAt(world, pos);
                refreshNearbyCeilings(world, pos);
            }
        }

        super.onStateReplaced(state, world, pos, newState, moved);
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(WATERLOGGED, LIGHT, FINISH, UNUSED);
    }

    public enum CeilingFinish implements StringIdentifiable {
        MATTE("matte"),
        GLOSS("gloss");

        private final String id;

        CeilingFinish(String id) {
            this.id = id;
        }

        @Override
        public String asString() {
            return this.id;
        }
    }

    public enum CeilingLight implements StringIdentifiable {
        NONE("none", 0),
        LOW("low", 13),
        BRIGHT("bright", 15);

        private final String id;
        public final int level;

        CeilingLight(String id, int level) {
            this.id = id;
            this.level = level;
        }

        @Override
        public String asString() {
            return this.id;
        }
    }

    public enum CeilingHiddenState implements StringIdentifiable {
        U00(0x00, "u00"),
        U01(0x01, "u01"),
        U02(0x02, "u02"),
        U03(0x03, "u03"),
        U04(0x04, "u04"),
        U05(0x05, "u05"),
        U06(0x06, "u06"),
        U07(0x07, "u07"),
        U08(0x08, "u08"),
        U09(0x09, "u09"),
        U0A(0x0A, "u0a"),
        U0B(0x0B, "u0b"),
        U0C(0x0C, "u0c"),
        U0D(0x0D, "u0d"),
        U0E(0x0E, "u0e"),
        U0F(0x0F, "u0f"),
        U10(0x10, "u10"),
        U13(0x13, "u13"),
        U14(0x14, "u14"),
        U17(0x17, "u17"),
        U18(0x18, "u18"),
        U1B(0x1B, "u1b"),
        U1C(0x1C, "u1c"),
        U1F(0x1F, "u1f"),
        U20(0x20, "u20"),
        U21(0x21, "u21"),
        U26(0x26, "u26"),
        U27(0x27, "u27"),
        U28(0x28, "u28"),
        U29(0x29, "u29"),
        U2E(0x2E, "u2e"),
        U2F(0x2F, "u2f"),
        U30(0x30, "u30"),
        U37(0x37, "u37"),
        U38(0x38, "u38"),
        U3F(0x3F, "u3f"),
        U40(0x40, "u40"),
        U41(0x41, "u41"),
        U42(0x42, "u42"),
        U43(0x43, "u43"),
        U4C(0x4C, "u4c"),
        U4D(0x4D, "u4d"),
        U4E(0x4E, "u4e"),
        U4F(0x4F, "u4f"),
        U50(0x50, "u50"),
        U53(0x53, "u53"),
        U5C(0x5C, "u5c"),
        U5F(0x5F, "u5f"),
        U60(0x60, "u60"),
        U61(0x61, "u61"),
        U6E(0x6E, "u6e"),
        U6F(0x6F, "u6f"),
        U70(0x70, "u70"),
        U7F(0x7F, "u7f"),
        U80(0x80, "u80"),
        U82(0x82, "u82"),
        U84(0x84, "u84"),
        U86(0x86, "u86"),
        U89(0x89, "u89"),
        U8B(0x8B, "u8b"),
        U8D(0x8D, "u8d"),
        U8F(0x8F, "u8f"),
        U90(0x90, "u90"),
        U94(0x94, "u94"),
        U9B(0x9B, "u9b"),
        U9F(0x9F, "u9f"),
        UA0(0xA0, "ua0"),
        UA6(0xA6, "ua6"),
        UA9(0xA9, "ua9"),
        UAF(0xAF, "uaf"),
        UB0(0xB0, "ub0"),
        UBF(0xBF, "ubf"),
        UC0(0xC0, "uc0"),
        UC2(0xC2, "uc2"),
        UCD(0xCD, "ucd"),
        UCF(0xCF, "ucf"),
        UD0(0xD0, "ud0"),
        UDF(0xDF, "udf"),
        UE0(0xE0, "ue0"),
        UEF(0xEF, "uef"),
        UF0(0xF0, "uf0"),
        UFF(0xFF, "uff");

        private static final CeilingHiddenState[] BY_MASK = new CeilingHiddenState[256];

        static {
            for (CeilingHiddenState state : values()) {
                BY_MASK[state.mask] = state;
            }
        }

        private final int mask;
        private final String id;

        CeilingHiddenState(int mask, String id) {
            this.mask = mask;
            this.id = id;
        }

        public static CeilingHiddenState fromMask(int mask) {
            int normalized = mask & 0xFF;
            CeilingHiddenState state = BY_MASK[normalized];
            if (state == null) {
                throw new IllegalArgumentException("Unsupported ceiling attachment mask: 0x"
                        + Integer.toHexString(normalized));
            }
            return state;
        }

        public int mask() {
            return this.mask;
        }

        @Override
        public String asString() {
            return this.id;
        }
    }

    private static VoxelShape makeShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.96875, 0, 1, 1, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.875, 0.0625, 0.0625, 0.96875, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0, 0.875, 0, 0.9375, 0.96875, 0.0625), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.125, 0.890625, 0.0625, 0.9375, 0.96875, 0.125), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0625, 0.890625, 0.0625, 0.125, 0.96875, 0.875), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.875, 0.890625, 0.125, 0.9375, 0.96875, 0.9375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0625, 0.890625, 0.875, 0.875, 0.96875, 0.9375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0625, 0.875, 0.9375, 1, 0.96875, 1), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.9375, 0.875, 0, 1, 0.96875, 0.9375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.9375, 0.859375, 0.046875, 0.953125, 0.875, 0.9375), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.0625, 0.859375, 0.9375, 0.953125, 0.875, 0.953125), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.046875, 0.859375, 0.0625, 0.0625, 0.875, 0.953125), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.046875, 0.859375, 0.046875, 0.9375, 0.875, 0.0625), BooleanBiFunction.OR);
        return shape;
    }

    private BlockState resolveAttachments(BlockState state, BlockView world, BlockPos pos) {
        boolean northWall = isWall(world, pos, Direction.NORTH);
        boolean eastWall = isWall(world, pos, Direction.EAST);
        boolean southWall = isWall(world, pos, Direction.SOUTH);
        boolean westWall = isWall(world, pos, Direction.WEST);

        boolean outerNe = hasOuterCorner(world, pos, Direction.NORTH, Direction.EAST, northWall, eastWall);
        boolean outerSe = hasOuterCorner(world, pos, Direction.SOUTH, Direction.EAST, southWall, eastWall);
        boolean outerSw = hasOuterCorner(world, pos, Direction.SOUTH, Direction.WEST, southWall, westWall);
        boolean outerNw = hasOuterCorner(world, pos, Direction.NORTH, Direction.WEST, northWall, westWall);

        int mask = 0;
        if (northWall) {
            mask |= NORTH_WALL_MASK;
        }
        if (eastWall) {
            mask |= EAST_WALL_MASK;
        }
        if (southWall) {
            mask |= SOUTH_WALL_MASK;
        }
        if (westWall) {
            mask |= WEST_WALL_MASK;
        }
        if (outerNe) {
            mask |= OUTER_NE_MASK;
        }
        if (outerSe) {
            mask |= OUTER_SE_MASK;
        }
        if (outerSw) {
            mask |= OUTER_SW_MASK;
        }
        if (outerNw) {
            mask |= OUTER_NW_MASK;
        }

        return state.with(UNUSED, CeilingHiddenState.fromMask(mask));
    }

    @Override
    public ClusterRecalcResult recalcCluster(World world, BlockPos seed) {
        BlockState seedState = world.getBlockState(seed);
        if (!seedState.isOf(this)) {
            return ClusterRecalcResult.none();
        }

        Set<BlockPos> component = collectPlanarComponent(world, seed);
        if (component.isEmpty()) {
            return ClusterRecalcResult.none();
        }
        ClusterRecalcResult unsafe = ClusterRecalcSafety.unsafeResult(component);
        if (unsafe != null) {
            return unsafe;
        }

        for (BlockPos pos : component) {
            BlockState state = world.getBlockState(pos);
            if (state.isOf(this) && !state.canPlaceAt(world, pos)) {
                world.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState(),
                        ClusterRecalcSafety.updateFlags(Block.NOTIFY_ALL));
            }
        }

        for (BlockPos pos : component) {
            refreshCeilingAt(world, pos);
        }
        return new ClusterRecalcResult(component, true);
    }

    private static boolean isWall(BlockView world, BlockPos pos, Direction direction) {
        BlockPos wallPos = pos.offset(direction);
        BlockState wallState = world.getBlockState(wallPos);
        return wallState.isSideSolidFullSquare(world, wallPos, direction.getOpposite());
    }

    private static boolean hasOuterCorner(BlockView world, BlockPos pos, Direction first, Direction second,
                                          boolean firstWall, boolean secondWall) {
        if (firstWall && secondWall && !hasFilledCorner(world, pos, first, second)) {
            return true;
        }

        if (firstWall || secondWall || !hasFilledCorner(world, pos, first, second)) {
            return false;
        }

        return hasCorniceRun(world, pos.offset(first), second)
                && hasCorniceRun(world, pos.offset(second), first);
    }

    private static boolean hasCorniceRun(BlockView world, BlockPos pos, Direction wallDirection) {
        BlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof CeilingBlock && isWall(world, pos, wallDirection);
    }

    private static boolean hasFilledCorner(BlockView world, BlockPos pos, Direction first, Direction second) {
        BlockPos cornerPos = pos.offset(first).offset(second);
        BlockState cornerState = world.getBlockState(cornerPos);
        return cornerState.isSideSolidFullSquare(world, cornerPos, first.getOpposite())
                && cornerState.isSideSolidFullSquare(world, cornerPos, second.getOpposite());
    }

    private Set<BlockPos> collectPlanarComponent(WorldAccess world, BlockPos seed) {
        Set<BlockPos> component = new LinkedHashSet<>();
        if (!ClusterRecalcSafety.getBlockState(world, seed).isOf(this)) {
            return component;
        }

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        if (!ClusterRecalcSafety.claim(seed)) {
            return component;
        }
        component.add(seed);
        queue.add(seed);

        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            for (BlockPos neighbor : planarNeighbours(pos)) {
                if (component.contains(neighbor)
                        || !ClusterRecalcSafety.getBlockState(world, neighbor).isOf(this)) {
                    continue;
                }
                if (!ClusterRecalcSafety.claim(neighbor)) {
                    return component;
                }
                component.add(neighbor);
                queue.add(neighbor);
            }
        }

        return component;
    }

    private static List<BlockPos> planarNeighbours(BlockPos pos) {
        return List.of(
                pos.north(),
                pos.east(),
                pos.south(),
                pos.west(),
                pos.north().east(),
                pos.east().south(),
                pos.south().west(),
                pos.west().north()
        );
    }

    private static void refreshNearbyCeilings(WorldAccess world, BlockPos pos) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            refreshCeilingAt(world, pos.offset(direction));
        }

        refreshCeilingAt(world, pos.north().east());
        refreshCeilingAt(world, pos.east().south());
        refreshCeilingAt(world, pos.south().west());
        refreshCeilingAt(world, pos.west().north());
    }

    private static void refreshCeilingAt(WorldAccess world, BlockPos pos) {
        BlockState current = world.getBlockState(pos);
        if (!(current.getBlock() instanceof CeilingBlock ceiling)) {
            return;
        }

        BlockState resolved = ceiling.resolveAttachments(current, world, pos);
        if (!resolved.equals(current)) {
            world.setBlockState(pos, resolved, ClusterRecalcSafety.updateFlags(Block.NOTIFY_ALL));
        }
    }

    private static int rotateMask(int mask, BlockRotation rotation) {
        int rotatedMask = 0;
        rotatedMask |= transformWallMask(mask, NORTH_WALL_MASK, Direction.NORTH, rotation);
        rotatedMask |= transformWallMask(mask, EAST_WALL_MASK, Direction.EAST, rotation);
        rotatedMask |= transformWallMask(mask, SOUTH_WALL_MASK, Direction.SOUTH, rotation);
        rotatedMask |= transformWallMask(mask, WEST_WALL_MASK, Direction.WEST, rotation);
        rotatedMask |= transformCornerMask(mask, OUTER_NE_MASK, Direction.NORTH, Direction.EAST, rotation);
        rotatedMask |= transformCornerMask(mask, OUTER_SE_MASK, Direction.SOUTH, Direction.EAST, rotation);
        rotatedMask |= transformCornerMask(mask, OUTER_SW_MASK, Direction.SOUTH, Direction.WEST, rotation);
        rotatedMask |= transformCornerMask(mask, OUTER_NW_MASK, Direction.NORTH, Direction.WEST, rotation);
        return rotatedMask;
    }

    private static int mirrorMask(int mask, BlockMirror mirror) {
        int mirroredMask = 0;
        mirroredMask |= transformWallMask(mask, NORTH_WALL_MASK, Direction.NORTH, mirror);
        mirroredMask |= transformWallMask(mask, EAST_WALL_MASK, Direction.EAST, mirror);
        mirroredMask |= transformWallMask(mask, SOUTH_WALL_MASK, Direction.SOUTH, mirror);
        mirroredMask |= transformWallMask(mask, WEST_WALL_MASK, Direction.WEST, mirror);
        mirroredMask |= transformCornerMask(mask, OUTER_NE_MASK, Direction.NORTH, Direction.EAST, mirror);
        mirroredMask |= transformCornerMask(mask, OUTER_SE_MASK, Direction.SOUTH, Direction.EAST, mirror);
        mirroredMask |= transformCornerMask(mask, OUTER_SW_MASK, Direction.SOUTH, Direction.WEST, mirror);
        mirroredMask |= transformCornerMask(mask, OUTER_NW_MASK, Direction.NORTH, Direction.WEST, mirror);
        return mirroredMask;
    }

    private static int transformWallMask(int mask, int sourceBit, Direction direction, BlockRotation rotation) {
        if ((mask & sourceBit) == 0) {
            return 0;
        }
        return wallMaskFor(rotation.rotate(direction));
    }

    private static int transformWallMask(int mask, int sourceBit, Direction direction, BlockMirror mirror) {
        if ((mask & sourceBit) == 0) {
            return 0;
        }
        return wallMaskFor(mirror.apply(direction));
    }

    private static int transformCornerMask(int mask, int sourceBit, Direction first, Direction second, BlockRotation rotation) {
        if ((mask & sourceBit) == 0) {
            return 0;
        }
        return cornerMaskFor(rotation.rotate(first), rotation.rotate(second));
    }

    private static int transformCornerMask(int mask, int sourceBit, Direction first, Direction second, BlockMirror mirror) {
        if ((mask & sourceBit) == 0) {
            return 0;
        }
        return cornerMaskFor(mirror.apply(first), mirror.apply(second));
    }

    private static int wallMaskFor(Direction direction) {
        return switch (direction) {
            case NORTH -> NORTH_WALL_MASK;
            case EAST -> EAST_WALL_MASK;
            case SOUTH -> SOUTH_WALL_MASK;
            case WEST -> WEST_WALL_MASK;
            default -> throw new IllegalArgumentException("Unexpected wall direction: " + direction);
        };
    }

    private static int cornerMaskFor(Direction first, Direction second) {
        boolean north = first == Direction.NORTH || second == Direction.NORTH;
        boolean east = first == Direction.EAST || second == Direction.EAST;
        boolean south = first == Direction.SOUTH || second == Direction.SOUTH;
        boolean west = first == Direction.WEST || second == Direction.WEST;

        if (north && east) {
            return OUTER_NE_MASK;
        }
        if (south && east) {
            return OUTER_SE_MASK;
        }
        if (south && west) {
            return OUTER_SW_MASK;
        }
        if (north && west) {
            return OUTER_NW_MASK;
        }

        throw new IllegalArgumentException("Unexpected corner directions: " + first + ", " + second);
    }
}
