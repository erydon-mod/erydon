package com.oliver.erydon.block;

import com.oliver.erydon.item.CorniceItem;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.WorldAccess;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Production slope targeting, saved attachments, reciprocal planar joins and the mixed-in world raycast. */
public final class CornicePlacementLaunchChecks {
    private static final double EPS = .00003;
    private CornicePlacementLaunchChecks() { }

    public static void run() throws Exception {
        int placements = 0, selected = 0, transforms = 0;
        var rayEntity = new net.minecraft.entity.MarkerEntity(net.minecraft.entity.EntityType.MARKER, null);
        String[] styles = {"modern", "georgian", "byzantine", "gothic"};
        for (String style : styles) {
            CorniceBlock cornice = cornice(style);
            require(cornice.asItem() instanceof CorniceItem, "Cornice is missing its actual slope placement item");
            for (Direction facing : Direction.Type.HORIZONTAL) for (int kind = 0; kind < 5; kind++) {
                BlockState support = support(kind, facing);
                BlockPos supportPos = new BlockPos(15, 7, 15);
                List<Direction> fronts = fronts(support);
                require(fronts.size() == 2, "Slope must expose exactly two valid front owner directions");
                for (Direction front : fronts) {
                    var states = new HashMap<BlockPos, BlockState>();
                    states.put(supportPos, support);
                    // The ceiling stays present above both the supporting slope and the cornice owner.
                    states.put(supportPos.up(), Blocks.STONE.getDefaultState());
                    for (Direction side : fronts) states.put(supportPos.offset(side).up(), Blocks.STONE.getDefaultState());
                    BlockView view = CopingPlacementLaunchProbe.view(states);
                    CorniceItem.Target target = CorniceItem.target(view, supportPos, front);
                    require(target != null && target.pos().equals(supportPos.offset(front))
                            && target.outward() == front, "Hypotenuse target did not stay in its requested adjacent front cell");
                    BlockState placed = CorniceSlopePlacement.resolve(cornice.getDefaultState(), view, target.pos(), front.getOpposite());
                    require(placed != null && placed.get(CorniceBlock.SHAPE).isAngled(), "Actual support was not fitted");
                    require(CorniceSlopePlacement.supportPos(placed, target.pos()).equals(supportPos), "Saved pose lost its slope owner");
                    require(CorniceSlopePlacement.attached(placed, view, target.pos()), "Saved pose cannot validate its actual support");
                    states.put(target.pos(), placed);
                    com.oliver.erydon.client.model.CorniceMeshLaunchChecks.verify(view, placed, target.pos());
                    require(states.get(supportPos) == support && states.get(supportPos.up()).isOf(Blocks.STONE),
                            "Cornice placement replaced the slope or the occupied ceiling");
                    require(placed.getOutlineShape(view, target.pos()) == placed.getCollisionShape(view, target.pos()),
                            "Angled selection/collision diverged");
                    CorniceRun run = CorniceSlopePlacement.run(placed);
                    for (double u : new double[]{0, .25, .5, .75, 1}) {
                        CorniceRun.Point sample = run.point(u, .125);
                        var boxes = placed.getOutlineShape(view, target.pos()).getBoundingBoxes();
                        require(boxes.stream().anyMatch(box -> sample.x() >= box.minX - EPS && sample.x() <= box.maxX + EPS
                                && sample.z() >= box.minZ - EPS && sample.z() <= box.maxZ + EPS
                                && .96875 >= box.minY - EPS && .96875 <= box.maxY + EPS),
                                "Transformed top-band sample is outside its placed outline: " + placed);
                    }
                    // Aim horizontally through the visible top band inside the support's empty triangular region.
                    CorniceRun.Point rear = run.point(.5, 0);
                    Vec3d wallPoint = new Vec3d(target.pos().getX() + rear.x(), target.pos().getY() + .96875,
                            target.pos().getZ() + rear.z());
                    Vec3d start = wallPoint.add(run.normalX() * 1.5, 0, run.normalZ() * 1.5);
                    Vec3d end = wallPoint.add(-run.normalX() * .05, 0, -run.normalZ() * .05);
                    var hit = view.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE,
                            RaycastContext.FluidHandling.NONE, rayEntity));
                    require(hit.getBlockPos().equals(target.pos()), "Actual hypotenuse raycast selected " + hit.getBlockPos()
                            + " instead of adjacent owner " + target.pos() + " for " + placed);
                    selected++;
                    Direction alternate = fronts.get(0) == front ? fronts.get(1) : fronts.get(0);
                    require(CorniceSlopePlacement.resolve(cornice("modern".equals(style) ? "georgian" : "modern").getDefaultState(),
                            view, supportPos.offset(alternate), alternate.getOpposite()) == null,
                            "Another material/style can duplicate the same hypotenuse attachment");
                    states.remove(target.pos());
                    states.put(target.pos(), Blocks.STONE.getDefaultState());
                    CorniceItem.Target fallback = CorniceItem.target(view, supportPos, front);
                    require(fallback != null && fallback.pos().equals(supportPos.offset(alternate)), "Occupied preferred front did not use the other front");
                    states.put(supportPos.offset(alternate), Blocks.STONE.getDefaultState());
                    require(CorniceItem.target(view, supportPos, front) == null, "Two occupied front cells allowed placement");
                    for (Direction side : Direction.Type.HORIZONTAL) if (!fronts.contains(side)) {
                        require(CorniceItem.target(view, supportPos, side) == null, "Native leg click was rerouted to the hypotenuse");
                    }
                    states.clear(); states.put(target.pos(), placed);
                    require(cornice.getStateForNeighborUpdate(placed, front.getOpposite(), Blocks.AIR.getDefaultState(),
                            access(states), target.pos(), supportPos).isAir(), "Removed slope retained a floating angled attachment");
                    states.put(supportPos, support.rotate(BlockRotation.CLOCKWISE_90));
                    require(cornice.getStateForNeighborUpdate(placed, front.getOpposite(), states.get(supportPos), access(states),
                            target.pos(), supportPos).isAir(), "Changed support silently moved the saved attachment");
                    for (BlockRotation rotation : BlockRotation.values()) {
                        CorniceRun transformed = CorniceSlopePlacement.run(placed.rotate(rotation));
                        int turns = rotation == BlockRotation.CLOCKWISE_90 ? 1 : rotation == BlockRotation.CLOCKWISE_180 ? 2
                                : rotation == BlockRotation.COUNTERCLOCKWISE_90 ? 3 : 0;
                        CorniceRun expected = run;
                        for (int turn = 0; turn < turns; turn++) expected = expected.rotate(Direction.EAST);
                        require(close(transformed.a(), expected.a()) && close(transformed.b(), expected.b()), "Saved rotation changed the physical rear edge");
                        transforms++;
                    }
                    for (BlockMirror mirror : BlockMirror.values()) {
                        BlockState mirrored = placed.mirror(mirror);
                        require(mirrored.mirror(mirror).equals(placed), "Saved angled mirror does not round-trip");
                        CorniceRun transformed = CorniceSlopePlacement.run(mirrored);
                        CorniceRun.Point expectedA = mirror == BlockMirror.NONE ? run.a() : reflect(run.b(), mirror);
                        CorniceRun.Point expectedB = mirror == BlockMirror.NONE ? run.b() : reflect(run.a(), mirror);
                        require(close(transformed.a(), expectedA) && close(transformed.b(), expectedB), "Mirror lost the translated front-cell geometry");
                        transforms++;
                    }
                    placements++;
                }
            }
        }
        nativeCardinal();
        sameOwnerReturns();
        occlusion();
        redrawDependencies();
        joins(cornice("modern"));
        ambiguity(cornice("modern"));
        com.oliver.erydon.client.model.CorniceMeshLaunchChecks.report();
        System.out.println("ERYDON_CORNICE_PLACEMENT_OK: " + placements + " four-style/front/support/facing cases with occupied ceilings; "
                + selected + " actual hypotenuse world-raycast owners; " + transforms + " saved rotation/mirror poses; fallback, duplicate and removal checks");
    }

    private static void nativeCardinal() {
        CorniceBlock cornice = cornice("georgian");
        for (Direction facing : Direction.Type.HORIZONTAL) {
            BlockState state = cornice.getDefaultState().with(CorniceBlock.FACING, facing);
            var states = new HashMap<BlockPos, BlockState>();
            states.put(BlockPos.ORIGIN.offset(facing), Blocks.STONE.getDefaultState());
            BlockView view = CopingPlacementLaunchProbe.view(states);
            require(cornice.canPlaceAt(state, access(states), BlockPos.ORIGIN), "Native full-square attachment no longer places");
            require(cornice.getStateForNeighborUpdate(state, facing, states.get(BlockPos.ORIGIN.offset(facing)), access(states),
                    BlockPos.ORIGIN, BlockPos.ORIGIN.offset(facing)).equals(state), "Native cardinal support changed its saved state");
            require(!CorniceConnections.usesAngledGeometry(view, state, BlockPos.ORIGIN), "Native straight run entered the angled renderer without a neighbour");
            states.clear();
            require(cornice.getStateForNeighborUpdate(state, facing, Blocks.AIR.getDefaultState(), access(states),
                    BlockPos.ORIGIN, BlockPos.ORIGIN.offset(facing)).isAir(), "Unsupported native cornice remains attached");
        }
    }

    private static void sameOwnerReturns() throws Exception {
        int cases = 0;
        var rayEntity = new net.minecraft.entity.MarkerEntity(net.minecraft.entity.EntityType.MARKER, null);
        for (String style : new String[]{"modern", "georgian", "byzantine", "gothic"}) {
            CorniceBlock cornice = cornice(style);
            for (Direction facing : Direction.Type.HORIZONTAL) for (int kind = 0; kind < 5; kind++) {
                BlockState slope = support(kind, facing);
                BlockPos supportPos = new BlockPos(15, 7, 15);
                for (Direction front : fronts(slope)) {
                    var states = new HashMap<BlockPos, BlockState>();
                    states.put(supportPos, slope);
                    states.put(supportPos.up(), Blocks.STONE.getDefaultState());
                    BlockView view = CopingPlacementLaunchProbe.view(states);
                    CorniceItem.Target target = CorniceItem.target(view, supportPos, front);
                    require(target != null, "Same-owner return lost the actual front placement target");
                    BlockPos owner = target.pos();
                    BlockState placed = CorniceSlopePlacement.resolve(cornice.getDefaultState(), view, owner, front.getOpposite());
                    require(placed != null, "Same-owner return lost its actual support fit");
                    // Independent cardinal endpoint matches for the authored
                    // NORTH slope frame. Half-cell rear endpoints have no same-cell return.
                    Direction wall = switch (placed.get(CorniceBlock.SHAPE)) {
                        case DIAGONAL_PRIMARY, BROAD_RIGHT_PRIMARY -> Direction.NORTH;
                        case DIAGONAL_SECONDARY, NARROW_RIGHT_SECONDARY, NARROW_LEFT_SECONDARY -> Direction.WEST;
                        case BROAD_LEFT_PRIMARY -> Direction.SOUTH;
                        default -> null;
                    };
                    if (wall == null) {
                        require(CorniceConnections.placedComponents(view, placed, owner).size() == 1,
                                "An unsupported half-cell endpoint invented a cardinal return");
                        continue;
                    }
                    states.put(owner, placed);
                    states.put(owner.up(), Blocks.STONE.getDefaultState());
                    int turns = facing == Direction.EAST ? 1 : facing == Direction.SOUTH ? 2 : facing == Direction.WEST ? 3 : 0;
                    for (int turn = 0; turn < turns; turn++) wall = wall.rotateYClockwise();
                    BlockPos wallPos = owner.offset(wall);
                    require(!wallPos.equals(supportPos), "Return wall overlaps the preserved diagonal support");
                    states.put(wallPos, Blocks.STONE.getDefaultState());
                    states.put(wallPos.up(), Blocks.STONE.getDefaultState());
                    require(CorniceConnections.nativeComponents(placed).size() == 1,
                            "World-supported return leaked into the pose-only lighting/Axiom cache");
                    List<CorniceConnections.Segment> segments = CorniceConnections.components(view, placed, owner);
                    require(segments.size() == 2, "Actual same-cell cube-supported cardinal arm is missing");
                    var angle = segments.get(0); var cardinal = segments.get(1);
                    require((angle.joinedStart() || angle.joinedEnd()) && (cardinal.joinedStart() || cardinal.joinedEnd()),
                            "Same-owner cardinal/angled arms did not receive mutual end cuts");
                    for (double depth : new double[]{0, .0625, .1875, .3125, .5}) {
                        CorniceRun.Point a = angle.point(angle.joinedEnd() ? 1 : 0, depth);
                        CorniceRun.Point b = cardinal.point(cardinal.joinedEnd() ? 1 : 0, depth);
                        require(close(a, b), "Same-owner return leaves a gap between identical profile depths");
                        require(angle.start().at(depth) < angle.end().at(depth) && cardinal.start().at(depth) < cardinal.end().at(depth),
                                "Same-owner return inverted an arm's profile span");
                    }
                    com.oliver.erydon.client.model.CorniceMeshLaunchChecks.verify(view, placed, owner);
                    var bounds = CorniceRaycast.conservativeBounds(placed);
                    var physical = placed.getOutlineShape(view, owner).getBoundingBox();
                    require(physical.minX >= bounds.minX - EPS && physical.maxX <= bounds.maxX + EPS
                            && physical.minY >= bounds.minY - EPS && physical.maxY <= bounds.maxY + EPS
                            && physical.minZ >= bounds.minZ - EPS && physical.maxZ <= bounds.maxZ + EPS,
                            "Conservative ray bounds omit an actual same-owner angle/return profile: " + placed
                                    + "; physical=" + physical + "; conservative=" + bounds);
                    for (var component : segments) {
                        CorniceRun.Point point = component.point(.5, .125);
                        Vec3d surface = new Vec3d(owner.getX() + point.x(), owner.getY() + .96875, owner.getZ() + point.z());
                        Vec3d start = surface.add(component.run().normalX() * 1.5, 0, component.run().normalZ() * 1.5);
                        Vec3d end = surface.add(-component.run().normalX() * .1875, 0, -component.run().normalZ() * .1875);
                        var hit = view.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE,
                                RaycastContext.FluidHandling.NONE, rayEntity));
                        require(hit.getBlockPos().equals(owner), "Actual same-owner return/overhang ray selects " + hit.getBlockPos() + " instead of " + owner);
                        BlockPos cell = BlockPos.ofFloored(hit.getPos());
                        BlockState cellState = view.getBlockState(cell);
                        var original = cellState.getOutlineShape(view, cell).raycast(start, end, cell);
                        var replayed = CorniceRaycast.closest(view, start, end, cell, cellState, original);
                        require(replayed != null && replayed.getBlockPos().equals(owner),
                                "Exact six-argument mixed-in cornice ray path omitted a same-owner return/overhang");
                    }
                    require(states.get(supportPos) == slope && states.get(owner.up()).isOf(Blocks.STONE)
                            && states.get(supportPos.up()).isOf(Blocks.STONE), "Same-owner return occupied or altered the ceiling/support");
                    // Additional surrounding cubes cannot invent a third arm whose rear edge misses the actual diagonal.
                    for (Direction other : Direction.Type.HORIZONTAL) if (!owner.offset(other).equals(supportPos)) {
                        states.put(owner.offset(other), Blocks.STONE.getDefaultState());
                    }
                    require(CorniceConnections.placedComponents(view, placed, owner).size() == 2,
                            "Extra nonjoining full walls generated a third corner arm");
                    for (Direction other : Direction.Type.HORIZONTAL) if (!owner.offset(other).equals(supportPos)) states.remove(owner.offset(other));
                    require(CorniceConnections.components(view, placed, owner).size() == 1,
                            "Removed return wall left a floating cardinal arm");
                    states.put(wallPos, Blocks.OAK_SLAB.getDefaultState());
                    require(CorniceConnections.placedComponents(view, placed, owner).size() == 1,
                            "A half-height wall was guessed to be full-square return support");
                    states.put(wallPos, cornice.getDefaultState());
                    require(CorniceConnections.placedComponents(view, placed, owner).size() == 1,
                            "A neighbouring cornice was queried recursively as return support");
                    cases++;
                }
            }
        }
        require(cases == 96, "Expected every valid 45-degree and shallow same-owner return pose, got " + cases);
        System.out.println("ERYDON_CORNICE_SAME_OWNER_RETURNS_OK: " + cases
                + " real four-style/facing/front 45-degree and shallow two-arm corners share profile-depth seams, baked mesh bounds and actual world/six-argument ray ownership; ceiling and pose-only cache retained");
    }

    private static void occlusion() {
        CorniceBlock cornice = cornice("modern");
        BlockState rear = cornice.getDefaultState().with(CorniceBlock.SHAPE, CorniceBlock.CorniceShape.DIAGONAL_PRIMARY);
        BlockState qualifier = rear.with(CorniceBlock.FACING, Direction.WEST);
        BlockPos rearOwner = new BlockPos(1, 0, 1), wall = new BlockPos(0, 0, 2);
        var states = new HashMap<BlockPos, BlockState>();
        states.put(rearOwner, rear);
        states.put(new BlockPos(0, 0, 1), support(0, Direction.NORTH));
        states.put(new BlockPos(1, 0, 2), qualifier);
        states.put(new BlockPos(1, 0, 3), support(0, Direction.WEST));
        states.put(wall, Blocks.STONE.getDefaultState());
        BlockView view = CopingPlacementLaunchProbe.view(states);
        Vec3d start = new Vec3d(.5, .96875, 3.5), end = new Vec3d(.5, .96875, .5);
        var entity = new net.minecraft.entity.MarkerEntity(net.minecraft.entity.EntityType.MARKER, null);
        var hit = view.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, entity));
        require(hit.getBlockPos().equals(wall), "Early DDA cell selected a cornice behind a full occluding wall: " + hit.getBlockPos());
        BlockPos firstCell = new BlockPos(0, 0, 3);
        require(CorniceRaycast.closest(view, start, end, firstCell, states.getOrDefault(firstCell, Blocks.AIR.getDefaultState()), null) == null,
                "Cornice helper returned a later-cell hit before DDA reached the wall");
        states.remove(wall);
        var visible = view.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, entity));
        require(visible.getBlockPos().equals(rearOwner), "DDA cell restriction made the exposed overhanging cornice unselectable");
        System.out.println("ERYDON_CORNICE_OCCLUSION_OK: actual mixed-in world ray stops at the front wall; removing it selects the rear overhang owner");
    }

    private static void redrawDependencies() throws Exception {
        CorniceBlock cornice = cornice("modern");
        BlockState state = cornice.getDefaultState().with(CorniceBlock.SHAPE, CorniceBlock.CorniceShape.DIAGONAL_PRIMARY);
        BlockPos owner = new BlockPos(15, 7, 15), remote = owner.add(4, 0, 0), outside = owner.add(5, 0, 0);
        NotificationWorld world = DoubleColumnRepairLaunchProbe.allocate(NotificationWorld.class);
        world.states = new HashMap<>(); world.redraws = new java.util.LinkedHashSet<>();
        world.states.put(owner, state); world.states.put(remote, state);
        world.states.put(outside, state.with(CorniceBlock.FACING, Direction.EAST));
        world.states.put(owner.west(), support(0, Direction.NORTH));
        world.states.put(remote.west(), support(0, Direction.NORTH));
        world.states.put(outside.north(), support(0, Direction.EAST));
        cornice.onBlockAdded(state, world, owner, Blocks.AIR.getDefaultState(), false);
        require(world.redraws.contains(owner) && world.redraws.contains(remote) && !world.redraws.contains(outside),
                "Command/Axiom block addition did not redraw the bounded four-cell dependency across a chunk border");
        require(world.writes == 0, "Distant redraw recalculated saved states instead of using listener-only notifications");
        world.redraws.clear();
        BlockPos returnWall = owner.north();
        world.states.put(returnWall, Blocks.STONE.getDefaultState());
        BlockState unchanged = cornice.getStateForNeighborUpdate(state, Direction.NORTH, Blocks.STONE.getDefaultState(), world, owner, returnWall);
        require(unchanged == state && world.redraws.contains(owner) && world.redraws.contains(remote) && !world.redraws.contains(outside),
                "A same-owner return wall update left an unchanged saved pose or its remote peer unredrawn");
        world.redraws.clear(); world.states.remove(returnWall);
        require(cornice.getStateForNeighborUpdate(state, Direction.NORTH, Blocks.AIR.getDefaultState(), world, owner, returnWall) == state
                        && world.redraws.contains(remote), "Removing a return wall did not redraw the distant live reciprocal dependency");
        world.redraws.clear(); world.states.remove(owner);
        cornice.onStateReplaced(state, world, owner, Blocks.AIR.getDefaultState(), false);
        require(world.redraws.contains(remote) && !world.redraws.contains(outside) && world.writes == 0,
                "Removing an owner left a remote reciprocal profile stale or wrote distant saved states");
        System.out.println("ERYDON_CORNICE_REDRAW_OK: real block-added, stable return-wall update/removal and owner-removal hooks redraw dependencies four cells away across a chunk border using listener-only flags");
    }

    /** Constructor bypass is test-only; every operation used by the actual hooks is captured in memory. */
    private static final class NotificationWorld extends net.minecraft.server.world.ServerWorld {
        Map<BlockPos, BlockState> states;
        java.util.Set<BlockPos> redraws;
        int writes;
        private NotificationWorld() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.getDefaultState()); }
        @Override public boolean setBlockState(BlockPos pos, BlockState state, int flags) {
            writes++;
            states.put(pos.toImmutable(), state);
            return true;
        }
        @Override public void updateListeners(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
            require(oldState == newState && flags == Block.NOTIFY_LISTENERS,
                    "Cornice redraw dispatch may recurse into neighbour updates or alter the saved pose");
            redraws.add(pos.toImmutable());
        }
    }

    private static void joins(CorniceBlock cornice) throws Exception {
        int joined = 0, detached = 0;
        boolean inside = false, outside = false, oppositeHands = false;
        for (CorniceBlock.CorniceShape shape : CorniceBlock.CorniceShape.values()) {
            if (!shape.isAngled()) continue;
            for (Direction facing : Direction.Type.HORIZONTAL) {
                BlockState source = cornice.getDefaultState().with(CorniceBlock.SHAPE, shape).with(CorniceBlock.FACING, facing);
                for (Direction otherFacing : Direction.Type.HORIZONTAL) for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                    if (x == 0 && z == 0) continue;
                    BlockPos otherPos = new BlockPos(x, 0, z);
                    BlockState target = cornice.getDefaultState().with(CorniceBlock.FACING, otherFacing);
                    BlockView view = CopingPlacementLaunchProbe.view(Map.of(BlockPos.ORIGIN, source, otherPos, target));
                    var one = CorniceConnections.components(view, source, BlockPos.ORIGIN).get(0);
                    var two = CorniceConnections.components(view, target, otherPos).get(0);
                    if (!one.joinedStart() && !one.joinedEnd()) {
                        require(!two.joinedStart() && !two.joinedEnd(), "Detached/gap source leaves a one-sided target cut");
                        detached++; continue;
                    }
                    require(two.joinedStart() || two.joinedEnd(), "Angular joint is not reciprocal");
                    boolean end = one.joinedEnd();
                    boolean targetEnd = two.joinedEnd();
                    for (double depth : new double[]{0, .0625, .1875, .3125, .5}) {
                        CorniceRun.Point a = one.point(end ? 1 : 0, depth), b = two.point(targetEnd ? 1 : 0, depth);
                        require(Math.hypot(a.x() - otherPos.getX() - b.x(), a.z() - otherPos.getZ() - b.z()) < EPS,
                                "Resolved cardinal/angular profile depths do not share their actual cut");
                        require(one.start().at(depth) < one.end().at(depth) && two.start().at(depth) < two.end().at(depth),
                                "Resolved corner inverted the profile stretch");
                    }
                    double shift = end ? one.end().slope() : -one.start().slope();
                    inside |= shift < -.000001; outside |= shift > .000001;
                    if (joined < 24) {
                        com.oliver.erydon.client.model.CorniceMeshLaunchChecks.verify(view, source, BlockPos.ORIGIN);
                        com.oliver.erydon.client.model.CorniceMeshLaunchChecks.verify(view, target, otherPos);
                    }
                    joined++;
                }
            }
        }
        require(joined > 0 && inside && outside, "Fixtures failed to exercise both shortening inner and extended outer angular joins");
        // A mixed pair of saved handed profiles exercises diagonal-to-diagonal connection as well.
        for (CorniceBlock.CorniceShape a : CorniceBlock.CorniceShape.values()) for (CorniceBlock.CorniceShape b : CorniceBlock.CorniceShape.values()) {
            if (!a.isAngled() || !b.isAngled() || !a.name().contains("LEFT") || !b.name().contains("RIGHT")) continue;
            for (Direction direction : Direction.Type.HORIZONTAL) for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                if (x == 0 && z == 0) continue;
                BlockState source = cornice.getDefaultState().with(CorniceBlock.SHAPE, a);
                BlockState target = cornice.getDefaultState().with(CorniceBlock.SHAPE, b).with(CorniceBlock.FACING, direction);
                BlockPos other = new BlockPos(x, 0, z);
                BlockView view = CopingPlacementLaunchProbe.view(Map.of(BlockPos.ORIGIN, source, other, target));
                var one = CorniceConnections.components(view, source, BlockPos.ORIGIN).get(0);
                if (one.joinedStart() || one.joinedEnd()) {
                    var two = CorniceConnections.components(view, target, other).get(0);
                    require(two.joinedStart() || two.joinedEnd(), "Opposite-hand angular join is one-sided");
                    oppositeHands = true;
                }
            }
        }
        require(oppositeHands, "Saved left/right slope corners were not exercised");
        System.out.println("ERYDON_CORNICE_JOINS_OK: " + joined + " reciprocal cardinal/angular profile seams; " + detached
                + " detached or unsafe pairs; inner/outer and opposite-handed corners");
    }

    private static void ambiguity(CorniceBlock cornice) {
        // All three rear endpoints meet at (-1,1). The third owner at x=-3
        // lies outside the source radius but inside the target radius.
        BlockState source = cornice.getDefaultState().with(CorniceBlock.SHAPE, CorniceBlock.CorniceShape.DIAGONAL_PRIMARY);
        BlockState target = source.with(CorniceBlock.FACING, Direction.EAST);
        BlockState conflicting = source.with(CorniceBlock.FACING, Direction.SOUTH);
        BlockPos targetPos = new BlockPos(-2, 0, 1), conflictPos = new BlockPos(-3, 0, 1);
        BlockView pair = CopingPlacementLaunchProbe.view(Map.of(BlockPos.ORIGIN, source, targetPos, target));
        require(CorniceConnections.components(pair, source, BlockPos.ORIGIN).get(0).joinedStart(),
                "Boundary ambiguity fixture failed to begin with a valid actual end join");
        BlockView triple = CopingPlacementLaunchProbe.view(Map.of(BlockPos.ORIGIN, source, targetPos, target, conflictPos, conflicting));
        var one = CorniceConnections.components(triple, source, BlockPos.ORIGIN).get(0);
        var two = CorniceConnections.components(triple, target, targetPos).get(0);
        var three = CorniceConnections.components(triple, conflicting, conflictPos).get(0);
        require(!one.joinedStart() && !two.joinedEnd() && !three.joinedStart(),
                "A target's wider discovery radius left a one-sided ambiguous join");
        BlockState shortRun = cornice.getDefaultState().with(CorniceBlock.SHAPE, CorniceBlock.CorniceShape.BROAD_RIGHT_PRIMARY);
        BlockState incoming = shortRun.with(CorniceBlock.SHAPE, CorniceBlock.CorniceShape.BROAD_LEFT_PRIMARY);
        BlockState outgoing = incoming.with(CorniceBlock.FACING, Direction.SOUTH);
        BlockPos incomingPos = new BlockPos(0, 0, 1), outgoingPos = new BlockPos(-1, 0, 0);
        BlockView pinched = CopingPlacementLaunchProbe.view(Map.of(BlockPos.ORIGIN, shortRun,
                incomingPos, incoming, outgoingPos, outgoing));
        for (var entry : Map.of(BlockPos.ORIGIN, shortRun, incomingPos, incoming, outgoingPos, outgoing).entrySet()) {
            var segment = CorniceConnections.components(pinched, entry.getValue(), entry.getKey()).get(0);
            require(!segment.joinedStart() && !segment.joinedEnd(),
                    "Combined end cuts invert a short profile or leave either neighbour's cut behind");
        }
        System.out.println("ERYDON_CORNICE_AMBIGUITY_OK: conflicting third owner outside the first discovery radius rejects all reciprocal cuts");
    }

    private static BlockState support(int kind, Direction facing) {
        String name = kind == 0 ? "glacium_slope_vertical" : kind < 3 ? "glacium_slope_vertical_shallow_broad" : "glacium_slope_vertical_shallow_narrow";
        BlockState state = block(name).getDefaultState().with(CorniceBlock.FACING, facing);
        if (kind == 1 || kind == 2) state = state.with(SlopeVerticalShallowBroadBlock.HAND,
                kind == 1 ? SlopeVerticalShallowBroadBlock.Handedness.RIGHT : SlopeVerticalShallowBroadBlock.Handedness.LEFT);
        if (kind == 3 || kind == 4) state = state.with(SlopeVerticalShallowNarrowBlock.HAND,
                kind == 3 ? SlopeVerticalShallowNarrowBlock.Handedness.RIGHT : SlopeVerticalShallowNarrowBlock.Handedness.LEFT);
        return state;
    }

    private static List<Direction> fronts(BlockState support) {
        List<Direction> result = new ArrayList<>();
        for (Direction side : Direction.Type.HORIZONTAL) if (CorniceSlopePlacement.shapeFor(support, side) != null) result.add(side);
        return result;
    }

    private static CorniceBlock cornice(String style) { return (CorniceBlock) block("glacium_cornice_" + style); }
    private static Block block(String name) {
        Block result = Registries.BLOCK.get(new Identifier("erydon", name));
        require(result != Blocks.AIR, "Missing cornice probe fixture " + name);
        return result;
    }
    private static CorniceRun.Point reflect(CorniceRun.Point point, BlockMirror mirror) {
        return mirror == BlockMirror.LEFT_RIGHT ? new CorniceRun.Point(point.x(), 1 - point.z())
                : new CorniceRun.Point(1 - point.x(), point.z());
    }
    private static boolean close(CorniceRun.Point a, CorniceRun.Point b) { return Math.hypot(a.x() - b.x(), a.z() - b.z()) < EPS; }
    private static WorldAccess access(Map<BlockPos, BlockState> states) {
        return (WorldAccess) Proxy.newProxyInstance(WorldAccess.class.getClassLoader(), new Class<?>[]{WorldAccess.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "getBlockState" -> states.getOrDefault(args[0], Blocks.AIR.getDefaultState());
                case "getFluidState" -> states.getOrDefault(args[0], Blocks.AIR.getDefaultState()).getFluidState();
                case "getHeight" -> 384;
                case "getBottomY" -> -64;
                case "getBlockEntity", "scheduleFluidTick" -> null;
                default -> {
                    if (method.isDefault()) yield InvocationHandler.invokeDefault(proxy, method, args);
                    throw new UnsupportedOperationException(method.toString());
                }
            };
        });
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
