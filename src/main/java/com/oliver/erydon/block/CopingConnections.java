package com.oliver.erydon.block;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import java.util.ArrayList;
import java.util.List;

/** Native EAST-facing edges and the signed incline on the other side of each end. */
public final class CopingConnections {
    public static final int WEST=1,EAST=2,NORTH=4,SOUTH=8;
    // Native flat-parent side bevel metrics; raw-model tests check all four edges.
    public static final double SIDE_BEVEL_RUN_OVERHANG=.03125;
    public static final double SIDE_BEVEL_OUTER_OVERHANG=.0671261556037529;
    public static final double SIDE_BEVEL_INNER_REACH=.038041381118820716;
    private static final double[] GRADIENTS={0,0,.5,1,2,-.5,-1,-2};
    private static final int[] ENDS={-1,1};
    private CopingConnections() {}

    /** Three bits per end, plus the two crosswise joins. Zero means a free end. */
    public record HorizontalEnd(int axis,int sign,double halfLength,double halfWidth,List<CopingHorizontalMitre.Cut> cuts) {
        public HorizontalEnd { cuts=List.copyOf(cuts); }
        public double lateral(double x,double z) { return axis==0 ? z-.5 : .5-x; }
        public double run(double x,double z) { return (axis==0 ? x : z)-.5; }
        public double cut(double lateral,int branch) {
            int index=branch<0 ? 0 : branch>0 ? 2 : lateral < -halfWidth ? 0 : lateral > halfWidth ? 2 : 1;
            return cuts.get(index).at(lateral);
        }
        public int edge() { return axis==0 ? (sign<0 ? WEST : EAST) : (sign<0 ? NORTH : SOUTH); }
        public double distance(double x,double z,int branch) { return run(x,z)-cut(lateral(x,z),branch); }
        public double expansion() {
            double reach=halfLength;
            for(double lateral:new double[]{-halfWidth-.125,-halfWidth,0,halfWidth,halfWidth+.125})
                reach=Math.max(reach,sign*cut(lateral,0));
            return reach/halfLength;
        }
    }
    /** Named side bevels keep a fixed perimeter offset, including their inward lip. */
    public static int bevelBranch(int edge,HorizontalEnd end) {
        if(end.axis()==0) return (edge&NORTH)!=0 ? -1 : (edge&SOUTH)!=0 ? 1 : 0;
        return (edge&EAST)!=0 ? -1 : (edge&WEST)!=0 ? 1 : 0;
    }
    /** Extend joined run bounds before exact half-plane clipping; centres and cross-sections stay fixed. */
    public static CopingHorizontalMitre.Point expand(double x,double z,List<HorizontalEnd> ends) {
        double a=x,b=z;
        for(var end:ends) {
            double run=end.run(x,z);
            if(end.sign()*run>0) {
                double shift=run*(end.expansion()-1);
                if(end.axis()==0) a+=shift; else b+=shift;
            }
        }
        return new CopingHorizontalMitre.Point(a,b);
    }
    public record Joins(int key,double westCut,double eastCut,List<HorizontalEnd> horizontal) {
        public Joins { horizontal=List.copyOf(horizontal); }
        public Joins(int key) { this(key,0,1,List.of()); }
        public Joins(int key,double westCut,double eastCut) { this(key,westCut,eastCut,List.of()); }
        public int mask() { return ((key&7)!=0 ? WEST : 0) | ((key&56)!=0 ? EAST : 0)
                | ((key&64)!=0 ? NORTH : 0) | ((key&128)!=0 ? SOUTH : 0); }
        public double westGradient() { return GRADIENTS[key&7]; }
        public double eastGradient() { return GRADIENTS[(key>>3)&7]; }
        public static Joins samePlane(CopingBlock.Surface profile,int mask) {
            int code=code(profile.gradient());
            return new Joins(((mask&WEST)!=0 ? code : 0) | ((mask&EAST)!=0 ? code<<3 : 0)
                    | ((mask&NORTH)!=0 ? 64 : 0) | ((mask&SOUTH)!=0 ? 128 : 0));
        }
    }

    /** Flat caps are symmetric; orient their joins along a neighbouring ramp's run. */
    public static Direction facing(BlockView world,BlockState state,BlockPos pos) {
        Direction facing=state.get(CopingBlock.FACING);
        if(state.get(CopingBlock.SURFACE).aligned()) return facing;
        if (state.get(CopingBlock.SURFACE)!=CopingBlock.Surface.FLAT) return facing;
        for(Direction direction:Direction.Type.HORIZONTAL) for(int distance=1;distance<=2;distance++) for(int y=-1;y<=1;y++) {
            Direction axis=joiningFacing(world,pos,pos.offset(direction,distance).up(y));
            if (axis!=null) return axis;
        }
        for(int y=-1;y<=1;y+=2) {
            Direction axis=joiningFacing(world,pos,pos.up(y));
            if (axis!=null) return axis;
        }
        return facing;
    }

