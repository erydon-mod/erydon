package com.oliver.erydon.client.model;

import net.minecraft.util.math.Direction;

import java.util.HashMap;
import java.util.Map;

public final class SlopeWorldUvAudit {
    private static final float EPSILON = 0.0001F;
    private static final int[] FULL_X_ROTATIONS = {0, 180};
    private static final int[] Y_ROTATIONS = {0, 90, 180, 270};
    private static final float[] SAMPLES = {0.0F, 0.25F, 0.5F, 0.75F, 1.0F};
    private static final FamilySpec[] FAMILIES = {
            new FamilySpec("standard", FULL_X_ROTATIONS, 1.0F, 0.0F),
            new FamilySpec("shallow-lower", FULL_X_ROTATIONS, 0.5F, 0.0F),
            new FamilySpec("shallow-upper", FULL_X_ROTATIONS, 0.5F, 0.5F),
            new FamilySpec("vertical", new int[]{0}, 0.0F, 1.0F),
            new FamilySpec("vertical-shallow-broad", new int[]{0}, 0.0F, 1.0F),
            new FamilySpec("vertical-shallow-narrow", new int[]{0}, 0.0F, 1.0F)
    };
    private static final Cell[] TEST_CELLS = {
            new Cell(0, 0, 0),
            new Cell(5, 7, 11),
            new Cell(-4, -3, -9)
    };

    private int checkedVertices;
    private int checkedNeighbourJoins;
    private int checkedFamilyJoins;
    private int checkedStretchCases;

    private SlopeWorldUvAudit() {
    }

    public static void main(String[] args) {
        SlopeWorldUvAudit audit = new SlopeWorldUvAudit();
        audit.run();
        System.out.println("Validated " + FAMILIES.length + " non-steep slope families across "
                + audit.checkedVertices + " world-projected face vertices, "
                + audit.checkedNeighbourJoins + " standard neighbour joins, "
                + audit.checkedFamilyJoins + " upper/lower and straight/corner joins, and "
                + audit.checkedStretchCases + " minimal-stretch slope edges.");
    }

    private void run() {
        validateEveryFamilyFace();
        validateStandardNeighbourJoins();
        validateFamilyJoins();
        validateMinimalStretch();
    }

    private void validateEveryFamilyFace() {
        for (FamilySpec family : FAMILIES) {
            for (int xDegrees : family.xRotations()) {
                for (int yDegrees : Y_ROTATIONS) {
                    FixedSlopeRotation transform = FixedSlopeRotation.of(xDegrees, yDegrees);
                    for (Direction nominalFace : Direction.values()) {
                        for (float s : SAMPLES) {
                            for (float t : SAMPLES) {
                                SourceVertex vertex = sourceVertex(family, nominalFace, s, t);
                                validateVertex(family.name(), transform, nominalFace, vertex);
                            }
                        }
                    }
                }
            }
        }
    }

    private void validateVertex(String family,
                                FixedSlopeRotation transform,
                                Direction nominalFace,
                                SourceVertex vertex) {
        Direction finalFace = transform.mapFace(nominalFace);
        float u = SlopeWorldUv.projectedU(transform, nominalFace, finalFace,
                vertex.x(), vertex.y(), vertex.z(), vertex.authoredU(), vertex.authoredV());
        float v = SlopeWorldUv.projectedV(transform, nominalFace, finalFace,
                vertex.x(), vertex.y(), vertex.z(), vertex.authoredU(), vertex.authoredV());

        float sourceX = nominalFace == Direction.UP ? vertex.authoredU() / 16.0F : vertex.x();
        float sourceY = nominalFace == Direction.UP ? 0.5F : vertex.y();
        float sourceZ = nominalFace == Direction.UP ? vertex.authoredV() / 16.0F : vertex.z();
        float localWorldX = transform.positionX(sourceX, sourceY, sourceZ);
        float localWorldY = transform.positionY(sourceX, sourceY, sourceZ);
        float localWorldZ = transform.positionZ(sourceX, sourceY, sourceZ);
        float expectedU = referenceU(finalFace, localWorldX, localWorldY, localWorldZ);
        float expectedV = referenceV(finalFace, localWorldX, localWorldY, localWorldZ);

        if (!near(u, expectedU) || !near(v, expectedV)) {
            fail(family + " " + nominalFace + " -> " + finalFace
                    + " does not use the standard world face projection");
        }
        if (u < -EPSILON || u > 16.0F + EPSILON || v < -EPSILON || v > 16.0F + EPSILON) {
            fail(family + " produced UV outside its selected CTM cell: " + u + ", " + v);
        }

        for (Cell cell : TEST_CELLS) {
            float atlasS = cellS(finalFace, cell) + u / 16.0F;
            float atlasT = cellT(finalFace, cell) + v / 16.0F;
            float worldX = cell.x() + localWorldX;
            float worldY = cell.y() + localWorldY;
            float worldZ = cell.z() + localWorldZ;
            if (!near(atlasS, globalS(finalFace, worldX, worldY, worldZ))
                    || !near(atlasT, globalT(finalFace, worldX, worldY, worldZ))) {
                fail(family + " " + finalFace + " changed repeat-atlas phase at " + cell);
            }
        }
        checkedVertices++;
    }

