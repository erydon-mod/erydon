package com.oliver.erydon.client.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oliver.erydon.block.GlazingSlopeGeometry;
import com.oliver.erydon.block.GlazingSlopeGeometry.Profile;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BakedQuadFactory;
import net.minecraft.client.render.model.ModelBakeSettings;
import net.minecraft.client.render.model.ModelRotation;
import net.minecraft.client.render.model.json.JsonUnbakedModel;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.EmptyBlockView;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Independent native-model oracle plus actual public Fabric and Axiom emission paths. */
public final class GlazingSlopeMeshLaunchChecks {
    private static final String[] FINISHES = {"tinted", "silver", "crystal", "bronze"};
    private static final String[] SHAPES = {"straight", "inner_left", "inner_right", "outer_left", "outer_right"};
    private static final double EPS = .00002;

    public static void run() throws Exception {
        GlazingSlopePlacementLaunchChecks.run();
        if (RendererAccess.INSTANCE.getRenderer() == null) RendererAccess.INSTANCE.registerRenderer(IndigoRenderer.INSTANCE);
        int states = 0, rays = 0, creaseRays = 0, layerRays = 0, triangles = 0, straightVertices = 0;
        try (SpriteContents glassContents = contents("glass"); SpriteContents leadContents = contents("lead")) {
            Sprite glass = new TestSprite(glassContents, 0, 0), lead = new TestSprite(leadContents, 32, 32);
            BakedModel wrapped = wrapped(glass);
            Map<String, List<Quad>> sourceCache = new HashMap<>();
            for (Profile profile : Profile.values()) for (String finish : FINISHES) {
                String form = form(profile);
                var block = GlazingSlopePlacementLaunchChecks.block(finish, form);
                GlazingSlopeBakedModel model = new GlazingSlopeBakedModel(wrapped, profile, glass, lead);
                for (BlockState state : block.getStateManager().getStates()) {
                    Direction facing = state.get(Properties.HORIZONTAL_FACING);
                    BlockHalf half = state.get(Properties.BLOCK_HALF);
                    String shape = Arrays.stream(SHAPES).filter(s -> GlazingSlopePlacementLaunchChecks.isShape(state, s)).findFirst().orElseThrow();
                    List<Quad> source = sourceCache.computeIfAbsent(profile + "/" + facing + "/" + half,
                            ignored -> nativeSource(profile, facing, half, glass, lead));
                    var faces = GlazingSlopeGeometry.faces(profile, facing, shape, half);
                    List<Quad> nativeMesh = nativeFaces(faces, glass, lead);
                    List<Quad> preview = baked(model.getQuads(state, null, Random.create(0)));
                    List<Quad> placed = placed(model, state, glass, lead);
                    if (profile == Profile.STANDARD) {
                        layerRays += checkLayerFrames(placed, sourceCache.computeIfAbsent("layer-neighbour",
                                ignored -> nativeSource(Profile.STANDARD, Direction.SOUTH, BlockHalf.BOTTOM, glass, lead)),
                                facing, half, shape, glass, lead);
                    }
                    require(!preview.isEmpty() && preview.size() == placed.size(), "Actual glazing renderer/preview quad counts differ: " + state);
                    compareMeshes(preview, placed, "Actual glazing FRAPI/preview mismatch: " + state);
                    compareNormals(preview, placed, state);
                    compareMeshes(nativeMesh, preview, "Actual glazing emitter changed cached native faces: " + state);
                    for (Quad quad : placed) {
                        require(quad.sprite == glass || quad.sprite == lead, "Glazing used an unrelated texture slot");
                        for (Point p : quad.points) require(Double.isFinite(p.x + p.y + p.z + p.u + p.v)
                                        && p.u >= quad.sprite.getMinU() - EPS && p.u <= quad.sprite.getMaxU() + EPS
                                        && p.v >= quad.sprite.getMinV() - EPS && p.v <= quad.sprite.getMaxV() + EPS,
                                "Glazing emitted invalid/atlas-crossing vertices: " + state);
                        if (distinct(quad) == 3) { require(area(quad) > 1e-10, "Degenerate visible glass triangle"); triangles++; }
                    }
                    VoxelShape outline = block.getOutlineShape(state, EmptyBlockView.INSTANCE, BlockPos.ORIGIN, ShapeContext.absent());
                    VoxelShape collision = block.getCollisionShape(state, EmptyBlockView.INSTANCE, BlockPos.ORIGIN, ShapeContext.absent());
                    require(!VoxelShapes.matchesAnywhere(outline, collision, BooleanBiFunction.NOT_SAME), "Glazing targeting/collision differ: " + state);
                    if (shape.equals("straight")) {
                        // Actual vanilla bake is the oracle for model rotations, original source UVs and UV-lock.
                        compareMeshes(source, preview, "Approved straight glazing changed: " + profile + " " + state);
                        straightVertices += source.size() * 4;
                    }
                    Direction otherFacing = shape.endsWith("left") ? facing.rotateYCounterclockwise() : facing.rotateYClockwise();
                    List<Quad> other = sourceCache.computeIfAbsent(profile + "/" + otherFacing + "/" + half,
                            ignored -> nativeSource(profile, otherFacing, half, glass, lead));
                    List<Triangle> actualTriangles = triangles(placed), sourceTriangles = triangles(source), otherTriangles = triangles(other);
                    List<Box> boxes = outline.getBoundingBoxes();
                    // Off-grid points expose old strip stair-steps and holes between the two analytic facets.
                    for (int ix = 0; ix < 25; ix++) for (int iz = 0; iz < 25; iz++) {
                        double x = (ix + .371) / 25, z = (iz + .619) / 25;
                        double[] expected = bounds(sourceTriangles, x, z);
                        if (!shape.equals("straight")) {
                            double[] second = bounds(otherTriangles, x, z);
                            expected = shape.startsWith("inner") ? new double[]{Math.min(expected[0], second[0]), Math.min(expected[1], second[1])}
                                    : new double[]{Math.max(expected[0], second[0]), Math.max(expected[1], second[1])};
                        }
                        double[] actual = bounds(actualTriangles, x, z);
                        close(expected, actual, EPS, "Glass corner facet gap/overlap: " + profile + " " + shape + " " + facing + " " + half + " at " + x + "," + z);
                        double[] solid = bounds(boxes, x, z);
                        // Smooth slab voxels remain a conservative, bounded approximation.
                        require(solid[0] <= actual[0] + EPS && solid[1] >= actual[1] - EPS,
                                "Glazing outline misses its rendered pane: " + state + " at " + x + "," + z);
                        double voxelTolerance = (profile == Profile.STANDARD ? 1 : Math.tan(Math.PI / 8)) / 32 + 1.0 / 128 + EPS;
                        require(actual[0] - solid[0] <= voxelTolerance && solid[1] - actual[1] <= voxelTolerance,
                                "Glazing voxel envelope exceeds declared grid/height precision: " + state);
                        rays++;
                    }
                    if (!shape.equals("straight")) {
                        List<Triangle> glassFaces = triangles(placed.stream().filter(q -> q.sprite == glass).toList());
                        List<Triangle> leadFaces = triangles(placed.stream().filter(q -> q.sprite == lead).toList());
                        for (double t : new double[]{.2, .35, .5, .65, .8}) {
                            double x = shape.endsWith("left") ? t : 1 - t, z = t;
                            int turns = facing == Direction.SOUTH ? 0 : facing == Direction.WEST ? 1 : facing == Direction.NORTH ? 2 : 3;
                            for (int turn = 0; turn < turns; turn++) { double old = x; x = 1 - z; z = old; }
                            require(!Double.isFinite(bounds(glassFaces, x, z)[0]), "Transparent glass overlaps the diagonal lead crease: " + state);
                            require(Double.isFinite(bounds(leadFaces, x, z)[0]), "Diagonal lead crease has a gap: " + state);
                            creaseRays++;
                        }
                    }
                    if (profile != Profile.STANDARD) {
                        BlockHalf opposite = half == BlockHalf.TOP ? BlockHalf.BOTTOM : BlockHalf.TOP;
                        require(faces == GlazingSlopeGeometry.faces(profile, facing, shape, opposite), "Saved shallow HALF duplicates geometry/cache");
                        require(outline == block.getOutlineShape(state.with(Properties.BLOCK_HALF, opposite), EmptyBlockView.INSTANCE,
                                BlockPos.ORIGIN, ShapeContext.absent()), "Saved shallow HALF changed its registered section");
                    }
                    states++;
                }
            }
        }
        require(triangles > 0 && creaseRays > 0 && straightVertices > 0, "Glazing fixture missed triangular corners or straight preservation");
        System.out.println("ERYDON_GLAZING_MESH_OK: savedStates=" + states + " independentFacetRays=" + rays
                + " creaseRays=" + creaseRays + " layerFrameRays=" + layerRays
                + " realTriangles=" + triangles + " preservedStraightVertices=" + straightVertices);
    }

