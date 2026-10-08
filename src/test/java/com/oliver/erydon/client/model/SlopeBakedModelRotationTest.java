package com.oliver.erydon.client.model;

import com.oliver.erydon.block.SlopeBlock;
import com.oliver.erydon.block.ShallowSlopeBlock;
import com.oliver.erydon.block.SlopeSteepBlock;
import com.oliver.erydon.block.SlopeOrientation;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SlopeBakedModelRotationTest {
    @Test
    void cornersJoinBothPhysicalRunsForEveryProfileFacingAndHalf() {
        // Inner corners union two perpendicular straight solids; outer corners
        // intersect them. Top-half solids reverse the required min/max boundary.
        for (int profile = 0; profile < 5; profile++)
            for (BlockHalf half : BlockHalf.values())
                for (Direction facing : Direction.Type.HORIZONTAL)
                    for (SlopeBlock.SlopeShape shape : SlopeBlock.SlopeShape.values()) {
                        int degrees = profile == 0 ? SlopeOrientation.standard(facing, half, shape)
                                : profile < 3 ? SlopeOrientation.shallow(facing, half,
                                        ShallowSlopeBlock.SlopeShape.valueOf(shape.name()))
                                : SlopeOrientation.steep(facing, half,
                                        SlopeSteepBlock.SlopeShape.valueOf(shape.name()));
                        FixedSlopeRotation transform = FixedSlopeRotation.of(
                                half == BlockHalf.TOP ? 180 : 0, degrees);
                        boolean inner = shape.name().startsWith("INNER");
                        boolean corner = shape != SlopeBlock.SlopeShape.STRAIGHT;
                        Direction other = shape.name().endsWith("LEFT")
                                ? facing.rotateYCounterclockwise() : facing.rotateYClockwise();
                        for (int ix = 0; ix <= 8; ix++) for (int iz = 0; iz <= 8; iz++) {
                            float x = ix / 8F, z = iz / 8F;
                            float height = height(profile, 1 - x);
                            if (corner) {
                                float across = height(profile, profile >= 3 || inner ? z : 1 - z);
                                height = inner ? Math.max(height, across) : Math.min(height, across);
                            }
                            float worldX = transform.positionX(x, height, z);
                            float worldY = transform.positionY(x, height, z);
                            float worldZ = transform.positionZ(x, height, z);
                            float expected = boundary(profile, half, facing, worldX, worldZ);
                            if (corner) {
                                float across = boundary(profile, half, other, worldX, worldZ);
                                boolean maximum = inner == (half == BlockHalf.BOTTOM);
                                expected = maximum ? Math.max(expected, across) : Math.min(expected, across);
                            }
                            assertEquals(expected, worldY, 0.00001,
                                    "profile=" + profile + " " + facing + " " + half + " " + shape
                                            + " at " + worldX + "," + worldZ);
                        }
                    }
    }

    @Test
    void northBottomInnerRightMeetsItsEastFacingNeighbour() {
        int rotation = SlopeBakedModel.normalRotationForState(
                Direction.NORTH, BlockHalf.BOTTOM, SlopeBlock.SlopeShape.INNER_RIGHT);

        assertEquals(0, Math.floorMod(rotation, 360));
    }

    private static float boundary(int profile, BlockHalf half, Direction facing, float x, float z) {
        float rise = switch (facing) {
            case EAST -> 1 - x;
            case WEST -> x;
            case NORTH -> z;
            case SOUTH -> 1 - z;
            default -> throw new AssertionError(facing);
        };
        float result = height(profile, rise);
        return half == BlockHalf.TOP ? 1 - result : result;
    }

    private static float height(int profile, float rise) {
        return switch (profile) {
            case 0 -> rise;
            case 1 -> rise / 2;
            case 2 -> 0.5F + rise / 2;
            case 3 -> Math.max(0, 2 * rise - 1);
            case 4 -> Math.min(1, 2 * rise);
            default -> throw new AssertionError(profile);
        };
    }
}
