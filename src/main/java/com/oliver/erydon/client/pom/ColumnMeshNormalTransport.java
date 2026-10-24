package com.oliver.erydon.client.pom;

import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.minecraft.util.math.Direction;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Scoped, synchronous transport for the dedicated large-column mesh's corner frames. */
public final class ColumnMeshNormalTransport {
    private static final int TAG = 0x0EC10000;
    private static final ThreadLocal<Stack> CURRENT = new ThreadLocal<>();
    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    private ColumnMeshNormalTransport() { }

    public static int tag() { return TAG; }
    /** Keeps the source texture plane stable when geometric light-face ties change after clipping. */
    public static int tag(Direction face) { return TAG | (face.getId() + 1); }
    public static boolean isColumn(int tag) { return (tag & ~7) == TAG && (tag & 7) <= 6; }
    public static Direction projectionFace(int tag) {
        int face = tag & 7;
        return isColumn(tag) && face != 0 ? Direction.byId(face - 1) : null;
    }
    public static boolean hasScope() { Stack stack = CURRENT.get(); return stack != null && stack.depth != 0; }

    /** The scope surrounds only Indium's already-staged synchronous buffer push. */
    public static int push(QuadView quad, Object vertices, int a, int b, int c, int d) {
        boolean verified = isColumn(quad.tag()) && RendererSupport.SUPPORTED;
        return push(quad, vertices, a, b, c, d, verified, verified && RendererSupport.TANGENTS_SUPPORTED);
    }

    // The explicit verified flag lets tests exercise the native-memory boundary without a running Fabric client.
    static int push(QuadView quad, Object vertices, int a, int b, int c, int d, boolean verified) {
        return push(quad, vertices, a, b, c, d, verified, false);
    }

    static int push(QuadView quad, Object vertices, int a, int b, int c, int d, boolean verified, boolean tangentVerified) {
        Stack stack = CURRENT.get();
        if (stack == null && (!verified || !isColumn(quad.tag()))) return 0;
        if (stack == null) { stack = new Stack(); CURRENT.set(stack); }
        int previous = stack.depth;
        if (stack.frames.size() == previous) stack.frames.add(new Frame());
        Frame frame = stack.frames.get(stack.depth++);
        frame.vertices = null;
        frame.consumed = false;
        frame.tangentsReady = false;
        try {
            if (verified && isColumn(quad.tag()) && vertices instanceof Object[] array && array.length == 4
                    && validOrder(a, b, c, d)
                    && capture(frame, quad, 0, a) && capture(frame, quad, 1, b)
                    && capture(frame, quad, 2, c) && capture(frame, quad, 3, d)) {
                frame.vertices = vertices;
                Direction plane = tangentVerified ? projectionFace(quad.tag()) : null;
                if (plane != null) {
                    frame.tangentsReady = true;
                    for (int vertex = 0; vertex < 4; vertex++) {
                        frame.tangents[vertex] = ColumnMeshTangentFrame.pack(plane, frame.packed[vertex]);
                        frame.tangentsReady &= frame.tangents[vertex] != 0;
                    }
                }
            }
        } catch (RuntimeException | Error failure) {
            restore(previous);
            throw failure;
        }
        return previous;
    }

    /** Restores nested draws and releases staging-array references even if the encoder threw. */
    public static void restore(int previousDepth) {
        Stack stack = CURRENT.get();
        if (stack == null) return;
        if (previousDepth < 0 || previousDepth > stack.depth) throw new IllegalArgumentException("Invalid column normal scope");
        while (stack.depth > previousDepth) stack.frames.get(--stack.depth).vertices = null;
    }

    /** Called after Iris has completed its ordinary write; other fields and other mods' normals stay intact. */
    public static boolean writeNormals(long result, Object vertices) {
        Stack stack = CURRENT.get();
        if (stack == null || stack.depth == 0 || result < 160) return false;
        Frame frame = stack.frames.get(stack.depth - 1);
        if (frame.vertices == null || frame.vertices != vertices || frame.consumed) return false;
        frame.consumed = true;
        long start = result - 160;
        for (int vertex = 0; vertex < 4; vertex++) {
            if (frame.tangentsReady) MemoryUtil.memPutInt(start + vertex * 40L + 24, frame.tangents[vertex]);
            MemoryUtil.memPutInt(start + vertex * 40L + 28, frame.packed[vertex]);
        }
        if (LOGGED.compareAndSet(false, true)) LoggerFactory.getLogger("Erydon/ColumnNormals")
                .info("Iris large-column normal bridge active; preserving tagged Fabric corner normals; projection tangent frames active={}", frame.tangentsReady);
        return true;
    }

    static int pack(float x, float y, float z) {
        return ((int) (x * 127) & 255) | (((int) (y * 127) & 255) << 8) | (((int) (z * 127) & 255) << 16);
    }

    private static boolean capture(Frame frame, QuadView quad, int destination, int source) {
        if (!quad.hasNormal(source)) return false;
        float x = quad.normalX(source), y = quad.normalY(source), z = quad.normalZ(source);
        float length = x * x + y * y + z * z;
        if (!Float.isFinite(length) || Math.abs(x) > 1.0001f || Math.abs(y) > 1.0001f || Math.abs(z) > 1.0001f
                || length < .95f || length > 1.05f) return false;
        frame.packed[destination] = pack(x, y, z);
        return true;
    }

    private static boolean validOrder(int a, int b, int c, int d) {
        return a >= 0 && a < 4 && b >= 0 && b < 4 && c >= 0 && c < 4 && d >= 0 && d < 4
                && ((1 << a) | (1 << b) | (1 << c) | (1 << d)) == 15;
    }

    private static final class Stack {
        private final List<Frame> frames = new ArrayList<>(1);
        private int depth;
    }

    private static final class Frame {
        private final int[] packed = new int[4];
        private final int[] tangents = new int[4];
        private boolean tangentsReady;
        private Object vertices;
        private boolean consumed;
    }

    private static final class RendererSupport {
        private static final boolean SUPPORTED = InlaySubstrateTransport.rendererSupported() && ColumnMeshNormalFormat.supported();
        private static final boolean TANGENTS_SUPPORTED = SUPPORTED && ColumnMeshNormalFormat.supportsTangents();
    }
}
