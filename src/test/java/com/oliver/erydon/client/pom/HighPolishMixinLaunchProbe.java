package com.oliver.erydon.client.pom;

import com.oliver.erydon.mixin.client.compat.iris.HighPolishIdMapAccessor;
import com.oliver.erydon.ErydonConfig;
import com.oliver.erydon.HighPolishSettings;
import com.oliver.erydon.block.CoverBlock;
import com.oliver.erydon.block.CeilingBlock;
import com.oliver.erydon.client.ErydonHighPolish;
import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.shaderpack.include.IncludeGraph;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import net.minecraft.resource.LifecycledResourceManagerImpl;
import net.minecraft.resource.ResourceType;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.irisshaders.iris.compat.sodium.impl.block_context.BlockContextHolder;
import net.irisshaders.iris.compat.sodium.impl.vertex_format.terrain_xhfp.XHFPTerrainVertex;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Test-only Fabric entry point: applies real mixins, then exits before opening Minecraft. */
public final class HighPolishMixinLaunchProbe implements PreLaunchEntrypoint {
    @Override public void onPreLaunch() {
        try {
            verifyCoverClassification();
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
            System.out.println("ERYDON_HIGH_POLISH_MIXIN_PROBE_OK: cover Matte/Gloss classification and lit vertex transport verified; resources installed; ID preflight precedes base programs; parsed map reused; metal preflight precedes every source read; metal sampler profile gate executed; multiface placement hook applied; ordinary and diagonal-ribbon substrate shorts written by real Iris encoder with all other bytes preserved.");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void verifyCoverClassification() throws Exception {
        // Capture the same restart-bound settings as production, with every optional control off.
        // Replace only the in-memory snapshot: never write or load a player's config.
        var settingsField = ErydonConfig.class.getDeclaredField("clientSettings");
        settingsField.setAccessible(true);
        var previous = (ErydonConfig.ClientSnapshot) settingsField.get(null);
        settingsField.set(null, new ErydonConfig.ClientSnapshot(previous.tooltipsEnabled(), previous.tooltipDelayMs(),
                new HighPolishSettings(false, false, false, Map.of())));
        try {
            require(!ErydonHighPolish.activeSettings().enabled() && !ErydonHighPolish.activeSettings().glazing()
                            && !ErydonHighPolish.activeSettings().twoWay(), "Probe must capture all optional polish controls off");
            SharedConstants.createGameVersion();
            Bootstrap.initialize();
            var covers = new ArrayList<CoverBlock>();
            for (String finish : List.of("white", "black", "bronze", "silver")) {
                covers.add(registerProbeBlock("erydon", "cover_" + finish,
                        new CoverBlock(AbstractBlock.Settings.copy(Blocks.WHITE_CONCRETE).nonOpaque()
                                .luminance(CoverBlock::luminance))));
            }
            var foreignCover = registerProbeBlock("cover_probe", "cover_silver",
                    new CoverBlock(AbstractBlock.Settings.copy(Blocks.WHITE_CONCRETE).nonOpaque()
                            .luminance(CoverBlock::luminance)));
            var misleadingName = registerProbeBlock("erydon", "cover_probe_foreign",
                    new Block(AbstractBlock.Settings.copy(Blocks.WHITE_CONCRETE)));
            var ceilings = new ArrayList<CeilingBlock>();
            for (String style : List.of("georgian", "modern", "byzantine")) {
                for (String stone : List.of("", "glacium_")) {
                    for (String inset : List.of("white", "black")) {
                        ceilings.add(registerProbeBlock("erydon", stone + "ceiling_coffered_" + style + "_" + inset + "_small",
                        new CeilingBlock(AbstractBlock.Settings.copy(Blocks.WHITE_CONCRETE).nonOpaque()
                                .luminance(CeilingBlock::luminance))));
                    }
                }
            }
            var states = new ArrayList<BlockState>();
            for (var cover : covers) states.addAll(cover.getStateManager().getStates());
            states.addAll(foreignCover.getStateManager().getStates());
            states.addAll(misleadingName.getStateManager().getStates());
            for (var ceiling : ceilings) states.addAll(ceiling.getStateManager().getStates());
            states.add(Blocks.GLASS.getDefaultState());
            states.add(Blocks.STONE.getDefaultState());
            Class<?> mapping = Class.forName("net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping");
            Method handler = Arrays.stream(mapping.getDeclaredMethods())
                    .filter(method -> method.getName().contains("assignOpaquePolish")).findFirst().orElseThrow();
            require(Modifier.isStatic(handler.getModifiers()), "Actual woven material-map handler must be static");
            handler.setAccessible(true);
            var baseline = new Object2IntOpenHashMap<BlockState>();
            baseline.defaultReturnValue(-1);
            for (int i = 0; i < states.size(); i++) baseline.put(states.get(i), 700 + i % 37);

            prepareCoverShader(HighPolishShaderAdapter.Profile.COMPLEMENTARY, -1);
            require(HighPolishShaderAdapter.ready(), "Supported source fixtures must complete real shader preparation");
            var classified = new Object2IntOpenHashMap<BlockState>(baseline);
            invokeMaterialHandler(handler, classified);
            int gloss = 0, litGloss = 0, matte = 0, litMatte = 0;
            for (BlockState state : states) {
                var id = Registries.BLOCK.getId(state.getBlock());
                boolean cover = covers.contains(state.getBlock());
                boolean coverGloss = cover
                        && state.get(CoverBlock.FINISH) == CoverBlock.CoverFinish.GLOSS;
                int expected = coverGloss ? (id.getPath().equals("cover_silver")
                        ? 12061 : 12059) : cover ? 12063 : baseline.getInt(state);
                // The ceiling inset is selected by its Gloss sprite, not by replacing the
                // entire block's material. The stone frame retains its existing Honed ID
                // with master polish off; lit states retain the shader's original ID.
                if (state.getBlock() instanceof CeilingBlock && id.getPath().startsWith("glacium_")
                        && state.getLuminance() == 0) expected = 12033;
                require(classified.getInt(state) == expected, "Wrong actual shader classification: " + state);
                if (cover) {
                    if (coverGloss) gloss++; else matte++;
                    int light = switch (((net.minecraft.util.StringIdentifiable)
                            state.getEntries().get(CoverBlock.LIGHT)).asString()) {
                        case "off" -> 0;
                        case "low" -> 13;
                        case "bright" -> 15;
                        default -> throw new AssertionError("Unexpected Cover light state: " + state);
                    };
                    require(state.getLuminance() == light && CoverBlock.luminance(state) == light,
                            "Classification changed Cover light level: " + state);
                    require((expected & 1) == 1, "Paper-thin covers must retain partial-block voxel lighting");
                    if (light > 0) {
                        if (coverGloss) litGloss++; else litMatte++;
                    }
                }
                if (state.getBlock() instanceof CeilingBlock) {
                    require(classified.getInt(state) != 12059 && classified.getInt(state) != 12061
                                    && classified.getInt(state) != 12063,
                            "Gloss ceiling inset must not overwrite its stone frame's material ID");
                    require(state.getLuminance() == CeilingBlock.luminance(state),
                            "Classification changed ceiling luminance: " + state);
                }
            }
            require(gloss == 4608 && litGloss == 3072 && matte == 4608 && litMatte == 3072,
                    "Every attachment/size/extension/light/water Cover combination must be checked");
            for (int material : List.of(12059, 12061, 12063)) {
                for (int light : List.of(0, 13, 15)) verifyCoverLightTransport(material, light);
            }
            for (var profile : HighPolishShaderAdapter.Profile.values()) {
                if (profile == HighPolishShaderAdapter.Profile.COMPLEMENTARY) continue;
                prepareCoverShader(profile, -1);
                var skipped = new Object2IntOpenHashMap<BlockState>(baseline);
                invokeMaterialHandler(handler, skipped);
                require(skipped.equals(baseline), "Unsupported shader changed cover material states: " + profile);
            }
            for (int material : List.of(12059, 12061, 12063)) {
                prepareCoverShader(HighPolishShaderAdapter.Profile.COMPLEMENTARY, material);
                var skipped = new Object2IntOpenHashMap<BlockState>(baseline);
                invokeMaterialHandler(handler, skipped);
                require(skipped.equals(baseline), "Preflight collision changed existing material states: " + material);
                prepareCoverShader(HighPolishShaderAdapter.Profile.COMPLEMENTARY, -1);
                skipped.put(Blocks.STONE.getDefaultState(), material);
                var collisionBaseline = new Object2IntOpenHashMap<BlockState>(skipped);
                invokeMaterialHandler(handler, skipped);
                require(skipped.equals(collisionBaseline), "Mapping collision must leave the entire map unchanged");
            }
            HighPolishShaderAdapter.beginShaderLoad(HighPolishShaderAdapter.Profile.COMPLEMENTARY, true);
            var incomplete = new Object2IntOpenHashMap<BlockState>(baseline);
            invokeMaterialHandler(handler, incomplete);
            require(incomplete.equals(baseline), "Incomplete shader preparation must preserve existing materials");
            for (String program : List.of("gbuffers_terrain", "deferred1", "gbuffers_water")) {
                prepareCoverShader(HighPolishShaderAdapter.Profile.COMPLEMENTARY, -1);
                HighPolishShaderAdapter.adaptFragment(program, "missing required shader anchors");
                require(!HighPolishShaderAdapter.ready(), "Missing required sources must fail closed: " + program);
                var skipped = new Object2IntOpenHashMap<BlockState>(baseline);
                invokeMaterialHandler(handler, skipped);
                require(skipped.equals(baseline), "Missing shader sources changed existing materials: " + program);
            }
            System.out.println("ERYDON_COVER_GLOSS_CLASSIFICATION_OK gloss=" + gloss + " lit=" + litGloss
                    + " matte=" + matte + " litMatte=" + litMatte
                    + " states=" + states.size() + " optionalSettings=off nativeLightEncodings=9");
        } finally {
            settingsField.set(null, previous);
            HighPolishShaderAdapter.beginShaderLoad(HighPolishShaderAdapter.Profile.UNSUPPORTED, false);
        }
    }

    private static <T extends Block> T registerProbeBlock(String namespace, String path, T block) {
        Identifier id = new Identifier(namespace, path);
        require(!Registries.BLOCK.containsId(id), "Probe unexpectedly overlaps a pre-existing block: " + id);
        return Registry.register(Registries.BLOCK, id, block);
    }

    private static void invokeMaterialHandler(Method handler, Object2IntMap<BlockState> map) throws Exception {
        handler.invoke(null, new CallbackInfoReturnable<>("createBlockStateIdMap", false, map));
    }

    private static void prepareCoverShader(HighPolishShaderAdapter.Profile profile, int collision) {
        HighPolishShaderAdapter.beginShaderLoad(profile, true, false);
        HighPolishShaderAdapter.acceptMaterialIds(id -> id == collision);
        HighPolishShaderAdapter.adaptFragment("gbuffers_terrain", """
                normalMap = ReadNormal(vTexCoord.st);
                normalM = normalMap.rgb;
                vec4 specularMap = texture2D(specular, texCoordM);
                emission = GetCustomEmission(specularMap, texCoordM);
                float smoothnessM = pow2(specularMap.r);
                if (specularMap.g < OSIEBCA * 229.1) {
                    materialMask = specularMap.g * OSIEBCA * 214.0;
                } else {
                    materialMask = specularMap.g - OSIEBCA * 15.0;
                }
                """);
        HighPolishShaderAdapter.adaptFragment("deferred1", "fresnelM = fresnelM * sqrt1(smoothnessD) - dither * 0.01;");
        HighPolishShaderAdapter.adaptFragment("gbuffers_water",
                "emission = GetCustomEmission(specularMap, texCoordM);\nreflectMult = smoothnessD;");
    }

    private static void verifyCoverLightTransport(int materialId, int lightLevel) {
        var writer = new XHFPTerrainVertex();
        var context = new BlockContextHolder();
        context.blockId = 701;
        context.renderType = 0;
        context.lightValue = (byte) lightLevel;
        writer.iris$setContextHolder(context);
        var vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();
        for (int i = 0; i < vertices.length; i++) {
            vertices[i].x = i >= 2 ? 1 : 0;
            vertices[i].y = .5F;
            vertices[i].z = i == 1 || i == 2 ? 1 : 0;
            vertices[i].u = .25F;
            vertices[i].v = .5F;
            vertices[i].color = 0xffd0c0b0;
            vertices[i].light = 0x00f00000 | lightLevel << 4;
        }
        var material = new Material(null, AlphaCutoffParameter.HALF, true);
        long memory = MemoryUtil.nmemAlloc(160);
        require(memory != 0, "Cover light probe allocation failed");
        try {
            writer.write(memory, material, vertices, 3);
            byte[] baseline = new byte[160];
            for (int i = 0; i < baseline.length; i++) baseline[i] = MemoryUtil.memGetByte(memory + i);
            context.blockId = (short) materialId;
            writer.write(memory, material, vertices, 3);
            for (int vertex = 0; vertex < 4; vertex++) {
                require(MemoryUtil.memGetShort(memory + vertex * 40L + 32) == materialId,
                        "Real Iris encoder lost the Cover material ID");
                require(MemoryUtil.memGetByte(memory + vertex * 40L + 39) == lightLevel,
                        "Real Iris encoder lost Cover's luminance byte");
            }
            boolean materialChanged = false;
            for (int i = 0; i < baseline.length; i++) {
                if (i % 40 == 32 || i % 40 == 33) {
                    materialChanged |= MemoryUtil.memGetByte(memory + i) != baseline[i];
                } else {
                    require(MemoryUtil.memGetByte(memory + i) == baseline[i],
                            "Cover material changed light/geometry vertex byte " + i + " for light=" + lightLevel);
                }
            }
            require(materialChanged && context.lightValue == lightLevel,
                    "Cover classification must change only its material ID and retain native luminance transport");
        } finally {
            MemoryUtil.nmemFree(memory);
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
            var ribbonEmitter = (net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter) java.lang.reflect.Proxy.newProxyInstance(
                    HighPolishMixinLaunchProbe.class.getClassLoader(),
                    new Class[]{net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter.class}, (proxy, method, arguments) -> {
                        require(method.getName().equals("emit"), "Ribbon transport unexpectedly touched emitter state");
                        require(InlaySubstrateTransport.currentRecord() == 17 && InlaySubstrateTransport.currentRibbon(),
                                "Ribbon emission lost its phase or projection flag");
                        writer.write(start, material, vertices, 3);
                        return proxy;
                    });
            InlaySubstrateTransport.emitRibbon(ribbonEmitter, 17);
            for (int vertex = 0; vertex < 4; vertex++) require(MemoryUtil.memGetShort(start + vertex * 40L + 34)
                            == InlaySubstrateTransport.encodedRenderType(17, true),
                    "Actual Iris encoder did not retain the ribbon flag and phase");
            for (int i = 0; i < 160; i++) {
                if (i % 40 != 34 && i % 40 != 35) require(MemoryUtil.memGetByte(start + i) == baseline[i],
                        "Ribbon transport changed unrelated vertex byte " + i);
            }
            require(InlaySubstrateTransport.currentRecord() == -1 && !InlaySubstrateTransport.currentRibbon(),
                    "Ribbon metadata leaked out of its synchronous emission");
            previous = InlaySubstrateTransport.pushRecord(43);
            try {
                InlaySubstrateTransport.emitRibbon(ribbonEmitter, 17);
                require(InlaySubstrateTransport.currentRecord() == 43 && !InlaySubstrateTransport.currentRibbon(),
                        "Nested ribbon did not restore its ordinary substrate payload");
                var failingEmitter = (net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter) java.lang.reflect.Proxy.newProxyInstance(
                        HighPolishMixinLaunchProbe.class.getClassLoader(),
                        new Class[]{net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter.class}, (proxy, method, arguments) -> {
                            throw new IllegalStateException("Expected ribbon emission failure");
                        });
                try {
                    InlaySubstrateTransport.emitRibbon(failingEmitter, 17);
                    throw new AssertionError("The emitter exception was swallowed");
                } catch (IllegalStateException expected) {
                    require(InlaySubstrateTransport.currentRecord() == 43 && !InlaySubstrateTransport.currentRibbon(),
                            "Failed ribbon emission did not restore its caller's payload");
                }
            } finally {
                InlaySubstrateTransport.restoreRecord(previous);
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