    private void validateStandardNeighbourJoins() {
        FixedSlopeRotation identity = FixedSlopeRotation.of(0, 0);
        for (Direction face : Direction.values()) {
            Map<String, AtlasPoint> points = new HashMap<>();
            for (int first = 0; first < 2; first++) {
                for (int second = 0; second < 2; second++) {
                    Cell cell = surfaceCell(face, first, second);
                    for (int localFirst = 0; localFirst < 2; localFirst++) {
                        for (int localSecond = 0; localSecond < 2; localSecond++) {
                            SourceVertex vertex = faceVertex(face, localFirst, localSecond);
                            float u = SlopeWorldUv.projectedU(identity, face, face,
                                    vertex.x(), vertex.y(), vertex.z(), vertex.authoredU(), vertex.authoredV());
                            float v = SlopeWorldUv.projectedV(identity, face, face,
                                    vertex.x(), vertex.y(), vertex.z(), vertex.authoredU(), vertex.authoredV());
                            float worldX = cell.x() + vertex.x();
                            float worldY = cell.y() + vertex.y();
                            float worldZ = cell.z() + vertex.z();
                            String key = positionKey(worldX, worldY, worldZ);
                            AtlasPoint current = new AtlasPoint(
                                    cellS(face, cell) + u / 16.0F,
                                    cellT(face, cell) + v / 16.0F);
                            AtlasPoint previous = points.putIfAbsent(key, current);
                            if (previous != null) {
                                if (!near(previous.s(), current.s()) || !near(previous.t(), current.t())) {
                                    fail(face + " changed CTM phase across a standard neighbouring face");
                                }
                                checkedNeighbourJoins++;
                            }
                        }
                    }
                }
            }
        }
    }

    private void validateFamilyJoins() {
        for (int xDegrees : FULL_X_ROTATIONS) {
            for (int yDegrees : Y_ROTATIONS) {
                FixedSlopeRotation transform = FixedSlopeRotation.of(xDegrees, yDegrees);
                Direction finalFace = transform.mapFace(Direction.UP);
                for (float x : SAMPLES) {
                    for (float z : SAMPLES) {
                        float authoredU = x * 16.0F;
                        float authoredV = z * 16.0F;
                        AtlasPoint lower = projectedPoint(transform, finalFace,
                                x, 0.5F * (1.0F - x), z, authoredU, authoredV);
                        AtlasPoint upper = projectedPoint(transform, finalFace,
                                x, 0.5F + 0.5F * (1.0F - x), z, authoredU, authoredV);
                        AtlasPoint corner = projectedPoint(transform, finalFace,
                                x, Math.max(1.0F - x, z), z, authoredU, authoredV);
                        if (!same(lower, upper)) {
                            fail("shallow upper/lower CTM phase changed at a shared top point");
                        }
                        if (!same(lower, corner)) {
                            fail("straight/corner CTM phase changed at a shared top point");
                        }
                        checkedFamilyJoins += 2;
                    }
                }
            }
        }
    }

