package com.oliver.erydon.client.model;

import com.google.gson.JsonParser;
import com.oliver.erydon.block.ArchRomanesqueBlock;
import net.fabricmc.fabric.api.renderer.v1.mesh.MeshBuilder;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BakedQuadFactory;
import net.minecraft.client.render.model.ModelRotation;
import net.minecraft.client.render.model.json.JsonUnbakedModel;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.oliver.erydon.block.ArchRomanesqueBlock.*;

/** Actual component assembly, complete-stream parity, renderer emission and conservative neighbour checks. */
final class ArchBoundaryVisibilityLaunchChecks {
    private ArchBoundaryVisibilityLaunchChecks() {}

    static void run(ArchRomanesqueBlock[] families, Sprite sprite) throws Exception {
        boundaryProof(sprite);
        neighbourProof();
        int cases = 0, authoredFaces = 0;
        int[] templates = new int[2], eligible = new int[2];
        Method build = ArchRepeatCtmRenderer.class.getDeclaredMethod("buildGeometry",
                BlockState.class, Supplier.class, QuadEmitter.class);
        build.setAccessible(true);
        for (int family = 1; family <= 2; family++) {
            String style = family == 1 ? "modern" : "gothic";
            Map<String, BakedModel> children = new HashMap<>();
            String blockPath = "aganite_arch_" + style;
            for (String suffix : ArchRomanesqueBakedModel.modelSuffixes(blockPath)) {
                children.put(suffix, component(style, suffix, sprite));
            }
            BakedModel empty = model(Map.of(), List.of(), sprite);
            ArchRomanesqueBakedModel assembled = new ArchRomanesqueBakedModel(empty);
            Field childrenField = ArchRomanesqueBakedModel.class.getDeclaredField("children");
            childrenField.setAccessible(true);
            @SuppressWarnings("unchecked") Map<Object, Object> cache = (Map<Object, Object>) childrenField.get(assembled);
            cache.put(families[family], new ArchRomanesqueBakedModel.ChildModels(blockPath, id -> {
                String prefix = blockPath + "_";
                String name = id.getPath().substring(id.getPath().lastIndexOf('/') + 1);
                return children.get(name.substring(prefix.length()));
            }));
            ArchRepeatCtmRenderer.Family rendererFamily = family == 1
                    ? ArchRepeatCtmRenderer.Family.MODERN : ArchRepeatCtmRenderer.Family.GOTHIC;
            ArchRepeatCtmRenderer renderer = new ArchRepeatCtmRenderer(assembled, "aganite", rendererFamily);
            require(ArchQuadSources.canCullThroughSynapheia(renderer, false), "Direct UV-only wrapper not recognized");
            require(!ArchQuadSources.canCullThroughSynapheia(renderer, true), "External transform was trusted");
            require(!ArchQuadSources.canCullThroughSynapheia(new Transparent(renderer), false), "Intermediate wrapper was trusted");

            for (int width = 1; width <= 6; width++) for (Direction facing : Direction.Type.HORIZONTAL)
                for (boolean reflected : new boolean[]{false, true}) for (Arrangement arrangement : Arrangement.values()) {
                    if (arrangement.isWide() && width <= 3 || !arrangement.isWide() && width > 3) continue;
                    BlockState state = families[family].getDefaultState().with(WIDTH, width).with(FACING, facing)
                            .with(REFLECTED, reflected).with(ARRANGEMENT, arrangement);
                    if (arrangement.isWide()) {
                        require(assembled.sourceQuads(state, Random.create(0)) == null, "Wide arch escaped conservative fallback");
                        continue;
                    }
                    List<BakedQuad> baseline = assembled.getQuads(state, null, Random.create(0));
                    ArchQuadSources.Sources sources = assembled.sourceQuads(state, Random.create(0));
                    require(sources.size() == baseline.size(), "Source metadata changed component count");
                    for (int i = 0; i < baseline.size(); i++) {
                        sameQuad(baseline.get(i), sources.get(i));
                        if (sources.cullFace(i) != null) authoredFaces++;
                    }
                    // Narrow widths share the same component stream. Exercise the costly CTM
                    // decomposition once per arrangement/facing/mirror, after checking all widths above.
                    if (width == 1) {
                        BakedModel legacy = complete(assembled, sprite);
                        List<?> before = build(build, new ArchRepeatCtmRenderer(legacy, "aganite", rendererFamily), state);
                        List<?> after = build(build, renderer, state);
                        require(signature(before, true).equals(signature(after, true)),
                                "Private provenance changed emitted geometry/UV/light/normal/material/cull/tint/order: " + state);
                        for (Object template : after) {
                            Direction hiddenFace = (Direction) field(template, "hiddenBoundaryFace");
                            if (hiddenFace != null) {
                                var vertices = (SpiralStairCtmGeometry.CellVertex[]) field(template, "vertices");
                                for (var cell : vertices) {
                                    var vertex = cell.vertex();
                                    require(vertex.x() >= 0 && vertex.x() <= 1 && vertex.y() >= 0 && vertex.y() <= 1
                                                    && vertex.z() >= 0 && vertex.z() <= 1, "Clipped cull candidate left its owner cell");
                                    float coordinate = switch (hiddenFace.getAxis()) {
                                        case X -> vertex.x(); case Y -> vertex.y(); case Z -> vertex.z();
                                    };
                                    require(coordinate == (hiddenFace.getDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0),
                                            "Clipping/POM interpolation moved a cull candidate off its exact boundary");
                                }
                            }
                        }
                        if (!reflected && facing == Direction.NORTH) {
                            templates[family - 1] += after.size();
                            eligible[family - 1] += (int) after.stream().filter(t -> field(t, "hiddenBoundaryFace") != null).count();
                        }
                    }
                    cases++;
                }
            emittedProof(renderer, families[family], sprite);
        }
        require(authoredFaces > 0 && eligible[0] > 0, "Vacuous authored cull proof");
        System.out.println("ERYDON_ARCH_BOUNDARY_VISIBILITY_OK states=" + cases + " sourceCullFaces=" + authoredFaces
                + " modernTemplates=" + templates[0] + " modernEligible=" + eligible[0]
                + " gothicTemplates=" + templates[1] + " gothicEligible=" + eligible[1]
                + " retainedStream=exact externalTransforms=baseline wide=baseline");
        ArchRepeatCtmRenderer.clearGeometryCache();
    }

