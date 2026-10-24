package com.oliver.erydon.client.pom;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Independent fail-closed contract for Iris's normal field, packing, and synchronous staged vertex order. */
public final class ColumnMeshNormalFormat {
    static final String FORMAT = "net/irisshaders/iris/compat/sodium/impl/vertex_format/terrain_xhfp/XHFPModelVertexType";
    static final String PACKING = "net/irisshaders/iris/vertices/NormI8";
    static final String TERRAIN = "link/infra/indium/renderer/render/TerrainRenderContext";
    static final String BUILDER = "me/jellysquid/mods/sodium/client/render/chunk/vertex/builder/ChunkMeshBufferBuilder";
    static final String VERTEX = "me/jellysquid/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex";
    static final String MATERIAL = "me/jellysquid/mods/sodium/client/render/chunk/terrain/material/Material";
    static final String PUSH = "([L" + VERTEX + ";L" + MATERIAL + ";)V";
    private static final String ORIENTATION = "me/jellysquid/mods/sodium/client/model/quad/properties/ModelQuadOrientation";
    private static final String QUAD = "link/infra/indium/renderer/mesh/MutableQuadViewImpl";
    private static final String ATTRIBUTES = "net/irisshaders/iris/compat/sodium/impl/vertex_format/IrisChunkMeshAttributes";
    private static final String ATTRIBUTE_FORMAT = "me/jellysquid/mods/sodium/client/gl/attribute/GlVertexAttributeFormat";
    private static final String GL_BUILDER = "me/jellysquid/mods/sodium/client/gl/attribute/GlVertexFormat$Builder";

    private ColumnMeshNormalFormat() { }

    /** Reads class resources only; optional renderer classes are never loaded during the guard. */
    public static boolean supported() {
        try {
            return matchesEncoder(InlaySubstrateVertexFormat.read(InlaySubstrateVertexFormat.ENCODER))
                    && matchesFormat(InlaySubstrateVertexFormat.read(FORMAT))
                    && matchesPacking(InlaySubstrateVertexFormat.read(PACKING))
                    && matchesTerrain(InlaySubstrateVertexFormat.read(TERRAIN))
                    && matchesPush(InlaySubstrateVertexFormat.read(BUILDER));
        } catch (IOException | RuntimeException incompatible) { return false; }
    }

    /** A changed tangent layout disables only tangent overrides, retaining the normal-only contract. */
    public static boolean supportsTangents() {
        try {
            return supported()
                    && matchesTangentEncoder(InlaySubstrateVertexFormat.read(InlaySubstrateVertexFormat.ENCODER))
                    && matchesTangentFormat(InlaySubstrateVertexFormat.read(FORMAT));
        } catch (IOException | RuntimeException incompatible) { return false; }
    }

    static boolean matchesTangentEncoder(ClassNode type) {
        if (!matchesEncoder(type)) return false;
        MethodNode write = method(type, "write", InlaySubstrateVertexFormat.WRITE_DESCRIPTOR);
        List<AbstractInsnNode> code = instructions(write);
        int writes = 0, packing = 0, stores = 0, loads = 0;
        for (int i = 0; i < code.size(); i++) {
            AbstractInsnNode instruction = code.get(i);
            // Slot 10 is reused by an earlier texture-average loop before the
            // tangent is computed. Only its live tangent range is relevant.
            if (packing > 0 && variable(instruction, Opcodes.ISTORE, 10)) stores++;
            if (packing > 0 && variable(instruction, Opcodes.ILOAD, 10)) loads++;
            if (call(instruction, "net/irisshaders/iris/vertices/NormalHelper", "computeTangent",
                    "(FFFLnet/irisshaders/iris/vertices/views/TriView;)I")
                    && instruction.getOpcode() == Opcodes.INVOKESTATIC && i + 1 < code.size()
                    && variable(code.get(i + 1), Opcodes.ISTORE, 10)) packing++;
            if (call(instruction, "org/lwjgl/system/MemoryUtil", "memPutInt", "(JI)V")
                    && i >= 4 && variable(code.get(i - 4), Opcodes.LLOAD, 1)
                    && constant(code.get(i - 3), 24L) && code.get(i - 2).getOpcode() == Opcodes.LADD) {
                if (instruction.getOpcode() != Opcodes.INVOKESTATIC || !variable(code.get(i - 1), Opcodes.ILOAD, 10)) return false;
                writes++;
            }
        }
        return writes == 1 && packing == 1 && stores == 1 && loads == 1;
    }

