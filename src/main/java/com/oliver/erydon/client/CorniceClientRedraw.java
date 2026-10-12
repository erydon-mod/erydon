package com.oliver.erydon.client;

import com.oliver.erydon.block.CorniceBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Block;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/** Same-state owner packets still invalidate geometry assembled from neighbouring walls and cornices. */
public final class CorniceClientRedraw {
    private CorniceClientRedraw() { }

    public static void afterPacket(BlockView world, BlockPos pos, BlockState packetState, Runnable redraw) {
        if (packetState.getBlock() instanceof CorniceBlock && world.getBlockState(pos) == packetState) redraw.run();
    }

    /** Run after the changed block data is applied, so packet ordering cannot leave peers rebuilt against an older scene. */
    public static void afterChange(World world, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!world.isClient || oldState == newState) return;
        int radius;
        if (oldState.getBlock() instanceof CorniceBlock || newState.getBlock() instanceof CorniceBlock) {
            radius = 4;
        } else {
            boolean affectsAngle = false;
            for (Direction side : Direction.Type.HORIZONTAL) {
                BlockState neighbour = world.getBlockState(pos.offset(side));
                if (neighbour.getBlock() instanceof CorniceBlock && neighbour.get(CorniceBlock.SHAPE).isAngled()) affectsAngle = true;
            }
            if (!affectsAngle) return;
            // The changed full wall/slope is one cell from its angled owner;
            // that owner's reciprocal ambiguity dependencies reach four more.
            radius = 5;
        }
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            BlockPos owner = pos.add(x, 0, z);
            BlockState state = world.getBlockState(owner);
            if (state.getBlock() instanceof CorniceBlock) world.updateListeners(owner, state, state, Block.NOTIFY_LISTENERS);
        }
    }
}
