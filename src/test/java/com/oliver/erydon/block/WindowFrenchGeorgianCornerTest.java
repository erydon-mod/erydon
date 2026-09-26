package com.oliver.erydon.block;

import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowFrenchGeorgianCornerTest {
    @Test
    void directTopClicksAlwaysExtendAndParallelFacingsNeverMerge() {
        for (Direction primary : Direction.Type.HORIZONTAL) {
            for (WindowFrenchGeorgianBlock.Piece piece : WindowFrenchGeorgianBlock.Piece.values()) {
                for (Direction player : Direction.Type.HORIZONTAL) {
                    assertEquals(WindowFrenchGeorgianBlock.Corner.NONE,
                            WindowFrenchGeorgianBlock.Corner.forPlacement(primary, piece, player,
                                    Direction.UP, true, false));
                }
                for (Direction player : new Direction[]{primary, primary.getOpposite()}) {
                    assertEquals(WindowFrenchGeorgianBlock.Corner.NONE,
                            WindowFrenchGeorgianBlock.Corner.forPlacement(primary, piece, player,
                                    primary.rotateYClockwise(), true, false));
                }
            }
        }
    }

    @Test
    void supportAndPlayerFacingCanAddEitherCornerOnUpperAndLowerRows() {
        for (Direction primary : Direction.Type.HORIZONTAL) {
            for (WindowFrenchGeorgianBlock.Corner corner : new WindowFrenchGeorgianBlock.Corner[]{
                    WindowFrenchGeorgianBlock.Corner.LEFT, WindowFrenchGeorgianBlock.Corner.RIGHT}) {
                Direction requested = corner.secondaryFacing(primary);
                for (WindowFrenchGeorgianBlock.Piece piece : WindowFrenchGeorgianBlock.Piece.values()) {
                    if (!corner.accepts(piece)) continue;
                    // Clicking a supporting wall uses its surface, regardless of player facing.
                    assertEquals(corner, WindowFrenchGeorgianBlock.Corner.forPlacement(
                            primary, piece, primary, requested, false, false));
                    // Clicking floor support or an existing pane uses the player's facing.
                    assertEquals(corner, WindowFrenchGeorgianBlock.Corner.forPlacement(
                            primary, piece, requested, Direction.UP, false, false));
                    assertEquals(corner, WindowFrenchGeorgianBlock.Corner.forPlacement(
                            primary, piece, requested, primary, true, false));
                    assertEquals(WindowFrenchGeorgianBlock.Corner.NONE,
                            WindowFrenchGeorgianBlock.Corner.forPlacement(
                                    primary, piece, requested, primary, true, true));
                }
            }
        }
    }

    @Test
    void cornerFaceTurnsPerpendicularlyForEveryFacing() {
        for (Direction primary : Direction.Type.HORIZONTAL) {
            assertEquals(primary.rotateYCounterclockwise(),
                    WindowFrenchGeorgianBlock.Corner.LEFT.secondaryFacing(primary));
            assertEquals(primary.rotateYClockwise(),
                    WindowFrenchGeorgianBlock.Corner.RIGHT.secondaryFacing(primary));
            assertNull(WindowFrenchGeorgianBlock.Corner.NONE.secondaryFacing(primary));
        }
    }

    @Test
    void reusedEndModelsMatchThePrimaryWindowHeightAndCornerSide() {
        for (WindowFrenchGeorgianBlock.Piece primary : WindowFrenchGeorgianBlock.Piece.values()) {
            boolean upper = primary.asString().startsWith("upper");
            assertEquals(upper ? WindowFrenchGeorgianBlock.Piece.UPPER_MULTI_LH
                            : WindowFrenchGeorgianBlock.Piece.LOWER_MULTI_LH,
                    WindowFrenchGeorgianBlock.Corner.LEFT.pieceFor(primary, false));
            assertEquals(upper ? WindowFrenchGeorgianBlock.Piece.UPPER_MULTI_RH
                            : WindowFrenchGeorgianBlock.Piece.LOWER_MULTI_RH,
                    WindowFrenchGeorgianBlock.Corner.RIGHT.pieceFor(primary, false));
            assertEquals(upper ? WindowFrenchGeorgianBlock.Piece.UPPER_MULTI_RH
                            : WindowFrenchGeorgianBlock.Piece.LOWER_MULTI_RH,
                    WindowFrenchGeorgianBlock.Corner.LEFT.pieceFor(primary, true));
            assertEquals(upper ? WindowFrenchGeorgianBlock.Piece.UPPER_MULTI_LH
                            : WindowFrenchGeorgianBlock.Piece.LOWER_MULTI_LH,
                    WindowFrenchGeorgianBlock.Corner.RIGHT.pieceFor(primary, true));
        }
    }

    @Test
    void cornersOnlyAttachAtTheMatchingEndOfAWindowRun() {
        assertTrue(WindowFrenchGeorgianBlock.Corner.LEFT.accepts(WindowFrenchGeorgianBlock.Piece.LOWER_SINGLE));
        assertTrue(WindowFrenchGeorgianBlock.Corner.RIGHT.accepts(WindowFrenchGeorgianBlock.Piece.UPPER_SINGLE));
        assertTrue(WindowFrenchGeorgianBlock.Corner.LEFT.accepts(WindowFrenchGeorgianBlock.Piece.LOWER_MULTI_RH));
        assertTrue(WindowFrenchGeorgianBlock.Corner.RIGHT.accepts(WindowFrenchGeorgianBlock.Piece.UPPER_MULTI_LH));
        assertFalse(WindowFrenchGeorgianBlock.Corner.LEFT.accepts(WindowFrenchGeorgianBlock.Piece.LOWER_MULTI_LH));
        assertFalse(WindowFrenchGeorgianBlock.Corner.RIGHT.accepts(WindowFrenchGeorgianBlock.Piece.UPPER_MULTI_RH));
        assertFalse(WindowFrenchGeorgianBlock.Corner.LEFT.accepts(WindowFrenchGeorgianBlock.Piece.UPPER_MULTI_MID));
    }
}
