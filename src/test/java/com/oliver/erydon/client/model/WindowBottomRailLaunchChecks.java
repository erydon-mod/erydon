package com.oliver.erydon.client.model;

import com.oliver.erydon.block.WindowArchBlock;
import com.oliver.erydon.block.WindowFrenchGeorgianBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.client.render.model.ModelRotation;
import net.minecraft.client.render.model.json.JsonUnbakedModel;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Independent checks on the complete placed window stream, including both corner arms. */
final class WindowBottomRailLaunchChecks {
    private static final double EPS = 0.000002;
    // A float quarter-turn/inverse can put an exact cell edge half an ULP inside it.
    // Normalize only true cell boundaries; keep resolvable penetrations and other coordinates exact.
    private static final double CELL_EDGE_ULP = Math.ulp(1.0F);
    private static int cases, exteriorSamples;
    private static boolean negativeControl;
    private record Point(double x, double z) { }
    private record Rect(double x0, double z0, double x1, double z1) { }

    static void check(BlockState state, List<BakedQuad> actual, List<BakedQuad> oldFrame) {
        boolean arch = state.getBlock() instanceof WindowArchBlock;
        Direction facing = arch ? state.get(WindowArchBlock.FACING) : state.get(WindowFrenchGeorgianBlock.FACING);
        List<Rect> boxes = movingRail(state);
        double height = 1.5 / 16;
        if (!boxes.isEmpty()) {
            double expected = boxes.stream().mapToDouble(box -> (box.x1 - box.x0) * (box.z1 - box.z0)).sum();
            for (Direction side : new Direction[]{Direction.DOWN, Direction.UP}) {
                List<List<Point>> surfaces = new ArrayList<>();
                for (BakedQuad quad : actual) {
                    if (quad.getFace() != side) continue;
                    List<Point> points = horizontal(quad, facing, side == Direction.DOWN ? 0 : height);
                    if (points != null && Math.abs(area(points)) > 1e-12) surfaces.add(points);
                }
                double coverage = surfaces.stream().mapToDouble(surface -> insideArea(surface, boxes)).sum();
                require(Math.abs(coverage - expected) < .000003, "Moving sash rail has a gap/excess " + side + " " + state);
                require(!overlaps(surfaces, boxes), "Moving sash rail overlaps pane/frame bottoms " + state);
                if (!negativeControl && side == Direction.DOWN) {
                    var duplicate = new ArrayList<>(surfaces); duplicate.addAll(surfaces);
                    require(overlaps(duplicate, boxes), "Duplicate sash-bottom negative control was ineffective"); negativeControl = true;
                }
            }
            for (BakedQuad quad : actual) if (quad.getFace() == Direction.DOWN) {
                double y = value(quad, 0, 1);
                if (y <= EPS || y >= height - EPS) continue;
                List<Point> points = horizontal(quad, facing, y);
                require(points == null || insideArea(points, boxes) < 1e-10, "Pane/lead bottom remains inside sash rail " + state);
            }
        }
        // Native frame/jamb feet and every exterior UV survive. Only the moving rail owns its filled volume.
        for (BakedQuad original : oldFrame) {
            double[] sample = sample(original);
            if (!covers(original, sample)) continue;
            Point local = north(sample[0], sample[2], facing);
            if (sample[1] <= height + EPS && boxes.stream().anyMatch(rect -> contains(rect, local, EPS))) continue;
            require(coveredWithUvs(original, sample, actual), "Native frame/jamb foot/glass changed outside sash rail " + state);
            exteriorSamples++;
        }
        // A newly invented fixed threshold necessarily has UP/side faces absent from both native frame and sash.
        for (BakedQuad quad : actual) {
            double[] point = sample(quad);
            if (!covers(quad, point) || coveredWithUvs(quad, point, oldFrame)) continue;
            if (onMovingRail(point, facing, boxes) && quad.getColorIndex() == -1) continue;
            boolean cap = quad.getFace() == Direction.DOWN && (Math.abs(point[1]) < EPS || Math.abs(point[1] - .01 / 16) < EPS);
            require(cap, "Spurious finishing threshold/side face outside native frame and moving sash " + state + " " + diagnostic(quad, facing));
        }
        cases++;
    }

