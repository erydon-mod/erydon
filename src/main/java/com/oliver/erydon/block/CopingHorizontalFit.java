package com.oliver.erydon.block;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;

/** Finite horizontal fits, measured from the rendered vertical-slope footprints. */
public enum CopingHorizontalFit {
    AXIS(.5, .5, 0, 1, 1),
    DIAGONAL(.25, .25, -Math.PI / 4, Math.sqrt(2), Math.sqrt(.5)),
    BROAD_RIGHT(.3, .4, -Math.atan2(1, .5), 3 / Math.sqrt(5), 2 / Math.sqrt(5)),
    BROAD_LEFT(.3, .6, Math.atan2(1, .5), 3 / Math.sqrt(5), 2 / Math.sqrt(5)),
    BROAD_WIDE_RIGHT(.1, .3, -Math.atan2(1, .5), 3 / Math.sqrt(5), 3 / Math.sqrt(5)),
    BROAD_WIDE_LEFT(.1, .7, Math.atan2(1, .5), 3 / Math.sqrt(5), 3 / Math.sqrt(5)),
    NARROW_RIGHT(-.15, .3, -Math.atan2(1, .5), 2.5 / Math.sqrt(5), 2 / Math.sqrt(5)),
    NARROW_LEFT(-.15, .7, Math.atan2(1, .5), 2.5 / Math.sqrt(5), 2 / Math.sqrt(5)),
    NARROW_THIN_RIGHT(.05, .4, -Math.atan2(1, .5), 2.5 / Math.sqrt(5), 1 / Math.sqrt(5)),
    NARROW_THIN_LEFT(.05, .6, Math.atan2(1, .5), 2.5 / Math.sqrt(5), 1 / Math.sqrt(5));

    public final double centreX, centreZ, angle, runLength, width;
    CopingHorizontalFit(double x, double z, double angle, double length, double width) {
        centreX=x; centreZ=z; this.angle=angle; runLength=length; this.width=width;
    }

    public record Pose(double centreX, double centreZ, double yawRadians, double runLength, double width) { }

    public Pose pose(Direction facing) {
        int turns=quarter(facing);
        double x=centreX, z=centreZ;
        for(int i=0;i<turns;i++) { double next=1-z; z=x; x=next; }
        return new Pose(x,z,angle+turns*Math.PI/2,runLength,width);
    }

    public static int quarter(Direction facing) {
        return switch(facing) { case SOUTH -> 1; case WEST -> 2; case NORTH -> 3; default -> 0; };
    }

    /** A shallow fit is chosen only from an actual opposite, parallel wall edge. */
    static CopingBlock.Support support(BlockState source,BlockView world,BlockPos pos) {
        if(source.getBlock() instanceof SlopeVerticalBlock)
            return new CopingBlock.Support(CopingBlock.Surface.DIAGONAL,
                    source.get(SlopeVerticalBlock.FACING).rotateYClockwise());
        boolean broad=source.getBlock() instanceof SlopeVerticalShallowBroadBlock;
        boolean narrow=source.getBlock() instanceof SlopeVerticalShallowNarrowBlock;
        if(!broad && !narrow) return null;
        Direction facing=broad ? source.get(SlopeVerticalShallowBroadBlock.FACING) : source.get(SlopeVerticalShallowNarrowBlock.FACING);
        boolean left=broad ? source.get(SlopeVerticalShallowBroadBlock.HAND)==SlopeVerticalShallowBroadBlock.Handedness.LEFT
                : source.get(SlopeVerticalShallowNarrowBlock.HAND)==SlopeVerticalShallowNarrowBlock.Handedness.LEFT;
        double best=shallowWidth(source,world,pos);
        if(!Double.isFinite(best)) return null;
        boolean reciprocal=false;
        for(int edge=0;edge<2;edge++) {
            BlockPos partner=partnerPos(pos,facing,left,edge);
            BlockState other=world.getBlockState(partner);
            if(shallow(other) && other.get(SlopeVerticalShallowBroadBlock.FACING)==facing.getOpposite()
                    && left(other)==left && Math.abs(shallowWidth(other,world,partner)-best)<.00001) reciprocal=true;
        }
        if(!reciprocal) return null;
        CopingBlock.Surface surface=broad
                ? best>1.25 ? (left ? CopingBlock.Surface.SHALLOW_BROAD_WIDE_LEFT : CopingBlock.Surface.SHALLOW_BROAD_WIDE_RIGHT)
                    : (left ? CopingBlock.Surface.SHALLOW_BROAD_LEFT : CopingBlock.Surface.SHALLOW_BROAD_RIGHT)
                : best<.75 ? (left ? CopingBlock.Surface.SHALLOW_NARROW_THIN_LEFT : CopingBlock.Surface.SHALLOW_NARROW_THIN_RIGHT)
                    : (left ? CopingBlock.Surface.SHALLOW_NARROW_LEFT : CopingBlock.Surface.SHALLOW_NARROW_RIGHT);
        return new CopingBlock.Support(surface,facing.rotateYClockwise());
    }