    static boolean matchesTangentFormat(ClassNode type) {
        if (!matchesFormat(type)) return false;
        List<AbstractInsnNode> code = instructions(method(type, "<clinit>", "()V"));
        int tangents = 0;
        for (int i = 0; i < code.size(); i++) {
            if (!(code.get(i) instanceof FieldInsnNode tangent) || !tangent.owner.equals(ATTRIBUTES)
                    || !tangent.name.equals("TANGENT")) continue;
            if (tangent.getOpcode() != Opcodes.GETSTATIC
                    || !tangent.desc.equals("Lme/jellysquid/mods/sodium/client/render/chunk/vertex/format/ChunkMeshAttribute;")
                    || i + 6 >= code.size() || !integer(code.get(i + 1), 24)
                    || !(code.get(i + 2) instanceof FieldInsnNode format) || format.getOpcode() != Opcodes.GETSTATIC
                    || !format.owner.equals("net/irisshaders/iris/compat/sodium/impl/vertex_format/IrisGlVertexAttributeFormat")
                    || !format.name.equals("BYTE") || !format.desc.equals("L" + ATTRIBUTE_FORMAT + ";")
                    || !integer(code.get(i + 3), 4) || !integer(code.get(i + 4), 1) || !integer(code.get(i + 5), 0)
                    || code.get(i + 6).getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call(code.get(i + 6), GL_BUILDER, "addElement",
                    "(Ljava/lang/Enum;IL" + ATTRIBUTE_FORMAT + ";IZZ)L" + GL_BUILDER + ";")) return false;
            tangents++;
        }
        return tangents == 1;
    }

    static boolean matchesEncoder(ClassNode type) {
        if (!InlaySubstrateVertexFormat.ENCODER.equals(type.name)) return false;
        MethodNode write = method(type, "write", InlaySubstrateVertexFormat.WRITE_DESCRIPTOR);
        if (write == null || (write.access & Opcodes.ACC_STATIC) != 0) return false;
        List<AbstractInsnNode> code = instructions(write);
        int normal = 0, pack = 0, stride = 0, returns = 0, writes = 0, vertexLoop = 0, increment = 0;
        for (int i = 0; i < code.size(); i++) {
            if (call(code.get(i), "org/lwjgl/system/MemoryUtil", "memPutInt", "(JI)V")) {
                writes++;
                if (i >= 4 && variable(code.get(i - 4), Opcodes.LLOAD, 1) && constant(code.get(i - 3), 28L)
                        && code.get(i - 2).getOpcode() == Opcodes.LADD
                        && variable(code.get(i - 1), Opcodes.ILOAD, 9)) normal++;
            }
            if (call(code.get(i), PACKING, "pack", "(Lorg/joml/Vector3f;)I")
                    && i >= 2 && i + 1 < code.size() && variable(code.get(i - 2), Opcodes.ALOAD, 0)
                    && code.get(i - 1) instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(type.name) && field.name.equals("normal") && field.desc.equals("Lorg/joml/Vector3f;")
                    && variable(code.get(i + 1), Opcodes.ISTORE, 9)) pack++;
            if (i + 3 < code.size() && variable(code.get(i), Opcodes.LLOAD, 1) && constant(code.get(i + 1), 40L)
                    && code.get(i + 2).getOpcode() == Opcodes.LADD && variable(code.get(i + 3), Opcodes.LSTORE, 1)) stride++;
            if (i >= 2 && i + 7 < code.size() && integer(code.get(i - 2), 0) && variable(code.get(i - 1), Opcodes.ISTORE, 11)
                    && variable(code.get(i), Opcodes.ILOAD, 11)
                    && integer(code.get(i + 1), 4) && code.get(i + 2).getOpcode() == Opcodes.IF_ICMPGE
                    && variable(code.get(i + 3), Opcodes.ALOAD, 4) && variable(code.get(i + 4), Opcodes.ILOAD, 11)
                    && code.get(i + 5).getOpcode() == Opcodes.AALOAD && variable(code.get(i + 6), Opcodes.ASTORE, 12)) vertexLoop++;
            if (code.get(i) instanceof IincInsnNode step && step.var == 11 && step.incr == 1) increment++;
            if (code.get(i).getOpcode() == Opcodes.LRETURN) {
                if (i == 0 || !variable(code.get(i - 1), Opcodes.LLOAD, 1)) return false;
                returns++;
            }
        }
        return normal == 1 && pack == 1 && writes == 9 && stride == 1 && returns == 1 && vertexLoop == 1 && increment == 1;
    }

