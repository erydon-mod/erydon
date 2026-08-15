package com.oliver.erydon.block;

import com.oliver.erydon.ErydonConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

public final class LightPendantHaloBlock extends Block {
    public static final BooleanProperty LIT = Properties.LIT;

    public LightPendantHaloBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.getStateManager().getDefaultState().with(LIT, true));
    }

    public static int luminance(BlockState state) {
        return state.get(LIT) ? ErydonConfig.pendantLightLevel() : 0;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(LIT);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, net.minecraft.util.math.BlockPos pos, ShapeContext context) {
        return VoxelShapes.empty();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, net.minecraft.util.math.BlockPos pos, ShapeContext context) {
        return VoxelShapes.empty();
    }

    @Override
    public VoxelShape getRaycastShape(BlockState state, BlockView world, net.minecraft.util.math.BlockPos pos) {
        return VoxelShapes.empty();
    }

    @Override
    public boolean canReplace(BlockState state, ItemPlacementContext context) {
        return true;
    }
}
