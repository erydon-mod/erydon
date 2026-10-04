package com.oliver.erydon.block;

import net.minecraft.block.*;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.*;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

/** One item chooses its surface profile from the supporting cell below. */
public final class CopingBlock extends HorizontalFacingBlock implements Waterloggable {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<Surface> SURFACE = EnumProperty.of("surface", Surface.class);
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;
    public static final BooleanProperty OFFSET = BooleanProperty.of("offset");
    public static final double THICKNESS = 3.6 / 16.0;
    private static final VoxelShape[][] SHAPES = shapes();
    private static final VoxelShape[][] OFFSET_SHAPES = offsetShapes();
    private static final VoxelShape[][] PARTICLE_SHAPES = particleShapes();
    private static final VoxelShape[][] OFFSET_PARTICLE_SHAPES = offsetParticleShapes();

    public CopingBlock(Settings settings) {
        super(settings.nonOpaque().dynamicBounds());
        setDefaultState(stateManager.getDefaultState().with(FACING, Direction.EAST)
                .with(SURFACE, Surface.FLAT).with(WATERLOGGED, false).with(OFFSET, false));
    }

    public enum Surface implements StringIdentifiable {
        FLAT("flat", 0, 1, 0, 0),
        SLOPE("slope", 0, 1, 0, -1),
        SHALLOW_LOWER("shallow_lower", 0, 1, -.5, -1),
        SHALLOW_UPPER("shallow_upper", 0, 1, 0, -.5),
        STEEP_LOWER("steep_lower", 0, .5, 0, -1),
        STEEP_UPPER("steep_upper", .5, 1, 0, -1),
        DIAGONAL("diagonal", CopingHorizontalFit.DIAGONAL),
        SHALLOW_BROAD_RIGHT("shallow_broad_right", CopingHorizontalFit.BROAD_RIGHT),
        SHALLOW_BROAD_LEFT("shallow_broad_left", CopingHorizontalFit.BROAD_LEFT),
        SHALLOW_BROAD_WIDE_RIGHT("shallow_broad_wide_right", CopingHorizontalFit.BROAD_WIDE_RIGHT),
        SHALLOW_BROAD_WIDE_LEFT("shallow_broad_wide_left", CopingHorizontalFit.BROAD_WIDE_LEFT),
        SHALLOW_NARROW_RIGHT("shallow_narrow_right", CopingHorizontalFit.NARROW_RIGHT),
        SHALLOW_NARROW_LEFT("shallow_narrow_left", CopingHorizontalFit.NARROW_LEFT),
        SHALLOW_NARROW_THIN_RIGHT("shallow_narrow_thin_right", CopingHorizontalFit.NARROW_THIN_RIGHT),
        SHALLOW_NARROW_THIN_LEFT("shallow_narrow_thin_left", CopingHorizontalFit.NARROW_THIN_LEFT);

        private final String name;
        public final double start, end, high, low;
        public final CopingHorizontalFit fit;
        Surface(String name, double start, double end, double high, double low) {
            this.name = name; this.start = start; this.end = end; this.high = high; this.low = low;
            fit=CopingHorizontalFit.AXIS;
        }
        Surface(String name,CopingHorizontalFit fit) { this.name=name; this.fit=fit; start=0; end=1; high=low=0; }
        @Override public String asString() { return name; }
        public double gradient() { return (high - low) / (end - start); }
        public double height(double x) { return high - (x - start) * gradient(); }
        public boolean aligned() { return fit!=CopingHorizontalFit.AXIS; }
        public Surface authoringProfile() { return aligned() ? FLAT : this; }
        public Surface mirroredHand() {
            return switch(this) {
                case SHALLOW_BROAD_RIGHT -> SHALLOW_BROAD_LEFT; case SHALLOW_BROAD_LEFT -> SHALLOW_BROAD_RIGHT;
                case SHALLOW_BROAD_WIDE_RIGHT -> SHALLOW_BROAD_WIDE_LEFT; case SHALLOW_BROAD_WIDE_LEFT -> SHALLOW_BROAD_WIDE_RIGHT;
                case SHALLOW_NARROW_RIGHT -> SHALLOW_NARROW_LEFT; case SHALLOW_NARROW_LEFT -> SHALLOW_NARROW_RIGHT;
                case SHALLOW_NARROW_THIN_RIGHT -> SHALLOW_NARROW_THIN_LEFT; case SHALLOW_NARROW_THIN_LEFT -> SHALLOW_NARROW_THIN_RIGHT;
                default -> this;
            };
        }
    }

