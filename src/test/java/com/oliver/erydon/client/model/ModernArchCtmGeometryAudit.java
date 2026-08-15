package com.oliver.erydon.client.model;

import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

import java.util.List;

public final class ModernArchCtmGeometryAudit {
    private static final float EPSILON = 0.00001F;

    private ModernArchCtmGeometryAudit() {
    }

    public static void main(String[] args) {
        verifyCtmSetAndModelClassification();
        verifyRotatedFrontFaceWorldProjection();
        System.out.println("Modern arch CTM geometry audit passed.");
    }

    private static void verifyCtmSetAndModelClassification() {
        ErydonCtmService service = ErydonCtmService.get(null);
        assertEquals("normal", "aganite", service.modernArchCtmSetName("aganite_arch_modern"));
        assertEquals("aged", "aganite_aged", service.modernArchCtmSetName("aganite_aged_arch_modern"));
        assertEquals("suffix-aged parser", "legacy_aged",
                service.modernArchCtmSetName("legacy_arch_modern_aged"));
        assertEquals("ashlar", "aganite_ashlar",
                service.modernArchCtmSetName("aganite_ashlar_arch_modern"));
        assertEquals("rusticated", "aganite_rusticated",
                service.modernArchCtmSetName("aganite_rusticated_arch_modern"));
        assertEquals("hewn", "aganite_hewn",
                service.modernArchCtmSetName("aganite_hewn_arch_modern"));
        assertEquals("rock", "aganite_rock",
                service.modernArchCtmSetName("aganite_rock_arch_modern"));
        assertEquals("unrelated", null, service.modernArchCtmSetName("aganite_arch_gothic"));

        Identifier blockId = new Identifier("erydon", "aganite_arch_modern");
        if (!ModernArchCtmModelLoadingPlugin.isWorldModernArchModel(
                new ModelIdentifier(blockId, "arr=small_top,facing=north,width=1,waterlogged=false"))) {
            throw new IllegalStateException("World Modern arch model was not classified.");
        }
        if (ModernArchCtmModelLoadingPlugin.isWorldModernArchModel(
                new ModelIdentifier(blockId, "inventory"))) {
            throw new IllegalStateException("Inventory Modern arch must retain its authored icon model.");
        }
        if (ModernArchCtmModelLoadingPlugin.isWorldModernArchModel(
                new ModelIdentifier(new Identifier("erydon", "aganite_arch_gothic"), "normal"))) {
            throw new IllegalStateException("Gothic arch was classified as Modern.");
        }
        if (ModernArchCtmModelLoadingPlugin.isWorldModernArchModel(
                new ModelIdentifier(new Identifier("minecraft", "stone"), "normal"))) {
            throw new IllegalStateException("Foreign model was classified as an ERYDON Modern arch.");
        }
    }

    private static void verifyRotatedFrontFaceWorldProjection() {
        List<SpiralStairCtmGeometry.Vertex> vertices = List.of(
                vertex(-0.10F, 0.20F, 0.5F),
                vertex(0.85F, 0.55F, 0.5F),
                vertex(0.55F, 1.35F, 0.5F),
                vertex(-0.40F, 1.00F, 0.5F)
        );
        List<SpiralStairCtmGeometry.Fragment> fragments =
                SpiralStairCtmGeometry.split(Direction.SOUTH, vertices);
        if (fragments.size() < 3) {
            throw new IllegalStateException("Expected rotated Modern face to cross several world cells.");
        }

        for (SpiralStairCtmGeometry.Fragment fragment : fragments) {
            for (SpiralStairCtmGeometry.CellVertex cellVertex : fragment.vertices()) {
                assertNear("world X projection",
                        cellVertex.vertex().x() - fragment.cellS(),
                        SpiralStairCtmGeometry.u(Direction.SOUTH, cellVertex));
                assertNear("world Y projection",
                        1.0F - (cellVertex.vertex().y() - fragment.cellT()),
                        SpiralStairCtmGeometry.v(Direction.SOUTH, cellVertex));
            }
        }
    }

    private static SpiralStairCtmGeometry.Vertex vertex(float x, float y, float z) {
        return new SpiralStairCtmGeometry.Vertex(
                x, y, z, -1, 0, false, 0.0F, 0.0F, 0.0F);
    }

    private static void assertNear(String label, float expected, float actual) {
        if (Math.abs(expected - actual) > EPSILON) {
            throw new IllegalStateException(label + ": expected " + expected + ", found " + actual);
        }
    }

    private static void assertEquals(String label, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new IllegalStateException(label + ": expected " + expected + ", found " + actual);
        }
    }
}
