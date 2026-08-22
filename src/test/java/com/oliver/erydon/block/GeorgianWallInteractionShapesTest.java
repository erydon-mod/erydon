package com.oliver.erydon.block;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeorgianWallInteractionShapesTest {
    private static final double EPSILON = 0.000001D;

    @Test
    void everyAuthoredSlopeSelectionHasAUsableShapeInEveryDirection() {
        for (Direction uphill : Direction.Type.HORIZONTAL) {
            for (GeorgianWallSlopeResolver.Part part : new GeorgianWallSlopeResolver.Part[]{
                    GeorgianWallSlopeResolver.Part.LOWER,
                    GeorgianWallSlopeResolver.Part.UPPER
            }) {
                VoxelShape regular = GeorgianWallInteractionShapes.shapeFor(
                        mode(part, uphill, GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                                GeorgianWallSlopeResolver.Variant.REGULAR),
                        0
                );
                assertFalse(regular.isEmpty());
            }

            assertFalse(GeorgianWallInteractionShapes.shapeFor(
                    mode(GeorgianWallSlopeResolver.Part.LOWER, uphill,
                            GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                            GeorgianWallSlopeResolver.Variant.ONRAMP),
                    0
            ).isEmpty());
            assertFalse(GeorgianWallInteractionShapes.shapeFor(
                    mode(GeorgianWallSlopeResolver.Part.UPPER, uphill,
                            GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                            GeorgianWallSlopeResolver.Variant.OFFRAMP),
                    0
            ).isEmpty());

            for (GeorgianWallSlopeResolver.Variant variant : GeorgianWallSlopeResolver.Variant.values()) {
                assertFalse(GeorgianWallInteractionShapes.shapeFor(
                        mode(GeorgianWallSlopeResolver.Part.LOWER, uphill,
                                GeorgianWallSlopeResolver.Profile.STEEP_45, variant),
                        0
                ).isEmpty());
            }
        }
    }

    @Test
    void modelRotationMatchesTheRuntimeNorthEastSouthWestRotation() {
        GeorgianWallSlopeResolver.Mode northMode = mode(
                GeorgianWallSlopeResolver.Part.LOWER,
                Direction.NORTH,
                GeorgianWallSlopeResolver.Profile.STEEP_45,
                GeorgianWallSlopeResolver.Variant.REGULAR
        );
        GeorgianWallSlopeResolver.Mode eastMode = mode(
                GeorgianWallSlopeResolver.Part.LOWER,
                Direction.EAST,
                GeorgianWallSlopeResolver.Profile.STEEP_45,
                GeorgianWallSlopeResolver.Variant.REGULAR
        );
        Box north = GeorgianWallInteractionShapes.shapeFor(northMode, 0).getBoundingBox();
        Box east = GeorgianWallInteractionShapes.shapeFor(eastMode, 0).getBoundingBox();

        assertEquals(1.0D - north.maxZ, east.minX, EPSILON);
        assertEquals(1.0D - north.minZ, east.maxX, EPSILON);
        assertEquals(north.minX, east.minZ, EPSILON);
        assertEquals(north.maxX, east.maxZ, EPSILON);
        assertEquals(north.minY, east.minY, EPSILON);
        assertEquals(north.maxY, east.maxY, EPSILON);
    }

    @Test
    void transitionShapesIncludeTheirOutOfBlockHandoffs() {
        Box shallowOnramp = GeorgianWallInteractionShapes.shapeFor(
                mode(GeorgianWallSlopeResolver.Part.LOWER, Direction.NORTH,
                        GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                        GeorgianWallSlopeResolver.Variant.ONRAMP),
                0
        ).getBoundingBox();
        Box shallowOfframp = GeorgianWallInteractionShapes.shapeFor(
                mode(GeorgianWallSlopeResolver.Part.UPPER, Direction.NORTH,
                        GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                        GeorgianWallSlopeResolver.Variant.OFFRAMP),
                0
        ).getBoundingBox();
        Box steepOnramp = GeorgianWallInteractionShapes.shapeFor(
                mode(GeorgianWallSlopeResolver.Part.LOWER, Direction.NORTH,
                        GeorgianWallSlopeResolver.Profile.STEEP_45,
                        GeorgianWallSlopeResolver.Variant.ONRAMP),
                0
        ).getBoundingBox();
        Box steepOfframp = GeorgianWallInteractionShapes.shapeFor(
                mode(GeorgianWallSlopeResolver.Part.LOWER, Direction.NORTH,
                        GeorgianWallSlopeResolver.Profile.STEEP_45,
                        GeorgianWallSlopeResolver.Variant.OFFRAMP),
                0
        ).getBoundingBox();

        assertTrue(shallowOnramp.maxZ >= 19.25D / 16.0D - EPSILON);
        assertTrue(shallowOfframp.minZ <= -15.82177D / 16.0D + EPSILON);
        assertTrue(steepOnramp.maxZ >= 19.25D / 16.0D - EPSILON);
        assertTrue(steepOfframp.minZ <= -3.25D / 16.0D + EPSILON);
    }

    @Test
    void aFlatCornerArmAddsOnlyItsRequestedWorldSideAndIsCached() {
        GeorgianWallSlopeResolver.Mode mode = mode(
                GeorgianWallSlopeResolver.Part.UPPER,
                Direction.NORTH,
                GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                GeorgianWallSlopeResolver.Variant.REGULAR
        );
        VoxelShape withoutArm = GeorgianWallInteractionShapes.shapeFor(mode, 0);
        int eastArm = GeorgianWallInteractionShapes.directionBit(Direction.EAST);
        VoxelShape withArm = GeorgianWallInteractionShapes.shapeFor(mode, eastArm);

        assertTrue(withoutArm.getBoundingBox().maxX < 1.0D);
        assertEquals(1.0D, withArm.getBoundingBox().maxX, EPSILON);
        assertSame(withArm, GeorgianWallInteractionShapes.shapeFor(mode, eastArm));
    }

    private static GeorgianWallSlopeResolver.Mode mode(
            GeorgianWallSlopeResolver.Part part,
            Direction uphill,
            GeorgianWallSlopeResolver.Profile profile,
            GeorgianWallSlopeResolver.Variant variant) {
        return new GeorgianWallSlopeResolver.Mode(part, uphill, profile, variant);
    }
}