    public record Support(Surface surface, Direction facing) { }

    public static Support support(BlockState state, BlockView world, BlockPos pos) {
        if(state.getBlock() instanceof SlopeVerticalBlock || state.getBlock() instanceof SlopeVerticalShallowBroadBlock
                || state.getBlock() instanceof SlopeVerticalShallowNarrowBlock)
            return CopingHorizontalFit.support(state,world,pos);
        if (state.getBlock() instanceof ShallowSlopeBlock block) {
            if (state.get(ShallowSlopeBlock.HALF) == BlockHalf.TOP) return flatSupport(state, world, pos);
            if (state.get(ShallowSlopeBlock.SHAPE) != ShallowSlopeBlock.SlopeShape.STRAIGHT) return null;
            return new Support(block.variant() == ShallowSlopeBlock.Variant.LOWER
                    ? Surface.SHALLOW_LOWER : Surface.SHALLOW_UPPER, state.get(ShallowSlopeBlock.FACING));
        }
        if (state.getBlock() instanceof SlopeSteepBlock block) {
            if (state.get(SlopeSteepBlock.HALF) == BlockHalf.TOP) return flatSupport(state, world, pos);
            if (state.get(SlopeSteepBlock.SHAPE) != SlopeSteepBlock.SlopeShape.STRAIGHT) return null;
            return new Support(block.variant() == SlopeSteepBlock.Variant.LOWER
                    ? Surface.STEEP_LOWER : Surface.STEEP_UPPER, state.get(SlopeSteepBlock.FACING));
        }
        if (state.getBlock() instanceof SlopeBlock) {
            if (state.get(SlopeBlock.HALF) == BlockHalf.TOP) return flatSupport(state, world, pos);
            if (state.get(SlopeBlock.SHAPE) != SlopeBlock.SlopeShape.STRAIGHT) return null;
            return new Support(Surface.SLOPE, state.get(SlopeBlock.FACING));
        }
        return flatSupport(state, world, pos);
    }

    private static Support flatSupport(BlockState state, BlockView world, BlockPos pos) {
        return Block.isFaceFullSquare(state.getCollisionShape(world, pos), Direction.UP)
                ? new Support(Surface.FLAT, Direction.EAST) : null;
    }

