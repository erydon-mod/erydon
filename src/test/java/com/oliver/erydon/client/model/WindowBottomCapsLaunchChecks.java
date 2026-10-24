package com.oliver.erydon.client.model;

import com.oliver.erydon.block.WindowArchBlock;
import com.oliver.erydon.block.WindowFrenchGeorgianBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BakedQuadFactory;
import net.minecraft.client.render.model.ModelRotation;
import net.minecraft.client.render.model.json.JsonUnbakedModel;
import net.minecraft.client.render.model.json.ModelElementFace;
import net.minecraft.client.render.model.json.ModelElementTexture;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Independent area/coverage checks against the actual authored window elements and baked cap quads. */
final class WindowBottomCapsLaunchChecks {
    private static final double AREA_TOLERANCE = .00002 / 256;
    private record Point(double x, double z) { }
    private record Surface(List<Point> points, double y, BakedQuad quad) { }
    private record Case(String family, String suffix, int[] candidates, double[] areas) { }

    static void run(WindowArchBlock arch, WindowFrenchGeorgianBlock french, Sprite sprite) throws Exception {
        // Reference areas were independently computed from raw JSON with Shapely,
        // preserving authored DOWN faces and including every sibling-wall flute.
        Case[] cases = {
                new Case("arch", "glass_lower", new int[]{0, 1, 2, 3, 4, 6, 13, 14, 15},
                        new double[]{12.885207403, 11.096859339, 11.074929156, 9.286581093}),
                new Case("arch", "single_upper", new int[]{5, 6, 7, 12, 13, 14}, new double[]{.348855}),
                new Case("arch", "multi_upper", new int[]{5, 6, 7, 12, 13, 14, 23}, new double[]{2.928514410025}),
                new Case("arch", "mid_upper", new int[]{5, 6, 7, 12, 13, 14, 21, 22}, new double[]{4.598424387285}),
                new Case("french_georgian", "closed_upper_single", new int[]{4, 5}, new double[]{0}),
                new Case("french_georgian", "closed_upper_multi_lh", new int[]{7}, new double[]{.0625}),
                new Case("french_georgian", "closed_upper_multi_rh", new int[]{12}, new double[]{.0625})
        };
        int checked = 0, quads = 0;
        for (Case fixture : cases) {
            BakedModel model = GeorgianWindowRenderingLaunchChecks.component(fixture.family, fixture.suffix, sprite, false);
            JsonUnbakedModel source = source(fixture.family, fixture.suffix);
            List<Surface> candidates = candidates(source, fixture.candidates, sprite);
            List<Surface> authored = downFaces(source, sprite);
            for (int variant = 0; variant < fixture.areas.length; variant++) {
                boolean isArch = fixture.family.equals("arch");
                BlockState state = isArch ? arch.getDefaultState().with(WindowArchBlock.SILL, true)
                        .with(WindowArchBlock.SILL_ENABLED, false)
                        .with(WindowArchBlock.PIECE, switch (variant) {
                            case 1 -> WindowArchBlock.Piece.LOWER_LEFT;
                            case 2 -> WindowArchBlock.Piece.LOWER_RIGHT;
                            case 3 -> WindowArchBlock.Piece.LOWER_SINGLE;
                            default -> WindowArchBlock.Piece.LOWER_GLASS;
                        }) : french.getDefaultState().with(WindowFrenchGeorgianBlock.SILL, true)
                        .with(WindowFrenchGeorgianBlock.SILL_SIDE, WindowFrenchGeorgianBlock.SillSide.NONE);
                BlockState defaultSill = isArch ? state.with(WindowArchBlock.SILL_ENABLED, true)
                        : state.with(WindowFrenchGeorgianBlock.SILL_SIDE, WindowFrenchGeorgianBlock.SillSide.OUTWARD);
                require(model.getQuads(null, null, Random.create(0))
                                == model.getQuads(defaultSill, null, Random.create(0)),
                        "Capping changed the original default/item quad list");
                Set<BakedQuad> originals = Collections.newSetFromMap(new IdentityHashMap<>());
                originals.addAll(model.getQuads(null, null, Random.create(0)));
                for (Direction face : Direction.values()) {
                    List<BakedQuad> before = model.getQuads(null, face, Random.create(0));
                    originals.addAll(before);
                    require(before == model.getQuads(state, face, Random.create(0)),
                            "No-sill caps changed an existing culled face bucket");
                }
                List<BakedQuad> caps = model.getQuads(state, null, Random.create(0)).stream()
                        .filter(quad -> !originals.contains(quad)).toList();
                List<BakedQuad> repeated = model.getQuads(state, null, Random.create(0)).stream()
                        .filter(quad -> !originals.contains(quad)).toList();
                require(caps.size() == repeated.size(), "Cached cap count changed between queries");
                for (int i = 0; i < caps.size(); i++) require(caps.get(i) == repeated.get(i),
                        "A cap was clipped or rebaked during a render query");
                List<Surface> covers = new ArrayList<>(authored);
                if (fixture.suffix.equals("glass_lower")) {
                    List<Surface> wall = downFaces(source("arch", "wall"), sprite);
                    if ((variant & 1) != 0) covers.addAll(wall);
                    if ((variant & 2) != 0) for (Surface face : wall)
                        covers.add(new Surface(face.points.stream().map(p -> new Point(1 - p.x, 1 - p.z)).toList(),
                                face.y, face.quad));
                }
                List<Surface> added = new ArrayList<>();
                double area = 0;
                for (BakedQuad cap : caps) {
                    Surface face = surface(cap);
                    require(face != null && cap.getFace() == Direction.DOWN, "A cap is tilted or faces upward");
                    require(candidates.stream().anyMatch(candidate -> contains(candidate, face)
                                    && Math.signum(signedArea(candidate.points)) == Math.signum(signedArea(face.points))
                                    && candidate.quad.getColorIndex() == cap.getColorIndex()
                                    && candidate.quad.getSprite() == cap.getSprite()
                                    && candidate.quad.hasShade() == cap.hasShade()),
                            "A cap escaped its authored footprint, winding or material");
                    for (Surface existing : covers) if (samePlane(face, existing))
                        require(intersectionArea(face.points, existing.points) <= AREA_TOLERANCE,
                                "A cap overlaps an authored DOWN face or sibling wall/flute");
                    for (Surface previous : added) if (samePlane(face, previous))
                        require(intersectionArea(face.points, previous.points) <= AREA_TOLERANCE,
                                "Two cap materials overlap on the same plane");
                    int[] data = cap.getVertexData();
                    for (int vertex = 0; vertex < 4; vertex++) {
                        float u = Float.intBitsToFloat(data[vertex * 8 + 4]);
                        float v = Float.intBitsToFloat(data[vertex * 8 + 5]);
                        require(u >= sprite.getMinU() - .000001 && u <= sprite.getMaxU() + .000001
                                        && v >= sprite.getMinV() - .000001 && v <= sprite.getMaxV() + .000001,
                                "A new bottom cap samples beyond its sprite atlas bounds");
                    }
                    area += Math.abs(signedArea(face.points));
                    added.add(face);
                }
                require(Math.abs(area - fixture.areas[variant] / 256) <= AREA_TOLERANCE,
                        "Exposed cap coverage differs from independent reference: " + fixture.suffix + " variant="
                                + variant + " actualPixels=" + area * 256 + " expectedPixels=" + fixture.areas[variant]);
                checked++; quads += caps.size();
            }
        }
        System.out.println("ERYDON_WINDOW_BOTTOM_CAPS_OK cases=" + checked + " capQuads=" + quads
                + " independentCoverage=exact originalDefaults=identical overlaps=zero UVs=atlasSafe cache=reused");
    }

