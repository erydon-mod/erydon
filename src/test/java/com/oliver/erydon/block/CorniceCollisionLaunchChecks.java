package com.oliver.erydon.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.CuboidBlockIterator;
import net.minecraft.util.Identifier;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockCollisionSpliterator;
import net.minecraft.world.BlockView;
import net.minecraft.world.CollisionView;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.border.WorldBorder;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Real engine discovery, including iterator corners and attached owners outside vanilla padding. */
public final class CorniceCollisionLaunchChecks {
    private static int samples, outsideOwners, cornerOwners, pairedScenes;
    private static volatile Object blackhole;
    private CorniceCollisionLaunchChecks() { }

    public static void run() throws Exception {
        ordinaryReadBudget();
        nativeNeighbourUpdateBudget();
        exactCorner();
        extendedOwner(BlockPos.ORIGIN, false);
        extendedOwner(new BlockPos(16, 0, 16), true);
        for (String style : List.of("modern", "georgian", "byzantine", "gothic")) {
            CorniceBlock block = cornice(style);
            for (CorniceBlock.CorniceShape shape : CorniceBlock.CorniceShape.values()) {
                if (!shape.isAngled()) continue;
                for (var facing : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
                    BlockState state = block.getDefaultState().with(CorniceBlock.SHAPE, shape).with(CorniceBlock.FACING, facing);
                    Map<BlockPos, BlockState> states = new HashMap<>();
                    states.put(BlockPos.ORIGIN, state);
                    addSupport(states, BlockPos.ORIGIN, state);
                    sample(new Fixture(states), BlockPos.ORIGIN, state);
                }
            }
        }
        acceptedPairs();
        offThreadDiscovery();
        engineBenchmark();
        System.out.println("ERYDON_CORNICE_ENGINE_COLLISION_OK: " + samples + " actual engine/owner collision samples; "
                + cornerOwners + " two-boundary owners; " + outsideOwners + " owners outside vanilla padding; "
                + pairedScenes + " supported pair/rotation scenes; corner, same-owner return, loaded chunk-border, suffocation and ordinary-read regressions");
    }

    private static void engineBenchmark() {
        var management = ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean meter = management instanceof com.sun.management.ThreadMXBean supported
                && supported.isThreadAllocatedMemorySupported() ? supported : null;
        if (meter != null && !meter.isThreadAllocatedMemoryEnabled()) meter.setThreadAllocatedMemoryEnabled(true);
        CorniceBlock block = cornice("modern");
        BlockState corner = block.getDefaultState().with(CorniceBlock.SHAPE, CorniceBlock.CorniceShape.DIAGONAL_PRIMARY);
        BlockState source = block.getDefaultState().with(CorniceBlock.SHAPE, CorniceBlock.CorniceShape.BROAD_RIGHT_SECONDARY);
        BlockState target = block.getDefaultState().with(CorniceBlock.SHAPE, CorniceBlock.CorniceShape.NARROW_RIGHT_SECONDARY)
                .with(CorniceBlock.FACING, net.minecraft.util.math.Direction.WEST);
        Map<BlockPos, BlockState> extended = new HashMap<>();
        extended.put(BlockPos.ORIGIN, source); extended.put(new BlockPos(1, 0, -2), target);
        addSupport(extended, BlockPos.ORIGIN, source); addSupport(extended, new BlockPos(1, 0, -2), target);
        Fixture[] fixtures = {new Fixture(Map.of(BlockPos.ORIGIN, Blocks.STONE.getDefaultState()), false),
                new Fixture(Map.of(BlockPos.ORIGIN, corner, BlockPos.ORIGIN.west(), support(corner)), false), new Fixture(extended, false)};
        Box[] queries = {new Box(.2, .2, .2, .3, .3, .3), new Box(-.60, .90, 1.23, -.50, .98, 1.33),
                query(BlockPos.ORIGIN, CorniceConnections.components(fixtures[2].view, source, BlockPos.ORIGIN).get(0).point(.95, .46))};
        String[] names = {"ordinary-stone", "two-boundary-cornice", "supplemental-outside-owner"};
        int operations = 512;
        for (int scene = 0; scene < fixtures.length; scene++) {
            Fixture fixture = fixtures[scene]; Box query = queries[scene];
            for (int warmup = 0; warmup < 512; warmup++) consumeCollisions(fixture, query);
            double[] time = new double[7], allocation = new double[7], reads = new double[7];
            for (int trial = 0; trial < 7; trial++) {
                int beforeReads = fixture.reads;
                long beforeBytes = meter == null ? -1 : meter.getThreadAllocatedBytes(Thread.currentThread().getId());
                long before = System.nanoTime();
                for (int operation = 0; operation < operations; operation++) consumeCollisions(fixture, query);
                time[trial] = (System.nanoTime() - before) / (double) operations;
                allocation[trial] = meter == null ? -1 : (meter.getThreadAllocatedBytes(Thread.currentThread().getId()) - beforeBytes) / (double) operations;
                reads[trial] = (fixture.reads - beforeReads) / (double) operations;
            }
            Arrays.sort(time); Arrays.sort(allocation); Arrays.sort(reads);
            System.out.printf(Locale.ROOT,
                    "ERYDON_CORNICE_PERF_ENGINE: %s; warm engine median %.0f ns/query (%.0f..%.0f); %.0f allocated bytes/query; %.0f state reads/query; 7x512 calls after512warmups; no position-tracking QA allocation%n",
                    names[scene], time[3], time[0], time[6], allocation[3], reads[3]);
        }
    }

