package com.oliver.erydon.client.pom;

import com.oliver.erydon.mixin.client.compat.iris.HighPolishIdMapAccessor;
import com.google.common.collect.ImmutableList;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.shaderpack.include.IncludeGraph;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import net.minecraft.resource.LifecycledResourceManagerImpl;
import net.minecraft.resource.ResourceType;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.irisshaders.iris.compat.sodium.impl.block_context.BlockContextHolder;
import net.irisshaders.iris.compat.sodium.impl.vertex_format.terrain_xhfp.XHFPTerrainVertex;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Test-only Fabric entry point: applies real mixins, then exits before opening Minecraft. */
public final class HighPolishMixinLaunchProbe implements PreLaunchEntrypoint {
    @Override public void onPreLaunch() {
        try {
            Class<?> resources = Class.forName("net.minecraft.resource.LifecycledResourceManagerImpl");
            var handler = Arrays.stream(resources.getDeclaredMethods())
                    .filter(m -> m.getName().contains("stoneFinishLabels")).findFirst().orElseThrow();
            require(Modifier.isStatic(handler.getModifiers()), "Constructor HEAD handler must be static");
            try (var manager = new LifecycledResourceManagerImpl(ResourceType.CLIENT_RESOURCES, List.of())) {
                require(manager.streamResourcePacks().anyMatch(p -> p.getName().equals("erydon:stone_finish_labels")),
                        "Stone-finish labels were not installed");
            }

            // Exercise the package-private Iris constructor through the real Mixin factory.
            var emptyShader = Files.createDirectories(Path.of("empty-shader"));
            var ids = HighPolishIdMapAccessor.erydon$create(emptyShader, null, List.of());
            require(ids != null, "Iris ID-map constructor invoker failed");
            Class.forName("net.irisshaders.iris.shaderpack.ShaderPack");
            Class.forName("net.irisshaders.iris.shaderpack.programs.ProgramSet");
            Class.forName("net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping");
            Class<?> blockItem = Class.forName("net.minecraft.item.BlockItem", false, getClass().getClassLoader());
            require(Arrays.stream(blockItem.getDeclaredMethods())
                            .anyMatch(m -> m.getName().contains("preserveMultifacePlacement")),
                    "Multiface placement mixin did not apply to BlockItem");
            var transformed = new ClassNode();
            new ClassReader(Files.readAllBytes(Path.of(".mixin.out/class/net/irisshaders/iris/shaderpack/ShaderPack.class")))
                    .accept(transformed, 0);
            var constructor = transformed.methods.stream().filter(m -> m.name.equals("<init>")
                    && m.desc.contains("Ljava/util/Map;")).findFirst().orElseThrow();
            int check = -1, program = -1, reuse = -1, rawMapAllocations = 0;
            for (int i = 0; i < constructor.instructions.size(); i++) {
                var instruction = constructor.instructions.get(i);
                if (instruction instanceof MethodInsnNode call) {
                    if (call.name.contains("checkIdsBeforePrograms")) check = i;
                    if (call.name.contains("reuseCheckedIds")) reuse = i;
                }
                if (instruction instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW) {
                    if (allocation.desc.endsWith("/ProgramSet")) program = i;
                    if (allocation.desc.endsWith("/IdMap")) rawMapAllocations++;
                }
            }
            require(check >= 0 && check < program && program < reuse,
                    "Real Iris constructor must check IDs before base shaders, then reuse that map");
            require(rawMapAllocations == 0, "Iris must not parse the ID map a second time");
            verifyMetalPreflightOrdering();
            verifyMetalSamplerProfiles(emptyShader);
            verifyInlayVertexTransport();
            System.out.println("ERYDON_HIGH_POLISH_MIXIN_PROBE_OK: resources installed; ID preflight precedes base programs; parsed map reused; metal preflight precedes every source read; metal sampler profile gate executed; multiface placement hook applied; inlay substrate short written by real Iris encoder with all other bytes preserved.");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void verifyMetalPreflightOrdering() throws Exception {
        var transformed = new ClassNode();
        new ClassReader(Files.readAllBytes(Path.of(".mixin.out/class/net/irisshaders/iris/shaderpack/programs/ProgramSet.class")))
                .accept(transformed, 0);
        var constructor = transformed.methods.stream().filter(method -> method.name.equals("<init>"))
                .findFirst().orElseThrow();
        int initialized = -1, preflight = -1, firstRead = Integer.MAX_VALUE, directSource = -1;
        for (int i = 0; i < constructor.instructions.size(); i++) {
            var instruction = constructor.instructions.get(i);
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals("java/lang/Object") && call.name.equals("<init>")) initialized = i;
                if (call.name.contains("preflightMetalPrograms")) preflight = i;
                if (call.name.startsWith("read") && call.owner.equals(transformed.name)) firstRead = Math.min(firstRead, i);
                if (call.name.equals("readProgramSource")) directSource = i;
            }
        }
        require(initialized >= 0 && initialized < preflight && preflight < firstRead && preflight < directSource,
                "Real ProgramSet constructor must initialize this, then preflight before any shader source read");
        var handler = transformed.methods.stream().filter(method -> method.name.contains("preflightMetalPrograms"))
                .findFirst().orElseThrow();
        require((handler.access & Opcodes.ACC_STATIC) == 0, "Metal eligibility must belong to this ProgramSet");
        int transform = -1, accepted = -1;
        for (int i = 0; i < handler.instructions.size(); i++) {
            var instruction = handler.instructions.get(i);
            if (instruction instanceof MethodInsnNode call && call.owner.endsWith("/MetallicShaderAdapter")
                    && call.name.equals("adapt")) transform = i;
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.contains("metalEligible")) accepted = i;
        }
        require(transform >= 0 && accepted > transform,
                "Woven preflight must transform and validate all required sources before accepting metal eligibility");
    }

    private static void verifyMetalSamplerProfiles(Path emptyShader) throws Exception {
        // Pre-launch has not run Iris's initializer. Supply defaults in memory only: no load, save or graphics.
        var configField = Iris.class.getDeclaredField("irisConfig");
        configField.setAccessible(true);
        Object previousConfig = configField.get(null);
        try {
            configField.set(null, new IrisConfig(Path.of("unused-probe-iris.properties")));
            ShaderPackOptions options = new ShaderPackOptions(new IncludeGraph(emptyShader, ImmutableList.of()), Map.of());
            var handler = Arrays.stream(ShaderProperties.class.getDeclaredMethods())
                    .filter(method -> method.getName().contains("registerCuPomSamplerDirectly")).findFirst().orElseThrow();
            handler.setAccessible(true);
            for (HighPolishShaderAdapter.Profile profile : HighPolishShaderAdapter.Profile.values()) {
                // The real constructor establishes the unsupported/empty source baseline without graphics startup.
                ShaderProperties properties = new ShaderProperties("# empty probe shader\n", options, List.of());
                require(!properties.getIrisCustomTextures().containsKey("erydonMetalLookup"),
                        "Unrecognised shader properties must not add the metal sampler");
                HighPolishShaderAdapter.beginShaderLoad(profile, true);
                handler.invoke(properties, "# empty probe shader\n", options, List.of(), new CallbackInfo("<init>", false));
                require(properties.getIrisCustomTextures().containsKey("erydonMetalLookup")
                                == (profile == HighPolishShaderAdapter.Profile.COMPLEMENTARY),
                        "Metal sampler must be registered only for supported Complementary profiles: " + profile);
                require(!properties.getIrisCustomTextures().containsKey("erydonCtmPomLookup"),
                        "Metal material sampling must not enable the independent CTM-POM bridge");
            }
        } finally {
            configField.set(null, previousConfig);
            HighPolishShaderAdapter.beginShaderLoad(HighPolishShaderAdapter.Profile.UNSUPPORTED, false);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void verifyInlayVertexTransport() {
        InlaySubstrateTransport.setSourceSupported(false);
        InlaySubstrateTransport.restoreRecord(-1);
        require(InlaySubstrateTransport.rendererSupported(), "Pinned renderer format and synchronous emitter must pass substrate guard");
        require(Arrays.stream(XHFPTerrainVertex.class.getDeclaredMethods())
                        .anyMatch(method -> method.getName().contains("writeInlaySubstrate")),
                "Substrate transport mixin must be applied to the real Iris encoder");
        var writer = new XHFPTerrainVertex();
        var context = new BlockContextHolder();
        context.blockId = 12040;
        context.renderType = 0;
        context.lightValue = 9;
        writer.iris$setContextHolder(context);
        var vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();
        for (int i = 0; i < 4; i++) {
            vertices[i].x = i >= 2 ? 1 : 0;
            vertices[i].y = 0.5F;
            vertices[i].z = i == 1 || i == 2 ? 1 : 0;
            vertices[i].u = .25F + .125F * vertices[i].x;
            vertices[i].v = .5F + .0625F * vertices[i].z;
            vertices[i].color = 0xffd0c0b0;
            vertices[i].light = 0x00f000b0;
        }
        var material = new Material(null, AlphaCutoffParameter.HALF, true);
        long memory = MemoryUtil.nmemAlloc(4L * 40 + 32);
        require(memory != 0, "Vertex probe allocation failed");
        long start = memory + 16;
        try {
            MemoryUtil.memSet(memory, 0x5a, 4L * 40 + 32);
            require(writer.write(start, material, vertices, 3) == start + 160, "Iris vertex stride changed");
            byte[] baseline = new byte[160];
            for (int i = 0; i < baseline.length; i++) baseline[i] = MemoryUtil.memGetByte(start + i);
            ErydonCuPomRuntimeState.beginShaderLoad(true);
            ErydonCuPomRuntimeState.acceptProgramStatus("TRANSFORMED");
            ErydonCuPomRuntimeState.confirmTerrainProgramsCompiled();
            InlaySubstrateTransport.setSourceSupported(true);
            require(InlaySubstrateTransport.enabled(), "Substrate path must be active after successful preflight/link");
            int previous = InlaySubstrateTransport.pushRecord(17);
            try {
                writer.write(start, material, vertices, 3);
            } finally {
                InlaySubstrateTransport.restoreRecord(previous);
            }
            for (int vertex = 0; vertex < 4; vertex++) {
                require(MemoryUtil.memGetShort(start + vertex * 40L + 34) == -19,
                        "Actual Iris write did not carry substrate record 17");
            }
            for (int i = 0; i < 160; i++) {
                if (i % 40 != 34 && i % 40 != 35) require(MemoryUtil.memGetByte(start + i) == baseline[i],
                        "Substrate transport changed unrelated vertex byte " + i);
            }
            for (int i = 0; i < 16; i++) {
                require(MemoryUtil.memGetByte(memory + i) == 0x5a && MemoryUtil.memGetByte(start + 160 + i) == 0x5a,
                        "Substrate transport wrote outside the vertex allocation");
            }
            writer.write(start, material, vertices, 3);
            for (int vertex = 0; vertex < 4; vertex++) require(MemoryUtil.memGetShort(start + vertex * 40L + 34) == 0,
                    "Scoped substrate payload leaked into the next ordinary quad");
            InlaySubstrateTransport.setSourceSupported(false);
            previous = InlaySubstrateTransport.pushRecord(17);
            try { writer.write(start, material, vertices, 3); }
            finally { InlaySubstrateTransport.restoreRecord(previous); }
            require(MemoryUtil.memGetShort(start + 34) == 0, "Unsupported shader must retain the native vertex format values");
        } finally {
            InlaySubstrateTransport.setSourceSupported(false);
            InlaySubstrateTransport.restoreRecord(-1);
            ErydonCuPomRuntimeState.beginShaderLoad(false);
            MemoryUtil.nmemFree(memory);
        }
    }
}