    private static void emittedProof(ArchRepeatCtmRenderer renderer, ArchRomanesqueBlock block, Sprite sprite) throws Exception {
        Field spritesField = ErydonCtmService.class.getDeclaredField("repeatSpritesBySet");
        spritesField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Sprite[]> cache = (Map<String, Sprite[]>) spritesField.get(ErydonCtmService.get(null));
        Sprite[] sprites = new Sprite[36]; Arrays.fill(sprites, sprite);
        Sprite[] previous = cache.put("aganite", sprites);
        try {
            BlockState state = block.getDefaultState().with(WIDTH, 1).with(ARRANGEMENT, Arrangement.SMALL_BODY);
            for (Direction facing : Direction.Type.HORIZONTAL) for (boolean reflected : new boolean[]{false, true}) {
                BlockState oriented = state.with(FACING, facing).with(REFLECTED, reflected);
                ArchRepeatCtmRenderer.clearGeometryCache();
                List<Object> air = emit(renderer, oriented, Blocks.AIR.getDefaultState(), false, false);
                List<Object> stone = emit(renderer, oriented, Blocks.STONE.getDefaultState(), false, false);
                require(!air.isEmpty() && stone.size() < air.size(), "Opaque neighbours failed to reject boundary surfaces");
                List<Object> external = emit(renderer, oriented, Blocks.STONE.getDefaultState(), true, false);
                require(air.equals(external), "External transform changed original emission or queried neighbours");
                List<Object> knownUv = emit(renderer, oriented, Blocks.STONE.getDefaultState(), true, true);
                require(stone.equals(knownUv), "Known sole UV transform did not match ordinary safe culling");
                int cursor = 0;
                for (Object surviving : stone) {
                    while (cursor < air.size() && !air.get(cursor).equals(surviving)) cursor++;
                    require(cursor < air.size(), "Retained quad changed attributes or ordering"); cursor++;
                }
                for (BlockState nonOpaque : List.of(Blocks.GLASS.getDefaultState(), Blocks.OAK_LEAVES.getDefaultState(),
                        Blocks.STONE_SLAB.getDefaultState(), Blocks.AIR.getDefaultState())) {
                    require(air.equals(emit(renderer, oriented, nonOpaque, false, false)), "Partial or nonopaque neighbour removed a face");
                }
            }
        } finally {
            if (previous == null) cache.remove("aganite"); else cache.put("aganite", previous);
        }
    }