    private static List<Rect> movingRail(BlockState state) {
        if (!(state.getBlock() instanceof WindowFrenchGeorgianBlock)) return List.of();
        if (!state.get(WindowFrenchGeorgianBlock.OPEN)) {
            List<Rect> result = new ArrayList<>();
            result.add(new Rect(0, 0, 1, 3 / 16.0));
            var corner = state.get(WindowFrenchGeorgianBlock.CORNER);
            if (corner == WindowFrenchGeorgianBlock.Corner.LEFT) result.add(new Rect(0, 3 / 16.0, 3 / 16.0, 1));
            if (corner == WindowFrenchGeorgianBlock.Corner.RIGHT) result.add(new Rect(13 / 16.0, 3 / 16.0, 1, 1));
            return result;
        }
        if (state.get(WindowFrenchGeorgianBlock.CORNER) != WindowFrenchGeorgianBlock.Corner.NONE) return List.of();
        var piece = state.get(WindowFrenchGeorgianBlock.PIECE);
        boolean left;
        switch (piece) {
            case UPPER_SINGLE, LOWER_SINGLE -> left = state.get(WindowFrenchGeorgianBlock.HINGE) == net.minecraft.block.enums.DoorHinge.LEFT;
            case UPPER_MULTI_LH, LOWER_MULTI_LH -> left = true;
            case UPPER_MULTI_RH, LOWER_MULTI_RH -> left = false;
            default -> { return List.of(); }
        }
        return List.of(left ? new Rect(13.032 / 16, 1 / 16.0, 15.968 / 16, 15 / 16.0)
                : new Rect(.048 / 16, 1 / 16.0, 2.952 / 16, 15 / 16.0));
    }

    private static double[] sample(BakedQuad quad) {
        double[] point = new double[3];
        for (int axis = 0; axis < 3; axis++) point[axis] = .2 * value(quad, 0, axis) + .3 * value(quad, 1, axis) + .5 * value(quad, 2, axis);
        return point;
    }

    private static boolean coveredWithUvs(BakedQuad original, double[] point, List<BakedQuad> candidates) {
        double[] uv = sampleUv(original, point);
        return candidates.stream().anyMatch(quad -> quad.getFace() == original.getFace()
                && quad.getColorIndex() == original.getColorIndex() && quad.getSprite() == original.getSprite()
                && quad.hasShade() == original.hasShade() && sameUv(uv, sampleUv(quad, point)));
    }

    private static boolean onMovingRail(double[] point, Direction facing, List<Rect> boxes) {
        if (boxes.isEmpty() || point[1] < -EPS || point[1] > 1.5 / 16 + EPS) return false;
        Point local = north(point[0], point[2], facing);
        return boxes.stream().anyMatch(rect -> contains(rect, local, EPS) && (Math.abs(point[1]) < EPS || Math.abs(point[1] - 1.5 / 16) < EPS
                || Math.abs(local.x - rect.x0) < EPS || Math.abs(local.x - rect.x1) < EPS
                || Math.abs(local.z - rect.z0) < EPS || Math.abs(local.z - rect.z1) < EPS));
    }

