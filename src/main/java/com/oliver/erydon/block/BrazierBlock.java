package com.oliver.erydon.block;

import com.oliver.erydon.ErydonConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

public final class BrazierBlock extends Block {

    private static final VoxelShape BASE_SHAPE = Block.createCuboidShape(0.5, 0.0, 0.5, 15.5, 6.0, 15.5);
    private static final VoxelShape OUTLINE_SHAPE = VoxelShapes.union(
            BASE_SHAPE,
            Block.createCuboidShape(0.5, 3.5, 0.5, 15.5, 18.5, 15.5)
    );

    public BrazierBlock(Settings settings) {
        super(settings);
    }

    public static int luminance(BlockState state) {
        return ErydonConfig.brazierLightLevel();
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return OUTLINE_SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return BASE_SHAPE;
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }
}
