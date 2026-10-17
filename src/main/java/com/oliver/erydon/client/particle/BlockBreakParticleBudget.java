package com.oliver.erydon.client.particle;

import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;

/** One small debris cloud per broken block, independent of outline fragmentation. */
public final class BlockBreakParticleBudget {
    public static final int MAX_PARTICLES = 16;

    private BlockBreakParticleBudget() { }

    public static void emit(VoxelShape shape, SampleConsumer consumer) {
        if (shape.isEmpty()) return;
        // Bounds avoid enumerating the potentially thousands of boxes in a curved outline.
        Box bounds = shape.getBoundingBox();
        double dx = bounds.maxX - bounds.minX, dy = bounds.maxY - bounds.minY, dz = bounds.maxZ - bounds.minZ;
        int nx = divisions(dx), ny = divisions(dy), nz = divisions(dz);
        while (nx * ny * nz > MAX_PARTICLES) {
            if (nx >= ny && nx >= nz) nx = Math.max(2, nx / 2);
            else if (ny >= nz) ny = Math.max(2, ny / 2);
            else nz = Math.max(2, nz / 2);
        }
        for (int x = 0; x < nx; x++) {
            double fx = (x + 0.5) / nx;
            for (int y = 0; y < ny; y++) {
                double fy = (y + 0.5) / ny;
                for (int z = 0; z < nz; z++) {
                    double fz = (z + 0.5) / nz;
                    consumer.accept(bounds.minX + fx * dx, bounds.minY + fy * dy, bounds.minZ + fz * dz,
                            fx - 0.5, fy - 0.5, fz - 0.5);
                }
            }
        }
    }

    private static int divisions(double extent) {
        return Math.max(2, (int) Math.ceil(Math.min(1.0, extent) / 0.25));
    }

    @FunctionalInterface
    public interface SampleConsumer {
        void accept(double x, double y, double z, double velocityX, double velocityY, double velocityZ);
    }
}
