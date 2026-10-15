package com.oliver.erydon.block;

import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.shape.VoxelShapes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AlcoveVoxelShapesTest {
    @Test
    void exactGridMatchesOverlappingBoxesWithoutRoundingTheirBoundaries() {
        var boxes = List.of(
                new double[]{-.015625, .0467, .05984, .178165, .71875, 1.046875},
                new double[]{.1, .64147, .125, .9, 1.09375, 1.03},
                new double[]{.6148, .25, .03978, 1.016, 1, .9});
        var reference = VoxelShapes.empty();
        for (var box : boxes) {
            reference = VoxelShapes.union(reference,
                    VoxelShapes.cuboid(box[0], box[1], box[2], box[3], box[4], box[5]));
        }
        var actual = AlcoveVoxelShapes.fromBoxes(boxes);
        assertFalse(VoxelShapes.matchesAnywhere(reference, actual, BooleanBiFunction.NOT_SAME));
        assertEquals(reference.getBoundingBox(), actual.getBoundingBox());
        assertSame(actual.getBoundingBoxes(), actual.getBoundingBoxes(), "Outlines are reused for cached rotations");
    }

    @Test
    void noBoxesProducesAnEmptyShape() {
        assertTrue(AlcoveVoxelShapes.fromBoxes(List.of()).isEmpty());
    }
}
