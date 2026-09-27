package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class InlaySubstrateProjectionTest {
    @Test void masterOffCompositeSeparatesAlongTheTrueSlopeNormalWhileFallbackStaysUnchanged() {
        float[][] points = points(Direction.UP, false);
        float[][] original = java.util.Arrays.stream(points).map(float[]::clone).toArray(float[][]::new);
        var quad = quad(points, new float[4][2]);
        SynapheiaRepeatBakedModel.offsetSourceOverlay(quad, Direction.UP, false, -1);
        for (int i = 0; i < 4; i++) assertArrayEquals(original[i], points[i]);
        SynapheiaRepeatBakedModel.offsetSourceOverlay(quad, Direction.UP, false, 12);
        float dx = points[0][0] - original[0][0], dy = points[0][1] - original[0][1];
        assertTrue(dx < 0 && dy > 0, "Sloped composite must move along its normal, not just the vertical axis");
        assertEquals(-0.5F, dx / dy, 0.0001F);
        assertEquals(1.0F / 1024.0F, Math.sqrt(dx * dx + dy * dy), 0.0000001F);
        for (int i = 0; i < 4; i++) {
            assertEquals(dx, points[i][0] - original[i][0], 0.0000001F);
            assertEquals(dy, points[i][1] - original[i][1], 0.0000001F);
            assertEquals(original[i][2], points[i][2]);
        }
    }

    @Test void slopeBaseRuleUsesOnlyItsExactAuthoredParticleAndNeverRepeatsAnExistingOutput() {
        Identifier block = new Identifier("erydon", "nerium_trim_bronze_slope");
        Identifier particle = new Identifier("erydon", "block/nerium_block_bronzetrim");
        var base = new SynapheiaManifest.Rule(new Identifier("minecraft", "optifine/ctm/nerium/base.properties"),
                "test", SynapheiaManifest.Method.REPEAT, IntStream.range(0, 36)
                .mapToObj(i -> new Identifier("minecraft", "optifine/ctm/nerium/" + i)).toList(),
                Set.of(Direction.values()), Set.of(block), Set.of(particle), false, 0);
        var overlay = new SynapheiaManifest.Rule(new Identifier("minecraft", "optifine/ctm/nerium/overlay.properties"),
                "test", SynapheiaManifest.Method.OVERLAY_CTM, IntStream.range(0, 47)
                .mapToObj(i -> new Identifier("minecraft", "optifine/ctm/overlay/trim/bronze/" + i)).toList(),
                Set.of(Direction.values()), Set.of(block), Set.of(particle), SynapheiaManifest.OverlayShape.SOURCE, false, 20);
        var plan = SynapheiaBlockPlan.compile(block, List.of(base, overlay));
        Identifier missing = new Identifier("minecraft", "missingno");
        assertSame(base, SynapheiaRepeatBakedModel.resolveRepeatRule(plan, Direction.UP, missing, particle));
        assertNull(SynapheiaRepeatBakedModel.resolveRepeatRule(plan, Direction.UP, base.tiles().get(17), particle));
        assertNull(SynapheiaRepeatBakedModel.resolveRepeatRule(plan, Direction.UP, missing,
                new Identifier("erydon", "block/other")));
        assertNull(SynapheiaRepeatBakedModel.resolveRepeatRule(SynapheiaBlockPlan.compile(block, List.of(base)),
                Direction.UP, missing, particle));
    }

    @Test void everySharedOverlayHasTheCorrespondingRepeatBaseForItsAuthoredTexture() throws Exception {
        Path root = Path.of("src/main/resources/assets/minecraft");
        List<SynapheiaManifest.Rule> rules = new ArrayList<>();
        try (var paths = Files.walk(root.resolve("optifine/ctm"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".properties")).toList()) {
                Properties properties = new Properties();
                try (var stream = Files.newInputStream(path)) { properties.load(stream); }
                var blocks = SynapheiaManifest.parseBlocks(properties.getProperty("matchBlocks"));
                if (blocks.isEmpty()) continue;
                rules.add(SynapheiaManifest.parseRule(new Identifier("minecraft", root.relativize(path).toString().replace('\\', '/')),
                        "test", properties, blocks));
            }
        }
        int overlays = 0;
        for (var overlay : rules) {
            if (overlay.method() != SynapheiaManifest.Method.OVERLAY_CTM) continue;
            overlays++;
            assertEquals(SynapheiaManifest.OverlayShape.SOURCE, overlay.overlayShape());
            for (var block : overlay.blocks()) {
                var plan = SynapheiaBlockPlan.compile(block, rules.stream().filter(r -> r.blocks().contains(block)).toList());
                for (var face : overlay.faces()) for (var source : overlay.matchTiles()) {
                    assertNotNull(plan.repeatRule(face, source), block + " " + face + " " + source);
                }
            }
        }
        assertEquals(192, overlays, "Audit all current shared inlay overlay rules");
    }

    @Test void flatPartialSlopedAndGhostTriangleCoordinatesShareTheBaseProjectionOnEveryFace() {
        for (Direction face : Direction.values()) {
            for (boolean triangle : List.of(false, true)) {
                float[][] points = points(face, triangle);
                float[][] uv = new float[4][2];
                QuadView quad = quad(points, uv);
                var cell = SynapheiaCellGeometry.singleCell(face, quad);
                assertNotNull(cell);
                for (int i = 0; i < 4; i++) {
                    uv[i][0] = 0.25F + 0.125F * SynapheiaCellGeometry.u(face, quad, i, cell);
                    uv[i][1] = 0.5F + 0.0625F * SynapheiaCellGeometry.v(face, quad, i, cell);
                }
                if (triangle) { uv[3][0] = 0.35F; uv[3][1] = 0.56F; }
                assertTrue(SynapheiaRepeatBakedModel.substrateProjectionMatches(quad, face, cell, .25F, .5F, .125F, .0625F));
                uv[1][0] += .01F;
                assertFalse(SynapheiaRepeatBakedModel.substrateProjectionMatches(quad, face, cell, .25F, .5F, .125F, .0625F),
                        "A differently oriented or cropped texture must not be treated as the substrate");
            }
        }
    }

    private static float[][] points(Direction face, boolean triangle) {
        float[][] result = new float[4][3];
        float[][] projected = {{0, .25F}, {0, 1}, {1, 1}, {1, .25F}};
        for (int i = 0; i < 4; i++) {
            float s = projected[i][0], t = projected[i][1], depth = .2F + .5F * s;
            result[i] = switch (face.getAxis()) {
                case X -> new float[]{depth, t, s};
                case Y -> new float[]{s, depth, t};
                case Z -> new float[]{s, t, depth};
            };
        }
        if (triangle) result[3] = result[2].clone();
        return result;
    }

    private static MutableQuadView quad(float[][] points, float[][] uv) {
        return (MutableQuadView) Proxy.newProxyInstance(QuadView.class.getClassLoader(), new Class<?>[]{MutableQuadView.class},
                (proxy, method, arguments) -> {
                    int vertex = arguments == null || arguments.length == 0 ? 0 : (int) arguments[0];
                    return switch (method.getName()) {
                        case "x" -> points[vertex][0]; case "y" -> points[vertex][1]; case "z" -> points[vertex][2];
                        case "u" -> uv[vertex][0]; case "v" -> uv[vertex][1];
                        case "pos" -> {
                            points[vertex][0] = (float) arguments[1]; points[vertex][1] = (float) arguments[2];
                            points[vertex][2] = (float) arguments[3]; yield proxy;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }
}
