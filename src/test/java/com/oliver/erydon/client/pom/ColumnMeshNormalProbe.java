package com.oliver.erydon.client.pom;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;

/** Renderer-hook diagnosis that exits before Minecraft creates a window or world. */
public final class ColumnMeshNormalProbe implements PreLaunchEntrypoint {
    @Override public void onPreLaunch() {
        try {
            SharedConstants.createGameVersion();
            Bootstrap.initialize();
            ColumnMeshNormalLaunchChecks.run();
            System.out.println("ERYDON_COLUMN_NORMAL_PROBE_OK java=" + Runtime.version().feature());
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
