package com.oliver.erydon.client.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oliver.erydon.block.ArchGothicBlock;
import com.oliver.erydon.block.ArchRomanesqueBlock;
import com.oliver.erydon.block.WideArchLayout;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.oliver.erydon.block.ArchRomanesqueBlock.*;

/** Real raw Gothic components, placed assembly, exterior coverage and downstream repeat/POM splitting. */
final class GothicWideArchTopLaunchChecks {
    private GothicWideArchTopLaunchChecks() { }

    static void run(ArchRomanesqueBlock gothic, ArchRomanesqueBlock strictClipControl,
                    Sprite sprite, Method layout) throws Exception {
        require(gothic.getClass() == ArchGothicBlock.class, "Wrong Gothic family under test");
        strictPomGhostBounds();
        if (RendererAccess.INSTANCE.getRenderer() == null) RendererAccess.INSTANCE.registerRenderer(IndigoRenderer.INSTANCE);
        Method materials = ArchRepeatCtmRenderer.class.getDeclaredMethod("ensureMaterials");
        materials.setAccessible(true);
        require((boolean) materials.invoke(null), "Missing test renderer materials");
        Method build = ArchRepeatCtmRenderer.class.getDeclaredMethod("buildGeometry",
                BlockState.class, Supplier.class, QuadEmitter.class);
        build.setAccessible(true);

        Map<Identifier, BakedModel> children = new HashMap<>();
        String blockPath = "aganite_arch_gothic";
        for (String suffix : ArchRomanesqueBakedModel.modelSuffixes(blockPath)) {
            children.put(ArchRomanesqueBakedModel.modelId(blockPath, suffix), rawComponent(suffix, sprite));
        }
        originalTopFaces(children.get(ArchRomanesqueBakedModel.modelId(blockPath, "top_large")), sprite);
        ArchRomanesqueBakedModel assembled = new ArchRomanesqueBakedModel(children.values().iterator().next());
        Field cache = ArchRomanesqueBakedModel.class.getDeclaredField("children");
        cache.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Block, ArchRomanesqueBakedModel.ChildModels> cached =
                (Map<Block, ArchRomanesqueBakedModel.ChildModels>) cache.get(assembled);
        cached.put(gothic, new ArchRomanesqueBakedModel.ChildModels(blockPath, children::get));

        // An independently closed copy of the original element volumes is the
        // roof oracle. It detects absent upward-sloped end faces as well as UP
        // faces, without assuming that the authored partial face list is closed.
        Map<Identifier, BakedModel> closed = new HashMap<>(children);
        for (String suffix : List.of("corner_medium", "side_large", "top_large")) {
            JsonObject document = rawDocument(suffix);
            document.addProperty("erydon_split_world_uv", true);
            for (var value : document.getAsJsonArray("elements")) for (Direction face : Direction.values()) {
                var faces = value.getAsJsonObject().getAsJsonObject("faces");
                if (!faces.has(face.getName())) {
                    JsonObject added = new JsonObject(); added.addProperty("texture", "#stone");
                    faces.add(face.getName(), added);
                }
            }
            closed.put(ArchRomanesqueBakedModel.modelId(blockPath, suffix), rawComponent(suffix, document, sprite));
        }
        ArchRomanesqueBakedModel solidOracle = new ArchRomanesqueBakedModel(closed.values().iterator().next());
        @SuppressWarnings("unchecked")
        Map<Block, ArchRomanesqueBakedModel.ChildModels> oracleCache =
                (Map<Block, ArchRomanesqueBakedModel.ChildModels>) cache.get(solidOracle);
        oracleCache.put(gothic, new ArchRomanesqueBakedModel.ChildModels(blockPath, closed::get));

        int crowns = 0, unchangedInternalCells = 0, retainedCaps = 0;
        for (int width = 4; width <= 6; width++) for (int height = 1; height <= 6; height++)
            for (Direction facing : Direction.Type.HORIZONTAL) for (boolean reflected : new boolean[]{false, true}) {
                Arrangement[] reference = height == 1
                        ? new Arrangement[]{Arrangement.TRIPLE_SINGLE_L, Arrangement.TOP_LARGE, Arrangement.TRIPLE_SINGLE_R}
                        : new Arrangement[]{Arrangement.TRIPLE_TOP_L, Arrangement.TOP_LARGE, Arrangement.TRIPLE_TOP_R};
                double expectedArea = 0;
                float authoredTop = 1;
                List<List<BakedQuad>> referenceCells = new ArrayList<>();
                List<List<float[][]>> solidRoofs = new ArrayList<>();
                for (Arrangement arrangement : reference) {
                    BlockState sourceState = gothic.getDefaultState().with(WIDTH, 3).with(FACING, Direction.NORTH)
                            .with(REFLECTED, reflected).with(ARRANGEMENT, arrangement);
                    List<BakedQuad> sourceCaps = assembled.getQuads(sourceState, null, Random.create(0))
                            .stream().filter(GothicWideArchTopLaunchChecks::crown).toList();
                    referenceCells.add(sourceCaps);
                    if (height == 1) solidRoofs.add(solidOracle.getQuads(sourceState, null, Random.create(0))
                            .stream().map(GothicWideArchTopLaunchChecks::points).toList());
                    for (BakedQuad quad : sourceCaps) {
                        expectedArea += area(quad) * width / 3.0;
                        authoredTop = Math.max(authoredTop, coordinate(quad, 0, 1));
                    }
                }
                require(authoredTop > 1, "Actual Gothic raw sources no longer reproduce the outset-cap case");
                double actualArea = 0, strictArea = 0;
                float actualTop = 1;
                List<List<BakedQuad>> topCells = new ArrayList<>();
                List<List<float[][]>> renderedRoofs = new ArrayList<>();
                for (int row = 0; row < height; row++) for (int x = 0; x < width; x++) {
                    Arrangement arrangement = (Arrangement) layout.invoke(gothic, width, height, x, row, StyleSet.BASE);
                    BlockState state = gothic.getDefaultState().with(WIDTH, width).with(FACING, facing)
                            .with(REFLECTED, reflected).with(ARRANGEMENT, arrangement);
                    if (!arrangement.isWide()) continue;
                    List<BakedQuad> actual = assembled.getQuads(state, null, Random.create(0));
                    for (BakedQuad quad : actual) for (int vertex = 0; vertex < 4; vertex++) {
                        float ownedAxis = coordinate(quad, vertex, facing.getAxis() == Direction.Axis.X ? 2 : 0);
                        require(ownedAxis >= 0 && ownedAxis <= 1 && coordinate(quad, vertex, 1) >= 0,
                                "Computed wide-arch intersection left its exact owner boundary: " + state);
                    }
                    BlockState control = strictClipControl.getDefaultState().with(WIDTH, width).with(FACING, facing)
                            .with(REFLECTED, reflected).with(ARRANGEMENT, arrangement);
                    // The other family retains strict clipping. Feed it the exact same Gothic
                    // components to reproduce the missing caps without duplicating the clipper.
                    List<BakedQuad> strict = WideArchQuads.create(control, child -> assembled.getQuads(
                            gothic.getDefaultState().with(WIDTH, 3).with(FACING, Direction.NORTH)
                                    .with(REFLECTED, reflected).with(ARRANGEMENT, child.get(ARRANGEMENT)),
                            null, Random.create(0)));
                    if (row != 0) {
                        sameMesh(strict, actual);
                        unchangedInternalCells++;
                        continue;
                    }
                    List<BakedQuad> caps = actual.stream().filter(GothicWideArchTopLaunchChecks::crown).toList();
                    topCells.add(caps);
                    for (BakedQuad quad : caps) {
                        require(outwardTop(quad), "Gothic cap has inward or degenerate winding");
                        actualArea += area(quad);
                        actualTop = Math.max(actualTop, coordinate(quad, 0, 1));
                        for (int vertex = 0; vertex < 4; vertex++) {
                            float px = coordinate(quad, vertex, 0), pz = coordinate(quad, vertex, 2);
                            require(Math.abs(coordinate(quad, vertex, 4) - sprite.getFrameU(px * 16)) < 1e-6
                                            && Math.abs(coordinate(quad, vertex, 5) - sprite.getFrameV(pz * 16)) < 1e-6,
                                    "Gothic top changed world-cell UV phase or left its sprite: " + state + " cell=" + x
                                            + " vertex=" + vertex + " position=" + px + "," + coordinate(quad, vertex, 1) + "," + pz
                                            + " actualUV=" + coordinate(quad, vertex, 4) + "," + coordinate(quad, vertex, 5)
                                            + " expectedUV=" + sprite.getFrameU(px * 16) + "," + sprite.getFrameV(pz * 16)
                                            + " sprite=" + sprite.getMinU() + "," + sprite.getMaxU() + "," + sprite.getMinV() + "," + sprite.getMaxV()
                                            + " vertices=" + Arrays.toString(quad.getVertexData()));
                        }
                    }
                    for (BakedQuad quad : strict) if (crown(quad)) strictArea += area(quad);
                    retainedCaps += caps.size();
                    downstream(build, caps, state, sprite);
                    if (height == 1) {
                        List<BakedQuad> roof = actual.stream().filter(quad -> upwardArea(points(quad)) > 1e-10).toList();
                        renderedRoofs.add(downstreamRoof(build, roof, state, sprite));
                    }
                }
                require(Math.abs(actualArea - expectedArea) < 3e-5,
                        "Gothic crown lost/duplicated raw top area: " + width + "x" + height + " " + facing
                                + " reflected=" + reflected + " " + actualArea + " != " + expectedArea);
                require(strictArea < expectedArea * 0.25, "Negative control no longer reproduces missing top faces");
                Arrangement first = (Arrangement) layout.invoke(gothic, width, height, 0, 0, StyleSet.BASE);
                float expectedTop = (float) (1 + (authoredTop - 1) * WideArchLayout.scaleY(first, width));
                require(Math.abs(actualTop - expectedTop) < 2e-6, "Authored crown height was flattened or moved");
                for (int x = 0; x < width; x++) for (float px : new float[]{0.25F, 0.5F, 0.75F})
                    for (float pz : new float[]{0.25F, 0.5F, 0.75F}) {
                        boolean covered = false;
                        for (BakedQuad quad : topCells.get(x)) if (covers(quad, px, pz, facing)) covered = true;
                        boolean sourceCovered = false;
                        float sourceX = (float) ((x + px) * 3.0 / width);
                        for (int cell = 0; cell < 3; cell++) for (BakedQuad quad : referenceCells.get(cell)) {
                            if (covers(quad, sourceX - cell, pz, Direction.NORTH)) sourceCovered = true;
                        }
                        require(covered == sourceCovered, "Gothic top coverage differs from actual raw crown at "
                                + width + "x" + height + " cell=" + x
                                + " facing=" + facing + " reflected=" + reflected + " sample=" + px + "," + pz);
                    }
                if (height == 1) for (int cell = 0; cell < width; cell++)
                    for (int sample = 0; sample < 32; sample++) for (float depth : new float[]{0.25F, 0.5F, 0.75F}) {
                        float x = (sample + 0.5F) / 32;
                        float sourceX = (float) ((cell + x) * 3.0 / width);
                        float expected = Float.NEGATIVE_INFINITY;
                        for (int sourceCell = 0; sourceCell < 3; sourceCell++) {
                            expected = Math.max(expected, roofHeight(solidRoofs.get(sourceCell),
                                    sourceX - sourceCell, depth, Direction.NORTH));
                        }
                        float actual = roofHeight(renderedRoofs.get(cell), x, depth, facing);
                        require(Float.isFinite(expected) && Math.abs(actual - expected) < 3e-5,
                                "Open Gothic short-crown roof over authored stone: width=" + width + " cell=" + cell
                                        + " facing=" + facing + " reflected=" + reflected + " sample=" + x + "," + depth
                                        + " actual=" + actual + " solid=" + expected);
                    }
                crowns++;
            }
        System.out.println("ERYDON_GOTHIC_WIDE_TOP_OK crowns=" + crowns + " retainedCaps=" + retainedCaps
                + " unchangedInternalCells=" + unchangedInternalCells + " negativeControl=strict-cell-clipping");
    }

