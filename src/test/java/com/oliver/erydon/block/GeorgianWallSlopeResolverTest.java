package com.oliver.erydon.block;

import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.block.enums.StairShape;
import net.minecraft.block.enums.WallShape;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeorgianWallSlopeResolverTest {
    @Test
    void erydonShallowStairsMapToTheAlternatingTwentySevenDegreeParts() {
        assertEquals(
                GeorgianWallSlopeResolver.Part.UPPER,
                GeorgianWallSlopeResolver.partForShallowStair(false, StairShape.STRAIGHT)
        );
        assertEquals(
                GeorgianWallSlopeResolver.Part.LOWER,
                GeorgianWallSlopeResolver.partForShallowStair(true, StairShape.STRAIGHT)
        );
        assertEquals(
                GeorgianWallSlopeResolver.Part.NONE,
                GeorgianWallSlopeResolver.partForShallowStair(false, StairShape.INNER_LEFT)
        );
    }

    @Test
    void erydonShallowStairsMustFaceAlongTheIncline() {
        assertTrue(GeorgianWallSlopeResolver.isAlignedShallowStair(
                StairShape.STRAIGHT,
                Direction.EAST,
                Direction.EAST
        ));
        assertFalse(GeorgianWallSlopeResolver.isAlignedShallowStair(
                StairShape.STRAIGHT,
                Direction.NORTH,
                Direction.EAST
        ));
        assertFalse(GeorgianWallSlopeResolver.isAlignedShallowStair(
                StairShape.OUTER_RIGHT,
                Direction.EAST,
                Direction.EAST
        ));
    }

    @Test
    void erydonShallowStairsAreNotMisreadAsFortyFiveDegreeStairs() {
        assertFalse(GeorgianWallSlopeResolver.isAlignedSteepStair(
                true,
                true,
                BlockHalf.BOTTOM,
                StairShape.STRAIGHT,
                Direction.EAST,
                Direction.EAST
        ));
        assertTrue(GeorgianWallSlopeResolver.isAlignedSteepStair(
                true,
                false,
                BlockHalf.BOTTOM,
                StairShape.STRAIGHT,
                Direction.EAST,
                Direction.EAST
        ));
    }

    @Test
    void onlyFourAndEightLayerSupportsUseTheShallowWallModels() {
        assertEquals(
                GeorgianWallSlopeResolver.Part.UPPER,
                GeorgianWallSlopeResolver.partForLayer(4, false)
        );
        assertEquals(
                GeorgianWallSlopeResolver.Part.NONE,
                GeorgianWallSlopeResolver.partForLayer(4, true)
        );
        assertEquals(
                GeorgianWallSlopeResolver.Part.LOWER,
                GeorgianWallSlopeResolver.partForLayer(8, false)
        );
        assertEquals(
                GeorgianWallSlopeResolver.Part.LOWER,
                GeorgianWallSlopeResolver.partForLayer(8, true)
        );
        assertEquals(
                GeorgianWallSlopeResolver.Part.NONE,
                GeorgianWallSlopeResolver.partForLayer(3, false)
        );
        assertEquals(
                GeorgianWallSlopeResolver.Part.NONE,
                GeorgianWallSlopeResolver.partForLayer(5, false)
        );
    }

    @Test
    void lowerSlabsRemainSupportedAndUpperSlabsRemainRejected() {
        assertEquals(
                GeorgianWallSlopeResolver.Part.UPPER,
                GeorgianWallSlopeResolver.partForSlab(SlabType.BOTTOM)
        );
        assertEquals(
                GeorgianWallSlopeResolver.Part.LOWER,
                GeorgianWallSlopeResolver.partForSlab(SlabType.DOUBLE)
        );
        assertEquals(
                GeorgianWallSlopeResolver.Part.NONE,
                GeorgianWallSlopeResolver.partForSlab(SlabType.TOP)
        );
    }

    @Test
    void shallowTransitionsSelectOnlyTheModelsThatExist() {
        assertEquals(
                GeorgianWallSlopeResolver.Variant.ONRAMP,
                GeorgianWallSlopeResolver.transitionVariant(
                        GeorgianWallSlopeResolver.Part.LOWER,
                        GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                        true,
                        false
                )
        );
        assertEquals(
                GeorgianWallSlopeResolver.Variant.OFFRAMP,
                GeorgianWallSlopeResolver.transitionVariant(
                        GeorgianWallSlopeResolver.Part.UPPER,
                        GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                        false,
                        true
                )
        );
        assertEquals(
                GeorgianWallSlopeResolver.Variant.REGULAR,
                GeorgianWallSlopeResolver.transitionVariant(
                        GeorgianWallSlopeResolver.Part.LOWER,
                        GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                        false,
                        true
                )
        );
        assertEquals(
                GeorgianWallSlopeResolver.Variant.REGULAR,
                GeorgianWallSlopeResolver.transitionVariant(
                        GeorgianWallSlopeResolver.Part.UPPER,
                        GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                        true,
                        false
                )
        );
    }

    @Test
    void steepLowerPiecesSupportBothTransitions() {
        assertEquals(
                GeorgianWallSlopeResolver.Variant.ONRAMP,
                GeorgianWallSlopeResolver.transitionVariant(
                        GeorgianWallSlopeResolver.Part.LOWER,
                        GeorgianWallSlopeResolver.Profile.STEEP_45,
                        true,
                        false
                )
        );
        assertEquals(
                GeorgianWallSlopeResolver.Variant.OFFRAMP,
                GeorgianWallSlopeResolver.transitionVariant(
                        GeorgianWallSlopeResolver.Part.LOWER,
                        GeorgianWallSlopeResolver.Profile.STEEP_45,
                        false,
                        true
                )
        );
        assertEquals(
                GeorgianWallSlopeResolver.Variant.REGULAR,
                GeorgianWallSlopeResolver.transitionVariant(
                        GeorgianWallSlopeResolver.Part.LOWER,
                        GeorgianWallSlopeResolver.Profile.STEEP_45,
                        false,
                        false
                )
        );
    }

    @Test
    void steepOnrampIsAnchoredOnTheLowerFlatGroundBlock() {
        assertTrue(GeorgianWallSlopeResolver.isSteepOnrampAnchor(
                GeorgianWallSlopeResolver.Part.LOWER,
                false,
                true
        ));
        assertFalse(GeorgianWallSlopeResolver.isSteepOnrampAnchor(
                GeorgianWallSlopeResolver.Part.LOWER,
                true,
                true
        ));
        assertEquals(
                GeorgianWallSlopeResolver.Variant.ONRAMP,
                GeorgianWallSlopeResolver.transitionVariant(
                        GeorgianWallSlopeResolver.Part.LOWER,
                        GeorgianWallSlopeResolver.Profile.STEEP_45,
                        false,
                        false,
                        true
                )
        );
        assertEquals(
                GeorgianWallSlopeResolver.Variant.REGULAR,
                GeorgianWallSlopeResolver.transitionVariant(
                        GeorgianWallSlopeResolver.Part.LOWER,
                        GeorgianWallSlopeResolver.Profile.STEEP_45,
                        false,
                        false,
                        false
                )
        );
    }

    @Test
    void cornerEndpointsBecomeTransitionsButMidRunBranchesStayRegular() {
        assertTrue(GeorgianWallSlopeResolver.isLowCornerBoundary(true, true, false));
        assertTrue(GeorgianWallSlopeResolver.isHighCornerBoundary(true, false, true));
        assertFalse(GeorgianWallSlopeResolver.isLowCornerBoundary(true, true, true));
        assertFalse(GeorgianWallSlopeResolver.isHighCornerBoundary(true, true, true));
        assertFalse(GeorgianWallSlopeResolver.isLowCornerBoundary(false, true, false));
        assertFalse(GeorgianWallSlopeResolver.isHighCornerBoundary(false, false, true));
    }

    @Test
    void openShallowEndsUseTransitionCapsWithoutAFlatContinuation() {
        assertTrue(GeorgianWallSlopeResolver.isLowShallowTermination(
                GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                true,
                false
        ));
        assertTrue(GeorgianWallSlopeResolver.isHighShallowTermination(
                GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                false,
                true
        ));
        assertFalse(GeorgianWallSlopeResolver.isLowShallowTermination(
                GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                true,
                true
        ));
        assertFalse(GeorgianWallSlopeResolver.isHighShallowTermination(
                GeorgianWallSlopeResolver.Profile.STEEP_45,
                false,
                true
        ));
    }

    @Test
    void reportsOnlyFlatConnectionsPerpendicularToTheIncline() {
        assertEquals(
                List.of(Direction.EAST),
                GeorgianWallSlopeResolver.flatCornerDirections(
                        Direction.NORTH,
                        WallShape.LOW,
                        WallShape.LOW,
                        WallShape.NONE,
                        WallShape.NONE
                )
        );
        assertEquals(
                List.of(Direction.NORTH),
                GeorgianWallSlopeResolver.flatCornerDirections(
                        Direction.EAST,
                        WallShape.LOW,
                        WallShape.LOW,
                        WallShape.NONE,
                        WallShape.NONE
                )
        );
    }

    @Test
    void aSinglePieceWithoutACombinedModelStaysRegular() {
        assertEquals(
                GeorgianWallSlopeResolver.Variant.REGULAR,
                GeorgianWallSlopeResolver.transitionVariant(
                        GeorgianWallSlopeResolver.Part.LOWER,
                        GeorgianWallSlopeResolver.Profile.STEEP_45,
                        true,
                        true
                )
        );
    }

    @Test
    void shallowRunFinishesOnUpperBeforeTheFlatBoundary() {
        assertTrue(GeorgianWallSlopeResolver.isShallowFlatContinuation(
                GeorgianWallSlopeResolver.Part.LOWER,
                GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                true,
                false
        ));
        assertFalse(GeorgianWallSlopeResolver.isShallowFlatContinuation(
                GeorgianWallSlopeResolver.Part.LOWER,
                GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                true,
                true
        ));
        assertFalse(GeorgianWallSlopeResolver.isShallowFlatContinuation(
                GeorgianWallSlopeResolver.Part.UPPER,
                GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                true,
                false
        ));
    }

    @Test
    void upperShallowStairEndpointRemainsPierFree() {
        assertTrue(GeorgianWallSlopeResolver.isPierFreeShallowStairEndpoint(
                GeorgianWallSlopeResolver.Part.LOWER,
                GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                true,
                false,
                true
        ));
        assertFalse(GeorgianWallSlopeResolver.isPierFreeShallowStairEndpoint(
                GeorgianWallSlopeResolver.Part.LOWER,
                GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                true,
                false,
                false
        ));
        assertFalse(GeorgianWallSlopeResolver.isPierFreeShallowStairEndpoint(
                GeorgianWallSlopeResolver.Part.LOWER,
                GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                true,
                true,
                true
        ));
    }

    @Test
    void rejectsUnsupportedProfileAndTransitionCombinations() {
        assertEquals(
                GeorgianWallSlopeResolver.Mode.NONE,
                GeorgianWallSlopeResolver.modeForSlope(
                        GeorgianWallSlopeResolver.Part.UPPER,
                        Direction.NORTH,
                        GeorgianWallSlopeResolver.Profile.STEEP_45,
                        GeorgianWallSlopeResolver.Variant.REGULAR
                )
        );
        assertEquals(
                GeorgianWallSlopeResolver.Mode.NONE,
                GeorgianWallSlopeResolver.modeForSlope(
                        GeorgianWallSlopeResolver.Part.UPPER,
                        Direction.NORTH,
                        GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                        GeorgianWallSlopeResolver.Variant.ONRAMP
                )
        );
        assertEquals(
                GeorgianWallSlopeResolver.Mode.NONE,
                GeorgianWallSlopeResolver.modeForSlope(
                        GeorgianWallSlopeResolver.Part.LOWER,
                        Direction.NORTH,
                        GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                        GeorgianWallSlopeResolver.Variant.OFFRAMP
                )
        );
    }

    @Test
    void validModesRemainSlopeModels() {
        GeorgianWallSlopeResolver.Mode shallow = GeorgianWallSlopeResolver.modeForSlope(
                GeorgianWallSlopeResolver.Part.UPPER,
                Direction.WEST,
                GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                GeorgianWallSlopeResolver.Variant.OFFRAMP
        );
        GeorgianWallSlopeResolver.Mode steep = GeorgianWallSlopeResolver.modeForSlope(
                GeorgianWallSlopeResolver.Part.LOWER,
                Direction.EAST,
                GeorgianWallSlopeResolver.Profile.STEEP_45,
                GeorgianWallSlopeResolver.Variant.ONRAMP
        );

        assertTrue(shallow.isSlope());
        assertEquals(GeorgianWallSlopeResolver.Variant.OFFRAMP, shallow.variant());
        assertTrue(steep.isSlope());
        assertEquals(GeorgianWallSlopeResolver.Variant.ONRAMP, steep.variant());
    }

    @Test
    void bothEndsOfShallowAndSteepInclinesAreJointBoundaries() {
        BlockPos shallowPos = new BlockPos(10, 20, 30);
        GeorgianWallSlopeResolver.Mode shallow = GeorgianWallSlopeResolver.modeForSlope(
                GeorgianWallSlopeResolver.Part.UPPER,
                Direction.NORTH,
                GeorgianWallSlopeResolver.Profile.SHALLOW_27,
                GeorgianWallSlopeResolver.Variant.OFFRAMP
        );
        assertTrue(GeorgianWallSlopeResolver.isSlopeEndpoint(
                GeorgianWallSlopeResolver.downhillNeighbourPos(shallowPos, shallow),
                shallowPos,
                shallow
        ));
        assertTrue(GeorgianWallSlopeResolver.isSlopeEndpoint(
                GeorgianWallSlopeResolver.uphillNeighbourPos(shallowPos, shallow),
                shallowPos,
                shallow
        ));

        BlockPos steepPos = new BlockPos(-4, 8, 12);
        GeorgianWallSlopeResolver.Mode steep = GeorgianWallSlopeResolver.modeForSlope(
                GeorgianWallSlopeResolver.Part.LOWER,
                Direction.WEST,
                GeorgianWallSlopeResolver.Profile.STEEP_45,
                GeorgianWallSlopeResolver.Variant.ONRAMP
        );
        assertTrue(GeorgianWallSlopeResolver.isSlopeEndpoint(
                GeorgianWallSlopeResolver.downhillNeighbourPos(steepPos, steep),
                steepPos,
                steep
        ));
        assertTrue(GeorgianWallSlopeResolver.isSlopeEndpoint(
                GeorgianWallSlopeResolver.uphillNeighbourPos(steepPos, steep),
                steepPos,
                steep
        ));
        assertFalse(GeorgianWallSlopeResolver.isSlopeEndpoint(
                steepPos.offset(Direction.NORTH),
                steepPos,
                steep
        ));
    }
}
