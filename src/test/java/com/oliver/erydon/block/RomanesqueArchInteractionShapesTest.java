package com.oliver.erydon.block;

import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.shape.VoxelShapes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class RomanesqueArchInteractionShapesTest {
    @Test
    void authoredHandedComponentsAreExactWidthMirrorsIncludingDepth() {
        int checked = 0;
        for (var arrangement : ArchRomanesqueBlock.Arrangement.values()) {
            if (arrangement.isWide()) continue;
            var shape = RomanesqueArchInteractionShapes.north(arrangement);
            var boxes = new ArrayList<double[]>();
            for (var box : shape.getBoundingBoxes()) {
                boxes.add(new double[]{1-box.maxX, box.minY, box.minZ, 1-box.minX, box.maxY, box.maxZ});
            }
            var expected = AlcoveVoxelShapes.fromBoxes(boxes);
            var actual = RomanesqueArchInteractionShapes.north(arrangement.mirrored());
            assertFalse(VoxelShapes.matchesAnywhere(expected, actual, BooleanBiFunction.NOT_SAME),
                    "Authored shape is not a width mirror: " + arrangement);
            checked++;
        }
        assertTrue(checked > 20);
    }

    @Test
    void addedLowerCurveAndRaisedProfilesAreTargetableWhileVoidStaysOpen() {
        var lower = RomanesqueArchInteractionShapes.north(ArchRomanesqueBlock.Arrangement.TRIPLE_ROW2_L);
        assertFalse(lower.isEmpty());
        var columnLower = RomanesqueArchInteractionShapes.north(ArchRomanesqueBlock.Arrangement.TRIPLE_ROW2_L_COLUMN);
        assertTrue(columnLower.getBoundingBox().minY < 0, "Column detailing crosses the row below");
        assertTrue(RomanesqueArchInteractionShapes.north(ArchRomanesqueBlock.Arrangement.TRIPLE_VOID).isEmpty());
        var upper = RomanesqueArchInteractionShapes.north(ArchRomanesqueBlock.Arrangement.TRIPLE_COLUMN_UPPER_L);
        assertFalse(upper.isEmpty());
        assertTrue(upper.getBoundingBoxes().stream().anyMatch(box -> box.minZ < 0 || box.maxZ > 1),
                "Authored profile projections must be included");
    }
}
