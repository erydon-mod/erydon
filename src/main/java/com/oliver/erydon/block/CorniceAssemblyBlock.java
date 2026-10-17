package com.oliver.erydon.block;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.oliver.erydon.block.entity.CorniceAssemblyBlockEntity;
import com.oliver.erydon.util.ClusterRecalcSafety;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.Waterloggable;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.loot.context.LootContextParameterSet;
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.server.world.ServerWorld;
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
import net.minecraft.util.math.random.Random;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.ArrayVoxelShape;
import net.minecraft.util.shape.BitSetVoxelSet;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.Arrays;

/** A shared cell holds independent paid cornices and a cover without occupying their supports. */
public final class CorniceAssemblyBlock extends Block implements BlockEntityProvider, Waterloggable, ClusterRebuildableBlock {
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final BooleanProperty MIRRORED = BooleanProperty.of("mirrored");
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;
    public static final EnumProperty<Light> LIGHT = EnumProperty.of("light", Light.class);
    // Includes retained component shapes in the weight, so evicting the source raster cache still frees its budget.
    private static final Cache<List<VoxelShape>, VoxelShape> SHAPES = CacheBuilder.newBuilder().maximumWeight(8 * 1024 * 1024)
            .weigher((List<VoxelShape> key, VoxelShape value) -> {
                long bytes = 256L + CorniceInteractionShapes.estimatedBytes(value);
                for (VoxelShape part : key) bytes += CorniceInteractionShapes.estimatedBytes(part);
                return (int) Math.min(Integer.MAX_VALUE, bytes);
            }).build();