    static void finish(Sprite sprite) throws Exception {
        outsideDiamondControl();
        for (String family : new String[]{"arch", "french_georgian"}) {
            String block = family.equals("arch") ? "aganite_window_arch" : "aganite_window_french_georgian";
            BlockState seed = net.minecraft.registry.Registries.BLOCK.get(new Identifier("erydon", block)).getDefaultState();
            BlockState noSill = family.equals("arch") ? seed.with(WindowArchBlock.SILL, true).with(WindowArchBlock.SILL_ENABLED, false)
                    : seed.with(WindowFrenchGeorgianBlock.SILL, true).with(WindowFrenchGeorgianBlock.SILL_SIDE, WindowFrenchGeorgianBlock.SillSide.NONE);
            BakedModel sill = GeorgianWindowRenderingLaunchChecks.component(family, "sill", sprite);
            require(sill.getQuads(family.equals("arch") ? noSill : noSill.with(WindowFrenchGeorgianBlock.OPEN, true), null, Random.create(0)).isEmpty(), "Open no-sill model still adds a fixed threshold");
            if (family.equals("french_georgian")) {
                List<BakedQuad> closed = sill.getQuads(noSill, null, Random.create(0));
                require(!closed.isEmpty() && closed == sill.getQuads(noSill, null, Random.create(0)), "Closed sash rail missing or rebuilt per query");
                require(sill.getQuads(noSill.with(WindowFrenchGeorgianBlock.SILL, false), null, Random.create(0))
                        == sill.getQuads(null, null, Random.create(0)), "Non-bottom row acquired a closed sash rail");
            }
            require(sill.getQuads(seed, null, Random.create(0)) == sill.getQuads(null, null, Random.create(0)), "Default/item sill changed");
        }
        BlockState seed = net.minecraft.registry.Registries.BLOCK.get(new Identifier("erydon", "aganite_window_french_georgian")).getDefaultState();
        BlockState closedBase = seed.with(WindowFrenchGeorgianBlock.SILL, true).with(WindowFrenchGeorgianBlock.OPEN, false)
                .with(WindowFrenchGeorgianBlock.SILL_SIDE, WindowFrenchGeorgianBlock.SillSide.NONE);
        for (String suffix : new String[]{"closed_lower_single", "closed_upper_single", "closed_lower_multi_lh", "closed_lower_multi_mid",
                "closed_lower_multi_rh", "closed_upper_multi_lh", "closed_upper_multi_mid", "closed_upper_multi_rh"}) {
            BakedModel leaf = GeorgianWindowRenderingLaunchChecks.component("french_georgian", suffix, sprite);
            List<BakedQuad> cached = leaf.getQuads(closedBase, null, Random.create(0));
            require(cached == leaf.getQuads(closedBase, null, Random.create(0)), "Closed sash clipping was rebuilt per query " + suffix);
            require(leaf.getQuads(null, null, Random.create(0)) == leaf.getQuads(closedBase.with(WindowFrenchGeorgianBlock.SILL, false), null, Random.create(0)),
                    "Closed non-bottom/item geometry changed " + suffix);
            for (BakedQuad quad : cached) if (quad.getColorIndex() == 0)
                for (int vertex = 0; vertex < 4; vertex++) require(value(quad, vertex, 1) >= 1.5 / 16 - EPS,
                        "Closed pane extends through its opaque lower sash frame " + suffix);
        }
        BlockState state = seed.with(WindowFrenchGeorgianBlock.OPEN, true).with(WindowFrenchGeorgianBlock.SILL, true);
        for (String suffix : new String[]{"open_lower_single_lh", "open_lower_single_rh", "open_lower_multi_lh", "open_lower_multi_rh",
                "open_upper_single_lh", "open_upper_single_rh", "open_upper_single_edge_lh", "open_upper_single_edge_rh"}) {
            BakedModel moving = GeorgianWindowRenderingLaunchChecks.component("french_georgian", suffix, sprite);
            List<BakedQuad> cached = moving.getQuads(state, null, Random.create(0));
            for (WindowFrenchGeorgianBlock.SillSide side : WindowFrenchGeorgianBlock.SillSide.values())
                require(cached == moving.getQuads(state.with(WindowFrenchGeorgianBlock.SILL_SIDE, side), null, Random.create(0)),
                        "Sill choice changed/rebuilt the moving sash geometry " + suffix);
            BlockState wing = state.with(WindowFrenchGeorgianBlock.HINGE, suffix.endsWith("_lh")
                    ? net.minecraft.block.enums.DoorHinge.LEFT : net.minecraft.block.enums.DoorHinge.RIGHT);
            for (BakedQuad quad : cached) if (quad.getColorIndex() == 0 && quad.getFace() == Direction.DOWN
                    && value(quad, 0, 1) < 1.5 / 16 - EPS) {
                List<Point> points = horizontal(quad, Direction.NORTH, value(quad, 0, 1));
                require(points == null || insideArea(points, movingRail(wing)) < 1e-10,
                        "Upper fanlight concealed the base-reaching sash pane from clipping " + suffix);
            }
        }
        // The previous wrong model was a full-width horizontal strip: it is neither a cap nor a moving-wing boundary.
        double[] inventedThreshold = {.5, 1 / 16.0, 1.5 / 16};
        require(!onMovingRail(inventedThreshold, Direction.NORTH, movingRail(state)), "Wrong-threshold negative control was accepted");
        require(cases == 896 && exteriorSamples > 10000 && negativeControl, "Missing no-sill assembled states " + cases);
        materialAtlasChecks(sprite);
        System.out.println("ERYDON_WINDOW_BOTTOM_RAIL_OK cases=" + cases + " exteriorSamples=" + exteriorSamples
                + " nativeJambFeet=preserved openFixedThreshold=absent closedSash+movingSash=sealed overlaps=zero cached=true");
    }

