package com.oliver.erydon.client.model;

import com.oliver.erydon.client.pom.ColumnMeshNormalTransport;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColumnMeshProjectionTest {
    @Test void columnsKeepTheirSourcePlaneAcrossLightingFaceTies() {
        for (String path : new String[]{"aganite_column_circular_double", "aganite_aged_column_circular_double"}) {
            Identifier column = new Identifier("erydon", path);
            for (Direction lighting : Direction.values()) for (Direction plane : Direction.values()) {
                assertEquals(plane, SynapheiaRepeatBakedModel.repeatProjectionFace(
                        column, lighting, lighting.getOpposite(), ColumnMeshNormalTransport.tag(plane)));
            }
        }
    }

    @Test void foreignFamiliesAndMalformedTagsRetainOrdinaryProjection() {
        for (Identifier id : new Identifier[]{new Identifier("minecraft", "stone"),
                new Identifier("other", "aganite_column_circular_double"),
                new Identifier("erydon", "aganite_column_circular"),
                new Identifier("erydon", "aganite_block")}) {
            for (Direction plane : Direction.values()) {
                assertEquals(Direction.NORTH, SynapheiaRepeatBakedModel.repeatProjectionFace(
                        id, Direction.NORTH, Direction.UP, ColumnMeshNormalTransport.tag(plane)));
            }
        }
        Identifier column = new Identifier("erydon", "aganite_column_circular_double");
        for (int tag : new int[]{0, ColumnMeshNormalTransport.tag(), ColumnMeshNormalTransport.tag() | 7,
                ColumnMeshNormalTransport.tag() | 8, 0x0DAE0000, 0x0DAF0000,
                CopingTexturePlane.tag(Direction.UP), CopingTexturePlane.corniceTag(Direction.UP)}) {
            assertEquals(Direction.NORTH, SynapheiaRepeatBakedModel.repeatProjectionFace(
                    column, Direction.NORTH, Direction.UP, tag));
        }
    }

    @Test void copingAndCorniceTexturePlanesRemainIndependent() {
        for (Direction plane : Direction.values()) {
            assertEquals(plane, SynapheiaRepeatBakedModel.repeatProjectionFace(
                    new Identifier("erydon", "aganite_coping_georgian"), Direction.NORTH, Direction.NORTH,
                    CopingTexturePlane.tag(plane)));
            assertEquals(plane, SynapheiaRepeatBakedModel.repeatProjectionFace(
                    new Identifier("erydon", "aganite_cornice_georgian"), Direction.NORTH, Direction.NORTH,
                    CopingTexturePlane.corniceTag(plane)));
        }
    }
}
