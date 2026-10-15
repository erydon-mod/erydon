package com.oliver.erydon.migration;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;

/** Standalone lightweight REI search proof without unrelated geometry or a game window. */
public final class ReiSearchLaunchProbe implements PreLaunchEntrypoint {
    @Override public void onPreLaunch() {
        try {
            SharedConstants.createGameVersion();
            Bootstrap.initialize();
            if (Boolean.getBoolean("erydon.test.reiAbsent")) {
                if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("roughlyenoughitems")
                        || new com.oliver.erydon.mixin.ErydonMixinPlugin().shouldApplyMixin(
                                "me.shedaniel.rei.impl.client.search.argument.Argument",
                                "com.oliver.erydon.mixin.client.compat.rei.ReiFullBlockArgumentMixin")) {
                    throw new AssertionError("Optional REI search mixin was enabled without REI");
                }
                System.out.println("ERYDON_REI_OPTIONAL_ABSENCE_OK mixin=skipped bootstrap=passed");
            } else {
                ReiSearchLaunchChecks.run();
            }
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