    private void validateMinimalStretch() {
        validateStretch("standard", Direction.UP,
                new SourceVertex(0.0F, 1.0F, 0.0F, 0.0F, 0.0F),
                new SourceVertex(1.0F, 0.0F, 0.0F, 16.0F, 0.0F),
                (float) Math.sqrt(2.0D));
        validateStretch("shallow-lower", Direction.UP,
                new SourceVertex(0.0F, 0.5F, 0.0F, 0.0F, 0.0F),
                new SourceVertex(1.0F, 0.0F, 0.0F, 16.0F, 0.0F),
                (float) Math.sqrt(1.25D));
        validateStretch("shallow-upper", Direction.UP,
                new SourceVertex(0.0F, 1.0F, 0.0F, 0.0F, 0.0F),
                new SourceVertex(1.0F, 0.5F, 0.0F, 16.0F, 0.0F),
                (float) Math.sqrt(1.25D));
        validateStretch("vertical", Direction.SOUTH,
                new SourceVertex(0.0F, 0.0F, 1.0F, 0.0F, 0.0F),
                new SourceVertex(1.0F, 0.0F, 0.0F, 0.0F, 0.0F),
                (float) Math.sqrt(2.0D));
        validateStretch("vertical-shallow-broad", Direction.EAST,
                new SourceVertex(0.5F, 0.0F, 1.0F, 0.0F, 0.0F),
                new SourceVertex(1.0F, 0.0F, 0.0F, 0.0F, 0.0F),
                (float) Math.sqrt(1.25D));
        validateStretch("vertical-shallow-narrow", Direction.EAST,
                new SourceVertex(0.0F, 0.0F, 1.0F, 0.0F, 0.0F),
                new SourceVertex(0.5F, 0.0F, 0.0F, 0.0F, 0.0F),
                (float) Math.sqrt(1.25D));
    }

    private void validateStretch(String family,
                                 Direction nominalFace,
                                 SourceVertex first,
                                 SourceVertex second,
                                 float expectedStretch) {
        int[] xRotations = nominalFace == Direction.UP ? FULL_X_ROTATIONS : new int[]{0};
        for (int xDegrees : xRotations) {
            for (int yDegrees : Y_ROTATIONS) {
                FixedSlopeRotation transform = FixedSlopeRotation.of(xDegrees, yDegrees);
                Direction finalFace = transform.mapFace(nominalFace);
                float firstU = SlopeWorldUv.projectedU(transform, nominalFace, finalFace,
                        first.x(), first.y(), first.z(), first.authoredU(), first.authoredV());
                float firstV = SlopeWorldUv.projectedV(transform, nominalFace, finalFace,
                        first.x(), first.y(), first.z(), first.authoredU(), first.authoredV());
                float secondU = SlopeWorldUv.projectedU(transform, nominalFace, finalFace,
                        second.x(), second.y(), second.z(), second.authoredU(), second.authoredV());
                float secondV = SlopeWorldUv.projectedV(transform, nominalFace, finalFace,
                        second.x(), second.y(), second.z(), second.authoredU(), second.authoredV());
                float textureLength = (float) Math.hypot(
                        (secondU - firstU) / 16.0F,
                        (secondV - firstV) / 16.0F);
                float physicalLength = distance(transform, first, second);
                float stretch = physicalLength / textureLength;
                if (!near(stretch, expectedStretch)) {
                    fail(family + " slope stretch " + stretch + " != minimal world-projected "
                            + expectedStretch + " at x=" + xDegrees + ", y=" + yDegrees);
                }
                checkedStretchCases++;
            }
        }
    }

