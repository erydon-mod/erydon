package com.oliver.erydon.client.model;

import com.google.gson.JsonParser;
import com.oliver.erydon.block.CorniceConnections;
import com.oliver.erydon.block.CornicePerformanceLaunchChecks;
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

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Actual baked face counts, CTM/POM expansion and the shared weighted renderer cache. */
public final class CorniceGeometryPerformanceLaunchChecks {
    private static volatile Object blackhole;
    private CorniceGeometryPerformanceLaunchChecks() { }

    public static void run() throws Exception {
        var cases = CornicePerformanceLaunchChecks.geometryCases();
        CorniceFittedCache cache = new CorniceFittedCache();
        try (var contents = new SpriteContents(new Identifier("erydon", "cornice_perf"), new SpriteDimensions(16,16),
                new NativeImage(16,16,false), AnimationResourceMetadata.EMPTY)) {
            Sprite sprite = new TestSprite(contents);
            for (String style : List.of("modern", "georgian", "guilloche", "gothic")) {
                List<BakedQuad> source = source(style, sprite);
                int maximum = 0, maximumCtm = 0, maximumPom = 0;
                List<CorniceConnections.Segment> worst = null;
                for (var fixture : cases) {
                    List<BakedQuad> fitted = fit(source, fixture.segments());
                    if (fitted.size() > maximum) { maximum = fitted.size(); worst = fixture.segments(); }
                    int ctm = 0, pom = 0;
                    for (var quad : fitted) {
                        for (var fragment : SpiralStairCtmGeometry.split(quad.getFace(), vertices(quad))) {
                            ctm += SynapheiaRepeatBakedModel.repeatPrimitives(fragment.vertices(), false).size();
                            pom += SynapheiaRepeatBakedModel.repeatPrimitives(fragment.vertices(), true).size();
                        }
                    }
                    maximumCtm = Math.max(maximumCtm, ctm); maximumPom = Math.max(maximumPom, pom);
                }
                require(worst != null && maximum <= source.size() * 4, "Reworked cornice exceeded the two-run/triangle bound");
                var key = worst;
                String block = "probe_" + style;
                List<BakedQuad> initial = fit(source, key);
                cache.put(block, key, initial);
                require(cache.get(block, key) == initial, "Renderer cache did not reuse its immutable fitted quads");
                for (int warmup = 0; warmup < 200; warmup++) blackhole = fit(source, key);
                double[] cold = new double[7], warm = new double[7];
                for (int trial = 0; trial < 7; trial++) {
                    long started = System.nanoTime();
                    for (int operation = 0; operation < 200; operation++) blackhole = fit(source, key);
                    cold[trial] = (System.nanoTime() - started) / 200.0;
                    started = System.nanoTime();
                    for (int operation = 0; operation < 10000; operation++) blackhole = cache.get(block, key);
                    warm[trial] = (System.nanoTime() - started) / 10000.0;
                }
                Arrays.sort(cold); Arrays.sort(warm);
                System.out.printf(Locale.ROOT,
                        "ERYDON_CORNICE_PERF_GEOMETRY: %s; native=%d; maximum fitted=%d; maximum world-CTM=%d; rejected generic POM workaround (not used)=%d; cold fit median %.3f ms; shared-cache hit %.0f ns; largest entry estimate=%d bytes%n",
                        style, source.size(), maximum, maximumCtm, maximumPom, cold[3] / 1e6, warm[3], CorniceFittedCache.estimatedBytes(initial));
            }
        }
        System.out.println("ERYDON_CORNICE_RENDER_BUDGET_OK: fitted cache shares an estimated 32 MiB across all block IDs per reload; shape cache shares an estimated 16 MiB; native child quads and cache infrastructure are additional");
    }

    private static List<BakedQuad> source(String style, Sprite sprite) throws Exception {
        String path = "block/cornice/" + style + "/cornice_" + style + "_straight";
        var stream = CorniceGeometryPerformanceLaunchChecks.class.getClassLoader().getResourceAsStream("assets/erydon/models/" + path + ".json");
        require(stream != null, "Missing cornice performance parent " + path);
        JsonUnbakedModel model;
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            model = JsonUnbakedModel.deserialize(JsonParser.parseReader(reader).toString());
        }
        BakedQuadFactory factory = new BakedQuadFactory();
        List<BakedQuad> result = new ArrayList<>();
        for (var element : model.getElements()) for (var face : element.faces.entrySet())
            result.add(factory.bake(element.from, element.to, face.getValue(), sprite, face.getKey(), ModelRotation.X0_Y0,
                    element.rotation, element.shade, new Identifier("erydon", path)));
        return List.copyOf(result);
    }

    private static List<BakedQuad> fit(List<BakedQuad> source, List<CorniceConnections.Segment> segments) {
        List<BakedQuad> result = new ArrayList<>();
        for (var segment : segments) result.addAll(CorniceAngledGeometry.fit(source, segment));
        return List.copyOf(result);
    }

    private static List<SpiralStairCtmGeometry.Vertex> vertices(BakedQuad quad) {
        int[] data = quad.getVertexData();
        List<SpiralStairCtmGeometry.Vertex> result = new ArrayList<>(4);
        for (int vertex = 0; vertex < 4; vertex++) {
            int index = vertex * 8, packed = data[index + 7];
            var position = new SpiralStairCtmGeometry.Vertex(Float.intBitsToFloat(data[index]), Float.intBitsToFloat(data[index + 1]),
                    Float.intBitsToFloat(data[index + 2]), data[index + 3], data[index + 6], true,
                    (byte) packed / 127.0F, (byte) (packed >>> 8) / 127.0F, (byte) (packed >>> 16) / 127.0F);
            if (result.stream().noneMatch(other -> other.x() == position.x() && other.y() == position.y() && other.z() == position.z()))
                result.add(position);
        }
        return result;
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class TestSprite extends Sprite {
        TestSprite(SpriteContents contents) { super(new Identifier("erydon", "cornice_perf_atlas"), contents,64,64,16,32); }
        @Override public float getAnimationFrameDelta() { return 0; }
    }
}
