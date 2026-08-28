package com.oliver.erydon.client.model;

import com.oliver.erydon.block.SlopeBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SlopeBakedModelRotationTest {
    @Test
    void innerRightAddsAHalfTurnForEveryHorizontalFacing() {
        for (BlockHalf half : BlockHalf.values()) {
            for (Direction facing : Direction.Type.HORIZONTAL) {
                int straight = SlopeBakedModel.normalRotationForState(
                        facing, half, SlopeBlock.SlopeShape.STRAIGHT);
                int innerRight = SlopeBakedModel.normalRotationForState(
                        facing, half, SlopeBlock.SlopeShape.INNER_RIGHT);

                assertEquals(Math.floorMod(straight + 180, 360), Math.floorMod(innerRight, 360),
                        () -> "Unexpected inner-right rotation for " + facing + " " + half);
            }
        }
    }

    @Test
    void reportedNorthBottomInnerRightStateRendersAtNinetyDegrees() {
        int rotation = SlopeBakedModel.normalRotationForState(
                Direction.NORTH, BlockHalf.BOTTOM, SlopeBlock.SlopeShape.INNER_RIGHT);

        assertEquals(90, Math.floorMod(rotation, 360));
    }
}