    private static JsonUnbakedModel source(String family, String suffix) throws Exception {
        String path = "/assets/erydon/models/block/window/" + family + "/window_" + family + "_" + suffix + ".json";
        try (var input = WindowBottomCapsLaunchChecks.class.getResourceAsStream(path)) {
            require(input != null, "Missing authored cap fixture " + path);
            return JsonUnbakedModel.deserialize(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
    }

    private static List<Surface> candidates(JsonUnbakedModel model, int[] indices, Sprite sprite) {
        List<Surface> result = new ArrayList<>();
        var factory = new BakedQuadFactory();
        for (int index : indices) {
            var element = model.getElements().get(index);
            ModelElementFace material = element.faces.values().iterator().next();
            ModelElementFace cap = new ModelElementFace(null, material.tintIndex, material.textureId,
                    new ModelElementTexture(new float[]{element.from.x, 16 - element.to.z,
                            element.to.x, 16 - element.from.z}, 0));
            result.add(surface(factory.bake(element.from, element.to, cap, sprite, Direction.DOWN,
                    ModelRotation.X0_Y0, element.rotation, element.shade, new Identifier("erydon", "cap_reference"))));
        }
        return result;
    }

    private static List<Surface> downFaces(JsonUnbakedModel model, Sprite sprite) {
        List<Surface> result = new ArrayList<>();
        var factory = new BakedQuadFactory();
        for (var element : model.getElements()) {
            var face = element.faces.get(Direction.DOWN);
            if (face == null) continue;
            Surface surface = surface(factory.bake(element.from, element.to, face, sprite, Direction.DOWN,
                    ModelRotation.X0_Y0, element.rotation, element.shade, new Identifier("erydon", "cap_existing")));
            if (surface != null) result.add(surface);
        }
        return result;
    }

    private static Surface surface(BakedQuad quad) {
        int[] data = quad.getVertexData(); List<Point> points = new ArrayList<>();
        double y = Float.intBitsToFloat(data[1]);
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * 8;
            if (Math.abs(Float.intBitsToFloat(data[offset + 1]) - y) > .000001) return null;
            points.add(new Point(Float.intBitsToFloat(data[offset]), Float.intBitsToFloat(data[offset + 2])));
        }
        return new Surface(points, y, quad);
    }

    private static boolean samePlane(Surface a, Surface b) { return Math.abs(a.y - b.y) < .000001; }
    private static boolean contains(Surface outer, Surface inner) {
        if (!samePlane(outer, inner)) return false;
        double sign = Math.signum(signedArea(outer.points));
        for (Point point : inner.points) for (int edge = 0; edge < outer.points.size(); edge++)
            if (cross(outer.points.get(edge), outer.points.get((edge + 1) % outer.points.size()), point) * sign < -.000001)
                return false;
        return true;
    }

    private static double signedArea(List<Point> points) {
        double twice = 0;
        for (int i = 0; i < points.size(); i++) {
            Point a = points.get(i), b = points.get((i + 1) % points.size()); twice += a.x * b.z - b.x * a.z;
        }
        return twice / 2;
    }

    private static double cross(Point a, Point b, Point p) {
        return (b.x - a.x) * (p.z - a.z) - (b.z - a.z) * (p.x - a.x);
    }

    private static double intersectionArea(List<Point> polygon, List<Point> clip) {
        List<Point> result = polygon; double sign = Math.signum(signedArea(clip));
        for (int edge = 0; edge < clip.size() && !result.isEmpty(); edge++) {
            Point a = clip.get(edge), b = clip.get((edge + 1) % clip.size());
            List<Point> input = result; result = new ArrayList<>(); Point previous = input.get(input.size() - 1);
            double before = cross(a, b, previous) * sign;
            for (Point current : input) {
                double after = cross(a, b, current) * sign;
                if ((before >= 0) != (after >= 0)) {
                    double t = before / (before - after);
                    result.add(new Point(previous.x + (current.x - previous.x) * t,
                            previous.z + (current.z - previous.z) * t));
                }
                if (after >= 0) result.add(current);
                previous = current; before = after;
            }
        }
        return Math.abs(signedArea(result));
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
