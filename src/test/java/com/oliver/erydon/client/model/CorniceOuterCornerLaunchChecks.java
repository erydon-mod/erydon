package com.oliver.erydon.client.model;

import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BakedQuadFactory;
import net.minecraft.client.render.model.ModelRotation;
import net.minecraft.client.render.model.json.JsonUnbakedModel;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Actual authored profiles, independent swept-surface samples, and the original compact corner control. */
public final class CorniceOuterCornerLaunchChecks {
    private static final double EPS = 0.000002;
    private CorniceOuterCornerLaunchChecks() { }

    public static void run() throws Exception {
        int cases = 0, samples = 0, oldMissing = 0;
        for (int resolution : new int[]{16, 32, 64}) {
            try (var contents = new SpriteContents(new Identifier("erydon", "block/glacium_block"),
                    new SpriteDimensions(resolution, resolution), new NativeImage(resolution, resolution, false),
                    AnimationResourceMetadata.EMPTY)) {
                Sprite sprite = new TestSprite(contents, resolution);
                for (String style : List.of("modern", "georgian", "guilloche", "gothic")) {
                    List<BakedQuad> straight = bake(style, "straight", sprite);
                    List<int[]> original = straight.stream().map(q -> q.getVertexData().clone()).toList();
                    List<BakedQuad> old = bake(style, style.equals("modern") ? "outer" : "outer_corner", sprite);
                    // The three classical outer masters use the existing -90 degree child rotation.
                    List<double[][]> oldPoints = old.stream().map(q -> rotate(points(q), style.equals("modern") ? 0 : 3)).toList();
                    for (Direction facing : Direction.Type.HORIZONTAL) {
                        int turns = turns(facing);
                        List<BakedQuad> actual = CorniceOuterCornerGeometry.outer(straight, facing);
                        List<BakedQuad> first = CorniceOuterCornerGeometry.arm(straight, false, facing);
                        List<BakedQuad> second = CorniceOuterCornerGeometry.arm(straight, true, facing);
                        require(!first.isEmpty() && !second.isEmpty(), "Missing outer arm: " + style);
                        for (int arm = 0; arm < 2; arm++) for (BakedQuad quad : arm == 0 ? first : second) {
                            double[][] p = rotate(points(quad), 4 - turns);
                            boolean cutFace = true;
                            for (double[] v : p) {
                                require(arm == 0 ? v[0] <= v[2] + EPS : v[2] <= v[0] + EPS,
                                        "Cornice arms overlap beyond their physical mitre: " + style + "/" + facing);
                                cutFace &= Math.abs(v[0] - v[2]) < EPS;
                            }
                            require(!cutFace, "A new internal miter cap was emitted: " + style);
                            require(area(points(quad)) > 1e-12, "Degenerate corner surface");
                            normals(quad);
                            for (int i = 0; i < 4; i++) {
                                double u = value(quad, i, 4), v = value(quad, i, 5);
                                require(u >= sprite.getMinU() - EPS && u <= sprite.getMaxU() + EPS
                                                && v >= sprite.getMinV() - EPS && v <= sprite.getMaxV() + EPS,
                                        "Corner escaped its actual atlas sprite: " + style);
                            }
                            if (quad.getFace().getAxis() == Direction.Axis.Y && flat(quad)) {
                                boolean inCell = true;
                                for (int i = 0; i < 4; i++) inCell &= value(quad, i, 0) >= 0 && value(quad, i, 0) <= 1
                                        && value(quad, i, 2) >= 0 && value(quad, i, 2) <= 1;
                                if (inCell) for (int i = 0; i < 4; i++) {
                                    double x = value(quad, i, 0), z = value(quad, i, 2);
                                    require(Math.abs(value(quad, i, 4) - sprite.getFrameU((float) x * 16)) < 1e-6
                                                    && Math.abs(value(quad, i, 5) - sprite.getFrameV((float) (quad.getFace() == Direction.UP ? z : 1 - z) * 16)) < 1e-6,
                                            "Flat corner changed world texture phase: " + style + "/" + facing);
                                }
                            }
                        }

                        // This oracle maps original surface samples directly; it does not call the clipper.
                        for (BakedQuad source : straight) {
                            double[][] p = points(source);
                            if (area(p) <= 1e-12) continue;
                            for (int arm = 0; arm < 2; arm++) for (double a : new double[]{.05, .25, .5, .75, .95})
                                for (double b : new double[]{.1, .5, .9}) {
                                    double[] v = bilinear(p, a, b);
                                    double distance = arm == 0 ? v[2] - v[0] : v[0] + v[2] - 1;
                                    if (distance < 0.00002) continue;
                                    double[] north = arm == 0 ? v : new double[]{v[2], v[1], 1 - v[0]};
                                    double[] placed = rotate(north, turns);
                                    Direction expectedFace = source.getFace();
                                    for (int turn = 0; turn < Math.floorMod(turns + (arm == 1 ? 3 : 0), 4); turn++)
                                        if (expectedFace.getAxis() != Direction.Axis.Y) expectedFace = expectedFace.rotateYClockwise();
                                    Direction selectedFace = expectedFace;
                                    require(actual.stream().anyMatch(q -> q.getFace() == selectedFace && q.getSprite() == source.getSprite()
                                                    && q.getColorIndex() == source.getColorIndex() && q.hasShade() == source.hasShade()
                                                    && covers(points(q), placed)),
                                            "Outer corner lost an unchanged swept profile surface: " + style + "/" + facing + "/" + arm);
                                    if (!style.equals("modern") && oldPoints.stream().noneMatch(q -> covers(q, north))) oldMissing++;
                                    samples++;
                                }
                        }
                        if (style.equals("modern")) {
                            double before = oldPoints.stream().mapToDouble(CorniceOuterCornerLaunchChecks::area).sum();
                            double after = actual.stream().map(CorniceOuterCornerLaunchChecks::points)
                                    .mapToDouble(CorniceOuterCornerLaunchChecks::area).sum();
                            require(Math.abs(before - after) < 1e-9, "Modern stepped quarter exterior gained/lost area");
                            for (double[][] q : oldPoints) for (double a : new double[]{.15, .5, .85}) {
                                double[] north = bilinear(q, a, .37), placed = rotate(north, turns);
                                require(actual.stream().anyMatch(candidate -> covers(points(candidate), placed)),
                                        "Modern outer geometry changed");
                            }
                        }
                        for (int i = 0; i < straight.size(); i++) require(Arrays.equals(original.get(i), straight.get(i).getVertexData()),
                                "Generating a corner mutated the cached straight model");
                        cases++;
                    }
                }
            }
        }
        require(cases == 48 && samples > 1000 && oldMissing > 0, "Incomplete corner matrix/old hand-built negative control");
        System.out.println("ERYDON_CORNICE_OUTER_MITER_OK cases=" + cases + " sweptSamples=" + samples
                + " oldUnmatchedSamples=" + oldMissing + " seamOverlap=zero modernExterior=unchanged normals=authored atlas=16/32/64 worldPhase=preserved");
    }