    @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, SURFACE, WATERLOGGED, OFFSET);
    }
    @Override public BlockState getPlacementState(ItemPlacementContext context) {
        boolean offset = context instanceof com.oliver.erydon.item.CopingItem.Placement placement && placement.offset;
        BlockPos below = context instanceof com.oliver.erydon.item.CopingItem.Placement placement
                ? placement.supportPos : context.getBlockPos().down();
        boolean waterlogged=context.getWorld().getFluidState(context.getBlockPos()).getFluid() == Fluids.WATER;
        BlockState fitted=forSupport(context.getWorld().getBlockState(below),context.getWorld(),below,waterlogged,offset);
        // Ordinary placement can build a free-standing cap; explicit slope placement
        // still rejects non-planar corners that have no matching profile.
        return fitted != null || context instanceof com.oliver.erydon.item.CopingItem.Placement
                ? fitted : getDefaultState().with(WATERLOGGED,waterlogged);
    }
    public BlockState forSupport(BlockState supportState,BlockView world,BlockPos below,boolean waterlogged,boolean offset) {
        Support support = support(supportState, world, below);
        return support == null ? null : getDefaultState().with(SURFACE, support.surface()).with(FACING, support.facing()).with(OFFSET, offset)
                .with(WATERLOGGED,waterlogged);
    }
    @Override public boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        return true;
    }
    @Override public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighbour,
                                                         WorldAccess world, BlockPos pos, BlockPos neighbourPos) {
        if (state.get(WATERLOGGED)) world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
        if (direction == Direction.DOWN || state.get(OFFSET)) {
            BlockPos below = supportPos(state,pos);
            Support support = support(world.getBlockState(below), world, below);
            if (support == null || (state.get(OFFSET) && (support.surface() != Surface.STEEP_UPPER
                    || support.facing() != state.get(FACING)))) return state;
            return state.with(SURFACE, support.surface()).with(FACING, support.facing());
        }
        return state;
    }
    public static BlockPos supportPos(BlockState state,BlockPos pos) {
        return state.get(OFFSET) && !state.get(SURFACE).aligned() ? pos.down().offset(state.get(FACING).getOpposite()) : pos.down();
    }

    /** Optional family integration can read this once at placement without a client model. */
    public record AttachmentPose(double centreX,double centreZ,double yawRadians,double topCentreY,
                                 double gradientX,double gradientZ,double runLength,double width) { }
    public static AttachmentPose attachmentPose(BlockState state) {
        Surface profile=state.get(SURFACE);
        Direction facing=state.get(FACING);
        var pose=profile.fit.pose(facing);
        double g=profile.gradient();
        double normalX=g/Math.sqrt(1+g*g);
        double midpoint=(profile.start+profile.end)/2;
        double x=midpoint+normalX*THICKNESS, z=.5;
        if(profile.aligned()) { x=pose.centreX(); z=pose.centreZ(); }
        else for(int turn=0;turn<CopingHorizontalFit.quarter(facing);turn++) { double next=1-z; z=x; x=next; }
        if(state.get(OFFSET) && !profile.aligned()) { x-=facing.getOffsetX(); z-=facing.getOffsetZ(); }
        double top=profile.height(midpoint)+THICKNESS/Math.sqrt(1+g*g);
        return new AttachmentPose(x,z,pose.yawRadians(),top,-g*facing.getOffsetX(),-g*facing.getOffsetZ(),
                profile.aligned() ? pose.runLength() : profile.end-profile.start,pose.width());
    }
    @Override public FluidState getFluidState(BlockState state) {
        return state.get(WATERLOGGED) ? Fluids.WATER.getStill(false) : super.getFluidState(state);
    }
    @Override public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }
    @Override public BlockState mirror(BlockState state, BlockMirror mirror) {
        if(state.get(SURFACE).aligned() && mirror!=BlockMirror.NONE) {
            boolean handed=state.get(SURFACE)!=Surface.DIAGONAL;
            return state.with(SURFACE,state.get(SURFACE).mirroredHand()).with(FACING,
                    CopingHorizontalFit.mirrorFacing(state.get(FACING),mirror==BlockMirror.LEFT_RIGHT,handed));
        }
        return rotate(state, mirror.getRotation(state.get(FACING)));
    }
    @Override public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        Surface surface=state.get(SURFACE);
        if(world!=null && (surface.aligned() || (surface==Surface.FLAT && CopingConnections.hasAlignedNeighbour(world,pos)))) {
            Direction facing=CopingConnections.facing(world,state,pos);
            var joins=CopingConnections.resolve(world,state,pos,facing);
            if(!joins.horizontal().isEmpty()) return CopingHorizontalShape.shape(surface,facing,joins);
        }
        return (state.get(OFFSET) ? OFFSET_SHAPES : SHAPES)
                [state.get(SURFACE).ordinal()][state.get(FACING).getHorizontal()];
    }
    @Override public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getOutlineShape(state, world, pos, context);
    }
    @Override public VoxelShape getCullingShape(BlockState state, BlockView world, BlockPos pos) {
        return VoxelShapes.empty();
    }

    /** One box bounds vanilla debris to at most 64 particles, regardless of slope detail. */
    public VoxelShape getBreakParticleShape(BlockState state) {
        return (state.get(OFFSET) ? OFFSET_PARTICLE_SHAPES : PARTICLE_SHAPES)
                [state.get(SURFACE).ordinal()][state.get(FACING).getHorizontal()];
    }

    private static VoxelShape[][] particleShapes() {
        VoxelShape[][] result=new VoxelShape[Surface.values().length][4];
        for (Surface surface:Surface.values()) for (Direction facing:Direction.Type.HORIZONTAL) {
            var box=SHAPES[surface.ordinal()][facing.getHorizontal()].getBoundingBox();
            if(surface.aligned()) {
                var pose=surface.fit.pose(facing);
                result[surface.ordinal()][facing.getHorizontal()]=VoxelShapes.cuboid(
                        pose.centreX()-.5,box.minY,pose.centreZ()-.5,pose.centreX()+.5,box.maxY,pose.centreZ()+.5);
            } else result[surface.ordinal()][facing.getHorizontal()]=VoxelShapes.cuboid(box);
        }
        return result;
    }

    private static VoxelShape[][] offsetParticleShapes() {
        VoxelShape[][] result=new VoxelShape[Surface.values().length][4];
        for(Surface surface:Surface.values()) for(Direction facing:Direction.Type.HORIZONTAL)
            result[surface.ordinal()][facing.getHorizontal()]=PARTICLE_SHAPES[surface.ordinal()][facing.getHorizontal()]
                    .offset(surface.aligned() ? 0 : -facing.getOffsetX(),0,surface.aligned() ? 0 : -facing.getOffsetZ());
        return result;
    }

    private static VoxelShape[][] offsetShapes() {
        VoxelShape[][] result = new VoxelShape[Surface.values().length][4];
        for (Surface surface : Surface.values()) for (Direction facing : Direction.Type.HORIZONTAL) {
            if(surface.aligned()) {
                result[surface.ordinal()][facing.getHorizontal()]=SHAPES[surface.ordinal()][facing.getHorizontal()];
                continue;
            }
            result[surface.ordinal()][facing.getHorizontal()] = SHAPES[surface.ordinal()][facing.getHorizontal()]
                    .offset(-facing.getOffsetX(),0,-facing.getOffsetZ());
        }
        return result;
    }

    private static VoxelShape[][] shapes() {
        VoxelShape[][] result = new VoxelShape[Surface.values().length][4];
        for (Surface surface : Surface.values()) for (Direction facing : Direction.Type.HORIZONTAL) {
            if(surface.aligned()) {
                result[surface.ordinal()][facing.getHorizontal()]=alignedShape(surface.fit.pose(facing));
                continue;
            }
            VoxelShape shape = VoxelShapes.empty();
            double gradient = surface.gradient(), length = Math.sqrt(1 + gradient * gradient);
            double normalX = gradient / length, normalY = 1 / length;
            // 1/32-cell sampling follows the support slopes' existing collision resolution.
            for (int step = 0; step < 40; step++) {
                double a = surface.start + (surface.end - surface.start) * (step - 4) / 32.0;
                double b = surface.start + (surface.end - surface.start) * (step - 3) / 32.0;
                double x1 = a, x2 = b + normalX * THICKNESS;
                double y1 = surface.height(b), y2 = surface.height(a) + normalY * THICKNESS;
                double z1 = -.125, z2 = 1.125;
                VoxelShape box = switch (facing) {
                    case SOUTH -> VoxelShapes.cuboid(1-z2, y1, x1, 1-z1, y2, x2);
                    case WEST -> VoxelShapes.cuboid(1-x2, y1, 1-z2, 1-x1, y2, 1-z1);
                    case NORTH -> VoxelShapes.cuboid(z1, y1, 1-x2, z2, y2, 1-x1);
                    default -> VoxelShapes.cuboid(x1, y1, z1, x2, y2, z2);
                };
                shape = VoxelShapes.union(shape, box);
            }
            result[surface.ordinal()][facing.getHorizontal()] = shape.simplify();
        }
        return result;
    }

    private static VoxelShape alignedShape(CopingHorizontalFit.Pose pose) {
        double cosine=Math.cos(pose.yawRadians()),sine=Math.sin(pose.yawRadians());
        double run=pose.runLength()/2+.125,cross=pose.width()/2+.125;
        double zRadius=Math.abs(sine)*run+Math.abs(cosine)*cross;
        double step=1/32.0;
        int first=(int)Math.floor((pose.centreZ()-zRadius)/step),last=(int)Math.ceil((pose.centreZ()+zRadius)/step);
        VoxelShape shape=VoxelShapes.empty();
        for(int row=first;row<last;row++) {
            double z=(row+.5)*step-pose.centreZ();
            double a=(-run-sine*z)/cosine,b=(run-sine*z)/cosine;
            double c=(-cross-cosine*z)/-sine,d=(cross-cosine*z)/-sine;
            double low=Math.max(Math.min(a,b),Math.min(c,d)),high=Math.min(Math.max(a,b),Math.max(c,d));
            if(low>=high) continue;
            double margin=step/2*Math.max(Math.abs(sine/cosine),Math.abs(cosine/sine));
            shape=VoxelShapes.union(shape,VoxelShapes.cuboid(pose.centreX()+low-margin,0,row*step,
                    pose.centreX()+high+margin,THICKNESS,(row+1)*step));
        }
        return shape.simplify();
    }
}