    private static List<Object> emit(ArchRepeatCtmRenderer renderer, BlockState state, BlockState neighbour,
                                     boolean externalTransform, boolean knownUvOnly) {
        MeshBuilder mesh = IndigoRenderer.INSTANCE.meshBuilder();
        RenderContext context = (RenderContext) Proxy.newProxyInstance(ArchBoundaryVisibilityLaunchChecks.class.getClassLoader(),
                new Class<?>[]{RenderContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getEmitter" -> mesh.getEmitter();
                    case "hasTransform" -> externalTransform;
                    case "isFaceCulled" -> false;
                    default -> throw new AssertionError("Unexpected context call " + method);
                });
        BlockRenderView view = view(neighbour, externalTransform && !knownUvOnly);
        if (knownUvOnly) renderer.emitBlockQuads(view, state, new BlockPos(-16, 73, 15), () -> Random.create(0), context, true);
        else renderer.emitBlockQuads(view, state, new BlockPos(-16, 73, 15), () -> Random.create(0), context);
        List<Object> output = new ArrayList<>();
        mesh.build().forEach(quad -> {
            List<Object> words = new ArrayList<>();
            words.add(quad.cullFace()); words.add(quad.nominalFace()); words.add(quad.lightFace());
            words.add(quad.material()); words.add(quad.colorIndex()); words.add(quad.tag());
            for (int i = 0; i < 4; i++) {
                words.add(Float.floatToRawIntBits(quad.x(i))); words.add(Float.floatToRawIntBits(quad.y(i)));
                words.add(Float.floatToRawIntBits(quad.z(i))); words.add(Float.floatToRawIntBits(quad.u(i)));
                words.add(Float.floatToRawIntBits(quad.v(i))); words.add(quad.color(i)); words.add(quad.lightmap(i));
                words.add(quad.hasNormal(i));
                if (quad.hasNormal(i)) { words.add(Float.floatToRawIntBits(quad.normalX(i)));
                    words.add(Float.floatToRawIntBits(quad.normalY(i))); words.add(Float.floatToRawIntBits(quad.normalZ(i))); }
            }
            output.add(words);
        });
        return output;
    }

    private static void boundaryProof(Sprite sprite) {
        QuadEmitter decoder = IndigoRenderer.INSTANCE.meshBuilder().getEmitter();
        for (Direction face : Direction.values()) {
            BakedQuad quad = boxFace(sprite, face);
            decoder.fromVanilla(quad, IndigoRenderer.INSTANCE.materialFinder().find(), null);
            require(ArchBoundaryVisibility.eligibleFace(decoder, face) == face, "Exact outward boundary rejected");
            require(ArchBoundaryVisibility.eligibleFace(decoder, null) == null, "Inferred unauthored cull face");
            require(ArchBoundaryVisibility.eligibleFace(decoder, face.getOpposite()) == null, "Opposite boundary trusted");
            for (float displacement : new float[]{-.001F, .001F, -0.0000001F, 0.0000001F, Float.NaN}) {
                decoder.fromVanilla(quad, IndigoRenderer.INSTANCE.materialFinder().find(), null);
                for (int i = 0; i < 4; i++) decoder.pos(i,
                        decoder.x(i) + face.getOffsetX() * displacement,
                        decoder.y(i) + face.getOffsetY() * displacement,
                        decoder.z(i) + face.getOffsetZ() * displacement);
                require(ArchBoundaryVisibility.eligibleFace(decoder, face) == null, "Inset/outset/nonfinite boundary trusted");
            }
            decoder.fromVanilla(quad, IndigoRenderer.INSTANCE.materialFinder().find(), null);
            int[] reversed = quad.getVertexData().clone();
            for (int i = 0; i < 4; i++) System.arraycopy(quad.getVertexData(), (3 - i) * 8, reversed, i * 8, 8);
            decoder.fromVanilla(new BakedQuad(reversed, -1, face, sprite, true), IndigoRenderer.INSTANCE.materialFinder().find(), null);
            require(ArchBoundaryVisibility.eligibleFace(decoder, face) == null, "Inward surface trusted");
        }
    }

