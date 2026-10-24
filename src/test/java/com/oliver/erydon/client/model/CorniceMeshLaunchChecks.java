package com.oliver.erydon.client.model;

import com.google.gson.JsonParser;
import com.oliver.erydon.block.CorniceBlock;
import com.oliver.erydon.block.CorniceConnections;
import com.oliver.erydon.migration.ErydonIdMigration;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;
import java.lang.reflect.Proxy;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BakedQuadFactory;
import net.minecraft.client.render.model.ModelRotation;
import net.minecraft.client.render.model.json.JsonUnbakedModel;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Production vanilla baking, reworked profiles, Fabric replay and physical selection bounds. */
public final class CorniceMeshLaunchChecks {
    private static int vertices, productionReplays;
    private CorniceMeshLaunchChecks() { }

    public static void verify(BlockView world, BlockState state, BlockPos owner) throws Exception {
        Identifier block = Registries.BLOCK.getId(state.getBlock());
        String legacy = ErydonIdMigration.legacyResourcePath(block.getPath());
        String style = legacy.substring(legacy.indexOf("_cornice_") + 9);
        String path = "block/cornice/" + style + "/cornice_" + style + "_straight";
        var stream = CorniceMeshLaunchChecks.class.getClassLoader().getResourceAsStream("assets/erydon/models/" + path + ".json");
        require(stream != null, "Missing cornice parent " + path);
        JsonUnbakedModel model;
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var json=JsonParser.parseReader(reader).getAsJsonObject();
            var textures=new com.google.gson.JsonObject();textures.addProperty("stone","erydon:cornice_probe");json.add("textures",textures);
            model=JsonUnbakedModel.deserialize(json.toString());
        }
        try (var contents = new SpriteContents(new Identifier("erydon", "cornice_probe"), new SpriteDimensions(16, 16),
                new NativeImage(16, 16, false), AnimationResourceMetadata.EMPTY)) {
            Sprite sprite = new TestSprite(contents);
            List<BakedQuad> source = new ArrayList<>();
            BakedQuadFactory factory = new BakedQuadFactory();
            for (var element : model.getElements()) for (var face : element.faces.entrySet()) {
                source.add(factory.bake(element.from, element.to, face.getValue(), sprite, face.getKey(), ModelRotation.X0_Y0,
                        element.rotation, element.shade, new Identifier("erydon", path)));
            }
            List<BakedQuad> volumes = CorniceProfileVolumes.bake(model, ignored -> sprite, new Identifier("erydon", path));
            var shape = state.getOutlineShape(world, owner);
            var outlineBoxes = shape.getBoundingBoxes();
            require(shape == state.getCollisionShape(world, owner), "Cornice outline/collision differ");
            var builder = IndigoRenderer.INSTANCE.meshBuilder();
            var emitter = builder.getEmitter();
            for (var segment : CorniceConnections.components(world, state, owner)) {
                for (BakedQuad quad : CorniceAngledGeometry.fit(source, volumes, segment)) {
                    for (int vertex = 0; vertex < 4; vertex++) {
                        int offset = vertex * 8;
                        int[] data = quad.getVertexData();
                        double x = Float.intBitsToFloat(data[offset]), y = Float.intBitsToFloat(data[offset + 1]), z = Float.intBitsToFloat(data[offset + 2]);
                        require(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z), "Nonfinite reworked cornice vertex");
                        require(outlineBoxes.stream().anyMatch(box -> x >= box.minX - .00005 && x <= box.maxX + .00005
                                && y >= box.minY - .00005 && y <= box.maxY + .00005 && z >= box.minZ - .00005 && z <= box.maxZ + .00005),
                                "Baked " + style + " cornice vertex is outside its physical outline: " + x + "," + y + "," + z + " " + state);
                        require((data[offset + 7] & 0xFFFFFF) != 0, "Reworked face lost its normal");
                        vertices++;
                    }
                    emitter.fromVanilla(quad, IndigoRenderer.INSTANCE.materialFinder().find(), null)
                            .tag(CopingTexturePlane.corniceTag(quad.getFace())).emit();
                }
            }
            builder.build().forEach(quad -> {
                require(quad.cullFace() == null, "Overhanging cornice was neighbour-culled");
                require(SynapheiaRepeatBakedModel.repeatProjectionFace(block, quad.lightFace(), quad.nominalFace(), quad.tag())
                        == CopingTexturePlane.face(quad.tag()), "Fabric replay changed the cornice texture plane");
            });
            if (state.get(CorniceBlock.SHAPE).isHalfSupport()) replay(world, state, owner, source, volumes, sprite);
        }
    }

    private static void replay(BlockView world, BlockState state, BlockPos owner, List<BakedQuad> source, List<BakedQuad> volumes, Sprite sprite) throws Exception {
        if (RendererAccess.INSTANCE.getRenderer() == null) RendererAccess.INSTANCE.registerRenderer(IndigoRenderer.INSTANCE);
        BakedModel placeholder = (BakedModel) Proxy.newProxyInstance(BakedModel.class.getClassLoader(),
                new Class<?>[]{BakedModel.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getParticleSprite")) return sprite;
                    throw new AssertionError("Unexpected fitted source fallback " + method.getName());
                });
        CorniceBakedModel renderer = new CorniceBakedModel(placeholder, new CorniceFittedCache(), volumes);
        var sourceField = CorniceBakedModel.class.getDeclaredField("straightQuads"); sourceField.setAccessible(true);
        sourceField.set(renderer, List.copyOf(source));
        List<BakedQuad> fallback = renderer.getQuads(state, null, Random.create(0));
        var actualSegments = CorniceConnections.components(world, state, owner);
        List<BakedQuad> expectedGeometry = fallback;
        if (!actualSegments.equals(CorniceConnections.nativeComponents(state))) {
            List<BakedQuad> joined = new ArrayList<>();
            for (var segment : actualSegments) joined.addAll(CorniceAngledGeometry.fit(source, volumes, segment));
            expectedGeometry = List.copyOf(joined);
        }
        final List<BakedQuad> emittedExpected = expectedGeometry;
        require(fallback == renderer.getQuads(state, null, Random.create(0)), "Half-support Axiom rebuilt cached geometry");
        for (Direction face : Direction.values()) require(renderer.getQuads(state, face, Random.create(0)).isEmpty(),
                "Fitted half-support faces entered a neighbour-cull bucket");
        var builder = IndigoRenderer.INSTANCE.meshBuilder();
        RenderContext context = (RenderContext) Proxy.newProxyInstance(RenderContext.class.getClassLoader(),
                new Class<?>[]{RenderContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getEmitter")) return builder.getEmitter();
                    throw new AssertionError("Half-support left the real fitted Fabric path: " + method.getName());
                });
        renderer.emitBlockQuads((BlockRenderView) world, state, owner, () -> Random.create(0), context);
        int[] index = {0};
        builder.build().forEach(quad -> {
            require(index[0] < emittedExpected.size(), "Fabric emitted additional fitted half-support geometry");
            BakedQuad expected = emittedExpected.get(index[0]++);
            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = vertex * 8; int[] data = expected.getVertexData();
                require(Math.abs(quad.x(vertex) - Float.intBitsToFloat(data[offset])) < .000002
                                && Math.abs(quad.y(vertex) - Float.intBitsToFloat(data[offset + 1])) < .000002
                                && Math.abs(quad.z(vertex) - Float.intBitsToFloat(data[offset + 2])) < .000002
                                && Math.abs(quad.u(vertex) - Float.intBitsToFloat(data[offset + 4])) < .000002
                                && Math.abs(quad.v(vertex) - Float.intBitsToFloat(data[offset + 5])) < .000002,
                        "Actual CorniceBakedModel Fabric/Axiom half-support positions or UVs differ");
            }
            require(CopingTexturePlane.isCornice(quad.tag())
                            && SynapheiaRepeatBakedModel.repeatProjectionFace(Registries.BLOCK.getId(state.getBlock()),
                            quad.lightFace(), quad.nominalFace(), quad.tag()) == CopingTexturePlane.face(quad.tag()),
                    "Half-support replay lost its actual world-connected texture plane");
        });
        require(index[0] == emittedExpected.size(), "Fabric omitted fitted half-support geometry");
        productionReplays++;
    }

    public static void report() {
        System.out.println("ERYDON_CORNICE_MESH_OK: " + vertices + " production-baked transformed vertices fit placed selection/collision bounds; Fabric replay retains normals and CTM planes; actual half-support Fabric/Axiom replays=" + productionReplays);
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class TestSprite extends Sprite {
        TestSprite(SpriteContents contents) { super(new Identifier("erydon", "cornice_probe_atlas"), contents, 64, 64, 16, 32); }
        @Override public float getAnimationFrameDelta() { return 0; }
    }
}
