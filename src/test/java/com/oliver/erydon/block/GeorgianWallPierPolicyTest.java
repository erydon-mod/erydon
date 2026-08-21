package com.oliver.erydon.block;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeorgianWallPierPolicyTest {
    @Test
    void productionSpacingStorageAndCycleRemainCompatible() {
        assertEquals(GeorgianWallPierSpacing.EVERY_4,
                GeorgianWallPierSpacing.fromStoredValue(0));
        assertEquals(GeorgianWallPierSpacing.EVERY_3,
                GeorgianWallPierSpacing.fromStoredValue(1));
        assertEquals(GeorgianWallPierSpacing.EVERY_5,
                GeorgianWallPierSpacing.fromStoredValue(2));
        assertEquals(GeorgianWallPierSpacing.JOINTS_ONLY,
                GeorgianWallPierSpacing.fromStoredValue(3));
        assertEquals(GeorgianWallPierSpacing.NONE,
                GeorgianWallPierSpacing.fromStoredValue(4));
        assertEquals(GeorgianWallPierSpacing.EVERY_2,
                GeorgianWallPierSpacing.fromStoredValue(5));

        GeorgianWallPierSpacing spacing = GeorgianWallPierSpacing.EVERY_2;
        for (GeorgianWallPierSpacing expected : new GeorgianWallPierSpacing[]{
                GeorgianWallPierSpacing.EVERY_3,
                GeorgianWallPierSpacing.EVERY_4,
                GeorgianWallPierSpacing.EVERY_5,
                GeorgianWallPierSpacing.JOINTS_ONLY,
                GeorgianWallPierSpacing.NONE,
                GeorgianWallPierSpacing.EVERY_2
        }) {
            spacing = spacing.next();
            assertEquals(expected, spacing);
        }
        assertTrue(GeorgianWallPierSpacing.JOINTS_ONLY.piersEnabled());
        assertEquals(
                "message.erydon.georgian_wall.pier_spacing.joints_only",
                GeorgianWallPierSpacing.JOINTS_ONLY.translationKey()
        );
        assertFalse(GeorgianWallPierSpacing.NONE.piersEnabled());
    }

    @Test
    void cardinalConnectionsKeepPriorityOverDiagonals() {
        assertTrue(GeorgianWallConnectionPolicy.allowsDiagonal(false, false));
        assertFalse(GeorgianWallConnectionPolicy.allowsDiagonal(true, false));
        assertFalse(GeorgianWallConnectionPolicy.allowsDiagonal(false, true));
    }

    @Test
    void periodicPiersKeepTheProductionIntervals() {
        for (int interval : new int[]{2, 3, 4, 5}) {
            for (int index = 0; index < 20; index++) {
                assertEquals(
                        (index + 1) % interval == 0,
                        GeorgianWallConnectionPolicy.shouldUsePeriodicPier(
                                index,
                                interval,
                                false
                        )
                );
            }
        }
        assertFalse(GeorgianWallConnectionPolicy.shouldUsePeriodicPier(3, 4, true));
    }

    @Test
    void shallowRunsLinkTheirAlternatingVerticalParts() {
        BlockPos origin = new BlockPos(10, 20, 30);
        GeorgianWallSlopeResolver.Mode lower = new GeorgianWallSlopeResolver.Mode(
                GeorgianWallSlopeResolver.Part.LOWER,
                Direction.NORTH,
                GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                GeorgianWallSlopeResolver.Variant.REGULAR
        );
        GeorgianWallSlopeResolver.Mode upper = new GeorgianWallSlopeResolver.Mode(
                GeorgianWallSlopeResolver.Part.UPPER,
                Direction.NORTH,
                GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                GeorgianWallSlopeResolver.Variant.REGULAR
        );

        assertEquals(origin.north().up(),
                GeorgianWallSlopeResolver.uphillNeighbourPos(origin, lower));
        assertEquals(origin.south(),
                GeorgianWallSlopeResolver.downhillNeighbourPos(origin, lower));
        assertEquals(origin.north(),
                GeorgianWallSlopeResolver.uphillNeighbourPos(origin, upper));
        assertEquals(origin.south().down(),
                GeorgianWallSlopeResolver.downhillNeighbourPos(origin, upper));
    }

    @Test
    void steepTransitionsLinkFlatEndpointsAtTheirOwnHeight() {
        BlockPos origin = new BlockPos(10, 20, 30);
        GeorgianWallSlopeResolver.Mode onramp = new GeorgianWallSlopeResolver.Mode(
                GeorgianWallSlopeResolver.Part.LOWER,
                Direction.EAST,
                GeorgianWallSlopeResolver.Profile.STEEP_45,
                GeorgianWallSlopeResolver.Variant.ONRAMP
        );
        GeorgianWallSlopeResolver.Mode offramp = new GeorgianWallSlopeResolver.Mode(
                GeorgianWallSlopeResolver.Part.LOWER,
                Direction.EAST,
                GeorgianWallSlopeResolver.Profile.STEEP_45,
                GeorgianWallSlopeResolver.Variant.OFFRAMP
        );

        assertEquals(origin.west(),
                GeorgianWallSlopeResolver.downhillNeighbourPos(origin, onramp));
        assertEquals(origin.east().up(),
                GeorgianWallSlopeResolver.uphillNeighbourPos(origin, onramp));
        assertEquals(origin.west().down(),
                GeorgianWallSlopeResolver.downhillNeighbourPos(origin, offramp));
        assertEquals(origin.east(),
                GeorgianWallSlopeResolver.uphillNeighbourPos(origin, offramp));
    }
}
