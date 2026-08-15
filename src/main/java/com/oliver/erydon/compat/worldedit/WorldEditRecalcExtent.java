package com.oliver.erydon.compat.worldedit;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.util.ClusterRecalcSupport;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.RunContext;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class WorldEditRecalcExtent extends AbstractDelegateExtent {

    private static final int RECALC_MARGIN = 1;

    private final ServerWorld world;
    private final Map<Long, DirtyBox> dirtyBoxes = new HashMap<>();

    WorldEditRecalcExtent(Extent extent, ServerWorld world) {
        super(extent);
        this.world = world;
    }

    @Override
    public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 location, T block) throws WorldEditException {
        BlockState oldState = getBlock(location);
        if (!super.setBlock(location, block)) {
            return false;
        }

        if (shouldTrack(location, oldState, block)) {
            markDirty(location.getX(), location.getY(), location.getZ());
        }
        return true;
    }

    @Override
    protected Operation commitBefore() {
        if (dirtyBoxes.isEmpty()) {
            return null;
        }

        List<ClusterRecalcSupport.Box> scanBoxes = new ArrayList<>(dirtyBoxes.size());
        for (DirtyBox dirtyBox : dirtyBoxes.values()) {
            scanBoxes.add(dirtyBox.toScanBox().expand(RECALC_MARGIN));
        }
        dirtyBoxes.clear();

        return new Operation() {
            private boolean complete;

            @Override
            public Operation resume(RunContext run) {
                if (!complete) {
                    ClusterRecalcSupport.scanBoxes(world, scanBoxes);
                    complete = true;
                }
                return null;
            }

            @Override
            public void cancel() {
            }
        };
    }

    private boolean shouldTrack(BlockVector3 location, BlockState oldState, BlockStateHolder<?> newState) {
        if (isErydonBlock(oldState.getBlockType().getId()) || isErydonBlock(newState.getBlockType().getId())) {
            return true;
        }

        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (Direction direction : Direction.values()) {
            mutable.set(
                    location.getX() + direction.getOffsetX(),
                    location.getY() + direction.getOffsetY(),
                    location.getZ() + direction.getOffsetZ()
            );
            if (world.isOutOfHeightLimit(mutable)) {
                continue;
            }
            if (isErydonBlock(mutable)) {
                return true;
            }
        }

        return false;
    }

    private boolean isErydonBlock(BlockPos pos) {
        return Erydon.MOD_ID.equals(Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).getNamespace());
    }

    private static boolean isErydonBlock(String blockId) {
        return blockId.startsWith(Erydon.MOD_ID + ":");
    }

    private void markDirty(int x, int y, int z) {
        long chunkKey = ChunkPos.toLong(x >> 4, z >> 4);
        dirtyBoxes.computeIfAbsent(chunkKey, ignored -> new DirtyBox()).include(x, y, z);
    }

    private static final class DirtyBox {
        private int minX = Integer.MAX_VALUE;
        private int minY = Integer.MAX_VALUE;
        private int minZ = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE;
        private int maxY = Integer.MIN_VALUE;
        private int maxZ = Integer.MIN_VALUE;

        private void include(int x, int y, int z) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        private ClusterRecalcSupport.Box toScanBox() {
            return new ClusterRecalcSupport.Box(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }
}