    private static String diagnostic(BakedQuad q, Direction facing) {
        StringBuilder text = new StringBuilder(q.getFace() + ",tint=" + q.getColorIndex());
        for (int v = 0; v < 4; v++) text.append(" world[").append(value(q, v, 0) * 16).append(',')
                .append(value(q, v, 1) * 16).append(',').append(value(q, v, 2) * 16)
                .append("] north=").append(north(value(q, v, 0), value(q, v, 2), facing));
        return text.toString();
    }

    private static boolean covers(BakedQuad q, double[] p) {
        return triangle(q, p, 0, 1, 2) || triangle(q, p, 0, 2, 3);
    }

    private static boolean sameUv(double[] a, double[] b) {
        return a != null && b != null && Math.abs(a[0] - b[0]) < .000003 && Math.abs(a[1] - b[1]) < .000003;
    }

    private static double[] sampleUv(BakedQuad q, double[] p) {
        for (int[] vertices : new int[][]{{0, 1, 2}, {0, 2, 3}}) {
            int a = vertices[0], b = vertices[1], c = vertices[2];
            if (!triangle(q, p, a, b, c)) continue;
            double[] u = new double[3], v = new double[3], w = new double[3];
            for (int axis = 0; axis < 3; axis++) {
                u[axis] = value(q, b, axis) - value(q, a, axis);
                v[axis] = value(q, c, axis) - value(q, a, axis);
                w[axis] = p[axis] - value(q, a, axis);
            }
            double uu = dot(u, u), uv = dot(u, v), vv = dot(v, v), wu = dot(w, u), wv = dot(w, v);
            double det = uu * vv - uv * uv, s = (vv * wu - uv * wv) / det, t = (uu * wv - uv * wu) / det;
            double[] result = new double[2];
            for (int axis = 0; axis < 2; axis++) result[axis] = value(q, a, axis + 4)
                    + s * (value(q, b, axis + 4) - value(q, a, axis + 4))
                    + t * (value(q, c, axis + 4) - value(q, a, axis + 4));
            return result;
        }
        return null;
    }

