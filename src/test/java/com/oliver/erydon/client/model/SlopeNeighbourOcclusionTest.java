package com.oliver.erydon.client.model;

import com.oliver.erydon.block.*;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockRenderView;
import org.junit.jupiter.api.Test;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SlopeNeighbourOcclusionTest {
    private static List<Mesh> cachedMeshes;

    @Test
    void boundaryPredicateRejectsOnlyEnclosedOutwardFacesAndChangesNoAttributes() {
        for (Direction face : Direction.values()) {
            Quad quad = boundary(face);
            Quad before = quad.copy();
            assertFalse(SlopeNeighbourOcclusion.transform(bit(face)).transform(quad.view()));
            assertUnchanged(before, quad);
            assertTrue(SlopeNeighbourOcclusion.transform(bit(face.getOpposite())).transform(quad.view()));
            Collections.reverse(Arrays.asList(quad.vertices));
            assertTrue(SlopeNeighbourOcclusion.transform(63).transform(quad.view()), "Inward winding");
        }
        for (int x : new int[]{0, 180}) for (int y : new int[]{0, 90, 180, 270}) {
            Quad quad = rotate(boundary(Direction.NORTH), FixedSlopeRotation.of(x, y));
            assertFalse(SlopeNeighbourOcclusion.transform(63).transform(quad.view()));
        }
        Quad inset = boundary(Direction.NORTH);
        for (float[] v : inset.vertices) v[2] = 0.001F;
        assertTrue(SlopeNeighbourOcclusion.transform(63).transform(inset.view()));
        Quad overhang = boundary(Direction.NORTH);
        overhang.vertices[2][0] = 1.000625F;
        assertTrue(SlopeNeighbourOcclusion.transform(63).transform(overhang.view()));
        Quad ghost = boundary(Direction.NORTH);
        ghost.vertices[3][1] = -0.00001F;
        assertTrue(SlopeNeighbourOcclusion.transform(63).transform(ghost.view()));
        Quad diagonal = boundary(Direction.NORTH);
        diagonal.vertices[2][2] = 0.001F;
        assertTrue(SlopeNeighbourOcclusion.transform(63).transform(diagonal.view()));
        Quad invalid = boundary(Direction.NORTH);
        invalid.vertices[0][0] = Float.NaN;
        assertTrue(SlopeNeighbourOcclusion.transform(63).transform(invalid.view()));
        for (Direction face : List.of(Direction.UP, Direction.DOWN)) {
            Quad outset = boundary(face);
            for (float[] v : outset.vertices) v[1] += face.getOffsetY() * 0.0005F;
            assertFalse(SlopeNeighbourOcclusion.transform(bit(face)).transform(outset.view()));
            for (float[] v : outset.vertices) v[1] += face.getOffsetY() * 0.0001F;
            assertTrue(SlopeNeighbourOcclusion.transform(63).transform(outset.view()));
        }
        for (int mask = 0; mask < 64; mask++) {
            assertSame(SlopeNeighbourOcclusion.transform(mask), SlopeNeighbourOcclusion.transform(mask));
        }
    }

    @Test
    void emittedMeshesKeepAllSurvivorAttributesAcrossEveryVariantCornerAndRotation() throws Exception {
        int original = 0, retained = 0;
        List<Mesh> meshes = meshes();
        assertEquals(160, meshes.size());
        for (Mesh mesh : meshes) {
            int rejected = 0;
            for (Quad quad : mesh.quads) {
                Quad before = quad.copy();
                assertTrue(SlopeNeighbourOcclusion.transform(0).transform(quad.view()), mesh.label());
                boolean survives = SlopeNeighbourOcclusion.transform(63).transform(quad.view());
                assertUnchanged(before, quad);
                if (survives) retained++; else rejected++;
                original++;
            }
            assertTrue(rejected > 0, mesh.label());
            assertTrue(rejected < mesh.quads.size(), "Inclined surfaces must remain: " + mesh.label());
        }
        System.out.println("SLOPE_OCCLUSION_FULL_CUBE: meshes=160 emitted=" + original + " retained=" + retained);
    }

    @Test
    void actualOppositeExtrusionSurfacesHaveEqualDomainsIncludingPomTriangles() throws Exception {
        int pairs = 0, rejected = 0;
        for (Mesh mesh : meshes()) {
            boolean vertical = mesh.model == SlopeVerticalBakedModel.class && mesh.x == 0;
            boolean upper = mesh.model == ShallowSlopeBakedModel.class
                    && mesh.method.equals("emitNativeStraightSlope") && mesh.variant == 1;
            if (!vertical && !upper) continue;
            FixedSlopeRotation rotation = FixedSlopeRotation.of(mesh.x, mesh.y);
            Direction first = vertical ? Direction.UP : rotation.mapFace(Direction.NORTH);
            Direction second = first.getOpposite();
            Area a = boundaryArea(mesh.quads, first), b = boundaryArea(mesh.quads, second);
            assertFalse(a.isEmpty(), mesh.label());
            Area difference = new Area(a); difference.subtract(b);
            assertTrue(difference.isEmpty(), "Source exceeds opposite neighbour: " + mesh.label());
            difference = new Area(b); difference.subtract(a);
            assertTrue(difference.isEmpty(), "Opposite neighbour exceeds source: " + mesh.label());
            for (Quad quad : mesh.quads) {
                if (!SlopeNeighbourOcclusion.transform(bit(first) | bit(second)).transform(quad.view())) rejected++;
            }
            pairs++;
        }
        assertEquals(28, pairs);
        System.out.println("SLOPE_OCCLUSION_MATCHING_SEAMS: pairedMeshes=" + pairs + " rejected=" + rejected);
    }

    @Test
    void exposedFinFragmentsArePresentAndTheirSeamsRemainOutsideSupportedMatchingScope() throws Exception {
        for (Mesh mesh : meshes()) {
            if (!mesh.method.equals("emitNativeStraightSlope") || mesh.x != 0 || mesh.y != 0
                    || mesh.model == SlopeVerticalBakedModel.class) continue;
            int enclosed = 0, fins = 0;
            for (Quad quad : mesh.quads) {
                Direction face = quad.face();
                if (face != Direction.NORTH && face != Direction.SOUTH) continue;
                boolean inside = true;
                for (float[] v : quad.vertices) inside &= v[1] <= roof(mesh, v[0]) + 0.000001F;
                if (inside) enclosed++; else fins++;
            }
            if (mesh.model != ShallowSlopeBakedModel.class || mesh.variant == 0) {
                assertTrue(fins > 0, mesh.label());
            } else assertEquals(0, fins, mesh.label());
            System.out.println("SLOPE_OCCLUSION_ROOF: " + mesh.label() + " enclosed=" + enclosed + " finFragments=" + fins);
        }
    }

    // Invoked only by the bootstrap launch probe, never by plain JUnit.
    static void runNeighbourChecks() {
        BlockPos pos = new BlockPos(17, 80, -23);
        Block upper = new ShallowSlopeBlock(settings(), ShallowSlopeBlock.Variant.UPPER);
        BlockState source = upper.getDefaultState().with(ShallowSlopeBlock.FACING, Direction.EAST);
        for (BlockState neighbour : List.of(Blocks.AIR.getDefaultState(), Blocks.GLASS.getDefaultState(),
                Blocks.STONE_SLAB.getDefaultState(), Blocks.OAK_LEAVES.getDefaultState())) {
            assertEquals(0, SlopeNeighbourOcclusion.occludedNeighbourMask(view(pos, neighbour), source, pos));
        }
        assertEquals(63, SlopeNeighbourOcclusion.occludedNeighbourMask(view(pos, Blocks.STONE.getDefaultState()), source, pos));
        Capture untouched = new Capture();
        assertFalse(SlopeNeighbourOcclusion.push(view(pos, Blocks.AIR.getDefaultState()), source, pos, untouched.context()));
        assertEquals(0, untouched.transforms.size());
        int cases = 0;
        for (BlockHalf half : BlockHalf.values()) for (Direction facing : Direction.Type.HORIZONTAL) {
            BlockState state = upper.getDefaultState().with(ShallowSlopeBlock.HALF, half).with(ShallowSlopeBlock.FACING, facing);
            Direction lateral = facing.getAxis() == Direction.Axis.X ? Direction.NORTH : Direction.WEST;
            assertEquals(bit(lateral) | bit(lateral.getOpposite()), mask(pos, state));
            assertEquals(0, SlopeNeighbourOcclusion.occludedNeighbourMask(view(pos,
                    state.with(ShallowSlopeBlock.HALF, half == BlockHalf.TOP ? BlockHalf.BOTTOM : BlockHalf.TOP)), state, pos));
            assertEquals(0, mask(pos, state.with(ShallowSlopeBlock.SHAPE, ShallowSlopeBlock.SlopeShape.INNER_LEFT)));
            cases++;
        }
        for (Block block : List.of(new SlopeVerticalBlock(settings()), new SlopeVerticalShallowBroadBlock(settings()),
                new SlopeVerticalShallowNarrowBlock(settings()))) {
            for (BlockState state : block.getStateManager().getStates()) {
                assertEquals(bit(Direction.UP) | bit(Direction.DOWN), mask(pos, state));
                cases++;
            }
        }
        for (Block block : List.of(new SlopeBlock(settings()), new ShallowSlopeBlock(settings(), ShallowSlopeBlock.Variant.LOWER),
                new SlopeSteepBlock(settings(), SlopeSteepBlock.Variant.LOWER), new SlopeSteepBlock(settings(), SlopeSteepBlock.Variant.UPPER))) {
            for (BlockState state : block.getStateManager().getStates()) assertEquals(0, mask(pos, state));
        }
        BlockState cutout = new ShallowSlopeBlock(settings(), ShallowSlopeBlock.Variant.UPPER).getDefaultState();
        BlockRenderLayerMap.INSTANCE.putBlock(cutout.getBlock(), RenderLayer.getCutout());
        assertEquals(0, mask(pos, cutout));
        BlockRenderLayerMap.INSTANCE.putBlock(cutout.getBlock(), RenderLayer.getTranslucent());
        assertEquals(0, mask(pos, cutout));
        BlockState differentMaterial = new ShallowSlopeBlock(settings(), ShallowSlopeBlock.Variant.UPPER).getDefaultState()
                .with(ShallowSlopeBlock.FACING, Direction.EAST);
        assertEquals(0, SlopeNeighbourOcclusion.occludedNeighbourMask(view(pos, differentMaterial), source, pos));
        System.out.println("SLOPE_OCCLUSION_NEIGHBOURS: matchingStates=" + cases + " air/glass/partial/layers/mismatches/fins retained");
    }

    private static AbstractBlock.Settings settings() { return AbstractBlock.Settings.copy(Blocks.STONE).nonOpaque(); }
    private static int mask(BlockPos pos, BlockState state) { return SlopeNeighbourOcclusion.occludedNeighbourMask(view(pos, state), state, pos); }
    private static int bit(Direction face) { return 1 << face.ordinal(); }

    private static BlockRenderView view(BlockPos centre, BlockState neighbour) {
        return (BlockRenderView) Proxy.newProxyInstance(SlopeNeighbourOcclusionTest.class.getClassLoader(),
                new Class<?>[]{BlockRenderView.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getBlockState")) {
                        BlockPos actual = (BlockPos) args[0];
                        assertEquals(1, Math.abs(actual.getX() - centre.getX()) + Math.abs(actual.getY() - centre.getY())
                                + Math.abs(actual.getZ() - centre.getZ()), "Neighbour coordinates");
                        return neighbour;
                    }
                    throw new AssertionError("Unexpected world query: " + method);
                });
    }

    private static float roof(Mesh mesh, float x) {
        if (mesh.model == SlopeBakedModel.class) return 1 - x;
        if (mesh.model == ShallowSlopeBakedModel.class) return (mesh.variant == 1 ? 1 : 0.5F) - 0.5F * x;
        return mesh.variant == 1 ? Math.min(1, 2 * (1 - x)) : Math.max(0, 1 - 2 * x);
    }

    private static Area boundaryArea(List<Quad> quads, Direction face) {
        Area result = new Area();
        for (Quad quad : quads) {
            if (quad.face() != face) continue;
            for (int[] triangle : new int[][]{{0, 1, 2}, {0, 2, 3}}) {
                Path2D.Double path = new Path2D.Double();
                for (int i = 0; i < 3; i++) {
                    float[] v = quad.vertices[triangle[i]];
                    double a = v[face.getAxis() == Direction.Axis.X ? 2 : 0];
                    double b = v[face.getAxis() == Direction.Axis.Y ? 2 : 1];
                    if (i == 0) path.moveTo(a, b); else path.lineTo(a, b);
                }
                path.closePath(); result.add(new Area(path));
            }
        }
        return result;
    }

    private static List<Mesh> meshes() throws Exception {
        if (cachedMeshes != null) return cachedMeshes;
        List<Mesh> result = new ArrayList<>();
        for (Class<?> model : List.of(SlopeBakedModel.class, ShallowSlopeBakedModel.class, SlopeSteepBakedModel.class, SlopeVerticalBakedModel.class)) {
            Class<?> spritesType = Class.forName(model.getName() + "$FaceSprites");
            Object sprites = Proxy.newProxyInstance(model.getClassLoader(), new Class<?>[]{spritesType}, (p, m, a) -> null);
            for (Method method : model.getDeclaredMethods()) {
                if (!List.of("emitNativeStraightSlope", "emitNativeOuterCorner", "emitNativeInnerCorner", "emitPrism").contains(method.getName())) continue;
                method.setAccessible(true);
                for (int x : new int[]{0, 180}) for (int y : new int[]{0, 90, 180, 270}) {
                    int variants = method.getParameterCount() == 3 ? 1 : method.getName().equals("emitPrism") ? 5 : 2;
                    for (int variant = 0; variant < variants; variant++) {
                        Capture capture = new Capture();
                        List<Object> args = new ArrayList<>(List.of(capture.context(), sprites, FixedSlopeRotation.of(x, y)));
                        if (method.getName().equals("emitPrism")) args.add(verticalPoints(model, variant));
                        else if (variants == 2) args.add(variant == 1);
                        method.invoke(null, args.toArray());
                        result.add(new Mesh(model, method.getName(), variant, x, y, capture.quads));
                    }
                }
            }
        }
        return cachedMeshes = result;
    }

    private static Object verticalPoints(Class<?> model, int variant) throws Exception {
        Method factory = model.getDeclaredMethod(variant == 0 ? "vertical" : variant <= 2 ? "broadRight" : "narrowRight");
        factory.setAccessible(true); Object points = factory.invoke(null);
        if (variant == 2 || variant == 4) {
            Method mirror = model.getDeclaredMethod("mirrorZ", points.getClass());
            mirror.setAccessible(true); points = mirror.invoke(null, points);
        }
        return points;
    }

    private static Quad boundary(Direction face) {
        Quad q = new Quad();
        float[][] positions = {{0.1F, 0.1F, 0}, {0.1F, 0.8F, 0}, {0.7F, 0.8F, 0}, {0.7F, 0.1F, 0}};
        for (int v = 0; v < 4; v++) {
            float a = positions[v][0], b = positions[v][1];
            float[] p = switch (face) {
                case NORTH -> new float[]{a, b, 0};
                case SOUTH -> new float[]{1 - a, b, 1};
                case WEST -> new float[]{0, b, 1 - a};
                case EAST -> new float[]{1, b, a};
                case UP -> new float[]{a, 1, b};
                case DOWN -> new float[]{a, 0, 1 - b};
            };
            System.arraycopy(p, 0, q.vertices[v], 0, 3);
            q.vertices[v][3] = v * 0.173F; q.vertices[v][4] = v * 0.219F;
            q.vertices[v][5] = face.getOffsetX(); q.vertices[v][6] = face.getOffsetY(); q.vertices[v][7] = face.getOffsetZ();
            q.normals[v] = true; q.colors[v] = 0x12345678 + v; q.lightmaps[v] = 0x00F000A0 + v;
        }
        q.properties.put("nominalFace", face); q.properties.put("cullFace", null);
        q.properties.put("tag", 17); q.properties.put("colorIndex", 3);
        return q;
    }

    private static Quad rotate(Quad source, FixedSlopeRotation rotation) {
        Quad result = source.copy();
        for (float[] p : result.vertices) {
            float x = p[0], y = p[1], z = p[2];
            p[0] = rotation.positionX(x, y, z); p[1] = rotation.positionY(x, y, z); p[2] = rotation.positionZ(x, y, z);
        }
        result.properties.put("nominalFace", rotation.mapFace(source.face()));
        return result;
    }

    private static void assertUnchanged(Quad expected, Quad actual) {
        for (int v = 0; v < 4; v++) for (int c = 0; c < 8; c++) {
            assertEquals(Float.floatToRawIntBits(expected.vertices[v][c]), Float.floatToRawIntBits(actual.vertices[v][c]));
        }
        assertArrayEquals(expected.normals, actual.normals); assertArrayEquals(expected.colors, actual.colors);
        assertArrayEquals(expected.lightmaps, actual.lightmaps); assertEquals(expected.properties, actual.properties);
    }

    private record Mesh(Class<?> model, String method, int variant, int x, int y, List<Quad> quads) {
        String label() { return model.getSimpleName() + "/" + method + "/" + variant + "/" + x + "/" + y; }
    }

    private static final class Quad {
        float[][] vertices = new float[4][8];
        boolean[] normals = new boolean[4];
        int[] colors = new int[4], lightmaps = new int[4];
        final Map<String, Object> properties = new HashMap<>();
        Direction face() { return (Direction) properties.get("nominalFace"); }
        Quad copy() {
            Quad q = new Quad(); q.vertices = Arrays.stream(vertices).map(float[]::clone).toArray(float[][]::new);
            q.normals = normals.clone(); q.colors = colors.clone(); q.lightmaps = lightmaps.clone(); q.properties.putAll(properties);
            return q;
        }
        Object get(Method method, Object[] args) {
            String name = method.getName();
            int component = List.of("x", "y", "z", "u", "v", "normalX", "normalY", "normalZ").indexOf(name);
            if (component >= 0) return vertices[(int) args[0]][component];
            if (name.equals("hasNormal")) return normals[(int) args[0]];
            if (name.equals("color")) return colors[(int) args[0]];
            if (name.equals("lightmap")) return lightmaps[(int) args[0]];
            if (args == null || args.length == 0) return properties.getOrDefault(name, method.getReturnType() == int.class ? 0 : null);
            throw new AssertionError("Occlusion tried to mutate a quad: " + method);
        }
        MutableQuadView view() {
            return (MutableQuadView) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{MutableQuadView.class}, (p, m, a) -> get(m, a));
        }
    }

    private static final class Capture {
        Quad data = new Quad();
        final List<Quad> quads = new ArrayList<>();
        final List<RenderContext.QuadTransform> transforms = new ArrayList<>();
        final QuadEmitter emitter = (QuadEmitter) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{QuadEmitter.class}, (proxy, method, args) -> {
            String name = method.getName(); int count = args == null ? 0 : args.length;
            if (name.equals("emit")) {
                boolean keep = true;
                for (int i = transforms.size() - 1; i >= 0 && keep; i--) keep = transforms.get(i).transform(data.view());
                if (keep) quads.add(data.copy()); data = new Quad();
            } else if (name.equals("pos") || name.equals("uv") || name.equals("normal")) {
                int offset = name.equals("pos") ? 0 : name.equals("uv") ? 3 : 5;
                for (int i = 1; i < count; i++) data.vertices[(int) args[0]][offset + i - 1] = (float) args[i];
                if (name.equals("normal")) data.normals[(int) args[0]] = true;
            } else if (name.equals("color") && count == 4) for (int i = 0; i < 4; i++) data.colors[i] = (int) args[i];
            else if ((name.equals("color") || name.equals("lightmap")) && count == 2) {
                (name.equals("color") ? data.colors : data.lightmaps)[(int) args[0]] = (int) args[1];
            } else if (List.of("material", "nominalFace", "cullFace", "colorIndex", "tag").contains(name) && count == 1) data.properties.put(name, args[0]);
            else if (!name.equals("spriteBake")) return data.get(method, args);
            return proxy;
        });
        RenderContext context() {
            return (RenderContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{RenderContext.class}, (p, m, a) -> {
                if (m.getName().equals("pushTransform")) { transforms.add((RenderContext.QuadTransform) a[0]); return null; }
                if (m.getName().equals("popTransform")) { transforms.remove(transforms.size() - 1); return null; }
                if (m.getName().equals("getEmitter")) return emitter;
                throw new AssertionError("Unexpected render call: " + m);
            });
        }
    }
}
