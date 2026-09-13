package com.oliver.erydon.block;

import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WindowArchPerformanceTest {
    @Test
    void particleHookMatchesTheMinecraftMethodAndIsClientOnly() throws Exception {
        var node = new org.objectweb.asm.tree.ClassNode();
        try (var input = getClass().getResourceAsStream("/net/minecraft/client/particle/ParticleManager.class")) {
            assertNotNull(input);
            new org.objectweb.asm.ClassReader(input).accept(node, 0);
        }
        int matches = 0;
        for (var method : node.methods) {
            if (!method.name.equals("addBlockBreakParticles")) continue;
            for (var instruction : method.instructions) {
                if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                        && call.owner.equals("net/minecraft/block/BlockState")
                        && call.name.equals("getOutlineShape")
                        && call.desc.equals("(Lnet/minecraft/world/BlockView;Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/util/shape/VoxelShape;")) {
                    matches++;
                }
            }
        }
        assertEquals(1, matches, "The particle hook must match exactly one Minecraft call site");
        var config = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/erydon.mixins.json"))).getAsJsonObject();
        var hook = new com.google.gson.JsonPrimitive("client.WindowArchBreakParticlesMixin");
        assertTrue(config.getAsJsonArray("client").contains(hook));
        assertFalse(config.getAsJsonArray("mixins").contains(hook));
    }

    @Test
    void all128StatesKeepExactCollisionAndReuseCachedShapes() {
        for (var piece : WindowArchBlock.Piece.values()) {
            for (Direction facing : Direction.Type.HORIZONTAL) {
                for (boolean open : new boolean[]{false, true}) {
                    VoxelShape base = WindowArchShapes.shapeFor(piece, open, false, facing);
                    // An open central pane has no frame: its sill-only shape is the authored sill.
                    VoxelShape sill = WindowArchShapes.shapeFor(WindowArchBlock.Piece.LOWER_GLASS, true, true, facing);
                    for (boolean hasSill : new boolean[]{false, true}) {
                        VoxelShape expected = hasSill ? VoxelShapes.combine(base, sill, BooleanBiFunction.OR) : base;
                        VoxelShape actual = WindowArchShapes.shapeFor(piece, open, hasSill, facing);
                        assertFalse(VoxelShapes.matchesAnywhere(expected, actual, BooleanBiFunction.NOT_SAME),
                                "Caching must preserve every occupied collision voxel");
                        assertSame(actual, WindowArchShapes.shapeFor(piece, open, hasSill, facing));
                    }
                }
            }
        }
    }

    @Test
    void breakParticlesAreBoundedForEveryStateWithoutFillingEmptyOpenings() {
        int worstOriginal = 0;
        int worstOptimized = 0;
        for (var piece : WindowArchBlock.Piece.values()) {
            for (Direction facing : Direction.Type.HORIZONTAL) {
                for (boolean open : new boolean[]{false, true}) {
                    for (boolean sill : new boolean[]{false, true}) {
                        VoxelShape detailed = WindowArchShapes.shapeFor(piece, open, sill, facing);
                        VoxelShape particles = WindowArchShapes.particleShapeFor(piece, open, sill, facing);
                        assertEquals(detailed.isEmpty(), particles.isEmpty());
                        assertSame(particles, WindowArchShapes.particleShapeFor(piece, open, sill, facing));
                        if (!detailed.isEmpty()) assertEquals(detailed.getBoundingBox(), particles.getBoundingBox());
                        int count = vanillaParticleCount(particles);
                        assertTrue(count <= 64, "An arch-window break must never exceed vanilla cube debris");
                        worstOriginal = Math.max(worstOriginal, vanillaParticleCount(detailed));
                        worstOptimized = Math.max(worstOptimized, count);
                    }
                }
            }
        }
        assertTrue(worstOriginal > 1000, "The regression fixture must exercise detailed arch geometry");
        System.out.println("Arch-window breaking particles: original max=" + worstOriginal + ", optimized max=" + worstOptimized);
    }

    private static int vanillaParticleCount(VoxelShape shape) {
        int[] count = {0};
        shape.forEachBox((x0, y0, z0, x1, y1, z1) -> count[0] += divisions(x1-x0) * divisions(y1-y0) * divisions(z1-z0));
        return count[0];
    }

    private static int divisions(double extent) {
        return Math.max(2, (int)Math.ceil(Math.min(1, extent) / .25));
    }
}
