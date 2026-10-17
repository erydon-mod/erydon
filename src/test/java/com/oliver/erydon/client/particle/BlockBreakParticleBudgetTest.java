package com.oliver.erydon.client.particle;

import com.google.gson.JsonParser;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class BlockBreakParticleBudgetTest {
    private record Sample(double x, double y, double z, double vx, double vy, double vz) { }

    @Test void boundsOfEverySizeStayWithinSixteenAndKeepBalancedVelocities() {
        for (double x : new double[]{0.01, 0.25, 0.5, 0.75, 1, 4})
            for (double y : new double[]{0.01, 0.25, 0.5, 0.75, 1, 6})
                for (double z : new double[]{0.01, 0.25, 0.5, 0.75, 1, 4}) {
                    Box bounds = new Box(-2, -0.5, 3, -2 + x, -0.5 + y, 3 + z);
                    List<Sample> samples = samples(VoxelShapes.cuboid(bounds));
                    assertTrue(samples.size() >= 8 && samples.size() <= 16, "Particle count for " + bounds);
                    for (Sample sample : samples) {
                        assertTrue(bounds.contains(sample.x, sample.y, sample.z));
                        assertTrue(Math.abs(sample.vx) < 0.5 && Math.abs(sample.vy) < 0.5 && Math.abs(sample.vz) < 0.5);
                    }
                    assertEquals(0, samples.stream().mapToDouble(Sample::vx).sum(), 1e-12);
                    assertEquals(0, samples.stream().mapToDouble(Sample::vy).sum(), 1e-12);
                    assertEquals(0, samples.stream().mapToDouble(Sample::vz).sum(), 1e-12);
                }
        assertEquals(16, samples(VoxelShapes.fullCube()).size());
        assertTrue(samples(VoxelShapes.empty()).isEmpty());
    }

    @Test void disconnectedDetailedShapesUseOnlyOverallBounds() {
        VoxelShape shape = VoxelShapes.empty();
        for (int i = 0; i < 64; i++) {
            double x = (i % 4) / 4.0, y = (i / 4 % 4) / 4.0, z = (i / 16) / 4.0;
            shape = VoxelShapes.union(shape, VoxelShapes.cuboid(x, y, z, x + 0.1, y + 0.1, z + 0.1));
        }
        assertTrue(shape.getBoundingBoxes().size() > 16);
        assertEquals(samples(VoxelShapes.cuboid(shape.getBoundingBox())), samples(shape));
        assertTrue(samples(shape).size() <= 16);
    }

    @Test void hookTargetsOnlyBreakEnumerationAndSamplerNeverWalksShapeBoxes() throws Exception {
        ClassNode minecraft = readClass("/net/minecraft/client/particle/ParticleManager.class");
        long sites = minecraft.methods.stream().filter(m -> m.name.equals("addBlockBreakParticles"))
                .flatMap(m -> java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false))
                .filter(i -> i instanceof MethodInsnNode call && call.owner.equals("net/minecraft/util/shape/VoxelShape")
                        && call.name.equals("forEachBox")
                        && call.desc.equals("(Lnet/minecraft/util/shape/VoxelShapes$BoxConsumer;)V")).count();
        assertEquals(1, sites, "Exactly one mapped break enumeration must be wrapped");
        ClassNode sampler = readClass("/com/oliver/erydon/client/particle/BlockBreakParticleBudget.class");
        for (var method : sampler.methods)
            for (var instruction : method.instructions)
                if (instruction instanceof MethodInsnNode call)
                    assertFalse(call.name.equals("forEachBox") || call.name.equals("getBoundingBoxes"),
                            "The cap must not enumerate fragmented outline boxes");
        var config = JsonParser.parseString(Files.readString(Path.of("src/main/resources/erydon.mixins.json")))
                .getAsJsonObject();
        var hook = new com.google.gson.JsonPrimitive("client.BlockBreakParticlesMixin");
        assertTrue(config.getAsJsonArray("client").contains(hook));
        assertFalse(config.getAsJsonArray("mixins").contains(hook));
        ClassNode mixin = readClass("/com/oliver/erydon/mixin/client/BlockBreakParticlesMixin.class");
        List<String> wrapped = new ArrayList<>();
        for (var method : mixin.methods) {
            if (method.visibleAnnotations == null) continue;
            for (var annotation : method.visibleAnnotations)
                if (annotation.desc.endsWith("/WrapOperation;")) wrapped.add(annotation.values.toString());
        }
        assertEquals(1, wrapped.size());
        assertTrue(wrapped.get(0).contains("addBlockBreakParticles"));
        assertFalse(wrapped.get(0).contains("addBlockBreakingParticles"));
    }

    private static List<Sample> samples(VoxelShape shape) {
        List<Sample> result = new ArrayList<>();
        BlockBreakParticleBudget.emit(shape, (x, y, z, vx, vy, vz) -> result.add(new Sample(x, y, z, vx, vy, vz)));
        return result;
    }

    private static ClassNode readClass(String resource) throws Exception {
        try (var input = BlockBreakParticleBudgetTest.class.getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
}
