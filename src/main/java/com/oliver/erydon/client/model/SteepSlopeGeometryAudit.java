package com.oliver.erydon.client.model;

import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SteepSlopeGeometryAudit {
    private static final float EPSILON = 0.0001F;
    private static final int[] X_ROTATIONS = {0, 180};
    private static final int[] Y_ROTATIONS = {0, 90, 180, 270};

    private int checkedMeshes;
    private int checkedQuads;
    private int checkedUvVertices;
    private int checkedTopJoins;

    private SteepSlopeGeometryAudit() {
    }

    public static void main(String[] args) {
        SteepSlopeGeometryAudit audit = new SteepSlopeGeometryAudit();
        audit.run();
        System.out.println("Validated " + audit.checkedMeshes + " steep meshes, "
                + audit.checkedQuads + " quads, and "
                + audit.checkedUvVertices + " face-projected UV vertices across "
                + audit.checkedTopJoins + " adjacent top joins.");
    }

    private void run() {
        for (boolean upperVariant : new boolean[]{false, true}) {
            for (int xDegrees : X_ROTATIONS) {
                for (int yDegrees : Y_ROTATIONS) {
                    List<SlopeSteepBakedModel.CornerDebugQuad> logical = SlopeSteepBakedModel.debugLogicalStraightGeometry(
                            upperVariant, xDegrees, yDegrees);
                    validateMesh(logical, upperVariant, false, true, xDegrees, yDegrees);
                    validateWorldAlignedUvs(logical, upperVariant, false, xDegrees, yDegrees);
                    validateAxisAlignedScale(logical, upperVariant, false, xDegrees, yDegrees);
                    validateStraightSlopeAtlas(logical, upperVariant, xDegrees, yDegrees);

                    List<SlopeSteepBakedModel.CornerDebugQuad> live = SlopeSteepBakedModel.debugLiveStraightGeometry(
                            upperVariant, xDegrees, yDegrees);
                    List<SlopeSteepBakedModel.CornerDebugQuad> baked = SlopeSteepBakedModel.debugBakedStraightGeometry(
                            upperVariant, xDegrees, yDegrees);
                    assertMeshesEqual(live, baked, upperVariant, false, xDegrees, yDegrees);
                }
            }
        }

        for (boolean upperVariant : new boolean[]{false, true}) {
            for (boolean outer : new boolean[]{false, true}) {
                for (int xDegrees : X_ROTATIONS) {
                    for (int yDegrees : Y_ROTATIONS) {
                        List<SlopeSteepBakedModel.CornerDebugQuad> logical = SlopeSteepBakedModel.debugLogicalCornerGeometry(
                                upperVariant, outer, xDegrees, yDegrees);
                        validateMesh(logical, upperVariant, outer, true, xDegrees, yDegrees);
                        validateWorldAlignedUvs(logical, upperVariant, outer, xDegrees, yDegrees);
                        validateStablePomBounds(logical, upperVariant, outer, xDegrees, yDegrees);
                        validateAxisAlignedScale(logical, upperVariant, outer, xDegrees, yDegrees);
                        validateCornerTopAtlas(logical, upperVariant, outer, xDegrees, yDegrees);
                        validateCornerBottomCap(logical, upperVariant, outer, xDegrees, yDegrees);

                        List<SlopeSteepBakedModel.CornerDebugQuad> live = SlopeSteepBakedModel.debugLiveCornerGeometry(
                                upperVariant, outer, xDegrees, yDegrees);
                        List<SlopeSteepBakedModel.CornerDebugQuad> baked = SlopeSteepBakedModel.debugBakedCornerGeometry(
                                upperVariant, outer, xDegrees, yDegrees);
                        assertMeshesEqual(live, baked, upperVariant, outer, xDegrees, yDegrees);
                    }
                }
            }
        }

        validateAdjacentTopJoins();
    }

    private void validateAdjacentTopJoins() {
        validateUpperLowerJoins();
        validateStraightCornerJoins();
    }

    private void validateUpperLowerJoins() {
        for (int xDegrees : X_ROTATIONS) {
            int upperOffsetY = xDegrees == 0 ? -1 : 1;
            for (int yDegrees : Y_ROTATIONS) {
                List<SlopeSteepBakedModel.CornerDebugQuad> lower =
                        SlopeSteepBakedModel.debugLogicalStraightGeometry(false, xDegrees, yDegrees);
                List<SlopeSteepBakedModel.CornerDebugQuad> upper =
                        SlopeSteepBakedModel.debugLogicalStraightGeometry(true, xDegrees, yDegrees);
                int sharedVertices = sharedTopPhaseCount(
                        lower, 0, 0, 0,
                        upper, 0, upperOffsetY, 0,
                        false, false, xDegrees, yDegrees,
                        true,
                        "upper/lower");
                if (sharedVertices < 2) {
                    fail("upper/lower join exposed fewer than two shared top vertices",
                            false, false, xDegrees, yDegrees);
                }
                checkedTopJoins++;
            }
        }

        for (boolean outer : new boolean[]{false, true}) {
            for (boolean top : new boolean[]{false, true}) {
                int xDegrees = top ? 180 : 0;
                int upperOffsetY = top ? 1 : -1;
                for (boolean right : new boolean[]{false, true}) {
                    for (int facingRotation : Y_ROTATIONS) {
                        int cornerRotation = cornerRotation(facingRotation, top, right);
                        List<SlopeSteepBakedModel.CornerDebugQuad> lower =
                                SlopeSteepBakedModel.debugLogicalCornerGeometry(
                                        false, outer, xDegrees, cornerRotation);
                        List<SlopeSteepBakedModel.CornerDebugQuad> upper =
                                SlopeSteepBakedModel.debugLogicalCornerGeometry(
                                        true, outer, xDegrees, cornerRotation);
                        int sharedVertices = sharedTopPhaseCount(
                                lower, 0, 0, 0,
                                upper, 0, upperOffsetY, 0,
                                false, outer, xDegrees, cornerRotation,
                                true,
                                "upper/lower corner");
                        if (sharedVertices < 2) {
                            fail("upper/lower corner join exposed fewer than two shared top vertices",
                                    false, outer, xDegrees, cornerRotation);
                        }
                        checkedTopJoins++;
                    }
                }
            }
        }
    }

    private void validateStraightCornerJoins() {
        int[][] origins = {
                {0, 0, 0},
                {5, 5, 5},
                {-1, -1, -1}
        };
        for (boolean upperVariant : new boolean[]{false, true}) {
            for (boolean outer : new boolean[]{false, true}) {
                for (boolean top : new boolean[]{false, true}) {
                    int xDegrees = top ? 180 : 0;
                    for (boolean right : new boolean[]{false, true}) {
                        for (int facingRotation : Y_ROTATIONS) {
                            int cornerRotation = cornerRotation(facingRotation, top, right);
                            int turnRotation = Math.floorMod(
                                    facingRotation + (right ? 90 : -90), 360);
                            Direction facing = facingForRotation(facingRotation);
                            Direction turnFacing = right
                                    ? facing.rotateYClockwise()
                                    : facing.rotateYCounterclockwise();
                            Direction throughOffset = outer
                                    ? turnFacing.getOpposite()
                                    : turnFacing;
                            Direction turnOffset = outer
                                    ? facing.getOpposite()
                                    : facing;

                            List<SlopeSteepBakedModel.CornerDebugQuad> corner =
                                    SlopeSteepBakedModel.debugLogicalCornerGeometry(
                                            upperVariant, outer, xDegrees, cornerRotation);
                            List<SlopeSteepBakedModel.CornerDebugQuad> throughStraight =
                                    SlopeSteepBakedModel.debugLogicalStraightGeometry(
                                            upperVariant, xDegrees, facingRotation);
                            List<SlopeSteepBakedModel.CornerDebugQuad> turnStraight =
                                    SlopeSteepBakedModel.debugLogicalStraightGeometry(
                                            upperVariant, xDegrees, turnRotation);

                            for (int[] origin : origins) {
                                validateStraightCornerLeg(
                                        corner, origin,
                                        throughStraight, throughOffset,
                                        upperVariant, outer, xDegrees, cornerRotation,
                                        "through");
                                validateStraightCornerLeg(
                                        corner, origin,
                                        turnStraight, turnOffset,
                                        upperVariant, outer, xDegrees, cornerRotation,
                                        "turn");
                            }
                        }
                    }
                }
            }
        }
    }

    private void validateStraightCornerLeg(
            List<SlopeSteepBakedModel.CornerDebugQuad> corner,
            int[] cornerOrigin,
            List<SlopeSteepBakedModel.CornerDebugQuad> straight,
            Direction straightOffset,
            boolean upperVariant,
            boolean outer,
            int xDegrees,
            int cornerRotation,
            String legName) {
        int sharedVertices = sharedSlopedTopPhaseCount(
                corner, cornerOrigin[0], cornerOrigin[1], cornerOrigin[2],
                straight,
                cornerOrigin[0] + straightOffset.getOffsetX(),
                cornerOrigin[1],
                cornerOrigin[2] + straightOffset.getOffsetZ(),
                upperVariant, outer, xDegrees, cornerRotation,
                legName + " straight/corner");
        if (sharedVertices < 2) {
            fail(legName + " straight/corner join exposed fewer than two matching sloped vertices",
                    upperVariant, outer, xDegrees, cornerRotation);
        }
        checkedTopJoins++;
    }

    private static int cornerRotation(int facingRotation, boolean top, boolean right) {
        if (top && !right) {
            return Math.floorMod(facingRotation - 90, 360);
        }
        if (!top && right) {
            return Math.floorMod(facingRotation + 90, 360);
        }
        return facingRotation;
    }

    private static Direction facingForRotation(int yDegrees) {
        return switch (Math.floorMod(yDegrees, 360)) {
            case 0 -> Direction.EAST;
            case 90 -> Direction.SOUTH;
            case 180 -> Direction.WEST;
            case 270 -> Direction.NORTH;
            default -> throw new IllegalArgumentException("rotation must be a multiple of 90");
        };
    }

    private static int sharedSlopedTopPhaseCount(
            List<SlopeSteepBakedModel.CornerDebugQuad> first,
            int firstX,
            int firstY,
            int firstZ,
            List<SlopeSteepBakedModel.CornerDebugQuad> second,
            int secondX,
            int secondY,
            int secondZ,
            boolean upperVariant,
            boolean outer,
            int xDegrees,
            int yDegrees,
            String joinName) {
        Map<String, AtlasPoint> firstVertices = topAtlasPoints(
                first, firstX, firstY, firstZ,
                upperVariant, outer, xDegrees, yDegrees, joinName, true);
        Map<String, AtlasPoint> secondVertices = topAtlasPoints(
                second, secondX, secondY, secondZ,
                upperVariant, outer, xDegrees, yDegrees, joinName, true);
        return sharedPhaseCount(
                firstVertices, secondVertices,
                upperVariant, outer, xDegrees, yDegrees,
                true, joinName);
    }

    private static int sharedTopPhaseCount(
            List<SlopeSteepBakedModel.CornerDebugQuad> first,
            int firstX,
            int firstY,
            int firstZ,
            List<SlopeSteepBakedModel.CornerDebugQuad> second,
            int secondX,
            int secondY,
            int secondZ,
            boolean upperVariant,
            boolean outer,
            int xDegrees,
            int yDegrees,
            boolean strict,
            String joinName) {
        Map<String, AtlasPoint> firstVertices = topAtlasPoints(first, firstX, firstY, firstZ,
                upperVariant, outer, xDegrees, yDegrees, joinName, false);
        Map<String, AtlasPoint> secondVertices = topAtlasPoints(second, secondX, secondY, secondZ,
                upperVariant, outer, xDegrees, yDegrees, joinName, false);
        return sharedPhaseCount(
                firstVertices, secondVertices,
                upperVariant, outer, xDegrees, yDegrees,
                strict, joinName);
    }

    private static int sharedPhaseCount(
            Map<String, AtlasPoint> firstVertices,
            Map<String, AtlasPoint> secondVertices,
            boolean upperVariant,
            boolean outer,
            int xDegrees,
            int yDegrees,
            boolean strict,
            String joinName) {
        int sharedVertices = 0;
        for (Map.Entry<String, AtlasPoint> entry : firstVertices.entrySet()) {
            AtlasPoint secondPoint = secondVertices.get(entry.getKey());
            if (secondPoint == null) {
                continue;
            }
            AtlasPoint firstPoint = entry.getValue();
            if (!near(firstPoint.s(), secondPoint.s()) || !near(firstPoint.t(), secondPoint.t())) {
                if (strict) {
                    fail(joinName + " CTM phase changed at shared top vertex " + entry.getKey()
                                    + ": " + firstPoint + " != " + secondPoint,
                            upperVariant, outer, xDegrees, yDegrees);
                }
                return 0;
            }
            sharedVertices++;
        }
        return sharedVertices;
    }

    private static Map<String, AtlasPoint> topAtlasPoints(
            List<SlopeSteepBakedModel.CornerDebugQuad> quads,
            int blockX,
            int blockY,
            int blockZ,
            boolean upperVariant,
            boolean outer,
            int xDegrees,
            int yDegrees,
            String joinName,
            boolean slopedOnly) {
        Map<String, AtlasPoint> points = new HashMap<>();
        for (SlopeSteepBakedModel.CornerDebugQuad quad : quads) {
            if (quad.nominalFace() != Direction.UP
                    || (slopedOnly && !sourceHeightVaries(quad))) {
                continue;
            }
            for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++) {
                if (isTextureOnlyTriangleVertex(quad, vertexIndex)) {
                    continue;
                }
                SlopeSteepBakedModel.CornerDebugVertex vertex = quad.vertex(vertexIndex);
                String key = globalPositionKey(vertex, blockX, blockY, blockZ)
                        + "|" + quad.spriteFace();
                AtlasPoint point = atlasPoint(quad.spriteFace(), blockX, blockY, blockZ, vertex);
                AtlasPoint previous = points.putIfAbsent(key, point);
                if (previous != null && (!near(previous.s(), point.s()) || !near(previous.t(), point.t()))) {
                    fail(joinName + " mesh changed CTM phase at duplicate top vertex " + key,
                            upperVariant, outer, xDegrees, yDegrees);
                }
            }
        }
        return points;
    }

    private static AtlasPoint atlasPoint(Direction face,
                                         int blockX,
                                         int blockY,
                                         int blockZ,
                                         SlopeSteepBakedModel.CornerDebugVertex vertex) {
        float cellS = switch (face) {
            case UP, DOWN, NORTH, SOUTH -> face == Direction.NORTH ? -blockX - 1 : blockX;
            case WEST -> blockZ;
            case EAST -> -blockZ - 1;
        };
        float cellT = switch (face) {
            case UP -> blockZ;
            case DOWN -> -blockZ - 1;
            case NORTH, SOUTH, WEST, EAST -> -blockY;
        };
        return new AtlasPoint(cellS + vertex.u() / 16.0F, cellT + vertex.v() / 16.0F);
    }

    private static String globalPositionKey(SlopeSteepBakedModel.CornerDebugVertex vertex,
                                            int blockX,
                                            int blockY,
                                            int blockZ) {
        return quantize(vertex.x() + blockX) + ","
                + quantize(vertex.y() + blockY) + ","
                + quantize(vertex.z() + blockZ);
    }

    private void validateMesh(List<SlopeSteepBakedModel.CornerDebugQuad> quads,
                              boolean upperVariant,
                              boolean outer,
                              boolean requireTopUvContinuity,
                              int xDegrees,
                              int yDegrees) {
        if (quads.isEmpty()) {
            fail("empty corner mesh", upperVariant, outer, xDegrees, yDegrees);
        }

        Map<String, SlopeSteepBakedModel.CornerDebugVertex> sharedTopVertices = new HashMap<>();
        Set<String> polygonKeys = new HashSet<>();
        for (SlopeSteepBakedModel.CornerDebugQuad quad : quads) {
            checkedQuads++;
            assertPositiveAreaAndWinding(quad, upperVariant, outer, xDegrees, yDegrees);

            String polygonKey = polygonKey(quad);
            if (!polygonKeys.add(polygonKey)) {
                fail("duplicate polygon " + polygonKey, upperVariant, outer, xDegrees, yDegrees);
            }

            for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++) {
                SlopeSteepBakedModel.CornerDebugVertex vertex = quad.vertex(vertexIndex);
                assertFiniteAndInRange(vertex, upperVariant, outer, xDegrees, yDegrees);
                if (requireTopUvContinuity
                        && quad.nominalFace() == Direction.UP
                        && !isTextureOnlyTriangleVertex(quad, vertexIndex)) {
                    String positionKey = positionKey(vertex) + "|" + quad.spriteFace();
                    SlopeSteepBakedModel.CornerDebugVertex previous = sharedTopVertices.putIfAbsent(positionKey, vertex);
                    if (previous != null && (!near(previous.u(), vertex.u()) || !near(previous.v(), vertex.v()))) {
                        fail("shared top vertex changed UV at " + positionKey, upperVariant, outer, xDegrees, yDegrees);
                    }
                }
            }
        }
        checkedMeshes++;
    }

    private void validateWorldAlignedUvs(List<SlopeSteepBakedModel.CornerDebugQuad> quads,
                                         boolean upperVariant,
                                         boolean outer,
                                         int xDegrees,
                                         int yDegrees) {
        FixedSlopeRotation transform = FixedSlopeRotation.of(xDegrees, yDegrees);
        for (SlopeSteepBakedModel.CornerDebugQuad quad : quads) {
            Direction expectedProjectionFace = expectedProjectionFace(quad);
            Direction expectedTextureFace = transform.mapFace(expectedProjectionFace);
            Direction expectedSpriteFace = expectedTextureFace;
            if (quad.projectionFace() != expectedProjectionFace
                    || quad.textureFace() != expectedTextureFace
                    || quad.spriteFace() != expectedSpriteFace) {
                fail("texture projection face " + quad.projectionFace() + " -> " + quad.textureFace()
                                + " with sprite " + quad.spriteFace()
                                + " != " + expectedProjectionFace + " -> " + expectedTextureFace
                                + " with sprite " + expectedSpriteFace,
                        upperVariant, outer, xDegrees, yDegrees);
            }
            for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++) {
                if (isTextureOnlyTriangleVertex(quad, vertexIndex)) {
                    continue;
                }
                SlopeSteepBakedModel.CornerDebugVertex vertex = quad.vertex(vertexIndex);
                float worldX = transform.positionX(vertex.sourceX(), vertex.sourceY(), vertex.sourceZ());
                float worldY = transform.positionY(vertex.sourceX(), vertex.sourceY(), vertex.sourceZ());
                float worldZ = transform.positionZ(vertex.sourceX(), vertex.sourceY(), vertex.sourceZ());
                float expectedU = referenceU(quad.textureFace(), worldX, worldY, worldZ);
                float expectedV = referenceV(quad.textureFace(), worldX, worldY, worldZ);
                if (!near(vertex.u(), expectedU) || !near(vertex.v(), expectedV)) {
                    fail("UV does not match its selected CTM face at quad vertex " + vertexIndex,
                            upperVariant, outer, xDegrees, yDegrees);
                }
                checkedUvVertices++;
            }
        }
    }

    private static void validateAxisAlignedScale(List<SlopeSteepBakedModel.CornerDebugQuad> quads,
                                                 boolean upperVariant,
                                                 boolean outer,
                                                 int xDegrees,
                                                 int yDegrees) {
        for (SlopeSteepBakedModel.CornerDebugQuad quad : quads) {
            if (quad.nominalFace() == Direction.UP && sourceHeightVaries(quad)) {
                continue;
            }
            float physicalArea = quadArea(quad);
            float textureArea = uvArea(quad);
            if (!near(physicalArea, textureArea)) {
                fail("axis-aligned face texture scale " + physicalArea + " != " + textureArea,
                        upperVariant, outer, xDegrees, yDegrees);
            }
        }
    }

    /**
     * Iris reconstructs the sampled sprite rectangle from the mean UV and each
     * vertex's distance from it. All four vertices therefore need to describe
     * the same rectangle; trapezoidal UV quads otherwise make POM sample a
     * different region across one primitive.
     */
    private static void validateStablePomBounds(List<SlopeSteepBakedModel.CornerDebugQuad> quads,
                                                boolean upperVariant,
                                                boolean outer,
                                                int xDegrees,
                                                int yDegrees) {
        for (int quadIndex = 0; quadIndex < quads.size(); quadIndex++) {
            SlopeSteepBakedModel.CornerDebugQuad quad = quads.get(quadIndex);
            float midU = 0.0F;
            float midV = 0.0F;
            for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++) {
                midU += quad.vertex(vertexIndex).u();
                midV += quad.vertex(vertexIndex).v();
            }
            midU *= 0.25F;
            midV *= 0.25F;

            float expectedMinU = Float.NaN;
            float expectedMinV = Float.NaN;
            float expectedSizeU = Float.NaN;
            float expectedSizeV = Float.NaN;
            for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++) {
                SlopeSteepBakedModel.CornerDebugVertex vertex = quad.vertex(vertexIndex);
                float deltaU = vertex.u() - midU;
                float deltaV = vertex.v() - midV;
                float minU = Math.min(vertex.u(), midU - deltaU);
                float minV = Math.min(vertex.v(), midV - deltaV);
                float sizeU = Math.abs(deltaU) * 2.0F;
                float sizeV = Math.abs(deltaV) * 2.0F;
                if (vertexIndex == 0) {
                    expectedMinU = minU;
                    expectedMinV = minV;
                    expectedSizeU = sizeU;
                    expectedSizeV = sizeV;
                } else if (!near(minU, expectedMinU)
                        || !near(minV, expectedMinV)
                        || !near(sizeU, expectedSizeU)
                        || !near(sizeV, expectedSizeV)) {
                    fail("POM bounds vary inside quad " + quadIndex + " on " + quad.face()
                                    + ": [" + quad.v0().u() + "," + quad.v0().v() + "]"
                                    + " [" + quad.v1().u() + "," + quad.v1().v() + "]"
                                    + " [" + quad.v2().u() + "," + quad.v2().v() + "]"
                                    + " [" + quad.v3().u() + "," + quad.v3().v() + "]",
                            upperVariant, outer, xDegrees, yDegrees);
                }
            }
        }
    }

    private static void validateStraightSlopeAtlas(List<SlopeSteepBakedModel.CornerDebugQuad> quads,
                                                   boolean upperVariant,
                                                   int xDegrees,
                                                   int yDegrees) {
        List<SlopeSteepBakedModel.CornerDebugQuad> sloped = slopedTopQuads(quads);
        if (sloped.size() != 1) {
            fail("straight slope must have exactly one sloped top quad", upperVariant, false, xDegrees, yDegrees);
        }

        SlopeSteepBakedModel.CornerDebugQuad quad = sloped.get(0);
        float textureArea = uvArea(quad);
        if (!near(textureArea, 1.0F)) {
            fail("straight sloped face must use one vertical CTM cell, not area " + textureArea,
                    upperVariant, false, xDegrees, yDegrees);
        }

        float stretch = quadArea(quad) / textureArea;
        float expectedStretch = (float) Math.sqrt(1.25D);
        if (!near(stretch, expectedStretch)) {
            fail("straight sloped face stretch " + stretch + " != " + expectedStretch,
                    upperVariant, false, xDegrees, yDegrees);
        }
    }

    private static void validateCornerTopAtlas(List<SlopeSteepBakedModel.CornerDebugQuad> quads,
                                               boolean upperVariant,
                                               boolean outer,
                                               int xDegrees,
                                               int yDegrees) {
        List<SlopeSteepBakedModel.CornerDebugQuad> top = new ArrayList<>();
        for (SlopeSteepBakedModel.CornerDebugQuad quad : quads) {
            if (quad.nominalFace() == Direction.UP) {
                top.add(quad);
            }
        }

        int slopedCount = 0;
        int flatCount = 0;
        for (SlopeSteepBakedModel.CornerDebugQuad quad : top) {
            float physicalArea = quadArea(quad);
            float textureArea = uvArea(quad);
            if (sourceHeightVaries(quad)) {
                slopedCount++;
                float stretch = physicalArea / textureArea;
                float expectedStretch = (float) Math.sqrt(1.25D);
                if (!near(stretch, expectedStretch)) {
                    fail("corner sloped face stretch " + stretch + " != " + expectedStretch,
                            upperVariant, outer, xDegrees, yDegrees);
                }
                if (quad.projectionFace().getAxis().isVertical()) {
                    fail("corner sloped face retained a vertical projection",
                            upperVariant, outer, xDegrees, yDegrees);
                }
            } else {
                flatCount++;
                if (!near(physicalArea, textureArea)) {
                    fail("corner flat face texture scale " + physicalArea + " != " + textureArea,
                            upperVariant, outer, xDegrees, yDegrees);
                }
                if (quad.projectionFace() != Direction.UP) {
                    fail("corner flat face changed projection from UP",
                            upperVariant, outer, xDegrees, yDegrees);
                }
            }
        }
        int expectedSloped = upperVariant == outer ? 4 : 2;
        int expectedFlat = upperVariant ? (outer ? 1 : 2) : 0;
        if (slopedCount != expectedSloped || flatCount != expectedFlat) {
            fail("corner top facet counts sloped=" + slopedCount + ", flat=" + flatCount
                            + " != " + expectedSloped + ", " + expectedFlat,
                    upperVariant, outer, xDegrees, yDegrees);
        }
        validateInternalCornerRidgeGeometry(top, upperVariant, outer, xDegrees, yDegrees);
    }

    private static void validateInternalCornerRidgeGeometry(
            List<SlopeSteepBakedModel.CornerDebugQuad> top,
            boolean upperVariant,
            boolean outer,
            int xDegrees,
            int yDegrees) {
        List<SlopeSteepBakedModel.CornerDebugQuad> sloped = slopedTopQuads(top);
        Map<Direction, Set<String>> wingVertices = new HashMap<>();
        for (SlopeSteepBakedModel.CornerDebugQuad quad : sloped) {
            Set<String> positions = wingVertices.computeIfAbsent(
                    quad.projectionFace(), ignored -> new HashSet<>());
            for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++) {
                if (!isTextureOnlyTriangleVertex(quad, vertexIndex)) {
                    positions.add(positionKey(quad.vertex(vertexIndex)));
                }
            }
        }
        if (wingVertices.size() != 2
                || !wingVertices.containsKey(Direction.EAST)
                || !wingVertices.containsKey(Direction.NORTH)) {
            fail("corner must have EAST and NORTH projected slope wings",
                    upperVariant, outer, xDegrees, yDegrees);
        }

        Set<String> firstVertices = new HashSet<>(wingVertices.get(Direction.EAST));
        firstVertices.retainAll(wingVertices.get(Direction.NORTH));
        int sharedVertices = firstVertices.size();
        if (sharedVertices < 2) {
            fail("corner wings exposed fewer than two shared ridge vertices",
                    upperVariant, outer, xDegrees, yDegrees);
        }
    }

    private static List<SlopeSteepBakedModel.CornerDebugQuad> slopedTopQuads(
            List<SlopeSteepBakedModel.CornerDebugQuad> quads) {
        List<SlopeSteepBakedModel.CornerDebugQuad> result = new ArrayList<>();
        for (SlopeSteepBakedModel.CornerDebugQuad quad : quads) {
            if (quad.nominalFace() == Direction.UP && sourceHeightVaries(quad)) {
                result.add(quad);
            }
        }
        return result;
    }

    private static boolean sourceHeightVaries(SlopeSteepBakedModel.CornerDebugQuad quad) {
        float first = quad.v0().sourceY();
        return !near(first, quad.v1().sourceY())
                || !near(first, quad.v2().sourceY())
                || !near(first, quad.v3().sourceY());
    }

    private static Direction expectedProjectionFace(SlopeSteepBakedModel.CornerDebugQuad quad) {
        if (quad.nominalFace() != Direction.UP || !sourceHeightVaries(quad)) {
            return quad.nominalFace();
        }

        Normal normal = sourceNormal(quad.v0(), quad.v1(), quad.v2());
        if (normal.length() <= EPSILON) {
            normal = sourceNormal(quad.v0(), quad.v2(), quad.v3());
        }
        boolean xGradient = Math.abs(normal.x()) >= Math.abs(normal.z());
        return xGradient ? Direction.EAST : Direction.NORTH;
    }

    private static Normal sourceNormal(SlopeSteepBakedModel.CornerDebugVertex a,
                                       SlopeSteepBakedModel.CornerDebugVertex b,
                                       SlopeSteepBakedModel.CornerDebugVertex c) {
        float abX = b.sourceX() - a.sourceX();
        float abY = b.sourceY() - a.sourceY();
        float abZ = b.sourceZ() - a.sourceZ();
        float acX = c.sourceX() - a.sourceX();
        float acY = c.sourceY() - a.sourceY();
        float acZ = c.sourceZ() - a.sourceZ();
        return new Normal(
                abY * acZ - abZ * acY,
                abZ * acX - abX * acZ,
                abX * acY - abY * acX);
    }

    private static float referenceU(Direction face, float x, float y, float z) {
        float textureS = face == Direction.EAST || face == Direction.WEST ? z : x;
        float normalized = face == Direction.NORTH || face == Direction.EAST
                ? 1.0F - textureS
                : textureS;
        return referenceTextureUnits(normalized);
    }

    private static float referenceV(Direction face, float x, float y, float z) {
        float textureT = face == Direction.UP || face == Direction.DOWN ? z : y;
        float normalized = face == Direction.UP ? textureT : 1.0F - textureT;
        return referenceTextureUnits(normalized);
    }

    private static float referenceTextureUnits(float normalized) {
        if (Math.abs(normalized) <= 0.000001F) {
            return 0.0F;
        }
        if (Math.abs(normalized - 1.0F) <= 0.000001F) {
            return 16.0F;
        }
        return normalized * 16.0F;
    }

    private static void validateCornerBottomCap(List<SlopeSteepBakedModel.CornerDebugQuad> quads,
                                                boolean upperVariant,
                                                boolean outer,
                                                int xDegrees,
                                                int yDegrees) {
        int capQuadCount = 0;
        float capArea = 0.0F;
        for (SlopeSteepBakedModel.CornerDebugQuad quad : quads) {
            if (quad.nominalFace() != Direction.DOWN) {
                continue;
            }
            capQuadCount++;
            capArea += triangleArea(quad.v0(), quad.v1(), quad.v2())
                    + triangleArea(quad.v0(), quad.v2(), quad.v3());
        }

        int expectedQuadCount = upperVariant || outer ? 1 : 2;
        float expectedArea = upperVariant ? 1.0F : outer ? 0.25F : 0.75F;
        if (capQuadCount != expectedQuadCount) {
            fail("corner bottom cap quad count " + capQuadCount + " != " + expectedQuadCount,
                    upperVariant, outer, xDegrees, yDegrees);
        }
        if (!near(capArea, expectedArea)) {
            fail("corner bottom cap area " + capArea + " != " + expectedArea,
                    upperVariant, outer, xDegrees, yDegrees);
        }
    }

    private static void assertMeshesEqual(List<SlopeSteepBakedModel.CornerDebugQuad> live,
                                          List<SlopeSteepBakedModel.CornerDebugQuad> baked,
                                          boolean upperVariant,
                                          boolean outer,
                                          int xDegrees,
                                          int yDegrees) {
        if (live.size() != baked.size()) {
            fail("live/baked quad count mismatch", upperVariant, outer, xDegrees, yDegrees);
        }

        for (int quadIndex = 0; quadIndex < live.size(); quadIndex++) {
            SlopeSteepBakedModel.CornerDebugQuad liveQuad = live.get(quadIndex);
            SlopeSteepBakedModel.CornerDebugQuad bakedQuad = baked.get(quadIndex);
            if (liveQuad.face() != bakedQuad.face()
                    || liveQuad.nominalFace() != bakedQuad.nominalFace()
                    || liveQuad.projectionFace() != bakedQuad.projectionFace()
                    || liveQuad.textureFace() != bakedQuad.textureFace()
                    || liveQuad.spriteFace() != bakedQuad.spriteFace()) {
                fail("live/baked face mismatch at quad " + quadIndex, upperVariant, outer, xDegrees, yDegrees);
            }

            for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++) {
                assertVerticesEqual(liveQuad.vertex(vertexIndex), bakedQuad.vertex(vertexIndex),
                        "live/baked vertex mismatch at quad " + quadIndex + " vertex " + vertexIndex,
                        upperVariant, outer, xDegrees, yDegrees);
            }
        }
    }

    private static void assertVerticesEqual(SlopeSteepBakedModel.CornerDebugVertex a,
                                            SlopeSteepBakedModel.CornerDebugVertex b,
                                            String message,
                                            boolean upperVariant,
                                            boolean outer,
                                            int xDegrees,
                                            int yDegrees) {
        if (!near(a.x(), b.x()) || !near(a.y(), b.y()) || !near(a.z(), b.z())
                || !near(a.u(), b.u()) || !near(a.v(), b.v())
                || !near(a.sourceX(), b.sourceX())
                || !near(a.sourceY(), b.sourceY())
                || !near(a.sourceZ(), b.sourceZ())
                || !near(a.authoredU(), b.authoredU())
                || !near(a.authoredV(), b.authoredV())) {
            fail(message, upperVariant, outer, xDegrees, yDegrees);
        }
    }

    private static void assertFiniteAndInRange(SlopeSteepBakedModel.CornerDebugVertex vertex,
                                               boolean upperVariant,
                                               boolean outer,
                                               int xDegrees,
                                               int yDegrees) {
        if (!Float.isFinite(vertex.x()) || !Float.isFinite(vertex.y()) || !Float.isFinite(vertex.z())
                || !Float.isFinite(vertex.u()) || !Float.isFinite(vertex.v())) {
            fail("non-finite vertex", upperVariant, outer, xDegrees, yDegrees);
        }
        if (vertex.u() < -EPSILON || vertex.u() > 16.0F + EPSILON
                || vertex.v() < -EPSILON || vertex.v() > 16.0F + EPSILON) {
            fail("UV outside 0-16 range: " + vertex.u() + ", " + vertex.v(), upperVariant, outer, xDegrees, yDegrees);
        }
    }

    private static void assertPositiveAreaAndWinding(SlopeSteepBakedModel.CornerDebugQuad quad,
                                                     boolean upperVariant,
                                                     boolean outer,
                                                     int xDegrees,
                                                     int yDegrees) {
        Normal normal = normal(quad.v0(), quad.v1(), quad.v2());
        float area = 0.5F * normal.length() + triangleArea(quad.v0(), quad.v2(), quad.v3());
        if (area <= EPSILON) {
            fail("zero-area quad", upperVariant, outer, xDegrees, yDegrees);
        }
        if (normal.length() <= EPSILON) {
            fail("first triangle is degenerate and cannot supply Iris's POM tangent",
                    upperVariant, outer, xDegrees, yDegrees);
        }
        if (triangleArea(quad.v0(), quad.v2(), quad.v3()) <= EPSILON
                && !exactSamePosition(quad.v2(), quad.v3())) {
            fail("triangle is not encoded as exact A-B-C-C for Indium orientation locking",
                    upperVariant, outer, xDegrees, yDegrees);
        }

        float dot = normal.dot(quad.face());
        if (dot <= EPSILON) {
            fail("inward or inconsistent winding for " + quad.face(), upperVariant, outer, xDegrees, yDegrees);
        }
    }

    private static float triangleArea(SlopeSteepBakedModel.CornerDebugVertex a,
                                      SlopeSteepBakedModel.CornerDebugVertex b,
                                      SlopeSteepBakedModel.CornerDebugVertex c) {
        return 0.5F * normal(a, b, c).length();
    }

    private static float quadArea(SlopeSteepBakedModel.CornerDebugQuad quad) {
        return triangleArea(quad.v0(), quad.v1(), quad.v2())
                + triangleArea(quad.v0(), quad.v2(), quad.v3());
    }

    private static float uvArea(SlopeSteepBakedModel.CornerDebugQuad quad) {
        float area = 0.0F;
        if (triangleArea(quad.v0(), quad.v1(), quad.v2()) > EPSILON) {
            area += uvTriangleArea(quad.v0(), quad.v1(), quad.v2());
        }
        if (triangleArea(quad.v0(), quad.v2(), quad.v3()) > EPSILON) {
            area += uvTriangleArea(quad.v0(), quad.v2(), quad.v3());
        }
        return area;
    }

    private static boolean isTextureOnlyTriangleVertex(SlopeSteepBakedModel.CornerDebugQuad quad,
                                                       int vertexIndex) {
        boolean firstDegenerate = triangleArea(quad.v0(), quad.v1(), quad.v2()) <= EPSILON;
        boolean secondDegenerate = triangleArea(quad.v0(), quad.v2(), quad.v3()) <= EPSILON;
        if (firstDegenerate && !secondDegenerate) {
            return vertexIndex == 1;
        }
        if (secondDegenerate && !firstDegenerate) {
            return vertexIndex == 3;
        }
        return false;
    }

    private static boolean exactSamePosition(SlopeSteepBakedModel.CornerDebugVertex first,
                                             SlopeSteepBakedModel.CornerDebugVertex second) {
        return Float.floatToRawIntBits(first.x()) == Float.floatToRawIntBits(second.x())
                && Float.floatToRawIntBits(first.y()) == Float.floatToRawIntBits(second.y())
                && Float.floatToRawIntBits(first.z()) == Float.floatToRawIntBits(second.z());
    }

    private static float uvTriangleArea(SlopeSteepBakedModel.CornerDebugVertex a,
                                        SlopeSteepBakedModel.CornerDebugVertex b,
                                        SlopeSteepBakedModel.CornerDebugVertex c) {
        float abU = (b.u() - a.u()) / 16.0F;
        float abV = (b.v() - a.v()) / 16.0F;
        float acU = (c.u() - a.u()) / 16.0F;
        float acV = (c.v() - a.v()) / 16.0F;
        return 0.5F * Math.abs(abU * acV - abV * acU);
    }

    private static Normal normal(SlopeSteepBakedModel.CornerDebugVertex a,
                                 SlopeSteepBakedModel.CornerDebugVertex b,
                                 SlopeSteepBakedModel.CornerDebugVertex c) {
        float abX = b.x() - a.x();
        float abY = b.y() - a.y();
        float abZ = b.z() - a.z();
        float acX = c.x() - a.x();
        float acY = c.y() - a.y();
        float acZ = c.z() - a.z();
        float crossX = abY * acZ - abZ * acY;
        float crossY = abZ * acX - abX * acZ;
        float crossZ = abX * acY - abY * acX;
        return new Normal(crossX, crossY, crossZ);
    }

    private static String polygonKey(SlopeSteepBakedModel.CornerDebugQuad quad) {
        List<String> vertices = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String key = positionKey(quad.vertex(i));
            if (!vertices.contains(key)) {
                vertices.add(key);
            }
        }
        vertices.sort(Comparator.naturalOrder());
        return quad.face() + "|" + String.join(";", vertices);
    }

    private static String positionKey(SlopeSteepBakedModel.CornerDebugVertex vertex) {
        return quantize(vertex.x()) + "," + quantize(vertex.y()) + "," + quantize(vertex.z());
    }

    private static long quantize(float value) {
        return Math.round(value / EPSILON);
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) <= EPSILON;
    }

    private static void fail(String message, boolean upperVariant, boolean outer, int xDegrees, int yDegrees) {
        throw new IllegalStateException(message
                + " [variant=" + (upperVariant ? "upper" : "lower")
                + ", corner=" + (outer ? "outer" : "inner")
                + ", x=" + xDegrees
                + ", y=" + yDegrees + "]");
    }

    private record AtlasPoint(float s, float t) {
    }

    private record Normal(float x, float y, float z) {
        float length() {
            return (float) Math.sqrt(x * x + y * y + z * z);
        }

        float dot(Direction face) {
            return switch (face) {
                case DOWN -> -y;
                case UP -> y;
                case NORTH -> -z;
                case SOUTH -> z;
                case WEST -> -x;
                case EAST -> x;
            };
        }
    }
}
