package com.oliver.erydon.compat.worldedit;

import com.oliver.erydon.Erydon;
import com.sk89q.worldedit.WorldEdit;
import net.fabricmc.loader.api.FabricLoader;

public final class WorldEditCompat {

    private static boolean initialized;

    private WorldEditCompat() {
    }

    public static void init() {
        if (initialized || !FabricLoader.getInstance().isModLoaded("worldedit")) {
            return;
        }

        try {
            WorldEdit.getInstance().getEventBus().register(new WorldEditCompatListener());
            initialized = true;
            Erydon.LOGGER.info("[{}] Registered WorldEdit compatibility hook.", Erydon.MOD_ID);
        } catch (LinkageError | RuntimeException ex) {
            Erydon.LOGGER.warn("[{}] Failed to register WorldEdit compatibility hook.", Erydon.MOD_ID, ex);
        }
    }
}
