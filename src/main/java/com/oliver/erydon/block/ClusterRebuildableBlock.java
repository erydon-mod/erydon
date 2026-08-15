package com.oliver.erydon.block;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Set;

/**
 * Exposes an explicit, admin-triggered cluster rebuild hook.
 */
public interface ClusterRebuildableBlock {

    ClusterRecalcResult recalcCluster(World world, BlockPos seed);

    enum RecalcStatus {
        NONE,
        RECALCULATED,
        MANUAL_LOCKED,
        UNLOADED_EDGE,
        TOO_LARGE
    }

    record ClusterRecalcResult(Set<BlockPos> positions, RecalcStatus status) {
        public ClusterRecalcResult(Set<BlockPos> positions, boolean recalculated) {
            this(positions, recalculated ? RecalcStatus.RECALCULATED : RecalcStatus.MANUAL_LOCKED);
        }

        public static ClusterRecalcResult none() {
            return new ClusterRecalcResult(Set.of(), RecalcStatus.NONE);
        }

        public boolean recalculated() {
            return status == RecalcStatus.RECALCULATED;
        }
    }
}
