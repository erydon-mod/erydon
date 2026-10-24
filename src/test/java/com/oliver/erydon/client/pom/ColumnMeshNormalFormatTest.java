package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;

class ColumnMeshNormalFormatTest {
    @Test void actualPinnedRendererHasTheIndependentNormalContract() {
        assertTrue(ColumnMeshNormalFormat.supported());
    }

    @Test void actualPinnedRendererHasTheSeparateTangentContract() throws Exception {
        assertTrue(ColumnMeshNormalFormat.supportsTangents());
        assertTrue(ColumnMeshNormalFormat.matchesTangentEncoder(read(InlaySubstrateVertexFormat.ENCODER)));
        assertTrue(ColumnMeshNormalFormat.matchesTangentFormat(read(ColumnMeshNormalFormat.FORMAT)));
    }

    @Test void movedTangentWriteOrChangedStagedSlotRetainsNormalOnlySupport() throws Exception {
        for (int mutation = 0; mutation < 3; mutation++) {
            ClassNode encoder = read(InlaySubstrateVertexFormat.ENCODER);
            int changed = 0;
            for (MethodNode method : encoder.methods) if (method.name.equals("write")) for (var instruction : method.instructions) {
                if (instruction instanceof LdcInsnNode value && Long.valueOf(24).equals(value.cst)) {
                    if (mutation == 0) { value.cst = 26L; changed++; }
                    if (mutation == 1) { ((VarInsnNode) next(next(instruction))).var = 9; changed++; }
                } else if (mutation == 2 && instruction instanceof MethodInsnNode call && call.name.equals("computeTangent")) {
                    ((VarInsnNode) next(instruction)).var = 14; changed++;
                }
            }
            assertEquals(1, changed);
            assertTrue(ColumnMeshNormalFormat.matchesEncoder(encoder), "Tangent write changes must preserve the independent normal-only guard");
            assertFalse(ColumnMeshNormalFormat.matchesTangentEncoder(encoder), "Changed tangent staging " + mutation);
        }
    }