    /** Adjacent height layers must expose an opaque bar, without coplanar glass hiding it. */
    private static int checkLayerFrames(List<Quad> actual, List<Quad> neighbour, Direction facing,
                                         BlockHalf half, String shape, Sprite glass, Sprite lead) {
        int turns = facing == Direction.SOUTH ? 0 : facing == Direction.WEST ? 1 : facing == Direction.NORTH ? 2 : 3;
        List<Quad> canonical = new ArrayList<>();
        for (Quad quad : actual) {
            Point[] points = new Point[4];
            for (int i = 0; i < 4; i++) {
                Point p = quad.points[i];
                double x = p.x, y = p.y, z = p.z;
                for (int turn = 0; turn < turns; turn++) { double old = x; x = z; z = 1 - old; }
                if (half == BlockHalf.TOP) { y = 1 - y; z = 1 - z; }
                points[i] = new Point(x, y, z, p.u, p.v);
            }
            canonical.add(new Quad(points, quad.sprite));
        }
        var ownLeadFaces = triangles(canonical.stream().filter(q -> q.sprite == lead).toList());
        int rays = 0;
        for (int end : new int[]{0, 1}) {
            // Undoing X180 also reverses the retained high/low edge of a corner.
            int retainedEnd = shape.startsWith("inner") == (half == BlockHalf.TOP) ? 1 : 0;
            if (!shape.equals("straight") && end != retainedEnd) continue;
            List<Quad> joined = new ArrayList<>(canonical);
            double shift = end == 0 ? -1 : 1;
            for (Quad quad : neighbour) joined.add(new Quad(Arrays.stream(quad.points)
                    .map(p -> new Point(p.x, p.y + shift, p.z + shift, p.u, p.v)).toArray(Point[]::new), quad.sprite));
            var glassFaces = triangles(joined.stream().filter(q -> q.sprite == glass).toList());
            // The 1.25-pixel crossbar projects to 0.055 blocks at 45 degrees, on both pane surfaces.
            for (double x : new double[]{.2, .35, .5, .65, .8}) for (double inset : new double[]{.01, .025, .04}) {
                double[] topLead = bounds(ownLeadFaces, x, end - inset), topGlass = bounds(glassFaces, x, end - inset);
                double[] bottomLead = bounds(ownLeadFaces, x, end + inset), bottomGlass = bounds(glassFaces, x, end + inset);
                require(Double.isFinite(topLead[1]) && Math.abs(topLead[1] - (end - inset + Math.sqrt(2) * 1.25 / 32)) < EPS,
                        "Height-layer top crossbar is missing: " + facing + " " + half + " " + shape);
                require(!Double.isFinite(topGlass[1]) || topGlass[1] < topLead[1] - EPS,
                        "Coplanar glass hides the height-layer top crossbar: " + facing + " " + half + " " + shape);
                require(Double.isFinite(bottomLead[0]) && Math.abs(bottomLead[0] - (end + inset - Math.sqrt(2) * 1.25 / 32)) < EPS,
                        "Height-layer underside crossbar is missing: " + facing + " " + half + " " + shape);
                require(!Double.isFinite(bottomGlass[0]) || bottomGlass[0] > bottomLead[0] + EPS,
                        "Coplanar glass hides the height-layer underside crossbar: " + facing + " " + half + " " + shape);
                rays += 2;
            }
        }
        return rays;
    }

