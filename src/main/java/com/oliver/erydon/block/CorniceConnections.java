package com.oliver.erydon.block;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Local rear-edge discovery creates reciprocal mitres without persisted neighbour properties. */
public final class CorniceConnections {
    private static final CorniceRun.Cut START = new CorniceRun.Cut(0, 0);
    private static final CorniceRun.Cut END = new CorniceRun.Cut(0, 1);
    private static final double MAX_DEPTH = .5;
    private static final AtomicReferenceArray<List<Segment>> NATIVE =
            new AtomicReferenceArray<>(CorniceBlock.CorniceShape.values().length * 4);
    private CorniceConnections() { }

    public record Segment(CorniceRun run, CorniceRun.Cut start, CorniceRun.Cut end,
                          boolean joinedStart, boolean joinedEnd, boolean cropProfile) {
        /** Published angled callers retain their existing affine fitting. */
        public Segment(CorniceRun run, CorniceRun.Cut start, CorniceRun.Cut end, boolean joinedStart, boolean joinedEnd) {
            this(run, start, end, joinedStart, joinedEnd, false);
        }

        public Segment cropped() { return cropProfile ? this : new Segment(run, start, end, joinedStart, joinedEnd, true); }
        public boolean usesProfileCrop() {
            // A half-support peer can mark either side of a join. Only cardinal
            // profiles opt in; the published long hypotenuse fitting stays affine.
            boolean cardinal = Math.abs(run.tangentX()) < .0000001 || Math.abs(run.tangentZ()) < .0000001;
            return cropProfile && cardinal && (run.length() < 1 - .0000001 || joinedStart || joinedEnd);
        }

        /** Source texture/ornament phase on the fixed cardinal block grid, in physical blocks. */
        public double sourcePhase() {
            double phase = run.a().x() * run.tangentX() + run.a().z() * run.tangentZ();
            phase -= Math.floor(phase + .0000001);
            return Math.abs(phase) < .0000001 ? 0 : phase;
        }

        /** Rigid source coordinate, with no longitudinal ornament scaling or end-cut shear. */
        public CorniceRun.Point profilePoint(double physicalU, double depth) {
            return new CorniceRun.Point(run.a().x() + run.tangentX() * physicalU + run.normalX() * depth,
                    run.a().z() + run.tangentZ() * physicalU + run.normalZ() * depth);
        }
        public CorniceRun.Point point(double u, double depth) {
            return run.point(start.at(depth) + (end.at(depth) - start.at(depth)) * u, depth);
        }
    }

    private record Placed(BlockPos owner, BlockState state, int part, int index, CorniceRun run,
                          CorniceAssembly.Contents contents) { }
    private record Candidate(Placed placed, int end, CorniceRun.Join join) { }
    private record Ports(Candidate start, Candidate end) {
        boolean admissible() {
            CorniceRun.Cut first = start == null ? START : start.join.sourceCut();
            CorniceRun.Cut last = end == null ? END : end.join.sourceCut();
            return first.at(MAX_DEPTH) < last.at(MAX_DEPTH) - .000001;
        }
    }

    public static List<Segment> nativeComponents(BlockState state) {
        int index = state.get(CorniceBlock.SHAPE).ordinal() * 4 + state.get(CorniceBlock.FACING).getHorizontal();
        List<Segment> cached = NATIVE.get(index);
        if (cached != null) return cached;
        List<Segment> created = createNativeComponents(state);
        NATIVE.compareAndSet(index, null, created);
        return NATIVE.get(index);
    }

    private static List<Segment> createNativeComponents(BlockState state) {
        CorniceBlock.CorniceShape shape = state.get(CorniceBlock.SHAPE);
        if (shape == CorniceBlock.CorniceShape.OUTER_CORNER) return List.of();
        CorniceRun first = CorniceSlopePlacement.run(state);
        if (shape != CorniceBlock.CorniceShape.INNER_CORNER)
            return List.of(new Segment(first, START, END, false, false, shape.isHalfSupport()));
        CorniceRun second = new CorniceRun(0, 0, 1, 0).rotate(state.get(CorniceBlock.FACING).rotateYCounterclockwise());
        return List.of(new Segment(first, START, END, false, false), new Segment(second, START, END, false, false));
    }

    /** One angled item can also return along a real full wall in its own cell. */
    public static List<Segment> placedComponents(BlockView world, BlockState state, BlockPos pos) {
        if (!state.get(CorniceBlock.SHAPE).isAngled()) return nativeComponents(state);
        return placedComponents(world, state, pos, world == null ? CorniceAssembly.EMPTY : sourceContents(world, state, pos));
    }

