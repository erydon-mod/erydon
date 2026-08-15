package com.oliver.erydon.block;

import com.oliver.erydon.ErydonConfig;
import com.oliver.erydon.util.ClusterRecalcSafety;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

public class LightBlock extends Block implements ClusterRebuildableBlock {

    /**
     * Direction towards the supporting block (same convention as CoverBlock / LayerVertical...)
     * Property name is still "facing".
     */
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;

    public static final EnumProperty<Frame> FRAME = EnumProperty.of("frame", Frame.class);
    public static final EnumProperty<Part> PART = EnumProperty.of("part", Part.class);
    public static final EnumProperty<Bracket> BRACKET = EnumProperty.of("bracket", Bracket.class);
    public static final EnumProperty<Style> STYLE = EnumProperty.of("style", Style.class);
    public static final BooleanProperty LIT = Properties.LIT;

    private static final ThreadLocal<Boolean> REFLOWING = ThreadLocal.withInitial(() -> false);
    private final Layout layout;

    // Base orientation is FACING=NORTH (supported by north wall).
    private static final VoxelShape SMALL_BODY_SHAPE_NORTH = makeSmallBodyShape();
    private static final VoxelShape SMALL_BODY_SHAPE_EAST = rotateYByQuarter(SMALL_BODY_SHAPE_NORTH, 1);
    private static final VoxelShape SMALL_BODY_SHAPE_SOUTH = rotateYByQuarter(SMALL_BODY_SHAPE_NORTH, 2);
    private static final VoxelShape SMALL_BODY_SHAPE_WEST = rotateYByQuarter(SMALL_BODY_SHAPE_NORTH, 3);

    private static final VoxelShape LARGE_END_TOP_SHAPE_NORTH = makeLargeEndShape();
    private static final VoxelShape LARGE_END_TOP_SHAPE_EAST = rotateYByQuarter(LARGE_END_TOP_SHAPE_NORTH, 1);
    private static final VoxelShape LARGE_END_TOP_SHAPE_SOUTH = rotateYByQuarter(LARGE_END_TOP_SHAPE_NORTH, 2);
    private static final VoxelShape LARGE_END_TOP_SHAPE_WEST = rotateYByQuarter(LARGE_END_TOP_SHAPE_NORTH, 3);

    private static final VoxelShape LARGE_END_BOTTOM_SHAPE_NORTH = rotateYByQuarter(rotateX180(LARGE_END_TOP_SHAPE_NORTH), 2);
    private static final VoxelShape LARGE_END_BOTTOM_SHAPE_EAST = rotateYByQuarter(LARGE_END_BOTTOM_SHAPE_NORTH, 1);
    private static final VoxelShape LARGE_END_BOTTOM_SHAPE_SOUTH = rotateYByQuarter(LARGE_END_BOTTOM_SHAPE_NORTH, 2);
    private static final VoxelShape LARGE_END_BOTTOM_SHAPE_WEST = rotateYByQuarter(LARGE_END_BOTTOM_SHAPE_NORTH, 3);

    private static final VoxelShape LARGE_MID_SHAPE_NORTH = makeLargeMidShape();
    private static final VoxelShape LARGE_MID_SHAPE_EAST = rotateYByQuarter(LARGE_MID_SHAPE_NORTH, 1);
    private static final VoxelShape LARGE_MID_SHAPE_SOUTH = rotateYByQuarter(LARGE_MID_SHAPE_NORTH, 2);
    private static final VoxelShape LARGE_MID_SHAPE_WEST = rotateYByQuarter(LARGE_MID_SHAPE_NORTH, 3);

    private static final VoxelShape BRACKET_FULL_SHAPE_NORTH = makeBracketFullShape();
    private static final VoxelShape BRACKET_FULL_SHAPE_EAST = rotateYByQuarter(BRACKET_FULL_SHAPE_NORTH, 1);
    private static final VoxelShape BRACKET_FULL_SHAPE_SOUTH = rotateYByQuarter(BRACKET_FULL_SHAPE_NORTH, 2);
    private static final VoxelShape BRACKET_FULL_SHAPE_WEST = rotateYByQuarter(BRACKET_FULL_SHAPE_NORTH, 3);