    private static void consumeCollisions(Fixture fixture, Box query) {
        for (VoxelShape shape : fixture.view.getBlockCollisions(null, query)) blackhole = shape;
    }

    private static void exactCorner() {
        BlockState state = cornice("modern").getDefaultState().with(CorniceBlock.SHAPE, CorniceBlock.CorniceShape.DIAGONAL_PRIMARY);
        Fixture fixture = new Fixture(Map.of(BlockPos.ORIGIN, state, BlockPos.ORIGIN.west(), support(state)));
        requireOwner(fixture, BlockPos.ORIGIN, new Box(-.60, .90, 1.23, -.50, .98, 1.33));
        require(owners(fixture, new Box(-.60, .90, 1.23, -.50, .98, 1.33), true).isEmpty(),
                "Nonopaque cornice made a head-suffocation collision");
        for (var rotation : BlockRotation.values()) {
            Map<BlockPos, BlockState> returned = new HashMap<>();
            returned.put(BlockPos.ORIGIN, state);
            returned.put(BlockPos.ORIGIN.west(), support(state));
            returned.put(BlockPos.ORIGIN.north(), Blocks.STONE.getDefaultState());
            Fixture rotated = rotated(returned, rotation);
            BlockState posed = state.rotate(rotation);
            require(CorniceConnections.components(rotated.view, posed, BlockPos.ORIGIN).size() == 2,
                    "Actual wall-return collision fixture lost one arm");
            sample(rotated, BlockPos.ORIGIN, posed);
        }
    }

    private static void extendedOwner(BlockPos origin, boolean border) {
        CorniceBlock block = cornice("modern");
        BlockState source = block.getDefaultState().with(CorniceBlock.SHAPE, CorniceBlock.CorniceShape.BROAD_RIGHT_SECONDARY);
        BlockState target = block.getDefaultState().with(CorniceBlock.SHAPE, CorniceBlock.CorniceShape.NARROW_RIGHT_SECONDARY)
                .with(CorniceBlock.FACING, net.minecraft.util.math.Direction.WEST);
        BlockPos peer = origin.add(1, 0, -2);
        Map<BlockPos, BlockState> states = new HashMap<>();
        states.put(origin, source); states.put(peer, target);
        require(addSupport(states, origin, source) && addSupport(states, peer, target), "Extended fixture supports overlap its owners");
        Fixture fixture = new Fixture(states);
        var segment = CorniceConnections.components(fixture.view, source, origin).get(0);
        require(segment.joinedEnd(), "Extended collision fixture lost its actual mitre");
        var point = segment.point(.95, .46);
        Box query = query(origin, point);
        require(!insideVanilla(origin, query), "Extended regression does not lie outside vanilla padding");
        VoxelShape peerShape = target.getCollisionShape(fixture.view, peer).offset(peer.getX(), peer.getY(), peer.getZ());
        require(!VoxelShapes.matchesAnywhere(peerShape, VoxelShapes.cuboid(query), BooleanBiFunction.AND),
                "Peer collision masks the missing source-only overhang");
        requireOwner(fixture, origin, query);
        require(owners(fixture, query, true).isEmpty(), "Supplemental nonopaque cornice became a suffocation collision");
        extendedOwnerRaycasts(states, origin, source);
        if (border) require(fixture.chunkRequests.contains(ChunkPos.toLong(1, 1)), "Supplemental owner across chunk border was never read");
    }

