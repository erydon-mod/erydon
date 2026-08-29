package com.oliver.erydon.client.model;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;

/** Render-call-local lazy cache for the 3x3x3 neighbourhood around one block. */
final class SynapheiaNeighbourCache {
    private final BlockRenderView view;
    private final StateGetter stateGetter;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final BlockPos.Mutable cursor = new BlockPos.Mutable();
    private final BlockState[] states = new BlockState[27];
    private int loadedMask;

    SynapheiaNeighbourCache(BlockRenderView view, BlockPos origin) {
        this.view = view;
        this.stateGetter = null;
        this.originX = origin.getX();
        this.originY = origin.getY();
        this.originZ = origin.getZ();
    }

    SynapheiaNeighbourCache(StateGetter stateGetter, BlockPos origin) {
        this.view = null;
        this.stateGetter = stateGetter;
        this.originX = origin.getX();
        this.originY = origin.getY();
        this.originZ = origin.getZ();
    }

    BlockState get(int dx, int dy, int dz) {
        int index = index(dx, dy, dz);
        int bit = 1 << index;
        if ((loadedMask & bit) == 0) {
            cursor.set(originX + dx, originY + dy, originZ + dz);
            states[index] = stateGetter == null
                    ? view.getBlockState(cursor) : stateGetter.getBlockState(cursor);
            loadedMask |= bit;
        }
        return states[index];
    }

    private static int index(int dx, int dy, int dz) {
        if (dx < -1 || dx > 1 || dy < -1 || dy > 1 || dz < -1 || dz > 1) {
            throw new IllegalArgumentException("Synapheia neighbour offset outside 3x3x3: "
                    + dx + "," + dy + "," + dz);
        }
        return (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1);
    }

    @FunctionalInterface
    interface StateGetter {
        BlockState getBlockState(BlockPos pos);
    }
}
