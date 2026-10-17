package com.oliver.erydon.client.model;

import com.oliver.erydon.block.WindowFrenchGeorgianBlock;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.EncodingFormat;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.MutableQuadViewImpl;
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
import java.util.ArrayDeque;
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
            var transforms = new ArrayDeque<RenderContext.QuadTransform>();
            var mesh = IndigoRenderer.INSTANCE.meshBuilder();
            var delegate = mesh.getEmitter();
            var emitter = new MutableQuadViewImpl() {
                { data = new int[EncodingFormat.TOTAL_STRIDE]; clear(); }
                @Override public void emitDirectly() {
                    for (var transform : transforms) if (!transform.transform(this)) return;
                    delegate.copyFrom(this).emit();
                }
            };
            var material = IndigoRenderer.INSTANCE.materialFinder().find();
            SpriteFinder finder = (SpriteFinder) Proxy.newProxyInstance(SpriteFinder.class.getClassLoader(),
                    new Class<?>[]{SpriteFinder.class}, (proxy, method, args) -> sprite);
            RenderContext context = (RenderContext) Proxy.newProxyInstance(RenderContext.class.getClassLoader(),
                    new Class<?>[]{RenderContext.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "pushTransform" -> {
                                var transform = (RenderContext.QuadTransform) args[0];
                                if (transform instanceof WorldAlignedYRotation)
                                    field(WorldAlignedYRotation.class, "spriteFinder").set(transform, finder);
                                if (transform instanceof WindowSillTransform)
                                    field(WindowSillTransform.class, "spriteFinder").set(transform, finder);
                                transforms.push(transform);
                            }
                            case "popTransform" -> transforms.pop();
                            case "fallbackConsumer" -> { return (Consumer<BakedModel>) model -> {
                                fabric.add(ids.get(model));
                                for (int face = -1; face < 6; face++) {
                                    Direction side = face < 0 ? null : Direction.values()[face];
                                    for (BakedQuad quad : model.getQuads(null, side, Random.create(0)))
                                        emitter.fromVanilla(quad, material, side).emit();
                                }
                            }; }
                            default -> throw new AssertionError("Unexpected render context call " + method.getName());
                        }
                        return null;
                    });
            Map<BlockState, List<FrameQuad>> fabricFrames = new HashMap<>();
            int checked = 0;
            for (BlockState state : block.getStateManager().getStates()) {
                models.lookups.clear();
                List<BakedQuad> actual = renderer.getQuads(state, null, Random.create(0));
                List<Identifier> axiom = List.copyOf(models.lookups);
                boolean clearCentre = state.get(CORNER) == Corner.NONE && state.get(OPEN)
                        && state.get(PIECE) == Piece.LOWER_MULTI_MID && !state.get(SILL);
                require(actual.isEmpty() == clearCentre, "A window disappeared or a lower centre retained geometry: " + state);
                fabric.clear();
                renderer.emitBlockQuads(null, state, BlockPos.ORIGIN, () -> Random.create(0), context);
                require(transforms.isEmpty(), "Georgian rendering leaked a transform");
                List<float[]> emitted = new ArrayList<>();
                List<float[]> emittedUvs = new ArrayList<>();
                List<Direction> emittedFaces = new ArrayList<>();
                mesh.build().forEach(quad -> {
                    float[] positions = new float[12];
                    for (int vertex = 0; vertex < 4; vertex++) {
                        positions[vertex * 3] = quad.x(vertex);
                        positions[vertex * 3 + 1] = quad.y(vertex);
                        positions[vertex * 3 + 2] = quad.z(vertex);
                    }
                    emitted.add(positions);
                    float[] uvs = new float[8];
                    for (int vertex = 0; vertex < 4; vertex++) {
                        uvs[vertex * 2] = quad.u(vertex);
                        uvs[vertex * 2 + 1] = quad.v(vertex);
                    }
                    emittedUvs.add(uvs);
                    emittedFaces.add(quad.lightFace());
                });
                List<Identifier> expected = expected(state);
                require(fabric.equals(expected), "Fabric selected wrong components: " + state + " " + fabric);
                require(axiom.equals(expected), "Axiom selected different components: " + state + " " + axiom);
                boolean[] sourcePanes = sillGeometryAndUnchangedFrame(renderer, state, actual, expected, models,
                        emitted, emittedUvs, emittedFaces, fabricFrames);
                fabricAxiomParity(state, actual, emitted, emittedUvs, emittedFaces, sourcePanes);
                if (state.get(CORNER) == Corner.NONE) {
                    if (!state.get(OPEN)) {
                        // Closing a wide run retains the exact one-wide appearance from 1.5.44,
                        // including the culled buckets consumed by Axiom and ordinary rendering.
                        same(actual, renderer.getQuads(state.with(PIECE, state.get(PIECE).single()), null, Random.create(0)));
                        for (Direction side : Direction.values()) {
                            same(renderer.getQuads(state, side, Random.create(0)),
                                    renderer.getQuads(state.with(PIECE, state.get(PIECE).single()), side, Random.create(0)));
                        }
                    } else {
                        openStraightGeometry(renderer, state, models.get(WindowFrenchGeorgianBakedModel.modelId(
                                "aganite_window_french_georgian", "sill")));
                    }
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
                if (state.get(OPEN) && (state.get(CORNER) == Corner.NONE && state.get(PIECE).asString().startsWith("upper")
                        || state.get(PIECE) == Piece.UPPER_SINGLE)) {
                    fixedFrameSurfaces(renderer, state, emitted);
                }
                checked++;
            }
            sillUvGuards();
            System.out.println("ERYDON_GEORGIAN_WINDOW_RENDERING_OK states=" + checked
                    + " FabricAxiomComponents=exact closedStraightQuads=exact openCentres=clear openOuterWings=retained openCornerPost=6quads"
                    + " fixedFanlightJambs=FabricMesh+AxiomAllFacings inwardSills=mirrorAboutFrame+unchangedFrameGlass+UVGuards");
        } finally {
            singleton.set(null, previous);
        }
    }

    private static void fabricAxiomParity(BlockState state, List<BakedQuad> fallback, List<float[]> emitted,
                                          List<float[]> uvs, List<Direction> faces, boolean[] sourcePanes) {
        require(fallback.size() == emitted.size(), "Fabric/Axiom quad counts differ: " + state);
        for (int at = 0; at < fallback.size(); at++) {
            BakedQuad quad = fallback.get(at);
            int[] data = quad.getVertexData();
            for (int vertex = 0; vertex < 4; vertex++) {
                for (int axis = 0; axis < 3; axis++) require(Math.abs(emitted.get(at)[vertex * 3 + axis]
                                - Float.intBitsToFloat(data[vertex * 8 + axis])) <= .000002,
                        "Fabric/Axiom sill/frame position differs: " + state + " quad=" + at);
                // Established two-way mirror coatings use world-locked UV baking in
                // Fabric and authored UV remapping in Axiom. Test each against its own
                // frame baseline instead; every stone sill/cap remains strictly equal.
                boolean coatedPane = state.get(GLASS) == com.oliver.erydon.block.WindowArchBlock.Glass.TWO_WAY
                        && sourcePanes[at] && quad.getColorIndex() == -1;
                if (!coatedPane) for (int axis = 0; axis < 2; axis++) require(Math.abs(uvs.get(at)[vertex * 2 + axis]
                                - Float.intBitsToFloat(data[vertex * 8 + 4 + axis])) <= .000002,
                        "Fabric/Axiom texture coordinates differ: " + state + " quad=" + at);
            }
            // Flat horizontal face classification controls CTM projection and lighting.
            boolean flat = true;
            for (int vertex = 1; vertex < 4; vertex++)
                flat &= Math.abs(emitted.get(at)[vertex * 3 + 1] - emitted.get(at)[1]) < .000001;
            if (flat && quad.getFace().getAxis() == Direction.Axis.Y)
                require(faces.get(at) == quad.getFace(), "Sill rotation changed a horizontal face normal");
        }
    }

    private static boolean[] sillGeometryAndUnchangedFrame(WindowFrenchGeorgianBakedModel renderer, BlockState state,
                                                       List<BakedQuad> actual, List<Identifier> components, Lookup models,
                                                       List<float[]> emitted, List<float[]> uvs, List<Direction> faces,
                                                       Map<BlockState, List<FrameQuad>> fabricFrames) {
        List<BakedQuad> frame = new ArrayList<>();
        List<FrameQuad> fabricFrame = new ArrayList<>();
        boolean[] sourcePanes = new boolean[actual.size()];
        int at = 0, sillFace = 0;
        Direction facing = state.get(FACING);
        for (Identifier id : components) {
            BakedModel source = models.get(id);
            List<BakedQuad> quads = new ArrayList<>(source.getQuads(null, null, Random.create(0)));
            for (Direction face : Direction.values()) quads.addAll(source.getQuads(null, face, Random.create(0)));
            String path = id.getPath();
            boolean sill = path.endsWith("_sill") || path.endsWith("_sill_lh") || path.endsWith("_sill_rh");
            if (sill && path.endsWith("_sill")) facing = sillFace++ == 0 ? state.get(FACING) : secondaryFacing(state);
            for (BakedQuad sourceQuad : quads) {
                require(at < actual.size(), "Component geometry count changed");
                BakedQuad placed = actual.get(at++);
                sourcePanes[at - 1] = sourceQuad.getColorIndex() == 0;
                if (!sill) {
                    frame.add(placed);
                    fabricFrame.add(new FrameQuad(emitted.get(at - 1), uvs.get(at - 1), faces.get(at - 1)));
                    continue;
                }
                for (int vertex = 0; vertex < 4; vertex++) {
                    int offset = vertex * 8;
                    double x = Float.intBitsToFloat(sourceQuad.getVertexData()[offset]);
                    double z = Float.intBitsToFloat(sourceQuad.getVertexData()[offset + 2]);
                    if (state.get(SILL_SIDE) == SillSide.INWARD) { x = 1 - x; z = 3 / 16.0 - z; }
                    double[] point = rotate(x, z, facing);
                    require(Math.abs(point[0] - Float.intBitsToFloat(placed.getVertexData()[offset])) <= .000002
                                    && Math.abs(point[1] - Float.intBitsToFloat(placed.getVertexData()[offset + 2])) <= .000002
                                    && Math.abs(Float.intBitsToFloat(sourceQuad.getVertexData()[offset + 1])
                                            - Float.intBitsToFloat(placed.getVertexData()[offset + 1])) <= .000002,
                            "Sill/cap did not reflect about the window frame's own 3/16 depth: " + state);
                }
            }
        }
        require(at == actual.size(), "Unexpected geometry outside authored components");
        same(frame, renderer.getQuads(state.with(SILL, false).with(SILL_SIDE, SillSide.OUTWARD), null, Random.create(0)));
        same(frame, renderer.getQuads(state.with(SILL, false).with(SILL_SIDE, SillSide.INWARD), null, Random.create(0)));
        BlockState baselineState = state.with(SILL, false).with(SILL_SIDE, SillSide.OUTWARD);
        List<FrameQuad> baseline = fabricFrames.putIfAbsent(baselineState, fabricFrame);
        if (baseline != null) {
            require(baseline.size() == fabricFrame.size(), "Sill side changed Fabric frame/glass quad count");
            for (int quad = 0; quad < baseline.size(); quad++) {
                FrameQuad before = baseline.get(quad), after = fabricFrame.get(quad);
                require(Arrays.equals(before.positions, after.positions) && Arrays.equals(before.uvs, after.uvs)
                                && before.face == after.face,
                        "Sill side changed Fabric frame/glass geometry, coating UVs or normals: " + state);
            }
        }
        return sourcePanes;
    }

    private record FrameQuad(float[] positions, float[] uvs, Direction face) { }

    /** Real emitted faces, not component IDs, must close both sides of each fixed fanlight. */
    private static void fixedFrameSurfaces(WindowFrenchGeorgianBakedModel renderer, BlockState state,
                                           List<float[]> emitted) {
        List<float[]> fallback = new ArrayList<>();
        for (int face = -1; face < 6; face++) {
            for (BakedQuad quad : renderer.getQuads(state, face < 0 ? null : Direction.values()[face], Random.create(0))) {
                float[] positions = new float[12];
                for (int vertex = 0; vertex < 4; vertex++) for (int axis = 0; axis < 3; axis++)
                    positions[vertex * 3 + axis] = Float.intBitsToFloat(quad.getVertexData()[vertex * 8 + axis]);
                fallback.add(positions);
            }
        }
        for (double x : new double[]{.75, 15.25}) for (double y : new double[]{6.5, 10, 14}) {
            double[] horizontal = rotate(x / 16, 3.0 / 16, state.get(FACING));
            double[] point = {horizontal[0], y / 16, horizontal[1]};
            require(emitted.stream().anyMatch(quad -> covers(quad, point)), "Fabric lost fixed fanlight jamb at "
                    + Arrays.toString(point) + " " + state);
            require(fallback.stream().anyMatch(quad -> covers(quad, point)), "Axiom lost fixed fanlight jamb at "
                    + Arrays.toString(point) + " " + state);
        }
        if (state.get(CORNER) == Corner.NONE && state.get(PIECE) != Piece.UPPER_SINGLE) {
            double[] horizontal = rotate(.75 / 16, 1.5 / 16, state.get(FACING));
            double[] point = {horizontal[0], 5.852 / 16, horizontal[1]};
            require(emitted.stream().anyMatch(quad -> covers(quad, point)), "Fabric lost the clipped jamb's internal bottom cap");
            require(fallback.stream().anyMatch(quad -> covers(quad, point)), "Axiom lost the clipped jamb's internal bottom cap");
        }
    }

    private static boolean covers(float[] quad, double[] point) {
        return coversTriangle(quad, point, 0, 1, 2) || coversTriangle(quad, point, 0, 2, 3);
    }

    private static boolean coversTriangle(float[] quad, double[] point, int a, int b, int c) {
        double[] u = new double[3], v = new double[3], w = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            u[axis] = quad[b * 3 + axis] - quad[a * 3 + axis];
            v[axis] = quad[c * 3 + axis] - quad[a * 3 + axis];
            w[axis] = point[axis] - quad[a * 3 + axis];
        }
        double uu = dot(u, u), uv = dot(u, v), vv = dot(v, v), wu = dot(w, u), wv = dot(w, v);
        double denominator = uu * vv - uv * uv;
        if (denominator <= 1e-14) return false;
        double first = (vv * wu - uv * wv) / denominator, second = (uu * wv - uv * wu) / denominator;
        if (first < -1e-5 || second < -1e-5 || first + second > 1.00001) return false;
        double distanceSquared = 0;
        for (int axis = 0; axis < 3; axis++) {
            double residual = w[axis] - first * u[axis] - second * v[axis];
            distanceSquared += residual * residual;
        }
        return distanceSquared <= 1e-10;
    }

    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    private static List<Identifier> expected(BlockState state) {
        List<Identifier> result = new ArrayList<>();
        boolean corner = state.get(CORNER) != Corner.NONE;
        Piece primary = corner || state.get(OPEN) ? state.get(PIECE) : state.get(PIECE).single();
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
        else suffix = switch (piece) {
            case UPPER_SINGLE -> "open_upper_single_" + hinge;
            case LOWER_SINGLE -> "open_lower_single_" + hinge;
            case UPPER_MULTI_LH -> "open_upper_single_edge_lh";
            case UPPER_MULTI_RH -> "open_upper_single_edge_rh";
            case UPPER_MULTI_MID -> "open_upper_single_mid";
            case LOWER_MULTI_LH -> "open_lower_multi_lh";
            case LOWER_MULTI_RH -> "open_lower_multi_rh";
            case LOWER_MULTI_MID -> null;
        };
        if (suffix != null) add(result, suffix);
        if (state.get(SILL)) {
            add(result, "sill");
            if (open && !corner) {
                String cap = piece == Piece.LOWER_SINGLE ? hinge
                        : piece == Piece.LOWER_MULTI_LH ? "lh" : piece == Piece.LOWER_MULTI_RH ? "rh" : null;
                if (cap != null) {
                    if (state.get(SILL_SIDE) == SillSide.INWARD) cap = cap.equals("lh") ? "rh" : "lh";
                    add(result, "sill_" + cap);
                }
            }
        }
    }

    private static void openStraightGeometry(WindowFrenchGeorgianBakedModel renderer, BlockState state, BakedModel authoredSill) {
        Piece piece = state.get(PIECE);
        List<BakedQuad> quads = new ArrayList<>(renderer.getQuads(state, null, Random.create(0)));
        for (Direction side : Direction.values()) quads.addAll(renderer.getQuads(state, side, Random.create(0)));
        if (piece == Piece.LOWER_MULTI_MID) {
            if (!state.get(SILL)) require(quads.isEmpty(), "An open lower centre still has a culled frame/glass face");
            List<BakedQuad> expected = new ArrayList<>();
            if (state.get(SILL)) {
                int rotation = switch (state.get(FACING)) {
                    case EAST -> 90; case SOUTH -> 180; case WEST -> 270; default -> 0;
                };
                if (state.get(SILL_SIDE) == SillSide.INWARD) rotation = (rotation + 180) % 360;
                AxiomFallbackQuads.add(expected, authoredSill, rotation, null, Random.create(0), true);
                for (Direction side : Direction.values()) {
                    AxiomFallbackQuads.add(expected, authoredSill, rotation, side, Random.create(0), true);
                }
                if (state.get(SILL_SIDE) == SillSide.INWARD) for (int at = 0; at < expected.size(); at++)
                    expected.set(at, WindowSillTransform.translate(expected.get(at), state.get(FACING)));
            }
            same(quads, expected);
            require(quads.stream().allMatch(quad -> quad.getColorIndex() == -1),
                    "An open lower centre retained glass or lead");
        } else if (piece == Piece.UPPER_MULTI_MID) {
            // Sills are independently checked against their reflected authored geometry.
            // An inward sill can project beyond this fanlight-only depth bound; do not
            // discard stone/two-way faces by tint, since that could hide a real wing.
            BlockState withoutSill = state.with(SILL, false);
            List<BakedQuad> fanlight = new ArrayList<>(renderer.getQuads(withoutSill, null, Random.create(0)));
            for (Direction side : Direction.values()) fanlight.addAll(renderer.getQuads(withoutSill, side, Random.create(0)));
            for (BakedQuad quad : fanlight) for (int vertex = 0; vertex < 4; vertex++) {
                require(depth(quad, vertex, state.get(FACING)) <= .250001,
                        "A centre fanlight retained a deep hinged pane");
            }
            require(fanlight.stream().anyMatch(quad -> quad.getColorIndex() == 0), "An upper centre lost its fixed fanlight glass");
        } else if (piece == Piece.LOWER_MULTI_LH || piece == Piece.LOWER_MULTI_RH
                || piece == Piece.UPPER_MULTI_LH || piece == Piece.UPPER_MULTI_RH) {
            require(quads.stream().anyMatch(quad -> {
                for (int vertex = 0; vertex < 4; vertex++) if (depth(quad, vertex, state.get(FACING)) > .75) return true;
                return false;
            }), "An outer edge lost its opening wing");
        }
    }

    /** Real mutable quads and fallback quads must agree on safe world UV projection. */
    private static void sillUvGuards() throws Exception {
        int checked = 0;
        var material = IndigoRenderer.INSTANCE.materialFinder().find();
        for (String texture : new String[]{"block/aganite_block", "block/glazing_silver"}) {
            try (SpriteContents contents = new SpriteContents(new Identifier("erydon", texture),
                    new SpriteDimensions(16, 16), new NativeImage(16, 16, false), AnimationResourceMetadata.EMPTY)) {
                Sprite sprite = new Sprite(new Identifier("erydon", "sill_uv_probe_atlas"), contents, 64, 64, 16, 32) {};
                SpriteFinder finder = (SpriteFinder) Proxy.newProxyInstance(SpriteFinder.class.getClassLoader(),
                        new Class<?>[]{SpriteFinder.class}, (proxy, method, args) -> sprite);
                for (Direction facing : Direction.Type.HORIZONTAL) for (Direction face : new Direction[]{Direction.UP, Direction.DOWN}) {
                    for (boolean crossesBoundary : new boolean[]{false, true}) {
                        double low = crossesBoundary ? .75 : .875, high = .9375;
                        double[][] points = face == Direction.UP
                                ? new double[][]{{.25, high}, {.75, high}, {.75, low}, {.25, low}}
                                : new double[][]{{.25, low}, {.75, low}, {.75, high}, {.25, high}};
                        int[] data = new int[32];
                        double[][] expected = new double[4][];
                        for (int vertex = 0; vertex < 4; vertex++) {
                            double[] before = rotate(points[vertex][0], points[vertex][1], facing);
                            expected[vertex] = rotate(points[vertex][0], points[vertex][1] - 13 / 16.0, facing);
                            int at = vertex * 8;
                            data[at] = Float.floatToRawIntBits((float) before[0]);
                            data[at + 1] = Float.floatToRawIntBits(.25F);
                            data[at + 2] = Float.floatToRawIntBits((float) before[1]);
                            data[at + 3] = -1;
                            data[at + 4] = Float.floatToRawIntBits(sprite.getFrameU(vertex < 2 ? 2 : 14));
                            data[at + 5] = Float.floatToRawIntBits(sprite.getFrameV(vertex % 2 == 0 ? 3 : 13));
                        }
                        BakedQuad source = new BakedQuad(data, -1, face, sprite, true);
                        BakedQuad fallback = WindowSillTransform.translate(source, facing);
                        var mutable = new MutableQuadViewImpl() {
                            { this.data = new int[EncodingFormat.TOTAL_STRIDE]; clear(); }
                            @Override public void emitDirectly() { throw new AssertionError("UV fixture unexpectedly emitted"); }
                        };
                        mutable.fromVanilla(source, material, null);
                        for (int vertex = 0; vertex < 4; vertex++) mutable.normal(vertex, 0, face.getOffsetY(), 0);
                        var transform = new WindowSillTransform(facing);
                        field(WindowSillTransform.class, "spriteFinder").set(transform, finder);
                        require(transform.transform(mutable), "Sill translation rejected its quad");
                        boolean project = texture.endsWith("_block") && !crossesBoundary;
                        float[] u = new float[4], v = new float[4];
                        for (int vertex = 0; vertex < 4; vertex++) {
                            u[vertex] = (float) (expected[vertex][0] * 16);
                            v[vertex] = (float) ((face == Direction.UP ? expected[vertex][1] : 1 - expected[vertex][1]) * 16);
                        }
                        float centreU = (u[0] + u[1] + u[2] + u[3]) / 4;
                        float centreV = (v[0] + v[1] + v[2] + v[3]) / 4;
                        for (int vertex = 0; vertex < 4; vertex++) {
                            int at = vertex * 8;
                            require(Math.abs(mutable.x(vertex) - expected[vertex][0]) < .000001
                                            && Math.abs(mutable.z(vertex) - expected[vertex][1]) < .000001
                                            && Math.abs(Float.intBitsToFloat(fallback.getVertexData()[at]) - expected[vertex][0]) < .000001
                                            && Math.abs(Float.intBitsToFloat(fallback.getVertexData()[at + 2]) - expected[vertex][1]) < .000001,
                                    "Native/fallback sill translation moved a quad away from its frame");
                            float expectedU = project ? sprite.getFrameU(u[vertex] + sprite.getAnimationFrameDelta() * (centreU - u[vertex]))
                                    : Float.intBitsToFloat(data[at + 4]);
                            float expectedV = project ? sprite.getFrameV(v[vertex] + sprite.getAnimationFrameDelta() * (centreV - v[vertex]))
                                    : Float.intBitsToFloat(data[at + 5]);
                            require(Math.abs(mutable.u(vertex) - expectedU) < .000001
                                            && Math.abs(mutable.v(vertex) - expectedV) < .000001
                                            && Math.abs(Float.intBitsToFloat(fallback.getVertexData()[at + 4]) - expectedU) < .000001
                                            && Math.abs(Float.intBitsToFloat(fallback.getVertexData()[at + 5]) - expectedV) < .000001,
                                    "Sill UVs projected across an atlas boundary, changed glazing, or lost their world phase");
                            require(mutable.hasNormal(vertex) && Math.abs(mutable.normalX(vertex)) < .000001
                                            && Math.abs(mutable.normalY(vertex) - face.getOffsetY()) < .000001
                                            && Math.abs(mutable.normalZ(vertex)) < .000001,
                                    "Translating an inward sill changed its normals");
                        }
                        require(mutable.lightFace() == face && fallback.getFace() == face, "Sill translation changed its lighting face");
                        checked++;
                    }
                }
            }
        }
        System.out.println("ERYDON_GEORGIAN_SILL_UV_OK cases=" + checked
                + " nativeFallback=exact stoneWorldProjection=safe boundaryCrossing+glazing=authored normals=preserved");
    }

    private static double depth(BakedQuad quad, int vertex, Direction facing) {
        int[] data = quad.getVertexData();
        double x = Float.intBitsToFloat(data[vertex * 8]);
        double z = Float.intBitsToFloat(data[vertex * 8 + 2]);
        return switch (facing) {
            case EAST -> 1 - x;
            case SOUTH -> 1 - z;
            case WEST -> x;
            default -> z;
        };
    }

    private static void add(List<Identifier> list, String suffix) {
        list.add(WindowFrenchGeorgianBakedModel.modelId("aganite_window_french_georgian", suffix));
    }
    private static void same(List<BakedQuad> a, List<BakedQuad> b) {
        require(a.size() == b.size(), "Expected geometry quad count differs");
        for (int i = 0; i < a.size(); i++) {
            BakedQuad left = a.get(i), right = b.get(i);
            require(Arrays.equals(left.getVertexData(), right.getVertexData()) && left.getColorIndex() == right.getColorIndex()
                    && left.getFace() == right.getFace() && left.getSprite() == right.getSprite() && left.hasShade() == right.hasShade(),
                    "Expected geometry UV/position/normal/tint/lighting/order differs");
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
