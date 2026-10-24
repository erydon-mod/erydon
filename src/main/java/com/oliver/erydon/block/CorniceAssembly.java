package com.oliver.erydon.block;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.oliver.erydon.block.entity.CorniceAssemblyBlockEntity;
import com.oliver.erydon.block.entity.ModBlockEntities;
import com.oliver.erydon.util.ClusterRecalcSafety;
import net.fabricmc.fabric.api.rendering.data.v1.RenderAttachedBlockView;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.state.property.Property;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Paid, cell-local parts. Geometry and neighbouring joins are derived, never saved as extra items. */
public final class CorniceAssembly {
    public static final int MAX_CORNICES = 4;
    public static final Contents EMPTY = new Contents(List.of(), null);
    private static final Cache<BlockState, Contents> SINGLE = CacheBuilder.newBuilder().maximumSize(1024).build();
    private static final ThreadLocal<Boolean> REFRESHING_COVER = ThreadLocal.withInitial(() -> false);

    private CorniceAssembly() { }

    public static final class Contents {
        private final List<BlockState> cornices;
        private final @Nullable BlockState cover;
        private final List<BlockState> parts;

        public Contents(List<BlockState> cornices, @Nullable BlockState cover) {
            this.cornices = List.copyOf(cornices);
            this.cover = cover;
            if (cornices.size() > MAX_CORNICES || cornices.stream().anyMatch(s -> !(s.getBlock() instanceof CorniceBlock))
                    || cover != null && !(cover.getBlock() instanceof CoverBlock)) {
                throw new IllegalArgumentException("Invalid cornice assembly parts");
            }
            if (cover == null) parts = this.cornices;
            else {
                List<BlockState> all = new ArrayList<>(this.cornices);
                all.add(cover);
                parts = List.copyOf(all);
            }
        }

        public List<BlockState> cornices() { return cornices; }
        public @Nullable BlockState cover() { return cover; }
        public List<BlockState> parts() { return parts; }

        public boolean isEmpty() { return cornices.isEmpty() && cover == null; }
        public int size() { return cornices.size() + (cover == null ? 0 : 1); }
        @Override public boolean equals(Object other) {
            return other instanceof Contents contents && cornices.equals(contents.cornices)
                    && java.util.Objects.equals(cover, contents.cover);
        }
        @Override public int hashCode() { return 31 * cornices.hashCode() + java.util.Objects.hashCode(cover); }
        @Override public String toString() { return "Contents[cornices=" + cornices + ", cover=" + cover + "]"; }
    }

    /** Render workers read the immutable attachment, rather than a mutable live entity. */
    public static Contents contents(BlockView world, BlockPos pos) {
        return contents(world, pos, world.getBlockState(pos));
    }

    /** Reuse a caller's cached state; a part-render proxy can still expose its shared immutable attachment. */
    public static Contents contents(BlockView world, BlockPos pos, BlockState state) {
        if ((state.getBlock() instanceof CorniceAssemblyBlock || state.getBlock() instanceof CorniceBlock || state.getBlock() instanceof CoverBlock)
                && !(world instanceof World) && world instanceof RenderAttachedBlockView attached) {
            Object attachment = attached.getBlockEntityRenderAttachment(pos);
            if (attachment instanceof Contents snapshot) return snapshot;
            // A render snapshot may contain the carrier state before its entity
            // payload. Never fall through to a mutable entity on that worker.
            if (state.getBlock() instanceof CorniceAssemblyBlock) return EMPTY;
        }
        if (state.getBlock() instanceof CorniceAssemblyBlock) {
            if (world.getBlockEntity(pos) instanceof CorniceAssemblyBlockEntity entity) return entity.worldContents(entity.getCachedState());
            return EMPTY;
        }
        if (!(state.getBlock() instanceof CorniceBlock || state.getBlock() instanceof CoverBlock)) return EMPTY;
        Contents cached = SINGLE.getIfPresent(state);
        if (cached != null) return cached;
        Contents single = state.getBlock() instanceof CorniceBlock ? new Contents(List.of(state), null) : new Contents(List.of(), state);
        SINGLE.put(state, single);
        return single;
    }

