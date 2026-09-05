package com.oliver.erydon.client.model;

import com.oliver.erydon.block.ColumnBlock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColumnBakedModelTest {
    @Test
    void capitalCanTurnWithoutTurningTheBaseOrShaft() {
        for (boolean gothic : new boolean[]{false, true}) {
            for (ColumnBlock.CapitalStyle style : new ColumnBlock.CapitalStyle[]{
                    ColumnBlock.CapitalStyle.GEORGIAN, ColumnBlock.CapitalStyle.GUILLOCHE,
                    ColumnBlock.CapitalStyle.NARROW}) {
                assertEquals(ColumnBlock.Orientation.DIAGONAL, ColumnBakedModel.orientationForPart(
                        ColumnBlock.ColumnPart.CAPITAL, style, gothic,
                        ColumnBlock.Orientation.DIAGONAL, ColumnBlock.Orientation.STRAIGHT));
                assertEquals(ColumnBlock.Orientation.STRAIGHT, ColumnBakedModel.orientationForPart(
                        ColumnBlock.ColumnPart.BASE, style, gothic,
                        ColumnBlock.Orientation.DIAGONAL, ColumnBlock.Orientation.STRAIGHT));
                assertEquals(ColumnBlock.Orientation.STRAIGHT, ColumnBakedModel.orientationForPart(
                        ColumnBlock.ColumnPart.PILLAR, style, gothic,
                        ColumnBlock.Orientation.DIAGONAL, ColumnBlock.Orientation.DIAGONAL));
            }
        }
    }

    @Test
    void baseCanTurnIndependentlyAndNoCapitalKeepsAnUnturnedShaft() {
        for (ColumnBlock.ColumnPart part : new ColumnBlock.ColumnPart[]{
                ColumnBlock.ColumnPart.PLINTH, ColumnBlock.ColumnPart.BASE}) {
            assertEquals(ColumnBlock.Orientation.DIAGONAL, ColumnBakedModel.orientationForPart(
                    part, ColumnBlock.CapitalStyle.GUILLOCHE, false,
                    ColumnBlock.Orientation.STRAIGHT, ColumnBlock.Orientation.DIAGONAL));
        }
        assertEquals(ColumnBlock.Orientation.STRAIGHT, ColumnBakedModel.orientationForPart(
                ColumnBlock.ColumnPart.CAPITAL, ColumnBlock.CapitalStyle.NONE, false,
                ColumnBlock.Orientation.DIAGONAL, ColumnBlock.Orientation.DIAGONAL));
        // Gothic ignores the unused capital-style property, including NONE.
        assertEquals(ColumnBlock.Orientation.DIAGONAL, ColumnBakedModel.orientationForPart(
                ColumnBlock.ColumnPart.CAPITAL, ColumnBlock.CapitalStyle.NONE, true,
                ColumnBlock.Orientation.DIAGONAL, ColumnBlock.Orientation.STRAIGHT));
    }

    @Test
    void noCapitalReusesTheShaftModelForBothColumnShapes() {
        assertEquals("pillar",
                ColumnBakedModel.capitalSuffix(ColumnBlock.CapitalStyle.NONE, false));
        assertEquals("pillar",
                ColumnBakedModel.capitalSuffix(ColumnBlock.CapitalStyle.NONE, true));
    }

    @Test
    void existingCapitalModelsRemainSelected() {
        assertEquals("capital",
                ColumnBakedModel.capitalSuffix(ColumnBlock.CapitalStyle.GEORGIAN, false));
        assertEquals("capital_guilloche",
                ColumnBakedModel.capitalSuffix(ColumnBlock.CapitalStyle.GUILLOCHE, true));
        assertEquals("capital_narrow",
                ColumnBakedModel.capitalSuffix(ColumnBlock.CapitalStyle.NARROW, true));
        assertEquals("capital",
                ColumnBakedModel.capitalSuffix(ColumnBlock.CapitalStyle.NARROW, false));
    }
}