    private static void extendedOwnerRaycasts(Map<BlockPos, BlockState> states, BlockPos origin, BlockState source) {
        var marker = new net.minecraft.entity.MarkerEntity(net.minecraft.entity.EntityType.MARKER, null);
        int selected = 0;
        for (BlockRotation rotation : BlockRotation.values()) {
            Fixture fixture = rotated(states, rotation);
            BlockPos owner = rotate(origin, rotation);
            BlockState posed = source.rotate(rotation);
            var segment = CorniceConnections.components(fixture.view, posed, owner).get(0);
            var point = segment.point(.95, .46);
            Vec3d start = new Vec3d(owner.getX() + point.x(), owner.getY() + 1.1, owner.getZ() + point.z());
            Vec3d end = new Vec3d(start.x, owner.getY() + .8, start.z);
            BlockPos cell = BlockPos.ofFloored(start.x, owner.getY() + .985, start.z);
            require(Math.max(Math.abs(cell.getX() - owner.getX()), Math.abs(cell.getZ() - owner.getZ())) == 2,
                    "Extended ray fixture no longer exercises two-cell owner discovery");
            var actual = fixture.view.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.NONE, marker));
            require(actual.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK && actual.getBlockPos().equals(owner),
                    "Actual radius-two selection missed source owner " + owner + " for " + rotation + ": " + actual);
            BlockState cellState = fixture.states.getOrDefault(cell, Blocks.AIR.getDefaultState());
            require(cellState.getBlock() instanceof CorniceBlock, "Expected peer cornice in traversed selection cell");
            var replay = CorniceRaycast.closest(fixture.view, start, end, cell, cellState, null);
            require(replay != null && replay.getBlockPos().equals(owner),
                    "Six-argument radius-two ray replay lost the source beyond a peer-owned cell");
            selected++;
        }
        System.out.println("ERYDON_CORNICE_RADIUS_TWO_SELECTION_OK: " + selected
                + " rotations with actual world rays and six-argument replays select the source-only tip two cells from its owner at " + origin);
    }

    private static void acceptedPairs() {
        CorniceBlock block = cornice("modern");
        for (CorniceBlock.CorniceShape shape : CorniceBlock.CorniceShape.values()) {
            if (!shape.isAngled()) continue;
            BlockState source = block.getDefaultState().with(CorniceBlock.SHAPE, shape);
            for (CorniceBlock.CorniceShape other : CorniceBlock.CorniceShape.values()) {
                if (other == CorniceBlock.CorniceShape.OUTER_CORNER) continue;
                for (var facing : net.minecraft.util.math.Direction.Type.HORIZONTAL) for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                    if (x == 0 && z == 0) continue;
                    BlockPos peer = new BlockPos(x, 0, z);
                    BlockState target = block.getDefaultState().with(CorniceBlock.SHAPE, other).with(CorniceBlock.FACING, facing);
                    Map<BlockPos, BlockState> states = new HashMap<>();
                    states.put(BlockPos.ORIGIN, source); states.put(peer, target);
                    if (!addSupport(states, BlockPos.ORIGIN, source) || !addSupport(states, peer, target)) continue;
                    Fixture fixture = new Fixture(states);
                    if (CorniceConnections.components(fixture.view, source, BlockPos.ORIGIN).stream()
                            .noneMatch(segment -> segment.joinedStart() || segment.joinedEnd())) continue;
                    for (var rotation : BlockRotation.values()) {
                        Fixture posed = rotated(states, rotation);
                        sample(posed, BlockPos.ORIGIN, source.rotate(rotation));
                        sample(posed, rotate(peer, rotation), target.rotate(rotation));
                        pairedScenes++;
                    }
                }
            }
        }
        require(pairedScenes > 0, "No supported accepted mitres reached engine collision proof");
    }

    private static void sample(Fixture fixture, BlockPos owner, BlockState state) {
        var direct = state.getCollisionShape(fixture.view, owner).offset(owner.getX(), owner.getY(), owner.getZ());
        for (var segment : CorniceConnections.components(fixture.view, state, owner))
            for (double u : new double[]{.0001, .5, .9999}) for (double depth : new double[]{.10, .30}) {
                Box query = query(owner, segment.point(u, depth));
                if (!VoxelShapes.matchesAnywhere(direct, VoxelShapes.cuboid(query), BooleanBiFunction.AND)) continue;
                requireOwner(fixture, owner, query);
            }
    }

    private static void requireOwner(Fixture fixture, BlockPos owner, Box query) {
        var found = owners(fixture, query, false);
        require(found.contains(owner), "Engine missed cornice owner " + owner + " for " + query + "; found " + found);
        boolean collision = false;
        for (var ignored : fixture.view.getBlockCollisions(null, query)) collision = true;
        require(collision, "Actual getBlockCollisions returned no collision for a visible cornice");
        require(new HashSet<>(found).size() == found.size(), "Supplemental owner emitted duplicate collision entries");
        if (!insideVanilla(owner, query)) outsideOwners++;
        else if (boundaryAxes(owner, query) == 2) cornerOwners++;
        samples++;
    }

    private static void ordinaryReadBudget() {
        for (Map<BlockPos, BlockState> states : List.of(Map.<BlockPos, BlockState>of(), Map.of(BlockPos.ORIGIN, Blocks.STONE.getDefaultState()))) {
            Fixture fixture = new Fixture(states);
            Box query = new Box(.2, .2, .2, .3, .3, .3);
            owners(fixture, query, false);
            require(fixture.reads == 19, "Ordinary collision query added state reads or expanded vanilla bounds: " + fixture.reads);
        }
        System.out.println("ERYDON_CORNICE_COLLISION_READ_BUDGET_OK: empty and ordinary stone queries retain vanilla's19 state reads; supplemental owner discovery has at most2 candidate reads per visited vertical slope and skips owners already in the vanilla range");
    }

    private static void nativeNeighbourUpdateBudget() {
        CorniceBlock block = cornice("modern");
        BlockState nativeState = block.getDefaultState();
        Map<BlockPos, BlockState> states = new HashMap<>();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            states.put(new BlockPos(x, 0, z), nativeState);
        int[] reads = {0};
        var access = (net.minecraft.world.WorldAccess) Proxy.newProxyInstance(net.minecraft.world.WorldAccess.class.getClassLoader(),
                new Class<?>[]{net.minecraft.world.WorldAccess.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getBlockState" -> { reads[0]++; yield states.getOrDefault(args[0], Blocks.AIR.getDefaultState()); }
                    case "getFluidState" -> states.getOrDefault(args[0], Blocks.AIR.getDefaultState()).getFluidState();
                    case "getHeight" -> 384;
                    case "getBottomY" -> -64;
                    case "getBlockEntity" -> null;
                    default -> {
                        if (method.isDefault()) yield InvocationHandler.invokeDefault(proxy, method, args);
                        throw new UnsupportedOperationException(method.toString());
                    }
                });
        BlockState unsupported = block.getStateForNeighborUpdate(nativeState, net.minecraft.util.math.Direction.NORTH,
                nativeState, access, BlockPos.ORIGIN, BlockPos.ORIGIN.north());
        require(unsupported.isAir() && reads[0] == 9,
                "Native update entered recursive cornice geometry scans or accepted a thin cornice wall: " + reads[0]);
        int unsupportedReads = reads[0];
        states.put(BlockPos.ORIGIN.north(), Blocks.STONE.getDefaultState()); reads[0] = 0;
        BlockState attached = block.getStateForNeighborUpdate(nativeState, net.minecraft.util.math.Direction.NORTH,
                Blocks.STONE.getDefaultState(), access, BlockPos.ORIGIN, BlockPos.ORIGIN.north());
        require(attached.equals(nativeState) && reads[0] == 7,
                "Native full-stone attachment changed state or ran unbounded neighbour geometry: " + reads[0]);
        System.out.println("ERYDON_CORNICE_NATIVE_UPDATE_BUDGET_OK: dense thin-cornice neighbours remove unsupported native run in "
                + unsupportedReads + " reads; real stone attachment retains native state in " + reads[0] + " reads; no recursive shape scans");
    }

    private static void offThreadDiscovery() throws Exception {
        var world = DoubleColumnRepairLaunchProbe.allocate(OffThreadWorld.class);
        world.server = DoubleColumnRepairLaunchProbe.allocate(OffThreadServer.class);
        world.server.owner = Thread.currentThread();
        var state = cornice("modern").getDefaultState().with(CorniceBlock.SHAPE, CorniceBlock.CorniceShape.BROAD_RIGHT_SECONDARY);
        world.fixture = new Fixture(Map.of(BlockPos.ORIGIN, state, BlockPos.ORIGIN.north(), support(state)));
        var worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                Box query = new Box(1.57, .97, -1.15, 1.59, .99, -1.12);
                for (var ignored : world.getBlockCollisions(null, query)) { }
            }).get(10, TimeUnit.SECONDS);
            require(world.directReads == 0 && !world.fixture.ownerReads.contains(BlockPos.ORIGIN),
                    "Off-thread collision discovery queried a supplemental owner or synchronous world state");
        } finally { worker.shutdownNow(); }
        System.out.println("ERYDON_CORNICE_COLLISION_THREAD_OK: off-thread ServerWorld traversal makes no supplemental owner or direct world lookup");
    }

    private static List<BlockPos> owners(CollisionView view, Box query, boolean suffocating) {
        List<BlockPos> result = new ArrayList<>();
        var iterator = new BlockCollisionSpliterator<BlockPos>(view, null, query, suffocating, (owner, shape) -> owner.toImmutable());
        while (iterator.hasNext()) result.add(iterator.next());
        return result;
    }
    private static List<BlockPos> owners(Fixture fixture, Box query, boolean suffocating) { return owners(fixture.view, query, suffocating); }
    private static Box query(BlockPos owner, CorniceRun.Point point) {
        return new Box(owner.getX() + point.x() - .002, owner.getY() + .981, owner.getZ() + point.z() - .002,
                owner.getX() + point.x() + .002, owner.getY() + .989, owner.getZ() + point.z() + .002);
    }
    private static boolean insideVanilla(BlockPos owner, Box box) { return boundaryAxes(owner, box) >= 0; }
    private static int boundaryAxes(BlockPos owner, Box box) {
        int result = 0;
        double[] low = {box.minX, box.minY, box.minZ}, high = {box.maxX, box.maxY, box.maxZ};
        int[] position = {owner.getX(), owner.getY(), owner.getZ()};
        for (int axis = 0; axis < 3; axis++) {
            int first = MathHelper.floor(low[axis] - 1e-7) - 1, last = MathHelper.floor(high[axis] + 1e-7) + 1;
            if (position[axis] < first || position[axis] > last) return -1;
            if (position[axis] == first || position[axis] == last) result++;
        }
        return result;
    }
    private static boolean addSupport(Map<BlockPos, BlockState> states, BlockPos owner, BlockState state) {
        if (!state.get(CorniceBlock.SHAPE).isAngled()) return true;
        BlockPos pos = CorniceSlopePlacement.supportPos(state, owner);
        BlockState support = support(state), existing = states.get(pos);
        if (existing != null && !existing.equals(support)) return false;
        states.put(pos, support); return true;
    }
    private static BlockState support(BlockState cornice) {
        if (cornice.get(CorniceBlock.SHAPE).isHalfSupport()) return CorniceHalfSupportLaunchChecks.supportFor(cornice);
        String name = cornice.get(CorniceBlock.SHAPE).name();
        boolean broad = name.startsWith("BROAD"), narrow = name.startsWith("NARROW");
        String path = broad ? "glacium_slope_vertical_shallow_broad" : narrow ? "glacium_slope_vertical_shallow_narrow" : "glacium_slope_vertical";
        BlockState state = Registries.BLOCK.get(new Identifier("erydon", path)).getDefaultState()
                .with(CorniceBlock.FACING, cornice.get(CorniceBlock.FACING));
        if (broad) state = state.with(SlopeVerticalShallowBroadBlock.HAND, name.contains("LEFT")
                ? SlopeVerticalShallowBroadBlock.Handedness.LEFT : SlopeVerticalShallowBroadBlock.Handedness.RIGHT);
        if (narrow) state = state.with(SlopeVerticalShallowNarrowBlock.HAND, name.contains("LEFT")
                ? SlopeVerticalShallowNarrowBlock.Handedness.LEFT : SlopeVerticalShallowNarrowBlock.Handedness.RIGHT);
        return state;
    }
    private static CorniceBlock cornice(String style) { return (CorniceBlock) Registries.BLOCK.get(new Identifier("erydon", "glacium_cornice_" + style)); }
    private static Fixture rotated(Map<BlockPos, BlockState> states, BlockRotation rotation) {
        Map<BlockPos, BlockState> result = new HashMap<>();
        states.forEach((pos, state) -> result.put(rotate(pos, rotation), state.rotate(rotation)));
        return new Fixture(result);
    }
    private static BlockPos rotate(BlockPos pos, BlockRotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new BlockPos(-pos.getZ(), pos.getY(), pos.getX());
            case CLOCKWISE_180 -> new BlockPos(-pos.getX(), pos.getY(), -pos.getZ());
            case COUNTERCLOCKWISE_90 -> new BlockPos(pos.getZ(), pos.getY(), -pos.getX());
            default -> pos;
        };
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class Fixture {
        final CollisionView view;
        final Map<BlockPos, BlockState> states;
        final Set<Long> chunkRequests = new HashSet<>();
        final Set<BlockPos> ownerReads = new HashSet<>();
        final boolean track;
        int reads;
        Fixture(Map<BlockPos, BlockState> states) { this(states, true); }
        Fixture(Map<BlockPos, BlockState> states, boolean track) {
            this.track = track;
            this.states = Map.copyOf(states);
            view = (CollisionView) Proxy.newProxyInstance(CollisionView.class.getClassLoader(), new Class<?>[]{CollisionView.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getBlockState" -> { reads++; if (this.track) ownerReads.add(((BlockPos) args[0]).toImmutable()); yield this.states.getOrDefault(args[0], Blocks.AIR.getDefaultState()); }
                        case "getChunkAsView" -> { if (this.track) chunkRequests.add(ChunkPos.toLong((int) args[0], (int) args[1])); yield proxy; }
                        case "getFluidState" -> this.states.getOrDefault(args[0], Blocks.AIR.getDefaultState()).getFluidState();
                        case "getEntityCollisions" -> List.of();
                        case "getWorldBorder" -> new WorldBorder();
                        case "getBlockEntity" -> null;
                        case "getHeight" -> 384;
                        case "getBottomY" -> -64;
                        default -> {
                            if (method.isDefault()) yield InvocationHandler.invokeDefault(proxy, method, args);
                            throw new UnsupportedOperationException(method.toString());
                        }
                    });
        }
    }
    private static final class OffThreadServer extends IntegratedServer {
        Thread owner;
        private OffThreadServer() { super(null, null, null, null, null, null, null); }
        @Override public Thread getThread() { return owner; }
    }
    private static final class OffThreadWorld extends ServerWorld {
        OffThreadServer server;
        Fixture fixture;
        int directReads;
        private OffThreadWorld() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
        @Override public MinecraftServer getServer() { return server; }
        @Override public BlockView getChunkAsView(int x, int z) { return fixture.view; }
        @Override public BlockState getBlockState(BlockPos pos) { directReads++; throw new AssertionError("Off-thread direct world lookup: " + pos); }
    }
}
