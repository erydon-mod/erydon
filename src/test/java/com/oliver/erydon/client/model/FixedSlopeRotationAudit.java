package com.oliver.erydon.client.model;

import com.sun.management.ThreadMXBean;
import net.minecraft.client.render.model.ModelRotation;
import net.minecraft.util.math.Direction;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class FixedSlopeRotationAudit {
    private static final int[] X_ROTATIONS = {0, 180};
    private static final int[] Y_ROTATIONS = {0, 90, 180, 270};
    private static final int SAMPLE_COUNT = 7;
    private static final Vector3f[] ESCAPE_RING = new Vector3f[4096];
    private static volatile long blackhole;

    private FixedSlopeRotationAudit() {
    }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        VerificationSummary summary = verify();
        System.out.printf(Locale.ROOT,
                "Fixed slope rotation verification passed: rotations=%d positions=%d normals=%d quadNormals=%d faces=%d exactFloatBits=%d%n",
                summary.rotations(), summary.positions(), summary.normals(), summary.quadNormals(),
                summary.faces(), summary.exactFloatBits());
        if (arguments.benchmark()) {
            benchmark(arguments);
        }
    }

    private static VerificationSummary verify() {
        long positions = 0;
        long normals = 0;
        long quadNormals = 0;
        long faces = 0;
        long exactFloatBits = 0;
        float[] coordinates = {
                -0.001F, 0.0F, 0.0005F, 0.0625F, 0.125F, 0.25F,
                0.4995F, 0.5F, 0.5005F, 0.75F, 0.875F, 0.9375F,
                0.9995F, 1.0F, 1.001F
        };
        Random random = new Random(0x455259444f4eL);

        for (int xDegrees : X_ROTATIONS) {
            for (int yDegrees : Y_ROTATIONS) {
                FixedSlopeRotation candidate = FixedSlopeRotation.of(xDegrees, yDegrees);
                ModelRotation modelRotation = ModelRotation.get(xDegrees, yDegrees);
                Matrix4f matrix = modelRotation.getRotation().getMatrix();

                for (int index = 0; index < coordinates.length; index++) {
                    float x = coordinates[index];
                    float y = coordinates[(index * 5 + 3) % coordinates.length];
                    float z = coordinates[(index * 11 + 7) % coordinates.length];
                    exactFloatBits += verifyPosition(matrix, candidate, x, y, z);
                    positions++;
                    exactFloatBits += verifyDirection(matrix, candidate, x - 0.5F, y - 0.5F, z - 0.5F);
                    normals++;
                }

                for (int sample = 0; sample < 50_000; sample++) {
                    float x = random.nextFloat() * 1.004F - 0.002F;
                    float y = random.nextFloat() * 1.004F - 0.002F;
                    float z = random.nextFloat() * 1.004F - 0.002F;
                    exactFloatBits += verifyPosition(matrix, candidate, x, y, z);
                    positions++;

                    float normalX = random.nextFloat() * 2.0F - 1.0F;
                    float normalY = random.nextFloat() * 2.0F - 1.0F;
                    float normalZ = random.nextFloat() * 2.0F - 1.0F;
                    exactFloatBits += verifyDirection(matrix, candidate, normalX, normalY, normalZ);
                    normals++;

                    float x1 = random.nextFloat();
                    float y1 = random.nextFloat();
                    float z1 = random.nextFloat();
                    float x2 = random.nextFloat();
                    float y2 = random.nextFloat();
                    float z2 = random.nextFloat();
                    float x3 = random.nextFloat();
                    float y3 = random.nextFloat();
                    float z3 = random.nextFloat();
                    exactFloatBits += verifyQuadNormal(matrix, candidate,
                            x, y, z,
                            x1, y1, z1,
                            x2, y2, z2,
                            x3, y3, z3);
                    quadNormals++;
                }

                for (Direction face : Direction.values()) {
                    Direction expected = modelRotation.getDirectionTransformation().map(face);
                    Direction actual = candidate.mapFace(face);
                    if (expected != actual) {
                        throw new AssertionError("Face mismatch x=" + xDegrees + " y=" + yDegrees
                                + " face=" + face + " expected=" + expected + " actual=" + actual);
                    }
                    faces++;
                }
                if (candidate.mapFace(null) != null) {
                    throw new AssertionError("Null face mapping changed");
                }
            }
        }
        return new VerificationSummary(8, positions, normals, quadNormals, faces, exactFloatBits);
    }

    private static long verifyPosition(Matrix4f matrix, FixedSlopeRotation candidate,
                                       float x, float y, float z) {
        Vector3f expected = new Vector3f(x - 0.5F, y - 0.5F, z - 0.5F);
        matrix.transformPosition(expected).add(0.5F, 0.5F, 0.5F);
        return assertExact("position", expected.x, expected.y, expected.z,
                candidate.positionX(x, y, z), candidate.positionY(x, y, z), candidate.positionZ(x, y, z));
    }

    private static long verifyDirection(Matrix4f matrix, FixedSlopeRotation candidate,
                                        float x, float y, float z) {
        Vector3f expected = new Vector3f(x, y, z);
        matrix.transformDirection(expected);
        return assertExact("direction", expected.x, expected.y, expected.z,
                candidate.directionX(x, y, z), candidate.directionY(x, y, z), candidate.directionZ(x, y, z));
    }

    private static long verifyQuadNormal(Matrix4f matrix,
                                         FixedSlopeRotation candidate,
                                         float x0, float y0, float z0,
                                         float x1, float y1, float z1,
                                         float x2, float y2, float z2,
                                         float x3, float y3, float z3) {
        Vector3f expected = cross(
                x1 - x0, y1 - y0, z1 - z0,
                x2 - x0, y2 - y0, z2 - z0);
        if (expected.lengthSquared() <= 0.000001F) {
            expected = cross(
                    x2 - x0, y2 - y0, z2 - z0,
                    x3 - x0, y3 - y0, z3 - z0);
        }
        expected.normalize();
        matrix.transformDirection(expected);

        float ax = x1 - x0;
        float ay = y1 - y0;
        float az = z1 - z0;
        float bx = x2 - x0;
        float by = y2 - y0;
        float bz = z2 - z0;
        float normalX = ay * bz - az * by;
        float normalY = az * bx - ax * bz;
        float normalZ = ax * by - ay * bx;
        float lengthSquared = normalX * normalX + (normalY * normalY + normalZ * normalZ);
        if (lengthSquared <= 0.000001F) {
            ax = x2 - x0;
            ay = y2 - y0;
            az = z2 - z0;
            bx = x3 - x0;
            by = y3 - y0;
            bz = z3 - z0;
            normalX = ay * bz - az * by;
            normalY = az * bx - ax * bz;
            normalZ = ax * by - ay * bx;
            lengthSquared = normalX * normalX + (normalY * normalY + normalZ * normalZ);
        }
        float inverseLength = 1.0F / (float) Math.sqrt(lengthSquared);
        normalX *= inverseLength;
        normalY *= inverseLength;
        normalZ *= inverseLength;

        return assertExact("quad normal", expected.x, expected.y, expected.z,
                candidate.directionX(normalX, normalY, normalZ),
                candidate.directionY(normalX, normalY, normalZ),
                candidate.directionZ(normalX, normalY, normalZ));
    }

    private static Vector3f cross(float ax, float ay, float az, float bx, float by, float bz) {
        return new Vector3f(
                ay * bz - az * by,
                az * bx - ax * bz,
                ax * by - ay * bx);
    }

    private static long assertExact(String label,
                                    float expectedX, float expectedY, float expectedZ,
                                    float actualX, float actualY, float actualZ) {
        int expectedXBits = Float.floatToRawIntBits(expectedX);
        int expectedYBits = Float.floatToRawIntBits(expectedY);
        int expectedZBits = Float.floatToRawIntBits(expectedZ);
        if (expectedXBits != Float.floatToRawIntBits(actualX)
                || expectedYBits != Float.floatToRawIntBits(actualY)
                || expectedZBits != Float.floatToRawIntBits(actualZ)) {
            throw new AssertionError(label + " mismatch expected="
                    + expectedX + "," + expectedY + "," + expectedZ
                    + " actual=" + actualX + "," + actualY + "," + actualZ);
        }
        return 3;
    }

    private static void benchmark(Arguments arguments) throws IOException {
        ThreadMXBean threadBean = threadBean();
        for (int warmup = 0; warmup < 3; warmup++) {
            runOne(Algorithm.JOML_REFERENCE, arguments.warmup(), 0, threadBean, true);
            runOne(Algorithm.FIXED_SCALAR, arguments.warmup(), 0, threadBean, true);
        }

        List<BenchmarkResult> results = new ArrayList<>();
        for (int sample = 1; sample <= SAMPLE_COUNT; sample++) {
            Algorithm first = (sample & 1) == 0 ? Algorithm.FIXED_SCALAR : Algorithm.JOML_REFERENCE;
            Algorithm second = first == Algorithm.JOML_REFERENCE ? Algorithm.FIXED_SCALAR : Algorithm.JOML_REFERENCE;
            BenchmarkResult firstResult = runOne(first, arguments.iterations(), sample, threadBean, false);
            BenchmarkResult secondResult = runOne(second, arguments.iterations(), sample, threadBean, false);
            if (firstResult.checksum() != secondResult.checksum()) {
                throw new AssertionError("Benchmark checksum mismatch: " + firstResult + " versus " + secondResult);
            }
            results.add(firstResult);
            results.add(secondResult);
        }

        writeCsv(arguments.output(), results);
        printSummary(results);
        System.out.println("Raw fixed-slope benchmark written to " + arguments.output());
    }

    private static BenchmarkResult runOne(Algorithm algorithm, int quads, int sample,
                                          ThreadMXBean threadBean, boolean warmup) {
        long threadId = Thread.currentThread().getId();
        long allocatedBefore = threadBean == null ? -1 : threadBean.getThreadAllocatedBytes(threadId);
        long cpuBefore = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
        GcSnapshot gcBefore = GcSnapshot.capture();
        long wallBefore = System.nanoTime();
        long checksum = algorithm.run(quads);
        long wallNanos = System.nanoTime() - wallBefore;
        GcSnapshot gcAfter = GcSnapshot.capture();
        long cpuNanos = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - cpuBefore;
        long allocatedBytes = threadBean == null ? -1 : threadBean.getThreadAllocatedBytes(threadId) - allocatedBefore;
        blackhole ^= checksum;

        if (warmup) {
            return null;
        }
        return new BenchmarkResult(algorithm, sample, quads, quads * 4L, quads * 4L,
                checksum, wallNanos, cpuNanos, allocatedBytes,
                gcAfter.count() - gcBefore.count(), gcAfter.timeMillis() - gcBefore.timeMillis());
    }

    private enum Algorithm {
        JOML_REFERENCE("joml_reference") {
            @Override
            long run(int quads) {
                Matrix4f[] matrices = matrices();
                long checksum = 0;
                int escapeIndex = 0;
                for (int quad = 0; quad < quads; quad++) {
                    Matrix4f matrix = matrices[quad & 7];
                    for (int vertex = 0; vertex < 4; vertex++) {
                        float x = coordinate(quad, vertex, 13);
                        float y = coordinate(quad, vertex, 29);
                        float z = coordinate(quad, vertex, 47);
                        Vector3f position = new Vector3f(x - 0.5F, y - 0.5F, z - 0.5F);
                        matrix.transformPosition(position).add(0.5F, 0.5F, 0.5F);
                        ESCAPE_RING[escapeIndex++ & (ESCAPE_RING.length - 1)] = position;

                        float normalX = x * 2.0F - 1.0F;
                        float normalY = y * 2.0F - 1.0F;
                        float normalZ = z * 2.0F - 1.0F;
                        Vector3f normal = new Vector3f(normalX, normalY, normalZ);
                        matrix.transformDirection(normal);
                        ESCAPE_RING[escapeIndex++ & (ESCAPE_RING.length - 1)] = normal;
                        checksum = mix(checksum, position.x, position.y, position.z,
                                normal.x, normal.y, normal.z);
                    }
                }
                return checksum;
            }
        },
        FIXED_SCALAR("fixed_scalar") {
            @Override
            long run(int quads) {
                FixedSlopeRotation[] rotations = rotations();
                long checksum = 0;
                for (int quad = 0; quad < quads; quad++) {
                    FixedSlopeRotation rotation = rotations[quad & 7];
                    for (int vertex = 0; vertex < 4; vertex++) {
                        float x = coordinate(quad, vertex, 13);
                        float y = coordinate(quad, vertex, 29);
                        float z = coordinate(quad, vertex, 47);
                        float positionX = rotation.positionX(x, y, z);
                        float positionY = rotation.positionY(x, y, z);
                        float positionZ = rotation.positionZ(x, y, z);
                        float normalX = x * 2.0F - 1.0F;
                        float normalY = y * 2.0F - 1.0F;
                        float normalZ = z * 2.0F - 1.0F;
                        float transformedNormalX = rotation.directionX(normalX, normalY, normalZ);
                        float transformedNormalY = rotation.directionY(normalX, normalY, normalZ);
                        float transformedNormalZ = rotation.directionZ(normalX, normalY, normalZ);
                        checksum = mix(checksum, positionX, positionY, positionZ,
                                transformedNormalX, transformedNormalY, transformedNormalZ);
                    }
                }
                return checksum;
            }
        };

        private final String id;

        Algorithm(String id) {
            this.id = id;
        }

        abstract long run(int quads);
    }

    private static float coordinate(int quad, int vertex, int multiplier) {
        int value = (quad * multiplier + vertex * 97) & 1023;
        return value / 1023.0F;
    }

    private static long mix(long checksum,
                            float value0, float value1, float value2,
                            float value3, float value4, float value5) {
        checksum = Long.rotateLeft(checksum ^ Float.floatToRawIntBits(value0), 9);
        checksum = Long.rotateLeft(checksum ^ Float.floatToRawIntBits(value1), 9);
        checksum = Long.rotateLeft(checksum ^ Float.floatToRawIntBits(value2), 9);
        checksum = Long.rotateLeft(checksum ^ Float.floatToRawIntBits(value3), 9);
        checksum = Long.rotateLeft(checksum ^ Float.floatToRawIntBits(value4), 9);
        checksum = Long.rotateLeft(checksum ^ Float.floatToRawIntBits(value5), 9);
        return checksum;
    }

    private static Matrix4f[] matrices() {
        Matrix4f[] matrices = new Matrix4f[8];
        int index = 0;
        for (int xDegrees : X_ROTATIONS) {
            for (int yDegrees : Y_ROTATIONS) {
                matrices[index++] = ModelRotation.get(xDegrees, yDegrees).getRotation().getMatrix();
            }
        }
        return matrices;
    }

    private static FixedSlopeRotation[] rotations() {
        FixedSlopeRotation[] rotations = new FixedSlopeRotation[8];
        int index = 0;
        for (int xDegrees : X_ROTATIONS) {
            for (int yDegrees : Y_ROTATIONS) {
                rotations[index++] = FixedSlopeRotation.of(xDegrees, yDegrees);
            }
        }
        return rotations;
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

    private static void writeCsv(Path output, List<BenchmarkResult> results) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = new ArrayList<>();
        lines.add("algorithm,sample,quads,vertices,normals,checksum,wall_ns,cpu_ns,allocated_bytes,gc_count,gc_time_ms,quads_per_second,bytes_per_quad,bytes_per_vertex");
        for (BenchmarkResult result : results) {
            double seconds = result.wallNanos() / 1_000_000_000.0;
            lines.add(String.format(Locale.ROOT,
                    "%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.3f,%.3f,%.3f",
                    result.algorithm().id, result.sample(), result.quads(), result.vertices(), result.normals(),
                    result.checksum(), result.wallNanos(), result.cpuNanos(), result.allocatedBytes(),
                    result.gcCount(), result.gcTimeMillis(), result.quads() / seconds,
                    result.allocatedBytes() < 0 ? -1.0 : (double) result.allocatedBytes() / result.quads(),
                    result.allocatedBytes() < 0 ? -1.0 : (double) result.allocatedBytes() / result.vertices()));
        }
        Files.write(output, lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static void printSummary(List<BenchmarkResult> results) {
        for (Algorithm algorithm : Algorithm.values()) {
            List<BenchmarkResult> samples = results.stream().filter(result -> result.algorithm() == algorithm).toList();
            double[] throughput = samples.stream()
                    .mapToDouble(result -> result.quads() / (result.wallNanos() / 1_000_000_000.0)).sorted().toArray();
            double[] bytesPerQuad = samples.stream()
                    .mapToDouble(result -> (double) result.allocatedBytes() / result.quads()).sorted().toArray();
            long gcCount = samples.stream().mapToLong(BenchmarkResult::gcCount).sum();
            long gcTime = samples.stream().mapToLong(BenchmarkResult::gcTimeMillis).sum();
            System.out.printf(Locale.ROOT,
                    "%s median_quads_s=%.3f min=%.3f max=%.3f median_bytes_quad=%.3f gc_count=%d gc_ms=%d%n",
                    algorithm.id, median(throughput), throughput[0], throughput[throughput.length - 1],
                    median(bytesPerQuad), gcCount, gcTime);
        }
    }

    private static double median(double[] sorted) {
        int middle = sorted.length / 2;
        return (sorted.length & 1) == 0
                ? (sorted[middle - 1] + sorted[middle]) / 2.0
                : sorted[middle];
    }

    private record VerificationSummary(int rotations, long positions, long normals, long quadNormals,
                                       long faces, long exactFloatBits) {
    }

    private record GcSnapshot(long count, long timeMillis) {
        private static GcSnapshot capture() {
            long count = 0;
            long time = 0;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                count += Math.max(bean.getCollectionCount(), 0);
                time += Math.max(bean.getCollectionTime(), 0);
            }
            return new GcSnapshot(count, time);
        }
    }

    private record BenchmarkResult(Algorithm algorithm, int sample, int quads, long vertices, long normals,
                                   long checksum, long wallNanos, long cpuNanos, long allocatedBytes,
                                   long gcCount, long gcTimeMillis) {
    }

    private record Arguments(boolean benchmark, Path output, int iterations, int warmup) {
        private static Arguments parse(String[] args) {
            boolean benchmark = Arrays.asList(args).contains("--benchmark");
            Path output = Path.of("build", "benchmark-results", "fixed-slope-rotation.csv");
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
}