    /** Real inherited material wrappers, displaced atlas regions and every supported pixel density. */
    private static void materialAtlasChecks(Sprite fixtureSprite) throws Exception {
        var sources = new HashMap<Identifier, JsonUnbakedModel>();
        java.util.function.Function<Identifier, JsonUnbakedModel> loader = id -> sources.computeIfAbsent(id, key -> {
            String path = "/assets/" + key.getNamespace() + "/models/" + key.getPath() + ".json";
            try (var input = WindowBottomRailLaunchChecks.class.getResourceAsStream(path)) {
                require(input != null, "Missing rail material parent " + key);
                return JsonUnbakedModel.deserialize(new InputStreamReader(input, StandardCharsets.UTF_8));
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
        int materials = 0, atlasCases = 0;
        for (String family : new String[]{"arch", "french_georgian"}) {
            var directory = WindowBottomRailLaunchChecks.class.getResource("/assets/erydon/models/block/window/" + family);
            require(directory != null && directory.getProtocol().equals("file"), "Rail probe requires its ordinary resource classpath");
            List<Path> wrappers;
            try (var files = Files.list(Path.of(directory.toURI()))) {
                wrappers = files.filter(p -> p.getFileName().toString().matches(".+_window_" + family + (family.equals("arch") ? "_wall" : "_sill(?:_(?:lh|rh))?") + "(?:_aged)?\\.json")).toList();
            }
            require(wrappers.size() == (family.equals("arch") ? 81 : 243), "Missing material/finish rail wrappers for " + family);
            BlockState seed = net.minecraft.registry.Registries.BLOCK.get(new Identifier("erydon", family.equals("arch")
                    ? "aganite_window_arch" : "aganite_window_french_georgian")).getDefaultState();
            BlockState state = family.equals("arch") ? seed.with(WindowArchBlock.SILL, true).with(WindowArchBlock.SILL_ENABLED, false)
                    : seed.with(WindowFrenchGeorgianBlock.SILL, true).with(WindowFrenchGeorgianBlock.SILL_SIDE, WindowFrenchGeorgianBlock.SillSide.NONE);
            BakedModel original = GeorgianWindowRenderingLaunchChecks.component(family, "sill", fixtureSprite, false);
            for (Path wrapperPath : wrappers) {
                String filename = wrapperPath.getFileName().toString();
                Identifier id = new Identifier("erydon", "block/window/" + family + "/" + filename.substring(0, filename.length() - 5));
                JsonUnbakedModel source = loader.apply(id); source.setParents(loader::apply);
                Identifier material = source.resolveSprite("#stone").getTextureId();
                require(material.getNamespace().equals("erydon") && !material.getPath().contains("missing"),
                        "Finishing rail lost its native material/finish " + id);
                for (int resolution : new int[]{16, 32, 64}) {
                    try (var contents = new SpriteContents(material, new SpriteDimensions(resolution, resolution),
                            new NativeImage(resolution, resolution, false), AnimationResourceMetadata.EMPTY)) {
                        Sprite atlas = new Sprite(new Identifier("erydon", "window_rail_atlas"), contents,
                                resolution * 8, resolution * 8, resolution * 2, resolution * 3) { };
                        for (int yaw : new int[]{0, 90, 180, 270}) {
                            List<BakedQuad> nativeQuads = new ArrayList<>();
                            var factory = new net.minecraft.client.render.model.BakedQuadFactory();
                            for (var element : source.getElements()) for (var face : element.faces.entrySet())
                                nativeQuads.add(factory.bake(element.from, element.to, face.getValue(), atlas, face.getKey(),
                                        ModelRotation.get(0, yaw), element.rotation, element.shade, id));
                            require(!nativeQuads.isEmpty(), "Native foot/moving-rail material check was empty");
                            List<List<BakedQuad>> variants = List.of(nativeQuads);
                            if (id.getPath().matches(".*_sill(?:_aged)?")) {
                                BakedModel rail = WindowBottomRail.wrapSill(original, source, ignored -> atlas, ModelRotation.get(0, yaw), id, seed);
                                variants = new ArrayList<>();
                                for (var corner : WindowFrenchGeorgianBlock.Corner.values()) {
                                    List<BakedQuad> cached = rail.getQuads(state.with(WindowFrenchGeorgianBlock.CORNER, corner), null, Random.create(0));
                                    require(!cached.isEmpty() && cached == rail.getQuads(state.with(WindowFrenchGeorgianBlock.CORNER, corner), null, Random.create(0)),
                                            "Closed rail union missing or rebuilt for material " + id);
                                    variants.add(cached);
                                }
                            }
                            for (List<BakedQuad> variant : variants) for (BakedQuad quad : variant) for (int vertex = 0; vertex < 4; vertex++)
                                require(quad.getSprite() == atlas && value(quad, vertex, 4) >= atlas.getMinU() - EPS
                                                && value(quad, vertex, 4) <= atlas.getMaxU() + EPS
                                                && value(quad, vertex, 5) >= atlas.getMinV() - EPS
                                                && value(quad, vertex, 5) <= atlas.getMaxV() + EPS,
                                        "Native jamb foot/moving rail UV escaped atlas " + id + " pixels=" + resolution + " yaw=" + yaw);
                            atlasCases += variants.size();
                        }
                    }
                }
                materials++;
            }
        }
        require(materials == 324 && atlasCases == 5832, "Missing rail material/finish/atlas coverage");
        System.out.println("ERYDON_WINDOW_RAIL_MATERIAL_UVS_OK materials=" + materials + " atlasCases=" + atlasCases + " resolutions=16/32/64 allFacings=true");
    }

    private static boolean triangle(BakedQuad q, double[] p, int a, int b, int c) {
        double[] u = new double[3], v = new double[3], w = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            u[axis] = value(q, b, axis) - value(q, a, axis);
            v[axis] = value(q, c, axis) - value(q, a, axis);
            w[axis] = p[axis] - value(q, a, axis);
        }
        double uu = dot(u, u), uv = dot(u, v), vv = dot(v, v), wu = dot(w, u), wv = dot(w, v);
        double det = uu * vv - uv * uv;
        if (det < 1e-16) return false;
        double s = (vv * wu - uv * wv) / det, t = (uu * wv - uv * wu) / det;
        if (s < -EPS || t < -EPS || s + t > 1 + EPS) return false;
        double distance = 0;
        for (int axis = 0; axis < 3; axis++) distance += Math.pow(w[axis] - s * u[axis] - t * v[axis], 2);
        return distance < 1e-11;
    }

    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static double value(BakedQuad q, int vertex, int slot) {
        return Float.intBitsToFloat(q.getVertexData()[vertex * (q.getVertexData().length / 4) + slot]);
    }
    private static List<Point> horizontal(BakedQuad q, Direction facing, double y) {
        List<Point> result = new ArrayList<>();
        for (int vertex = 0; vertex < 4; vertex++) {
            if (Math.abs(value(q, vertex, 1) - y) > EPS) return null;
            result.add(north(value(q, vertex, 0), value(q, vertex, 2), facing));
        }
        return result;
    }
    private static Point north(double x, double z, Direction facing) {
        Point point = switch (facing) { case EAST -> new Point(z, 1 - x); case SOUTH -> new Point(1 - x, 1 - z);
            case WEST -> new Point(1 - z, x); default -> new Point(x, z); };
        return new Point(cellEdge(point.x), cellEdge(point.z));
    }
    private static double cellEdge(double value) {
        if (Math.abs(value) <= CELL_EDGE_ULP) return 0;
        return Math.abs(value - 1) <= CELL_EDGE_ULP ? 1 : value;
    }
    private static void outsideDiamondControl() {
        // Actual vanilla-baked/quarter-turned solid triangle captured by this probe.
        // Its base is authored on z=1; only float transform dust put it 2.98e-8 inside.
        List<Rect> arm = List.of(new Rect(0, 0, 3 / 16.0, 1));
        List<Point> captured = List.of(new Point(.13769376277923584, cellEdge(.9999999701976776)),
                new Point(.09375, 1.0439436435699463), new Point(.049806296825408936, cellEdge(.9999999701976776)));
        require(insideArea(captured, arm) < 1e-14, "Captured outside diamond was mistaken for a buried cap");
        double realPenetration = 1 - 64 * CELL_EDGE_ULP;
        List<Point> real = List.of(new Point(captured.get(0).x, cellEdge(realPenetration)), captured.get(1),
                new Point(captured.get(2).x, cellEdge(realPenetration)));
        require(insideArea(real, arm) > 1e-8, "A resolvable real penetration was hidden by edge normalization");
    }
    private static boolean contains(Rect r, Point p, double margin) {
        return p.x >= r.x0 - margin && p.x <= r.x1 + margin && p.z >= r.z0 - margin && p.z <= r.z1 + margin;
    }
    private static double insideArea(List<Point> polygon, List<Rect> boxes) {
        double result = 0;
        for (Rect box : boxes) result += Math.abs(area(intersect(polygon, rectangle(box))));
        if (boxes.size() == 2) result -= Math.abs(area(intersect(intersect(polygon, rectangle(boxes.get(0))), rectangle(boxes.get(1)))));
        return result;
    }
    private static boolean overlaps(List<List<Point>> surfaces, List<Rect> boxes) {
        for (int i = 0; i < surfaces.size(); i++) for (int j = i + 1; j < surfaces.size(); j++)
            if (insideArea(intersect(surfaces.get(i), surfaces.get(j)), boxes) > 1e-9) return true;
        return false;
    }
    private static List<Point> rectangle(Rect r) { return List.of(new Point(r.x0, r.z0), new Point(r.x1, r.z0), new Point(r.x1, r.z1), new Point(r.x0, r.z1)); }
    private static List<Point> intersect(List<Point> subject, List<Point> clip) {
        if (subject.isEmpty() || clip.isEmpty()) return List.of();
        double sign = area(clip) < 0 ? -1 : 1;
        List<Point> result = subject;
        for (int edge = 0; edge < clip.size() && !result.isEmpty(); edge++) {
            Point a = clip.get(edge), b = clip.get((edge + 1) % clip.size());
            List<Point> input = result; result = new ArrayList<>();
            Point previous = input.get(input.size() - 1); double ps = side(a, b, previous) * sign;
            for (Point current : input) {
                double cs = side(a, b, current) * sign;
                if ((ps >= 0) != (cs >= 0)) {
                    double t = ps / (ps - cs);
                    result.add(new Point(previous.x + (current.x - previous.x) * t, previous.z + (current.z - previous.z) * t));
                }
                if (cs >= 0) result.add(current);
                previous = current; ps = cs;
            }
        }
        return result;
    }
    private static double side(Point a, Point b, Point p) { return (b.x - a.x) * (p.z - a.z) - (b.z - a.z) * (p.x - a.x); }
    private static double area(List<Point> points) {
        double result = 0;
        for (int i = 0; i < points.size(); i++) { Point a = points.get(i), b = points.get((i + 1) % points.size()); result += a.x * b.z - a.z * b.x; }
        return result / 2;
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