    private static boolean shallow(BlockState state) {
        return state.getBlock() instanceof SlopeVerticalShallowBroadBlock || state.getBlock() instanceof SlopeVerticalShallowNarrowBlock;
    }
    private static boolean left(BlockState state) {
        return state.getBlock() instanceof SlopeVerticalShallowBroadBlock
                ? state.get(SlopeVerticalShallowBroadBlock.HAND)==SlopeVerticalShallowBroadBlock.Handedness.LEFT
                : state.get(SlopeVerticalShallowNarrowBlock.HAND)==SlopeVerticalShallowNarrowBlock.Handedness.LEFT;
    }
    private static BlockPos partnerPos(BlockPos pos,Direction facing,boolean left,int edge) {
        int x=edge==0 ? -1 : 0,z=edge==1 ? (left ? 1 : -1) : 0;
        for(int turn=0;turn<quarter(facing.rotateYClockwise());turn++) { int next=-z; z=x; x=next; }
        return pos.add(x,0,z);
    }
    private static double shallowWidth(BlockState source,BlockView world,BlockPos pos) {
        boolean broad=source.getBlock() instanceof SlopeVerticalShallowBroadBlock;
        Direction facing=source.get(SlopeVerticalShallowBroadBlock.FACING);
        boolean left=left(source);
        // Conflicting parallel rails have no unique automatic wall width.
        double best=Double.POSITIVE_INFINITY;
        for(int edge=0;edge<2;edge++) {
            int x=edge==0 ? -1 : 0, z=edge==1 ? (left ? 1 : -1) : 0;
            for(int turn=0;turn<CopingHorizontalFit.quarter(facing.rotateYClockwise());turn++) {
                int next=-z; z=x; x=next;
            }
            BlockState other=world.getBlockState(pos.add(x,0,z));
            boolean otherBroad=other.getBlock() instanceof SlopeVerticalShallowBroadBlock;
            boolean otherNarrow=other.getBlock() instanceof SlopeVerticalShallowNarrowBlock;
            if(!otherBroad && !otherNarrow) continue;
            Direction otherFacing=otherBroad ? other.get(SlopeVerticalShallowBroadBlock.FACING) : other.get(SlopeVerticalShallowNarrowBlock.FACING);
            boolean otherLeft=otherBroad ? other.get(SlopeVerticalShallowBroadBlock.HAND)==SlopeVerticalShallowBroadBlock.Handedness.LEFT
                    : other.get(SlopeVerticalShallowNarrowBlock.HAND)==SlopeVerticalShallowNarrowBlock.Handedness.LEFT;
            if(otherFacing!=facing.getOpposite() || otherLeft!=left) continue;
            // Canonical RIGHT half-planes: broad x+.5z<=1, narrow<=.5.
            double outer=broad ? 1 : .5;
            double inner=1.5-(otherBroad ? 1 : .5)-(edge==0 ? 1 : .5);
            double otherMax=1.5-(edge==0 ? 1 : .5);
            if(inner>0.00001 || otherMax>outer+.00001) continue;
            double width=outer-inner;
            if(width>0.00001) {
                if(Double.isFinite(best) && Math.abs(best-width)>.00001) return Double.NaN;
                best=width;
            }
        }
        return best;
    }

    /** Reflect the translated fit, not merely the cardinal run direction. */
    public static Direction mirrorFacing(Direction facing,boolean leftRight,boolean handed) {
        if(handed) return leftRight
                ? switch(facing) { case EAST -> Direction.EAST; case SOUTH -> Direction.NORTH; case WEST -> Direction.WEST; default -> Direction.SOUTH; }
                : switch(facing) { case EAST -> Direction.WEST; case SOUTH -> Direction.SOUTH; case WEST -> Direction.EAST; default -> Direction.NORTH; };
        return leftRight
                ? switch(facing) { case EAST -> Direction.NORTH; case SOUTH -> Direction.WEST; case WEST -> Direction.SOUTH; default -> Direction.EAST; }
                : switch(facing) { case EAST -> Direction.SOUTH; case SOUTH -> Direction.EAST; case WEST -> Direction.NORTH; default -> Direction.WEST; };
    }
}
