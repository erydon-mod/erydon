package com.oliver.erydon.client.model;

import com.google.gson.JsonParser;
import com.oliver.erydon.block.CorniceBlock;
import com.oliver.erydon.block.CorniceConnections;
import com.oliver.erydon.migration.ErydonIdMigration;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
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
    private static int vertices;
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
            model = JsonUnbakedModel.deserialize(JsonParser.parseReader(reader).toString());
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
            var shape = state.getOutlineShape(world, owner);
            var outlineBoxes = shape.getBoundingBoxes();
            require(shape == state.getCollisionShape(world, owner), "Cornice outline/collision differ");
            var builder = IndigoRenderer.INSTANCE.meshBuilder();
            var emitter = builder.getEmitter();
            for (var segment : CorniceConnections.components(world, state, owner)) {
                for (BakedQuad quad : CorniceAngledGeometry.fit(source, segment)) {
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
        }
    }

    public static void report() {
        System.out.println("ERYDON_CORNICE_MESH_OK: " + vertices + " production-baked transformed vertices fit placed selection/collision bounds; Fabric replay retains normals and CTM planes");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class TestSprite extends Sprite {
        TestSprite(SpriteContents contents) { super(new Identifier("erydon", "cornice_probe_atlas"), contents, 64, 64, 16, 32); }
        @Override public float getAnimationFrameDelta() { return 0; }
    }
}
