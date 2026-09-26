package com.oliver.erydon.client.pom;

import com.oliver.erydon.mixin.client.compat.iris.HighPolishIdMapAccessor;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.resource.LifecycledResourceManagerImpl;
import net.minecraft.resource.ResourceType;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

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
            System.out.println("ERYDON_HIGH_POLISH_MIXIN_PROBE_OK: resources installed; ID preflight precedes base programs; parsed map reused; multiface placement hook applied.");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
