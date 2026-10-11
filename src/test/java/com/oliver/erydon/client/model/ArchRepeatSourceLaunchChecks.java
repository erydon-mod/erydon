package com.oliver.erydon.client.model;

import com.oliver.erydon.block.ArchRomanesqueBlock;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import static com.oliver.erydon.block.ArchRomanesqueBlock.*;

/** Exercises the real vanilla decoder, CTM splitting and source-contract classifier without a window. */
final class ArchRepeatSourceLaunchChecks {
    private ArchRepeatSourceLaunchChecks() {}

    static void run(ArchRomanesqueBlock[] families, Sprite sprite) throws Exception {
        if (RendererAccess.INSTANCE.getRenderer() == null)
            RendererAccess.INSTANCE.registerRenderer(IndigoRenderer.INSTANCE);
        Method materials = ArchRepeatCtmRenderer.class.getDeclaredMethod("ensureMaterials");
        materials.setAccessible(true);
        require((boolean) materials.invoke(null), "Arch materials could not be initialized");
        Method build = ArchRepeatCtmRenderer.class.getDeclaredMethod("buildGeometry",
                BlockState.class, Supplier.class, QuadEmitter.class);
        build.setAccessible(true);

        classifier(families[1], sprite);
        int cases = 0;
        for (int family = 1; family <= 2; family++) {
            var rendererFamily = family == 1 ? ArchRepeatCtmRenderer.Family.MODERN : ArchRepeatCtmRenderer.Family.GOTHIC;
            for (int width = 1; width <= 3; width++) for (Direction facing : Direction.Type.HORIZONTAL)
                for (boolean reflected : new boolean[]{false, true}) for (boolean ao : new boolean[]{false, true}) {
                    BlockState state = families[family].getDefaultState().with(WIDTH, width).with(FACING, facing)
                            .with(REFLECTED, reflected);
                    int[] calls = new int[7];
                    BakedModel complete = fixture(sprite, state, ao, true, false, 2, calls);
                    BakedModel legacy = fixture(sprite, state, ao, false, false, 2, new int[7]);
                    BakedModel prefix = fixture(sprite, state, ao, false, true, 2, new int[7]);
                    List<?> actual = build(build, new Transparent(complete), state, rendererFamily);
                    List<?> old = build(build, legacy, state, rendererFamily);
                    List<?> repeated = build(build, prefix, state, rendererFamily);
                    require(repeated.size() > 0 && actual.size() > 0, "Vacuous collection fixture");
                    require(old.size() == repeated.size() + actual.size(), "Unexpected duplicate collection size");
                    require(signature(actual).equals(signature(old.subList(repeated.size(), old.size()))),
                            "Retained CTM/POM stream changed geometry, UV projection, normals, AO, cull, tint or order");
                    require(calls[6] == 1, "Complete assembled model was not queried exactly once");
                    for (int index = 0; index < 6; index++) require(calls[index] == 0, "Directional bucket queried twice");

                    long overlays = actual.stream().filter(template -> tint(template) == 17).count();
                    List<?> single = build(build, fixture(sprite, state, ao, true, false, 1, new int[7]), state, rendererFamily);
                    require(overlays > 0 && overlays == 2 * single.stream().filter(template -> tint(template) == 17).count(),
                            "Legitimate coincident overlays were deduplicated");

                    // An unknown wrapper can change the bucket contract; preserve all seven calls and their cull tags.
                    int[] unknownCalls = new int[7];
                    BakedModel unknown = new OverrideQuads(fixture(sprite, state, ao, true, false, 2, unknownCalls));
                    require(signature(build(build, unknown, state, rendererFamily)).equals(signature(old)),
                            "Custom wrapper fallback changed the original seven-bucket stream");
                    for (int count : unknownCalls) require(count == 1, "Generic fallback did not query every bucket once");
                    cases++;
                }
        }
        System.out.println("ERYDON_ARCH_REPEAT_SOURCES_OK cases=" + cases
                + " retainedStream=exact coincidentLayers=preserved genericFallback=seven-buckets");
    }

