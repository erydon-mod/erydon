package com.oliver.erydon.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.Waterloggable;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;

public class GlazingShallowSlopeBlock extends HorizontalFacingBlock implements Waterloggable {
    public enum Variant {
        LOWER,
        UPPER
    }

    public enum SlopeShape implements StringIdentifiable {
        STRAIGHT("straight"),
        INNER_LEFT("inner_left"),
        INNER_RIGHT("inner_right"),
        OUTER_LEFT("outer_left"),
        OUTER_RIGHT("outer_right");

        private final String name;

        SlopeShape(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return name;
        }
    }

    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<BlockHalf> HALF = Properties.BLOCK_HALF;
    public static final EnumProperty<SlopeShape> SHAPE = EnumProperty.of("shape", SlopeShape.class);
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;

    private static final Direction[] HORIZONTALS = new Direction[] {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };
    private static final VoxelShape LOWER_STRAIGHT = createLowerShape();
    private static final VoxelShape LOWER_INNER = createLowerInnerShape();
    private static final VoxelShape LOWER_OUTER = createLowerOuterShape();
    private static final VoxelShape UPPER_STRAIGHT = createUpperShape();
    private static final VoxelShape UPPER_INNER = createUpperInnerShape();
    private static final VoxelShape UPPER_OUTER = createUpperOuterShape();
    private static final VoxelShape[][] LOWER_BOTTOM_CACHE = buildShapeCache(LOWER_STRAIGHT, LOWER_INNER, LOWER_OUTER);
    private static final VoxelShape[][] LOWER_TOP_CACHE = buildShapeCache(flipY(LOWER_STRAIGHT), flipY(LOWER_INNER), flipY(LOWER_OUTER));
    private static final VoxelShape[][] UPPER_BOTTOM_CACHE = buildShapeCache(UPPER_STRAIGHT, UPPER_INNER, UPPER_OUTER);
    private static final VoxelShape[][] UPPER_TOP_CACHE = buildShapeCache(flipY(UPPER_STRAIGHT), flipY(UPPER_INNER), flipY(UPPER_OUTER));

    private final Variant variant;

