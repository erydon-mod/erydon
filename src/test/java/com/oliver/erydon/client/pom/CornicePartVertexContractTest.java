package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.LdcInsnNode;

import static org.junit.jupiter.api.Assertions.*;

class CornicePartVertexContractTest {
    @Test void optionalShadowRequiresTheActualInstanceHolderField() throws Exception {
        var encoder = InlaySubstrateVertexFormat.read(InlaySubstrateVertexFormat.ENCODER);
        assertTrue(InlaySubstrateVertexFormat.matchesEncoder(encoder));
        assertTrue(encoder.fields.removeIf(field -> field.name.equals("contextHolder")));
        assertFalse(InlaySubstrateVertexFormat.matchesEncoder(encoder),
                "A matching write method must not enable a mixin whose mandatory shadow field is missing");
        encoder = InlaySubstrateVertexFormat.read(InlaySubstrateVertexFormat.ENCODER);
        encoder.fields.stream().filter(field -> field.name.equals("contextHolder")).findFirst().orElseThrow().desc = "Ljava/lang/Object;";
        assertFalse(InlaySubstrateVertexFormat.matchesEncoder(encoder), "A changed shadow descriptor must disable the optional hook");
    }

    @Test void optionalAccessorRequiresTheExactInstanceStateMap() throws Exception {
        var context = InlaySubstrateVertexFormat.read(InlaySubstrateVertexFormat.CONTEXT);
        assertTrue(InlaySubstrateVertexFormat.matchesContextStateMap(context));
        assertTrue(context.fields.removeIf(field -> field.name.equals("blockStateIds")));
        assertFalse(InlaySubstrateVertexFormat.matchesContextStateMap(context));
        context = InlaySubstrateVertexFormat.read(InlaySubstrateVertexFormat.CONTEXT);
        context.fields.stream().filter(field -> field.name.equals("blockStateIds")).findFirst().orElseThrow().desc = "Ljava/util/Map;";
        assertFalse(InlaySubstrateVertexFormat.matchesContextStateMap(context));
        context = InlaySubstrateVertexFormat.read(InlaySubstrateVertexFormat.CONTEXT);
        context.fields.stream().filter(field -> field.name.equals("blockStateIds")).findFirst().orElseThrow().access |= Opcodes.ACC_STATIC;
        assertFalse(InlaySubstrateVertexFormat.matchesContextStateMap(context));
    }

    @Test void startupGuardReadsBytecodeWithoutLoadingMinecraftOrOptionalRendererTypes() throws Exception {
        String helper = InlaySubstrateVertexFormat.class.getName();
        ClassLoader isolated = new ClassLoader(InlaySubstrateVertexFormat.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.equals(helper)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) {
                            try (var stream = getResourceAsStream(name.replace('.', '/') + ".class")) {
                                if (stream == null) throw new ClassNotFoundException(name);
                                byte[] bytes = stream.readAllBytes();
                                loaded = defineClass(name, bytes, 0, bytes.length);
                            } catch (java.io.IOException exception) { throw new ClassNotFoundException(name, exception); }
                        }
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                    if (name.startsWith("net.minecraft.") || name.startsWith("net.fabricmc.")
                            || name.startsWith("net.irisshaders.") || name.startsWith("link.infra.indium.")
                            || name.startsWith("me.jellysquid.") || name.startsWith("it.unimi.dsi.fastutil.")) {
                        throw new ClassNotFoundException("Startup guard attempted to load " + name);
                    }
                    return super.loadClass(name, resolve);
                }
            }
        };
        Class<?> earlyGuard = Class.forName(helper, true, isolated);
        assertNotSame(InlaySubstrateVertexFormat.class, earlyGuard);
        assertEquals(Boolean.TRUE, earlyGuard.getMethod("supported").invoke(null),
                "The exact live renderer contract should be checkable with Minecraft and renderer classes unavailable");
    }

    @Test void partLuminanceWritesRequireTheExactPinnedByteOffset() throws Exception {
        var encoder = InlaySubstrateVertexFormat.read(InlaySubstrateVertexFormat.ENCODER);
        assertTrue(InlaySubstrateVertexFormat.matchesEncoder(encoder));
        int changed = 0;
        for (var method : encoder.methods) if (method.name.equals("write")) {
            for (var instruction : method.instructions) {
                if (instruction instanceof LdcInsnNode value && Long.valueOf(39).equals(value.cst)) {
                    value.cst = 38L;
                    changed++;
                }
            }
        }
        assertEquals(1, changed);
        assertFalse(InlaySubstrateVertexFormat.matchesEncoder(encoder),
                "An encoder with a moved luminance byte must disable shared-cell part transport");
    }
}
