package com.oliver.erydon.client.model;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;

/** Checks real block states/render layers, exiting before a window or world is opened. */
public final class SlopeNeighbourOcclusionLaunchProbe implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        try {
            SharedConstants.createGameVersion();
            Bootstrap.initialize();
            SlopeNeighbourOcclusionTest.runNeighbourChecks();
            System.out.println("ERYDON_SLOPE_OCCLUSION_OK");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