    private static float distance(FixedSlopeRotation transform, SourceVertex first, SourceVertex second) {
        float dx = transform.positionX(second.x(), second.y(), second.z())
                - transform.positionX(first.x(), first.y(), first.z());
        float dy = transform.positionY(second.x(), second.y(), second.z())
                - transform.positionY(first.x(), first.y(), first.z());
        float dz = transform.positionZ(second.x(), second.y(), second.z())
                - transform.positionZ(first.x(), first.y(), first.z());
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static AtlasPoint projectedPoint(FixedSlopeRotation transform,
                                             Direction finalFace,
                                             float x,
                                             float y,
                                             float z,
                                             float authoredU,
                                             float authoredV) {
        return new AtlasPoint(
                SlopeWorldUv.projectedU(transform, Direction.UP, finalFace,
                        x, y, z, authoredU, authoredV),
                SlopeWorldUv.projectedV(transform, Direction.UP, finalFace,
                        x, y, z, authoredU, authoredV));
    }

    private static SourceVertex sourceVertex(FamilySpec family, Direction face, float s, float t) {
        if (face == Direction.UP) {
            float height = family.topBase() + family.topRise() * (1.0F - s);
            return new SourceVertex(s, height, t, s * 16.0F, t * 16.0F);
        }
        return faceVertex(face, s, t);
    }

    private static SourceVertex faceVertex(Direction face, float s, float t) {
        return switch (face) {
            case UP -> new SourceVertex(s, 1.0F, t, s * 16.0F, t * 16.0F);
            case DOWN -> new SourceVertex(s, 0.0F, t, s * 16.0F, t * 16.0F);
            case NORTH -> new SourceVertex(s, t, 0.0F, s * 16.0F, (1.0F - t) * 16.0F);
            case SOUTH -> new SourceVertex(s, t, 1.0F, s * 16.0F, (1.0F - t) * 16.0F);
            case WEST -> new SourceVertex(0.0F, t, s, s * 16.0F, (1.0F - t) * 16.0F);
            case EAST -> new SourceVertex(1.0F, t, s, s * 16.0F, (1.0F - t) * 16.0F);
        };
    }

    private static Cell surfaceCell(Direction face, int first, int second) {
        return switch (face) {
            case UP, DOWN -> new Cell(first, 3, second);
            case NORTH, SOUTH -> new Cell(first, second, 3);
            case WEST, EAST -> new Cell(3, second, first);
        };
    }

    private static float referenceU(Direction face, float x, float y, float z) {
        float textureS = face == Direction.EAST || face == Direction.WEST ? z : x;
        return textureUnits(face == Direction.NORTH || face == Direction.EAST
                ? 1.0F - textureS
                : textureS);
    }

    private static float referenceV(Direction face, float x, float y, float z) {
        float textureT = face == Direction.UP || face == Direction.DOWN ? z : y;
        return textureUnits(face == Direction.UP ? textureT : 1.0F - textureT);
    }

    private static float textureUnits(float normalized) {
        if (Math.abs(normalized) <= 0.000001F) {
            return 0.0F;
        }
        if (Math.abs(normalized - 1.0F) <= 0.000001F) {
            return 16.0F;
        }
        return normalized * 16.0F;
    }

    private static float cellS(Direction face, Cell cell) {
        return switch (face) {
            case UP, DOWN, SOUTH -> cell.x();
            case NORTH -> -cell.x() - 1;
            case WEST -> cell.z();
            case EAST -> -cell.z() - 1;
        };
    }

    private static float cellT(Direction face, Cell cell) {
        return switch (face) {
            case UP -> cell.z();
            case DOWN -> -cell.z() - 1;
            case NORTH, SOUTH, WEST, EAST -> -cell.y();
        };
    }

    private static float globalS(Direction face, float x, float y, float z) {
        return switch (face) {
            case UP, DOWN, SOUTH -> x;
            case NORTH -> -x;
            case WEST -> z;
            case EAST -> -z;
        };
    }

    private static float globalT(Direction face, float x, float y, float z) {
        return switch (face) {
            case UP -> z;
            case DOWN -> -z;
            case NORTH, SOUTH, WEST, EAST -> 1.0F - y;
        };
    }

    private static String positionKey(float x, float y, float z) {
        return quantize(x) + "," + quantize(y) + "," + quantize(z);
    }

    private static long quantize(float value) {
        return Math.round(value / EPSILON);
    }

    private static boolean same(AtlasPoint first, AtlasPoint second) {
        return near(first.s(), second.s()) && near(first.t(), second.t());
    }

    private static boolean near(float first, float second) {
        return Math.abs(first - second) <= EPSILON;
    }

    private static void fail(String message) {
        throw new IllegalStateException(message);
    }

    private record FamilySpec(String name, int[] xRotations, float topRise, float topBase) {
    }

    private record SourceVertex(float x, float y, float z, float authoredU, float authoredV) {
    }

    private record Cell(int x, int y, int z) {
    }

    private record AtlasPoint(float s, float t) {
    }
}