    @Test void tangentCodecMustProduceThePinnedStagedPayload() throws Exception {
        ClassNode encoder = read(InlaySubstrateVertexFormat.ENCODER);
        int changed = 0;
        for (MethodNode method : encoder.methods) if (method.name.equals("write")) for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("computeTangent")) {
                call.name = "changedTangentCodec"; changed++;
            }
        }
        assertEquals(1, changed);
        assertTrue(ColumnMeshNormalFormat.matchesEncoder(encoder));
        assertFalse(ColumnMeshNormalFormat.matchesTangentEncoder(encoder));
    }

    @Test void tangentAttributeRequiresSignedNormalizedFourBytesAndThePinnedStride() throws Exception {
        for (int mutation = 0; mutation < 5; mutation++) {
            ClassNode format = read(ColumnMeshNormalFormat.FORMAT);
            int changed = 0;
            for (MethodNode method : format.methods) if (method.name.equals("<clinit>")) for (var instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field) || !field.name.equals("TANGENT")) continue;
                AbstractInsnNode offset = next(instruction), encoding = next(offset), size = next(encoding), normalized = next(size), integer = next(normalized);
                switch (mutation) {
                    case 0 -> ((IntInsnNode) offset).operand = 25;
                    case 1 -> ((FieldInsnNode) encoding).name = "UNSIGNED_BYTE";
                    case 2 -> method.instructions.set(size, new InsnNode(Opcodes.ICONST_3));
                    case 3 -> method.instructions.set(normalized, new InsnNode(Opcodes.ICONST_0));
                    case 4 -> method.instructions.set(integer, new InsnNode(Opcodes.ICONST_1));
                }
                changed++;
                break;
            }
            assertEquals(1, changed);
            assertTrue(ColumnMeshNormalFormat.matchesFormat(format), "Only the tangent contract changed " + mutation);
            assertFalse(ColumnMeshNormalFormat.matchesTangentFormat(format), "Changed tangent attribute " + mutation);
        }
        ClassNode format = read(ColumnMeshNormalFormat.FORMAT);
        format.fields.stream().filter(field -> field.name.equals("STRIDE")).findFirst().orElseThrow().value = 44;
        assertFalse(ColumnMeshNormalFormat.matchesTangentFormat(format));
    }

    @Test void movedNormalOffsetIsRejectedEvenWhenExistingInlayGuardStillAcceptsIt() throws Exception {
        ClassNode encoder = read(InlaySubstrateVertexFormat.ENCODER);
        assertTrue(ColumnMeshNormalFormat.matchesEncoder(encoder));
        int changed = 0;
        for (MethodNode method : encoder.methods) if (method.name.equals("write")) for (var instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode constant && Long.valueOf(28).equals(constant.cst)) {
                constant.cst = 30L; changed++;
            }
        }
        assertEquals(1, changed);
        assertTrue(InlaySubstrateVertexFormat.matchesEncoder(encoder), "Inlay +32/+34/+39 contract is independent");
        assertFalse(ColumnMeshNormalFormat.matchesEncoder(encoder));
    }

    @Test void changedNormalCodecOrComponentOrderFailsClosed() throws Exception {
        ClassNode codec = read(ColumnMeshNormalFormat.PACKING);
        assertTrue(ColumnMeshNormalFormat.matchesPacking(codec));
        for (MethodNode method : codec.methods) if (method.name.equals("pack") && method.desc.equals("(FFFF)I")) {
            for (var instruction : method.instructions) if (instruction instanceof LdcInsnNode value && Float.valueOf(127).equals(value.cst)) value.cst = 128f;
        }
        assertFalse(ColumnMeshNormalFormat.matchesPacking(codec));
        codec = read(ColumnMeshNormalFormat.PACKING);
        for (MethodNode method : codec.methods) if (method.name.equals("pack") && method.desc.equals("(Lorg/joml/Vector3f;)I")) {
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.name.equals("z")) call.name = "y";
        }
        assertFalse(ColumnMeshNormalFormat.matchesPacking(codec));
    }

    @Test void signedNormalizedThreeByteNormalAndStrideAreMandatory() throws Exception {
        for (int mutation = 0; mutation < 5; mutation++) {
            ClassNode format = read(ColumnMeshNormalFormat.FORMAT);
            assertTrue(ColumnMeshNormalFormat.matchesFormat(format));
            for (MethodNode method : format.methods) if (method.name.equals("<clinit>")) for (var instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field) || !field.name.equals("NORMAL")) continue;
                AbstractInsnNode offset = next(instruction), encoding = next(offset), size = next(encoding), normalized = next(size), integer = next(normalized);
                switch (mutation) {
                    case 0 -> ((IntInsnNode) offset).operand = 29;
                    case 1 -> ((FieldInsnNode) encoding).name = "UNSIGNED_BYTE";
                    case 2 -> method.instructions.set(size, new InsnNode(Opcodes.ICONST_4));
                    case 3 -> method.instructions.set(normalized, new InsnNode(Opcodes.ICONST_0));
                    case 4 -> method.instructions.set(integer, new InsnNode(Opcodes.ICONST_1));
                }
                break;
            }
            assertFalse(ColumnMeshNormalFormat.matchesFormat(format), "Changed normal attribute " + mutation);
        }
        ClassNode encoder = read(InlaySubstrateVertexFormat.ENCODER);
        for (MethodNode method : encoder.methods) if (method.name.equals("write")) for (var instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode value && Long.valueOf(40).equals(value.cst)) value.cst = 44L;
        }
        assertFalse(ColumnMeshNormalFormat.matchesEncoder(encoder));
        assertFalse(ColumnMeshNormalFormat.matchesTangentEncoder(encoder), "Tangent writes also require the pinned encoder stride");
    }

    @Test void stagingOrderAndImmediateArrayForwardingMustRemainExact() throws Exception {
        ClassNode terrain = read(ColumnMeshNormalFormat.TERRAIN);
        assertTrue(ColumnMeshNormalFormat.matchesTerrain(terrain));
        for (MethodNode method : terrain.methods) if (method.name.equals("bufferQuad")) for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("getVertexIndex")) call.name = "differentOrder";
        }
        assertFalse(ColumnMeshNormalFormat.matchesTerrain(terrain));
        ClassNode buffer = read(ColumnMeshNormalFormat.BUILDER);
        assertTrue(ColumnMeshNormalFormat.matchesPush(buffer));
        for (MethodNode method : buffer.methods) if (method.name.equals("push")) for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("write")) call.name = "enqueueWrite";
        }
        assertFalse(ColumnMeshNormalFormat.matchesPush(buffer));
    }

    @Test void actualScopeUsesFinallyAndTheExistingWriterRetainsAllOtherPayloadFields() throws Exception {
        ClassNode indium = read("com/oliver/erydon/mixin/client/indium/TerrainRenderContextMixin");
        MethodNode scope = indium.methods.stream().filter(method -> method.name.equals("erydon$columnCornerNormals")).findFirst().orElseThrow();
        assertTrue(scope.tryCatchBlocks.stream().anyMatch(block -> block.type == null), "Encoder errors must execute scope cleanup");
        long restores = java.util.stream.StreamSupport.stream(scope.instructions.spliterator(), false)
                .filter(instruction -> instruction instanceof MethodInsnNode call && call.name.equals("restore")
                        && call.owner.equals("com/oliver/erydon/client/pom/ColumnMeshNormalTransport")).count();
        assertTrue(restores >= 2, "Successful and exceptional exits restore the prior scope");
        ClassNode writer = read("com/oliver/erydon/mixin/client/compat/iris/InlaySubstrateVertexMixin");
        MethodNode hook = writer.methods.stream().filter(method -> method.name.equals("erydon$writeInlaySubstrate")).findFirst().orElseThrow();
        for (long offset : new long[]{32, 34, 39}) assertTrue(java.util.stream.StreamSupport.stream(hook.instructions.spliterator(), false)
                .anyMatch(instruction -> instruction instanceof LdcInsnNode constant && Long.valueOf(offset).equals(constant.cst)));
    }

    @Test void guardCanRunBeforeMinecraftOrOptionalRendererTypesAreLoaded() throws Exception {
        String helper = ColumnMeshNormalFormat.class.getName();
        ClassLoader isolated = new ClassLoader(ColumnMeshNormalFormat.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(helper) || name.equals(InlaySubstrateVertexFormat.class.getName())) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) try (var stream = getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (stream == null) throw new ClassNotFoundException(name);
                            byte[] bytes = stream.readAllBytes(); loaded = defineClass(name, bytes, 0, bytes.length);
                        } catch (java.io.IOException exception) { throw new ClassNotFoundException(name, exception); }
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                }
                if (name.startsWith("net.minecraft.") || name.startsWith("net.fabricmc.") || name.startsWith("net.irisshaders.")
                        || name.startsWith("link.infra.indium.") || name.startsWith("me.jellysquid.")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        Class<?> guard = Class.forName(helper, true, isolated);
        assertEquals(Boolean.TRUE, guard.getMethod("supported").invoke(null));
        assertEquals(Boolean.TRUE, guard.getMethod("supportsTangents").invoke(null));
    }

    private static ClassNode read(String name) throws Exception { return InlaySubstrateVertexFormat.read(name); }
    private static AbstractInsnNode next(AbstractInsnNode node) {
        do { node = node.getNext(); } while (node != null && node.getOpcode() < 0);
        return node;
    }
}