    private static List<Quad> nativeSource(Profile profile, Direction facing, BlockHalf half, Sprite glass, Sprite lead) {
        String path = profile == Profile.STANDARD ? "models/block/glazing/slope/glazing_framed_slope.json"
                : "models/block/glazing/shallow_slope/glazing_slope_shallow_" + (profile == Profile.SHALLOW_UPPER ? "upper" : "lower") + "_straight.json";
        JsonObject json = resource(path);
        JsonUnbakedModel model = JsonUnbakedModel.deserialize(json.toString());
        int yaw = facing == Direction.SOUTH ? 0 : facing == Direction.WEST ? 90 : facing == Direction.NORTH ? 180 : 270;
        ModelRotation rotation = ModelRotation.get(profile == Profile.STANDARD && half == BlockHalf.TOP ? 180 : 0, yaw);
        ModelBakeSettings settings = new ModelBakeSettings() {
            @Override public AffineTransformation getRotation() { return rotation.getRotation(); }
            @Override public boolean isUvLocked() { return true; }
        };
        List<BakedQuad> quads = new ArrayList<>();
        BakedQuadFactory factory = new BakedQuadFactory();
        for (var element : model.getElements()) for (var face : element.faces.entrySet()) {
            Sprite sprite = face.getValue().textureId.equals("#glass") ? glass : lead;
            quads.add(factory.bake(element.from, element.to, face.getValue(), sprite, face.getKey(), settings,
                    element.rotation, element.shade, new Identifier("erydon", "glazing_source_oracle")));
        }
        return baked(quads);
    }

