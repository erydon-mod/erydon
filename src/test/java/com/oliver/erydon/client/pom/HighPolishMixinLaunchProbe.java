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
            System.out.println("ERYDON_HIGH_POLISH_MIXIN_PROBE_OK: resources installed; ID preflight precedes base programs; parsed map reused; metal preflight precedes every source read; metal sampler profile gate executed; multiface placement hook applied.");
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
}
