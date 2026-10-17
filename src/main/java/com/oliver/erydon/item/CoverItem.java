package com.oliver.erydon.item;

import com.oliver.erydon.block.CorniceAssemblyBlock;
import com.oliver.erydon.block.CorniceAssembly;
import com.oliver.erydon.block.CorniceBlock;
import com.oliver.erydon.block.CoverBlock;
import net.minecraft.block.Block;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** A ceiling skin may share its existing cell with moulding. */
public final class CoverItem extends ErydonBlockItem {
    public CoverItem(Block block, Settings settings) { super(block, settings); }

    @Override public ActionResult useOnBlock(ItemUsageContext context) {
        var clicked = context.getWorld().getBlockState(context.getBlockPos());
        // The moulding can hide the ceiling from the ray. Clicking it still skins
        // the ceiling immediately above, without moving either occupied cell.
        if (clicked.getBlock() instanceof CorniceBlock || clicked.getBlock() instanceof CorniceAssemblyBlock) {
            var placement = new CorniceItem.Placement(context, new CorniceItem.Target(context.getBlockPos(), Direction.DOWN));
            var part = ((CoverBlock) getBlock()).getPlacementState(placement);
            if (part != null && part.canPlaceAt(context.getWorld(), context.getBlockPos())
                    && CorniceAssembly.canAdd(context.getWorld(), context.getBlockPos(), part)) {
                return CorniceAssemblyPlacement.add(placement, part);
            }
        }
        BlockPos adjacent = context.getBlockPos().offset(context.getSide());
        if (CorniceAssemblyPlacement.sharedHost(context.getWorld().getBlockState(adjacent))) {
            return place(new CorniceItem.Placement(context, new CorniceItem.Target(adjacent, context.getSide())));
        }
        return super.useOnBlock(context);
    }

    @Override public ActionResult place(ItemPlacementContext context) {
        if (CorniceAssemblyPlacement.sharedHost(context.getWorld().getBlockState(context.getBlockPos()))) {
            return CorniceAssemblyPlacement.add(context, ((CoverBlock) getBlock()).getPlacementState(context));
        }
        return super.place(context);
    }

    @Override public ItemPlacementContext getPlacementContext(ItemPlacementContext context) {
        return context instanceof CorniceItem.Placement ? context : super.getPlacementContext(context);
    }
}