    private static List<Quad> nativeFaces(List<GlazingSlopeGeometry.Face> faces, Sprite glass, Sprite lead) {
        List<Quad> result = new ArrayList<>();
        for (var face : faces) {
            Sprite sprite = face.texture().equals("glass") ? glass : lead;
            List<Point> points = face.vertices().stream().map(p -> new Point(p.x(), p.y(), p.z(), sprite.getFrameU(p.u()), sprite.getFrameV(p.v()))).toList();
            require(points.size() >= 3, "Native glazing emitted a sub-triangle polygon");
            if (points.size() == 4) result.add(new Quad(points.toArray(Point[]::new), sprite));
            else for (int i = 1; i < points.size() - 1; i++) result.add(new Quad(new Point[]{points.get(0), points.get(i), points.get(i + 1), points.get(i + 1)}, sprite));
        }
        return result;
    }

    @SuppressWarnings("removal")
    private static List<Quad> placed(GlazingSlopeBakedModel model, BlockState state, Sprite glass, Sprite lead) {
        var builder = IndigoRenderer.INSTANCE.meshBuilder();
        RenderContext context = (RenderContext) Proxy.newProxyInstance(RenderContext.class.getClassLoader(), new Class<?>[]{RenderContext.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getEmitter")) return builder.getEmitter();
                    throw new AssertionError("Glazing bypassed the real native emitter: " + method.getName());
                });
        ((FabricBakedModel) model).emitBlockQuads(null, state, BlockPos.ORIGIN, () -> Random.create(0), context);
        List<Quad> result = new ArrayList<>();
        builder.build().forEach(quad -> {
            require(quad.tag() == 0 || quad.tag() == 1, "Glazing mesh lost its glass/lead material tag");
            Sprite sprite = quad.tag() == 0 ? glass : lead;
            Point[] points = new Point[4];
            Vec3d[] normals = new Vec3d[4];
            for (int i = 0; i < 4; i++) {
                points[i] = new Point(quad.x(i), quad.y(i), quad.z(i), quad.u(i), quad.v(i));
                require(quad.hasNormal(i), "Glazing mesh lost the sloped vertex normal");
                normals[i] = new Vec3d(quad.normalX(i), quad.normalY(i), quad.normalZ(i));
                double length = normals[i].length();
                require(Double.isFinite(length) && length > .98 && length < 1.01, "Glazing replay corrupted its packed normal");
            }
            // Quantized normals must remain perpendicular to both independent facet directions.
            Vec3d first = position(points[1]).subtract(position(points[0]));
            Vec3d second = position(points[2]).subtract(position(points[0]));
            Vec3d winding = first.crossProduct(second);
            require(winding.lengthSquared() > 1e-20, "Glazing emitted a facet without a winding");
            for (Vec3d normal : normals) {
                require(Math.abs(normal.dotProduct(first.normalize())) <= Math.sqrt(3) / 127 + EPS
                                && Math.abs(normal.dotProduct(second.normalize())) <= Math.sqrt(3) / 127 + EPS,
                        "Glazing normal is not perpendicular to its real sloped facet");
                require(normal.dotProduct(winding) > 0, "Glazing normal points against its outward facet winding");
            }
            Vec3d finalWinding = second.crossProduct(position(points[3]).subtract(position(points[0])));
            require(finalWinding.lengthSquared() < 1e-20 || normals[3].dotProduct(finalWinding) > 0,
                    "Glazing quad folds against its triangle winding");
            result.add(new Quad(points, sprite, normals));
        });
        return result;
    }