    static boolean matchesFormat(ClassNode type) {
        if (!FORMAT.equals(type.name) || type.fields.stream().noneMatch(field -> field.name.equals("STRIDE")
                && field.desc.equals("I") && (field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)
                && Integer.valueOf(40).equals(field.value))) return false;
        MethodNode initializer = method(type, "<clinit>", "()V");
        if (initializer == null) return false;
        List<AbstractInsnNode> code = instructions(initializer);
        int normals = 0, stride = 0;
        for (int i = 0; i < code.size(); i++) {
            if (i + 1 < code.size() && integer(code.get(i), 40)
                    && call(code.get(i + 1), "me/jellysquid/mods/sodium/client/gl/attribute/GlVertexFormat", "builder",
                    "(Ljava/lang/Class;I)L" + GL_BUILDER + ";")) stride++;
            if (!(code.get(i) instanceof FieldInsnNode normal) || !normal.owner.equals(ATTRIBUTES) || !normal.name.equals("NORMAL")) continue;
            if (normal.getOpcode() != Opcodes.GETSTATIC
                    || !normal.desc.equals("Lme/jellysquid/mods/sodium/client/render/chunk/vertex/format/ChunkMeshAttribute;")
                    || i + 6 >= code.size() || !integer(code.get(i + 1), 28)
                    || !(code.get(i + 2) instanceof FieldInsnNode format) || format.getOpcode() != Opcodes.GETSTATIC
                    || !format.owner.equals("net/irisshaders/iris/compat/sodium/impl/vertex_format/IrisGlVertexAttributeFormat")
                    || !format.name.equals("BYTE") || !format.desc.equals("L" + ATTRIBUTE_FORMAT + ";")
                    || !integer(code.get(i + 3), 3) || !integer(code.get(i + 4), 1) || !integer(code.get(i + 5), 0)
                    || !call(code.get(i + 6), GL_BUILDER, "addElement",
                    "(Ljava/lang/Enum;IL" + ATTRIBUTE_FORMAT + ";IZZ)L" + GL_BUILDER + ";")) return false;
            normals++;
        }
        return normals == 1 && stride == 1;
    }

