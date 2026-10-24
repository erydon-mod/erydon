package com.oliver.erydon.client.model;

import com.oliver.erydon.block.WindowArchBlock;
import com.oliver.erydon.block.WindowFrenchGeorgianBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BakedQuadFactory;
import net.minecraft.client.render.model.ModelBakeSettings;
import net.minecraft.client.render.model.json.JsonUnbakedModel;
import net.minecraft.client.render.model.json.ModelElement;
import net.minecraft.client.render.model.json.ModelElementFace;
import net.minecraft.client.render.model.json.ModelElementTexture;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.List;
import java.util.EnumMap;
import java.util.function.Function;
import org.joml.Vector3f;

/** Cached lower sash rails, preserving the authored fixed jamb feet and open threshold. */
public final class WindowBottomRail {
    private static final double AREA_EPS = 1.0e-12;
    // These are the existing authored sill_lh/rh leaves: the lower rails of the OPEN sash.
    private static final Box LEFT_SASH = new Box(13.032F / 16, 0, 1F / 16, 15.968F / 16, 1.5F / 16, 15F / 16);
    private static final Box RIGHT_SASH = new Box(.048F / 16, 0, 1F / 16, 2.952F / 16, 1.5F / 16, 15F / 16);
    private static final Box CLOSED_SASH = new Box(0, 0, 0, 1, 1.5F / 16, 3F / 16);

    private WindowBottomRail() { }

    /** The input has already received conditional missing-bottom caps. */
    public static BakedModel wrapChild(BakedModel originalAlreadyCaps, BlockState seed) {
        if (!(seed.getBlock() instanceof WindowFrenchGeorgianBlock)) return originalAlreadyCaps;
        BlockState sample = seed.with(WindowFrenchGeorgianBlock.SILL, true).with(WindowFrenchGeorgianBlock.OPEN, true)
                .with(WindowFrenchGeorgianBlock.SILL_SIDE, WindowFrenchGeorgianBlock.SillSide.NONE);
        List<BakedQuad> stream = sourceQuads(originalAlreadyCaps, sample);
        Box sash = movingRail(stream);
        if (sash != null) return new VariantModel(originalAlreadyCaps, clipChild(stream, sash), false);
        // A closed base pane spans X and remains in the native 3-pixel frame plane.
        // Its lower rail has the same height/depth as the accepted opened sash.
        if (stream.stream().anyMatch(quad -> quad.getColorIndex() == 0
                && vertices(quad).stream().anyMatch(v -> v.coordinate(1) <= .02F / 16)
                && vertices(quad).stream().allMatch(v -> v.coordinate(2) >= 0 && v.coordinate(2) <= 3F / 16)))
            return new VariantModel(originalAlreadyCaps, clipChild(stream, CLOSED_SASH), false, true, List.of());
        return originalAlreadyCaps;
    }

    /** Retain existing sill choices; a closed no-sill sash gets its own shallow bottom frame. */
    public static BakedModel wrapSill(BakedModel original, JsonUnbakedModel source,
                                      Function<SpriteIdentifier, Sprite> textures, ModelBakeSettings settings,
                                      Identifier id, BlockState seed) {
        if (!(seed.getBlock() instanceof WindowArchBlock) && !(seed.getBlock() instanceof WindowFrenchGeorgianBlock)) return original;
        if (seed.getBlock() instanceof WindowFrenchGeorgianBlock) {
            Sprite stone = textures.apply(source.resolveSprite("#stone"));
            List<List<BakedQuad>> rails = new ArrayList<>();
            for (var corner : WindowFrenchGeorgianBlock.Corner.values()) rails.add(closedRail(stone, settings, id, corner));
            return new VariantModel(original, List.of(), true, false, List.copyOf(rails));
        }
        // The native arch frame already ends in complete jamb feet.
        return new VariantModel(original, List.of(), true);
    }

