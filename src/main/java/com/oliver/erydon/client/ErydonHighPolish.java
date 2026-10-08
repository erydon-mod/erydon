package com.oliver.erydon.client;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.ErydonConfig;
import com.oliver.erydon.HighPolishSettings;
import com.oliver.erydon.block.AlcoveBlock;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.text.Text;

/** Restart-bound preference: no settings polling, world scanning or extra render passes. */
public final class ErydonHighPolish {
    private static final HighPolishSettings ACTIVE = ErydonConfig.clientSettings().highPolish();

    private ErydonHighPolish() { }

    public static boolean usesHighPolish(String namespace, String path) {
        return PolishedStoneMaterials.enabled(ACTIVE, namespace, path);
    }

    public static HighPolishSettings activeSettings() { return ACTIVE; }

    public static boolean twoWayEnabled() { return ACTIVE.twoWayEnabled(); }

    public static void registerGlazingResources() {
        if (!ACTIVE.glazingEnabled()) return;
        // Only registered for this launch; the shader adapter adjusts reflections separately.
        boolean registered = ResourceManagerHelper.registerBuiltinResourcePack(
                new Identifier(Erydon.MOD_ID, "high_polish_glazing"),
                FabricLoader.getInstance().getModContainer(Erydon.MOD_ID).orElseThrow(),
                Text.translatable("resourcepack.erydon.high_polish_glazing"),
                ResourcePackActivationType.ALWAYS_ENABLED);
        if (!registered) throw new IllegalStateException("Missing built-in glazing specular resources");
    }

    public static void registerLayers() {
        int matched = 0;
        for (var block : Registries.BLOCK) {
            Identifier id = Registries.BLOCK.getId(block);
            // Raw-authoring alcoves are stone too. Their JSON version does not
            // justify transparent terrain, even when the polish option is on.
            if (block instanceof AlcoveBlock && Erydon.MOD_ID.equals(id.getNamespace())) {
                BlockRenderLayerMap.INSTANCE.putBlock(block, RenderLayer.getSolid());
            }
            if (!PolishedStoneMaterials.includes(id.getNamespace(), id.getPath())) continue;
            matched++;
        }
        Erydon.LOGGER.info("[{}] High polish {} using opaque stone ({} eligible blocks; glazing {}; two-way {}; changes require restart)",
                Erydon.MOD_ID, ACTIVE.enabled() ? "on" : "off", matched,
                ACTIVE.glazingEnabled() ? "on" : "off",
                ACTIVE.twoWayEnabled() ? "on" : "off");
    }
}