    private static void classifier(ArchRomanesqueBlock modern, Sprite sprite) throws Exception {
        BlockState state = modern.getDefaultState();
        BakedModel child = fixture(sprite, state, true, false, false, 2, new int[7]);
        BakedModel assembled = new ArchRomanesqueBakedModel(child);
        require(ArchQuadSources.hasCompleteNullBucket(assembled), "Real arch family contract was not recognized");
        require(ArchQuadSources.hasCompleteNullBucket(new Transparent(new Transparent(assembled))),
                "Inherited forwarding implementation lost the complete contract");
        require(!ArchQuadSources.hasCompleteNullBucket(child), "Ordinary buckets were mistaken for complete geometry");
        require(!ArchQuadSources.hasCompleteNullBucket(new OverrideQuads(assembled)),
                "Custom getQuads implementation was treated as transparent");
        require(!ArchQuadSources.hasCompleteNullBucket(new OverrideWrapped(assembled)),
                "Custom wrapper lookup was treated as transparent");
        Transparent cyclic = new Transparent(null);
        cyclic.cycle();
        require(!ArchQuadSources.hasCompleteNullBucket(cyclic), "Cyclic wrapper did not fall back safely");

        if (FabricLoader.getInstance().isModLoaded("continuity")) {
            // This is the actual post-bake chain used by the Modern CTM plugin.
            Class<?> type = Class.forName("me.pepperbell.continuity.client.model.CtmBakedModel");
            BakedModel ctm = (BakedModel) type.getConstructor(BakedModel.class, BlockState.class).newInstance(assembled, state);
            var renderer = new ArchRepeatCtmRenderer(ctm, "aganite", ArchRepeatCtmRenderer.Family.MODERN);
            var first = ArchRepeatCtmRenderer.class.getDeclaredField("firstSourceFace");
            first.setAccessible(true);
            require(first.getInt(renderer) == 6, "Real Modern/Continuity chain would retain duplicate collection");
        }
    }

    private static List<?> build(Method build, BakedModel source, BlockState state,
                                  ArchRepeatCtmRenderer.Family family) throws Exception {
        var renderer = new ArchRepeatCtmRenderer(source, "aganite", family);
        Supplier<Random> random = () -> Random.create(0);
        return (List<?>) build.invoke(renderer, state, random, IndigoRenderer.INSTANCE.meshBuilder().getEmitter());
    }

    private static BakedModel fixture(Sprite sprite, BlockState state, boolean ao, boolean completeContract,
                                      boolean prefixOnly, int overlayCount, int[] calls) {
        BakedQuad overlay = surface(sprite, Direction.SOUTH, 17);
        List<BakedQuad> overlays = overlayCount == 2 ? List.of(overlay, overlay) : List.of(overlay);
        List<BakedQuad> culled = Arrays.stream(Direction.values()).map(face -> surface(sprite, face, face.ordinal())).toList();
        BakedModel raw = (BakedModel) Proxy.newProxyInstance(ArchRepeatSourceLaunchChecks.class.getClassLoader(),
                new Class<?>[]{BakedModel.class}, (proxy, method, args) -> {
                    if (args != null && args.length == 3 && method.getReturnType() == List.class) {
                        Direction face = (Direction) args[1];
                        return face == null ? overlays : List.of(culled.get(face.ordinal()));
                    }
                    throw new AssertionError("Unexpected raw fixture query: " + method);
                });
        Class<?>[] interfaces = completeContract ? new Class<?>[]{BakedModel.class, ArchQuadSources.Complete.class}
                : new Class<?>[]{BakedModel.class};
        return (BakedModel) Proxy.newProxyInstance(ArchRepeatSourceLaunchChecks.class.getClassLoader(), interfaces,
                (proxy, method, args) -> {
                    if (args != null && args.length == 3 && method.getReturnType() == List.class) {
                        Direction face = (Direction) args[1];
                        calls[face == null ? 6 : face.ordinal()]++;
                        if (prefixOnly && face == null) return List.of();
                        Direction facing = state.get(FACING);
                        var reflection = state.get(REFLECTED) ? ArchHorizontalReflection.forState(facing, false) : null;
                        Direction sourceFace = reflection == null ? face : reflection.face(face);
                        int rotation = switch (facing) { case EAST -> 90; case SOUTH -> 180; case WEST -> 270; default -> 0; };
                        List<BakedQuad> quads = AxiomFallbackQuads.collect(raw, rotation, sourceFace, (Random) args[2]);
                        if (reflection != null) quads.replaceAll(reflection::reflect);
                        return quads;
                    }
                    if (method.getReturnType() == Sprite.class) return sprite;
                    if (method.getReturnType() == boolean.class) return ao;
                    throw new AssertionError("Unexpected complete fixture query: " + method);
                });
    }