    /** A union boundary, baked once; two corner arms never leave coplanar duplicate bottoms. */
    private static List<BakedQuad> closedRail(Sprite stone, ModelBakeSettings settings, Identifier id,
                                             WindowFrenchGeorgianBlock.Corner corner) {
        float[] xs = corner == WindowFrenchGeorgianBlock.Corner.NONE ? new float[]{0, 16}
                : corner == WindowFrenchGeorgianBlock.Corner.LEFT ? new float[]{0, 3, 16} : new float[]{0, 13, 16};
        float[] zs = corner == WindowFrenchGeorgianBlock.Corner.NONE ? new float[]{0, 3} : new float[]{0, 3, 16};
        boolean[][] occupied = new boolean[xs.length - 1][zs.length - 1];
        for (int x = 0; x < occupied.length; x++) for (int z = 0; z < occupied[x].length; z++)
            occupied[x][z] = z == 0 || corner == WindowFrenchGeorgianBlock.Corner.LEFT && x == 0
                    || corner == WindowFrenchGeorgianBlock.Corner.RIGHT && x == occupied.length - 1;
        List<BakedQuad> result = new ArrayList<>();
        BakedQuadFactory factory = new BakedQuadFactory();
        for (int x = 0; x < occupied.length; x++) for (int z = 0; z < occupied[x].length; z++) if (occupied[x][z]) {
            var faces = new EnumMap<Direction, ModelElementFace>(Direction.class);
            for (Direction face : Direction.values()) {
                int nx = x + face.getOffsetX(), nz = z + face.getOffsetZ();
                if (face.getAxis() != Direction.Axis.Y && nx >= 0 && nx < occupied.length
                        && nz >= 0 && nz < occupied[nx].length && occupied[nx][nz]) continue;
                faces.put(face, new ModelElementFace(null, -1, "#stone", new ModelElementTexture(null, 0)));
            }
            var element = new ModelElement(new Vector3f(xs[x], 0, zs[z]), new Vector3f(xs[x + 1], 1.5F, zs[z + 1]), faces, null, true);
            for (var face : element.faces.entrySet()) result.add(factory.bake(element.from, element.to, face.getValue(),
                    stone, face.getKey(), settings, null, true, id));
        }
        return List.copyOf(result);
    }

    private static Box movingRail(List<BakedQuad> source) {
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (BakedQuad quad : source) if (quad.getColorIndex() == 0) {
            List<Vertex> corners = vertices(quad);
            // Upper leaves also contain the fixed fanlight. Only the pane that
            // reaches the bottom can own this moving sash's lower rail.
            if (corners.stream().noneMatch(vertex -> vertex.coordinate(1) <= .02F / 16)) continue;
            for (Vertex vertex : corners) {
                minX = Math.min(minX, vertex.coordinate(0)); maxX = Math.max(maxX, vertex.coordinate(0));
                minZ = Math.min(minZ, vertex.coordinate(2)); maxZ = Math.max(maxZ, vertex.coordinate(2));
            }
        }
        if (maxX - minX > 3 / 16F || maxZ - minZ < .5F) return null;
        return (minX + maxX) / 2 > .5F ? LEFT_SASH : RIGHT_SASH;
    }

    private static List<BakedQuad> sourceQuads(BakedModel model, BlockState sample) {
        List<BakedQuad> result = new ArrayList<>();
        for (Direction bucket : new Direction[]{null, Direction.DOWN, Direction.UP,
                Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST})
            result.addAll(model.getQuads(sample, bucket, Random.create(0)));
        return List.copyOf(result);
    }

    private static List<BakedQuad> clipChild(List<BakedQuad> source, Box box) {
        List<BakedQuad> result = new ArrayList<>();
        for (BakedQuad quad : source) {
            List<Vertex> polygon = vertices(quad);
            if (disjoint(polygon, box)) { result.add(quad); continue; }
            List<Vertex> remaining = polygon;
            for (int axis = 0; axis < 3 && !remaining.isEmpty(); axis++) for (int side = 0; side < 2 && !remaining.isEmpty(); side++) {
                float boundary = side == 0 ? box.min(axis) : box.max(axis);
                boolean greater = side == 0;
                List<Vertex> outside = clip(remaining, axis, boundary, greater, false);
                addQuads(result, quad, outside);
                remaining = clip(remaining, axis, boundary, greater, true);
            }
            // The rail supplies every boundary of the removed solid portion.
        }
        return List.copyOf(result);
    }

