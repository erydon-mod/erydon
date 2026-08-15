package com.oliver.erydon.client.model;

import com.oliver.erydon.block.SurroundBlock;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

public final class HorizontalUvLockAudit {
    private static final float EPSILON = 0.000001F;

    private HorizontalUvLockAudit() {
    }

    public static void main(String[] args) {
        verify(Direction.UP, 1, 3.2F, 4.8F, 11.2F, 3.2F);
        verify(Direction.DOWN, 1, 3.2F, 4.8F, 4.8F, 12.8F);
        verify(Direction.UP, 2, 3.2F, 4.8F, 12.8F, 11.2F);
        verify(Direction.DOWN, 3, 3.2F, 4.8F, 11.2F, 3.2F);

        for (Direction face : new Direction[]{Direction.UP, Direction.DOWN}) {
            float u = 2.75F;
            float v = 13.125F;
            float rotatedU = u;
            float rotatedV = v;
            for (int turn = 0; turn < 4; turn++) {
                float nextU = HorizontalUvLock.lockedU(face, 1, rotatedU, rotatedV);
                float nextV = HorizontalUvLock.lockedV(face, 1, rotatedU, rotatedV);
                rotatedU = nextU;
                rotatedV = nextV;
            }
            assertNear(face + " four-turn U", u, rotatedU);
            assertNear(face + " four-turn V", v, rotatedV);

            float forwardU = HorizontalUvLock.lockedU(face, 1, u, v);
            float forwardV = HorizontalUvLock.lockedV(face, 1, u, v);
            float reverseU = HorizontalUvLock.lockedU(face, 3, forwardU, forwardV);
            float reverseV = HorizontalUvLock.lockedV(face, 3, forwardU, forwardV);
            assertNear(face + " inverse U", u, reverseU);
            assertNear(face + " inverse V", v, reverseV);
        }

        verifyWorldProjection();
        verifyBakeTargets();
        verifyTextureTargets();
        verifySurroundCoverage();
        System.out.println("World-aligned horizontal UV-lock verification passed");
    }

    private static void verifyWorldProjection() {
        assertNear("up projected U", 4.0F, HorizontalUvLock.projectedU(Direction.UP, 0.25F, 0.75F));
        assertNear("up projected V", 12.0F, HorizontalUvLock.projectedV(Direction.UP, 0.25F, 0.75F));
        assertNear("down projected U", 4.0F, HorizontalUvLock.projectedU(Direction.DOWN, 0.25F, 0.75F));
        assertNear("down projected V", 4.0F, HorizontalUvLock.projectedV(Direction.DOWN, 0.25F, 0.75F));

        float[] below = {-2.0F, -1.0F, 3.0F, 2.0F};
        assertTrue("negative range rejected", !HorizontalUvLock.fitIntoSprite(below));

        float[] above = {15.0F, 17.0F, 16.0F, 16.5F};
        assertTrue("positive range rejected", !HorizontalUvLock.fitIntoSprite(above));
        float[] roundingDrift = {-0.0006F, 8.0F, 16.0006F, 4.0F};
        assertTrue("rounding drift fits", HorizontalUvLock.fitIntoSprite(roundingDrift));
        assertNear("rounding drift clamped low", 0.0F, roundingDrift[0]);
        assertNear("rounding drift clamped high", 16.0F, roundingDrift[2]);
        assertTrue("over-wide range rejected", !HorizontalUvLock.fitIntoSprite(new float[]{-1.0F, 16.1F}));
    }

    private static void verifyBakeTargets() {
        assertTarget("surround component", "erydon", "block/surround/gothic_ornate/aganite_surround_gothic_ornate_mantel", true);
        assertTarget("cornice component", "erydon", "block/cornice/georgian/aganite_cornice_georgian_straight", true);
        assertTarget("modern arch component", "erydon", "block/arch/modern/aganite_arch_modern_top_large", true);
        assertTarget("Gothic arch component", "erydon", "block/arch/gothic/aganite_arch_gothic_top_large", true);
        assertTarget("Romanesque arch component", "erydon", "block/arch/romanesque/aganite_arch_romanesque_top_large", true);
        assertTarget("circular column component", "erydon", "block/column/circular/aganite_column_circular_capital", true);
        assertTarget("square column component", "erydon", "block/column/square/aganite_column_square_capital", true);
        assertTarget("arched window component", "erydon", "block/window/arch/aganite_window_arch_wall", true);
        assertTarget("French window component", "erydon", "block/window/french_georgian/aganite_window_french_georgian_sill", true);
        assertTarget("wall post", "erydon", "block/wall/georgian/aganite_wall_georgian_post", true);
        assertTarget("wall side", "erydon", "block/wall/georgian/aganite_wall_georgian_side", true);
        assertTarget("wall pier", "erydon", "block/wall/georgian/aganite_wall_georgian_pier", true);
        assertTarget("wall pier stub", "erydon", "block/wall/georgian/aganite_wall_georgian_pier_stub", true);
        assertTarget("wall diagonal deferred", "erydon", "block/wall/georgian/aganite_wall_georgian_side_diagonal", false);
        assertTarget("wall icon", "erydon", "block/wall/georgian/wall_georgian_icon", false);
        assertTarget("modern arch icon", "erydon", "block/arch/modern/arch_modern_icon", false);
        assertTarget("Gothic arch icon", "erydon", "block/arch/gothic/aganite_arch_gothic_icon", false);
        assertTarget("Gothic column item", "erydon", "block/column/gothic/column_gothic_item", false);
        assertTarget("foreign model", "minecraft", "block/stone", false);
    }

    private static void verifyTextureTargets() {
        assertTextureTarget("normal material", "erydon", "block/aganite_block", true);
        assertTextureTarget("aged material", "erydon", "block/aganite_block_aged", true);
        assertTextureTarget("ashlar material", "erydon", "block/aganite_ashlar_block", true);
        assertTextureTarget("lead", "erydon", "block/lead_black", false);
        assertTextureTarget("glazing", "erydon", "block/glazing_silver", false);
        assertTextureTarget("foreign stone", "minecraft", "block/stone", false);
    }

    private static void verifySurroundCoverage() {
        for (SurroundBlock.PieceType piece : SurroundBlock.PieceType.values()) {
            boolean expected = piece != SurroundBlock.PieceType.NONE;
            if (SurroundBakedModel.locksHorizontalUv(piece) != expected) {
                throw new AssertionError("Surround UV-lock coverage mismatch for " + piece);
            }
        }
    }

    private static void assertTarget(String label, String namespace, String path, boolean expected) {
        boolean actual = HorizontalUvLock.shouldProjectAtBake(new Identifier(namespace, path));
        if (actual != expected) {
            throw new AssertionError(label + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertTextureTarget(String label, String namespace, String path, boolean expected) {
        boolean actual = HorizontalUvLock.shouldLockTexture(new Identifier(namespace, path));
        if (actual != expected) {
            throw new AssertionError(label + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void verify(Direction face, int turns, float u, float v, float expectedU, float expectedV) {
        assertNear(face + " turn " + turns + " U", expectedU,
                HorizontalUvLock.lockedU(face, turns, u, v));
        assertNear(face + " turn " + turns + " V", expectedV,
                HorizontalUvLock.lockedV(face, turns, u, v));
    }

    private static void assertNear(String label, float expected, float actual) {
        if (Math.abs(expected - actual) > EPSILON) {
            throw new AssertionError(label + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertTrue(String label, boolean value) {
        if (!value) {
            throw new AssertionError(label);
        }
    }
}