    private static List<BakedQuad> bake(String style, String suffix, Sprite sprite) throws Exception {
        String path = "block/cornice/" + style + "/cornice_" + style + "_" + suffix;
        try (var stream = CorniceOuterCornerLaunchChecks.class.getResourceAsStream("/assets/erydon/models/" + path + ".json")) {
            require(stream != null, "Missing cornice " + path);
            var model = JsonUnbakedModel.deserialize(new InputStreamReader(stream, StandardCharsets.UTF_8));
            var factory = new BakedQuadFactory(); List<BakedQuad> result = new ArrayList<>();
            for (var element : model.getElements()) for (var face : element.faces.entrySet())
                result.add(factory.bake(element.from, element.to, face.getValue(), sprite, face.getKey(),
                        ModelRotation.X0_Y0, element.rotation, element.shade, new Identifier("erydon", path)));
            return List.copyOf(result);
        }
    }

    private static void normals(BakedQuad quad) {
        double[][] p = points(quad); double[] n = cross(subtract(p[1], p[0]), subtract(p[2], p[0]));
        if (dot(n, n) < 1e-20) n = cross(subtract(p[2], p[0]), subtract(p[3], p[0]));
        double length = Math.sqrt(dot(n, n));
        require(length > 0, "Corner has no real geometric normal");
        for (int i = 0; i < 4; i++) {
            int packed = quad.getVertexData()[i * 8 + 7];
            double[] actual = {(byte) packed, (byte) (packed >> 8), (byte) (packed >> 16)};
            require(dot(actual, n) / (Math.sqrt(dot(actual, actual)) * length) > .999,
                    "Miter clipping introduced a lighting seam or changed winding");
        }
    }

