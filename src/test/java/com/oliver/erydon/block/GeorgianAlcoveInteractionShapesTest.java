package com.oliver.erydon.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static com.oliver.erydon.block.AlcoveBlock.*;

class GeorgianAlcoveInteractionShapesTest {
    @Test
    void bodyDetailsRemainAtTheOuterJambAndRepeatWithinTheirRow() {
        for (var part : new AlcovePart[]{AlcovePart.BASE, AlcovePart.MIDDLE}) {
            for (var span : AlcoveSpan.values()) {
                var shape = GeorgianAlcoveInteractionShapes.details(part, span);
                assertSame(shape, GeorgianAlcoveInteractionShapes.details(part, span));
                if (span == AlcoveSpan.TRIPLE_CENTER) {
                    assertTrue(shape.isEmpty());
                } else {
                    assertFalse(shape.isEmpty(), part + "/" + span);
                    var bounds = shape.getBoundingBox();
                    assertEquals(0, bounds.minY, .000001);
                    assertEquals(1, bounds.maxY, .000001);
                    assertTrue(bounds.maxZ > 1, "The projecting jamb must be targetable");
                }
            }
        }
    }

    @Test
    void crownProjectionIncludesTheKeystoneAboveTheBlock() {
        for (var part : new AlcovePart[]{AlcovePart.SINGLE, AlcovePart.TOP}) {
            for (var span : AlcoveSpan.values()) {
                var shape = GeorgianAlcoveInteractionShapes.details(part, span);
                assertFalse(shape.isEmpty(), part + "/" + span);
                assertTrue(shape.getBoundingBox().maxZ > 1);
            }
            var center = GeorgianAlcoveInteractionShapes.details(part, AlcoveSpan.TRIPLE_CENTER);
            assertTrue(center.getBoundingBox().maxY > 1, "Keystone projects above the crown");
        }
    }

    @Test
    void projectingFootBelongsOnlyToTheBaseAndDoesNotRepeatUpTheJamb() {
        for (var span : new AlcoveSpan[]{AlcoveSpan.SINGLE, AlcoveSpan.LEFT, AlcoveSpan.TRIPLE_LEFT}) {
            assertTrue(contains(AlcovePart.BASE, span, -.25, .25, 16.8), "Left foot must be targetable");
            assertFalse(contains(AlcovePart.MIDDLE, span, -.25, .25, 16.8), "Middle rows must keep the fluted profile");
        }
        for (var span : new AlcoveSpan[]{AlcoveSpan.SINGLE, AlcoveSpan.RIGHT, AlcoveSpan.TRIPLE_RIGHT}) {
            assertTrue(contains(AlcovePart.BASE, span, 16.25, .25, 16.8), "Right foot must be targetable");
            assertFalse(contains(AlcovePart.MIDDLE, span, 16.25, .25, 16.8), "Middle rows must keep the fluted profile");
        }
    }

    private static boolean contains(AlcovePart part, AlcoveSpan span, double x, double y, double z) {
        return GeorgianAlcoveInteractionShapes.details(part, span).getBoundingBoxes().stream()
                .anyMatch(box -> box.contains(x / 16, y / 16, z / 16));
    }
}