    private static void neighbourProof() {
        BlockPos pos = new BlockPos(16, 73, -16);
        for (int mask = 0; mask < 64; mask++) {
            require(ArchBoundaryVisibility.opaqueNeighbourMask(view(Blocks.STONE.getDefaultState(), false), pos, mask) == mask,
                    "Opaque mask lost direction");
            for (BlockState neighbour : List.of(Blocks.GLASS.getDefaultState(), Blocks.OAK_LEAVES.getDefaultState(),
                    Blocks.STONE_SLAB.getDefaultState(), Blocks.AIR.getDefaultState())) {
                require(ArchBoundaryVisibility.opaqueNeighbourMask(view(neighbour, false), pos, mask) == 0,
                        "Nonopaque or partial neighbour was trusted");
            }
        }
        require(ArchBoundaryVisibility.opaqueNeighbourMask(view(Blocks.STONE.getDefaultState(), true), pos, 0) == 0,
                "Empty eligible mask queried world");
    }

    private static BlockRenderView view(BlockState neighbour, boolean forbidReads) {
        return (BlockRenderView) Proxy.newProxyInstance(ArchBoundaryVisibilityLaunchChecks.class.getClassLoader(),
                new Class<?>[]{BlockRenderView.class}, (proxy, method, args) -> {
                    require(!forbidReads, "Unknown transform performed unsafe neighbour reads");
                    if (method.getName().equals("getBlockState")) return neighbour;
                    throw new AssertionError("Unexpected world query " + method);
                });
    }

    private static BakedModel component(String style, String suffix, Sprite sprite) throws Exception {
        String path = "assets/erydon/" + (style.equals("gothic") ? "authoring_models/" : "models/")
                + "block/arch/" + style + "/arch_" + style + "_" + suffix + ".json";
        Map<Direction, List<BakedQuad>> culled = new EnumMap<>(Direction.class);
        List<BakedQuad> unculled = new ArrayList<>();
        try (var input = ArchBoundaryVisibilityLaunchChecks.class.getClassLoader().getResourceAsStream(path)) {
            require(input != null, "Missing component " + path);
            var root = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            if (style.equals("gothic")) {
                for (var surface : ErydonRawModelLoadingPlugin.surfaceSnapshots("arch/gothic/" + suffix,
                        new Identifier("erydon", "arch/gothic/" + suffix), root)) {
                    Direction face = Direction.byName(surface.faceDirection());
                    int[] data = new int[32];
                    for (int i = 0; i < 4; i++) {
                        for (int a = 0; a < 3; a++) data[i * 8 + a] = Float.floatToRawIntBits(surface.vertexPositions()[i][a]);
                        data[i * 8 + 3] = -1;
                        data[i * 8 + 4] = Float.floatToRawIntBits(sprite.getFrameU(surface.normalizedUvs()[i][0] * 16));
                        data[i * 8 + 5] = Float.floatToRawIntBits(sprite.getFrameV(surface.normalizedUvs()[i][1] * 16));
                    }
                    addBucket(culled, unculled, surface.cullFace() == null ? null : Direction.byName(surface.cullFace()),
                            new BakedQuad(data, -1, face, sprite, true));
                }
            } else {
                JsonUnbakedModel model = JsonUnbakedModel.deserialize(root.toString());
                BakedQuadFactory factory = new BakedQuadFactory();
                for (var element : model.getElements()) for (var entry : element.faces.entrySet()) {
                    addBucket(culled, unculled, entry.getValue().cullFace,
                            factory.bake(element.from, element.to, entry.getValue(), sprite, entry.getKey(),
                                    ModelRotation.X0_Y0, element.rotation, element.shade, new Identifier("erydon", "arch/" + suffix)));
                }
            }
        }
        return model(culled, unculled, sprite);
    }

