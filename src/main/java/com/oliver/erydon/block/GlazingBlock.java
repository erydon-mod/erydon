package com.oliver.erydon.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.Direction;

@SuppressWarnings("deprecation")
public class GlazingBlock extends Block {
    public GlazingBlock(Settings settings) {
        super(settings);
    }

    @Override
    public boolean isSideInvisible(BlockState state, BlockState neighborState, Direction direction) {
        if (neighborState.getBlock() instanceof GlazingBlock) {
            return true;
        }
        return super.isSideInvisible(state, neighborState, direction);
    }
}