    static boolean matchesPacking(ClassNode type) {
        if (!PACKING.equals(type.name)) return false;
        MethodNode pack = method(type, "pack", "(FFFF)I");
        MethodNode vector = method(type, "pack", "(Lorg/joml/Vector3f;)I");
        if (pack == null || vector == null) return false;
        List<AbstractInsnNode> expected = new ArrayList<>();
        for (int component = 0; component < 4; component++) {
            expected.add(new VarInsnNode(Opcodes.FLOAD, component)); expected.add(new LdcInsnNode(127f));
            expected.add(new InsnNode(Opcodes.FMUL)); expected.add(new InsnNode(Opcodes.F2I));
            expected.add(new IntInsnNode(Opcodes.SIPUSH, 255)); expected.add(new InsnNode(Opcodes.IAND));
            if (component > 0) {
                expected.add(new IntInsnNode(Opcodes.BIPUSH, component * 8)); expected.add(new InsnNode(Opcodes.ISHL));
                expected.add(new InsnNode(Opcodes.IOR));
            }
        }
        expected.add(new InsnNode(Opcodes.IRETURN));
        if (!tokens(instructions(pack)).equals(tokens(expected))) return false;
        expected.clear();
        for (String component : List.of("x", "y", "z")) {
            expected.add(new VarInsnNode(Opcodes.ALOAD, 0));
            expected.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "org/joml/Vector3f", component, "()F", false));
        }
        expected.add(new InsnNode(Opcodes.FCONST_0));
        expected.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PACKING, "pack", "(FFFF)I", false));
        expected.add(new InsnNode(Opcodes.IRETURN));
        return tokens(instructions(vector)).equals(tokens(expected));
    }

    static boolean matchesTerrain(ClassNode type) {
        if (!TERRAIN.equals(type.name)) return false;
        MethodNode buffer = method(type, "bufferQuad", "(L" + QUAD + ";L" + MATERIAL + ";)V");
        if (buffer == null) return false;
        List<AbstractInsnNode> code = instructions(buffer);
        int orientation = 0, order = 0, push = 0, staging = 0;
        for (int i = 0; i < code.size(); i++) {
            if (call(code.get(i), QUAD, "orientation", "()L" + ORIENTATION + ";")
                    && i > 0 && i + 1 < code.size() && variable(code.get(i - 1), Opcodes.ALOAD, 1)
                    && variable(code.get(i + 1), Opcodes.ASTORE, 5)) orientation++;
            if (call(code.get(i), ORIENTATION, "getVertexIndex", "(I)I") && i >= 2 && i + 1 < code.size()
                    && variable(code.get(i - 2), Opcodes.ALOAD, 5) && variable(code.get(i - 1), Opcodes.ILOAD, 7)
                    && variable(code.get(i + 1), Opcodes.ISTORE, 8)) order++;
            if (code.get(i) instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD && field.owner.equals(TERRAIN)
                    && field.name.equals("vertices") && field.desc.equals("[L" + VERTEX + ";")
                    && i + 1 < code.size() && variable(code.get(i + 1), Opcodes.ASTORE, 6)) staging++;
            if (call(code.get(i), BUILDER, "push", PUSH)) {
                if (i < 3 || !variable(code.get(i - 3), Opcodes.ALOAD, 9)
                        || !variable(code.get(i - 2), Opcodes.ALOAD, 6) || !variable(code.get(i - 1), Opcodes.ALOAD, 2)) return false;
                push++;
            }
        }
        return orientation == 1 && order == 1 && staging == 1 && push == 1;
    }

    static boolean matchesPush(ClassNode type) {
        if (!BUILDER.equals(type.name)) return false;
        MethodNode push = method(type, "push", PUSH);
        if (push == null) return false;
        List<AbstractInsnNode> code = instructions(push);
        int writes = 0;
        for (int i = 0; i < code.size(); i++) if (call(code.get(i),
                "me/jellysquid/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder", "write",
                InlaySubstrateVertexFormat.WRITE_DESCRIPTOR)) {
            if (i < 4 || !variable(code.get(i - 4), Opcodes.ALOAD, 2) || !variable(code.get(i - 3), Opcodes.ALOAD, 1)
                    || !variable(code.get(i - 2), Opcodes.ALOAD, 0)
                    || !(code.get(i - 1) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD || !field.owner.equals(BUILDER)
                    || !field.name.equals("sectionIndex") || !field.desc.equals("I")
                    || i + 1 >= code.size() || code.get(i + 1).getOpcode() != Opcodes.POP2) return false;
            writes++;
        }
        return writes == 1;
    }

    private static MethodNode method(ClassNode type, String name, String descriptor) {
        var methods = type.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor)).toList();
        return methods.size() == 1 ? methods.get(0) : null;
    }

    private static List<AbstractInsnNode> instructions(MethodNode method) {
        List<AbstractInsnNode> code = new ArrayList<>();
        for (var instruction : method.instructions) if (instruction.getOpcode() >= 0) code.add(instruction);
        return code;
    }

    private static List<String> tokens(List<AbstractInsnNode> code) {
        return code.stream().map(instruction -> {
            String token = Integer.toString(instruction.getOpcode());
            if (instruction instanceof VarInsnNode variable) token += ":" + variable.var;
            if (instruction instanceof IntInsnNode integer) token += ":" + integer.operand;
            if (instruction instanceof LdcInsnNode constant) token += ":" + constant.cst;
            if (instruction instanceof MethodInsnNode call) token += ":" + call.owner + ":" + call.name + ":" + call.desc;
            return token;
        }).toList();
    }

    private static boolean variable(AbstractInsnNode instruction, int opcode, int slot) {
        return instruction instanceof VarInsnNode variable && variable.getOpcode() == opcode && variable.var == slot;
    }

    private static boolean constant(AbstractInsnNode instruction, Object expected) {
        return instruction instanceof LdcInsnNode constant && expected.equals(constant.cst);
    }

    private static boolean integer(AbstractInsnNode instruction, int value) {
        return instruction.getOpcode() == Opcodes.ICONST_0 + value && value >= -1 && value <= 5
                || instruction instanceof IntInsnNode integer && integer.operand == value;
    }

    private static boolean call(AbstractInsnNode instruction, String owner, String name, String descriptor) {
        return instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor);
    }
}