    private static boolean disjoint(List<Vertex> polygon, Box box) {
        for (int axis = 0; axis < 3; axis++) {
            float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
            for (Vertex vertex : polygon) { min = Math.min(min, vertex.coordinate(axis)); max = Math.max(max, vertex.coordinate(axis)); }
            if (max < box.min(axis) || min > box.max(axis)) return true;
        }
        return false;
    }

    private static List<Vertex> clip(List<Vertex> polygon, int axis, float boundary, boolean greater, boolean inside) {
        List<Vertex> result = new ArrayList<>(polygon.size() + 1);
        Vertex previous = polygon.get(polygon.size() - 1);
        float previousSide = (previous.coordinate(axis) - boundary) * (greater ? 1 : -1);
        boolean previousInside = inside ? previousSide >= 0 : previousSide < 0;
        for (Vertex current : polygon) {
            float currentSide = (current.coordinate(axis) - boundary) * (greater ? 1 : -1);
            boolean currentInside = inside ? currentSide >= 0 : currentSide < 0;
            if (currentInside != previousInside) {
                double amount = (double) previousSide / (previousSide - (double) currentSide);
                append(result, previous.interpolate(current, amount, axis, boundary));
            }
            if (currentInside) append(result, current);
            previous = current; previousSide = currentSide; previousInside = currentInside;
        }
        if (result.size() > 1 && same(result.get(0), result.get(result.size() - 1))) result.remove(result.size() - 1);
        return result;
    }

