package com.oliver.erydon.block;

import com.oliver.erydon.client.model.SynapheiaCubeConnectionsLaunchChecks;
import com.oliver.erydon.client.particle.BlockBreakParticlesLaunchChecks;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;

/** Coordinated source-only family proof; no window, world save or packaged mod is needed. */
public final class BuildingControlsLaunchProbe implements PreLaunchEntrypoint {
    @Override public void onPreLaunch() {
        try {
            if (!FabricLoader.getInstance().isModLoaded("daedalon"))
                throw new AssertionError("This combined probe must load the current companion source outputs");
            SharedConstants.createGameVersion();
            Bootstrap.initialize();
            int failures = check("windows", WindowFrenchGeorgianLaunchProbe::run)
                    + check("overlays", SynapheiaCubeConnectionsLaunchChecks::run)
                    + check("erydonParticles", BlockBreakParticlesLaunchChecks::run)
                    + check("pendantLights", PendantLightingLaunchChecks::run)
                    + check("daedalonParticles", () -> Class.forName(
                            "com.oliver.daedalon.client.particle.BlockBreakParticlesLaunchChecks")
                            .getMethod("run").invoke(null));
            if (failures != 0) throw new AssertionError(failures + " independent building checks failed");
            System.out.println("ERYDON_BUILDING_CONTROLS_OK bothFamilyMixins=loaded");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    @FunctionalInterface private interface Check { void run() throws Exception; }
    private static int check(String name, Check check) {
        try { check.run(); return 0; }
        catch (Throwable failure) {
            System.err.println("ERYDON_BUILDING_CHECK_FAILED " + name);
            failure.printStackTrace();
            return 1;
        }
    }
}
