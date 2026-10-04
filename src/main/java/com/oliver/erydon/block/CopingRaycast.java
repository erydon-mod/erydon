package com.oliver.erydon.block;

import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;

/** Pitched caps occupy part of the supporting cell; raycast their owning cell too. */
public final class CopingRaycast {
    private CopingRaycast() { }
    public static BlockHitResult closest(BlockView world, Vec3d start, Vec3d end, BlockPos cell,
                                          BlockState support, BlockHitResult original) {
        BlockHitResult neighbourHit=original;
        // All finite touching fit pairs stay within two owner cells, including
        // sharp mixed-width corners. Skip this lookup above the thin cap slab.
        if(touchesCapSlab(start,end,cell)) for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++) if(x!=0 || z!=0) {
            BlockPos owner=cell.add(x,0,z); BlockState state=world.getBlockState(owner);
            if(state.getBlock() instanceof CopingBlock && (state.get(CopingBlock.SURFACE).aligned()
                    || state.get(CopingBlock.SURFACE)==CopingBlock.Surface.FLAT))
                neighbourHit=candidate(world,start,end,cell,owner,state,neighbourHit);
        }
        if (!(support.getBlock() instanceof SlopeBlock || support.getBlock() instanceof ShallowSlopeBlock
                || support.getBlock() instanceof SlopeSteepBlock || support.getBlock() instanceof SlopeVerticalBlock
                || support.getBlock() instanceof SlopeVerticalShallowBroadBlock || support.getBlock() instanceof SlopeVerticalShallowNarrowBlock)) return neighbourHit;
        BlockHitResult best = candidate(world,start,end,cell,cell.up(),neighbourHit);
        if(support.getBlock() instanceof SlopeVerticalBlock || support.getBlock() instanceof SlopeVerticalShallowBroadBlock
                || support.getBlock() instanceof SlopeVerticalShallowNarrowBlock) {
            for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++) if(x!=0 || z!=0)
                best=candidate(world,start,end,cell,cell.up().add(x,0,z),best);
        }
        if (support.getBlock() instanceof SlopeSteepBlock) {
            for (Direction facing : Direction.Type.HORIZONTAL) for (int y=0;y<=1;y++) {
                best=candidate(world,start,end,cell,cell.up(y).offset(facing),best);
            }
        }
        return best;
    }
    private static BlockHitResult candidate(BlockView world,Vec3d start,Vec3d end,BlockPos cell,BlockPos owner,BlockHitResult best) {
        return candidate(world,start,end,cell,owner,world.getBlockState(owner),best);
    }
    private static BlockHitResult candidate(BlockView world,Vec3d start,Vec3d end,BlockPos cell,BlockPos owner,BlockState state,BlockHitResult best) {
        if (!(state.getBlock() instanceof CopingBlock)) return best;
        BlockPos source=CopingBlock.supportPos(state,owner);
        if (!source.equals(cell) && !(state.get(CopingBlock.OFFSET) && source.up().equals(cell))
                && !state.get(CopingBlock.SURFACE).aligned()) {
            if(state.get(CopingBlock.SURFACE)!=CopingBlock.Surface.FLAT) return best;
        }
        var hit=state.getOutlineShape(world,owner,ShapeContext.absent()).raycast(start,end,owner);
        if (hit!=null && (best==null || hit.getPos().squaredDistanceTo(start)<best.getPos().squaredDistanceTo(start))) return hit;
        return best;
    }
    private static boolean touchesCapSlab(Vec3d start,Vec3d end,BlockPos cell) {
        double entry=0,exit=1;
        for(int axis=0;axis<3;axis++) {
            double a=axis==0 ? start.x : axis==1 ? start.y : start.z;
            double d=(axis==0 ? end.x : axis==1 ? end.y : end.z)-a;
            double low=axis==0 ? cell.getX() : axis==1 ? cell.getY() : cell.getZ();
            if(Math.abs(d)<.0000001) { if(a<low || a>low+1) return false; continue; }
            double first=(low-a)/d,last=(low+1-a)/d;
            entry=Math.max(entry,Math.min(first,last)); exit=Math.min(exit,Math.max(first,last));
        }
        if(entry>exit) return false;
        double y1=start.y+(end.y-start.y)*entry,y2=start.y+(end.y-start.y)*exit;
        return Math.min(y1,y2)<=cell.getY()+CopingBlock.THICKNESS+.00001 && Math.max(y1,y2)>=cell.getY()-.00001;
    }
}