    private static List<Segment> placedComponents(BlockView world, BlockState state, BlockPos pos, CorniceAssembly.Contents contents) {
        List<Segment> nativeRuns = nativeComponents(state);
        if (world == null || !state.get(CorniceBlock.SHAPE).isAngled()) return nativeRuns;
        CorniceRun angled = nativeRuns.get(0).run;
        Segment returnArm = null;
        for (Direction wall : Direction.Type.HORIZONTAL) {
            BlockPos supportPos = pos.offset(wall);
            BlockState support = world.getBlockState(supportPos);
            // Dynamic neighbouring cornices can query their own joins while
            // calculating shapes. They are never a full supporting wall here.
            if (CorniceAssembly.isOwner(support)
                    || !support.isSideSolidFullSquare(EmptyBlockView.INSTANCE, supportPos, wall.getOpposite())) continue;
            Segment cardinal = nativeComponents(state.with(CorniceBlock.SHAPE, CorniceBlock.CorniceShape.STRAIGHT)
                    .with(CorniceBlock.FACING, wall)).get(0);
            boolean meets = false;
            for (int end : new int[]{-1, 1}) {
                CorniceRun.Join join = CorniceRun.join(angled, end, cardinal.run, -end);
                if (join != null && safe(join, end, -end, angled, cardinal.run)) meets = true;
            }
            if (!meets) continue;
            // An explicitly paid part owns this return already; do not emit a duplicate inferred strip.
            boolean explicit = false;
            for (BlockState part : contents.cornices()) {
                if (part == state) continue;
                for (Segment existing : nativeComponents(part)) {
                    if (sameRun(existing.run, cardinal.run)) explicit = true;
                }
            }
            if (explicit) continue;
            // A third supported arm has no unique two-piece corner profile;
            // preserve the saved angled edge instead of guessing ownership.
            if (returnArm != null) return nativeRuns;
            returnArm = state.get(CorniceBlock.SHAPE).isHalfSupport() ? cardinal.cropped() : cardinal;
        }
        return returnArm == null ? nativeRuns : List.of(nativeRuns.get(0), returnArm);
    }

    public static boolean usesAngledGeometry(BlockView world, BlockState state, BlockPos pos) {
        if (state.get(CorniceBlock.SHAPE).isAngled()) return true;
        if (state.get(CorniceBlock.SHAPE) == CorniceBlock.CorniceShape.OUTER_CORNER || world == null) return false;
        return components(world, state, pos).stream().anyMatch(segment -> segment.joinedStart || segment.joinedEnd);
    }

    public static List<Segment> components(BlockView world, BlockState state, BlockPos pos) {
        if (state.get(CorniceBlock.SHAPE) == CorniceBlock.CorniceShape.OUTER_CORNER) return nativeComponents(state);
        CorniceAssembly.Contents sourceContents = world == null ? CorniceAssembly.EMPTY : sourceContents(world, state, pos);
        List<Segment> nativeRuns = placedComponents(world, state, pos, sourceContents);
        if (nativeRuns.isEmpty() || world == null) return nativeRuns;
        List<Placed> placed = neighbours(world, state, pos, sourceContents);
        if (placed.stream().noneMatch(peer -> peer.state.get(CorniceBlock.SHAPE).isAngled())
                && sourceContents.cornices().size() < 2) return nativeRuns;
        List<Segment> result = new ArrayList<>(nativeRuns.size());
        for (int index = 0; index < nativeRuns.size(); index++) {
            CorniceRun run = nativeRuns.get(index).run;
            int part = Math.max(0, sourceContents.cornices().indexOf(state));
            Placed source = new Placed(pos, state, part, index, run.translated(pos.getX(), pos.getZ()), sourceContents);
            Ports sourcePorts = ports(source, placed);
            Candidate start = sourcePorts.admissible() ? mutual(source, -1, sourcePorts.start, world) : null;
            Candidate end = sourcePorts.admissible() ? mutual(source, 1, sourcePorts.end, world) : null;
            CorniceRun.Cut startCut = start == null ? START : start.join.sourceCut();
            CorniceRun.Cut endCut = end == null ? END : end.join.sourceCut();
            boolean cropped = nativeRuns.get(index).cropProfile
                    || start != null && start.placed.state.get(CorniceBlock.SHAPE).isHalfSupport()
                    || end != null && end.placed.state.get(CorniceBlock.SHAPE).isHalfSupport();
            result.add(new Segment(run, startCut, endCut, start != null, end != null, cropped));
        }
        return List.copyOf(result);
    }