    private static boolean flat(BakedQuad q) { for (int i = 1; i < 4; i++) if (Math.abs(value(q, i, 1) - value(q, 0, 1)) > 1e-6) return false; return true; }
    private static double value(BakedQuad q, int vertex, int slot) { return Float.intBitsToFloat(q.getVertexData()[vertex * 8 + slot]); }
    private static double[][] points(BakedQuad q) { double[][] p = new double[4][3]; for (int i = 0; i < 4; i++) for (int a = 0; a < 3; a++) p[i][a] = value(q, i, a); return p; }
    private static double[] bilinear(double[][] p, double u, double v) { double[] out = new double[3]; for (int a = 0; a < 3; a++) out[a] = (1 - u) * (1 - v) * p[0][a] + u * (1 - v) * p[1][a] + u * v * p[2][a] + (1 - u) * v * p[3][a]; return out; }
    private static int turns(Direction f) { return switch (f) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; }; }
    private static double[] rotate(double[] p, int turns) { double x = p[0], z = p[2]; for (int i = 0; i < Math.floorMod(turns, 4); i++) { double old = x; x = 1 - z; z = old; } return new double[]{x, p[1], z}; }
    private static double[][] rotate(double[][] p, int turns) { return Arrays.stream(p).map(v -> rotate(v, turns)).toArray(double[][]::new); }
    private static double area(double[][] p) { return triangleArea(p[0], p[1], p[2]) + triangleArea(p[0], p[2], p[3]); }
    private static double triangleArea(double[] a, double[] b, double[] c) { double[] n = cross(subtract(b, a), subtract(c, a)); return Math.sqrt(dot(n, n)) / 2; }
    private static boolean covers(double[][] q, double[] p) { return triangle(q[0], q[1], q[2], p) || triangle(q[0], q[2], q[3], p); }
    private static boolean triangle(double[] a, double[] b, double[] c, double[] p) {
        double[] u = subtract(b, a), v = subtract(c, a), w = subtract(p, a);
        double uu = dot(u, u), uv = dot(u, v), vv = dot(v, v), wu = dot(w, u), wv = dot(w, v), det = uu * vv - uv * uv;
        if (det < 1e-20) return false;
        double s = (vv * wu - uv * wv) / det, t = (uu * wv - uv * wu) / det;
        if (s < -EPS || t < -EPS || s + t > 1 + EPS) return false;
        double distance = 0; for (int i = 0; i < 3; i++) distance += Math.pow(w[i] - s * u[i] - t * v[i], 2);
        return distance < 1e-11;
    }
    private static double[] subtract(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    private static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class TestSprite extends Sprite {
        TestSprite(SpriteContents contents, int resolution) { super(new Identifier("erydon", "cornice_miter_atlas"), contents, resolution * 4, resolution * 4, resolution, resolution * 2); }
        @Override public float getAnimationFrameDelta() { return 0; }
    }
}
