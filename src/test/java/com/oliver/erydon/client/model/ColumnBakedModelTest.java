package com.oliver.erydon.client.model;

import com.oliver.erydon.block.ColumnBlock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColumnBakedModelTest {
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
