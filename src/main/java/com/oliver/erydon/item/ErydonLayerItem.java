package com.oliver.erydon.item;

import com.oliver.erydon.block.LayerBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

public class ErydonLayerItem extends ErydonBlockItem {
    public ErydonLayerItem(Block block, Item.Settings settings) {
        super(block, settings);
    }

    private void playPlaceSound(World world, BlockPos pos, PlayerEntity player, BlockState state) {
        BlockSoundGroup soundGroup = state.getSoundGroup();
        world.playSound(player, pos, soundGroup.getPlaceSound(), SoundCategory.BLOCKS,
                (soundGroup.getVolume() + 1.0F) / 2.0F,
                soundGroup.getPitch() * 0.8F);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        BlockPos clickedPos = context.getBlockPos();
        Direction face = context.getSide();
        BlockState clickedState = world.getBlockState(clickedPos);

        // Case 1: Clicking a layer block itself (top or bottom face) -> increment in place
        if (clickedState.getBlock() == this.getBlock() && clickedState.contains(LayerBlock.LAYERS)) {
            int layers = clickedState.get(LayerBlock.LAYERS);
            if (layers < 8 && (face == Direction.UP || face == Direction.DOWN)) {
                if (!world.isClient) {
                    BlockState newState = clickedState.with(LayerBlock.LAYERS, layers + 1);
                    world.setBlockState(clickedPos, newState, Block.NOTIFY_ALL);
                    playPlaceSound(world, clickedPos, context.getPlayer(), newState);
                    PlayerEntity player = context.getPlayer();
                    ItemStack stack = context.getStack();
                    if (player == null || !player.getAbilities().creativeMode) {
                        stack.decrement(1);
                    }
                }
                return ActionResult.SUCCESS;
            }
        }

        // Case 2: Clicking the underside of ANY block -> try to increment the adjacent layer below
        if (face == Direction.DOWN) {
            BlockPos belowPos = clickedPos.down();
            BlockState belowState = world.getBlockState(belowPos);
            if (belowState.getBlock() == this.getBlock() && belowState.contains(LayerBlock.LAYERS)) {
                int layers = belowState.get(LayerBlock.LAYERS);
                if (layers < 8) {
                    if (!world.isClient) {
                        BlockState newState = belowState.with(LayerBlock.LAYERS, layers + 1);
                        world.setBlockState(belowPos, newState, Block.NOTIFY_ALL);
                        playPlaceSound(world, belowPos, context.getPlayer(), newState);
                        PlayerEntity player = context.getPlayer();
                        ItemStack stack = context.getStack();
                        if (player == null || !player.getAbilities().creativeMode) {
                            stack.decrement(1);
                        }
                    }
                    return ActionResult.SUCCESS;
                }
            }
        }

        // Fallback to normal placement rules (this covers placing a new stack above/below)
        return super.useOnBlock(context);
    }
}
