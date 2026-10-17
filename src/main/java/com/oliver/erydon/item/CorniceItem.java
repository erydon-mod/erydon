package com.oliver.erydon.item;

import com.oliver.erydon.block.CorniceSlopePlacement;
import com.oliver.erydon.block.CorniceAssembly;
import com.oliver.erydon.block.CorniceBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;

/** Angled moulding shares front cells when necessary, never occupying the ceiling. */
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

    public static Target target(BlockView world, BlockPos support, Direction clickedSide, BlockState cornice) {
        var slope = world.getBlockState(support);
        if (CorniceSlopePlacement.shapeFor(slope, clickedSide) == null) return null;
        // Prefer the clicked side even when it already holds another attachment.
        // The physical supporting edge, rather than the owner cell, is unique.
        Direction[] candidates = {clickedSide, clickedSide.rotateYClockwise(),
                clickedSide.getOpposite(), clickedSide.rotateYCounterclockwise()};
        for (Direction outward : candidates) {
            var shape = CorniceSlopePlacement.shapeFor(slope, outward);
            if (shape == null) continue;
            BlockPos owner = support.offset(outward);
            BlockState part = cornice.with(CorniceBlock.FACING, CorniceSlopePlacement.facing(slope))
                    .with(CorniceBlock.SHAPE, shape);
            if (CorniceAssembly.canAdd(world, owner, part)) return new Target(owner, outward);
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
        if (CorniceSlopePlacement.shapeFor(source, context.getSide()) == null) {
            // A thin ceiling cover may be the first ray hit. Attach within its
            // cell using the player's preferred wall, just as ordinary placement.
            if (CorniceAssemblyPlacement.sharedHost(source)) {
                var placement = new Placement(context, new Target(context.getBlockPos(), context.getSide()));
                BlockState part = ((CorniceBlock) getBlock()).getPlacementState(placement);
                if (part != null && CorniceAssembly.canAdd(context.getWorld(), context.getBlockPos(), part)) {
                    return CorniceAssemblyPlacement.add(placement, part);
                }
            }
            BlockPos adjacent = context.getBlockPos().offset(context.getSide());
            if (CorniceAssemblyPlacement.sharedHost(context.getWorld().getBlockState(adjacent))) {
                return place(new Placement(context, new Target(adjacent, context.getSide())));
            }
            return super.useOnBlock(context);
        }
        Target target = target(context.getWorld(), context.getBlockPos(), context.getSide(), getBlock().getDefaultState());
        return target == null ? ActionResult.FAIL : place(new Placement(context, target));
    }

    @Override public ActionResult place(ItemPlacementContext context) {
        if (CorniceAssemblyPlacement.sharedHost(context.getWorld().getBlockState(context.getBlockPos()))) {
            return CorniceAssemblyPlacement.add(context, ((CorniceBlock) getBlock()).getPlacementState(context));
        }
        return super.place(context);
    }

    @Override public ItemPlacementContext getPlacementContext(ItemPlacementContext context) {
        return context instanceof Placement ? context : super.getPlacementContext(context);
    }
}
