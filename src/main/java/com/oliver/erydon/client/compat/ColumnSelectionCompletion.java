package com.oliver.erydon.client.compat;

import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** Sparse additions leave the user's from/to box and unrelated blocks unchanged. */
final class ColumnSelectionCompletion {
    private ColumnSelectionCompletion() {}

    static Set<BlockPos> extraCells(BlockPos from, BlockPos to, Set<BlockPos> sparse,
                                    Function<BlockPos, List<BlockPos>> sectionAt) {
        Bounds box = from == null || to == null ? null : new Bounds(from, to);
        Set<BlockPos> extras = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        Consumer<BlockPos> complete = pos -> {
            if (visited.contains(pos)) return;
            List<BlockPos> section = sectionAt.apply(pos);
            visited.addAll(section);
            for (BlockPos cell : section) {
                if (!sparse.contains(cell) && (box == null || !box.contains(cell))) extras.add(cell);
            }
        };
        // Sections span at most two cells on each axis. Only a box boundary can cut one.
        if (box != null) box.visitBoundary(complete);
        sparse.forEach(complete);
        return extras;
    }

    private record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        Bounds(BlockPos from, BlockPos to) {
            this(Math.min(from.getX(), to.getX()), Math.min(from.getY(), to.getY()),
                    Math.min(from.getZ(), to.getZ()), Math.max(from.getX(), to.getX()),
                    Math.max(from.getY(), to.getY()), Math.max(from.getZ(), to.getZ()));
        }

        boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }

        void visitBoundary(Consumer<BlockPos> visit) {
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                visit.accept(new BlockPos(x, minY, z));
                if (minY != maxY) visit.accept(new BlockPos(x, maxY, z));
            }
            for (int y = minY + 1; y < maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    visit.accept(new BlockPos(x, y, minZ));
                    if (minZ != maxZ) visit.accept(new BlockPos(x, y, maxZ));
                }
                for (int z = minZ + 1; z < maxZ; z++) {
                    visit.accept(new BlockPos(minX, y, z));
                    if (minX != maxX) visit.accept(new BlockPos(maxX, y, z));
                }
            }
        }
    }
}
