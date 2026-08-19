package com.oliver.erydon.client.model;

import com.oliver.erydon.block.ArchGothicBlock;
import com.oliver.erydon.block.ArchModernBlock;
import com.oliver.erydon.block.ArchRomanesqueBlock;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.fabricmc.fabric.api.renderer.v1.model.ModelHelper;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Rebuilds an assembled arch in world-aligned repeat-CTM cells before the final
 * Synapheia pass. This keeps one texture phase across rotated
 * component boundaries while preserving atlas-safe cell splits.
 */
public final class ArchRepeatCtmRenderer extends ForwardingBakedModel {
    private static final float POM_EPSILON = 0.00001F;
    private static final int MAX_POM_BAND_SUBDIVISIONS = 1024;
    private static final Object MATERIAL_LOCK = new Object();
    private static final Object GEOMETRY_LOCK = new Object();
    private static final Map<StateKey, List<QuadTemplate>> GEOMETRY_BY_STATE =
            new ConcurrentHashMap<>();

    private static volatile RenderMaterial standardMaterial;
    private static volatile RenderMaterial noAmbientOcclusionMaterial;

    private final String ctmSet;
    private final Family family;

    public ArchRepeatCtmRenderer(BakedModel wrapped, String ctmSet, Family family) {
        this.wrapped = wrapped;
        this.ctmSet = ctmSet;
        this.family = family;
    }

    @Override
    public boolean isVanillaAdapter() {
        return false;
    }

    @Override
    @SuppressWarnings("removal")
    public void emitBlockQuads(BlockRenderView view,
                               BlockState state,
                               BlockPos pos,
                               Supplier<Random> randomSupplier,
                               RenderContext context) {
        if (!family.matches(state)
                || !state.contains(ArchRomanesqueBlock.ARRANGEMENT)
                || !state.contains(ArchRomanesqueBlock.FACING)
                || ctmSet == null
                || !ensureMaterials()) {
            wrapped.emitBlockQuads(view, state, pos, randomSupplier, context);
            return;
        }

        ErydonCtmService ctmService = ErydonCtmService.get(null);
        StateKey key = new StateKey(
                family,
                state.get(ArchRomanesqueBlock.ARRANGEMENT),
                state.get(ArchRomanesqueBlock.FACING)
        );
        List<QuadTemplate> templates = geometryForState(
                key, state, randomSupplier, context.getEmitter());
        Sprite[] repeatSprites = ctmService.repeatSprites(ctmSet);
        QuadEmitter emitter = context.getEmitter();

        for (QuadTemplate template : templates) {
            if (!context.hasTransform()
                    && template.cullFace != null
                    && context.isFaceCulled(template.cullFace)) {
                continue;
            }

            int tileIndex = ErydonCtmService.repeatTileIndex(
                    pos.getX() + template.offsetX,
                    pos.getY() + template.offsetY,
                    pos.getZ() + template.offsetZ,
                    template.lightFace
            );
            emitTemplate(emitter, template, repeatSprites[tileIndex]);
        }
    }

    static void clearGeometryCache() {
        GEOMETRY_BY_STATE.clear();
    }

    private List<QuadTemplate> geometryForState(StateKey key,
                                                BlockState state,
                                                Supplier<Random> randomSupplier,
                                                QuadEmitter decoder) {
        List<QuadTemplate> cached = GEOMETRY_BY_STATE.get(key);
        if (cached != null) {
            return cached;
        }

        synchronized (GEOMETRY_LOCK) {
            cached = GEOMETRY_BY_STATE.get(key);
            if (cached == null) {
                cached = buildGeometry(state, randomSupplier, decoder);
                GEOMETRY_BY_STATE.put(key, cached);
            }
            return cached;
        }
    }