    private static Candidate mutual(Placed source, int sourceEnd, Candidate candidate, BlockView world) {
        if (candidate == null) return null;
        // The target must consult its own discovery radius: another owner just
        // beyond the source's radius can make the target port ambiguous.
        Ports targetPorts = ports(candidate.placed, neighbours(world, candidate.placed.state, candidate.placed.owner,
                candidate.placed.contents));
        if (!targetPorts.admissible()) return null;
        Candidate reciprocal = candidate.end < 0 ? targetPorts.start : targetPorts.end;
        return reciprocal != null && same(reciprocal.placed, source) && reciprocal.end == sourceEnd ? candidate : null;
    }

    private static Ports ports(Placed source, List<Placed> placed) {
        return new Ports(only(source, -1, placed), only(source, 1, placed));
    }

    private static List<Placed> neighbours(BlockView world, BlockState state, BlockPos pos, CorniceAssembly.Contents sourceContents) {
        List<Placed> placed = new ArrayList<>();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            BlockPos owner = pos.add(x, 0, z);
            CorniceAssembly.Contents contents = owner.equals(pos) ? sourceContents : CorniceAssembly.contents(world, owner);
            List<BlockState> parts = contents.cornices();
            // Pure pose callers may not have installed the source state in their view yet.
            if (owner.equals(pos) && parts.isEmpty()) parts = List.of(state);
            for (int part = 0; part < parts.size(); part++) {
                BlockState other = parts.get(part);
                if (!other.isOf(state.getBlock())) continue;
                List<Segment> otherRuns = placedComponents(world, other, owner, contents);
                for (int index = 0; index < otherRuns.size(); index++) {
                    placed.add(new Placed(owner, other, part, index,
                            otherRuns.get(index).run.translated(owner.getX(), owner.getZ()), contents));
                }
            }
        }
        return placed;
    }

    private static Candidate only(Placed source, int sourceEnd, List<Placed> placed) {
        Candidate result = null;
        for (Placed target : placed) {
            if (same(source, target)) continue;
            if (!source.state.get(CorniceBlock.SHAPE).isAngled() && !target.state.get(CorniceBlock.SHAPE).isAngled()
                    && !(source.owner.equals(target.owner) && source.part != target.part)) continue;
            int targetEnd = -sourceEnd;
            CorniceRun.Join join = CorniceRun.join(source.run, sourceEnd, target.run, targetEnd);
            if (join == null || !safe(join, sourceEnd, targetEnd, source.run, target.run)) continue;
            if (result != null) return null;
            result = new Candidate(target, targetEnd, join);
        }
        return result;
    }

    private static boolean safe(CorniceRun.Join join, int sourceEnd, int targetEnd, CorniceRun sourceRun, CorniceRun targetRun) {
        double source = join.sourceCut().at(MAX_DEPTH), target = join.targetCut().at(MAX_DEPTH);
        // A half-length arm needs twice the normalized extension for the same physical mitre.
        // Existing full/diagonal bounds remain unchanged.
        double sourceMargin = .75 / Math.min(1, sourceRun.length());
        double targetMargin = .75 / Math.min(1, targetRun.length());
        return source >= -sourceMargin && source <= 1 + sourceMargin && target >= -targetMargin && target <= 1 + targetMargin
                && (sourceEnd < 0 ? source < 1 - .000001 : source > .000001)
                && (targetEnd < 0 ? target < 1 - .000001 : target > .000001);
    }

    private static boolean same(Placed a, Placed b) {
        return a.owner.equals(b.owner) && a.part == b.part && a.index == b.index;
    }

    private static boolean sameRun(CorniceRun a, CorniceRun b) {
        return Math.hypot(a.a().x() - b.a().x(), a.a().z() - b.a().z()) < .000001
                && Math.hypot(a.b().x() - b.b().x(), a.b().z() - b.b().z()) < .000001;
    }

    private static CorniceAssembly.Contents sourceContents(BlockView world, BlockState state, BlockPos pos) {
        // Real World has no frozen render attachment. Inspect its actual owner
        // once, because the supplied state can be one stored assembly part.
        return world instanceof World ? CorniceAssembly.contents(world, pos) : CorniceAssembly.contents(world, pos, state);
    }
}