    private static List<Vertex> vertices(BakedQuad quad) {
        int[] packed = quad.getVertexData(); int stride = packed.length / 4;
        List<Vertex> result = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) { int[] data = new int[stride]; System.arraycopy(packed, i * stride, data, 0, stride); result.add(new Vertex(data)); }
        return result;
    }

    private static void append(List<Vertex> result, Vertex vertex) {
        if (result.isEmpty() || !same(result.get(result.size() - 1), vertex)) result.add(vertex);
    }

    private static boolean same(Vertex a, Vertex b) {
        return a.coordinate(0) == b.coordinate(0) && a.coordinate(1) == b.coordinate(1) && a.coordinate(2) == b.coordinate(2);
    }

    private static void addQuads(List<BakedQuad> output, BakedQuad prototype, List<Vertex> polygon) {
        if (polygon.size() < 3) return;
        if (polygon.size() == 4) addQuad(output, prototype, polygon.get(0), polygon.get(1), polygon.get(2), polygon.get(3));
        else for (int i = 1; i + 1 < polygon.size(); i++)
            addQuad(output, prototype, polygon.get(0), polygon.get(i), polygon.get(i + 1), polygon.get(i + 1));
    }

    private static void addQuad(List<BakedQuad> output, BakedQuad prototype, Vertex a, Vertex b, Vertex c, Vertex d) {
        if (area(a, b, c) + area(a, c, d) <= AREA_EPS) return;
        int stride = a.data.length; int[] data = new int[stride * 4]; Vertex[] vertices = {a, b, c, d};
        for (int i = 0; i < 4; i++) System.arraycopy(vertices[i].data, 0, data, i * stride, stride);
        output.add(new BakedQuad(data, prototype.getColorIndex(), prototype.getFace(), prototype.getSprite(), prototype.hasShade()));
    }

    private static double area(Vertex a, Vertex b, Vertex c) {
        double x = b.coordinate(0) - (double) a.coordinate(0), y = b.coordinate(1) - (double) a.coordinate(1), z = b.coordinate(2) - (double) a.coordinate(2);
        double u = c.coordinate(0) - (double) a.coordinate(0), v = c.coordinate(1) - (double) a.coordinate(1), w = c.coordinate(2) - (double) a.coordinate(2);
        double nx = y * w - z * v, ny = z * u - x * w, nz = x * v - y * u;
        return Math.sqrt(nx * nx + ny * ny + nz * nz) / 2;
    }

    private static boolean noSill(BlockState state, boolean arch) {
        if (state == null) return false;
        if (arch) return state.getBlock() instanceof WindowArchBlock && state.get(WindowArchBlock.SILL) && !state.get(WindowArchBlock.SILL_ENABLED);
        return state.getBlock() instanceof WindowFrenchGeorgianBlock && state.get(WindowFrenchGeorgianBlock.SILL)
                && state.get(WindowFrenchGeorgianBlock.SILL_SIDE) == WindowFrenchGeorgianBlock.SillSide.NONE;
    }

    private record Box(float x0, float y0, float z0, float x1, float y1, float z1) {
        float min(int axis) { return axis == 0 ? x0 : axis == 1 ? y0 : z0; }
        float max(int axis) { return axis == 0 ? x1 : axis == 1 ? y1 : z1; }
    }

    private record Vertex(int[] data) {
        float coordinate(int axis) { return Float.intBitsToFloat(data[axis]); }
        Vertex interpolate(Vertex other, double amount, int axis, float boundary) {
            double t = Math.max(0, Math.min(1, amount)); int[] result = data.clone();
            // Authored baked leaves/caps have uniform vertex colour/light/normal.
            for (int slot : new int[]{0, 1, 2, 4, 5}) {
                double start = Float.intBitsToFloat(data[slot]), end = Float.intBitsToFloat(other.data[slot]);
                result[slot] = Float.floatToRawIntBits((float) (start + (end - start) * t));
            }
            result[axis] = Float.floatToRawIntBits(boundary);
            return new Vertex(result);
        }
    }

    private static final class VariantModel implements BakedModel {
        private final BakedModel original;
        private final List<BakedQuad> quads;
        private final boolean sill;
        private final boolean closed;
        private final List<List<BakedQuad>> cornerRails;
        VariantModel(BakedModel original, List<BakedQuad> quads, boolean sill) {
            this(original, quads, sill, false, List.of());
        }
        VariantModel(BakedModel original, List<BakedQuad> quads, boolean sill, boolean closed, List<List<BakedQuad>> cornerRails) {
            this.original = original; this.quads = quads; this.sill = sill; this.closed = closed; this.cornerRails = cornerRails;
        }
        @Override public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
            boolean active = sill ? noSill(state, state != null && state.getBlock() instanceof WindowArchBlock)
                    : state != null && state.getBlock() instanceof WindowFrenchGeorgianBlock
                    && state.get(WindowFrenchGeorgianBlock.SILL)
                    && (closed ? !state.get(WindowFrenchGeorgianBlock.OPEN) && noSill(state, false)
                    : state.get(WindowFrenchGeorgianBlock.OPEN)
                    && state.get(WindowFrenchGeorgianBlock.CORNER) == WindowFrenchGeorgianBlock.Corner.NONE);
            if (!active) return original.getQuads(state, face, random);
            if (face != null) return List.of();
            if (sill && !cornerRails.isEmpty() && !state.get(WindowFrenchGeorgianBlock.OPEN))
                return cornerRails.get(state.get(WindowFrenchGeorgianBlock.CORNER).ordinal());
            return quads;
        }
        @Override public boolean useAmbientOcclusion() { return original.useAmbientOcclusion(); }
        @Override public boolean hasDepth() { return original.hasDepth(); }
        @Override public boolean isSideLit() { return original.isSideLit(); }
        @Override public boolean isBuiltin() { return original.isBuiltin(); }
        @Override public Sprite getParticleSprite() { return original.getParticleSprite(); }
        @Override public ModelTransformation getTransformation() { return original.getTransformation(); }
        @Override public ModelOverrideList getOverrides() { return original.getOverrides(); }
    }
}