    private List<QuadTemplate> buildGeometry(BlockState state,
                                             Supplier<Random> randomSupplier,
                                             QuadEmitter decoder) {
        RenderMaterial defaultMaterial = wrapped.useAmbientOcclusion()
                ? standardMaterial
                : noAmbientOcclusionMaterial;
        List<QuadTemplate> result = new ArrayList<>();

        for (int faceIndex = 0; faceIndex <= ModelHelper.NULL_FACE_ID; faceIndex++) {
            Direction sourceCullFace = ModelHelper.faceFromIndex(faceIndex);
            List<BakedQuad> quads = wrapped.getQuads(state, sourceCullFace, randomSupplier.get());
            for (BakedQuad quad : quads) {
                decoder.fromVanilla(quad, defaultMaterial, sourceCullFace);
                Direction lightFace = decoder.lightFace();
                if (lightFace == null) {
                    continue;
                }

                List<SpiralStairCtmGeometry.Vertex> vertices = new ArrayList<>(4);
                for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++) {
                    boolean hasNormal = decoder.hasNormal(vertexIndex);
                    vertices.add(new SpiralStairCtmGeometry.Vertex(
                            decoder.x(vertexIndex),
                            decoder.y(vertexIndex),
                            decoder.z(vertexIndex),
                            decoder.color(vertexIndex),
                            decoder.lightmap(vertexIndex),
                            hasNormal,
                            hasNormal ? decoder.normalX(vertexIndex) : 0.0F,
                            hasNormal ? decoder.normalY(vertexIndex) : 0.0F,
                            hasNormal ? decoder.normalZ(vertexIndex) : 0.0F
                    ));
                }

                SourceProperties properties = new SourceProperties(
                        lightFace,
                        decoder.nominalFace(),
                        sourceCullFace,
                        decoder.material(),
                        decoder.colorIndex(),
                        decoder.tag()
                );
                for (SpiralStairCtmGeometry.Fragment fragment
                        : SpiralStairCtmGeometry.split(lightFace, vertices)) {
                    appendFragment(result, properties, fragment, family == Family.GOTHIC);
                }
            }
        }
        return List.copyOf(result);
    }

    private static void appendFragment(List<QuadTemplate> output,
                                       SourceProperties properties,
                                       SpiralStairCtmGeometry.Fragment fragment,
                                       boolean tessellateForPom) {
        List<SpiralStairCtmGeometry.CellVertex> vertices = fragment.vertices();
        int offsetX = SpiralStairCtmGeometry.offsetX(properties.lightFace, fragment);
        int offsetY = SpiralStairCtmGeometry.offsetY(properties.lightFace, fragment);
        int offsetZ = SpiralStairCtmGeometry.offsetZ(properties.lightFace, fragment);
        Direction cullFace = offsetX == 0 && offsetY == 0 && offsetZ == 0
                ? properties.cullFace
                : null;

        if (!tessellateForPom) {
            if (vertices.size() == 3) {
                appendTriangle(output, properties, cullFace, offsetX, offsetY, offsetZ,
                        vertices.get(0), vertices.get(1), vertices.get(2));
                return;
            }
            if (vertices.size() == 4) {
                output.add(template(properties, cullFace, offsetX, offsetY, offsetZ,
                        vertices.get(0), vertices.get(1), vertices.get(2), vertices.get(3)));
                return;
            }
            SpiralStairCtmGeometry.CellVertex first = vertices.get(0);
            for (int index = 1; index < vertices.size() - 1; index++) {
                appendTriangle(output, properties, cullFace, offsetX, offsetY, offsetZ,
                        first, vertices.get(index), vertices.get(index + 1));
            }
            return;
        }

        for (List<SpiralStairCtmGeometry.CellVertex> primitive : pomSafePrimitives(vertices)) {
            if (primitive.size() == 3) {
                appendTriangle(output, properties, cullFace, offsetX, offsetY, offsetZ,
                        primitive.get(0), primitive.get(1), primitive.get(2));
            } else {
                output.add(template(properties, cullFace, offsetX, offsetY, offsetZ,
                        primitive.get(0), primitive.get(1), primitive.get(2), primitive.get(3)));
            }
        }
    }

    /**
     * Complementary derives POM atlas bounds from the average UV and each
     * vertex's distance from it. World projection turns many rotated Gothic
     * faces into UV parallelograms, so those bounds change across one quad and
     * pull the texture into long wedges. Partition only the unsafe polygons
     * into UV-axis-aligned rectangles and right triangles. Their geometry and
     * world texture coordinates remain unchanged, while every emitted quad
     * describes one stable POM rectangle.
     */
    static List<List<SpiralStairCtmGeometry.CellVertex>> pomSafePrimitives(
            List<SpiralStairCtmGeometry.CellVertex> source) {
        if (source.size() < 3) {
            return List.of();
        }
        if ((source.size() == 3 || source.size() == 4) && hasStablePomBounds(source)) {
            return List.of(List.copyOf(source));
        }

        boolean clockwise = signedArea(source) < 0.0D;
        List<Float> levels = source.stream()
                .map(SpiralStairCtmGeometry.CellVertex::localT)
                .sorted(Comparator.naturalOrder())
                .toList();
        List<Float> uniqueLevels = new ArrayList<>(levels.size());
        for (float level : levels) {
            if (uniqueLevels.isEmpty()
                    || Math.abs(level - uniqueLevels.get(uniqueLevels.size() - 1)) > POM_EPSILON) {
                uniqueLevels.add(level);
            }
        }

        List<List<SpiralStairCtmGeometry.CellVertex>> result = new ArrayList<>();
        for (int levelIndex = 0; levelIndex < uniqueLevels.size() - 1; levelIndex++) {
            float lowerT = uniqueLevels.get(levelIndex);
            float upperT = uniqueLevels.get(levelIndex + 1);
            if (upperT - lowerT <= POM_EPSILON) {
                continue;
            }

            PomInterval lower = intervalAt(source, lowerT);
            PomInterval upper = intervalAt(source, upperT);
            int subdivisions = pomBandSubdivisions(lower, upper);
            for (int bandIndex = 0; bandIndex < subdivisions; bandIndex++) {
                float lowerAmount = bandIndex / (float) subdivisions;
                float upperAmount = (bandIndex + 1) / (float) subdivisions;
                float bandLowerT = lerp(lowerT, upperT, lowerAmount);
                float bandUpperT = lerp(lowerT, upperT, upperAmount);
                PomInterval bandLower = PomInterval.interpolate(lower, upper, lowerAmount);
                PomInterval bandUpper = PomInterval.interpolate(lower, upper, upperAmount);
                appendPomBand(result, source, bandLower, bandUpper,
                        bandLowerT, bandUpperT, clockwise);
            }
        }
        return List.copyOf(result);
    }

    private static int pomBandSubdivisions(PomInterval lower, PomInterval upper) {
        int subdivisions = 1;
        while (subdivisions < MAX_POM_BAND_SUBDIVISIONS) {
            boolean overlaps = true;
            PomInterval previous = lower;
            for (int index = 1; index <= subdivisions; index++) {
                PomInterval current = PomInterval.interpolate(
                        lower, upper, index / (float) subdivisions);
                if (!previous.overlaps(current)) {
                    overlaps = false;
                    break;
                }
                previous = current;
            }
            if (overlaps) {
                return subdivisions;
            }
            subdivisions *= 2;
        }
        return MAX_POM_BAND_SUBDIVISIONS;
    }

    private static void appendPomBand(
            List<List<SpiralStairCtmGeometry.CellVertex>> output,
            List<SpiralStairCtmGeometry.CellVertex> source,
            PomInterval lower,
            PomInterval upper,
            float lowerT,
            float upperT,
            boolean clockwise) {
        float innerLeft = Math.max(lower.minimum, upper.minimum);
        float innerRight = Math.min(lower.maximum, upper.maximum);

        if (innerRight - innerLeft > POM_EPSILON) {
            appendPomPrimitive(output, source, clockwise,
                    new PomPoint(innerLeft, lowerT),
                    new PomPoint(innerRight, lowerT),
                    new PomPoint(innerRight, upperT),
                    new PomPoint(innerLeft, upperT));
        }

        if (lower.minimum + POM_EPSILON < upper.minimum) {
            appendPomPrimitive(output, source, clockwise,
                    new PomPoint(lower.minimum, lowerT),
                    new PomPoint(innerLeft, lowerT),
                    new PomPoint(innerLeft, upperT));
        } else if (upper.minimum + POM_EPSILON < lower.minimum) {
            appendPomPrimitive(output, source, clockwise,
                    new PomPoint(upper.minimum, upperT),
                    new PomPoint(innerLeft, lowerT),
                    new PomPoint(innerLeft, upperT));
        }

        if (lower.maximum > upper.maximum + POM_EPSILON) {
            appendPomPrimitive(output, source, clockwise,
                    new PomPoint(innerRight, lowerT),
                    new PomPoint(lower.maximum, lowerT),
                    new PomPoint(innerRight, upperT));
        } else if (upper.maximum > lower.maximum + POM_EPSILON) {
            appendPomPrimitive(output, source, clockwise,
                    new PomPoint(innerRight, lowerT),
                    new PomPoint(upper.maximum, upperT),
                    new PomPoint(innerRight, upperT));
        }
    }

    private static void appendPomPrimitive(
            List<List<SpiralStairCtmGeometry.CellVertex>> output,
            List<SpiralStairCtmGeometry.CellVertex> source,
            boolean clockwise,
            PomPoint... points) {
        List<SpiralStairCtmGeometry.CellVertex> primitive = new ArrayList<>(points.length);
        for (PomPoint point : points) {
            primitive.add(interpolateCellVertex(source, point.s, point.t));
        }
        if (Math.abs(signedArea(primitive)) <= POM_EPSILON * POM_EPSILON) {
            return;
        }
        if (clockwise) {
            java.util.Collections.reverse(primitive);
        }
        output.add(List.copyOf(primitive));
    }

    private static PomInterval intervalAt(List<SpiralStairCtmGeometry.CellVertex> source,
                                          float targetT) {
        float minimum = Float.POSITIVE_INFINITY;
        float maximum = Float.NEGATIVE_INFINITY;
        for (int index = 0; index < source.size(); index++) {
            SpiralStairCtmGeometry.CellVertex first = source.get(index);
            SpiralStairCtmGeometry.CellVertex second = source.get((index + 1) % source.size());
            if (Math.abs(first.localT() - targetT) <= POM_EPSILON) {
                minimum = Math.min(minimum, first.localS());
                maximum = Math.max(maximum, first.localS());
            }
            float deltaT = second.localT() - first.localT();
            if (Math.abs(deltaT) <= POM_EPSILON) {
                continue;
            }
            float amount = (targetT - first.localT()) / deltaT;
            if (amount >= -POM_EPSILON && amount <= 1.0F + POM_EPSILON) {
                float s = lerp(first.localS(), second.localS(),
                        Math.max(0.0F, Math.min(1.0F, amount)));
                minimum = Math.min(minimum, s);
                maximum = Math.max(maximum, s);
            }
        }
        if (!Float.isFinite(minimum) || !Float.isFinite(maximum)) {
            throw new IllegalArgumentException("POM band does not intersect source polygon at T=" + targetT);
        }
        return new PomInterval(minimum, maximum);
    }

    private static SpiralStairCtmGeometry.CellVertex interpolateCellVertex(
            List<SpiralStairCtmGeometry.CellVertex> source,
            float targetS,
            float targetT) {
        for (SpiralStairCtmGeometry.CellVertex vertex : source) {
            if (Math.abs(vertex.localS() - targetS) <= POM_EPSILON
                    && Math.abs(vertex.localT() - targetT) <= POM_EPSILON) {
                return vertex;
            }
        }

        Barycentric best = null;
        for (int index = 1; index < source.size() - 1; index++) {
            Barycentric candidate = Barycentric.at(
                    source.get(0), source.get(index), source.get(index + 1), targetS, targetT);
            if (candidate != null && (best == null || candidate.minimumWeight > best.minimumWeight)) {
                best = candidate;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("Cannot interpolate degenerate POM polygon.");
        }
        return new SpiralStairCtmGeometry.CellVertex(
                weightedVertex(best), targetS, targetT);
    }

    private static SpiralStairCtmGeometry.Vertex weightedVertex(Barycentric weights) {
        SpiralStairCtmGeometry.Vertex first = weights.first.vertex();
        SpiralStairCtmGeometry.Vertex second = weights.second.vertex();
        SpiralStairCtmGeometry.Vertex third = weights.third.vertex();
        float firstWeight = weights.firstWeight;
        float secondWeight = weights.secondWeight;
        float thirdWeight = weights.thirdWeight;
        boolean hasNormal = first.hasNormal() && second.hasNormal() && third.hasNormal();
        float normalX = hasNormal
                ? weighted(first.normalX(), second.normalX(), third.normalX(), weights) : 0.0F;
        float normalY = hasNormal
                ? weighted(first.normalY(), second.normalY(), third.normalY(), weights) : 0.0F;
        float normalZ = hasNormal
                ? weighted(first.normalZ(), second.normalZ(), third.normalZ(), weights) : 0.0F;
        if (hasNormal) {
            float lengthSquared = normalX * normalX + normalY * normalY + normalZ * normalZ;
            if (lengthSquared > 0.0000001F) {
                float inverseLength = 1.0F / (float) Math.sqrt(lengthSquared);
                normalX *= inverseLength;
                normalY *= inverseLength;
                normalZ *= inverseLength;
            }
        }
        return new SpiralStairCtmGeometry.Vertex(
                first.x() * firstWeight + second.x() * secondWeight + third.x() * thirdWeight,
                first.y() * firstWeight + second.y() * secondWeight + third.y() * thirdWeight,
                first.z() * firstWeight + second.z() * secondWeight + third.z() * thirdWeight,
                weightedPackedBytes(first.color(), second.color(), third.color(), weights),
                weightedLightmap(first.lightmap(), second.lightmap(), third.lightmap(), weights),
                hasNormal,
                normalX,
                normalY,
                normalZ
        );
    }

    private static float weighted(float first, float second, float third, Barycentric weights) {
        return first * weights.firstWeight
                + second * weights.secondWeight
                + third * weights.thirdWeight;
    }

    private static int weightedPackedBytes(int first, int second, int third, Barycentric weights) {
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int value = Math.max(0, Math.min(255, Math.round(weighted(
                    (first >>> shift) & 0xFF,
                    (second >>> shift) & 0xFF,
                    (third >>> shift) & 0xFF,
                    weights))));
            result |= value << shift;
        }
        return result;
    }

    private static int weightedLightmap(int first, int second, int third, Barycentric weights) {
        int block = Math.max(0, Math.min(0xFFFF, Math.round(weighted(
                first & 0xFFFF, second & 0xFFFF, third & 0xFFFF, weights))));
        int sky = Math.max(0, Math.min(0xFFFF, Math.round(weighted(
                (first >>> 16) & 0xFFFF,
                (second >>> 16) & 0xFFFF,
                (third >>> 16) & 0xFFFF,
                weights))));
        return sky << 16 | block;
    }

    static boolean hasStablePomBounds(List<SpiralStairCtmGeometry.CellVertex> source) {
        if (source.size() != 3 && source.size() != 4) {
            return false;
        }
        List<SpiralStairCtmGeometry.CellVertex> vertices = new ArrayList<>(source);
        if (vertices.size() == 3) {
            vertices.add(pomSafeTriangleGhost(vertices.get(0), vertices.get(1), vertices.get(2)));
        }
        float middleS = 0.0F;
        float middleT = 0.0F;
        for (SpiralStairCtmGeometry.CellVertex vertex : vertices) {
            middleS += vertex.localS();
            middleT += vertex.localT();
        }
        middleS *= 0.25F;
        middleT *= 0.25F;

        float expectedMinimumS = Float.NaN;
        float expectedMinimumT = Float.NaN;
        float expectedMaximumS = Float.NaN;
        float expectedMaximumT = Float.NaN;
        for (int index = 0; index < vertices.size(); index++) {
            SpiralStairCtmGeometry.CellVertex vertex = vertices.get(index);
            float radiusS = Math.abs(vertex.localS() - middleS);
            float radiusT = Math.abs(vertex.localT() - middleT);
            float minimumS = middleS - radiusS;
            float minimumT = middleT - radiusT;
            float maximumS = middleS + radiusS;
            float maximumT = middleT + radiusT;
            if (index == 0) {
                expectedMinimumS = minimumS;
                expectedMinimumT = minimumT;
                expectedMaximumS = maximumS;
                expectedMaximumT = maximumT;
            } else if (Math.abs(minimumS - expectedMinimumS) > POM_EPSILON
                    || Math.abs(minimumT - expectedMinimumT) > POM_EPSILON
                    || Math.abs(maximumS - expectedMaximumS) > POM_EPSILON
                    || Math.abs(maximumT - expectedMaximumT) > POM_EPSILON) {
                return false;
            }
        }
        return true;
    }

    private static double signedArea(List<SpiralStairCtmGeometry.CellVertex> vertices) {
        double twiceArea = 0.0D;
        for (int index = 0; index < vertices.size(); index++) {
            SpiralStairCtmGeometry.CellVertex current = vertices.get(index);
            SpiralStairCtmGeometry.CellVertex next = vertices.get((index + 1) % vertices.size());
            twiceArea += (double) current.localS() * next.localT()
                    - (double) next.localS() * current.localT();
        }
        return twiceArea * 0.5D;
    }

    private static float lerp(float first, float second, float amount) {
        return first + (second - first) * amount;
    }

    private static void appendTriangle(List<QuadTemplate> output,
                                       SourceProperties properties,
                                       Direction cullFace,
                                       int offsetX,
                                       int offsetY,
                                       int offsetZ,
                                       SpiralStairCtmGeometry.CellVertex first,
                                       SpiralStairCtmGeometry.CellVertex second,
                                       SpiralStairCtmGeometry.CellVertex third) {
        output.add(template(properties, cullFace, offsetX, offsetY, offsetZ,
                first, second, third, pomSafeTriangleGhost(first, second, third)));
    }

    /**
     * Iris derives POM sprite bounds from all four UVs. A triangle still needs
     * a duplicated final position, but duplicating that vertex's UV biases the
     * bounds and can pull height-mapped textures into long wedges. Give the
     * invisible fourth vertex the missing bounding-box UV instead; geometry
     * remains triangular and the three visible UVs remain unchanged.
     */
    static SpiralStairCtmGeometry.CellVertex pomSafeTriangleGhost(
            SpiralStairCtmGeometry.CellVertex first,
            SpiralStairCtmGeometry.CellVertex second,
            SpiralStairCtmGeometry.CellVertex third) {
        float ghostS = missingBoundsCoordinate(first.localS(), second.localS(), third.localS());
        float ghostT = missingBoundsCoordinate(first.localT(), second.localT(), third.localT());
        return new SpiralStairCtmGeometry.CellVertex(third.vertex(), ghostS, ghostT);
    }

    private static float missingBoundsCoordinate(float first, float second, float third) {
        float minimum = Math.min(first, Math.min(second, third));
        float maximum = Math.max(first, Math.max(second, third));
        return 2.0F * (minimum + maximum) - first - second - third;
    }

    private static QuadTemplate template(SourceProperties properties,
                                         Direction cullFace,
                                         int offsetX,
                                         int offsetY,
                                         int offsetZ,
                                         SpiralStairCtmGeometry.CellVertex first,
                                         SpiralStairCtmGeometry.CellVertex second,
                                         SpiralStairCtmGeometry.CellVertex third,
                                         SpiralStairCtmGeometry.CellVertex fourth) {
        return new QuadTemplate(
                properties.lightFace,
                properties.nominalFace,
                cullFace,
                properties.material,
                properties.colorIndex,
                properties.tag,
                offsetX,
                offsetY,
                offsetZ,
                new SpiralStairCtmGeometry.CellVertex[]{first, second, third, fourth}
        );
    }

    private static void emitTemplate(QuadEmitter emitter, QuadTemplate template, Sprite sprite) {
        emitter.material(template.material);
        emitter.colorIndex(template.colorIndex);
        emitter.tag(template.tag);
        emitter.cullFace(template.cullFace);
        emitter.nominalFace(template.nominalFace == null ? template.lightFace : template.nominalFace);

        for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++) {
            SpiralStairCtmGeometry.CellVertex cellVertex = template.vertices[vertexIndex];
            SpiralStairCtmGeometry.Vertex vertex = cellVertex.vertex();
            emitter.pos(vertexIndex, vertex.x(), vertex.y(), vertex.z());
            emitter.uv(
                    vertexIndex,
                    SpiralStairCtmGeometry.u(template.lightFace, cellVertex),
                    SpiralStairCtmGeometry.v(template.lightFace, cellVertex)
            );
            emitter.color(vertexIndex, vertex.color());
            emitter.lightmap(vertexIndex, vertex.lightmap());
            if (vertex.hasNormal()) {
                emitter.normal(vertexIndex, vertex.normalX(), vertex.normalY(), vertex.normalZ());
            }
        }
        emitter.spriteBake(sprite, MutableQuadView.BAKE_NORMALIZED);
        emitter.emit();
    }

    private static boolean ensureMaterials() {
        if (standardMaterial != null && noAmbientOcclusionMaterial != null) {
            return true;
        }
        Renderer renderer = RendererAccess.INSTANCE.getRenderer();
        if (renderer == null) {
            return false;
        }

        synchronized (MATERIAL_LOCK) {
            if (standardMaterial == null || noAmbientOcclusionMaterial == null) {
                standardMaterial = renderer.materialFinder().clear().find();
                noAmbientOcclusionMaterial = renderer.materialFinder()
                        .clear()
                        .ambientOcclusion(TriState.FALSE)
                        .find();
            }
        }
        return true;
    }

    public enum Family {
        MODERN,
        GOTHIC;

        private boolean matches(BlockState state) {
            return switch (this) {
                case MODERN -> state.getBlock() instanceof ArchModernBlock
                        && !(state.getBlock() instanceof ArchGothicBlock);
                case GOTHIC -> state.getBlock() instanceof ArchGothicBlock;
            };
        }
    }

    private record PomPoint(float s, float t) {
    }

    private record PomInterval(float minimum, float maximum) {
        private static PomInterval interpolate(PomInterval first,
                                               PomInterval second,
                                               float amount) {
            return new PomInterval(
                    lerp(first.minimum, second.minimum, amount),
                    lerp(first.maximum, second.maximum, amount)
            );
        }

        private boolean overlaps(PomInterval other) {
            return Math.max(minimum, other.minimum)
                    <= Math.min(maximum, other.maximum) + POM_EPSILON;
        }
    }

    private record Barycentric(SpiralStairCtmGeometry.CellVertex first,
                               SpiralStairCtmGeometry.CellVertex second,
                               SpiralStairCtmGeometry.CellVertex third,
                               float firstWeight,
                               float secondWeight,
                               float thirdWeight,
                               float minimumWeight) {
        private static Barycentric at(SpiralStairCtmGeometry.CellVertex first,
                                      SpiralStairCtmGeometry.CellVertex second,
                                      SpiralStairCtmGeometry.CellVertex third,
                                      float s,
                                      float t) {
            float denominator = (second.localT() - third.localT())
                    * (first.localS() - third.localS())
                    + (third.localS() - second.localS())
                    * (first.localT() - third.localT());
            if (Math.abs(denominator) <= POM_EPSILON * POM_EPSILON) {
                return null;
            }
            float firstWeight = ((second.localT() - third.localT())
                    * (s - third.localS())
                    + (third.localS() - second.localS())
                    * (t - third.localT())) / denominator;
            float secondWeight = ((third.localT() - first.localT())
                    * (s - third.localS())
                    + (first.localS() - third.localS())
                    * (t - third.localT())) / denominator;
            float thirdWeight = 1.0F - firstWeight - secondWeight;
            return new Barycentric(
                    first,
                    second,
                    third,
                    firstWeight,
                    secondWeight,
                    thirdWeight,
                    Math.min(firstWeight, Math.min(secondWeight, thirdWeight))
            );
        }
    }

    private record StateKey(Family family,
                            ArchRomanesqueBlock.Arrangement arrangement,
                            Direction facing) {
    }

    private record SourceProperties(Direction lightFace,
                                    Direction nominalFace,
                                    Direction cullFace,
                                    RenderMaterial material,
                                    int colorIndex,
                                    int tag) {
    }

    private record QuadTemplate(Direction lightFace,
                                Direction nominalFace,
                                Direction cullFace,
                                RenderMaterial material,
                                int colorIndex,
                                int tag,
                                int offsetX,
                                int offsetY,
                                int offsetZ,
                                SpiralStairCtmGeometry.CellVertex[] vertices) {
    }
}
