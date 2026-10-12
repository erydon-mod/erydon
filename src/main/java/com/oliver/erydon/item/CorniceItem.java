package com.oliver.erydon.item;

import com.oliver.erydon.block.CorniceSlopePlacement;
import net.minecraft.block.Block;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;

/** The moulding occupies a free cell in front of the angled face, never the ceiling. */
public final class CorniceItem extends ErydonBlockItem {
    public CorniceItem(Block block, Settings settings) { super(block, settings); }

    public record Target(BlockPos pos, Direction outward) { }

    public static Target target(BlockView world, BlockPos support, Direction clickedSide) {
        var state = world.getBlockState(support);
        if (CorniceSlopePlacement.shapeFor(state, clickedSide) == null) return null;
        BlockPos preferred = support.offset(clickedSide);
        if (world.getBlockState(preferred).isReplaceable()) return new Target(preferred, clickedSide);
        for (Direction side : Direction.Type.HORIZONTAL) {
            if (side != clickedSide && CorniceSlopePlacement.shapeFor(state, side) != null
                    && world.getBlockState(support.offset(side)).isReplaceable())
                return new Target(support.offset(side), side);
        }
        return null;
    }

    public static final class Placement extends ItemPlacementContext {
        private final Target target;
        public Placement(ItemUsageContext original, Target target) {
            super(original);
            this.target = target;
        }
        @Override public BlockPos getBlockPos() { return target == null ? super.getBlockPos() : target.pos(); }
        @Override public Direction getSide() { return target == null ? super.getSide() : target.outward(); }
    }

    @Override public ActionResult useOnBlock(ItemUsageContext context) {
        var source = context.getWorld().getBlockState(context.getBlockPos());
        if (CorniceSlopePlacement.shapeFor(source, context.getSide()) == null) return super.useOnBlock(context);
        Target target = target(context.getWorld(), context.getBlockPos(), context.getSide());
        return target == null ? ActionResult.FAIL : place(new Placement(context, target));
    }

    @Override public ItemPlacementContext getPlacementContext(ItemPlacementContext context) {
        return context instanceof Placement ? context : super.getPlacementContext(context);
    }
}