    private static final VoxelShape BRACKET_HALF_BOTTOM_SHAPE_NORTH = makeBracketHalfShape();
    private static final VoxelShape BRACKET_HALF_BOTTOM_SHAPE_EAST = rotateYByQuarter(BRACKET_HALF_BOTTOM_SHAPE_NORTH, 1);
    private static final VoxelShape BRACKET_HALF_BOTTOM_SHAPE_SOUTH = rotateYByQuarter(BRACKET_HALF_BOTTOM_SHAPE_NORTH, 2);
    private static final VoxelShape BRACKET_HALF_BOTTOM_SHAPE_WEST = rotateYByQuarter(BRACKET_HALF_BOTTOM_SHAPE_NORTH, 3);

    private static final VoxelShape BRACKET_HALF_TOP_SHAPE_NORTH = rotateYByQuarter(rotateX180(BRACKET_HALF_BOTTOM_SHAPE_NORTH), 2);
    private static final VoxelShape BRACKET_HALF_TOP_SHAPE_EAST = rotateYByQuarter(BRACKET_HALF_TOP_SHAPE_NORTH, 1);
    private static final VoxelShape BRACKET_HALF_TOP_SHAPE_SOUTH = rotateYByQuarter(BRACKET_HALF_TOP_SHAPE_NORTH, 2);
    private static final VoxelShape BRACKET_HALF_TOP_SHAPE_WEST = rotateYByQuarter(BRACKET_HALF_TOP_SHAPE_NORTH, 3);

    private static final VoxelShape WALL_BASE_SHAPE_NORTH = makeWallBaseShape();
    private static final VoxelShape WALL_BASE_SHAPE_EAST = rotateYByQuarter(WALL_BASE_SHAPE_NORTH, 1);
    private static final VoxelShape WALL_BASE_SHAPE_SOUTH = rotateYByQuarter(WALL_BASE_SHAPE_NORTH, 2);
    private static final VoxelShape WALL_BASE_SHAPE_WEST = rotateYByQuarter(WALL_BASE_SHAPE_NORTH, 3);

    private static final VoxelShape WALL_SINGLE_SHAPE_NORTH = VoxelShapes.union(
            WALL_BASE_SHAPE_NORTH,
            VoxelShapes.cuboid(5.25 / 16.0, 10.5 / 16.0, 0.25 / 16.0, 10.75 / 16.0, 1.0, 5.75 / 16.0)
    );
    private static final VoxelShape WALL_SINGLE_SHAPE_EAST = rotateYByQuarter(WALL_SINGLE_SHAPE_NORTH, 1);
    private static final VoxelShape WALL_SINGLE_SHAPE_SOUTH = rotateYByQuarter(WALL_SINGLE_SHAPE_NORTH, 2);
    private static final VoxelShape WALL_SINGLE_SHAPE_WEST = rotateYByQuarter(WALL_SINGLE_SHAPE_NORTH, 3);

    private static final VoxelShape WALL_DOUBLE_SHAPE_NORTH = VoxelShapes.union(
            WALL_BASE_SHAPE_NORTH,
            VoxelShapes.cuboid(1.75 / 16.0, 10.5 / 16.0, 0.25 / 16.0, 14.25 / 16.0, 1.0, 5.75 / 16.0)
    );
    private static final VoxelShape WALL_DOUBLE_SHAPE_EAST = rotateYByQuarter(WALL_DOUBLE_SHAPE_NORTH, 1);
    private static final VoxelShape WALL_DOUBLE_SHAPE_SOUTH = rotateYByQuarter(WALL_DOUBLE_SHAPE_NORTH, 2);
    private static final VoxelShape WALL_DOUBLE_SHAPE_WEST = rotateYByQuarter(WALL_DOUBLE_SHAPE_NORTH, 3);

    public static int luminance(BlockState state) {
        if (!state.get(LIT)) {
            return 0;
        }
        return state.getBlock() instanceof WallLightBlock ? ErydonConfig.wallLightLevel() : ErydonConfig.modernLightLevel();
    }