    private static List<Quad> baked(List<BakedQuad> quads) {
        List<Quad> result = new ArrayList<>();
        for (BakedQuad quad : quads) {
            Point[] points = new Point[4]; Vec3d[] normals = new Vec3d[4]; int[] data = quad.getVertexData();
            for (int i = 0; i < 4; i++) {
                points[i] = new Point(value(data, i, 0), value(data, i, 1), value(data, i, 2), value(data, i, 4), value(data, i, 5));
                int normal = data[i * 8 + 7];
                normals[i] = new Vec3d((byte) normal / 127.0, (byte) (normal >>> 8) / 127.0, (byte) (normal >>> 16) / 127.0);
            }
            result.add(new Quad(points, quad.getSprite(), normals));
        }
        return result;
    }

    private static void compareNormals(List<Quad> preview, List<Quad> placed, BlockState state) {
        for (int q = 0; q < preview.size(); q++) for (int vertex = 0; vertex < 4; vertex++) {
            Quad shown = preview.get(q), emitted = placed.get(q);
            require(samePoint(shown.points[vertex], emitted.points[vertex]), "Preview reordered cached glazing vertices: " + state);
            Vec3d delta = shown.normals[vertex].subtract(emitted.normals[vertex]);
            require(Math.abs(delta.x) <= 1.0 / 127 + EPS && Math.abs(delta.y) <= 1.0 / 127 + EPS
                            && Math.abs(delta.z) <= 1.0 / 127 + EPS,
                    "Glazing preview lost the actual Fabric packed normal: " + state);
        }
    }

    private static Vec3d position(Point point) { return new Vec3d(point.x, point.y, point.z); }
    private static float value(int[] data, int vertex, int component) { return Float.intBitsToFloat(data[vertex * 8 + component]); }

