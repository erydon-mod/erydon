package com.oliver.erydon.client.model;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HighPolishWaterModelTest {
    @Test void catchesSubmergedFullBlocksAndWaterloggedShapesWithBoundedReads() {
        // Across positive and negative chunk edges; no Minecraft registries are needed.
        for (BlockPos pos : List.of(new BlockPos(15, 63, -16), new BlockPos(-16, 0, 15))) {
            var reads = new ArrayList<BlockPos>();
            HighPolishWaterModel.WaterLookup<Set<BlockPos>> water = (wet, query) -> {
                reads.add(query.toImmutable());
                return wet.contains(query);
            };
            for (Direction direction : Direction.values()) {
                reads.clear();
                var neighbour = pos.offset(direction);
                assertTrue(HighPolishWaterModel.touchesWater(Set.of(neighbour), false, pos, water));
                assertTrue(reads.size() <= 6);
                assertTrue(reads.contains(neighbour));
            }
            reads.clear();
            assertFalse(HighPolishWaterModel.touchesWater(Set.of(), false, pos, water));
            assertEquals(6, new HashSet<>(reads).size());
            assertFalse(reads.contains(pos), "Use the supplied block state for waterlogging");
            reads.clear();
            assertTrue(HighPolishWaterModel.touchesWater(Set.of(), true, pos, water));
            assertTrue(reads.isEmpty(), "Waterlogged shapes need no neighbour reads");
            assertFalse(HighPolishWaterModel.touchesWater(Set.of(pos.add(2, 0, 0)), false, pos, water),
                    "Distant water must not disable a dry block's polish");
        }
    }
}
