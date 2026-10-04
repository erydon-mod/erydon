package com.oliver.erydon.block;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import java.util.ArrayList;
import java.util.List;

/** Bounded joined outlines use the same expanded end cuts as the placed flat mesh. */
final class CopingHorizontalShape {
    private record Key(CopingBlock.Surface surface,Direction facing,CopingConnections.Joins joins) { }
    private record Point(double x,double z) { }
    private static final Cache<Key,VoxelShape> CACHE=CacheBuilder.newBuilder().maximumSize(1024).build();
    private CopingHorizontalShape() { }
    static VoxelShape shape(CopingBlock.Surface surface,Direction facing,CopingConnections.Joins joins) {
        Key key=new Key(surface,facing,joins); VoxelShape cached=CACHE.getIfPresent(key);
        if(cached!=null) return cached;
        VoxelShape result=create(surface.fit.pose(facing),joins); CACHE.put(key,result); return result;
    }
    private static VoxelShape create(CopingHorizontalFit.Pose pose,CopingConnections.Joins joins) {
        double halfRun=pose.runLength()/2,halfWidth=pose.width()/2;
        int horizontalMask=joins.horizontal().stream().mapToInt(CopingConnections.HorizontalEnd::edge).reduce(0,(a,b)->a|b);
        int legacy=joins.mask()&~horizontalMask;
        double lowX=.5-halfRun-((legacy&CopingConnections.WEST)!=0 ? 0 : .125);
        double highX=.5+halfRun+((legacy&CopingConnections.EAST)!=0 ? 0 : .125);
        double lowZ=.5-halfWidth-((legacy&CopingConnections.NORTH)!=0 ? 0 : .125);
        double highZ=.5+halfWidth+((legacy&CopingConnections.SOUTH)!=0 ? 0 : .125);
        if((legacy&CopingConnections.WEST)!=0) lowX+=Math.min(0,CopingBlock.THICKNESS*Math.tan(Math.atan(joins.westGradient())/2));
        if((legacy&CopingConnections.EAST)!=0) highX+=Math.max(0,CopingBlock.THICKNESS*Math.tan(Math.atan(joins.eastGradient())/2));
        List<Double> xs=coordinates(lowX,highX,.5-halfRun,.5,.5+halfRun);
        List<Double> zs=coordinates(lowZ,highZ,.5-halfWidth,.5,.5+halfWidth);
        VoxelShape result=VoxelShapes.empty();
        for(int xi=1;xi<xs.size();xi++) for(int zi=1;zi<zs.size();zi++) {
            List<Point> polygon=new ArrayList<>();
            for(Point original:List.of(new Point(xs.get(xi-1),zs.get(zi-1)),new Point(xs.get(xi),zs.get(zi-1)),
                    new Point(xs.get(xi),zs.get(zi)),new Point(xs.get(xi-1),zs.get(zi)))) {
                var point=CopingConnections.expand(original.x,original.z,joins.horizontal());
                polygon.add(new Point(point.x(),point.z()));
            }
            List<List<Point>> pieces=new ArrayList<>(); pieces.add(polygon);
            for(var end:joins.horizontal()) {
                split(pieces,p -> end.lateral(p.x,p.z),-end.halfWidth());
                split(pieces,p -> end.lateral(p.x,p.z),end.halfWidth());
            }
            for(List<Point> piece:pieces) {
                for(var end:joins.horizontal()) piece=clip(piece,p -> end.distance(p.x,p.z,0),0,end.sign()<0);
                if(piece.size()<3) continue;
                result=VoxelShapes.union(result,raster(piece,pose));
            }
        }
        // Body cuts use proportional core widths. Named bevels instead use the
        // fixed outer branch even where their rotated lip projects inside the core.
        for(int edge:new int[]{CopingConnections.WEST,CopingConnections.EAST,CopingConnections.NORTH,CopingConnections.SOUTH}) {
            if((joins.mask()&edge)!=0) continue;
            double bx0,bx1,bz0,bz1;
            double run=CopingConnections.SIDE_BEVEL_RUN_OVERHANG,out=CopingConnections.SIDE_BEVEL_OUTER_OVERHANG,in=CopingConnections.SIDE_BEVEL_INNER_REACH;
            if(edge==CopingConnections.NORTH || edge==CopingConnections.SOUTH) {
                bx0=(legacy&CopingConnections.WEST)!=0 ? lowX : .5-halfRun-run;
                bx1=(legacy&CopingConnections.EAST)!=0 ? highX : .5+halfRun+run;
                bz0=edge==CopingConnections.NORTH ? .5-halfWidth-out : .5+halfWidth-in;
                bz1=edge==CopingConnections.NORTH ? .5-halfWidth+in : .5+halfWidth+out;
            } else {
                bx0=edge==CopingConnections.WEST ? .5-halfRun-out : .5+halfRun-in;
                bx1=edge==CopingConnections.WEST ? .5-halfRun+in : .5+halfRun+out;
                bz0=(legacy&CopingConnections.NORTH)!=0 ? .5-halfWidth : .5-halfWidth-run;
                bz1=(legacy&CopingConnections.SOUTH)!=0 ? .5+halfWidth : .5+halfWidth+run;
            }
            List<List<Point>> pieces=new ArrayList<>();
            pieces.add(rectangle(bx0,bx1,bz0,bz1));
            for(var end:joins.horizontal()) split(pieces,p -> end.run(p.x,p.z),0);
            for(List<Point> piece:pieces) {
                List<Point> expanded=piece.stream().map(p -> {
                    var point=CopingConnections.expand(p.x,p.z,joins.horizontal()); return new Point(point.x(),point.z());
                }).toList();
                List<List<Point>> clipped=new ArrayList<>(); clipped.add(expanded);
                for(var end:joins.horizontal()) if(CopingConnections.bevelBranch(edge,end)==0) {
                    split(clipped,p -> end.lateral(p.x,p.z),-end.halfWidth());
                    split(clipped,p -> end.lateral(p.x,p.z),end.halfWidth());
                }
                for(List<Point> retained:clipped) {
                    for(var end:joins.horizontal()) retained=clip(retained,p -> end.distance(p.x,p.z,CopingConnections.bevelBranch(edge,end)),0,end.sign()<0);
                    if(retained.size()>=3) result=VoxelShapes.union(result,raster(retained,pose));
                }
            }
        }
        return result.simplify();
    }
    private static List<Point> rectangle(double lowX,double highX,double lowZ,double highZ) {
        return List.of(new Point(lowX,lowZ),new Point(highX,lowZ),new Point(highX,highZ),new Point(lowX,highZ));
    }
    private static VoxelShape raster(List<Point> nativePolygon,CopingHorizontalFit.Pose pose) {
        double cosine=Math.cos(pose.yawRadians()),sine=Math.sin(pose.yawRadians());
        List<Point> polygon=nativePolygon.stream().map(p -> new Point(pose.centreX()+(p.x-.5)*cosine-(p.z-.5)*sine,
                pose.centreZ()+(p.x-.5)*sine+(p.z-.5)*cosine)).toList();
        double minZ=polygon.stream().mapToDouble(Point::z).min().orElseThrow(),maxZ=polygon.stream().mapToDouble(Point::z).max().orElseThrow();
        int first=(int)Math.floor(minZ*32),last=(int)Math.ceil(maxZ*32); VoxelShape result=VoxelShapes.empty();
        for(int row=first;row<last;row++) {
            List<Point> strip=clip(clip(polygon,row/32.0,true),(row+1)/32.0,false);
            if(strip.size()<3) continue;
            double low=strip.stream().mapToDouble(Point::x).min().orElseThrow(),high=strip.stream().mapToDouble(Point::x).max().orElseThrow();
            if(high-low>.000001) result=VoxelShapes.union(result,VoxelShapes.cuboid(low,0,row/32.0,high,CopingBlock.THICKNESS,(row+1)/32.0));
        }
        return result;
    }
    private static List<Double> coordinates(double low,double high,double... points) {
        List<Double> result=new ArrayList<>(); result.add(low);
        for(double point:points) if(point>low+.000001 && point<high-.000001) result.add(point);
        result.add(high); result.sort(Double::compare); return result;
    }
    private static List<Point> clip(List<Point> input,double z,boolean greater) {
        return clip(input,Point::z,z,greater);
    }
    private static void split(List<List<Point>> pieces,java.util.function.ToDoubleFunction<Point> coordinate,double at) {
        List<List<Point>> result=new ArrayList<>();
        for(List<Point> piece:pieces) {
            boolean low=piece.stream().anyMatch(p -> coordinate.applyAsDouble(p)<at-.000001);
            boolean high=piece.stream().anyMatch(p -> coordinate.applyAsDouble(p)>at+.000001);
            if(low && high) { result.add(clip(piece,coordinate,at,false)); result.add(clip(piece,coordinate,at,true)); }
            else result.add(piece);
        }
        pieces.clear(); pieces.addAll(result);
    }
    private static List<Point> clip(List<Point> input,java.util.function.ToDoubleFunction<Point> coordinate,double boundary,boolean greater) {
        if(input.isEmpty()) return input;
        List<Point> result=new ArrayList<>(); Point a=input.get(input.size()-1); double da=coordinate.applyAsDouble(a)-boundary;
        for(Point b:input) {
            double db=coordinate.applyAsDouble(b)-boundary; boolean ia=greater ? da>=0 : da<=0,ib=greater ? db>=0 : db<=0;
            if(ia!=ib) { double t=da/(da-db); result.add(new Point(a.x+(b.x-a.x)*t,a.z+(b.z-a.z)*t)); }
            if(ib) result.add(b); a=b; da=db;
        }
        return result;
    }
}
