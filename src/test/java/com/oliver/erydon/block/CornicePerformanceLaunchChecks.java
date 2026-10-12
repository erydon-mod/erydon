package com.oliver.erydon.block;

import com.google.common.cache.Cache;
import com.oliver.erydon.migration.ErydonIdMigration;
import com.sun.management.ThreadMXBean;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** Bounded production CPU/allocation audit; these timings are not an in-game FPS result. */
public final class CornicePerformanceLaunchChecks {
    private static final int TRIALS = 7;
    private static final int WARMUP = 2000;
    private static final int OPERATIONS = 4000;
    // Source support: 4 reads. Each 25-owner discovery: 24 + 25 * 4 support reads.
    // At most two components with two reciprocal ports: 4 + 5 * 124 = 624.
    private static final int MAX_CONNECTION_READS = 624;
    private static volatile Object blackhole;
    private static final ThreadMXBean ALLOCATIONS = allocations();
    private CornicePerformanceLaunchChecks() { }

    /** The same resolved production fixtures are available to the real baked-mesh/cache audit. */
    public record GeometryCase(String name, List<CorniceConnections.Segment> segments) {
        public GeometryCase { segments = List.copyOf(segments); }
    }

    public static List<GeometryCase> geometryCases() {
        List<GeometryCase> cases = new ArrayList<>();
        for (Fixture fixture : fixtures(cornice("modern"))) {
            var segments = CorniceConnections.components(fixture.world.view, fixture.state, BlockPos.ORIGIN);
            if (!segments.isEmpty()) cases.add(new GeometryCase(fixture.name, segments));
        }
        return List.copyOf(cases);
    }

    public static void run() throws Exception {
        CorniceLightingLaunchChecks.run();
        breakParticleBudget();
        long started = System.nanoTime();
        CorniceBlock cornice = cornice("modern");
        List<Fixture> fixtures = fixtures(cornice);
        extendedJoinRaycasts(fixtures);
        radiusTwoBorderRaycasts(cornice);
        System.out.printf(Locale.ROOT,
                "ERYDON_CORNICE_PERF_ENV: Java %s; %s/%s; %d logical CPUs; max heap %.0f MiB; %d trials x %d calls after %d warmups; allocation meter %s%n",
                System.getProperty("java.version"), System.getProperty("os.name"), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() / 1048576.0,
                TRIALS, OPERATIONS, WARMUP, ALLOCATIONS != null);
        System.out.println("ERYDON_CORNICE_PERF_SCOPE: isolated prelaunch JVM; real connection and voxel-shape code, proxy map reads; no chunks, GPU, shaders or live frame timing; shared launch/bootstrap work may affect GC and JIT");
        for (Fixture fixture : fixtures) {
            fixture.world.reads = 0;
            var segments = CorniceConnections.components(fixture.world.view, fixture.state, BlockPos.ORIGIN);
            int reads = fixture.world.reads;
            require(reads <= MAX_CONNECTION_READS && segments.size() <= 2, "Connection work escaped its local constant bound: " + fixture.name);
            fixture.world.reads = 0;
            VoxelShape shape = fixture.state.getOutlineShape(fixture.world.view, BlockPos.ORIGIN);
            int shapeReads = fixture.world.reads;
            require(shape == fixture.state.getOutlineShape(fixture.world.view, BlockPos.ORIGIN), "Warm outline rebuilt its shape: " + fixture.name);
            Measurement connections = measure(() -> CorniceConnections.components(fixture.world.view, fixture.state, BlockPos.ORIGIN));
            Measurement outlines = measure(() -> fixture.state.getOutlineShape(fixture.world.view, BlockPos.ORIGIN));
            System.out.printf(Locale.ROOT,
                    "ERYDON_CORNICE_PERF_CASE: %s; components=%d; connection reads=%d; outline reads=%d; connection %s; warm outline %s; outline boxes=%d%n",
                    fixture.name, segments.size(), reads, shapeReads, connections, outlines, shape.getBoundingBoxes().size());
            if (!fixture.state.get(CorniceBlock.SHAPE).isAngled()
                    && segments.stream().noneMatch(segment -> segment.joinedStart() || segment.joinedEnd())) {
                Baseline baseline = new Baseline();
                VoxelShape legacy = baseline.shape(fixture.state);
                require(legacy == shape, "Ordinary outline is not the existing prebuilt profile: " + fixture.name);
                // This is the previous implementation's state/profile/array path, not a second JVM or historical build.
                Measurement ordinary = measure(() -> baseline.shape(fixture.state));
                System.out.printf(Locale.ROOT,
                        "ERYDON_CORNICE_PERF_ORDINARY: %s; former prebuilt path %s; current/previous median %.2fx; additional neighbour reads=%d%n",
                        fixture.name, ordinary, outlines.medianNs / ordinary.medianNs, shapeReads);
            }
        }
        raycasts(cornice, fixtures.get(fixtures.size() - 1).world);
        coldAndSharedShapes(fixtures);
        int states = cornice.getStateManager().getStates().size();
        require(states == 52, "Cornice state matrix changed; review total state and cache budgets");
        long representatives = Registries.BLOCK.stream().filter(block -> block instanceof CorniceBlock).count();
        System.out.printf(Locale.ROOT,
                "ERYDON_CORNICE_PERF_STATES: %d registered probe representatives x %d saved states; former 12 states each; full-source inventory must be verified independently (probe intentionally skips other families)%n",
                representatives, states);
        System.out.printf(Locale.ROOT,
                "ERYDON_CORNICE_PERFORMANCE_OK: bounded local scans and immutable shape reuse verified; audit elapsed %.2f s; warm throughput does not establish Minecraft FPS or visual acceptance%n",
                (System.nanoTime() - started) / 1e9);
    }

