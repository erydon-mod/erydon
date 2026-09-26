package com.oliver.erydon.block;

import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArchitecturalTransformTest {
    @Test void axiomMoveRotateThenFlipKeepsArchRowsInOrder() {
        List<ArchRomanesqueBlock.Arrangement[]> rows = List.of(
                new ArchRomanesqueBlock.Arrangement[]{
                        ArchRomanesqueBlock.Arrangement.DOUBLE_TOP_L,
                        ArchRomanesqueBlock.Arrangement.DOUBLE_TOP_R},
                new ArchRomanesqueBlock.Arrangement[]{
                        ArchRomanesqueBlock.Arrangement.DOUBLE_BODY_L,
                        ArchRomanesqueBlock.Arrangement.DOUBLE_BODY_R},
                new ArchRomanesqueBlock.Arrangement[]{
                        ArchRomanesqueBlock.Arrangement.TRIPLE_TOP_L,
                        ArchRomanesqueBlock.Arrangement.TOP_LARGE,
                        ArchRomanesqueBlock.Arrangement.TRIPLE_TOP_R},
                new ArchRomanesqueBlock.Arrangement[]{
                        ArchRomanesqueBlock.Arrangement.TRIPLE_ROW2_L,
                        ArchRomanesqueBlock.Arrangement.TRIPLE_VOID,
                        ArchRomanesqueBlock.Arrangement.TRIPLE_ROW2_R},
                new ArchRomanesqueBlock.Arrangement[]{
                        ArchRomanesqueBlock.Arrangement.TRIPLE_COLUMN_UPPER_L,
                        ArchRomanesqueBlock.Arrangement.TRIPLE_VOID_COLUMN,
                        ArchRomanesqueBlock.Arrangement.TRIPLE_COLUMN_UPPER_R});
        for (Direction facing : Direction.Type.HORIZONTAL) {
            Direction sourceRight = facing.rotateYClockwise();
            Direction rotatedFacing = BlockRotation.CLOCKWISE_90.rotate(facing);
            for (BlockMirror mirror : new BlockMirror[]{BlockMirror.FRONT_BACK, BlockMirror.LEFT_RIGHT}) {
                Direction resultRight = mirror.apply(rotatedFacing).rotateYClockwise();
                int rotatedRightX = -sourceRight.getOffsetZ();
                int rotatedRightZ = sourceRight.getOffsetX();
                int resultRightX = mirror == BlockMirror.FRONT_BACK ? -rotatedRightX : rotatedRightX;
                int resultRightZ = mirror == BlockMirror.LEFT_RIGHT ? -rotatedRightZ : rotatedRightZ;
                int directionSign = resultRightX * resultRight.getOffsetX()
                        + resultRightZ * resultRight.getOffsetZ();
                for (var row : rows) {
                    for (int x = 0; x < row.length; x++) {
                        int expectedColumn = directionSign < 0 ? row.length - 1 - x : x;
                        assertEquals(row[expectedColumn], row[x].mirrored(),
                                facing + " " + mirror + " " + row[x]);
                    }
                }
            }
        }
    }

    @Test void windowMirrorsReverseLocalHandednessForBothWorldAxes() {
        for (Direction facing : Direction.Type.HORIZONTAL) {
            for (BlockMirror mirror : new BlockMirror[]{BlockMirror.FRONT_BACK, BlockMirror.LEFT_RIGHT}) {
                Direction mirroredFacing = mirror.apply(facing);
                assertEquals(mirroredFacing.rotateYClockwise(), mirror.apply(facing.rotateYCounterclockwise()));
                for (var piece : WindowArchBlock.Piece.values()) {
                    var result = piece.mirrored();
                    assertEquals(piece, result.mirrored());
                    if (piece.name().endsWith("LEFT")) assertTrue(result.name().endsWith("RIGHT"));
                    if (piece.name().endsWith("RIGHT")) assertTrue(result.name().endsWith("LEFT"));
                }
            }
        }
    }

    @Test void archMirrorsKeepClusterLayoutAndQuarterTurnsPreserveLocalSides() {
        for (var arrangement : ArchRomanesqueBlock.Arrangement.values()) {
            var mirrored = arrangement.mirrored();
            assertEquals(arrangement, mirrored.mirrored());
            assertEquals(arrangement.sideL(), mirrored.sideR());
            assertEquals(arrangement.sideR(), mirrored.sideL());
            assertEquals(arrangement.upperL(), mirrored.upperR());
            assertEquals(arrangement.columnL(), mirrored.columnR());
            assertEquals(arrangement.plinthL(), mirrored.plinthR());
            assertEquals(arrangement.corner(), mirrored.corner());
            assertEquals(arrangement.hasTopLarge(), mirrored.hasTopLarge());
            assertEquals(arrangement.isVoid(), mirrored.isVoid());
        }
        for (Direction facing : Direction.Type.HORIZONTAL) {
            for (BlockRotation rotation : BlockRotation.values()) {
                assertEquals(rotation.rotate(facing).rotateYCounterclockwise(),
                        rotation.rotate(facing.rotateYCounterclockwise()));
            }
        }
    }

    @Test void entryPointsUseTheAuditedMappingsWithoutTouchingGlassOrOpenState() throws Exception {
        String window = Files.readString(Path.of("src/main/java/com/oliver/erydon/block/WindowArchBlock.java"));
        String body = window.substring(window.indexOf("public BlockState mirror("), window.indexOf("private void applyRectLayout"));
        assertTrue(body.contains("mirror != BlockMirror.NONE"));
        assertTrue(body.contains("piece = piece.mirrored()"));
        assertFalse(body.contains(".with(GLASS"));
        assertFalse(body.contains(".with(OPEN"));
    }

    @Test void multifacePlacementUsesTheClickedAxisEvenAtCorners() {
        for (Direction direction : Direction.values()) {
            double boundary = direction.getDirection() == Direction.AxisDirection.POSITIVE ? 0 : 1;
            assertEquals(direction.getOpposite(), MultifacePlacement.touchingFace(direction, boundary, boundary, boundary));
            assertNull(MultifacePlacement.touchingFace(direction, .5, .5, .5));
        }
    }
}