    private static BakedQuad boxFace(Sprite sprite, Direction face) {
        String json = "{\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{\""
                + face.getName() + "\":{\"texture\":\"#stone\"}}}]}";
        var element = JsonUnbakedModel.deserialize(json).getElements().get(0);
        return new BakedQuadFactory().bake(element.from, element.to, element.faces.get(face), sprite, face,
                ModelRotation.X0_Y0, element.rotation, element.shade, new Identifier("erydon", "arch_boundary_fixture"));
    }

    private static void addBucket(Map<Direction, List<BakedQuad>> culled, List<BakedQuad> unculled,
                                   Direction face, BakedQuad quad) {
        (face == null ? unculled : culled.computeIfAbsent(face, ignored -> new ArrayList<>())).add(quad);
    }

    private static BakedModel model(Map<Direction, List<BakedQuad>> buckets, List<BakedQuad> unculled, Sprite sprite) {
        return (BakedModel) Proxy.newProxyInstance(ArchBoundaryVisibilityLaunchChecks.class.getClassLoader(),
                new Class<?>[]{BakedModel.class}, (proxy, method, args) -> {
                    if (args != null && args.length == 3 && method.getReturnType() == List.class)
                        return args[1] == null ? unculled : buckets.getOrDefault(args[1], List.of());
                    if (method.getReturnType() == Sprite.class) return sprite;
                    if (method.getReturnType() == boolean.class) return true;
                    throw new AssertionError("Unexpected model query " + method);
                });
    }

    private static BakedModel complete(BakedModel assembled, Sprite sprite) {
        return (BakedModel) Proxy.newProxyInstance(ArchBoundaryVisibilityLaunchChecks.class.getClassLoader(),
                new Class<?>[]{BakedModel.class, ArchQuadSources.Complete.class}, (proxy, method, args) -> {
                    if (args != null && args.length == 3 && method.getReturnType() == List.class)
                        return assembled.getQuads((BlockState) args[0], (Direction) args[1], (Random) args[2]);
                    if (method.getReturnType() == Sprite.class) return sprite;
                    if (method.getReturnType() == boolean.class) return true;
                    throw new AssertionError("Unexpected complete-model query " + method);
                });
    }

    private static List<?> build(Method build, ArchRepeatCtmRenderer renderer, BlockState state) throws Exception {
        return (List<?>) build.invoke(renderer, state, (Supplier<Random>) () -> Random.create(0), IndigoRenderer.INSTANCE.meshBuilder().getEmitter());
    }

    private static Object field(Object value, String name) {
        try { var method = value.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static Object signature(Object value, boolean omitPrivateVisibility) throws Exception {
        if (value instanceof Float number) return Float.floatToRawIntBits(number);
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(); for (Object item : list) result.add(signature(item, omitPrivateVisibility)); return result;
        }
        if (value instanceof Object[] array) return signature(Arrays.asList(array), omitPrivateVisibility);
        if (value != null && value.getClass().isRecord()) {
            List<Object> result = new ArrayList<>();
            for (var component : value.getClass().getRecordComponents()) {
                if (omitPrivateVisibility && component.getName().equals("hiddenBoundaryFace")) continue;
                var accessor = component.getAccessor(); accessor.setAccessible(true);
                result.add(signature(accessor.invoke(value), omitPrivateVisibility));
            }
            return result;
        }
        return value;
    }

    private static void sameQuad(BakedQuad a, BakedQuad b) {
        require(Arrays.equals(a.getVertexData(), b.getVertexData()) && a.getFace() == b.getFace()
                && a.getSprite() == b.getSprite() && a.getColorIndex() == b.getColorIndex() && a.hasShade() == b.hasShade(),
                "Metadata capture changed complete quad stream");
    }

    private static final class Transparent extends ForwardingBakedModel {
        Transparent(BakedModel model) { wrapped = model; }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
