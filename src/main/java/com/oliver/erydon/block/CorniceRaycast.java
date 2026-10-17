package com.oliver.erydon.block;

import net.minecraft.block.ShapeContext;
import net.minecraft.block.BlockState;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;
import net.minecraft.world.BlockView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Resolve overhanging mouldings to their actual owner when selecting or breaking them. */
public final class CorniceRaycast {
    private CorniceRaycast() { }
    private record Candidate(BlockPos owner, BlockState state) { }
    private static final AtomicReferenceArray<Box> BOUNDS =
            new AtomicReferenceArray<>(CorniceBlock.CorniceShape.values().length * 4);

    public static BlockHitResult closest(BlockView world, Vec3d start, Vec3d end,
                                         BlockPos cell, BlockHitResult original) {
        return closest(world, start, end, cell, world.getBlockState(cell), original);
    }

    public static BlockHitResult closest(BlockView world, Vec3d start, Vec3d end,
                                         BlockPos cell, BlockState cellState, BlockHitResult original) {
        // Vanilla raycasts an overhanging outline against the full ray too.
        // Its hit must still belong to this DDA cell before traversal can stop.
        if (original != null && original.getBlockPos().equals(cell) && CorniceAssembly.isOwner(cellState)
                && !insideCell(original.getPos(), cell)) original = null;
        if (!touchesMouldingHeight(start, end, cell)) return original;
        List<Candidate> candidates = new ArrayList<>();
        // An isolated moulding stays within touching cells. An extended mitre
        // needs a touching neighbour too; ordinary air rays can stop here.
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            if (x == 0 && z == 0) continue;
            add(world, cell.add(x, 0, z), candidates);
        }
        if (candidates.isEmpty() && !CorniceAssembly.isOwner(cellState)) return original;
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            if (Math.abs(x) < 2 && Math.abs(z) < 2) continue;
            add(world, cell.add(x, 0, z), candidates);
        }
        List<Candidate> angled = new ArrayList<>();
        for (Candidate candidate : candidates)
            if (angled(world, candidate.owner, candidate.state)) angled.add(candidate);
        if (CorniceAssembly.isOwner(cellState) && angled(world, cell, cellState))
            angled.add(new Candidate(cell, cellState));
        if (angled.isEmpty()) return original;
        BlockHitResult best = original;
        for (Candidate candidate : candidates) {
            if (!touchesBounds(start, end, candidate.owner, conservativeBounds(candidate.state))) continue;
            if (!angled(world, candidate.owner, candidate.state) && !touchesAngledEnd(world, candidate, angled)) continue;
            var hit = candidate.state.getOutlineShape(world, candidate.owner, ShapeContext.absent()).raycast(start, end, candidate.owner);
            if (hit != null && insideCell(hit.getPos(), cell) && (best == null || hit.getPos().squaredDistanceTo(start)
                    < best.getPos().squaredDistanceTo(start))) best = hit;
        }
        return best;
    }

    /** Includes all finite accepted end cuts and the authored profile envelopes. */
    public static Box conservativeBounds(BlockState state) {
        if (state.getBlock() instanceof CorniceAssemblyBlock) return new Box(-3, 0, -3, 4, 1, 4);
        int index = state.get(CorniceBlock.SHAPE).ordinal() * 4 + state.get(CorniceBlock.FACING).getHorizontal();
        Box cached = BOUNDS.get(index);
        if (cached != null) return cached;
        // Gothic free-end ornaments extend slightly past the authored unit
        // length; the physical raster rounds those envelopes to 1/32 cells.
        double lowX = -1.0 / 32, lowZ = -1.0 / 32, highX = 33.0 / 32, highZ = 33.0 / 32;
        for (var component : CorniceConnections.nativeComponents(state)) {
            for (double u : new double[]{-.8, 1.8}) for (double depth : new double[]{-.0625, .5625}) {
                var point = component.run().point(u, depth);
                lowX = Math.min(lowX, point.x()); highX = Math.max(highX, point.x());
                lowZ = Math.min(lowZ, point.z()); highZ = Math.max(highZ, point.z());
            }
        }
        lowX = Math.floor(lowX * 32) / 32; lowZ = Math.floor(lowZ * 32) / 32;
        highX = Math.ceil(highX * 32) / 32; highZ = Math.ceil(highZ * 32) / 32;
        BOUNDS.compareAndSet(index, null, new Box(lowX, .25, lowZ, highX, 1, highZ));
        return BOUNDS.get(index);
    }

    private static boolean angled(BlockView world, BlockPos owner, BlockState state) {
        if (state.getBlock() instanceof CorniceBlock) return state.get(CorniceBlock.SHAPE).isAngled();
        return CorniceAssembly.contents(world, owner, state).cornices().stream()
                .anyMatch(part -> part.get(CorniceBlock.SHAPE).isAngled());
    }

    private static boolean touchesBounds(Vec3d start, Vec3d end, BlockPos owner, Box bounds) {
        double entry = 0, exit = 1;
        for (int axis = 0; axis < 3; axis++) {
            double a = axis == 0 ? start.x : axis == 1 ? start.y : start.z;
            double delta = (axis == 0 ? end.x : axis == 1 ? end.y : end.z) - a;
            double offset = axis == 0 ? owner.getX() : axis == 1 ? owner.getY() : owner.getZ();
            double low = offset + (axis == 0 ? bounds.minX : axis == 1 ? bounds.minY : bounds.minZ);
            double high = offset + (axis == 0 ? bounds.maxX : axis == 1 ? bounds.maxY : bounds.maxZ);
            if (Math.abs(delta) < 1.0e-7) { if (a < low || a > high) return false; }
            else {
                double first = (low - a) / delta, last = (high - a) / delta;
                entry = Math.max(entry, Math.min(first, last)); exit = Math.min(exit, Math.max(first, last));
            }
        }
        return entry <= exit;
    }

    private static boolean insideCell(Vec3d hit, BlockPos cell) {
        double epsilon = .00001;
        return hit.x >= cell.getX() - epsilon && hit.x <= cell.getX() + 1 + epsilon
                && hit.y >= cell.getY() - epsilon && hit.y <= cell.getY() + 1 + epsilon
                && hit.z >= cell.getZ() - epsilon && hit.z <= cell.getZ() + 1 + epsilon;
    }

    private static void add(BlockView world, BlockPos owner, List<Candidate> candidates) {
        BlockState state = world.getBlockState(owner);
        if (CorniceAssembly.isOwner(state)) candidates.add(new Candidate(owner, state));
    }

    private static boolean touchesAngledEnd(BlockView world, Candidate candidate, List<Candidate> angled) {
        for (BlockState sourcePart : CorniceAssembly.contents(world, candidate.owner).cornices()) {
            for (var source : CorniceConnections.nativeComponents(sourcePart)) for (Candidate other : angled) {
                for (BlockState targetPart : CorniceAssembly.contents(world, other.owner).cornices()) {
                    if (!targetPart.isOf(sourcePart.getBlock()) || !targetPart.get(CorniceBlock.SHAPE).isAngled()) continue;
                    CorniceRun target = CorniceSlopePlacement.run(targetPart).translated(
                            other.owner.getX() - candidate.owner.getX(), other.owner.getZ() - candidate.owner.getZ());
                    if (CorniceRun.join(source.run(), -1, target, 1) != null
                            || CorniceRun.join(source.run(), 1, target, -1) != null) return true;
                }
            }
        }
        return false;
    }

    private static boolean touchesMouldingHeight(Vec3d start, Vec3d end, BlockPos cell) {
        double entry = 0, exit = 1;
        for (int axis = 0; axis < 3; axis++) {
            double a = axis == 0 ? start.x : axis == 1 ? start.y : start.z;
            double delta = (axis == 0 ? end.x : axis == 1 ? end.y : end.z) - a;
            double low = axis == 0 ? cell.getX() : axis == 1 ? cell.getY() : cell.getZ();
            double high = low + 1;
            if (axis == 1) low += 0.25;
            if (Math.abs(delta) < 1.0e-7) {
                if (a < low || a > high) return false;
            } else {
                double first = (low - a) / delta, last = (high - a) / delta;
                entry = Math.max(entry, Math.min(first, last));
                exit = Math.min(exit, Math.max(first, last));
            }
        }
        return entry <= exit;
    }
}
