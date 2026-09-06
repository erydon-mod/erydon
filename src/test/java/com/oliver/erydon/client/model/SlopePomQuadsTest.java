package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SlopePomQuadsTest {
    @Test
    void everyEmittedSlopeFaceHasStablePomBoundsAcrossRotationsAndVariants() throws Exception {
        int meshes = 0;
        for (Class<?> model : List.of(SlopeBakedModel.class, ShallowSlopeBakedModel.class,
                SlopeSteepBakedModel.class, SlopeVerticalBakedModel.class)) {
            Class<?> spritesType = Class.forName(model.getName() + "$FaceSprites");
            Object sprites = Proxy.newProxyInstance(model.getClassLoader(),
                    new Class<?>[]{spritesType}, (proxy, method, args) -> null);
            for (Method method : model.getDeclaredMethods()) {
                if (!List.of("emitNativeStraightSlope", "emitNativeOuterCorner",
                        "emitNativeInnerCorner", "emitPrism").contains(method.getName())) continue;
                method.setAccessible(true);
                for (int x : new int[]{0, 180}) {
                    for (int y : new int[]{0, 90, 180, 270}) {
                        int variants = method.getParameterCount() == 3 ? 1
                                : method.getName().equals("emitPrism") ? 5 : 2;
                        for (int variant = 0; variant < variants; variant++) {
                            Capture capture = new Capture();
                            RenderContext context = (RenderContext) Proxy.newProxyInstance(
                                    model.getClassLoader(), new Class<?>[]{RenderContext.class},
                                    (proxy, invoked, args) -> capture.emitter);
                            List<Object> args = new ArrayList<>(List.of(context, sprites, FixedSlopeRotation.of(x, y)));
                            if (method.getName().equals("emitPrism")) {
                                args.add(verticalPoints(model, variant));
                            } else if (variants == 2) args.add(variant == 1);
                            method.invoke(null, args.toArray());
                            assertFalse(capture.quads.isEmpty());
                            String label = model.getSimpleName() + " " + method.getName() + " " + x + "/" + y + " " + variant;
                            for (float[][] quad : capture.quads) validate(quad, label);
                            double beforeArea = capture.sources.stream().mapToDouble(q ->
                                    area(q[0], q[1], q[2]) + area(q[0], q[2], q[3])).sum();
                            double afterArea = capture.quads.stream().mapToDouble(q ->
                                    area(q[0], q[1], q[2]) + area(q[0], q[2], q[3])).sum();
                            assertEquals(beforeArea, afterArea, 0.00001, "Surface area changed: " + label);
                            assertTrue(capture.quads.size() <= capture.sourceQuads * 3, "Unexpected subdivision growth: " + label);
                            meshes++;
                        }
                    }
                }
            }
        }
        assertEquals(160, meshes);
    }

    @Test
    void trapezoidSplitPreservesAreaAndVisibleUvMapping() {
        Capture capture = new Capture();
        float[][] source = {{0, 0, 0, 0, 0}, {1, 0, 0, 16, 0},
                {1, 0.001F, 0, 16, 0.016F}, {0, 1, 0, 0, 16}};
        for (int i = 0; i < 4; i++) {
            capture.emitter.pos(i, source[i][0], source[i][1], source[i][2]);
            capture.emitter.uv(i, source[i][3], source[i][4]);
        }
        SlopePomQuads.emit(capture.emitter, null);
        assertEquals(2, capture.quads.size());
        double area = 0;
        for (float[][] quad : capture.quads) {
            validate(quad, "side trapezoid");
            area += area(quad[0], quad[1], quad[2]) + area(quad[0], quad[2], quad[3]);
            int visibleVertices = Arrays.equals(Arrays.copyOf(quad[2], 3), Arrays.copyOf(quad[3], 3)) ? 3 : 4;
            for (int i = 0; i < visibleVertices; i++) {
                assertEquals(quad[i][0] * 16, quad[i][3], 0.0001);
                assertEquals(quad[i][1] * 16, quad[i][4], 0.0001);
            }
        }
        assertEquals(0.5005, area, 0.00001);
    }

    private static Object verticalPoints(Class<?> model, int variant) throws Exception {
        String shape = variant == 0 ? "vertical" : variant <= 2 ? "broadRight" : "narrowRight";
        Method factory = model.getDeclaredMethod(shape);
        factory.setAccessible(true);
        Object points = factory.invoke(null);
        if (variant == 2 || variant == 4) {
            Method mirror = model.getDeclaredMethod("mirrorZ", points.getClass());
            mirror.setAccessible(true);
            points = mirror.invoke(null, points);
        }
        return points;
    }

    private static void validate(float[][] quad, String label) {
        for (int axis = 3; axis <= 4; axis++) {
            float mid = 0;
            for (float[] vertex : quad) mid += vertex[axis] * 0.25F;
            float radius = Math.abs(quad[0][axis] - mid);
            assertTrue(radius > 0, label);
            for (float[] vertex : quad) {
                assertEquals(radius, Math.abs(vertex[axis] - mid), 0.0001, label);
                assertTrue(vertex[axis] >= -0.0001F && vertex[axis] <= 16.0001F, label);
            }
        }
        assertTrue(area(quad[0], quad[1], quad[2]) > 1e-12, "Degenerate tangent: " + label);
    }

    private static double area(float[] a, float[] b, float[] c) {
        double x = (b[1]-a[1])*(c[2]-a[2]) - (b[2]-a[2])*(c[1]-a[1]);
        double y = (b[2]-a[2])*(c[0]-a[0]) - (b[0]-a[0])*(c[2]-a[2]);
        double z = (b[0]-a[0])*(c[1]-a[1]) - (b[1]-a[1])*(c[0]-a[0]);
        return Math.sqrt(x*x + y*y + z*z) * 0.5;
    }

    private static final class Capture {
        int sourceQuads;
        float[][] data = new float[4][8];
        boolean[] normals = new boolean[4];
        final List<float[][]> quads = new ArrayList<>();
        final List<float[][]> sources = new ArrayList<>();
        final Map<String, Object> properties = new HashMap<>();
        final QuadEmitter emitter = (QuadEmitter) Proxy.newProxyInstance(
                QuadEmitter.class.getClassLoader(), new Class<?>[]{QuadEmitter.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    int count = args == null ? 0 : args.length;
                    if (name.equals("emit")) {
                        quads.add(Arrays.stream(data).map(float[]::clone).toArray(float[][]::new));
                        data = new float[4][8];
                        normals = new boolean[4];
                        properties.clear();
                    } else if (name.equals("pos") || name.equals("uv") || name.equals("normal")) {
                        int offset = name.equals("pos") ? 0 : name.equals("uv") ? 3 : 5;
                        for (int i = 1; i < count; i++) data[(int) args[0]][offset+i-1] = (float) args[i];
                        if (name.equals("normal")) normals[(int) args[0]] = true;
                    } else if (List.of("x", "y", "z", "u", "v", "normalX", "normalY", "normalZ").contains(name)) {
                        return data[(int) args[0]][List.of("x", "y", "z", "u", "v", "normalX", "normalY", "normalZ").indexOf(name)];
                    } else if (name.equals("hasNormal")) return normals[(int) args[0]];
                    else if (List.of("material", "nominalFace", "cullFace", "colorIndex", "tag").contains(name)) {
                        if (count == 1) properties.put(name, args[0]);
                        else return properties.getOrDefault(name, method.getReturnType() == int.class ? 0 : null);
                    } else if (name.equals("color") && count == 4) {
                        sourceQuads++;
                        sources.add(Arrays.stream(data).map(float[]::clone).toArray(float[][]::new));
                    }
                    else if (name.equals("color") && count == 1) return -1;
                    else if (name.equals("lightmap") && count == 1) return 0;
                    return proxy;
                });
    }
}
