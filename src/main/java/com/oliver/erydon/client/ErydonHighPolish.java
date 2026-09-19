package com.oliver.erydon.client;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.ErydonConfig;
import com.oliver.erydon.block.AlcoveBlock;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/** Restart-bound preference: no settings polling, world scanning or extra render passes. */
public final class ErydonHighPolish {
    private static final boolean ENABLED = ErydonConfig.clientSettings().highPolishEnabled();

    private ErydonHighPolish() { }

    public static boolean usesHighPolish(String namespace, String path) {
        return ENABLED && PolishedStoneMaterials.includes(namespace, path);
    }

    public static void registerLayers() {
        int matched = 0;
        for (var block : Registries.BLOCK) {
            Identifier id = Registries.BLOCK.getId(block);
            if (!PolishedStoneMaterials.includes(id.getNamespace(), id.getPath())) continue;
            matched++;
            if (ENABLED) {
                BlockRenderLayerMap.INSTANCE.putBlock(block, RenderLayer.getTranslucent());
            } else if (block instanceof AlcoveBlock) {
                // Polished alcoves previously used this shader pass unconditionally.
                BlockRenderLayerMap.INSTANCE.putBlock(block, RenderLayer.getSolid());
            }
        }
        Erydon.LOGGER.info("[{}] High polish {} ({} eligible blocks; changes require restart)",
                Erydon.MOD_ID, ENABLED ? "on" : "off", matched);
    }
}