    public static boolean isOwner(BlockState state) {
        return state.getBlock() instanceof CorniceBlock || state.getBlock() instanceof CorniceAssemblyBlock;
    }

    public static boolean hasAngled(BlockView world, BlockPos pos) {
        return contents(world, pos).cornices.stream().anyMatch(s -> s.get(CorniceBlock.SHAPE).isAngled());
    }

    public static boolean canAdd(BlockView world, BlockPos pos, BlockState part) {
        BlockState owner = world.getBlockState(pos);
        if (!isOwner(owner) && !(owner.getBlock() instanceof CoverBlock) && !owner.isReplaceable()) return false;
        if (!supported(world, pos, part)) return false;
        Contents current = contents(world, pos);
        if (part.getBlock() instanceof CoverBlock) return current.cover == null;
        if (!(part.getBlock() instanceof CorniceBlock) || current.cornices.size() >= MAX_CORNICES) return false;
        for (BlockState other : current.cornices) if (sameAttachment(part, other, pos, pos)) return false;
        if (part.get(CorniceBlock.SHAPE).isAngled()) {
            BlockPos support = CorniceSlopePlacement.supportPos(part, pos);
            for (Direction side : Direction.Type.HORIZONTAL) {
                BlockPos alternative = support.offset(side);
                if (alternative.equals(pos)) continue;
                for (BlockState other : contents(world, alternative).cornices) {
                    if (sameAttachment(part, other, pos, alternative)) return false;
                }
            }
        }
        return true;
    }

    /** Material-independent support identity prevents duplicate mouldings across the two front cells. */
    public static boolean sameAttachment(BlockState a, BlockState b, BlockPos aOwner, BlockPos bOwner) {
        if (a.get(CorniceBlock.SHAPE).isAngled() || b.get(CorniceBlock.SHAPE).isAngled()) {
            if (!a.get(CorniceBlock.SHAPE).isAngled() || !b.get(CorniceBlock.SHAPE).isAngled()) return false;
            if (!CorniceSlopePlacement.supportPos(a, aOwner).equals(CorniceSlopePlacement.supportPos(b, bOwner))) return false;
            CorniceRun first = CorniceSlopePlacement.run(a).translated(aOwner.getX(), aOwner.getZ());
            CorniceRun second = CorniceSlopePlacement.run(b).translated(bOwner.getX(), bOwner.getZ());
            return Math.hypot(first.a().x() - second.a().x(), first.a().z() - second.a().z()) < .000001
                    && Math.hypot(first.b().x() - second.b().x(), first.b().z() - second.b().z()) < .000001;
        }
        if (!aOwner.equals(bOwner)) return false;
        for (Direction side : walls(a)) if (walls(b).contains(side)) return true;
        return false;
    }

    private static List<Direction> walls(BlockState state) {
        Direction facing = state.get(CorniceBlock.FACING);
        return state.get(CorniceBlock.SHAPE) == CorniceBlock.CorniceShape.STRAIGHT ? List.of(facing)
                : List.of(facing, facing.rotateYCounterclockwise());
    }

    public static boolean supported(BlockView world, BlockPos pos, BlockState part) {
        if (part.getBlock() instanceof CorniceBlock cornice) return cornice.isSupported(part, world, pos);
        if (part.getBlock() instanceof CoverBlock) {
            Direction attach = part.get(CoverBlock.ATTACH);
            BlockPos support = pos.offset(attach);
            BlockState state = world.getBlockState(support);
            return !isOwner(state) && state.isSideSolidFullSquare(EmptyBlockView.INSTANCE, support, attach.getOpposite());
        }
        return false;
    }

    public static boolean add(World world, BlockPos pos, BlockState part) {
        if (world.isClient || !canAdd(world, pos, part)) return false;
        Contents current = contents(world, pos);
        List<BlockState> cornices = new ArrayList<>(current.cornices);
        BlockState cover = current.cover;
        if (part.getBlock() instanceof CoverBlock) cover = part; else cornices.add(part);
        return replace(world, pos, new Contents(cornices, cover));
    }