    private static BakedQuad surface(Sprite sprite, Direction face, int tint) {
        // Extend through texture-cell boundaries, so comparisons also exercise splitting/interpolation.
        float a = -0.25F, b = 1.25F;
        float[][] positions = switch (face) {
            case DOWN -> new float[][]{{a,0,a},{b,0,a},{b,0,b},{a,0,b}};
            case UP -> new float[][]{{a,1,b},{b,1,b},{b,1,a},{a,1,a}};
            case NORTH -> new float[][]{{b,a,0},{a,a,0},{a,b,0},{b,b,0}};
            case SOUTH -> new float[][]{{a,a,1},{b,a,1},{b,b,1},{a,b,1}};
            case WEST -> new float[][]{{0,a,a},{0,a,b},{0,b,b},{0,b,a}};
            case EAST -> new float[][]{{1,a,b},{1,a,a},{1,b,a},{1,b,b}};
        };
        int[] data = new int[32];
        for (int vertex = 0; vertex < 4; vertex++) {
            int start = vertex * 8;
            for (int axis = 0; axis < 3; axis++) data[start + axis] = Float.floatToRawIntBits(positions[vertex][axis]);
            data[start + 3] = 0xFF708090 + tint + vertex;
            data[start + 4] = Float.floatToRawIntBits(sprite.getFrameU(vertex % 2 * 16));
            data[start + 5] = Float.floatToRawIntBits(sprite.getFrameV(vertex / 2 * 16));
            data[start + 6] = 0x00D000B0 + tint + vertex;
            data[start + 7] = (face.getOffsetX() * 127 & 255) | ((face.getOffsetY() * 127 & 255) << 8)
                    | ((face.getOffsetZ() * 127 & 255) << 16);
        }
        return new BakedQuad(data, tint, face, sprite, tint % 2 == 0);
    }

    private static int tint(Object template) {
        try {
            Method accessor = template.getClass().getDeclaredMethod("colorIndex");
            accessor.setAccessible(true);
            return (int) accessor.invoke(template);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Object signature(Object value) throws Exception {
        if (value instanceof Float number) return Float.floatToRawIntBits(number);
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) result.add(signature(item));
            return result;
        }
        if (value instanceof Object[] array) return signature(Arrays.asList(array));
        if (value != null && value.getClass().isRecord()) {
            List<Object> result = new ArrayList<>();
            for (var component : value.getClass().getRecordComponents()) {
                Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                result.add(signature(accessor.invoke(value)));
            }
            return result;
        }
        return value;
    }

    private static class Transparent extends ForwardingBakedModel {
        Transparent(BakedModel model) { wrapped = model; }
        void cycle() { wrapped = this; }
    }

    private static final class OverrideQuads extends Transparent {
        OverrideQuads(BakedModel model) { super(model); }
        @Override public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
            return wrapped.getQuads(state, face, random);
        }
    }

    private static final class OverrideWrapped extends Transparent {
        OverrideWrapped(BakedModel model) { super(model); }
        @Override public BakedModel getWrappedModel() { return wrapped; }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
