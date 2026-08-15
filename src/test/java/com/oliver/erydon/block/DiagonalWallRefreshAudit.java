package com.oliver.erydon.block;

import com.sun.management.ThreadMXBean;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Test-only differential oracle and allocation benchmark for the four-position
 * diagonal-wall refresh. This class is not included in the release JAR.
 */
public final class DiagonalWallRefreshAudit {
    private static final int NORTH = 1;
    private static final int EAST = 1 << 1;
    private static final int SOUTH = 1 << 2;
    private static final int WEST = 1 << 3;
    private static final int ALL_DIAGONALS = NORTH | EAST | SOUTH | WEST;
    private static final int SAMPLE_COUNT = 7;
    private static volatile long blackhole;

    private DiagonalWallRefreshAudit() {
    }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        CorrectnessSummary correctness = verifyCorrectness();
        System.out.printf(Locale.ROOT,
                "Diagonal-wall differential verification passed: deterministic=%d, random=%d, writes=%d, events=%d%n",
                correctness.deterministicCases(), correctness.randomCases(), correctness.writes(), correctness.events());

        if (arguments.benchmark()) {
            runBenchmark(arguments);
        }
    }

    private static CorrectnessSummary verifyCorrectness() {
        int deterministicCases = 0;
        int randomCases = 0;
        long writes = 0;
        long events = 0;

        List<ScenarioFixture> fixtures = deterministicFixtures();
        for (ScenarioFixture fixture : fixtures) {
            Comparison comparison = compare(fixture.name(), fixture.world(), fixture.operations());
            deterministicCases++;
            writes += comparison.writes();
            events += comparison.events();
        }

        for (int seed = 0; seed < 750; seed++) {
            ScenarioFixture fixture = randomFixture(seed);
            Comparison comparison = compare(fixture.name(), fixture.world(), fixture.operations());
            randomCases++;
            writes += comparison.writes();
            events += comparison.events();
        }

        verifyExactProbeOrder();
        verifyUpdateFlags(fixtures);
        verifyConnectionPolicy();
        verifyPierSpacingPolicy();
        return new CorrectnessSummary(deterministicCases, randomCases, writes, events);
    }

    private static List<ScenarioFixture> deterministicFixtures() {
        List<ScenarioFixture> fixtures = new ArrayList<>();

        SimWorld noWalls = new SimWorld();
        fixtures.add(new ScenarioFixture("no-walls", noWalls,
                List.of(new Change(new Pos(0, 64, 0), Cell.solid()))));

        SimWorld oneWall = new SimWorld();
        oneWall.put(new Pos(1, 64, -1), Cell.wall(0, 0));
        fixtures.add(new ScenarioFixture("one-wall", oneWall,
                List.of(new Change(new Pos(0, 64, 0), Cell.solid()))));

        SimWorld severalWalls = candidateRing(new Pos(0, 64, 0), 3);
        fixtures.add(new ScenarioFixture("several-walls", severalWalls,
                List.of(new Change(new Pos(0, 64, 0), Cell.solid()))));

        SimWorld allWalls = candidateRing(new Pos(0, 64, 0), 4);
        fixtures.add(new ScenarioFixture("all-four-walls", allWalls,
                List.of(new Change(new Pos(0, 64, 0), Cell.solid()))));

        SimWorld lifecycle = candidateRing(new Pos(0, 64, 0), 4);
        fixtures.add(new ScenarioFixture("placement-replacement-removal", lifecycle, List.of(
                new Change(new Pos(0, 64, 0), Cell.solid()),
                new Change(new Pos(0, 64, 0), Cell.connectable()),
                new Change(new Pos(0, 64, 0), Cell.wall(0, 0)),
                new Change(new Pos(0, 64, 0), Cell.air())
        )));

        SimWorld cardinalBlocking = new SimWorld();
        cardinalBlocking.put(new Pos(0, 64, 0), Cell.wall(0, 0));
        cardinalBlocking.put(new Pos(1, 64, -1), Cell.wall(SOUTH | WEST, ALL_DIAGONALS));
        fixtures.add(new ScenarioFixture("cardinal-connectable-nonconnectable", cardinalBlocking, List.of(
                new Change(new Pos(0, 64, 0), Cell.connectable()),
                new Change(new Pos(0, 64, 0), Cell.nonConnectable()),
                new Change(new Pos(0, 64, 0), Cell.solid())
        )));

        SimWorld chain = new SimWorld();
        for (int i = 0; i < 12; i++) {
            chain.put(new Pos(i, 70, -i), Cell.wall(0, i % 2 == 0 ? 0 : ALL_DIAGONALS));
        }
        fixtures.add(new ScenarioFixture("recursive-chain", chain,
                List.of(new Change(new Pos(0, 70, 0), Cell.wall(0, 0)))));

        SimWorld chunkEdges = new SimWorld();
        for (Pos origin : List.of(
                new Pos(15, 64, 15),
                new Pos(16, 64, 16),
                new Pos(-1, 64, -1),
                new Pos(-16, 64, -16),
                new Pos(-17, -32, 31))) {
            addCandidateRing(chunkEdges, origin, 4);
        }
        fixtures.add(new ScenarioFixture("chunk-edges-negative-coordinates", chunkEdges, List.of(
                new Change(new Pos(15, 64, 15), Cell.solid()),
                new Change(new Pos(16, 64, 16), Cell.wall(0, 0)),
                new Change(new Pos(-1, 64, -1), Cell.solid()),
                new Change(new Pos(-16, 64, -16), Cell.air()),
                new Change(new Pos(-17, -32, 31), Cell.solid())
        )));

        SimWorld idempotent = candidateRing(new Pos(3, 80, -4), 4);
        fixtures.add(new ScenarioFixture("repeated-idempotent-refresh", idempotent, List.of(
                new Change(new Pos(3, 80, -4), Cell.solid()),
                new Refresh(new Pos(3, 80, -4)),
                new Refresh(new Pos(3, 80, -4))
        )));

        return fixtures;
    }

    private static ScenarioFixture randomFixture(int seed) {
        Random random = new Random(0x455259444f4eL + seed);
        int baseX = switch (seed % 5) {
            case 0 -> 15;
            case 1 -> 16;
            case 2 -> -1;
            case 3 -> -16;
            default -> random.nextInt(129) - 64;
        };
        int baseZ = random.nextBoolean() ? -16 : random.nextInt(129) - 64;
        int y = random.nextInt(385) - 64;
        Pos origin = new Pos(baseX, y, baseZ);
        SimWorld world = new SimWorld();

        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                int choice = random.nextInt(8);
                Cell cell = switch (choice) {
                    case 0, 1, 2 -> Cell.air();
                    case 3 -> Cell.solid();
                    case 4 -> Cell.connectable();
                    case 5 -> Cell.nonConnectable();
                    default -> Cell.wall(random.nextInt(16), random.nextInt(16));
                };
                if (cell.kind() != CellKind.AIR) {
                    world.put(new Pos(baseX + dx, y, baseZ + dz), cell);
                }
            }
        }

        List<Operation> operations = new ArrayList<>();
        Cell replacement = switch (seed % 5) {
            case 0 -> Cell.air();
            case 1 -> Cell.solid();
            case 2 -> Cell.connectable();
            case 3 -> Cell.nonConnectable();
            default -> Cell.wall(random.nextInt(16), random.nextInt(16));
        };
        operations.add(new Change(origin, replacement));
        operations.add(new Refresh(origin));
        if ((seed & 3) == 0) {
            operations.add(new Change(origin, Cell.air()));
        }
        return new ScenarioFixture("random-" + seed, world, operations);
    }

    private static SimWorld candidateRing(Pos origin, int count) {
        SimWorld world = new SimWorld();
        addCandidateRing(world, origin, count);
        return world;
    }

    private static void addCandidateRing(SimWorld world, Pos origin, int count) {
        int[][] offsets = {{1, -1}, {1, 1}, {-1, 1}, {-1, -1}};
        for (int index = 0; index < Math.min(count, offsets.length); index++) {
            world.put(origin.add(offsets[index][0], 0, offsets[index][1]), Cell.wall(0, 0));
        }
    }

    private static Comparison compare(String name, SimWorld initial, List<Operation> operations) {
        SimWorld reference = initial.copy();
        SimWorld mutable = initial.copy();

        for (Operation operation : operations) {
            operation.apply(reference, Algorithm.REFERENCE);
            operation.apply(mutable, Algorithm.MUTABLE);
        }

        if (!reference.cells.equals(mutable.cells)) {
            throw new AssertionError(name + " final state mismatch:\nreference=" + reference.cells
                    + "\nmutable=" + mutable.cells);
        }
        if (!reference.events.equals(mutable.events)) {
            int mismatch = firstMismatch(reference.events, mutable.events);
            throw new AssertionError(name + " event mismatch at index " + mismatch
                    + ":\nreference=" + eventAt(reference.events, mismatch)
                    + "\nmutable=" + eventAt(mutable.events, mismatch));
        }
        return new Comparison(reference.writeCount, reference.events.size());
    }

    private static int firstMismatch(List<Event> left, List<Event> right) {
        int shared = Math.min(left.size(), right.size());
        for (int index = 0; index < shared; index++) {
            if (!left.get(index).equals(right.get(index))) {
                return index;
            }
        }
        return shared;
    }

    private static Event eventAt(List<Event> events, int index) {
        return index < events.size() ? events.get(index) : null;
    }

    private static void verifyExactProbeOrder() {
        Pos origin = new Pos(-16, -32, 15);
        List<Pos> expected = List.of(
                origin.add(1, 0, -1),
                origin.add(1, 0, 1),
                origin.add(-1, 0, 1),
                origin.add(-1, 0, -1));

        for (Algorithm algorithm : Algorithm.values()) {
            SimWorld world = new SimWorld();
            algorithm.refreshAround(world, origin.toBlockPos());
            List<Pos> actual = world.events.stream()
                    .filter(event -> event.type() == EventType.READ)
                    .map(Event::pos)
                    .toList();
            if (!expected.equals(actual)) {
                throw new AssertionError(algorithm + " probe order mismatch: " + actual);
            }
        }
    }

    private static void verifyUpdateFlags(List<ScenarioFixture> fixtures) {
        for (ScenarioFixture fixture : fixtures) {
            SimWorld world = fixture.world().copy();
            for (Operation operation : fixture.operations()) {
                operation.apply(world, Algorithm.MUTABLE);
            }
            for (Event event : world.events) {
                if (event.type() == EventType.WRITE && event.flags() != Block.NOTIFY_ALL) {
                    throw new AssertionError(fixture.name() + " changed update flags to " + event.flags());
                }
            }
        }
    }

    private static void refreshAt(SimWorld world, Algorithm algorithm, BlockPos pos) {
        Cell state = world.read(pos);
        if (state.kind() != CellKind.WALL) {
            return;
        }

        int diagonals = 0;
        if (connectsDiagonal(world, state, pos, 1, -1, NORTH, EAST)) {
            diagonals |= NORTH;
        }
        if (connectsDiagonal(world, state, pos, 1, 1, SOUTH, EAST)) {
            diagonals |= EAST;
        }
        if (connectsDiagonal(world, state, pos, -1, 1, SOUTH, WEST)) {
            diagonals |= SOUTH;
        }
        if (connectsDiagonal(world, state, pos, -1, -1, NORTH, WEST)) {
            diagonals |= WEST;
        }

        if (diagonals != state.diagonalMask()) {
            Cell updated = new Cell(CellKind.WALL, state.cardinalMask(), diagonals);
            world.write(pos.toImmutable(), updated, Block.NOTIFY_ALL, algorithm);
        }
    }

    private static boolean connectsDiagonal(SimWorld world, Cell wall, BlockPos pos,
                                             int dx, int dz, int firstCardinal, int secondCardinal) {
        if ((wall.cardinalMask() & firstCardinal) != 0
                || (wall.cardinalMask() & secondCardinal) != 0) {
            return false;
        }
        Cell diagonal = world.read(new BlockPos(pos.getX() + dx, pos.getY(), pos.getZ() + dz));
        if (diagonal.kind() == CellKind.SOLID) {
            return true;
        }
        return diagonal.kind() == CellKind.WALL;
    }

    private static void verifyConnectionPolicy() {
        for (int cardinalMask = 0; cardinalMask <= 15; cardinalMask++) {
            boolean north = connected(cardinalMask, NORTH);
            boolean east = connected(cardinalMask, EAST);
            boolean south = connected(cardinalMask, SOUTH);
            boolean west = connected(cardinalMask, WEST);
            for (int candidateMask = 0; candidateMask <= 15; candidateMask++) {
                int accepted = 0;
                accepted = acceptDiagonal(accepted, candidateMask, NORTH, north, east);
                accepted = acceptDiagonal(accepted, candidateMask, EAST, south, east);
                accepted = acceptDiagonal(accepted, candidateMask, SOUTH, south, west);
                accepted = acceptDiagonal(accepted, candidateMask, WEST, north, west);

                int connectionCount = Integer.bitCount(cardinalMask) + Integer.bitCount(accepted);
                if (connectionCount > 4) {
                    throw new AssertionError("Connection cap exceeded: cardinal=" + cardinalMask
                            + " candidate=" + candidateMask + " accepted=" + accepted);
                }
                if (((cardinalMask & NORTH) != 0 && (accepted & (NORTH | WEST)) != 0)
                        || ((cardinalMask & EAST) != 0 && (accepted & (NORTH | EAST)) != 0)
                        || ((cardinalMask & SOUTH) != 0 && (accepted & (EAST | SOUTH)) != 0)
                        || ((cardinalMask & WEST) != 0 && (accepted & (SOUTH | WEST)) != 0)) {
                    throw new AssertionError("A diagonal survived beside a cardinal connection: cardinal="
                            + cardinalMask + " accepted=" + accepted);
                }
            }

            for (int diagonalMask = 0; diagonalMask <= 15; diagonalMask++) {
                int connectionCount = Integer.bitCount(cardinalMask) + Integer.bitCount(diagonalMask);
                boolean straightCardinal = diagonalMask == 0
                        && (cardinalMask == (NORTH | SOUTH) || cardinalMask == (EAST | WEST));
                boolean straightDiagonal = cardinalMask == 0
                        && (diagonalMask == (NORTH | SOUTH) || diagonalMask == (EAST | WEST));
                boolean expectedTurn = connectionCount >= 2 && !straightCardinal && !straightDiagonal;
                boolean actualTurn = GeorgianWallConnectionPolicy.hasConnectionTurn(cardinalMask, diagonalMask);
                if (actualTurn != expectedTurn) {
                    throw new AssertionError("Turn selection mismatch: cardinal=" + cardinalMask
                            + " diagonal=" + diagonalMask);
                }
            }
        }
    }

    private static void verifyPierSpacingPolicy() {
        if (GeorgianWallPierSpacing.fromStoredValue(0) != GeorgianWallPierSpacing.EVERY_4) {
            throw new AssertionError("Every fourth block must remain the default Georgian wall spacing");
        }
        if (GeorgianWallPierSpacing.fromStoredValue(1) != GeorgianWallPierSpacing.EVERY_3
                || GeorgianWallPierSpacing.fromStoredValue(2) != GeorgianWallPierSpacing.EVERY_5
                || GeorgianWallPierSpacing.fromStoredValue(3) != GeorgianWallPierSpacing.CORNERS_ONLY
                || GeorgianWallPierSpacing.fromStoredValue(4) != GeorgianWallPierSpacing.NONE
                || GeorgianWallPierSpacing.fromStoredValue(5) != GeorgianWallPierSpacing.EVERY_2) {
            throw new AssertionError("Georgian wall spacing storage compatibility changed");
        }

        GeorgianWallPierSpacing[] cycle = {
                GeorgianWallPierSpacing.EVERY_2,
                GeorgianWallPierSpacing.EVERY_3,
                GeorgianWallPierSpacing.EVERY_4,
                GeorgianWallPierSpacing.EVERY_5,
                GeorgianWallPierSpacing.CORNERS_ONLY,
                GeorgianWallPierSpacing.NONE,
        };
        for (int index = 0; index < cycle.length; index++) {
            GeorgianWallPierSpacing expected = cycle[(index + 1) % cycle.length];
            if (cycle[index].next() != expected) {
                throw new AssertionError("Unexpected Georgian wall spacing cycle after " + cycle[index]);
            }
        }
        if (!GeorgianWallPierSpacing.CORNERS_ONLY.piersEnabled()
                || GeorgianWallPierSpacing.NONE.piersEnabled()) {
            throw new AssertionError("Georgian wall corner-only and no-pier modes are not distinct");
        }

        for (int interval : new int[]{2, 3, 4, 5}) {
            for (int index = 0; index < 32; index++) {
                boolean expected = (index + 1) % interval == 0;
                boolean actual = GeorgianWallConnectionPolicy.shouldUsePeriodicPier(index, interval, false);
                if (actual != expected) {
                    throw new AssertionError("Periodic pier mismatch: interval=" + interval
                            + " index=" + index);
                }
            }
        }
        if (GeorgianWallConnectionPolicy.shouldUsePeriodicPier(0, 0, false)
                || GeorgianWallConnectionPolicy.shouldUsePeriodicPier(-1, 4, false)
                || GeorgianWallConnectionPolicy.shouldUsePeriodicPier(3, 4, true)) {
            throw new AssertionError("Disabled, negative, or corner-adjacent positions must not select a pier");
        }

        for (int distance = 0; distance < 8; distance++) {
            int fromStart = GeorgianWallConnectionPolicy.anchoredRunIndex(distance, 8, true, false);
            int fromEnd = GeorgianWallConnectionPolicy.anchoredRunIndex(distance, 8, false, true);
            if (fromStart != distance || fromEnd != 7 - distance) {
                throw new AssertionError("Straight-run spacing did not reset from its corner pier");
            }
        }

        for (int cardinalMask = 0; cardinalMask <= 15; cardinalMask++) {
            for (int diagonalMask = 0; diagonalMask <= 15; diagonalMask++) {
                boolean expected = diagonalMask == 0
                        && (Integer.bitCount(cardinalMask) == 1
                        || cardinalMask == (NORTH | SOUTH)
                        || cardinalMask == (EAST | WEST));
                if (GeorgianWallConnectionPolicy.isStraightRunSection(cardinalMask, diagonalMask) != expected) {
                    throw new AssertionError("Straight-run terminal selection mismatch: cardinal="
                            + cardinalMask + " diagonal=" + diagonalMask);
                }
            }
        }

        if (!GeorgianWallConnectionPolicy.isPeriodicPierSection(NORTH, 0, NORTH, false)
                || !GeorgianWallConnectionPolicy.isPeriodicPierSection(NORTH | SOUTH, 0,
                NORTH | SOUTH, false)
                || GeorgianWallConnectionPolicy.isPeriodicPierSection(NORTH, 0, NORTH, true)
                || GeorgianWallConnectionPolicy.isPeriodicPierSection(NORTH | EAST, 0,
                NORTH | EAST, false)
                || GeorgianWallConnectionPolicy.isPeriodicPierSection(NORTH, NORTH, NORTH, false)) {
            throw new AssertionError("Terminal and through-run periodic pier markers are not distinct");
        }
    }

    private static int acceptDiagonal(int accepted, int candidates, int bit,
                                      boolean first, boolean second) {
        if ((candidates & bit) != 0 && GeorgianWallConnectionPolicy.allowsDiagonal(first, second)) {
            return accepted | bit;
        }
        return accepted;
    }

    private static boolean connected(int mask, int bit) {
        return (mask & bit) != 0;
    }

    private static void runBenchmark(Arguments arguments) throws IOException {
        ThreadMXBean threadBean = threadBean();
        BlockPos[] origins = benchmarkOrigins();

        for (BenchmarkScenario scenario : BenchmarkScenario.values()) {
            for (int warmup = 0; warmup < 3; warmup++) {
                runOne(Algorithm.REFERENCE, scenario, origins, arguments.warmup(), threadBean, 0, true);
                runOne(Algorithm.MUTABLE, scenario, origins, arguments.warmup(), threadBean, 0, true);
            }
        }

        List<BenchmarkResult> results = new ArrayList<>();
        for (BenchmarkScenario scenario : BenchmarkScenario.values()) {
            for (int sample = 1; sample <= SAMPLE_COUNT; sample++) {
                Algorithm first = (sample & 1) == 0 ? Algorithm.MUTABLE : Algorithm.REFERENCE;
                Algorithm second = first == Algorithm.REFERENCE ? Algorithm.MUTABLE : Algorithm.REFERENCE;
                BenchmarkResult firstResult = runOne(first, scenario, origins, arguments.iterations(),
                        threadBean, sample, false);
                BenchmarkResult secondResult = runOne(second, scenario, origins, arguments.iterations(),
                        threadBean, sample, false);
                assertSameWork(firstResult, secondResult);
                results.add(firstResult);
                results.add(secondResult);
            }
        }

        writeCsv(arguments.output(), results);
        printSummary(results);
        System.out.println("Raw diagonal-wall benchmark written to " + arguments.output());
    }

    private static BenchmarkResult runOne(Algorithm algorithm, BenchmarkScenario scenario, BlockPos[] origins,
                                          int operations, ThreadMXBean threadBean, int sample, boolean warmup) {
        ProbeWorld world = new ProbeWorld(scenario);
        long threadId = Thread.currentThread().getId();
        long allocatedBefore = threadBean == null ? -1 : threadBean.getThreadAllocatedBytes(threadId);
        long cpuBefore = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
        GcSnapshot gcBefore = GcSnapshot.capture();
        long wallBefore = System.nanoTime();

        for (int operation = 0; operation < operations; operation++) {
            algorithm.probe(world, origins[operation & (origins.length - 1)]);
        }

        long wallNanos = System.nanoTime() - wallBefore;
        GcSnapshot gcAfter = GcSnapshot.capture();
        long cpuNanos = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - cpuBefore;
        long allocatedBytes = threadBean == null ? -1 : threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        blackhole ^= world.checksum;

        if (warmup) {
            return null;
        }
        return new BenchmarkResult(
                scenario,
                algorithm,
                sample,
                operations,
                world.reads,
                world.walls,
                world.writes,
                world.stateCount,
                world.checksum,
                wallNanos,
                cpuNanos,
                allocatedBytes,
                gcAfter.count() - gcBefore.count(),
                gcAfter.timeMillis() - gcBefore.timeMillis());
    }

    private static void assertSameWork(BenchmarkResult first, BenchmarkResult second) {
        if (first.scenario() != second.scenario()
                || first.operations() != second.operations()
                || first.reads() != second.reads()
                || first.walls() != second.walls()
                || first.writes() != second.writes()
                || first.stateCount() != second.stateCount()
                || first.checksum() != second.checksum()) {
            throw new AssertionError("Benchmark algorithms performed different work: " + first + " versus " + second);
        }
    }

    private static ThreadMXBean threadBean() {
        if (!(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean)
                || !bean.isThreadAllocatedMemorySupported()) {
            return null;
        }
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        return bean;
    }

    private static BlockPos[] benchmarkOrigins() {
        BlockPos[] origins = new BlockPos[4096];
        for (int index = 0; index < origins.length; index++) {
            int x = ((index * 31) & 2047) - 1024;
            int z = ((index * 73) & 2047) - 1024;
            if ((index & 31) == 0) {
                x = (index & 1) == 0 ? -16 : 15;
            }
            origins[index] = new BlockPos(x, 64 + (index & 15), z);
        }
        return origins;
    }

    private static void writeCsv(Path output, List<BenchmarkResult> results) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = new ArrayList<>();
        lines.add("scenario,algorithm,sample,operations,reads,walls,writes,state_count,checksum,wall_ns,cpu_ns,allocated_bytes,gc_count,gc_time_ms,ops_per_second,bytes_per_operation,allocation_bytes_per_second");
        for (BenchmarkResult result : results) {
            double seconds = result.wallNanos() / 1_000_000_000.0;
            double operationsPerSecond = result.operations() / seconds;
            double bytesPerOperation = result.allocatedBytes() < 0
                    ? -1.0
                    : (double) result.allocatedBytes() / result.operations();
            double allocationRate = result.allocatedBytes() < 0
                    ? -1.0
                    : result.allocatedBytes() / seconds;
            lines.add(String.format(Locale.ROOT,
                    "%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.3f,%.3f,%.3f",
                    result.scenario().id,
                    result.algorithm().id,
                    result.sample(),
                    result.operations(),
                    result.reads(),
                    result.walls(),
                    result.writes(),
                    result.stateCount(),
                    result.checksum(),
                    result.wallNanos(),
                    result.cpuNanos(),
                    result.allocatedBytes(),
                    result.gcCount(),
                    result.gcTimeMillis(),
                    operationsPerSecond,
                    bytesPerOperation,
                    allocationRate));
        }
        Files.write(output, lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static void printSummary(List<BenchmarkResult> results) {
        Map<BenchmarkScenario, Map<Algorithm, List<BenchmarkResult>>> grouped = new EnumMap<>(BenchmarkScenario.class);
        for (BenchmarkResult result : results) {
            grouped.computeIfAbsent(result.scenario(), ignored -> new EnumMap<>(Algorithm.class))
                    .computeIfAbsent(result.algorithm(), ignored -> new ArrayList<>())
                    .add(result);
        }

        System.out.println("scenario algorithm median_ops_s min_ops_s max_ops_s median_bytes_op median_cpu_ms gc_count gc_time_ms");
        grouped.forEach((scenario, byAlgorithm) -> byAlgorithm.forEach((algorithm, samples) -> {
            double[] throughput = samples.stream()
                    .mapToDouble(result -> result.operations() / (result.wallNanos() / 1_000_000_000.0))
                    .sorted().toArray();
            double[] bytesPerOperation = samples.stream()
                    .mapToDouble(result -> result.allocatedBytes() < 0
                            ? -1.0
                            : (double) result.allocatedBytes() / result.operations())
                    .sorted().toArray();
            double[] cpuMillis = samples.stream()
                    .mapToDouble(result -> result.cpuNanos() / 1_000_000.0)
                    .sorted().toArray();
            long gcCount = samples.stream().mapToLong(BenchmarkResult::gcCount).sum();
            long gcTime = samples.stream().mapToLong(BenchmarkResult::gcTimeMillis).sum();
            System.out.printf(Locale.ROOT, "%s %s %.3f %.3f %.3f %.3f %.3f %d %d%n",
                    scenario.id,
                    algorithm.id,
                    median(throughput),
                    throughput[0],
                    throughput[throughput.length - 1],
                    median(bytesPerOperation),
                    median(cpuMillis),
                    gcCount,
                    gcTime);
        }));
    }

    private static double median(double[] sorted) {
        int middle = sorted.length / 2;
        return (sorted.length & 1) == 0
                ? (sorted[middle - 1] + sorted[middle]) / 2.0
                : sorted[middle];
    }

    private enum Algorithm {
        REFERENCE("reference") {
            @Override
            void refreshAround(SimWorld world, BlockPos pos) {
                refreshAt(world, this, pos.north().east());
                refreshAt(world, this, pos.south().east());
                refreshAt(world, this, pos.south().west());
                refreshAt(world, this, pos.north().west());
            }

            @Override
            void probe(ProbeWorld world, BlockPos pos) {
                world.probe(pos.north().east(), 0);
                world.probe(pos.south().east(), 1);
                world.probe(pos.south().west(), 2);
                world.probe(pos.north().west(), 3);
            }
        },
        MUTABLE("mutable") {
            @Override
            void refreshAround(SimWorld world, BlockPos pos) {
                BlockPos.Mutable mutable = new BlockPos.Mutable();
                mutable.set(pos.getX() + 1, pos.getY(), pos.getZ() - 1);
                refreshAt(world, this, mutable);
                mutable.set(pos.getX() + 1, pos.getY(), pos.getZ() + 1);
                refreshAt(world, this, mutable);
                mutable.set(pos.getX() - 1, pos.getY(), pos.getZ() + 1);
                refreshAt(world, this, mutable);
                mutable.set(pos.getX() - 1, pos.getY(), pos.getZ() - 1);
                refreshAt(world, this, mutable);
            }

            @Override
            void probe(ProbeWorld world, BlockPos pos) {
                BlockPos.Mutable mutable = new BlockPos.Mutable();
                mutable.set(pos.getX() + 1, pos.getY(), pos.getZ() - 1);
                world.probe(mutable, 0);
                mutable.set(pos.getX() + 1, pos.getY(), pos.getZ() + 1);
                world.probe(mutable, 1);
                mutable.set(pos.getX() - 1, pos.getY(), pos.getZ() + 1);
                world.probe(mutable, 2);
                mutable.set(pos.getX() - 1, pos.getY(), pos.getZ() - 1);
                world.probe(mutable, 3);
            }
        };

        private final String id;

        Algorithm(String id) {
            this.id = id;
        }

        abstract void refreshAround(SimWorld world, BlockPos pos);

        abstract void probe(ProbeWorld world, BlockPos pos);
    }

    private enum BenchmarkScenario {
        NO_WALLS("no_walls"),
        RELEVANT_WALLS("relevant_walls"),
        BULK_FILL("bulk_fill");

        private final String id;

        BenchmarkScenario(String id) {
            this.id = id;
        }
    }

    private enum CellKind {
        AIR,
        WALL,
        SOLID,
        CONNECTABLE,
        NON_CONNECTABLE
    }

    private enum EventType {
        READ,
        WRITE
    }

    private interface Operation {
        void apply(SimWorld world, Algorithm algorithm);
    }

    private record Change(Pos pos, Cell replacement) implements Operation {
        @Override
        public void apply(SimWorld world, Algorithm algorithm) {
            Cell previous = world.raw(pos);
            world.put(pos, replacement);

            // Wall placement/removal has lifecycle coverage before the global return hook.
            if (previous.kind() == CellKind.WALL || replacement.kind() == CellKind.WALL) {
                algorithm.refreshAround(world, pos.toBlockPos());
            }
            algorithm.refreshAround(world, pos.toBlockPos());
        }
    }

    private record Refresh(Pos pos) implements Operation {
        @Override
        public void apply(SimWorld world, Algorithm algorithm) {
            algorithm.refreshAround(world, pos.toBlockPos());
        }
    }

    private record Pos(int x, int y, int z) {
        private static Pos of(BlockPos pos) {
            return new Pos(pos.getX(), pos.getY(), pos.getZ());
        }

        private Pos add(int dx, int dy, int dz) {
            return new Pos(x + dx, y + dy, z + dz);
        }

        private BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }
    }

    private record Cell(CellKind kind, int cardinalMask, int diagonalMask) {
        private static Cell air() {
            return new Cell(CellKind.AIR, 0, 0);
        }

        private static Cell wall(int cardinalMask, int diagonalMask) {
            return new Cell(CellKind.WALL, cardinalMask & 15, diagonalMask & 15);
        }

        private static Cell solid() {
            return new Cell(CellKind.SOLID, 0, 0);
        }

        private static Cell connectable() {
            return new Cell(CellKind.CONNECTABLE, 0, 0);
        }

        private static Cell nonConnectable() {
            return new Cell(CellKind.NON_CONNECTABLE, 0, 0);
        }
    }

    private record Event(EventType type, Pos pos, int flags) {
    }

    private record ScenarioFixture(String name, SimWorld world, List<Operation> operations) {
    }

    private record Comparison(long writes, long events) {
    }

    private record CorrectnessSummary(int deterministicCases, int randomCases, long writes, long events) {
    }

    private record GcSnapshot(long count, long timeMillis) {
        private static GcSnapshot capture() {
            long count = 0;
            long time = 0;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (bean.getCollectionCount() >= 0) {
                    count += bean.getCollectionCount();
                }
                if (bean.getCollectionTime() >= 0) {
                    time += bean.getCollectionTime();
                }
            }
            return new GcSnapshot(count, time);
        }
    }

    private record BenchmarkResult(
            BenchmarkScenario scenario,
            Algorithm algorithm,
            int sample,
            int operations,
            long reads,
            long walls,
            long writes,
            long stateCount,
            long checksum,
            long wallNanos,
            long cpuNanos,
            long allocatedBytes,
            long gcCount,
            long gcTimeMillis) {
    }

    private record Arguments(boolean benchmark, Path output, int iterations, int warmup) {
        private static Arguments parse(String[] args) {
            boolean benchmark = Arrays.asList(args).contains("--benchmark");
            Path output = Path.of("build", "benchmark-results", "diagonal-wall-refresh.csv");
            int iterations = 500_000;
            int warmup = 100_000;

            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--output" -> output = Path.of(requireValue(args, ++index, "--output"));
                    case "--iterations" -> iterations = Integer.parseInt(requireValue(args, ++index, "--iterations"));
                    case "--warmup" -> warmup = Integer.parseInt(requireValue(args, ++index, "--warmup"));
                    default -> {
                    }
                }
            }

            if (iterations <= 0 || warmup <= 0) {
                throw new IllegalArgumentException("Benchmark iteration counts must be positive");
            }
            return new Arguments(benchmark, output, iterations, warmup);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }
    }

    private static final class SimWorld {
        private final Map<Pos, Cell> cells = new HashMap<>();
        private final List<Event> events = new ArrayList<>();
        private long writeCount;

        private SimWorld copy() {
            SimWorld copy = new SimWorld();
            copy.cells.putAll(cells);
            return copy;
        }

        private Cell raw(Pos pos) {
            return cells.getOrDefault(pos, Cell.air());
        }

        private void put(Pos pos, Cell cell) {
            if (cell.kind() == CellKind.AIR) {
                cells.remove(pos);
            } else {
                cells.put(pos, cell);
            }
        }

        private Cell read(BlockPos pos) {
            Pos snapshot = Pos.of(pos);
            events.add(new Event(EventType.READ, snapshot, 0));
            return raw(snapshot);
        }

        private void write(BlockPos pos, Cell cell, int flags, Algorithm algorithm) {
            if (++writeCount > 20_000) {
                throw new AssertionError("Diagonal refresh did not converge");
            }
            Pos snapshot = Pos.of(pos);
            events.add(new Event(EventType.WRITE, snapshot, flags));
            put(snapshot, cell);

            // A successful setBlockState re-enters the global return hook synchronously.
            algorithm.refreshAround(this, snapshot.toBlockPos());
        }
    }

    private static final class ProbeWorld {
        private final BenchmarkScenario scenario;
        private volatile BlockPos escapedPosition;
        private long reads;
        private long walls;
        private long writes;
        private long stateCount;
        private long checksum;

        private ProbeWorld(BenchmarkScenario scenario) {
            this.scenario = scenario;
        }

        private void probe(BlockPos pos, int ordinal) {
            reads++;
            escapedPosition = pos;
            long hash = ((long) pos.getX() * 0x9e3779b97f4a7c15L)
                    ^ ((long) pos.getY() * 0xc2b2ae3d27d4eb4fL)
                    ^ ((long) pos.getZ() * 0x165667b19e3779f9L)
                    ^ ordinal;
            boolean wall = switch (scenario) {
                case NO_WALLS -> false;
                case RELEVANT_WALLS -> (hash & 3L) != 0L;
                case BULK_FILL -> true;
            };
            if (!wall) {
                checksum = Long.rotateLeft(checksum ^ hash, 7);
                return;
            }

            walls++;
            int computedDiagonals = 0;
            long stateHash = hash;
            for (int diagonal = 0; diagonal < 4; diagonal++) {
                reads++;
                stateHash = Long.rotateLeft(
                        stateHash + 0x9e3779b97f4a7c15L + diagonal * 0x165667b19e3779f9L,
                        13);
                if ((stateHash & 3L) != 0L) {
                    computedDiagonals |= 1 << diagonal;
                }
            }
            boolean changed = scenario == BenchmarkScenario.BULK_FILL
                    ? (hash & 1L) == 0L
                    : (hash & 7L) == 1L;
            if (changed) {
                BlockPos stable = pos.toImmutable();
                escapedPosition = stable;
                writes++;
                stateCount += computedDiagonals + ordinal + 1L;
                checksum = Long.rotateLeft(checksum ^ stable.asLong() ^ stateHash ^ stateCount, 11);
            } else {
                stateCount += computedDiagonals;
                checksum = Long.rotateLeft(checksum ^ stateHash, 7);
            }
        }
    }
}