    public static boolean replacePart(World world, BlockPos pos, int index, BlockState part) {
        Contents current = contents(world, pos);
        if (index < 0 || index >= current.size() || !supported(world, pos, part)) return false;
        List<BlockState> cornices = new ArrayList<>(current.cornices);
        if (index < cornices.size()) {
            if (!(part.getBlock() instanceof CorniceBlock)) return false;
            for (int other = 0; other < cornices.size(); other++) {
                if (other != index && sameAttachment(part, cornices.get(other), pos, pos)) return false;
            }
            if (part.get(CorniceBlock.SHAPE).isAngled()) {
                BlockPos support = CorniceSlopePlacement.supportPos(part, pos);
                for (Direction side : Direction.Type.HORIZONTAL) {
                    BlockPos alternative = support.offset(side);
                    if (alternative.equals(pos)) continue;
                    for (BlockState other : contents(world, alternative).cornices) {
                        if (sameAttachment(part, other, pos, alternative)) return false;
                    }
                }
            }
            cornices.set(index, part);
        } else if (!(part.getBlock() instanceof CoverBlock)) return false;
        return replace(world, pos, new Contents(cornices, index == cornices.size() ? part : current.cover));
    }

    public static @Nullable BlockState removePart(World world, BlockPos pos, int index) {
        Contents current = contents(world, pos);
        if (world.isClient || index < 0 || index >= current.size()) return null;
        List<BlockState> cornices = new ArrayList<>(current.cornices);
        BlockState removed = index < cornices.size() ? cornices.remove(index) : current.cover;
        return replace(world, pos, new Contents(cornices, index < current.cornices.size() ? current.cover : null)) ? removed : null;
    }

    /** Contents are installed before neighbours are notified; no observer sees a half-converted owner. */
    public static boolean replace(World world, BlockPos pos, Contents desired) {
        if (world.isClient) return false;
        BlockState previous = world.getBlockState(pos);
        BlockState previousCover = contents(world, pos).cover;
        boolean water = world.getFluidState(pos).isOf(Fluids.WATER);
        if (desired.isEmpty()) {
            boolean changed = world.setBlockState(pos, (water ? Blocks.WATER : Blocks.AIR).getDefaultState(), ClusterRecalcSafety.updateFlags(Block.NOTIFY_ALL));
            refreshCover(world, pos, previousCover);
            return changed;
        }
        if (desired.size() == 1 && (desired.cover != null || !water)) {
            BlockState single = desired.parts().get(0);
            if (single.getBlock() instanceof CoverBlock) single = single.with(CoverBlock.WATERLOGGED, water);
            boolean changed = world.setBlockState(pos, single, ClusterRecalcSafety.updateFlags(Block.NOTIFY_ALL));
            refreshCover(world, pos, previousCover);
            redraw(world, pos);
            return changed || previous == single;
        }
        CorniceAssemblyBlock carrier = ModBlockEntities.corniceAssemblyBlock();
        BlockState state = previous.getBlock() instanceof CorniceAssemblyBlock ? previous : carrier.getDefaultState();
        state = state.with(CorniceAssemblyBlock.WATERLOGGED, water)
                .with(CorniceAssemblyBlock.LIGHT, CorniceAssemblyBlock.Light.of(desired.cover == null ? 0 : CoverBlock.luminance(desired.cover)));
        // Listener-only conversion prevents support callbacks observing an empty new entity.
        if (previous != state && !world.setBlockState(pos, state, Block.NOTIFY_LISTENERS)) return false;
        if (!(world.getBlockEntity(pos) instanceof CorniceAssemblyBlockEntity entity)) {
            throw new IllegalStateException("Cornice assembly entity missing after conversion at " + pos);
        }
        entity.setContents(desired);
        CorniceBlock.refreshAroundAssembly(world, pos);
        if (!ClusterRecalcSafety.isActive()) world.updateNeighbors(pos, state.getBlock());
        refreshCover(world, pos, previousCover);
        redraw(world, pos);
        return true;
    }