    private static JsonObject rawDocument(String suffix) throws Exception {
        String path = "assets/erydon/authoring_models/block/arch/gothic/arch_gothic_" + suffix + ".json";
        try (var input = GothicWideArchTopLaunchChecks.class.getClassLoader().getResourceAsStream(path)) {
            require(input != null, "Missing actual Gothic source " + path);
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static BakedModel rawComponent(String suffix, Sprite sprite) throws Exception {
        return rawComponent(suffix, rawDocument(suffix), sprite);
    }

    private static BakedModel rawComponent(String suffix, JsonElement json, Sprite sprite) throws Exception {
        Class<?> dataClass = Class.forName(ErydonRawModelLoadingPlugin.class.getName() + "$RawModelData");
        Class<?> bakedClass = Class.forName(ErydonRawModelLoadingPlugin.class.getName() + "$RawBakedModel");
        Class<?> purposeClass = Class.forName(ErydonRawModelLoadingPlugin.class.getName() + "$BakePurpose");
        Method parse = dataClass.getDeclaredMethod("parse", String.class, Identifier.class, JsonElement.class);
        Method bake = bakedClass.getDeclaredMethod("bake", dataClass, Sprite.class, Sprite.class, Map.class,
                Identifier.class, String.class, purposeClass);
        parse.setAccessible(true);
        bake.setAccessible(true);
        Identifier id = new Identifier("erydon", "authoring_models/block/arch/gothic/arch_gothic_" + suffix + ".json");
        Object data = parse.invoke(null, "arch_gothic/" + suffix, id, json);
        Object baseline = Arrays.stream(purposeClass.getEnumConstants())
                .filter(value -> ((Enum<?>) value).name().equals("BASELINE")).findFirst().orElseThrow();
        return (BakedModel) bake.invoke(null, data, sprite, sprite, Map.of(), id, "gothic-top-regression", baseline);
    }

    private static void originalTopFaces(BakedModel current, Sprite sprite) throws Exception {
        JsonObject original = rawDocument("top_large");
        original.remove("erydon_split_world_uv");
        for (int index : new int[]{0, 1, 3, 4}) {
            var faces = original.getAsJsonArray("elements").get(index).getAsJsonObject().getAsJsonObject("faces");
            faces.remove("up"); faces.remove("south");
        }
        original.getAsJsonArray("elements").get(2).getAsJsonObject().getAsJsonObject("faces").remove("east");
        original.getAsJsonArray("elements").get(5).getAsJsonObject().getAsJsonObject("faces").remove("west");
        BakedModel before = rawComponent("top_large", original, sprite);
        Map<String, Integer> available = new HashMap<>();
        for (Direction face : new Direction[]{null, Direction.UP, Direction.DOWN, Direction.NORTH,
                Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            for (BakedQuad quad : current.getQuads(null, face, Random.create(0)))
                available.merge(face + Arrays.toString(quad.getVertexData()) + quad.getFace(), 1, Integer::sum);
            for (BakedQuad quad : before.getQuads(null, face, Random.create(0))) {
                String key = face + Arrays.toString(quad.getVertexData()) + quad.getFace();
                int count = available.getOrDefault(key, 0);
                require(count > 0, "World-cell splitting changed an existing Gothic face/UV/cull bucket");
                available.put(key, count - 1);
            }
        }
    }

    private static void downstream(Method build, List<BakedQuad> caps, BlockState state, Sprite sprite) throws Exception {
        BakedModel top = (BakedModel) Proxy.newProxyInstance(GothicWideArchTopLaunchChecks.class.getClassLoader(),
                new Class<?>[]{BakedModel.class, ArchQuadSources.Complete.class}, (proxy, method, args) -> {
                    if (args != null && args.length == 3 && method.getReturnType() == List.class)
                        return args[1] == null ? caps : List.of();
                    if (method.getReturnType() == Sprite.class) return sprite;
                    if (method.getReturnType() == boolean.class) return true;
                    throw new AssertionError("Unexpected crown model query " + method);
                });
        var renderer = new ArchRepeatCtmRenderer(top, "aganite", ArchRepeatCtmRenderer.Family.GOTHIC);
        List<?> templates = (List<?>) build.invoke(renderer, state, (Supplier<Random>) () -> Random.create(0),
                IndigoRenderer.INSTANCE.meshBuilder().getEmitter());
        double before = caps.stream().mapToDouble(GothicWideArchTopLaunchChecks::area).sum(), after = 0;
        for (Object template : templates) {
            require(field(template, "lightFace") == Direction.UP && (int) field(template, "offsetY") == 0,
                    "Top CTM moved to another layer");
            var vertices = (SpiralStairCtmGeometry.CellVertex[]) field(template, "vertices");
            float[][] points = new float[4][3];
            for (int i = 0; i < 4; i++) {
                var cell = vertices[i];
                var v = cell.vertex();
                require(v.y() >= 1 && cell.localS() >= 0 && cell.localS() <= 1
                                && cell.localT() >= 0 && cell.localT() <= 1,
                        "CTM/POM clipped a crown cap or generated unsafe atlas coordinates");
                points[i] = new float[]{v.x(), v.y(), v.z()};
            }
            after += triangle(points[0], points[1], points[2]) + triangle(points[0], points[2], points[3]);
        }
        require(Math.abs(before - after) < 1e-5,
                "Repeat/POM splitting lost Gothic top area: " + state + " " + before + " != " + after);
    }

    private static List<float[][]> downstreamRoof(Method build, List<BakedQuad> roof,
                                                   BlockState state, Sprite sprite) throws Exception {
        BakedModel source = (BakedModel) Proxy.newProxyInstance(GothicWideArchTopLaunchChecks.class.getClassLoader(),
                new Class<?>[]{BakedModel.class, ArchQuadSources.Complete.class}, (proxy, method, args) -> {
                    if (args != null && args.length == 3 && method.getReturnType() == List.class)
                        return args[1] == null ? roof : List.of();
                    if (method.getReturnType() == Sprite.class) return sprite;
                    if (method.getReturnType() == boolean.class) return true;
                    throw new AssertionError("Unexpected solid-roof query " + method);
                });
        var renderer = new ArchRepeatCtmRenderer(source, "aganite", ArchRepeatCtmRenderer.Family.GOTHIC);
        List<?> templates = (List<?>) build.invoke(renderer, state, (Supplier<Random>) () -> Random.create(0),
                IndigoRenderer.INSTANCE.meshBuilder().getEmitter());
        List<float[][]> result = new ArrayList<>();
        double before = roof.stream().mapToDouble(GothicWideArchTopLaunchChecks::area).sum(), after = 0;
        for (Object template : templates) {
            var vertices = (SpiralStairCtmGeometry.CellVertex[]) field(template, "vertices");
            float[][] points = new float[4][3];
            for (int i = 0; i < 4; i++) {
                var cell = vertices[i]; var v = cell.vertex();
                require(cell.localS() >= 0 && cell.localS() <= 1 && cell.localT() >= 0 && cell.localT() <= 1,
                        "Solid-roof CTM/POM mapping left the texture cell: " + state + " vertex=" + i
                                + " localUV=" + cell.localS() + "," + cell.localT()
                                + " position=" + v.x() + "," + v.y() + "," + v.z()
                                + " face=" + field(template, "lightFace") + " vertices=" + Arrays.toString(vertices));
                points[i] = new float[]{v.x(), v.y(), v.z()};
            }
            after += triangle(points[0], points[1], points[2]) + triangle(points[0], points[2], points[3]);
            result.add(points);
        }
        require(Math.abs(before - after) < 3e-5,
                "Repeat/POM splitting changed short-crown roof area: " + state + " " + before + " != " + after);
        return result;
    }

    private static float roofHeight(List<float[][]> polygons, float x, float z, Direction facing) {
        float highest = Float.NEGATIVE_INFINITY;
        for (float[][] polygon : polygons) {
            float[][] p = new float[4][3];
            for (int i = 0; i < 4; i++) {
                float px = polygon[i][0], pz = polygon[i][2];
                float[] horizontal = switch (facing) {
                    case EAST -> new float[]{pz, 1 - px}; case SOUTH -> new float[]{1 - px, 1 - pz};
                    case WEST -> new float[]{1 - pz, px}; default -> new float[]{px, pz};
                };
                p[i] = new float[]{horizontal[0], polygon[i][1], horizontal[1]};
            }
            for (int[] triangle : new int[][]{{0, 1, 2}, {0, 2, 3}}) {
                float[] a = p[triangle[0]], b = p[triangle[1]], c = p[triangle[2]];
                float denominator = (b[2] - c[2]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[2] - c[2]);
                if (denominator >= -1e-10) continue; // Only the outward upper envelope.
                float u = ((b[2] - c[2]) * (x - c[0]) + (c[0] - b[0]) * (z - c[2])) / denominator;
                float v = ((c[2] - a[2]) * (x - c[0]) + (a[0] - c[0]) * (z - c[2])) / denominator;
                if (u >= -1e-6 && v >= -1e-6 && u + v <= 1 + 1e-6)
                    highest = Math.max(highest, u * a[1] + v * b[1] + (1 - u - v) * c[1]);
            }
        }
        return highest;
    }

    private static float[][] points(BakedQuad quad) {
        float[][] points = new float[4][3];
        for (int i = 0; i < 4; i++) for (int axis = 0; axis < 3; axis++) points[i][axis] = coordinate(quad, i, axis);
        return points;
    }

    private static double upwardArea(float[][] p) {
        return (p[1][2] - p[0][2]) * (p[2][0] - p[0][0]) - (p[1][0] - p[0][0]) * (p[2][2] - p[0][2])
                + (p[2][2] - p[0][2]) * (p[3][0] - p[0][0]) - (p[2][0] - p[0][0]) * (p[3][2] - p[0][2]);
    }

    private static void strictPomGhostBounds() {
        // Captured from a real width6 reflected crown at the north-facing seam.
        // Three visible corners are inside the sprite; the invisible fourth
        // must be the exact missing rectangle corner without moving geometry.
        var firstVertex = new SpiralStairCtmGeometry.Vertex(0.9993705F, 0.88142866F, 0.0001500152F,
                -1, 0, false, 0, 0, 0);
        var secondVertex = new SpiralStairCtmGeometry.Vertex(1, 0.88142866F, 0.0001500152F,
                -1, 0, false, 0, 0, 0);
        var thirdVertex = new SpiralStairCtmGeometry.Vertex(0.99937046F, 0.88130754F, 0.00015001518F,
                -1, 0, false, 0, 0, 0);
        var first = new SpiralStairCtmGeometry.CellVertex(firstVertex, 0.99937046F, 0.88142866F);
        var second = new SpiralStairCtmGeometry.CellVertex(secondVertex, 1, 0.88142866F);
        var third = new SpiralStairCtmGeometry.CellVertex(thirdVertex, 0.99937046F, 0.88130754F);
        var ghost = ArchRepeatCtmRenderer.pomSafeTriangleGhost(first, second, third);
        require(ghost.vertex() == thirdVertex, "POM ghost changed the invisible duplicate position");
        require(ghost.localS() == 1 && ghost.localT() == third.localT(),
                "Float cancellation moved the missing POM corner outside its exact bounds");
        require(first.localS() == 0.99937046F && second.localS() == 1 && third.localS() == 0.99937046F
                        && first.localT() == 0.88142866F && second.localT() == 0.88142866F
                        && third.localT() == 0.88130754F,
                "POM ghost changed a visible source UV");
    }

    private static Object field(Object value, String name) throws Exception {
        Method method = value.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(value);
    }

    private static boolean crown(BakedQuad quad) {
        if (quad.getFace() != Direction.UP) return false;
        for (int i = 0; i < 4; i++) if (coordinate(quad, i, 1) < 1) return false;
        return true;
    }

    private static boolean covers(BakedQuad quad, float x, float z, Direction facing) {
        float[][] points = new float[4][2];
        for (int i = 0; i < 4; i++) {
            float px = coordinate(quad, i, 0), pz = coordinate(quad, i, 2);
            points[i] = switch (facing) {
                case EAST -> new float[]{pz, 1 - px}; case SOUTH -> new float[]{1 - px, 1 - pz};
                case WEST -> new float[]{1 - pz, px}; default -> new float[]{px, pz};
            };
        }
        return inside(points[0], points[1], points[2], x, z) || inside(points[0], points[2], points[3], x, z);
    }

    private static boolean inside(float[] a, float[] b, float[] c, float x, float z) {
        if (Math.abs(cross(a, b, c[0], c[1])) <= 1e-8) return false;
        float ab = cross(a, b, x, z), bc = cross(b, c, x, z), ca = cross(c, a, x, z);
        return Math.min(ab, Math.min(bc, ca)) >= -1e-7 || Math.max(ab, Math.max(bc, ca)) <= 1e-7;
    }

    private static float cross(float[] a, float[] b, float x, float z) {
        return (b[0] - a[0]) * (z - a[1]) - (b[1] - a[1]) * (x - a[0]);
    }

    private static float coordinate(BakedQuad quad, int vertex, int axis) {
        return Float.intBitsToFloat(quad.getVertexData()[vertex * 8 + axis]);
    }

    private static double area(BakedQuad quad) {
        float[][] points = new float[4][3];
        for (int i = 0; i < 4; i++) for (int axis = 0; axis < 3; axis++) points[i][axis] = coordinate(quad, i, axis);
        return triangle(points[0], points[1], points[2]) + triangle(points[0], points[2], points[3]);
    }

    private static boolean outwardTop(BakedQuad quad) {
        float[][] points = new float[4][2];
        for (int i = 0; i < 4; i++) points[i] = new float[]{coordinate(quad, i, 0), coordinate(quad, i, 2)};
        float first = -cross(points[0], points[1], points[2][0], points[2][1]);
        float second = -cross(points[0], points[2], points[3][0], points[3][1]);
        return first >= 0 && second >= 0 && first + second > 0;
    }

    private static double triangle(float[] a, float[] b, float[] c) {
        double x = (b[1] - a[1]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[1] - a[1]);
        double y = (b[2] - a[2]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[2] - a[2]);
        double z = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
        return Math.sqrt(x * x + y * y + z * z) / 2;
    }

    private static void sameMesh(List<BakedQuad> expected, List<BakedQuad> actual) {
        require(expected.size() == actual.size(), "Internal row acquired/lost faces");
        for (int i = 0; i < expected.size(); i++) {
            BakedQuad a = expected.get(i), b = actual.get(i);
            require(Arrays.equals(a.getVertexData(), b.getVertexData()) && a.getFace() == b.getFace()
                            && a.getColorIndex() == b.getColorIndex() && a.getSprite() == b.getSprite() && a.hasShade() == b.hasShade(),
                    "Internal seam geometry, UVs or metadata changed");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
