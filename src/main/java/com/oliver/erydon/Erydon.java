package com.oliver.erydon;

import com.oliver.erydon.compat.FamilyReleaseCompatibility;
import com.oliver.erydon.compat.worldedit.WorldEditCompat;
import com.oliver.erydon.command.ErydonRecalcCommand;
import com.oliver.erydon.block.entity.ModBlockEntities;
import com.oliver.erydon.item.ModItemGroups;
import com.oliver.erydon.migration.ErydonIdMigration;
import com.oliver.erydon.network.ErydonConfigNetworking;
import com.oliver.erydon.util.ErydonLightUpdateQueue;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.oliver.erydon.item.ModItems;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class Erydon implements ModInitializer {

    public static final String MOD_ID = "erydon";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final String STARTUP_TEXT_LOGO = loadStartupTextLogo();

    @Override
    public void onInitialize() {
        FamilyReleaseCompatibility.enforce(MOD_ID);
        LOGGER.info("[{}] Initialising…", MOD_ID);

        ModBlocks.registerModBlocks();
        ErydonIdMigration.registerAliases();
        ModBlockEntities.register();
        ModItems.register();
        ModItemGroups.register();
        ErydonLoot.init();
        ErydonConfigNetworking.registerServer();
        ErydonLightUpdateQueue.register();
        ErydonRecalcCommand.register();
        WorldEditCompat.init();

    }

    static void logStartupTextLogo() {
        if (STARTUP_TEXT_LOGO.isBlank()) {
            return;
        }

        for (String line : STARTUP_TEXT_LOGO.split("\\R", -1)) {
            LOGGER.info(line);
        }
    }

    private static String loadStartupTextLogo() {
        try (InputStream input = Erydon.class.getResourceAsStream("/erydon_text_logo.txt")) {
            if (input == null) {
                LOGGER.warn("[{}] Startup text logo resource missing.", MOD_ID);
                return "";
            }

            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LOGGER.warn("[{}] Failed to load startup text logo.", MOD_ID, exception);
            return "";
        }
    }
}