    private static void raycasts(CorniceBlock block, CountingWorld mixed) {
        Map<BlockPos, BlockState> ordinary = new HashMap<>();
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++)
            ordinary.put(new BlockPos(x, 0, z), block.getDefaultState());
        CountingWorld empty = new CountingWorld(Map.of()), dense = new CountingWorld(ordinary);
        for (CountingWorld world : List.of(empty, dense, mixed)) {
            String name = world == empty ? "empty" : world == dense ? "dense-ordinary" : "dense-mixed";
            Vec3d start = new Vec3d(-1.5, .95, .5), end = new Vec3d(6.5, .95, .5);
            BlockPos[] cells = new BlockPos[6];
            BlockState[] cellStates = new BlockState[6];
            for (int i = 0; i < cells.length; i++) {
                cells[i] = new BlockPos(i, 0, 0);
                cellStates[i] = world.states.getOrDefault(cells[i], Blocks.AIR.getDefaultState());
            }
            Supplier<?> ray = () -> {
                net.minecraft.util.hit.BlockHitResult hit = null;
                for (int i = 0; i < cells.length; i++)
                    hit = CorniceRaycast.closest(world.view, start, end, cells[i], cellStates[i], hit);
                return hit;
            };
            world.reads = 0;
            blackhole = ray.get();
            int reads = world.reads;
            require(reads <= 6 * (24 + 24 * MAX_CONNECTION_READS), "Raycast escaped the fixed owner/component neighbourhood bound");
            if (world == empty) require(reads == 48, "Empty ray lost its eight-neighbour fast path");
            if (world == dense) require(reads <= 144, "Ordinary-only ray performed angular shape or connection scans");
            // This benchmarks the extra production scanner over six traversed cells, including empty and native scenes.
            Measurement cost = measure(ray, 128, 256);
            System.out.printf(Locale.ROOT,
                    "ERYDON_CORNICE_PERF_RAYCAST: %s, six traversed upper-band cells; extra reads=%d; extra scanner %s (7 x 256 calls after 128 warmups)%n",
                    name, reads, cost);
        }
        empty.reads = 0;
        CorniceRaycast.closest(empty.view, new Vec3d(-1.5, .125, .5), new Vec3d(6.5, .125, .5),
                BlockPos.ORIGIN, Blocks.AIR.getDefaultState(), null);
        require(empty.reads == 0, "Below-profile ray failed its zero-neighbour-read fast path");
        System.out.println("ERYDON_CORNICE_PERF_RAYCAST_GATE: below-profile scan has zero reads; upper-band scans run through the global BlockView raycast hook even when no cornice is present");
    }

    private static void breakParticleBudget() {
        int cases = 0, maximumParticles = 0;
        for (String style : List.of("modern", "georgian", "byzantine", "gothic")) {
            CorniceBlock block = cornice(style);
            for (CorniceBlock.CorniceShape shape : CorniceBlock.CorniceShape.values())
                for (Direction facing : Direction.Type.HORIZONTAL) {
                    BlockState state = block.getDefaultState().with(CorniceBlock.SHAPE, shape).with(CorniceBlock.FACING, facing);
                    List<net.minecraft.util.math.Box> boxes = block.getBreakParticleShape(state).getBoundingBoxes();
                    require(boxes.size() == 1, "Detailed outline escaped into break-particle generation: " + state);
                    var box = boxes.get(0);
                    double[] widths = {box.maxX - box.minX, box.maxY - box.minY, box.maxZ - box.minZ};
                    int particles = 1;
                    for (double width : widths) {
                        require(width > 0 && width <= 1 + 1e-9, "Break debris box exceeds one block along an axis: " + state);
                        // Vanilla ParticleManager clamps each dimension to one block and uses at least two samples.
                        particles *= Math.max(2, (int) Math.ceil(Math.min(1, width) / .25));
                    }
                    require(particles <= 64, "Cornice break event exceeds the single-box vanilla particle budget");
                    CorniceRun.Point middle = CorniceSlopePlacement.run(state).point(.5, .125);
                    require(box.contains(middle.x(), .95, middle.z()), "Break debris misses the central moulding: " + state);
                    maximumParticles = Math.max(maximumParticles, particles);
                    cases++;
                }
        }
        require(cases == 208, "Particle budget skipped saved poses or styles");
        System.out.printf(Locale.ROOT,
                "ERYDON_CORNICE_BREAK_PARTICLES_OK: %d style/shape/facing cases; one <=1-block box; central moulding enclosed; vanilla maximum %d particles per break (hard bound64)%n",
                cases, maximumParticles);
    }

    private static void extendedJoinRaycasts(List<Fixture> fixtures) {
        int samples = 0, outsideOwner = 0, twoCells = 0;
        var marker = new net.minecraft.entity.MarkerEntity(net.minecraft.entity.EntityType.MARKER, null);
        for (Fixture fixture : fixtures) {
            if (!fixture.name.contains("transition") && !fixture.name.contains("handed-slope")) continue;
            for (BlockRotation rotation : BlockRotation.values()) {
                Map<BlockPos, BlockState> rotated = new HashMap<>();
                fixture.world.states.forEach((owner, state) -> rotated.put(rotate(owner, rotation), state.rotate(rotation)));
                CountingWorld world = new CountingWorld(rotated);
                for (var entry : rotated.entrySet()) {
                    BlockPos owner = entry.getKey();
                    for (CorniceConnections.Segment segment : CorniceConnections.components(world.view, entry.getValue(), owner)) {
                        for (double u : new double[]{.0625, .125, .5, .875, .9375}) for (double depth : new double[]{.125, .375, .49}) {
                            CorniceRun.Point point = segment.point(u, depth);
                            Vec3d start = new Vec3d(owner.getX() + point.x(), .95, owner.getZ() + point.z());
                            Vec3d end = start.add(.0031, 0, .0017);
                            BlockPos cell = BlockPos.ofFloored(start);
                            var hit = world.view.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE,
                                    RaycastContext.FluidHandling.NONE, marker));
                            require(hit.getBlockPos().equals(owner), "Extended profile sample selected " + hit.getBlockPos()
                                    + " instead of owner " + owner + ": " + fixture.name + "/" + rotation + "/u=" + u + "/d=" + depth + "/cell=" + cell);
                            int distance = Math.max(Math.abs(cell.getX() - owner.getX()), Math.abs(cell.getZ() - owner.getZ()));
                            require(distance <= 2, "Registered mitre exceeds the owner discovery envelope");
                            if (distance > 0) outsideOwner++;
                            if (distance == 2) twoCells++;
                            samples++;
                        }
                    }
                }
            }
        }
        require(samples > 0 && outsideOwner > 0, "Extended joint raycast proof did not exercise overhanging physical selection");
        System.out.printf(Locale.ROOT,
                "ERYDON_CORNICE_EXTENDED_RAYCAST_OK: %d production top-profile samples across inward/outward/handed joins and 4 rotations; %d outside owning cell; %d two cells away%n",
                samples, outsideOwner, twoCells);
    }

    private static BlockPos rotate(BlockPos pos, BlockRotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new BlockPos(-pos.getZ(), pos.getY(), pos.getX());
            case CLOCKWISE_180 -> new BlockPos(-pos.getX(), pos.getY(), -pos.getZ());
            case COUNTERCLOCKWISE_90 -> new BlockPos(pos.getZ(), pos.getY(), -pos.getX());
            default -> pos;
        };
    }

    private static void radiusTwoBorderRaycasts(CorniceBlock block) {
        int joinedPairs = 0, borderSamples = 0, twoCellSamples = 0;
        var marker = new net.minecraft.entity.MarkerEntity(net.minecraft.entity.EntityType.MARKER, null);
        for (CorniceBlock.CorniceShape shape : CorniceBlock.CorniceShape.values()) {
            if (!shape.isAngled()) continue;
            BlockState source = block.getDefaultState().with(CorniceBlock.SHAPE, shape);
            for (Direction facing : Direction.Type.HORIZONTAL) for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                if (x == 0 && z == 0) continue;
                BlockPos peer = new BlockPos(x, 0, z);
                BlockState target = block.getDefaultState().with(CorniceBlock.FACING, facing);
                CountingWorld world = new CountingWorld(Map.of(BlockPos.ORIGIN, source, peer, target));
                var segments = CorniceConnections.components(world.view, source, BlockPos.ORIGIN);
                if (segments.stream().noneMatch(segment -> segment.joinedStart() || segment.joinedEnd())) continue;
                joinedPairs++;
                for (var entry : world.states.entrySet()) {
                    BlockPos owner = entry.getKey();
                    for (var segment : CorniceConnections.components(world.view, entry.getValue(), owner)) {
                        for (double u : new double[]{.0001, .9999}) for (double depth : new double[]{.375, .49}) {
                            CorniceRun.Point point = segment.point(u, depth);
                            Vec3d start = new Vec3d(owner.getX() + point.x(), .95, owner.getZ() + point.z());
                            BlockPos cell = BlockPos.ofFloored(start);
                            int distance = Math.max(Math.abs(cell.getX() - owner.getX()), Math.abs(cell.getZ() - owner.getZ()));
                            require(distance <= 2, "Border tip escaped the owner discovery radius: " + entry.getValue());
                            borderSamples++;
                            if (distance < 2) continue;
                            var hit = world.view.raycast(new RaycastContext(start, start.add(.0031, 0, .0017),
                                    RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, marker));
                            // Voxel raster rounding can overlap at a shared seam; either real owner is valid here.
                            require(hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK && world.states.containsKey(hit.getBlockPos()),
                                    "Radius-two border tip is not selectable: " + entry.getValue() + "/u=" + u + "/d=" + depth + "/cell=" + cell);
                            twoCellSamples++;
                        }
                    }
                }
            }
        }
        System.out.printf(Locale.ROOT,
                "ERYDON_CORNICE_RADIUS_TWO_BORDER_OK: %d accepted angular/cardinal pairs; %d near-cut profile samples; %d samples two cells from their owner (zero means this finite set does not prove radius-two selection)%n",
                joinedPairs, borderSamples, twoCellSamples);
    }

    private static void coldAndSharedShapes(List<Fixture> fixtures) throws Exception {
        var field = CorniceInteractionShapes.class.getDeclaredField("CACHE");
        field.setAccessible(true);
        Cache<?, ?> cache = (Cache<?, ?>) field.get(null);
        for (String style : new String[]{"modern", "georgian", "byzantine", "gothic"}) {
            CorniceBlock block = cornice(style);
            for (Fixture fixture : fixtures) {
                if (!fixture.state.get(CorniceBlock.SHAPE).isAngled()) continue;
                BlockState state = block.getDefaultState().with(CorniceBlock.SHAPE, fixture.state.get(CorniceBlock.SHAPE))
                        .with(CorniceBlock.FACING, fixture.state.get(CorniceBlock.FACING));
                Map<BlockPos, BlockState> states = new HashMap<>();
                fixture.world.states.forEach((pos, original) -> states.put(pos,
                        original.getBlock() instanceof CorniceBlock ? block.getDefaultState()
                                .with(CorniceBlock.SHAPE, original.get(CorniceBlock.SHAPE))
                                .with(CorniceBlock.FACING, original.get(CorniceBlock.FACING)) : original));
                CountingWorld world = new CountingWorld(states);
                List<CorniceConnections.Segment> components = CorniceConnections.components(world.view, state, BlockPos.ORIGIN);
                double[][] boxes = profileBoxes(style);
                double[] coldNs = new double[TRIALS], coldBytes = new double[TRIALS];
                VoxelShape result = null;
                for (int trial = 0; trial < TRIALS; trial++) {
                    // Test-only reflection forces a real cold production construction; never changes runtime code.
                    cache.invalidateAll();
                    long beforeBytes = allocated(), before = System.nanoTime();
                    result = CorniceInteractionShapes.shape(state, components, boxes);
                    coldNs[trial] = System.nanoTime() - before;
                    coldBytes[trial] = deltaBytes(beforeBytes);
                    blackhole = result;
                }
                VoxelShape same = CorniceInteractionShapes.shape(state, components, boxes);
                require(same == result, "Warm interaction shape was not shared: " + style + "/" + fixture.name);
                Measurement warm = measure(() -> CorniceInteractionShapes.shape(state, components, boxes));
                var weightField = result.getClass().getDeclaredField("estimatedBytes");
                weightField.setAccessible(true);
                int estimatedBytes = weightField.getInt(result);
                require(estimatedBytes > 0 && estimatedBytes < 16 * 1024 * 1024,
                        "One outline exceeds the whole shared interaction budget");
                System.out.printf(Locale.ROOT,
                        "ERYDON_CORNICE_PERF_SHAPE: %s/%s; cold median %.3f ms, %.0f allocated bytes; warm-cache %s; boxes=%d; retained estimate=%d bytes%n",
                        style, fixture.name, median(coldNs) / 1e6, median(coldBytes), warm,
                        result.getBoundingBoxes().size(), estimatedBytes);
            }
        }
        System.out.println("ERYDON_CORNICE_PERF_CACHE: warm queries return the same VoxelShape; interaction and fitted geometry memory budgets are audited separately");
    }

    private static List<Fixture> fixtures(CorniceBlock block) {
        List<Fixture> result = new ArrayList<>();
        BlockState straight = block.getDefaultState();
        result.add(new Fixture("ordinary-straight", straight, new CountingWorld(Map.of(BlockPos.ORIGIN, straight))));
        BlockState inner = straight.with(CorniceBlock.SHAPE, CorniceBlock.CorniceShape.INNER_CORNER);
        result.add(new Fixture("ordinary-inner", inner, new CountingWorld(Map.of(BlockPos.ORIGIN, inner))));
        result.add(new Fixture("ordinary-outer", straight.with(CorniceBlock.SHAPE, CorniceBlock.CorniceShape.OUTER_CORNER),
                new CountingWorld(Map.of())));
        for (CorniceBlock.CorniceShape shape : new CorniceBlock.CorniceShape[]{CorniceBlock.CorniceShape.DIAGONAL_PRIMARY,
                CorniceBlock.CorniceShape.BROAD_RIGHT_PRIMARY, CorniceBlock.CorniceShape.BROAD_LEFT_PRIMARY,
                CorniceBlock.CorniceShape.NARROW_RIGHT_PRIMARY, CorniceBlock.CorniceShape.NARROW_LEFT_PRIMARY}) {
            BlockState state = straight.with(CorniceBlock.SHAPE, shape);
            result.add(new Fixture(shape.asString(), state, new CountingWorld(Map.of(BlockPos.ORIGIN, state))));
        }
        BlockState returned = straight.with(CorniceBlock.SHAPE, CorniceBlock.CorniceShape.DIAGONAL_PRIMARY);
        BlockState support = Registries.BLOCK.get(new Identifier("erydon", "glacium_slope_vertical")).getDefaultState()
                .with(CorniceBlock.FACING, Direction.NORTH);
        CountingWorld returnWorld = new CountingWorld(Map.of(BlockPos.ORIGIN, returned,
                BlockPos.ORIGIN.west(), support, BlockPos.ORIGIN.north(), Blocks.STONE.getDefaultState(),
                BlockPos.ORIGIN.up(), Blocks.STONE.getDefaultState(), BlockPos.ORIGIN.west().up(), Blocks.STONE.getDefaultState()));
        require(CorniceSlopePlacement.attached(returned, returnWorld.view, BlockPos.ORIGIN), "Return fixture lost its actual slope support");
        var returnSegments = CorniceConnections.components(returnWorld.view, returned, BlockPos.ORIGIN);
        require(returnSegments.size() == 2 && returnSegments.stream().allMatch(segment -> segment.joinedStart() || segment.joinedEnd()),
                "Same-owner full-wall return is not a reciprocal two-run corner");
        result.add(new Fixture("same-owner-full-wall-return", returned, returnWorld));
        result.add(joined(block, "inward-cardinal-transition", -1, false));
        result.add(joined(block, "outward-cardinal-transition", 1, false));
        result.add(joined(block, "opposite-handed-slope-corner", 0, true));
        Map<BlockPos, BlockState> dense = new HashMap<>();
        CorniceBlock.CorniceShape[] shapes = CorniceBlock.CorniceShape.values();
        Direction[] directions = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            int index = (x + 4) * 9 + z + 4;
            dense.put(new BlockPos(x, 0, z), straight.with(CorniceBlock.SHAPE, shapes[index % shapes.length])
                    .with(CorniceBlock.FACING, directions[index % directions.length]));
        }
        dense.put(BlockPos.ORIGIN, inner);
        result.add(new Fixture("dense-81-owner-mixed-corners", inner, new CountingWorld(dense)));
        return List.copyOf(result);
    }

    private static Fixture joined(CorniceBlock block, String name, int sign, boolean handed) {
        for (CorniceBlock.CorniceShape shape : CorniceBlock.CorniceShape.values()) {
            if (!shape.isAngled() || handed && !shape.name().contains("LEFT")) continue;
            BlockState source = block.getDefaultState().with(CorniceBlock.SHAPE, shape);
            for (CorniceBlock.CorniceShape other : CorniceBlock.CorniceShape.values()) {
                if (handed ? !other.name().contains("RIGHT") : other != CorniceBlock.CorniceShape.STRAIGHT) continue;
                for (Direction direction : Direction.Type.HORIZONTAL) for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                    if (x == 0 && z == 0) continue;
                    BlockState target = block.getDefaultState().with(CorniceBlock.SHAPE, other).with(CorniceBlock.FACING, direction);
                    BlockPos peer = new BlockPos(x, 0, z);
                    CountingWorld world = new CountingWorld(Map.of(BlockPos.ORIGIN, source, peer, target));
                    CorniceConnections.Segment segment = CorniceConnections.components(world.view, source, BlockPos.ORIGIN).get(0);
                    if (!segment.joinedStart() && !segment.joinedEnd()) continue;
                    double stretch = segment.joinedEnd() ? segment.end().slope() : -segment.start().slope();
                    if (sign != 0 && Math.signum(stretch) != sign) continue;
                    return new Fixture(name, source, world);
                }
            }
        }
        throw new AssertionError("No production join fixture found: " + name);
    }

    private static Measurement measure(Supplier<?> operation) {
        return measure(operation, WARMUP, OPERATIONS);
    }

    private static Measurement measure(Supplier<?> operation, int warmup, int operations) {
        for (int i = 0; i < warmup; i++) blackhole = operation.get();
        double[] times = new double[TRIALS], bytes = new double[TRIALS];
        for (int trial = 0; trial < TRIALS; trial++) {
            long beforeBytes = allocated(), before = System.nanoTime();
            for (int i = 0; i < operations; i++) blackhole = operation.get();
            times[trial] = (System.nanoTime() - before) / (double) operations;
            double delta = deltaBytes(beforeBytes);
            bytes[trial] = delta < 0 ? -1 : delta / operations;
        }
        return new Measurement(median(times), Arrays.stream(times).min().orElseThrow(),
                Arrays.stream(times).max().orElseThrow(), median(bytes));
    }

    private record Measurement(double medianNs, double minNs, double maxNs, double bytesPerCall) {
        @Override public String toString() {
            return String.format(Locale.ROOT, "%.0f ns/call (%.0f..%.0f), %.0f bytes/call", medianNs, minNs, maxNs, bytesPerCall);
        }
    }
    private record Fixture(String name, BlockState state, CountingWorld world) { }
    private static final class CountingWorld {
        private final Map<BlockPos, BlockState> states;
        private final BlockView view;
        private int reads;
        CountingWorld(Map<BlockPos, BlockState> states) {
            this.states = Map.copyOf(states);
            this.view = (BlockView) Proxy.newProxyInstance(BlockView.class.getClassLoader(), new Class<?>[]{BlockView.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getBlockState" -> { reads++; yield this.states.getOrDefault(args[0], Blocks.AIR.getDefaultState()); }
                        case "getFluidState" -> this.states.getOrDefault(args[0], Blocks.AIR.getDefaultState()).getFluidState();
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

    /** Previous production profile lookup and prebuilt shape selection; array reflection is outside timing. */
    private static final class Baseline {
        private final VoxelShape[][][] profiles = new VoxelShape[4][3][];
        Baseline() throws ReflectiveOperationException {
            String[] profileNames = {"MODERN", "GEORGIAN", "GUILLOCHE", "GOTHIC"};
            String[] kindNames = {"RUN", "INNER", "OUTER"};
            for (int profile = 0; profile < profileNames.length; profile++)
                for (int kind = 0; kind < kindNames.length; kind++) {
                    var field = CorniceBlock.class.getDeclaredField(profileNames[profile] + "_" + kindNames[kind] + "_SHAPES");
                    field.setAccessible(true);
                    profiles[profile][kind] = (VoxelShape[]) field.get(null);
                }
        }
        VoxelShape shape(BlockState state) {
            Direction facing = state.get(CorniceBlock.FACING);
            CorniceBlock.CorniceShape shape = state.get(CorniceBlock.SHAPE);
            String path = ErydonIdMigration.legacyResourcePath(Registries.BLOCK.getId(state.getBlock()).getPath());
            int profile = path.contains("_cornice_georgian") ? 1 : path.contains("_cornice_guilloche") ? 2
                    : path.contains("_cornice_gothic") ? 3 : 0;
            int kind = shape == CorniceBlock.CorniceShape.OUTER_CORNER ? 2
                    : shape == CorniceBlock.CorniceShape.INNER_CORNER ? 1 : 0;
            int index = switch (facing) { case NORTH -> 0; case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
            return profiles[profile][kind][index];
        }
    }

    private static double[][] profileBoxes(String style) throws ReflectiveOperationException {
        String profile = style.equals("byzantine") ? "GUILLOCHE" : style.toUpperCase(Locale.ROOT);
        var field = CorniceBlock.class.getDeclaredField(profile + "_STRAIGHT_BOXES");
        field.setAccessible(true);
        return CorniceInteractionShapes.profileBoxes(style.equals("byzantine") ? "guilloche" : style,
                (double[][]) field.get(null));
    }
    private static CorniceBlock cornice(String style) {
        var block = Registries.BLOCK.get(new Identifier("erydon", "glacium_cornice_" + style));
        require(block instanceof CorniceBlock, "Missing performance representative: " + style);
        return (CorniceBlock) block;
    }
    private static ThreadMXBean allocations() {
        if (!(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean) || !bean.isThreadAllocatedMemorySupported()) return null;
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        return bean;
    }
    private static long allocated() { return ALLOCATIONS == null ? -1 : ALLOCATIONS.getThreadAllocatedBytes(Thread.currentThread().getId()); }
    private static double deltaBytes(long before) { return before < 0 ? -1 : allocated() - before; }
    private static double median(double[] values) { double[] sorted = values.clone(); Arrays.sort(sorted); return sorted[sorted.length / 2]; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
