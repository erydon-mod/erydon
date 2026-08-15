package com.oliver.erydon.command;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

interface ShowcasePlacementPlan {
    int width();

    int height();

    int depth();

    int displayCount();

    void place(ServerWorld world, BlockPos outerMin);
}
