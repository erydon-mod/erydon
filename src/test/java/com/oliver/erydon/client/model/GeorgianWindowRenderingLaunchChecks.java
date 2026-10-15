package com.oliver.erydon.client.model;

import com.oliver.erydon.block.WindowFrenchGeorgianBlock;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedModelManager;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BakedQuadFactory;
import net.minecraft.client.render.model.ModelRotation;
import net.minecraft.client.render.model.json.JsonUnbakedModel;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.oliver.erydon.block.WindowFrenchGeorgianBlock.*;

/** Uses actual authored templates and both public model entry points, without an atlas or window. */
public final class GeorgianWindowRenderingLaunchChecks {
    public static void run(WindowFrenchGeorgianBlock block) throws Exception {
        if (RendererAccess.INSTANCE.getRenderer() == null) RendererAccess.INSTANCE.registerRenderer(IndigoRenderer.INSTANCE);
        Field singleton = field(MinecraftClient.class, "instance");
        Object previous = singleton.get(null);
        try (SpriteContents contents = new SpriteContents(new Identifier("erydon", "window_probe"),
                new SpriteDimensions(16, 16), new NativeImage(16, 16, false), AnimationResourceMetadata.EMPTY)) {
            Sprite sprite = new Sprite(new Identifier("erydon", "window_probe_atlas"), contents, 64, 64, 16, 32) {};
            Lookup models = new Lookup();
            Map<BakedModel, Identifier> ids = new IdentityHashMap<>();
            for (String suffix : WindowFrenchGeorgianBakedModel.MODEL_SUFFIXES) {
                Identifier id = WindowFrenchGeorgianBakedModel.modelId("aganite_window_french_georgian", suffix);
                BakedModel model = component(suffix, sprite);
                models.put(id, model); ids.put(model, id);
            }
            BakedModel wrapped = models.values().iterator().next();
            models.put(WindowArchBakedModel.MIRROR_MATERIAL_MODEL, wrapped);
            MinecraftClient client = allocate(MinecraftClient.class);
            BakedModelManager manager = allocate(BakedModelManager.class);
            field(BakedModelManager.class, "models").set(manager, models);
            field(BakedModelManager.class, "missingModel").set(manager, wrapped);
            field(MinecraftClient.class, "bakedModelManager").set(client, manager);
            singleton.set(null, client);
            var renderer = new WindowFrenchGeorgianBakedModel(wrapped);
            List<Identifier> fabric = new ArrayList<>();
            int[] transforms = {0};
            RenderContext context = (RenderContext) Proxy.newProxyInstance(RenderContext.class.getClassLoader(),
                    new Class<?>[]{RenderContext.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "pushTransform" -> transforms[0]++;
                            case "popTransform" -> transforms[0]--;
                            case "fallbackConsumer" -> { return (Consumer<BakedModel>) model -> fabric.add(ids.get(model)); }
                            default -> throw new AssertionError("Unexpected render context call " + method.getName());
                        }
                        return null;
                    });
            int checked = 0;
            for (BlockState state : block.getStateManager().getStates()) {
                models.lookups.clear();
                List<BakedQuad> actual = renderer.getQuads(state, null, Random.create(0));
                List<Identifier> axiom = List.copyOf(models.lookups);
                require(!actual.isEmpty(), "An entire Georgian window disappeared");
                fabric.clear();
                renderer.emitBlockQuads(null, state, BlockPos.ORIGIN, () -> Random.create(0), context);
                require(transforms[0] == 0, "Georgian rendering leaked a transform");
                List<Identifier> expected = expected(state);
                require(fabric.equals(expected), "Fabric selected wrong components: " + state + " " + fabric);
                require(axiom.equals(expected), "Axiom selected different components: " + state + " " + axiom);
                if (state.get(CORNER) == Corner.NONE) {
                    same(actual, renderer.getQuads(state.with(PIECE, state.get(PIECE).single()), null, Random.create(0)));
                } else if (state.get(OPEN) && state.get(PIECE).single() == Piece.LOWER_SINGLE && !state.get(SILL)) {
                    require(actual.size() == 6, "Open lower corner retained leaf/frame/glass geometry");
                    double x = state.get(CORNER) == Corner.LEFT ? .09375 : .90625;
                    for (BakedQuad quad : actual) {
                        require(quad.getColorIndex() == -1, "Corner mullion retained glass or lead");
                        int[] data = quad.getVertexData();
                        for (int vertex = 0; vertex < 4; vertex++) {
                            float px = Float.intBitsToFloat(data[vertex * 8]);
                            float pz = Float.intBitsToFloat(data[vertex * 8 + 2]);
                            double[] centre = rotate(x, .09375, state.get(FACING));
                            require(Math.abs(px - centre[0]) <= .093751 && Math.abs(pz - centre[1]) <= .093751,
                                    "Corner post moved or a wing survives");
                        }
                    }
                }
                checked++;
            }
            System.out.println("ERYDON_GEORGIAN_WINDOW_RENDERING_OK states=" + checked
                    + " FabricAxiomComponents=exact legacyStraightQuads=exact openCornerPost=6quads");
        } finally {
            singleton.set(null, previous);
        }
    }

    private static List<Identifier> expected(BlockState state) {
        List<Identifier> result = new ArrayList<>();
        boolean corner = state.get(CORNER) != Corner.NONE;
        Piece primary = corner ? state.get(PIECE) : state.get(PIECE).asString().startsWith("upper") ? Piece.UPPER_SINGLE : Piece.LOWER_SINGLE;
        face(result, primary, state, corner);
        if (corner) {
            boolean left = (state.get(CORNER) == Corner.LEFT) != state.get(OPEN);
            boolean upper = state.get(PIECE).asString().startsWith("upper");
            Piece secondary = upper ? left ? Piece.UPPER_MULTI_LH : Piece.UPPER_MULTI_RH
                    : left ? Piece.LOWER_MULTI_LH : Piece.LOWER_MULTI_RH;
            face(result, secondary, state, true);
            if (state.get(OPEN)) add(result, "open_corner_post");
        }
        return result;
    }

    private static void face(List<Identifier> result, Piece piece, BlockState state, boolean corner) {
        boolean open = state.get(OPEN);
        String hinge = state.get(HINGE) == DoorHinge.LEFT ? "lh" : "rh";
        String suffix;
        if (!open) suffix = "closed_" + piece.asString();
        else if (corner) suffix = switch (piece) {
            case UPPER_SINGLE -> "open_corner_upper_single_" + hinge;
            case UPPER_MULTI_LH -> "open_corner_upper_multi_lh";
            case UPPER_MULTI_RH -> "open_corner_upper_multi_rh";
            case UPPER_MULTI_MID -> "open_upper_multi_mid";
            default -> null;
        };
        else suffix = "open_" + piece.asString() + "_" + hinge;
        if (suffix != null) add(result, suffix);
        if (state.get(SILL)) {
            add(result, "sill");
            if (open && !corner && piece == Piece.LOWER_SINGLE) add(result, "sill_" + hinge);
        }
    }

    private static void add(List<Identifier> list, String suffix) {
        list.add(WindowFrenchGeorgianBakedModel.modelId("aganite_window_french_georgian", suffix));
    }
    private static void same(List<BakedQuad> a, List<BakedQuad> b) {
        require(a.size() == b.size(), "Legacy straight quad count differs");
        for (int i = 0; i < a.size(); i++) {
            BakedQuad left = a.get(i), right = b.get(i);
            require(Arrays.equals(left.getVertexData(), right.getVertexData()) && left.getColorIndex() == right.getColorIndex()
                    && left.getFace() == right.getFace() && left.getSprite() == right.getSprite() && left.hasShade() == right.hasShade(),
                    "Legacy straight UV/position/normal/tint/lighting/order changed from its one-wide equivalent");
        }
    }
    private static double[] rotate(double x, double z, Direction facing) {
        return switch (facing) {
            case EAST -> new double[]{1 - z, x}; case SOUTH -> new double[]{1 - x, 1 - z};
            case WEST -> new double[]{z, 1 - x}; default -> new double[]{x, z};
        };
    }
    private static BakedModel component(String suffix, Sprite sprite) throws Exception {
        String path = "/assets/erydon/models/block/window/french_georgian/window_french_georgian_" + suffix + ".json";
        JsonUnbakedModel model;
        try (var stream = GeorgianWindowRenderingLaunchChecks.class.getResourceAsStream(path)) {
            require(stream != null, "Missing authored window component " + path);
            model = JsonUnbakedModel.deserialize(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        Map<Direction, List<BakedQuad>> culled = new EnumMap<>(Direction.class);
        List<BakedQuad> unculled = new ArrayList<>();
        var factory = new BakedQuadFactory();
        for (var element : model.getElements()) for (var entry : element.faces.entrySet()) {
            BakedQuad quad = factory.bake(element.from, element.to, entry.getValue(), sprite, entry.getKey(),
                    ModelRotation.X0_Y0, element.rotation, element.shade, new Identifier("erydon", "window/" + suffix));
            (entry.getValue().cullFace == null ? unculled
                    : culled.computeIfAbsent(entry.getValue().cullFace, ignored -> new ArrayList<>())).add(quad);
        }
        return (BakedModel) Proxy.newProxyInstance(BakedModel.class.getClassLoader(), new Class<?>[]{BakedModel.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getQuads")) return args[1] == null ? unculled : culled.getOrDefault(args[1], List.of());
                    if (method.getReturnType() == Sprite.class) return sprite;
                    if (method.getReturnType() == boolean.class) return true;
                    throw new AssertionError("Unexpected model method " + method.getName());
                });
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        return type.cast(((sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null)).allocateInstance(type));
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class Lookup extends HashMap<Identifier, BakedModel> {
        final List<Identifier> lookups = new ArrayList<>();
        private void record(Object key) {
            if (!key.equals(WindowArchBakedModel.MIRROR_MATERIAL_MODEL)) lookups.add((Identifier) key);
        }
        @Override public BakedModel get(Object key) { record(key); return super.get(key); }
        @Override public BakedModel getOrDefault(Object key, BakedModel fallback) { record(key); return super.getOrDefault(key, fallback); }
    }
}