    private static VoxelShape rotateYByQuarter(VoxelShape shape, int quarterTurnsClockwise) {
        int turns = Math.floorMod(quarterTurnsClockwise, 4);
        VoxelShape current = shape;
        for (int i = 0; i < turns; i++) {
            current = rotateY90(current);
        }
        return current;
    }

    private static VoxelShape rotateY90(VoxelShape shape) {
        // Rotate 90 deg around Y: NORTH -> EAST -> SOUTH -> WEST
        final VoxelShape[] out = new VoxelShape[]{VoxelShapes.empty()};
        shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
            double newMinX = 1.0 - maxZ;
            double newMaxX = 1.0 - minZ;
            double newMinZ = minX;
            double newMaxZ = maxX;
            out[0] = VoxelShapes.combine(out[0],
                    VoxelShapes.cuboid(newMinX, minY, newMinZ, newMaxX, maxY, newMaxZ),
                    BooleanBiFunction.OR);
        });
        return out[0];
    }

    private static VoxelShape rotateX180(VoxelShape shape) {
        final VoxelShape[] out = new VoxelShape[]{VoxelShapes.empty()};
        shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
            double newMinY = 1.0 - maxY;
            double newMaxY = 1.0 - minY;
            double newMinZ = 1.0 - maxZ;
            double newMaxZ = 1.0 - minZ;
            out[0] = VoxelShapes.combine(out[0],
                    VoxelShapes.cuboid(minX, newMinY, newMinZ, maxX, newMaxY, newMaxZ),
                    BooleanBiFunction.OR);
        });
        return out[0];
    }

    private static VoxelShape makeSmallBodyShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.7110848040456362, 0.375, 0.018140683436089156, 0.7423348040456362, 0.625, 0.08064068343608916), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.6439413540764009, 0.1875, 0.11554322907640086, 0.7064413540764009, 0.8125, 0.14679322907640086), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.562651278091751, 0.0625, 0.16581165476026347, 0.625151278091751, 0.9375, 0.19706165476026347), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.46886875, 0, 0.179818125, 0.53136875, 1, 0.211068125), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.37484872190824897, 0.0625, 0.16581165476026347, 0.43734872190824897, 0.9375, 0.19706165476026347), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.29355864592359915, 0.1875, 0.11554322907640086, 0.35605864592359915, 0.8125, 0.14679322907640086), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.25766519595436377, 0.375, 0.018140683436089156, 0.28891519595436377, 0.625, 0.08064068343608916), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape makeBracketFullShape() {
        return VoxelShapes.cuboid(0.377625, 0.37745875, 0, 0.621375, 0.62254125, 0.03125);
    }

    private static VoxelShape makeLargeEndShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.711084886027292, 0, 0.0340756802170752, 0.742334886027292, 0.25, 0.0965756802170752), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.6439414502815644, 0, 0.1314778321546768, 0.7064414502815644, 0.625, 0.1627278321546768), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.5626520704792513, 0, 0.18174670108213728, 0.6251520704792513, 0.875, 0.21299670108213728), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.46886875, 0, 0.195753125, 0.53136875, 1, 0.227003125), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.37484792952074875, 0, 0.18174670108213728, 0.43734792952074875, 0.875, 0.21299670108213728), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.2935585497184357, 0, 0.1314778321546768, 0.3560585497184357, 0.625, 0.1627278321546768), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.257665113972708, 0, 0.03407568021707519, 0.288915113972708, 0.25, 0.09657568021707519), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape makeLargeMidShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.7110847797602634, 0, 0.034075653091751035, 0.7423347797602634, 1, 0.09657565309175103), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.6595667960181391, 0, 0.11585278713466263, 0.6908167960181391, 1, 0.17835278713466263), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.5626521422688963, 0, 0.18174660718497126, 0.6251521422688963, 1, 0.21299660718497126), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.46886875, 0, 0.195753125, 0.53136875, 1, 0.227003125), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.37484790530639595, 0, 0.18174636800782604, 0.43734790530639595, 1, 0.21299636800782604), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.30918338704012266, 0, 0.11585322907640089, 0.34043338704012266, 1, 0.1783532290764009), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.25766526781502874, 0, 0.03407589226889626, 0.28891526781502874, 1, 0.09657589226889626), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape makeBracketHalfShape() {
        return VoxelShapes.cuboid(0.37745875, 0, 0, 0.62254125, 0.14850000000000002, 0.03125);
    }

    private static VoxelShape makeWallBaseShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(6.75 / 16.0, 8.75 / 16.0, 0, 9.25 / 16.0, 11.25 / 16.0, 0.5 / 16.0), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(7.25 / 16.0, 9.5 / 16.0, 0, 8.75 / 16.0, 10.5 / 16.0, 3.0 / 16.0), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(7.25 / 16.0, 0.5 / 16.0, 2.75 / 16.0, 8.75 / 16.0, 11.0 / 16.0, 3.25 / 16.0), BooleanBiFunction.OR);
        return shape;
    }

    public LightBlock(Settings settings) {
        this(settings, Layout.MODERN);
    }

    public LightBlock(Settings settings, Layout layout) {
        super(settings);
        this.layout = layout;
        BlockState defaultState = this.getStateManager().getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(FRAME, Frame.BRONZE)
                .with(STYLE, Style.SINGLE)
                .with(LIT, true);
        if (!usesCompactWallStateSchema()) {
            defaultState = defaultState
                    .with(PART, Part.SINGLE)
                    .with(BRACKET, layout == Layout.MODERN ? Bracket.FULL : Bracket.NONE);
        }
        this.setDefaultState(defaultState);
    }

    @Override
    public @Nullable BlockState getPlacementState(ItemPlacementContext ctx) {
        if (layout == Layout.WALL) {
            return getWallPlacementState(ctx);
        }

        Direction side = ctx.getSide();
        BlockPos pos = ctx.getBlockPos();
        World world = ctx.getWorld();

        Direction facing;
        Frame frame = Frame.BRONZE;
        boolean lit = true;

        if (side.getAxis().isHorizontal()) {
            // Wall placement: facing points at the support block.
            facing = side.getOpposite();

            BlockState source = findVerticalStyleSource(world, pos, facing);
            if (source != null) {
                frame = source.get(FRAME);
                lit = source.get(LIT);
            }
        } else {
            // Top/bottom click convenience: inherit support direction from a neighbour.
            BlockState source = findAnyVerticalSource(world, pos);
            if (source == null) {
                return null;
            }
            facing = source.get(FACING);
            frame = source.get(FRAME);
            lit = source.get(LIT);
        }

        BlockState placed = this.getDefaultState()
                .with(FACING, facing)
                .with(FRAME, frame)
                .with(PART, Part.SINGLE)
                .with(BRACKET, Bracket.FULL)
                .with(LIT, lit);

        return placed.canPlaceAt(world, pos) ? placed : null;
    }

    private @Nullable BlockState getWallPlacementState(ItemPlacementContext ctx) {
        Direction side = ctx.getSide();
        BlockPos pos = ctx.getBlockPos();
        World world = ctx.getWorld();

        Direction facing;
        Frame frame = Frame.BRONZE;
        Style style = Style.SINGLE;
        boolean lit = true;

        if (side.getAxis().isHorizontal()) {
            facing = side.getOpposite();

            BlockState source = findVerticalStyleSource(world, pos, facing);
            if (source != null) {
                frame = source.get(FRAME);
                style = source.get(STYLE);
                lit = source.get(LIT);
            }
        } else {
            BlockState source = findAnyVerticalSource(world, pos);
            if (source == null) {
                return null;
            }
            facing = source.get(FACING);
            frame = source.get(FRAME);
            style = source.get(STYLE);
            lit = source.get(LIT);
        }

        BlockState placed = this.getDefaultState()
                .with(FACING, facing)
                .with(FRAME, frame)
                .with(STYLE, style)
                .with(LIT, lit);
        if (!usesCompactWallStateSchema()) {
            placed = placed
                    .with(PART, Part.SINGLE)
                    .with(BRACKET, Bracket.NONE);
        }

        return placed.canPlaceAt(world, pos) ? placed : null;
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);
        if (layout != Layout.MODERN || world.isClient || REFLOWING.get()) {
            return;
        }
        reflowCluster(world, pos);
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (layout != Layout.MODERN) {
            super.onStateReplaced(state, world, pos, newState, moved);
            return;
        }

        if (!world.isClient && !REFLOWING.get()) {
            boolean changedClusterKey = state.isOf(this)
                    && (!newState.isOf(this) || !hasSameClusterKey(state, newState));
            boolean changedAutoState = newState.isOf(this)
                    && (!state.isOf(this)
                    || state.get(PART) != newState.get(PART)
                    || state.get(BRACKET) != newState.get(BRACKET)
                    || !hasSameClusterKey(state, newState));

            if (changedClusterKey) {
                reflowCluster(world, pos.up());
                reflowCluster(world, pos.down());
            }
            if (changedAutoState) {
                reflowCluster(world, pos);
            }
        }

        super.onStateReplaced(state, world, pos, newState, moved);
    }

    @Override
    public VoxelShape getRaycastShape(BlockState state, BlockView world, BlockPos pos) {
        return getOutlineShape(state, world, pos, ShapeContext.absent());
    }

    @Override
    public boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        Direction supportDir = state.get(FACING);
        BlockPos supportPos = pos.offset(supportDir);
        BlockState support = world.getBlockState(supportPos);
        return support.isSideSolidFullSquare(world, supportPos, supportDir.getOpposite());
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        Direction supportDir = state.get(FACING);
        if (direction == supportDir && !state.canPlaceAt(world, pos)) {
            return net.minecraft.block.Blocks.AIR.getDefaultState();
        }

        return state;
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (player.getStackInHand(hand).isOf(Items.DEBUG_STICK)) {
            return ActionResult.PASS;
        }

        if (world.isClient) {
            return ActionResult.SUCCESS;
        }

        syncClusterLit(world, pos, state, !state.get(LIT));
        return ActionResult.SUCCESS;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        if (usesCompactWallStateSchema()) {
            builder.add(FACING, FRAME, STYLE, LIT);
            return;
        }
        builder.add(FACING, FRAME, PART, BRACKET, STYLE, LIT);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (layout == Layout.WALL) {
            Direction facing = state.get(FACING);
            return switch (state.get(STYLE)) {
                case SINGLE -> shapeForFacing(facing, WALL_SINGLE_SHAPE_NORTH, WALL_SINGLE_SHAPE_EAST, WALL_SINGLE_SHAPE_SOUTH, WALL_SINGLE_SHAPE_WEST);
                case DOUBLE -> shapeForFacing(facing, WALL_DOUBLE_SHAPE_NORTH, WALL_DOUBLE_SHAPE_EAST, WALL_DOUBLE_SHAPE_SOUTH, WALL_DOUBLE_SHAPE_WEST);
            };
        }

        Direction facing = state.get(FACING);
        VoxelShape body = switch (state.get(PART)) {
            case SINGLE -> shapeForFacing(facing, SMALL_BODY_SHAPE_NORTH, SMALL_BODY_SHAPE_EAST, SMALL_BODY_SHAPE_SOUTH, SMALL_BODY_SHAPE_WEST);
            case TOP -> shapeForFacing(facing, LARGE_END_TOP_SHAPE_NORTH, LARGE_END_TOP_SHAPE_EAST, LARGE_END_TOP_SHAPE_SOUTH, LARGE_END_TOP_SHAPE_WEST);
            case MIDDLE -> shapeForFacing(facing, LARGE_MID_SHAPE_NORTH, LARGE_MID_SHAPE_EAST, LARGE_MID_SHAPE_SOUTH, LARGE_MID_SHAPE_WEST);
            case BOTTOM -> shapeForFacing(facing, LARGE_END_BOTTOM_SHAPE_NORTH, LARGE_END_BOTTOM_SHAPE_EAST, LARGE_END_BOTTOM_SHAPE_SOUTH, LARGE_END_BOTTOM_SHAPE_WEST);
        };
        VoxelShape bracket = switch (state.get(BRACKET)) {
            case NONE -> VoxelShapes.empty();
            case FULL -> shapeForFacing(facing, BRACKET_FULL_SHAPE_NORTH, BRACKET_FULL_SHAPE_EAST, BRACKET_FULL_SHAPE_SOUTH, BRACKET_FULL_SHAPE_WEST);
            case HALF_TOP -> shapeForFacing(facing, BRACKET_HALF_TOP_SHAPE_NORTH, BRACKET_HALF_TOP_SHAPE_EAST, BRACKET_HALF_TOP_SHAPE_SOUTH, BRACKET_HALF_TOP_SHAPE_WEST);
            case HALF_BOTTOM -> shapeForFacing(facing, BRACKET_HALF_BOTTOM_SHAPE_NORTH, BRACKET_HALF_BOTTOM_SHAPE_EAST, BRACKET_HALF_BOTTOM_SHAPE_SOUTH, BRACKET_HALF_BOTTOM_SHAPE_WEST);
        };
        return VoxelShapes.union(body, bracket);
    }

    private static VoxelShape shapeForFacing(Direction facing, VoxelShape north, VoxelShape east, VoxelShape south, VoxelShape west) {
        return switch (facing) {
            case NORTH -> north;
            case EAST -> east;
            case SOUTH -> south;
            case WEST -> west;
            default -> north;
        };
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getOutlineShape(state, world, pos, context);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public BlockState rotate(BlockState state, net.minecraft.util.BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, net.minecraft.util.BlockMirror mirror) {
        return rotate(state, mirror.getRotation(state.get(FACING)));
    }

    private boolean isClusterCompatible(BlockState a, BlockState b) {
        return b.isOf(this)
                && a.get(FACING) == b.get(FACING)
                && a.get(FRAME) == b.get(FRAME);
    }

    private boolean hasSameClusterKey(BlockState a, BlockState b) {
        return a.isOf(this)
                && b.isOf(this)
                && a.get(FACING) == b.get(FACING)
                && a.get(FRAME) == b.get(FRAME);
    }

    @Nullable
    private BlockState findVerticalStyleSource(WorldAccess world, BlockPos pos, Direction facing) {
        BlockState below = world.getBlockState(pos.down());
        if (below.isOf(this) && below.get(FACING) == facing) {
            return below;
        }

        BlockState above = world.getBlockState(pos.up());
        if (above.isOf(this) && above.get(FACING) == facing) {
            return above;
        }

        return null;
    }

    @Nullable
    private BlockState findAnyVerticalSource(WorldAccess world, BlockPos pos) {
        BlockState below = world.getBlockState(pos.down());
        if (below.isOf(this)) {
            return below;
        }

        BlockState above = world.getBlockState(pos.up());
        if (above.isOf(this)) {
            return above;
        }

        return null;
    }

    private void syncClusterLit(World world, BlockPos seed, BlockState anchor, boolean lit) {
        if (!anchor.isOf(this)) {
            return;
        }

        BlockPos bottom = findClusterBottom(world, seed, anchor);
        BlockPos top = findClusterTop(world, seed, anchor);

        for (int y = bottom.getY(); y <= top.getY(); y++) {
            BlockPos currentPos = new BlockPos(seed.getX(), y, seed.getZ());
            BlockState currentState = world.getBlockState(currentPos);
            if (!isClusterCompatible(anchor, currentState) || currentState.get(LIT) == lit) {
                continue;
            }
            world.setBlockState(currentPos, currentState.with(LIT, lit), Block.NOTIFY_ALL);
        }
    }

    private void reflowCluster(World world, BlockPos seed) {
        if (REFLOWING.get()) {
            return;
        }

        BlockState anchor = world.getBlockState(seed);
        if (!anchor.isOf(this)) {
            return;
        }

        BlockPos bottom = findClusterBottom(world, seed, anchor);
        BlockPos top = findClusterTop(world, seed, anchor);
        int height = top.getY() - bottom.getY() + 1;

        REFLOWING.set(true);
        try {
            for (int y = bottom.getY(); y <= top.getY(); y++) {
                int indexFromBottom = y - bottom.getY();
                BlockPos currentPos = new BlockPos(seed.getX(), y, seed.getZ());
                BlockState currentState = world.getBlockState(currentPos);
                if (!isClusterCompatible(anchor, currentState)) {
                    continue;
                }

                Part part = resolvePart(indexFromBottom, height);
                Bracket bracket = resolveBracket(indexFromBottom, height);
                BlockState updated = currentState.with(PART, part).with(BRACKET, bracket);
                if (updated != currentState) {
                    world.setBlockState(currentPos, updated,
                            ClusterRecalcSafety.updateFlags(Block.NOTIFY_ALL));
                }
            }
        } finally {
            REFLOWING.set(false);
        }
    }

    @Override
    public ClusterRecalcResult recalcCluster(World world, BlockPos seed) {
        if (layout != Layout.MODERN) {
            return ClusterRecalcResult.none();
        }

        BlockState anchor = world.getBlockState(seed);
        if (!anchor.isOf(this)) {
            return ClusterRecalcResult.none();
        }

        BlockPos bottom = findClusterBottom(world, seed, anchor);
        BlockPos top = findClusterTop(world, seed, anchor);
        Set<BlockPos> positions = new LinkedHashSet<>();

        for (int y = bottom.getY(); y <= top.getY(); y++) {
            BlockPos pos = new BlockPos(seed.getX(), y, seed.getZ());
            if (!ClusterRecalcSafety.claim(pos)) {
                break;
            }
            positions.add(pos);
        }

        ClusterRecalcResult unsafe = ClusterRecalcSafety.unsafeResult(positions);
        if (unsafe != null) {
            return unsafe;
        }

        reflowCluster(world, seed);
        return new ClusterRecalcResult(positions, true);
    }

    private BlockPos findClusterBottom(WorldAccess world, BlockPos seed, BlockState anchor) {
        BlockPos current = seed;
        while (isClusterCompatible(anchor, world.getBlockState(current.down()))) {
            current = current.down();
        }
        return current;
    }

    private BlockPos findClusterTop(WorldAccess world, BlockPos seed, BlockState anchor) {
        BlockPos current = seed;
        while (isClusterCompatible(anchor, world.getBlockState(current.up()))) {
            current = current.up();
        }
        return current;
    }

    private static Part resolvePart(int indexFromBottom, int height) {
        if (height <= 1) {
            return Part.SINGLE;
        }
        if (indexFromBottom == 0) {
            return Part.BOTTOM;
        }
        if (indexFromBottom == height - 1) {
            return Part.TOP;
        }
        return Part.MIDDLE;
    }

    private static Bracket resolveBracket(int indexFromBottom, int height) {
        if (height <= 1) {
            return Bracket.FULL;
        }

        if ((height & 1) == 1) {
            return indexFromBottom == (height / 2) ? Bracket.FULL : Bracket.NONE;
        }

        if (indexFromBottom == (height / 2)) {
            return Bracket.HALF_BOTTOM;
        }
        if (indexFromBottom == (height / 2) - 1) {
            return Bracket.HALF_TOP;
        }
        return Bracket.NONE;
    }

    protected boolean usesCompactWallStateSchema() {
        return false;
    }

    public enum Frame implements StringIdentifiable {
        BRONZE("bronze"),
        SILVER("silver");

        private final String id;

        Frame(String id) {
            this.id = id;
        }

        @Override
        public String asString() {
            return id;
        }
    }

    public enum Part implements StringIdentifiable {
        SINGLE("single"),
        TOP("top"),
        MIDDLE("middle"),
        BOTTOM("bottom");

        private final String id;

        Part(String id) {
            this.id = id;
        }

        @Override
        public String asString() {
            return id;
        }
    }

    public enum Bracket implements StringIdentifiable {
        NONE("none"),
        FULL("full"),
        HALF_TOP("half_top"),
        HALF_BOTTOM("half_bottom");

        private final String id;

        Bracket(String id) {
            this.id = id;
        }

        @Override
        public String asString() {
            return id;
        }
    }

    public enum Style implements StringIdentifiable {
        SINGLE("single"),
        DOUBLE("double");

        private final String id;

        Style(String id) {
            this.id = id;
        }

        @Override
        public String asString() {
            return id;
        }
    }

    public enum Layout {
        MODERN,
        WALL
    }
}