    public GlazingShallowSlopeBlock(Settings settings, Variant variant) {
        super(settings.nonOpaque());
        this.variant = variant;
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(HALF, BlockHalf.BOTTOM)
                .with(SHAPE, SlopeShape.STRAIGHT)
                .with(WATERLOGGED, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF, SHAPE, WATERLOGGED);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockHalf half;
        if (ctx.getSide() == Direction.DOWN) {
            half = BlockHalf.TOP;
        } else if (ctx.getSide() == Direction.UP) {
            half = BlockHalf.BOTTOM;
        } else {
            double hitY = ctx.getHitPos().y - ctx.getBlockPos().getY();
            half = hitY > 0.5d ? BlockHalf.TOP : BlockHalf.BOTTOM;
        }

        BlockState placed = this.getDefaultState()
                .with(FACING, ctx.getHorizontalPlayerFacing())
                .with(HALF, half)
                .with(WATERLOGGED, ctx.getWorld().getFluidState(ctx.getBlockPos()).getFluid() == Fluids.WATER)
                .with(SHAPE, SlopeShape.STRAIGHT);

        return getStateWithShape(placed, ctx.getWorld(), ctx.getBlockPos());
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getVoxelForState(state);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getVoxelForState(state);
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
    public boolean canFillWithFluid(BlockView world, BlockPos pos, BlockState state, Fluid fluid) {
        return !state.get(WATERLOGGED) && fluid == Fluids.WATER;
    }

    @Override
    public boolean tryFillWithFluid(WorldAccess world, BlockPos pos, BlockState state, FluidState fluidState) {
        if (!state.get(WATERLOGGED) && fluidState.getFluid() == Fluids.WATER) {
            world.setBlockState(pos, state.with(WATERLOGGED, true), Block.NOTIFY_ALL);
            world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
            return true;
        }
        return false;
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (state.get(WATERLOGGED)) {
            world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
        }
        if (direction.getAxis().isHorizontal()) {
            return getStateWithShape(state, world, pos);
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        Direction facing = state.get(FACING);
        SlopeShape shape = state.get(SHAPE);

        if (mirror == BlockMirror.LEFT_RIGHT && facing.getAxis() == Direction.Axis.Z) {
            return rotate(state, BlockRotation.CLOCKWISE_180).with(SHAPE, swapLeftRight(shape));
        }
        if (mirror == BlockMirror.FRONT_BACK && facing.getAxis() == Direction.Axis.X) {
            return rotate(state, BlockRotation.CLOCKWISE_180).with(SHAPE, swapOuter(shape));
        }
        return state;
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    private VoxelShape getVoxelForState(BlockState state) {
        VoxelShape[][] cache;
        if (variant == Variant.UPPER) {
            cache = state.get(HALF) == BlockHalf.TOP ? UPPER_TOP_CACHE : UPPER_BOTTOM_CACHE;
        } else {
            cache = state.get(HALF) == BlockHalf.TOP ? LOWER_TOP_CACHE : LOWER_BOTTOM_CACHE;
        }
        return cache[state.get(SHAPE).ordinal()][horizontalIndex(state.get(FACING))];
    }

    private static VoxelShape[][] buildShapeCache(VoxelShape straight, VoxelShape inner, VoxelShape outer) {
        VoxelShape[][] cache = new VoxelShape[SlopeShape.values().length][4];

        for (SlopeShape shape : SlopeShape.values()) {
            VoxelShape base = switch (shape) {
                case INNER_LEFT, INNER_RIGHT -> inner;
                case OUTER_LEFT, OUTER_RIGHT -> outer;
                case STRAIGHT -> straight;
            };

            for (Direction facing : HORIZONTALS) {
                int steps = stepsForFacing(facing);
                if (shape == SlopeShape.OUTER_LEFT || shape == SlopeShape.OUTER_RIGHT) {
                    steps = (steps + 3) & 3;
                }
                if (shape == SlopeShape.INNER_RIGHT || shape == SlopeShape.OUTER_RIGHT) {
                    steps = (steps + 1) & 3;
                }
                cache[shape.ordinal()][horizontalIndex(facing)] = rotateShapeSteps(base, steps);
            }
        }

        return cache;
    }

    private static VoxelShape createLowerShape() {
        return VoxelShapes.union(
                VoxelShapes.cuboid(0.0, 0.4212732111180475, 0.9731672951882979, 1.0, 0.49939821111804794, 1.0512922951883024),
                VoxelShapes.cuboid(0.0, 0.002713206968730675, -0.03732594349592181, 1.0, 0.08083820696873112, 0.04079905650408263),
                VoxelShapes.cuboid(0.0, 0.033963206968730675, 0.04079905650407819, 1.0, 0.11208820696873112, 0.11892405650408264),
                VoxelShapes.cuboid(0.0, 0.09779654030206408, 0.1970490565040782, 1.0, 0.17592154030206453, 0.27517405650408266),
                VoxelShapes.cuboid(0.0, 0.06687987363539738, 0.1189240565040782, 1.0, 0.14500487363539782, 0.19704905650408264),
                VoxelShapes.cuboid(0.0, 0.1297132069687308, 0.2751740565040782, 1.0, 0.20783820696873123, 0.35329905650408266),
                VoxelShapes.cuboid(0.0, 0.16329654030206397, 0.3532990565040782, 1.0, 0.24142154030206442, 0.43142405650408266),
                VoxelShapes.cuboid(0.0, 0.22979654030206398, 0.5095490565040782, 1.0, 0.3079215403020644, 0.5876740565040827),
                VoxelShapes.cuboid(0.0, 0.19721320696873057, 0.4314240565040782, 1.0, 0.275338206968731, 0.5095490565040827),
                VoxelShapes.cuboid(0.0, 0.2597132069687308, 0.5876740565040782, 1.0, 0.33783820696873124, 0.6657990565040827),
                VoxelShapes.cuboid(0.0, 0.2896298736353974, 0.6657990565040782, 1.0, 0.3677548736353978, 0.7439240565040827),
                VoxelShapes.cuboid(0.0, 0.3222132069687309, 0.7439240565040782, 1.0, 0.40033820696873135, 0.8220490565040827),
                VoxelShapes.cuboid(0.0, 0.3554632069687307, 0.8220490565040782, 1.0, 0.4335882069687311, 0.9001740565040827),
                VoxelShapes.cuboid(0.0, 0.3877132069687306, 0.9001740565040782, 1.0, 0.465838206968731, 0.972632389837416)
        );
    }

    private static VoxelShape createUpperShape() {
        return VoxelShapes.union(
                VoxelShapes.cuboid(0.0, 0.9212732111180475, 0.9731672951882979, 1.0, 0.9993982111180479, 1.0512922951883024),
                VoxelShapes.cuboid(0.0, 0.5027132069687307, -0.03732594349592181, 1.0, 0.5808382069687311, 0.04079905650408263),
                VoxelShapes.cuboid(0.0, 0.5339632069687307, 0.04079905650407819, 1.0, 0.6120882069687311, 0.11892405650408264),
                VoxelShapes.cuboid(0.0, 0.5977965403020641, 0.1970490565040782, 1.0, 0.6759215403020645, 0.27517405650408266),
                VoxelShapes.cuboid(0.0, 0.5668798736353974, 0.1189240565040782, 1.0, 0.6450048736353978, 0.19704905650408264),
                VoxelShapes.cuboid(0.0, 0.6297132069687308, 0.2751740565040782, 1.0, 0.7078382069687312, 0.35329905650408266),
                VoxelShapes.cuboid(0.0, 0.663296540302064, 0.3532990565040782, 1.0, 0.7414215403020644, 0.43142405650408266),
                VoxelShapes.cuboid(0.0, 0.729796540302064, 0.5095490565040782, 1.0, 0.8079215403020644, 0.5876740565040827),
                VoxelShapes.cuboid(0.0, 0.6972132069687306, 0.4314240565040782, 1.0, 0.775338206968731, 0.5095490565040827),
                VoxelShapes.cuboid(0.0, 0.7597132069687308, 0.5876740565040782, 1.0, 0.8378382069687312, 0.6657990565040827),
                VoxelShapes.cuboid(0.0, 0.7896298736353974, 0.6657990565040782, 1.0, 0.8677548736353978, 0.7439240565040827),
                VoxelShapes.cuboid(0.0, 0.8222132069687309, 0.7439240565040782, 1.0, 0.9003382069687313, 0.8220490565040827),
                VoxelShapes.cuboid(0.0, 0.8554632069687307, 0.8220490565040782, 1.0, 0.9335882069687311, 0.9001740565040827),
                VoxelShapes.cuboid(0.0, 0.8877132069687306, 0.9001740565040782, 1.0, 0.965838206968731, 0.972632389837416)
        );
    }

    private static VoxelShape createLowerInnerShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(-0.0443335416666667, 0.0027131239626707604, -0.037326434671054995, 0.9999999999999999, 0.08083812396267076, 0.040798565328945005), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(-0.044308934671055, 0.0027131239626707604, 0.0384829166666667, 0.033816065328945, 0.08083812396267076, 1.004483125), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.09499979166666661, 0.03396312396267076, 0.040798565328945005, 0.9999999999999999, 0.11208812396267076, 0.103298565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.1351664583333333, 0.06521312396267076, 0.103298565328945, 0.9999999999999999, 0.14333812396267076, 0.165798565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.193333125, 0.08646312396267064, 0.165798565328945, 0.9999999999999999, 0.16458812396267064, 0.228298565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.24799979166666664, 0.11571312396267075, 0.228298565328945, 0.9999999999999999, 0.19383812396267075, 0.290798565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.318333125, 0.14162979062933745, 0.290798565328945, 0.9999999999999999, 0.21975479062933745, 0.353298565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.380833125, 0.16654645729600415, 0.353298565328945, 0.9999999999999999, 0.24467145729600415, 0.415798565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.4366664583333333, 0.19212979062933744, 0.415798565328945, 0.9999999999999999, 0.27025479062933744, 0.478298565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.505833125, 0.22146312396267076, 0.478298565328945, 0.9999999999999999, 0.29958812396267076, 0.540798565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.568333125, 0.24471312396267075, 0.540798565328945, 0.9999999999999999, 0.32283812396267075, 0.603298565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.6308331250000001, 0.27129645729600393, 0.603298565328945, 1, 0.34942145729600393, 0.665798565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.6933331250000001, 0.2982131239626705, 0.665798565328945, 1, 0.3763381239626705, 0.728298565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.7558331250000001, 0.3265464572960036, 0.728298565328945, 1, 0.4046714572960036, 0.790798565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.8183331250000001, 0.3467964572960036, 0.790798565328945, 1, 0.4249214572960036, 0.8532985653289451), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.8808331250000001, 0.3750464572960037, 0.853298565328945, 1, 0.4531714572960037, 0.9157985653289451), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.5953123958333331, 0.27129645729600393, 0.6044721638377217, 0.6578123958333331, 0.34942145729600393, 1.0048890388377218), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.6578123958333331, 0.2982131239626705, 0.6669721638377217, 0.7203123958333331, 0.3763381239626705, 1.0048890388377218), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.7203123958333331, 0.3265464572960036, 0.727805497171055, 0.7828123958333331, 0.4046714572960036, 1.0048890388377218), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.8453123958333331, 0.3750464572960037, 0.8527221638377217, 0.9078123958333331, 0.4531714572960037, 1.0048890388377218), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.7828123958333331, 0.3467964572960036, 0.7919721638377217, 0.8453123958333331, 0.4249214572960036, 1.0048890388377218), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.5328123958333331, 0.24471312396267075, 0.5419721638377217, 0.5953123958333331, 0.32283812396267075, 1.0048890388377214), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.4703123958333334, 0.22146312396267076, 0.47947216383772173, 0.5328123958333331, 0.29958812396267076, 1.0048890388377214), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.4078123958333334, 0.19212979062933744, 0.4149721638377216, 0.4703123958333334, 0.27025479062933744, 1.0048890388377214), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.3453123958333334, 0.16654645729600415, 0.35447216383772173, 0.4078123958333334, 0.24467145729600415, 1.0048890388377214), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.2828123958333334, 0.14162979062933745, 0.29197216383772173, 0.3453123958333334, 0.21975479062933745, 1.0048890388377214), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.22031239583333317, 0.11571312396267075, 0.22655549717105483, 0.2828123958333334, 0.19383812396267075, 1.0048890388377214), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.15781239583333317, 0.08646312396267064, 0.1652221638377218, 0.22031239583333317, 0.16458812396267064, 1.0048890388377214), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.09531239583333326, 0.06521312396267076, 0.10213883050438832, 0.15781239583333317, 0.14333812396267076, 1.0048890388377214), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.03281239583333321, 0.03396312396267076, 0.04072216383772176, 0.09531239583333326, 0.11208812396267076, 1.0048890388377214), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.9078123958333331, 0.4062964572960037, 0.8839721638377217, 1.001562395833333, 0.4844214572960037, 1.0048890388377218), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape createLowerOuterShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(-0.035689287423249394, 0.42316187603732913, 0.010303813914472493, 0.04243571257675066, 0.5012868760373291, 1.054637355581139), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.04012006391447226, 0.42316187603732913, 0.9764877485855274, 1.0061202722478058, 0.5012868760373291, 1.0546127485855274), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.04243571257675066, 0.39191187603732913, 0.010303813914472493, 0.10493571257675066, 0.47003687603732913, 0.9153040222478057), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.10493571257675066, 0.36066187603732913, 0.010303813914472493, 0.16743571257675066, 0.43878687603732913, 0.875137355581139), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.16743571257675066, 0.33941187603732925, 0.010303813914472493, 0.22993571257675066, 0.41753687603732925, 0.8169706889144723), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.22993571257675066, 0.31016187603732914, 0.010303813914472493, 0.29243571257675066, 0.38828687603732914, 0.7623040222478057), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.29243571257675066, 0.28424520937066244, 0.010303813914472493, 0.35493571257675066, 0.36237020937066244, 0.6919706889144723), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.35493571257675066, 0.25932854270399575, 0.010303813914472493, 0.41743571257675066, 0.33745354270399575, 0.6294706889144723), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.41743571257675066, 0.23374520937066245, 0.010303813914472493, 0.47993571257675066, 0.31187020937066245, 0.5736373555811392), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.47993571257675066, 0.20441187603732913, 0.010303813914472493, 0.5424357125767507, 0.28253687603732913, 0.5044706889144724), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.5424357125767507, 0.18116187603732914, 0.010303813914472493, 0.6049357125767507, 0.25928687603732914, 0.44197068891447244), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.6049357125767507, 0.15457854270399596, 0.01030381391447227, 0.6674357125767507, 0.23270354270399596, 0.3794706889144722), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.6674357125767507, 0.12766187603732937, 0.01030381391447227, 0.7299357125767507, 0.20578687603732937, 0.3169706889144722), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.7299357125767507, 0.09932854270399627, 0.01030381391447227, 0.7924357125767507, 0.17745354270399627, 0.2544706889144722), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.7924357125767507, 0.07907854270399628, 0.01030381391447227, 0.8549357125767507, 0.15720354270399628, 0.19197068891447222), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.8549357125767507, 0.05082854270399617, 0.01030381391447227, 0.9174357125767507, 0.12895354270399617, 0.12947068891447222), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.6061093110855273, 0.15457854270399596, 0.35249141808113926, 1.0065261860855275, 0.23270354270399596, 0.41499141808113926), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.6686093110855273, 0.12766187603732937, 0.28999141808113926, 1.0065261860855275, 0.20578687603732937, 0.35249141808113926), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.7294426444188606, 0.09932854270399627, 0.22749141808113926, 1.0065261860855275, 0.17745354270399627, 0.28999141808113926), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.8543593110855274, 0.05082854270399617, 0.10249141808113926, 1.0065261860855275, 0.12895354270399617, 0.16499141808113926), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.7936093110855273, 0.07907854270399628, 0.16499141808113926, 1.0065261860855275, 0.15720354270399628, 0.22749141808113926), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.5436093110855273, 0.18116187603732914, 0.41499141808113926, 1.006526186085527, 0.25928687603732914, 0.47749141808113926), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.4811093110855274, 0.20441187603732913, 0.47749141808113926, 1.006526186085527, 0.28253687603732913, 0.5399914180811389), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.4166093110855273, 0.23374520937066245, 0.5399914180811389, 1.006526186085527, 0.31187020937066245, 0.6024914180811389), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.3561093110855273, 0.25932854270399575, 0.6024914180811389, 1.006526186085527, 0.33745354270399575, 0.6649914180811389), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.2936093110855273, 0.28424520937066244, 0.6649914180811389, 1.006526186085527, 0.36237020937066244, 0.7274914180811389), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.2281926444188604, 0.31016187603732914, 0.7274914180811389, 1.006526186085527, 0.38828687603732914, 0.7899914180811392), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.16685931108552737, 0.33941187603732925, 0.7899914180811392, 1.006526186085527, 0.41753687603732925, 0.8524914180811392), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.10377597775219399, 0.36066187603732913, 0.8524914180811392, 1.006526186085527, 0.43878687603732913, 0.9149914180811392), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.04235931108552732, 0.39191187603732913, 0.9149914180811392, 1.006526186085527, 0.47003687603732913, 0.9774914180811392), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.8856093110855274, 0.01957854270399617, 0.008741418081139207, 1.0065261860855275, 0.09770354270399617, 0.10249141808113926), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape createUpperInnerShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(-0.0443335416666667, 0.5027131239626708, -0.037326434671054995, 0.9999999999999999, 0.5808381239626708, 0.040798565328945005), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(-0.044308934671055, 0.5027131239626708, 0.0384829166666667, 0.033816065328945, 0.5808381239626708, 1.004483125), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.09499979166666661, 0.5339631239626708, 0.040798565328945005, 0.9999999999999999, 0.6120881239626708, 0.103298565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.1351664583333333, 0.5652131239626708, 0.103298565328945, 0.9999999999999999, 0.6433381239626708, 0.165798565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.193333125, 0.5864631239626706, 0.165798565328945, 0.9999999999999999, 0.6645881239626706, 0.228298565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.24799979166666664, 0.6157131239626707, 0.228298565328945, 0.9999999999999999, 0.6938381239626707, 0.290798565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.318333125, 0.6416297906293374, 0.290798565328945, 0.9999999999999999, 0.7197547906293374, 0.353298565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.380833125, 0.6665464572960041, 0.353298565328945, 0.9999999999999999, 0.7446714572960041, 0.415798565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.4366664583333333, 0.6921297906293374, 0.415798565328945, 0.9999999999999999, 0.7702547906293374, 0.478298565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.505833125, 0.7214631239626708, 0.478298565328945, 0.9999999999999999, 0.7995881239626708, 0.540798565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.568333125, 0.7447131239626708, 0.540798565328945, 0.9999999999999999, 0.8228381239626708, 0.603298565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.6308331250000001, 0.7712964572960039, 0.603298565328945, 1, 0.8494214572960039, 0.665798565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.6933331250000001, 0.7982131239626705, 0.665798565328945, 1, 0.8763381239626705, 0.728298565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.7558331250000001, 0.8265464572960036, 0.728298565328945, 1, 0.9046714572960036, 0.790798565328945), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.8183331250000001, 0.8467964572960036, 0.790798565328945, 1, 0.9249214572960036, 0.8532985653289451), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.8808331250000001, 0.8750464572960037, 0.853298565328945, 1, 0.9531714572960037, 0.9157985653289451), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.5953123958333331, 0.7712964572960039, 0.6044721638377217, 0.6578123958333331, 0.8494214572960039, 1.0048890388377218), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.6578123958333331, 0.7982131239626705, 0.6669721638377217, 0.7203123958333331, 0.8763381239626705, 1.0048890388377218), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.7203123958333331, 0.8265464572960036, 0.727805497171055, 0.7828123958333331, 0.9046714572960036, 1.0048890388377218), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.8453123958333331, 0.8750464572960037, 0.8527221638377217, 0.9078123958333331, 0.9531714572960037, 1.0048890388377218), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.7828123958333331, 0.8467964572960036, 0.7919721638377217, 0.8453123958333331, 0.9249214572960036, 1.0048890388377218), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.5328123958333331, 0.7447131239626708, 0.5419721638377217, 0.5953123958333331, 0.8228381239626708, 1.0048890388377214), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.4703123958333334, 0.7214631239626708, 0.47947216383772173, 0.5328123958333331, 0.7995881239626708, 1.0048890388377214), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.4078123958333334, 0.6921297906293374, 0.4149721638377216, 0.4703123958333334, 0.7702547906293374, 1.0048890388377214), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.3453123958333334, 0.6665464572960041, 0.35447216383772173, 0.4078123958333334, 0.7446714572960041, 1.0048890388377214), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.2828123958333334, 0.6416297906293374, 0.29197216383772173, 0.3453123958333334, 0.7197547906293374, 1.0048890388377214), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.22031239583333317, 0.6157131239626707, 0.22655549717105483, 0.2828123958333334, 0.6938381239626707, 1.0048890388377214), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.15781239583333317, 0.5864631239626706, 0.1652221638377218, 0.22031239583333317, 0.6645881239626706, 1.0048890388377214), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.09531239583333326, 0.5652131239626708, 0.10213883050438832, 0.15781239583333317, 0.6433381239626708, 1.0048890388377214), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.03281239583333321, 0.5339631239626708, 0.04072216383772176, 0.09531239583333326, 0.6120881239626708, 1.0048890388377214), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.9078123958333331, 0.9062964572960037, 0.9152221638377217, 1.001562395833333, 0.9844214572960037, 1.0048890388377218), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape createUpperOuterShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(-0.035689287423249394, 0.9341618760373291, 0.010303813914472493, 0.04243571257675066, 1.0122868760373291, 1.054637355581139), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.04012006391447226, 0.9341618760373291, 0.9764877485855274, 1.0061202722478058, 1.0122868760373291, 1.0546127485855274), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.04243571257675066, 0.9029118760373291, 0.010303813914472493, 0.10493571257675066, 0.9810368760373291, 0.9153040222478057), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.10493571257675066, 0.8716618760373291, 0.010303813914472493, 0.16743571257675066, 0.9497868760373291, 0.875137355581139), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.16743571257675066, 0.8504118760373293, 0.010303813914472493, 0.22993571257675066, 0.9285368760373293, 0.8169706889144723), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.22993571257675066, 0.8211618760373292, 0.010303813914472493, 0.29243571257675066, 0.8992868760373292, 0.7623040222478057), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.29243571257675066, 0.7952452093706625, 0.010303813914472493, 0.35493571257675066, 0.8733702093706625, 0.6919706889144723), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.35493571257675066, 0.7703285427039958, 0.010303813914472493, 0.41743571257675066, 0.8484535427039958, 0.6294706889144723), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.41743571257675066, 0.7447452093706625, 0.010303813914472493, 0.47993571257675066, 0.8228702093706625, 0.5736373555811392), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.47993571257675066, 0.7154118760373291, 0.010303813914472493, 0.5424357125767507, 0.7935368760373291, 0.5044706889144724), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.5424357125767507, 0.6921618760373291, 0.010303813914472493, 0.6049357125767507, 0.7702868760373291, 0.44197068891447244), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.6049357125767507, 0.665578542703996, 0.01030381391447227, 0.6674357125767507, 0.743703542703996, 0.3794706889144722), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.6674357125767507, 0.6386618760373294, 0.01030381391447227, 0.7299357125767507, 0.7167868760373294, 0.3169706889144722), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.7299357125767507, 0.6103285427039963, 0.01030381391447227, 0.7924357125767507, 0.6884535427039963, 0.2544706889144722), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.7924357125767507, 0.5900785427039963, 0.01030381391447227, 0.8549357125767507, 0.6682035427039963, 0.19197068891447222), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.8549357125767507, 0.5618285427039962, 0.01030381391447227, 0.9174357125767507, 0.6399535427039962, 0.12947068891447222), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.6061093110855273, 0.665578542703996, 0.35249141808113926, 1.0065261860855275, 0.743703542703996, 0.41499141808113926), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.6686093110855273, 0.6386618760373294, 0.28999141808113926, 1.0065261860855275, 0.7167868760373294, 0.35249141808113926), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.7294426444188606, 0.6103285427039963, 0.22749141808113926, 1.0065261860855275, 0.6884535427039963, 0.28999141808113926), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.8543593110855274, 0.5618285427039962, 0.10249141808113926, 1.0065261860855275, 0.6399535427039962, 0.16499141808113926), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.7936093110855273, 0.5900785427039963, 0.16499141808113926, 1.0065261860855275, 0.6682035427039963, 0.22749141808113926), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.5436093110855273, 0.6921618760373291, 0.41499141808113926, 1.006526186085527, 0.7702868760373291, 0.47749141808113926), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.4811093110855274, 0.7154118760373291, 0.47749141808113926, 1.006526186085527, 0.7935368760373291, 0.5399914180811389), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.4166093110855273, 0.7447452093706625, 0.5399914180811389, 1.006526186085527, 0.8228702093706625, 0.6024914180811389), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.3561093110855273, 0.7703285427039958, 0.6024914180811389, 1.006526186085527, 0.8484535427039958, 0.6649914180811389), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.2936093110855273, 0.7952452093706625, 0.6649914180811389, 1.006526186085527, 0.8733702093706625, 0.7274914180811389), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.2281926444188604, 0.8211618760373292, 0.7274914180811389, 1.006526186085527, 0.8992868760373292, 0.7899914180811392), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.16685931108552737, 0.8504118760373293, 0.7899914180811392, 1.006526186085527, 0.9285368760373293, 0.8524914180811392), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.10377597775219399, 0.8716618760373291, 0.8524914180811392, 1.006526186085527, 0.9497868760373291, 0.9149914180811392), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.04235931108552732, 0.9029118760373291, 0.9149914180811392, 1.006526186085527, 0.9810368760373291, 0.9774914180811392), BooleanBiFunction.OR);
        shape = VoxelShapes.combine(shape, VoxelShapes.cuboid(0.8856093110855274, 0.5305785427039962, 0.008741418081139207, 1.0065261860855275, 0.6087035427039962, 0.10249141808113926), BooleanBiFunction.OR);
        return shape;
    }

    private static VoxelShape flipY(VoxelShape shape) {
        final VoxelShape[] flipped = {VoxelShapes.empty()};
        shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> flipped[0] = VoxelShapes.union(
                flipped[0],
                VoxelShapes.cuboid(minX, 1.0 - maxY, minZ, maxX, 1.0 - minY, maxZ)
        ));
        return flipped[0];
    }

    private static VoxelShape createInnerFromStraight(VoxelShape straight) {
        return VoxelShapes.union(straight, rotateShapeSteps(straight, 3));
    }

    private static VoxelShape createOuterFromStraight(VoxelShape straight) {
        return VoxelShapes.combineAndSimplify(straight, rotateShapeSteps(straight, 3), BooleanBiFunction.AND);
    }

    private BlockState getStateWithShape(BlockState state, BlockView world, BlockPos pos) {
        return state.with(SHAPE, computeCornerShape(state, world, pos));
    }

    private SlopeShape computeCornerShape(BlockState state, BlockView world, BlockPos pos) {
        Direction facing = state.get(FACING);

        BlockState front = world.getBlockState(pos.offset(facing));
        if (isSameVariantSlope(front, state)) {
            Direction frontFacing = front.get(FACING);
            if (frontFacing.getAxis() != facing.getAxis()
                    && isDifferentOrientation(state, world, pos, frontFacing.getOpposite())) {
                return frontFacing == facing.rotateYCounterclockwise()
                        ? SlopeShape.INNER_LEFT
                        : SlopeShape.INNER_RIGHT;
            }
        }

        BlockState back = world.getBlockState(pos.offset(facing.getOpposite()));
        if (isSameVariantSlope(back, state)) {
            Direction backFacing = back.get(FACING);
            if (backFacing.getAxis() != facing.getAxis()
                    && isDifferentOrientation(state, world, pos, backFacing)) {
                return backFacing == facing.rotateYCounterclockwise()
                        ? SlopeShape.OUTER_LEFT
                        : SlopeShape.OUTER_RIGHT;
            }
        }

        return SlopeShape.STRAIGHT;
    }

    private boolean isDifferentOrientation(BlockState state, BlockView world, BlockPos pos, Direction direction) {
        BlockState other = world.getBlockState(pos.offset(direction));
        return !(isSameVariantSlope(other, state) && other.get(FACING) == state.get(FACING));
    }

    private boolean isSameVariantSlope(BlockState otherState, BlockState selfState) {
        if (!(otherState.getBlock() instanceof GlazingShallowSlopeBlock other)) {
            return false;
        }
        if (other.variant != this.variant) {
            return false;
        }
        return otherState.get(HALF) == selfState.get(HALF);
    }

    private static SlopeShape swapLeftRight(SlopeShape shape) {
        return switch (shape) {
            case INNER_LEFT -> SlopeShape.INNER_RIGHT;
            case INNER_RIGHT -> SlopeShape.INNER_LEFT;
            case OUTER_LEFT -> SlopeShape.OUTER_RIGHT;
            case OUTER_RIGHT -> SlopeShape.OUTER_LEFT;
            default -> shape;
        };
    }

    private static SlopeShape swapOuter(SlopeShape shape) {
        return switch (shape) {
            case OUTER_LEFT -> SlopeShape.OUTER_RIGHT;
            case OUTER_RIGHT -> SlopeShape.OUTER_LEFT;
            default -> shape;
        };
    }

    private static int stepsForFacing(Direction facing) {
        return switch (facing) {
            case SOUTH -> 0;
            case WEST -> 1;
            case NORTH -> 2;
            case EAST -> 3;
            default -> 0;
        };
    }

    private static VoxelShape rotateShapeSteps(VoxelShape shape, int steps) {
        int turns = ((steps % 4) + 4) % 4;
        VoxelShape current = shape;

        for (int i = 0; i < turns; i++) {
            final VoxelShape[] rotated = {VoxelShapes.empty()};

            current.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
                double newMinX = 1.0 - maxZ;
                double newMinZ = minX;
                double newMaxX = 1.0 - minZ;
                double newMaxZ = maxX;

                rotated[0] = VoxelShapes.union(
                        rotated[0],
                        VoxelShapes.cuboid(newMinX, minY, newMinZ, newMaxX, maxY, newMaxZ)
                );
            });

            current = rotated[0];
        }

        return current;
    }

    private static int horizontalIndex(Direction direction) {
        return switch (direction) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }
}
