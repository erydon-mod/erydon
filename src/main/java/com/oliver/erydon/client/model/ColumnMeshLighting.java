package com.oliver.erydon.client.model;

import com.oliver.erydon.client.pom.ColumnMeshNormalTransport;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.LightType;

import java.util.ArrayList;
import java.util.List;

/** Continuous world light for tagged cell meshes, scoped to their synchronous block emission. */
public final class ColumnMeshLighting {
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final ThreadLocal<Stack> CURRENT = new ThreadLocal<>();

    private ColumnMeshLighting() { }

    /** Returns the depth to restore in a finally block; null-world inventory/probe draws stay native. */
    public static int push(BlockRenderView world, BlockPos pos) {
        Stack stack = CURRENT.get();
        if ((world == null || pos == null) && (stack == null || stack.depth == 0)) return -1;
        if (stack == null) { stack = new Stack(); CURRENT.set(stack); }
        int previous = stack.depth;
        if (stack.frames.size() == previous) stack.frames.add(new Frame());
        Frame frame = stack.frames.get(stack.depth++);
        frame.world = pos == null ? null : world;
        if (pos != null) {
            frame.x = pos.getX(); frame.y = pos.getY(); frame.z = pos.getZ();
        }
        frame.validSamples = 0;
        frame.sampleCount = 0;
        frame.ownerChecked = false;
        frame.ownerEmissive = false;
        return previous;
    }

    /** Releases world/quad references on normal completion and renderer/world-query failures alike. */
    public static void restore(int previousDepth) {
        if (previousDepth == -1) return;
        Stack stack = CURRENT.get();
        if (stack == null && previousDepth == 0) return;
        if (stack == null || previousDepth < 0 || previousDepth > stack.depth)
            throw new IllegalArgumentException("Invalid column lighting scope");
        while (stack.depth > previousDepth) {
            Frame frame = stack.frames.get(--stack.depth);
            frame.world = null;
            while (frame.quadDepth > 0) frame.quads.get(--frame.quadDepth).quad = null;
        }
    }

    public static boolean hasScope() { return frame() != null; }

    /** Renderer entry bookkeeping only: sampling occurs at the flat native-light max operation. */
    public static void beginQuad(QuadView quad, boolean flatNonEmissive) {
        Frame frame = frame();
        if (frame == null) return;
        if (frame.quads.size() == frame.quadDepth) frame.quads.add(new QuadState());
        QuadState state = frame.quads.get(frame.quadDepth++);
        state.quad = quad;
        state.vertex = 0;
        state.eligible = flatNonEmissive && ColumnMeshNormalTransport.isColumn(quad.tag());
        // Validate the complete quad before changing any lightmap corner.
        if (state.eligible) for (int vertex = 0; vertex < 4; vertex++) {
            if (!coordinate(quad.x(vertex)) || !coordinate(quad.y(vertex)) || !coordinate(quad.z(vertex))) {
                state.eligible = false;
                break;
            }
        }
    }

    public static void endQuad(QuadView quad) {
        Frame frame = frame();
        if (frame == null || frame.quadDepth == 0) return;
        QuadState state = frame.quads.get(frame.quadDepth - 1);
        if (state.quad == quad) {
            state.quad = null;
            frame.quadDepth--;
        }
    }

    /** Replacement for only the native flat-light floor; -1 retains the renderer's original floor. */
    public static int next() {
        Frame frame = frame();
        if (frame == null || frame.quadDepth == 0) return -1;
        QuadState state = frame.quads.get(frame.quadDepth - 1);
        if (!state.eligible || state.vertex >= 4) return -1;
        QuadView quad = state.quad;
        int vertex = state.vertex++;
        if (!frame.ownerChecked) {
            frame.cursor.set(frame.x, frame.y, frame.z);
            BlockState owner = frame.world.getBlockState(frame.cursor);
            frame.ownerEmissive = owner.hasEmissiveLighting(frame.world, frame.cursor);
            frame.ownerChecked = true;
        }
        if (frame.ownerEmissive) return FULL_BRIGHT;
        double x = quad.x(vertex) - .5, y = quad.y(vertex) - .5, z = quad.z(vertex) - .5;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double fx = x - ix, fy = y - iy, fz = z - iz;
        double block = 0, sky = 0;
        for (int dx = 0; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = 0; dz <= 1; dz++) {
            double weight = (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy) * (dz == 0 ? 1 - fz : fz);
            if (weight == 0) continue;
            int light = frame.sample(ix + dx, iy + dy, iz + dz);
            block += (light & 255) * weight;
            sky += (light >>> 16) * weight;
        }
        return (int) Math.round(block) | ((int) Math.round(sky) << 16);
    }

    // A clipped cell is in 0..1. The wider finite interval also tolerates boundary round-off,
    // while keeping all eight interpolation centers inside the same reusable 3x3x3 lattice.
    private static boolean coordinate(float value) { return Float.isFinite(value) && value >= -.5f && value < 1.5f; }

    static int sampleCount() { Frame frame = frame(); return frame == null ? 0 : frame.sampleCount; }

    private static Frame frame() {
        Stack stack = CURRENT.get();
        if (stack == null || stack.depth == 0) return null;
        Frame frame = stack.frames.get(stack.depth - 1);
        return frame.world == null ? null : frame;
    }

    private static final class Stack {
        final List<Frame> frames = new ArrayList<>(1);
        int depth;
    }

    private static final class QuadState {
        QuadView quad;
        int vertex;
        boolean eligible;
    }

    private static final class Frame {
        final int[] samples = new int[27];
        final BlockPos.Mutable cursor = new BlockPos.Mutable();
        final List<QuadState> quads = new ArrayList<>(1);
        BlockRenderView world;
        int x, y, z, validSamples, sampleCount, quadDepth;
        boolean ownerChecked, ownerEmissive;

        int sample(int dx, int dy, int dz) {
            int index = (dx + 1) * 9 + (dy + 1) * 3 + dz + 1;
            int bit = 1 << index;
            if ((validSamples & bit) == 0) {
                cursor.set(x + dx, y + dy, z + dz);
                int block = Math.max(world.getLightLevel(LightType.BLOCK, cursor), world.getBlockState(cursor).getLuminance());
                int sky = world.getLightLevel(LightType.SKY, cursor);
                samples[index] = Math.max(0, Math.min(15, block)) * 16 | (Math.max(0, Math.min(15, sky)) * 16 << 16);
                validSamples |= bit;
                sampleCount++;
            }
            return samples[index];
        }
    }
}
