package com.oliver.erydon.client.compat;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnSelectionCompletionTest {
    private final Map<BlockPos, List<BlockPos>> sections = new HashMap<>();

    @Test
    void firstCornerWaitsForTheSecondCorner() {
        section(0, 0, 0, 2);
        assertTrue(extras(BlockPos.ORIGIN, null, Set.of()).isEmpty());
    }

    @Test
    void oneBaseCellAddsOnlyTheOtherSevenBaseCells() {
        Set<BlockPos> base = section(0, 0, 0, 2);
        section(0, 2, 0, 1);
        section(0, 3, 0, 2);
        BlockPos hit = new BlockPos(1, 1, 1);
        base.remove(hit);
        assertEquals(base, extras(hit, hit, Set.of()));
    }

    @Test
    void oneShaftCellDoesNotPullInAdjacentLayers() {
        section(0, 0, 0, 2);
        Set<BlockPos> shaft = section(0, 2, 0, 1);
        section(0, 3, 0, 1);
        section(0, 4, 0, 2);
        BlockPos hit = new BlockPos(1, 2, 0);
        shaft.remove(hit);
        assertEquals(shaft, extras(hit, hit, Set.of()));
    }

    @Test
    void aBaseOnTheEdgeIsCompletedEvenWhenBothCornersAreOrdinaryBlocks() {
        Set<BlockPos> base = section(0, 0, 0, 2);
        BlockPos from = new BlockPos(-3, 1, -3);
        BlockPos to = new BlockPos(3, 3, 3);
        base.removeIf(pos -> pos.getY() == 1);
        assertEquals(base, extras(from, to, Set.of()));
        assertEquals(base, extras(to, from, Set.of()));
    }

    @Test
    void fullySelectedSectionsAddNothing() {
        section(0, 0, 0, 2);
        assertTrue(extras(new BlockPos(-1, -1, -1), new BlockPos(2, 2, 2), Set.of()).isEmpty());
    }

    @Test
    void sparseSelectionIsCompletedWithoutAddingUnrelatedCells() {
        Set<BlockPos> base = section(-2, -2, -2, 2);
        BlockPos hit = new BlockPos(-1, -1, -1);
        BlockPos ordinary = new BlockPos(8, 8, 8);
        Set<BlockPos> sparse = Set.of(hit, ordinary);
        base.remove(hit);
        assertEquals(base, extras(null, null, sparse));
        assertEquals(Set.of(hit, ordinary), sparse);
    }

    @Test
    void shrinkingRangeDropsPreviouslyAddedSections() {
        section(0, 0, 0, 2);
        section(0, 2, 0, 1);
        Set<BlockPos> topShaft = section(0, 3, 0, 1);
        BlockPos top = new BlockPos(1, 3, 1);
        assertEquals(8, extras(new BlockPos(1, 0, 0), top, Set.of()).size());
        topShaft.remove(top);
        assertEquals(topShaft, extras(top, top, Set.of()));
        assertTrue(extras(new BlockPos(5, 5, 5), new BlockPos(6, 6, 6), Set.of()).isEmpty());
    }

    @Test
    void boxAndMagicSelectionAreUnionedBeforeFindingMissingCells() {
        Set<BlockPos> base = section(0, 0, 0, 2);
        Set<BlockPos> sparse = Set.of(new BlockPos(1, 0, 0), new BlockPos(1, 1, 0));
        BlockPos from = new BlockPos(0, 0, 0);
        BlockPos to = new BlockPos(0, 1, 0);
        base.removeAll(sparse);
        base.remove(from);
        base.remove(to);
        assertEquals(base, extras(from, to, sparse));
    }

    @Test
    void boundaryScanMatchesAnExhaustiveSelectionOracle() {
        section(-2, -2, -2, 2);
        section(-2, 0, -2, 1);
        section(-2, 1, -2, 2);
        section(0, 0, 0, 2);
        section(0, 2, 0, 1);
        section(0, 3, 0, 2);
        Random random = new Random(1201);
        for (int i = 0; i < 500; i++) {
            BlockPos from = randomPos(random);
            BlockPos to = randomPos(random);
            Set<BlockPos> selected = new HashSet<>(List.of(randomPos(random), randomPos(random)));
            Set<BlockPos> sparse = Set.copyOf(selected);
            for (BlockPos pos : BlockPos.iterate(from, to)) selected.add(pos.toImmutable());
            Set<BlockPos> expected = new HashSet<>();
            for (BlockPos pos : selected) expected.addAll(sections.getOrDefault(pos, List.of()));
            expected.removeAll(selected);
            assertEquals(expected, extras(from, to, sparse), "Range " + from + " to " + to);
        }
    }

    @Test
    void largeBoxesScanTheirSurfaceWithoutScanningTheWholeVolume() {
        AtomicInteger lookups = new AtomicInteger();
        ColumnSelectionCompletion.extraCells(BlockPos.ORIGIN, new BlockPos(99, 99, 99), Set.of(), pos -> {
            lookups.incrementAndGet();
            return List.of();
        });
        assertEquals(100 * 100 * 100 - 98 * 98 * 98, lookups.get());
    }

    private Set<BlockPos> extras(BlockPos from, BlockPos to, Set<BlockPos> sparse) {
        return ColumnSelectionCompletion.extraCells(from, to, sparse, pos -> sections.getOrDefault(pos, List.of()));
    }

    private Set<BlockPos> section(int x, int y, int z, int height) {
        List<BlockPos> cells = new ArrayList<>();
        for (int dx = 0; dx < 2; dx++) for (int dy = 0; dy < height; dy++) for (int dz = 0; dz < 2; dz++)
            cells.add(new BlockPos(x + dx, y + dy, z + dz));
        for (BlockPos pos : cells) sections.put(pos, List.copyOf(cells));
        return new HashSet<>(cells);
    }

    private static BlockPos randomPos(Random random) {
        return new BlockPos(random.nextInt(8) - 3, random.nextInt(9) - 3, random.nextInt(8) - 3);
    }
}