    static void refreshCover(World world, BlockPos pos, @Nullable BlockState previousCover) {
        if (REFRESHING_COVER.get()) return;
        REFRESHING_COVER.set(true);
        try {
            CoverBlock.refreshSharedCover(world, pos);
            BlockState currentCover = contents(world, pos).cover;
            if (previousCover != null && (currentCover == null || previousCover.getBlock() != currentCover.getBlock()
                    || previousCover.get(CoverBlock.ATTACH) != currentCover.get(CoverBlock.ATTACH))) {
                ((CoverBlock) previousCover.getBlock()).updateNeighboursInPlane(world, pos, previousCover.get(CoverBlock.ATTACH));
            }
        } finally { REFRESHING_COVER.remove(); }
    }

    public static int partIndexAt(BlockView world, BlockPos pos, Vec3d start, Vec3d end) {
        List<BlockState> parts = contents(world, pos).parts();
        int best = -1;
        double distance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < parts.size(); index++) {
            var hit = parts.get(index).getOutlineShape(world, pos, ShapeContext.absent()).raycast(start, end, pos);
            if (hit != null && hit.getPos().squaredDistanceTo(start) < distance) {
                best = index;
                distance = hit.getPos().squaredDistanceTo(start);
            }
        }
        return best;
    }

    public static ItemStack stackFor(BlockState part) {
        ItemStack stack = new ItemStack(part.getBlock());
        NbtCompound properties = new NbtCompound();
        for (var entry : part.getEntries().entrySet()) putProperty(properties, entry.getKey(), entry.getValue());
        stack.getOrCreateNbt().put("BlockStateTag", properties);
        return stack;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void putProperty(NbtCompound nbt, Property property, Comparable value) {
        nbt.putString(property.getName(), property.name(value));
    }

    public static Contents toWorld(Contents local, BlockState basis) {
        if (basis.get(CorniceAssemblyBlock.FACING) == Direction.NORTH && !basis.get(CorniceAssemblyBlock.MIRRORED)) return local;
        return transform(local, part -> {
            if (basis.get(CorniceAssemblyBlock.MIRRORED)) part = part.mirror(BlockMirror.FRONT_BACK);
            return part.rotate(rotation(basis.get(CorniceAssemblyBlock.FACING)));
        });
    }

    public static Contents toLocal(Contents world, BlockState basis) {
        if (basis.get(CorniceAssemblyBlock.FACING) == Direction.NORTH && !basis.get(CorniceAssemblyBlock.MIRRORED)) return world;
        return transform(world, part -> {
            BlockRotation inverse = switch (basis.get(CorniceAssemblyBlock.FACING)) {
                case EAST -> BlockRotation.COUNTERCLOCKWISE_90;
                case SOUTH -> BlockRotation.CLOCKWISE_180;
                case WEST -> BlockRotation.CLOCKWISE_90;
                default -> BlockRotation.NONE;
            };
            part = part.rotate(inverse);
            return basis.get(CorniceAssemblyBlock.MIRRORED) ? part.mirror(BlockMirror.FRONT_BACK) : part;
        });
    }

    private static Contents transform(Contents contents, java.util.function.UnaryOperator<BlockState> operation) {
        return new Contents(contents.cornices.stream().map(operation).toList(),
                contents.cover == null ? null : operation.apply(contents.cover));
    }

    private static BlockRotation rotation(Direction facing) {
        return switch (facing) {
            case EAST -> BlockRotation.CLOCKWISE_90;
            case SOUTH -> BlockRotation.CLOCKWISE_180;
            case WEST -> BlockRotation.COUNTERCLOCKWISE_90;
            default -> BlockRotation.NONE;
        };
    }

    public static void redraw(World world, BlockPos pos) {
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            BlockPos owner = pos.add(x, 0, z);
            BlockState state = world.getBlockState(owner);
            if (isOwner(state)) world.updateListeners(owner, state, state, Block.NOTIFY_LISTENERS);
        }
    }
}
