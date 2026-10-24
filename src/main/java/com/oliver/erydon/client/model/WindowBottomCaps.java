package com.oliver.erydon.client.model;

import com.oliver.erydon.block.WindowArchBlock;
import com.oliver.erydon.block.WindowFrenchGeorgianBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BakedQuadFactory;
import net.minecraft.client.render.model.ModelBakeSettings;
import net.minecraft.client.render.model.json.JsonUnbakedModel;
import net.minecraft.client.render.model.json.ModelElement;
import net.minecraft.client.render.model.json.ModelElementFace;
import net.minecraft.client.render.model.json.ModelElementTexture;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** Reload-local additions for the otherwise exposed bottoms of windows without sills. */
public final class WindowBottomCaps {
    private static final double EPS = 0.000001;
    private static final double AREA_EPS = 0.000000000001;

    private WindowBottomCaps() { }

    public static BakedModel wrap(BakedModel original, JsonUnbakedModel source,
                                  Function<SpriteIdentifier, Sprite> textures,
                                  ModelBakeSettings settings, Identifier id) {
        return wrap(original, source, textures, settings, id, null);
    }

    public static BakedModel wrap(BakedModel original, JsonUnbakedModel source,
                                  Function<SpriteIdentifier, Sprite> textures,
                                  ModelBakeSettings settings, Identifier id, JsonUnbakedModel siblingWall) {
        if (!"erydon".equals(id.getNamespace())) return original;
        String path = id.getPath();
        if (path.endsWith("_aged")) path = path.substring(0, path.length() - 5);
        boolean arch = path.startsWith("block/window/arch/");
        boolean lower = arch && path.endsWith("_glass_lower");
        boolean eligible = lower || arch && (path.endsWith("_single_upper")
                || path.endsWith("_multi_upper") || path.endsWith("_mid_upper"))
                || path.startsWith("block/window/french_georgian/")
                && (path.endsWith("_closed_upper_single") || path.endsWith("_closed_upper_multi_lh")
                || path.endsWith("_closed_upper_multi_rh"));
        if (!eligible) return original;
        // Lower corner components share their bottom plane with a separately emitted wall.
        // Its fluted footprint must be known; broad rectangular guesses leave overlaps.
        if (lower && siblingWall == null) return original;

        BakedQuadFactory factory = new BakedQuadFactory();
        List<Polygon> existing = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        for (ModelElement element : source.getElements()) {
            ModelElementFace down = element.faces.get(Direction.DOWN);
            if (down != null) {
                BakedQuad baked = factory.bake(element.from, element.to, down,
                        textures.apply(source.resolveSprite(down.textureId)), Direction.DOWN,
                        settings, element.rotation, element.shade, id);
                Polygon polygon = polygon(baked);
                if (polygon != null) existing.add(polygon);
                continue;
            }
            // These components have authored bottoms at y=0, .001 or .01 model units.
            // Do not invent caps on a higher crossbar, a tilted element, or replacement geometry.
            if (element.from.y < -0.00001F || element.from.y > 0.011F
                    || element.to.x <= element.from.x || element.to.z <= element.from.z
                    || element.rotation != null && element.rotation.angle() != 0
                    && element.rotation.axis() != Direction.Axis.Y) continue;
            ModelElementFace material = bottomMaterial(element);
            if (material == null) continue;
            ModelElementFace cap = new ModelElementFace(null, material.tintIndex, material.textureId,
                    new ModelElementTexture(new float[]{element.from.x, 16 - element.to.z,
                            element.to.x, 16 - element.from.z}, 0));
            BakedQuad baked = factory.bake(element.from, element.to, cap,
                    textures.apply(source.resolveSprite(cap.textureId)), Direction.DOWN,
                    settings, element.rotation, element.shade, id);
            Polygon polygon = polygon(baked);
            if (polygon != null) candidates.add(new Candidate(baked, polygon, priority(material)));
        }
        if (candidates.isEmpty()) return original;
        List<Polygon> rightWall = lower ? downPolygons(siblingWall, textures, settings, id, factory) : List.of();
        List<Polygon> leftWall = new ArrayList<>(rightWall.size());
        for (Polygon polygon : rightWall) {
            List<Vertex> rotated = new ArrayList<>(polygon.vertices.size());
            for (Vertex vertex : polygon.vertices) rotated.add(point(1 - vertex.x(), vertex.y(), 1 - vertex.z()));
            leftWall.add(new Polygon(List.copyOf(rotated), polygon.plane));
        }
        // Authored DOWN faces always win. Missing opaque caps win over missing pane caps.
        candidates.sort(Comparator.comparingInt(Candidate::priority).reversed());
        List<List<BakedQuad>> variants = new ArrayList<>(lower ? 4 : 1);
        boolean added = false;
        for (int wallMask = 0; wallMask < (lower ? 4 : 1); wallMask++) {
            List<Polygon> reserved = new ArrayList<>(existing);
            if ((wallMask & 1) != 0) reserved.addAll(rightWall);
            if ((wallMask & 2) != 0) reserved.addAll(leftWall);
            List<BakedQuad> quads = new ArrayList<>();
            for (Candidate candidate : candidates) {
                List<List<Vertex>> pieces = List.of(candidate.polygon.vertices);
                for (Polygon cover : reserved) {
                    if (Math.abs(cover.plane - candidate.polygon.plane) > EPS) continue;
                    List<List<Vertex>> next = new ArrayList<>();
                    for (List<Vertex> piece : pieces) subtract(piece, cover.vertices, next);
                    pieces = next;
                    if (pieces.isEmpty()) break;
                }
                for (List<Vertex> piece : pieces) addQuads(candidate.quad, piece, quads);
                reserved.add(candidate.polygon);
            }
            variants.add(List.copyOf(quads));
            added |= !quads.isEmpty();
        }
        return added ? new CappedModel(original, List.copyOf(variants), arch, lower) : original;
    }

