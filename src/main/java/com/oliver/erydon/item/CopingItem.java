package com.oliver.erydon.item;

import com.oliver.erydon.block.ShallowSlopeBlock;
import com.oliver.erydon.block.SlopeBlock;
import com.oliver.erydon.block.SlopeSteepBlock;
import com.oliver.erydon.block.SlopeVerticalBlock;
import com.oliver.erydon.block.SlopeVerticalShallowBroadBlock;
import com.oliver.erydon.block.SlopeVerticalShallowNarrowBlock;
import net.minecraft.block.Block;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.block.enums.BlockHalf;

/** A stepped steep face may raycast as a side; still cap the clicked slope. */
public final class CopingItem extends ErydonBlockItem {
    public CopingItem(Block block, Settings settings) { super(block, settings); }

    public record Target(BlockPos pos, boolean offset) { }
    public static Target target(BlockView world,BlockPos clicked) {
        var state=world.getBlockState(clicked);
        BlockPos target=clicked.up();
        if (world.getBlockState(target).isReplaceable()) return new Target(target,false);
        if (!(state.getBlock() instanceof SlopeSteepBlock steep) || steep.variant()!=SlopeSteepBlock.Variant.UPPER
                || state.get(SlopeSteepBlock.HALF)!=BlockHalf.BOTTOM) return null;
        Direction facing=state.get(SlopeSteepBlock.FACING);
        var obstruction=world.getBlockState(target);
        if (!(obstruction.getBlock() instanceof SlopeSteepBlock lower) || lower.variant()!=SlopeSteepBlock.Variant.LOWER
                || obstruction.get(SlopeSteepBlock.FACING)!=facing || obstruction.get(SlopeSteepBlock.HALF)!=BlockHalf.BOTTOM) return null;
        target=target.offset(facing);
        return world.getBlockState(target).isReplaceable() ? new Target(target,true) : null;
    }

    public static final class Placement extends ItemPlacementContext {
        public final BlockPos supportPos;
        public final boolean offset;
        private final BlockPos target;
        Placement(ItemUsageContext original, BlockPos supportPos, BlockPos target, boolean offset) {
            super(original); this.supportPos=supportPos; this.target=target; this.offset=offset;
        }
        @Override public BlockPos getBlockPos() { return target==null ? super.getBlockPos() : target; }
        @Override public Direction getSide() { return Direction.UP; }
    }

    @Override public ActionResult useOnBlock(ItemUsageContext original) {
        BlockPos clicked=original.getBlockPos();
        var state=original.getWorld().getBlockState(clicked);
        if (!(state.getBlock() instanceof SlopeBlock || state.getBlock() instanceof ShallowSlopeBlock
                || state.getBlock() instanceof SlopeSteepBlock || vertical(state.getBlock()))) return super.useOnBlock(original);
        Target target=target(original.getWorld(),clicked);
        return target==null ? ActionResult.FAIL : place(new Placement(original,clicked,target.pos(),target.offset()));
    }

    @Override public ItemPlacementContext getPlacementContext(ItemPlacementContext context) {
        if (context instanceof Placement) return context;
        BlockPos clicked = context.canReplaceExisting() ? context.getBlockPos()
                : context.getBlockPos().offset(context.getSide().getOpposite());
        Block block = context.getWorld().getBlockState(clicked).getBlock();
        if (block instanceof SlopeBlock || block instanceof ShallowSlopeBlock || block instanceof SlopeSteepBlock || vertical(block)) {
            return ItemPlacementContext.offset(context, clicked.up(), Direction.UP);
        }
        return context;
    }
    private static boolean vertical(Block block) {
        return block instanceof SlopeVerticalBlock || block instanceof SlopeVerticalShallowBroadBlock
                || block instanceof SlopeVerticalShallowNarrowBlock;
    }
}