    private static Direction joiningFacing(BlockView world,BlockPos pos,BlockPos owner) {
        BlockState other=world.getBlockState(owner);
        if (!(other.getBlock() instanceof CopingBlock) || other.get(CopingBlock.SURFACE)==CopingBlock.Surface.FLAT) return null;
        if(other.get(CopingBlock.SURFACE).aligned()) return null;
        Direction axis=other.get(CopingBlock.FACING);
        int dx=owner.getX()-pos.getX()-(other.get(CopingBlock.OFFSET) ? axis.getOffsetX() : 0);
        int dz=owner.getZ()-pos.getZ()-(other.get(CopingBlock.OFFSET) ? axis.getOffsetZ() : 0);
        return joins(CopingBlock.Surface.FLAT,axis,other.get(CopingBlock.SURFACE),axis,dx,owner.getY()-pos.getY(),dz)!=0 ? axis : null;
    }

    public static int mask(BlockView world,BlockState state,BlockPos pos) { return resolve(world,state,pos).mask(); }

    /** Ordinary flat caps keep their cached outline unless a new horizontal fit is adjacent. */
    public static boolean hasAlignedNeighbour(BlockView world,BlockPos pos) {
        for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++) if(x!=0 || z!=0) {
            BlockState state=world.getBlockState(pos.add(x,0,z));
            if(state.getBlock() instanceof CopingBlock && state.get(CopingBlock.SURFACE).aligned()) return true;
        }
        return false;
    }

    public static Joins resolve(BlockView world,BlockState state,BlockPos pos) {
        return resolve(world,state,pos,facing(world,state,pos));
    }

    public static Joins resolve(BlockView world,BlockState state,BlockPos pos,Direction facing) {
        CopingBlock.Surface surface=state.get(CopingBlock.SURFACE);
        if(surface.aligned()) return horizontal(world,state,pos,facing,new Joins(0));
        BlockPos anchor=state.get(CopingBlock.OFFSET) ? pos.offset(facing.getOpposite()) : pos;
        int key=0;
        // Offset steep owners can be two cells away even when the physical ends meet.
        for(Direction direction:Direction.Type.HORIZONTAL) for(int distance=1;distance<=2;distance++) for(int y=-1;y<=1;y++)
            key=add(world,surface,facing,anchor,pos.offset(direction,distance).up(y),key);
        for(int y=-1;y<=1;y+=2) key=add(world,surface,facing,anchor,pos.up(y),key);
        Joins legacy=new Joins(key);
        return surface==CopingBlock.Surface.FLAT ? horizontal(world,state,pos,facing,legacy) : legacy;
    }

    private record Candidate(int axis,int sign,int dx,int dz,int targetAxis,int targetSign,
                             CopingHorizontalMitre.Mitre mitre,double score) { }
    private static final class Neighbours {
        final BlockView world; final BlockPos origin; final BlockState[] states=new BlockState[25]; final Direction[] facings=new Direction[25];
        Neighbours(BlockView world,BlockPos origin,BlockState source,Direction facing) {
            this.world=world; this.origin=origin; states[12]=source; facings[12]=facing;
        }
        BlockState get(int x,int z) {
            int index=(x+2)*5+z+2;
            if(states[index]==null) states[index]=world.getBlockState(origin.add(x,0,z));
            return states[index];
        }
        Direction facing(int x,int z) {
            int index=(x+2)*5+z+2;
            if(facings[index]==null) facings[index]=CopingConnections.facing(world,get(x,z),origin.add(x,0,z));
            return facings[index];
        }
    }
    private static Joins horizontal(BlockView world,BlockState state,BlockPos pos,Direction facing,Joins legacy) {
        List<HorizontalEnd> ends=new ArrayList<>(); int key=legacy.key();
        Neighbours neighbours=new Neighbours(world,pos,state,facing);
        for(int axis=0;axis<(state.get(CopingBlock.SURFACE).aligned() ? 1 : 2);axis++) for(int sign:ENDS) {
            int edge=axis==0 ? (sign<0 ? WEST : EAST) : (sign<0 ? NORTH : SOUTH);
            if((legacy.mask()&edge)!=0) continue;
            Candidate best=best(neighbours,state,0,0,facing,axis,sign);
            if(best==null) continue;
            BlockState target=neighbours.get(best.dx,best.dz);
            Direction targetFacing=neighbours.facing(best.dx,best.dz);
            Candidate reverse=best(neighbours,target,best.dx,best.dz,targetFacing,best.targetAxis,best.targetSign);
            if(reverse==null || best.dx+reverse.dx!=0 || best.dz+reverse.dz!=0
                    || reverse.targetAxis!=axis || reverse.targetSign!=sign) continue;
            var rail=rail(state,facing,axis,0,0);
            HorizontalEnd end=new HorizontalEnd(axis,sign,rail.length()/2,rail.width()/2,best.mitre.sourceCuts());
            ends.add(end);
            key|=switch(end.edge()) { case WEST -> 1; case EAST -> 8; case NORTH -> 64; default -> 128; };
        }
        return ends.isEmpty() ? legacy : new Joins(key,legacy.westCut(),legacy.eastCut(),ends);
    }

    private static Candidate best(Neighbours neighbours,BlockState state,int ownerX,int ownerZ,Direction facing,int axis,int sign) {
        var source=rail(state,facing,axis,0,0); Candidate best=null;
        for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++) {
            if(x==0 && z==0) continue;
            BlockState other=neighbours.get(ownerX+x,ownerZ+z);
            if(!(other.getBlock() instanceof CopingBlock)) continue;
            var surface=other.get(CopingBlock.SURFACE);
            if(surface!=CopingBlock.Surface.FLAT && !surface.aligned()) continue;
            if(!state.get(CopingBlock.SURFACE).aligned() && !surface.aligned()) continue;
            for(int targetAxis=0;targetAxis<(surface.aligned() ? 1 : 2);targetAxis++) {
                var target=rail(other,neighbours.facing(ownerX+x,ownerZ+z),targetAxis,x,z);
                if(!touches(source,target)) continue;
                for(int targetSign:ENDS) {
                    var mitre=CopingHorizontalMitre.solve(source,sign,target,targetSign);
                    // A touching straight/diagonal corner can meet beyond the
                    // flat cap's original end. Bound that extension by the narrower wall's half-width, and
                    // keep one centre intersection on its original run to prevent
                    // two distant, mutually extended rails forming a false join.
                    double extension=state.get(CopingBlock.SURFACE)==CopingBlock.Surface.FLAT || surface==CopingBlock.Surface.FLAT
                            ? Math.min(source.width(),target.width())/2 : 0;
                    if(mitre==null || !mitre.reaches(extension)
                            || (mitre.sourceDistance()>source.length()/2+.00001
                                && mitre.targetDistance()>target.length()/2+.00001)
                            || !finiteEnd(source,sign,mitre.sourceCuts()) || !finiteEnd(target,targetSign,mitre.targetCuts())) continue;
                    double score=mitre.sourceDistance()+mitre.targetDistance();
                    Candidate candidate=new Candidate(axis,sign,x,z,targetAxis,targetSign,mitre,score);
                    if(best==null || score<best.score-.00001 || (equal(score,best.score)
                            && (x<best.dx || (x==best.dx && z<best.dz)))) best=candidate;
                }
            }
        }
        return best;
    }
    /** A join may extend its own end, but cannot erase the rail's opposite free end. */
    private static boolean finiteEnd(CopingHorizontalMitre.Rail rail,int sign,List<CopingHorizontalMitre.Cut> cuts) {
        double width=rail.width()/2;
        for(double p:new double[]{-width-.125,-width-.03125,-width,0,width,width+.03125,width+.125}) {
            int branch=p < -width ? 0 : p > width ? 2 : 1;
            double overhang=Math.max(0,Math.abs(p)-width);
            if(sign*cuts.get(branch).at(p)<-rail.length()/2-overhang-.00001) return false;
        }
        // Side bevels retain their authored run span. Their lateral cross-section
        // must not borrow the base's larger longitudinal overhang at the free end.
        double bevelLimit=-rail.length()/2-SIDE_BEVEL_RUN_OVERHANG-.00001;
        if(sign*cuts.get(0).at(-width-SIDE_BEVEL_OUTER_OVERHANG)<bevelLimit
                || sign*cuts.get(0).at(-width+SIDE_BEVEL_INNER_REACH)<bevelLimit
                || sign*cuts.get(2).at(width+SIDE_BEVEL_OUTER_OVERHANG)<bevelLimit
                || sign*cuts.get(2).at(width-SIDE_BEVEL_INNER_REACH)<bevelLimit) return false;
        return true;
    }

    private static CopingHorizontalMitre.Rail rail(BlockState state,Direction facing,int axis,int x,int z) {
        var surface=state.get(CopingBlock.SURFACE); var pose=surface.fit.pose(facing);
        return new CopingHorizontalMitre.Rail(x+pose.centreX(),z+pose.centreZ(),pose.yawRadians()+axis*Math.PI/2,
                axis==0 ? pose.runLength() : pose.width(),axis==0 ? pose.width() : pose.runLength());
    }

    /** Reject detached corners: SAT permits area overlap or one positive-length shared edge. */
    private static boolean touches(CopingHorizontalMitre.Rail a,CopingHorizontalMitre.Rail b) {
        double[] angles={a.yaw(),a.yaw()+Math.PI/2,b.yaw(),b.yaw()+Math.PI/2};
        double zeroAngle=Double.NaN;
        for(double angle:angles) {
            double x=Math.cos(angle),z=Math.sin(angle);
            double ar=Math.abs(Math.cos(a.yaw())*x+Math.sin(a.yaw())*z)*a.length()/2
                    +Math.abs(-Math.sin(a.yaw())*x+Math.cos(a.yaw())*z)*a.width()/2;
            double br=Math.abs(Math.cos(b.yaw())*x+Math.sin(b.yaw())*z)*b.length()/2
                    +Math.abs(-Math.sin(b.yaw())*x+Math.cos(b.yaw())*z)*b.width()/2;
            double overlap=ar+br-Math.abs((b.x()-a.x())*x+(b.z()-a.z())*z);
            if(overlap<-.00001) return false;
            if(overlap<=.00001) {
                if(Double.isFinite(zeroAngle) && Math.abs(Math.sin(angle-zeroAngle))>.00001) return false;
                zeroAngle=angle;
            }
        }
        return true;
    }

    private static int add(BlockView world,CopingBlock.Surface surface,Direction facing,BlockPos anchor,BlockPos owner,int key) {
        BlockState other=world.getBlockState(owner);
        if (!(other.getBlock() instanceof CopingBlock)) return key;
        if(other.get(CopingBlock.SURFACE).aligned()) return key;
        Direction otherFacing=other.get(CopingBlock.FACING);
        BlockPos otherAnchor=other.get(CopingBlock.OFFSET) ? owner.offset(otherFacing.getOpposite()) : owner;
        CopingBlock.Surface otherSurface=other.get(CopingBlock.SURFACE);
        int mask=joins(surface,facing,otherSurface,otherFacing,otherAnchor.getX()-anchor.getX(),
                otherAnchor.getY()-anchor.getY(),otherAnchor.getZ()-anchor.getZ());
        double gradient=otherSurface.gradient()*(facing==otherFacing.getOpposite() ? -1 : 1);
        int code=code(gradient);
        if ((mask&WEST)!=0) key=(key&~7)|code;
        if ((mask&EAST)!=0) key=(key&~56)|(code<<3);
        return key | ((mask&NORTH)!=0 ? 64 : 0) | ((mask&SOUTH)!=0 ? 128 : 0);
    }

    public static int joins(CopingBlock.Surface a,Direction facing,CopingBlock.Surface b,Direction otherFacing,int dx,int dy,int dz) {
        if(a.aligned() || b.aligned()) return 0;
        if (b!=CopingBlock.Surface.FLAT && facing.getAxis()!=otherFacing.getAxis()) return 0;
        Direction cross=facing.rotateYClockwise();
        int x=dx*facing.getOffsetX()+dz*facing.getOffsetZ();
        int z=dx*cross.getOffsetX()+dz*cross.getOffsetZ();
        boolean reverse=b!=CopingBlock.Surface.FLAT && facing==otherFacing.getOpposite();
        double start=reverse ? 1-b.end : b.start,end=reverse ? 1-b.start : b.end;
        double high=reverse ? b.low : b.high,low=reverse ? b.high : b.low;
        if (z==0) {
            if (equal(a.start,end+x) && equal(a.high,low+dy)) return WEST;
            if (equal(a.end,start+x) && equal(a.low,high+dy)) return EAST;
        }
        if (x==0 && equal(a.start,start) && equal(a.end,end)
                && equal(a.high,high+dy) && equal(a.low,low+dy)) {
            if (z==-1) return NORTH;
            if (z==1) return SOUTH;
        }
        return 0;
    }
    private static int code(double gradient) {
        for(int i=1;i<GRADIENTS.length;i++) if (equal(gradient,GRADIENTS[i])) return i;
        throw new IllegalArgumentException("Unsupported coping incline "+gradient);
    }
    private static boolean equal(double a,double b) { return Math.abs(a-b)<.00001; }
}