    private static ModelElementFace bottomMaterial(ModelElement element) {
        ModelElementFace selected = null;
        for (Direction face : Direction.values()) {
            ModelElementFace material = element.faces.get(face);
            if (material != null && (selected == null || priority(material) > priority(selected))) selected = material;
        }
        return selected;
    }

    private static List<Polygon> downPolygons(JsonUnbakedModel source,
                                             Function<SpriteIdentifier, Sprite> textures,
                                             ModelBakeSettings settings, Identifier id, BakedQuadFactory factory) {
        List<Polygon> result = new ArrayList<>();
        for (ModelElement element : source.getElements()) {
            ModelElementFace face = element.faces.get(Direction.DOWN);
            if (face == null) continue;
            BakedQuad baked = factory.bake(element.from, element.to, face,
                    textures.apply(source.resolveSprite(face.textureId)), Direction.DOWN,
                    settings, element.rotation, element.shade, id);
            Polygon polygon = polygon(baked);
            if (polygon != null) result.add(polygon);
        }
        return List.copyOf(result);
    }

    private static int priority(ModelElementFace face) {
        if (face.tintIndex == 0 || face.textureId.contains("pane")) return 0;
        return face.textureId.contains("lead") ? 1 : 2;
    }

    private static Polygon polygon(BakedQuad quad) {
        if (quad.getFace() != Direction.DOWN) return null;
        int[] data = quad.getVertexData();
        int stride = data.length / 4;
        if (stride < 6) return null;
        List<Vertex> vertices = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            int[] vertex = new int[stride];
            System.arraycopy(data, i * stride, vertex, 0, stride);
            vertices.add(new Vertex(vertex));
        }
        double plane = vertices.get(0).y();
        for (Vertex vertex : vertices) if (Math.abs(vertex.y() - plane) > EPS) return null;
        return Math.abs(area(vertices)) > AREA_EPS ? new Polygon(List.copyOf(vertices), plane) : null;
    }

    private static Vertex point(double x, double y, double z) {
        int[] data = new int[8]; data[0] = Float.floatToRawIntBits((float) x);
        data[1] = Float.floatToRawIntBits((float) y); data[2] = Float.floatToRawIntBits((float) z);
        return new Vertex(data);
    }

    /** Convex subtraction produces disjoint convex pieces, including when a cover makes a hole. */
    private static void subtract(List<Vertex> source, List<Vertex> cover, List<List<Vertex>> output) {
        List<Vertex> remaining = source;
        double sign = area(cover) >= 0 ? 1 : -1;
        for (int i = 0; i < cover.size() && !remaining.isEmpty(); i++) {
            Vertex a = cover.get(i), b = cover.get((i + 1) % cover.size());
            List<Vertex> outside = clip(remaining, a, b, sign, false);
            if (outside.size() >= 3 && Math.abs(area(outside)) > AREA_EPS) output.add(outside);
            remaining = clip(remaining, a, b, sign, true);
        }
    }

    private static List<Vertex> clip(List<Vertex> polygon, Vertex a, Vertex b, double sign, boolean inside) {
        List<Vertex> result = new ArrayList<>(polygon.size() + 1);
        Vertex previous = polygon.get(polygon.size() - 1);
        double previousSide = side(a, b, previous) * sign;
        boolean previousInside = inside ? previousSide >= 0 : previousSide <= 0;
        for (Vertex current : polygon) {
            double currentSide = side(a, b, current) * sign;
            boolean currentInside = inside ? currentSide >= 0 : currentSide <= 0;
            if (currentInside != previousInside) {
                double amount = previousSide / (previousSide - currentSide);
                append(result, Vertex.interpolate(previous, current, amount));
            }
            if (currentInside) append(result, current);
            previous = current; previousSide = currentSide; previousInside = currentInside;
        }
        if (result.size() > 1 && same(result.get(0), result.get(result.size() - 1))) result.remove(result.size() - 1);
        return result;
    }

    private static void append(List<Vertex> vertices, Vertex vertex) {
        if (vertices.isEmpty() || !same(vertices.get(vertices.size() - 1), vertex)) vertices.add(vertex);
    }

    private static boolean same(Vertex a, Vertex b) {
        return Math.abs(a.x() - b.x()) < EPS && Math.abs(a.z() - b.z()) < EPS;
    }

    private static double side(Vertex a, Vertex b, Vertex p) {
        return (b.x() - a.x()) * (p.z() - a.z()) - (b.z() - a.z()) * (p.x() - a.x());
    }

    private static double area(List<Vertex> polygon) {
        double twice = 0;
        for (int i = 0; i < polygon.size(); i++) {
            Vertex a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            twice += a.x() * b.z() - b.x() * a.z();
        }
        return twice / 2;
    }

    private static void addQuads(BakedQuad prototype, List<Vertex> polygon, List<BakedQuad> output) {
        if (polygon.size() < 3 || Math.abs(area(polygon)) <= AREA_EPS) return;
        if (polygon.size() == 4) {
            output.add(quad(prototype, polygon.get(0), polygon.get(1), polygon.get(2), polygon.get(3)));
        } else for (int i = 1; i + 1 < polygon.size(); i++) {
            output.add(quad(prototype, polygon.get(0), polygon.get(i), polygon.get(i + 1), polygon.get(i + 1)));
        }
    }

    private static BakedQuad quad(BakedQuad prototype, Vertex a, Vertex b, Vertex c, Vertex d) {
        int stride = a.data.length; int[] data = new int[stride * 4];
        Vertex[] vertices = {a, b, c, d};
        for (int i = 0; i < vertices.length; i++) System.arraycopy(vertices[i].data, 0, data, i * stride, stride);
        return new BakedQuad(data, prototype.getColorIndex(), prototype.getFace(), prototype.getSprite(), prototype.hasShade());
    }

    private record Vertex(int[] data) {
        double x() { return Float.intBitsToFloat(data[0]); }
        double y() { return Float.intBitsToFloat(data[1]); }
        double z() { return Float.intBitsToFloat(data[2]); }
        static Vertex interpolate(Vertex a, Vertex b, double amount) {
            double t = Math.max(0, Math.min(1, amount)); int[] data = a.data.clone();
            // Factory-created caps have uniform colour, lighting and packed normal.
            // Positions and the factory's atlas-shrunk UVs vary along the polygon edge.
            for (int slot : new int[]{0, 1, 2, 4, 5}) {
                double from = Float.intBitsToFloat(a.data[slot]), to = Float.intBitsToFloat(b.data[slot]);
                data[slot] = Float.floatToRawIntBits((float) (from + (to - from) * t));
            }
            return new Vertex(data);
        }
    }

    private record Polygon(List<Vertex> vertices, double plane) { }
    private record Candidate(BakedQuad quad, Polygon polygon, int priority) { }

    /** A vanilla child adapter lets the existing family renderer and CTM see these quads. */
    private static final class CappedModel implements BakedModel {
        private final BakedModel original;
        private final List<List<BakedQuad>> variants;
        private final boolean arch;
        private final boolean lower;

        private CappedModel(BakedModel original, List<List<BakedQuad>> variants, boolean arch, boolean lower) {
            this.original = original; this.variants = variants; this.arch = arch; this.lower = lower;
        }

        @Override public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
            List<BakedQuad> base = original.getQuads(state, face, random);
            if (face != null || !withoutSill(state)) return base;
            int variant = lower ? switch (state.get(WindowArchBlock.PIECE)) {
                case LOWER_LEFT -> 1; case LOWER_RIGHT -> 2; case LOWER_SINGLE -> 3; default -> 0;
            } : 0;
            List<BakedQuad> caps = variants.get(variant);
            if (caps.isEmpty()) return base;
            List<BakedQuad> result = new ArrayList<>(base.size() + caps.size());
            result.addAll(base); result.addAll(caps);
            return result;
        }

        private boolean withoutSill(BlockState state) {
            if (state == null) return false;
            if (arch) return state.getBlock() instanceof WindowArchBlock && state.get(WindowArchBlock.SILL)
                    && !state.get(WindowArchBlock.SILL_ENABLED);
            return state.getBlock() instanceof WindowFrenchGeorgianBlock
                    && state.get(WindowFrenchGeorgianBlock.SILL)
                    && state.get(WindowFrenchGeorgianBlock.SILL_SIDE) == WindowFrenchGeorgianBlock.SillSide.NONE;
        }

        @Override public boolean useAmbientOcclusion() { return original.useAmbientOcclusion(); }
        @Override public boolean hasDepth() { return original.hasDepth(); }
        @Override public boolean isSideLit() { return original.isSideLit(); }
        @Override public boolean isBuiltin() { return original.isBuiltin(); }
        @Override public Sprite getParticleSprite() { return original.getParticleSprite(); }
        @Override public ModelTransformation getTransformation() { return original.getTransformation(); }
        @Override public ModelOverrideList getOverrides() { return original.getOverrides(); }
    }
}
