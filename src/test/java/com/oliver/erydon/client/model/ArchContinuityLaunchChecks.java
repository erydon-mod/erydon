package com.oliver.erydon.client.model;

import com.oliver.erydon.block.ArchRomanesqueBlock;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.block.BlockModels;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.lang.reflect.Proxy;
import java.util.Map;

/** Exercises Continuity's real reload map and foreign CTM wrappers, without a graphics window. */
public final class ArchContinuityLaunchChecks {
    private ArchContinuityLaunchChecks() { }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void run() throws Exception {
        if (!FabricLoader.getInstance().isModLoaded("continuity")) return;
        Class<?> type = Class.forName("me.pepperbell.continuity.client.resource.ModelWrappingHandler");
        // Emissive models require MinecraftClient's block colors during static
        // initialization, so their rendering remains a live-client check.
        Object handler = type.getMethod("create", boolean.class, boolean.class).invoke(null, true, false);
        var field = type.getDeclaredField("blockStateModelIds");
        field.setAccessible(true);
        Map<?, ?> ids = (Map<?, ?>) field.get(handler);
        int arches = 0, foreignStates = 0;
        for (Block block : Registries.BLOCK) {
            if (block instanceof ArchRomanesqueBlock) {
                require(ids.get(BlockModels.getModelId(block.getDefaultState())) == block.getDefaultState(),
                        "Continuity lost the shared arch model: " + Registries.BLOCK.getId(block));
                arches++;
            } else {
                for (BlockState state : block.getStateManager().getStates()) {
                    require(ids.get(BlockModels.getModelId(state)) == state,
                            "Continuity changed a foreign state: " + state);
                    foreignStates++;
                }
            }
        }
        require(arches > 0 && ids.size() == arches + foreignStates, "Continuity model map grew with arch states");
        BakedModel base = (BakedModel) Proxy.newProxyInstance(BakedModel.class.getClassLoader(),
                new Class<?>[]{BakedModel.class}, (proxy, method, args) -> {
                    if (method.getReturnType() == boolean.class) return false;
                    throw new AssertionError("Unexpected model query: " + method.getName());
                });
        var wrap = type.getMethod("wrap", BakedModel.class, Identifier.class);
        Object foreign = wrap.invoke(handler, base, BlockModels.getModelId(Blocks.STONE.getDefaultState()));
        require(foreign.getClass().getName().endsWith(".CtmBakedModel"), "Foreign CTM wrapping was disabled");
        Object inventory = wrap.invoke(handler, base, new ModelIdentifier(Registries.BLOCK.getId(Blocks.STONE), "inventory"));
        require(inventory == base,
                "Inventory wrapping acquired a block-state CTM processor");
        System.out.println("ERYDON_ARCH_CONTINUITY_OK arches=" + arches + " foreignStates=" + foreignStates
                + " modelEntries=" + ids.size() + " foreignCtm=true");
    }
}
