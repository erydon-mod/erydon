package com.oliver.erydon.block;

import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

public class ShallowStairsTopBlock extends ShallowStairsBlockBase {

    public ShallowStairsTopBlock(BlockState baseBlockState, Settings settings) {
        super(baseBlockState, settings);
        setDefaultState(getDefaultState()
                .with(Properties.BLOCK_HALF, BlockHalf.TOP)
                .with(Properties.WATERLOGGED, false));
    }

    @Override
    protected boolean isTopHalf() { return true; }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getOutlineShape(state, world, pos, context);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockState state = super.getPlacementState(ctx);
        if (state != null) {
            return state.with(Properties.BLOCK_HALF, BlockHalf.TOP);
        }
        return state;
    }
}