    public CorniceAssemblyBlock(Settings settings) {
        super(settings.nonOpaque().dynamicBounds().luminance(state -> state.get(LIGHT).level));
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH)
                .with(MIRRORED, false).with(WATERLOGGED, false).with(LIGHT, Light.OFF));
    }

    @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, MIRRORED, WATERLOGGED, LIGHT);
    }

    @Override public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new CorniceAssemblyBlockEntity(pos, state);
    }

    @Override public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override public BlockState mirror(BlockState state, BlockMirror mirror) {
        return mirror == BlockMirror.NONE ? state : state.with(FACING, mirror.apply(state.get(FACING)))
                .with(MIRRORED, !state.get(MIRRORED));
    }

    @Override public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (world instanceof ServerWorld server && !server.getServer().isOnThread()) return VoxelShapes.empty();
        List<VoxelShape> shapes = new ArrayList<>();
        for (BlockState part : CorniceAssembly.contents(world, pos).parts()) {
            shapes.add(part.getOutlineShape(world, pos, context));
        }
        return combined(shapes);
    }

    @Override public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (world instanceof ServerWorld server && !server.getServer().isOnThread()) return VoxelShapes.empty();
        List<VoxelShape> shapes = new ArrayList<>();
        for (BlockState part : CorniceAssembly.contents(world, pos).cornices()) {
            shapes.add(part.getCollisionShape(world, pos, context));
        }
        return combined(shapes);
    }

    static VoxelShape combined(List<VoxelShape> shapes) {
        if (shapes.isEmpty()) return VoxelShapes.empty();
        if (shapes.size() == 1) return shapes.get(0);
        List<VoxelShape> key = List.copyOf(shapes);
        VoxelShape cached = SHAPES.getIfPresent(key);
        if (cached != null) return cached;
        List<Box> boxes = new ArrayList<>();
        TreeSet<Double> xValues = new TreeSet<>(), yValues = new TreeSet<>(), zValues = new TreeSet<>();
        for (VoxelShape shape : shapes) for (Box box : shape.getBoundingBoxes()) {
            boxes.add(box);
            xValues.add(coordinate(box.minX)); xValues.add(coordinate(box.maxX));
            yValues.add(coordinate(box.minY)); yValues.add(coordinate(box.maxY));
            zValues.add(coordinate(box.minZ)); zValues.add(coordinate(box.maxZ));
        }
        if (boxes.isEmpty()) return VoxelShapes.empty();
        double[] x = xValues.stream().mapToDouble(Double::doubleValue).toArray();
        double[] y = yValues.stream().mapToDouble(Double::doubleValue).toArray();
        double[] z = zValues.stream().mapToDouble(Double::doubleValue).toArray();
        BitSetVoxelSet cells = new BitSetVoxelSet(x.length - 1, y.length - 1, z.length - 1);
        for (Box box : boxes) {
            int x0 = Arrays.binarySearch(x, coordinate(box.minX)), x1 = Arrays.binarySearch(x, coordinate(box.maxX));
            int y0 = Arrays.binarySearch(y, coordinate(box.minY)), y1 = Arrays.binarySearch(y, coordinate(box.maxY));
            int z0 = Arrays.binarySearch(z, coordinate(box.minZ)), z1 = Arrays.binarySearch(z, coordinate(box.maxZ));
            for (int ix = x0; ix < x1; ix++) for (int iy = y0; iy < y1; iy++) for (int iz = z0; iz < z1; iz++) {
                cells.set(ix, iy, iz);
            }
        }
        // One exact grid keeps the paper-thin cover and native authored planes.
        // Repeated union/simplify otherwise rebuilds these same hundreds of boxes.
        VoxelShape result = new CombinedShape(cells, x, y, z);
        SHAPES.put(key, result);
        return result;
    }

    private static double coordinate(double value) { return value == 0 ? 0 : value; }

    static final class CombinedShape extends ArrayVoxelShape {
        final int estimatedBytes;
        private final List<Box> boxes;

        private CombinedShape(BitSetVoxelSet cells, double[] x, double[] y, double[] z) {
            super(cells, x, y, z);
            boxes = List.copyOf(super.getBoundingBoxes());
            estimatedBytes = (int) Math.min(Integer.MAX_VALUE, 512L + 8L * (x.length + y.length + z.length)
                    + ((long) (x.length - 1) * (y.length - 1) * (z.length - 1) + 7) / 8 + 80L * boxes.size());
        }

        @Override public List<Box> getBoundingBoxes() { return boxes; }
    }

    @Override public int getOpacity(BlockState state, BlockView world, BlockPos pos) { return 0; }
    @Override public boolean isTransparent(BlockState state, BlockView world, BlockPos pos) { return true; }
    @Override public VoxelShape getCullingShape(BlockState state, BlockView world, BlockPos pos) { return VoxelShapes.empty(); }
    @Override public BlockRenderType getRenderType(BlockState state) { return BlockRenderType.MODEL; }

    @Override public ItemStack getPickStack(BlockView world, BlockPos pos, BlockState state) {
        List<BlockState> parts = CorniceAssembly.contents(world, pos).parts();
        return parts.isEmpty() ? ItemStack.EMPTY : CorniceAssembly.stackFor(parts.get(0));
    }

    @Override public float calcBlockBreakingDelta(BlockState state, PlayerEntity player, BlockView world, BlockPos pos) {
        var start = player.getEyePos();
        int index = CorniceAssembly.partIndexAt(world, pos, start, start.add(player.getRotationVec(1).multiply(6)));
        if (index < 0) return 0;
        BlockState part = CorniceAssembly.contents(world, pos).parts().get(index);
        return part.calcBlockBreakingDelta(player, world, pos);
    }

    @Override public void onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        var start = player.getEyePos();
        int index = CorniceAssembly.partIndexAt(world, pos, start, start.add(player.getRotationVec(1).multiply(6)));
        if (index < 0) return;
        BlockState part = CorniceAssembly.contents(world, pos).parts().get(index);
        if (!player.getMainHandStack().getItem().canMine(part, world, pos, player)) return;
        part.getBlock().onBreak(world, pos, part, player);
    }

    @Override public FluidState getFluidState(BlockState state) {
        return state.get(WATERLOGGED) ? Fluids.WATER.getStill(false) : super.getFluidState(state);
    }

    @Override public BlockState getStateForNeighborUpdate(BlockState state, Direction side, BlockState neighbor,
                                                         WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (state.get(WATERLOGGED)) world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
        if (!world.isClient()) world.scheduleBlockTick(pos, this, 1);
        return state;
    }

    @Override public void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        reconcile(world, pos);
    }

    private static void reconcile(World world, BlockPos pos) {
        CorniceAssembly.Contents contents = CorniceAssembly.contents(world, pos);
        List<BlockState> retained = new ArrayList<>();
        List<BlockState> removed = new ArrayList<>();
        BlockState cover = contents.cover();
        for (BlockState part : contents.cornices()) {
            if (CorniceAssembly.supported(world, pos, part)) retained.add(part);
            else removed.add(part);
        }
        if (cover != null && !CorniceAssembly.supported(world, pos, cover)) {
            removed.add(cover);
            cover = null;
        }
        CorniceAssembly.Contents remaining = new CorniceAssembly.Contents(retained, cover);
        boolean ordinary = remaining.size() == 1 && (remaining.cover() != null || !world.getFluidState(pos).isOf(Fluids.WATER));
        if (remaining.isEmpty() || ordinary || !remaining.equals(contents)) {
            if (CorniceAssembly.replace(world, pos, remaining)) {
                for (BlockState part : removed) dropStack(world, pos, new ItemStack(part.getBlock()));
            }
        }
        else {
            CorniceAssembly.refreshCover(world, pos, contents.cover());
            CorniceAssembly.redraw(world, pos);
        }
    }

    @Override public ClusterRecalcResult recalcCluster(World world, BlockPos seed) {
        if (world.isClient || world.getBlockState(seed).getBlock() != this) return ClusterRecalcResult.none();
        Set<BlockPos> affected = Set.of(seed.toImmutable());
        if (!ClusterRecalcSafety.claim(seed)) {
            ClusterRecalcResult unsafe = ClusterRecalcSafety.unsafeResult(affected);
            return unsafe == null ? ClusterRecalcResult.none() : unsafe;
        }
        ClusterRecalcResult unsafe = ClusterRecalcSafety.unsafeResult(affected);
        if (unsafe != null) return unsafe;
        // The cell is the only assembly member. Its bounded redraw/reciprocity
        // dependencies must also be loaded before any paid part is changed.
        if (world instanceof ServerWorld server) {
            // Cover topology can update one neighbouring carrier, whose
            // reciprocal redraw reaches four more cells. The shared guard
            // prevents that neighbour from starting another cover cascade.
            for (int x = Math.floorDiv(seed.getX() - 5, 16); x <= Math.floorDiv(seed.getX() + 5, 16); x++) {
                for (int z = Math.floorDiv(seed.getZ() - 5, 16); z <= Math.floorDiv(seed.getZ() + 5, 16); z++) {
                    if (!server.isChunkLoaded(new BlockPos(x << 4, seed.getY(), z << 4))) {
                        return new ClusterRecalcResult(affected, RecalcStatus.UNLOADED_EDGE);
                    }
                }
            }
        }
        reconcile(world, seed);
        CorniceBlock.refreshAroundAssembly(world, seed);
        return new ClusterRecalcResult(affected, true);
    }

    @Override public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState replacement, boolean moved) {
        boolean removed = state.getBlock() != replacement.getBlock();
        BlockState oldCover = null;
        // The world already exposes the replacement state here, but the old
        // entity still owns the cover plane until super removes that entity.
        if (!world.isClient && removed && world.getBlockEntity(pos) instanceof CorniceAssemblyBlockEntity entity) {
            oldCover = entity.worldContents(state).cover();
        }
        super.onStateReplaced(state, world, pos, replacement, moved);
        if (!world.isClient && removed) {
            if (oldCover != null) CorniceAssembly.refreshCover(world, pos, oldCover);
            CorniceAssembly.redraw(world, pos);
        }
    }

    @Override public List<ItemStack> getDroppedStacks(BlockState state, LootContextParameterSet.Builder builder) {
        if (!(builder.getOptional(LootContextParameters.BLOCK_ENTITY) instanceof CorniceAssemblyBlockEntity entity)) return List.of();
        return entity.worldContents(state).parts().stream().map(part -> new ItemStack(part.getBlock())).toList();
    }

    public enum Light implements StringIdentifiable {
        OFF("off", 0), DIM("low", 13), BRIGHT("bright", 15);
        private final String id;
        public final int level;
        Light(String id, int level) { this.id = id; this.level = level; }
        @Override public String asString() { return id; }
        public static Light of(int level) { return level == 15 ? BRIGHT : level == 13 ? DIM : OFF; }
    }
}