    private static List<Triangle> triangles(List<Quad> quads) {
        List<Triangle> result = new ArrayList<>();
        for (Quad quad : quads) {
            result.add(new Triangle(quad.points[0], quad.points[1], quad.points[2]));
            result.add(new Triangle(quad.points[0], quad.points[2], quad.points[3]));
        }
        return result;
    }
    private static double[] bounds(List<Triangle> triangles, double x, double z) {
        double[] result = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (Triangle t : triangles) {
            double d = (t.b.z - t.c.z) * (t.a.x - t.c.x) + (t.c.x - t.b.x) * (t.a.z - t.c.z);
            if (Math.abs(d) < 1e-10) continue;
            double u = ((t.b.z - t.c.z) * (x - t.c.x) + (t.c.x - t.b.x) * (z - t.c.z)) / d;
            double v = ((t.c.z - t.a.z) * (x - t.c.x) + (t.a.x - t.c.x) * (z - t.c.z)) / d;
            if (u < -1e-8 || v < -1e-8 || u + v > 1 + 1e-8) continue;
            double y = u * t.a.y + v * t.b.y + (1 - u - v) * t.c.y;
            result[0] = Math.min(result[0], y); result[1] = Math.max(result[1], y);
        }
        return result;
    }
    private static double[] bounds(Iterable<Box> boxes, double x, double z) {
        double[] result = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (Box b : boxes) if (x > b.minX && x < b.maxX && z > b.minZ && z < b.maxZ) {
            result[0] = Math.min(result[0], b.minY); result[1] = Math.max(result[1], b.maxY);
        }
        return result;
    }
    private static void close(double[] expected, double[] actual, double tolerance, String label) {
        require(Double.isFinite(expected[0]) == Double.isFinite(actual[0]), label + " coverage differs");
        if (!Double.isFinite(expected[0])) return;
        require(Math.abs(expected[0] - actual[0]) <= tolerance && Math.abs(expected[1] - actual[1]) <= tolerance,
                label + " expected=" + Arrays.toString(expected) + " actual=" + Arrays.toString(actual));
    }
    private static void compareMeshes(List<Quad> expected, List<Quad> actual, String label) {
        require(expected.size() == actual.size(), label + " quad count " + expected.size() + " vs " + actual.size());
        List<Quad> unmatched = new ArrayList<>(actual);
        for (Quad quad : expected) {
            int found = -1;
            for (int i = 0; i < unmatched.size(); i++) if (sameQuad(quad, unmatched.get(i))) { found = i; break; }
            require(found >= 0, label + " missing source surface: " + Arrays.toString(quad.points));
            unmatched.remove(found);
        }
    }
    private static boolean sameQuad(Quad a, Quad b) {
        if (a.sprite != b.sprite) return false;
        boolean[] seen = new boolean[4];
        for (Point p : a.points) {
            boolean found = false;
            for (int j = 0; j < 4; j++) if (!seen[j] && samePoint(p, b.points[j])) { seen[j] = true; found = true; break; }
            if (!found) return false;
        }
        return true;
    }
    private static boolean samePoint(Point a, Point b) {
        return Math.abs(a.x - b.x) < EPS && Math.abs(a.y - b.y) < EPS && Math.abs(a.z - b.z) < EPS
                && Math.abs(a.u - b.u) < EPS && Math.abs(a.v - b.v) < EPS;
    }
    private static int distinct(Quad quad) {
        List<Point> unique = new ArrayList<>();
        for (Point p : quad.points) if (unique.stream().noneMatch(q -> Math.abs(p.x - q.x) + Math.abs(p.y - q.y) + Math.abs(p.z - q.z) < 1e-7)) unique.add(p);
        return unique.size();
    }
    private static double area(Quad q) {
        Point a = q.points[0], b = q.points[1], c = q.points[2];
        double ux = b.x - a.x, uy = b.y - a.y, uz = b.z - a.z, vx = c.x - a.x, vy = c.y - a.y, vz = c.z - a.z;
        return .5 * Math.sqrt(Math.pow(uy * vz - uz * vy, 2) + Math.pow(uz * vx - ux * vz, 2) + Math.pow(ux * vy - uy * vx, 2));
    }
    private static String form(Profile profile) { return profile == Profile.STANDARD ? "slope" : profile == Profile.SHALLOW_LOWER ? "shallow_slope_lower" : "shallow_slope_upper"; }
    private static JsonObject resource(String path) {
        var stream = GlazingSlopeMeshLaunchChecks.class.getResourceAsStream("/assets/erydon/" + path);
        require(stream != null, "Missing approved straight glazing source: " + path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) { return JsonParser.parseReader(reader).getAsJsonObject(); }
        catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }
    private static BakedModel wrapped(Sprite sprite) {
        return (BakedModel) Proxy.newProxyInstance(BakedModel.class.getClassLoader(), new Class<?>[]{BakedModel.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getParticleSprite" -> sprite; case "getTransformation" -> ModelTransformation.NONE;
                    case "getOverrides" -> ModelOverrideList.EMPTY; case "getQuads" -> List.of();
                    default -> method.getReturnType() == boolean.class ? false : null;
                });
    }
    private static SpriteContents contents(String slot) { return new SpriteContents(new Identifier("erydon", "glazing_probe_" + slot),
            new SpriteDimensions(16, 16), new NativeImage(16, 16, false), AnimationResourceMetadata.EMPTY); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private record Point(double x, double y, double z, double u, double v) {}
    private record Quad(Point[] points, Sprite sprite, Vec3d[] normals) {
        Quad(Point[] points, Sprite sprite) { this(points, sprite, null); }
    }
    private record Triangle(Point a, Point b, Point c) {}
    private static final class TestSprite extends Sprite {
        TestSprite(SpriteContents contents, int x, int y) { super(new Identifier("erydon", "glazing_probe_atlas"), contents, 64, 64, x, y); }
        @Override public float getAnimationFrameDelta() { return 0; }
    }
}
