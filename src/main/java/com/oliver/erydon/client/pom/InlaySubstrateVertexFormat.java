package com.oliver.erydon.client.pom;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Fails closed if the pinned Iris encoder's byte layout or synchronous Indium sink changes. */
final class InlaySubstrateVertexFormat {
    static final String ENCODER = "net/irisshaders/iris/compat/sodium/impl/vertex_format/terrain_xhfp/XHFPTerrainVertex";
    static final String CONTEXT = "net/irisshaders/iris/compat/sodium/impl/block_context/BlockContextHolder";
    static final String WRITE_DESCRIPTOR = "(JLme/jellysquid/mods/sodium/client/render/chunk/terrain/material/Material;"
            + "[Lme/jellysquid/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)J";

    private InlaySubstrateVertexFormat() { }

    static boolean supported() {
        try {
            return matchesEncoder(read(ENCODER)) && signedRenderType(read(
                    "net/irisshaders/iris/compat/sodium/impl/vertex_format/terrain_xhfp/XHFPModelVertexType"))
                    && immediateEmitter(read(
                    "link/infra/indium/renderer/render/AbstractBlockRenderContext$1"));
        } catch (IOException | RuntimeException incompatible) {
            return false;
        }
    }

    static ClassNode read(String name) throws IOException {
        try (var input = InlaySubstrateVertexFormat.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (input == null) throw new IOException("Missing renderer class " + name);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    static boolean matchesEncoder(ClassNode type) {
        if (!ENCODER.equals(type.name)) return false;
        var methods = type.methods.stream().filter(m -> m.name.equals("write") && m.desc.equals(WRITE_DESCRIPTOR)).toList();
        if (methods.size() != 1) return false;
        List<AbstractInsnNode> code = instructions(methods.get(0));
        int shorts = 0, renderType = 0, blockId = 0, stride = 0, returns = 0;
        for (int i = 0; i < code.size(); i++) {
            AbstractInsnNode instruction = code.get(i);
            if (instruction.getOpcode() == Opcodes.LRETURN) returns++;
            if (instruction instanceof MethodInsnNode call && call.owner.equals("org/lwjgl/system/MemoryUtil")
                    && call.name.equals("memPutShort") && call.desc.equals("(JS)V")) {
                shorts++;
                if (matchesShort(code, i, 34L, "renderType")) renderType++;
                if (matchesShort(code, i, 32L, "blockId")) blockId++;
            }
            if (instruction instanceof LdcInsnNode constant && Long.valueOf(40).equals(constant.cst)
                    && i > 0 && variable(code.get(i - 1), Opcodes.LLOAD, 1)
                    && i + 2 < code.size() && code.get(i + 1).getOpcode() == Opcodes.LADD
                    && variable(code.get(i + 2), Opcodes.LSTORE, 1)) stride++;
        }
        return shorts == 2 && renderType == 1 && blockId == 1 && stride == 1 && returns == 1;
    }

    private static boolean matchesShort(List<AbstractInsnNode> code, int call, long offset, String field) {
        if (call < 6) return false;
        return variable(code.get(call - 6), Opcodes.LLOAD, 1)
                && code.get(call - 5) instanceof LdcInsnNode constant && Long.valueOf(offset).equals(constant.cst)
                && code.get(call - 4).getOpcode() == Opcodes.LADD
                && variable(code.get(call - 3), Opcodes.ALOAD, 0)
                && code.get(call - 2) instanceof FieldInsnNode holder && holder.getOpcode() == Opcodes.GETFIELD
                && holder.owner.equals(ENCODER) && holder.name.equals("contextHolder")
                && code.get(call - 1) instanceof FieldInsnNode value && value.getOpcode() == Opcodes.GETFIELD
                && value.owner.equals(CONTEXT) && value.name.equals(field) && value.desc.equals("S");
    }

    static boolean immediateEmitter(ClassNode type) {
        var methods = type.methods.stream().filter(m -> m.name.equals("emitDirectly") && m.desc.equals("()V")).toList();
        if (methods.size() != 1) return false;
        List<AbstractInsnNode> code = instructions(methods.get(0));
        return code.size() == 6 && code.get(4) instanceof MethodInsnNode call
                && call.owner.equals("link/infra/indium/renderer/render/AbstractBlockRenderContext")
                && call.name.equals("renderQuad")
                && call.desc.equals("(Llink/infra/indium/renderer/mesh/MutableQuadViewImpl;Z)V")
                && code.get(5).getOpcode() == Opcodes.RETURN;
    }

    static boolean signedRenderType(ClassNode type) {
        var initializer = type.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst();
        if (initializer.isEmpty()) return false;
        List<AbstractInsnNode> code = instructions(initializer.get());
        for (int i = 0; i + 6 < code.size(); i++) {
            if (code.get(i) instanceof FieldInsnNode attribute && attribute.name.equals("BLOCK_ID")
                    && attribute.owner.equals("net/irisshaders/iris/compat/sodium/impl/vertex_format/IrisChunkMeshAttributes")) {
                return code.get(i + 1) instanceof IntInsnNode offset && offset.operand == 32
                        && code.get(i + 2) instanceof FieldInsnNode format && format.name.equals("SHORT")
                        && format.owner.equals("net/irisshaders/iris/compat/sodium/impl/vertex_format/IrisGlVertexAttributeFormat")
                        && code.get(i + 3).getOpcode() == Opcodes.ICONST_2
                        && code.get(i + 4).getOpcode() == Opcodes.ICONST_0
                        && code.get(i + 5).getOpcode() == Opcodes.ICONST_0
                        && code.get(i + 6) instanceof MethodInsnNode add && add.name.equals("addElement");
            }
        }
        return false;
    }

    private static boolean variable(AbstractInsnNode node, int opcode, int index) {
        return node instanceof VarInsnNode variable && variable.getOpcode() == opcode && variable.var == index;
    }

    private static List<AbstractInsnNode> instructions(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (var instruction : method.instructions) if (instruction.getOpcode() >= 0) result.add(instruction);
        return result;
    }
}
