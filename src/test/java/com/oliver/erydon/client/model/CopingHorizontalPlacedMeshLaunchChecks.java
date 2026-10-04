package com.oliver.erydon.client.model;

import com.google.gson.JsonParser;
import com.oliver.erydon.block.CopingBlock;
import com.oliver.erydon.block.CopingConnections;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/** Actual raw mesh vertices must remain selectable in multi-port and pitch/horizontal junctions. */
public final class CopingHorizontalPlacedMeshLaunchChecks {
    private static CopingGeometry flatSource;
    private static final Map<com.oliver.erydon.block.CopingHorizontalFit,CopingGeometry> FITTED=new java.util.EnumMap<>(com.oliver.erydon.block.CopingHorizontalFit.class);
    private static int pairSamples,pairCount,concealedSamples,junctionShapeVertices;
    private static CopingGeometry geometry() throws Exception {
        if(flatSource!=null) return flatSource;
        var stream=CopingHorizontalPlacedMeshLaunchChecks.class.getClassLoader().getResourceAsStream(
                "assets/erydon/authoring_models/block/coping/georgian/coping_georgian_flat.json");
        if(stream==null) throw new AssertionError("Missing flat coping parent");
        try(var reader=new InputStreamReader(stream,StandardCharsets.UTF_8)) {
            flatSource=CopingGeometry.parse(JsonParser.parseReader(reader).getAsJsonObject(),new Identifier("erydon","coping"),CopingBlock.Surface.FLAT);
        }
        return flatSource;
    }
    public static void verifyPair(BlockView view,BlockState source,BlockState target,BlockPos other) throws Exception {
        pairSamples+=unionSeams(geometry(),view,Map.of(BlockPos.ORIGIN,source,other,target)); pairCount++;
    }
    public static int verifyJunction(BlockView view,Map<BlockPos,BlockState> placed) throws Exception {
        int samples=unionSeams(geometry(),view,placed);
        for(var entry:placed.entrySet()) {
            var state=entry.getValue(); var owner=entry.getKey(); var profile=state.get(CopingBlock.SURFACE);
            var facing=CopingConnections.facing(view,state,owner); var joins=CopingConnections.resolve(view,state,owner,facing);
            var shape=state.getOutlineShape(view,owner);
            if(shape!=state.getCollisionShape(view,owner)) throw new AssertionError("Physical junction outline and collision differ");
            var transform=new CopingHorizontalTransform(profile.fit.pose(facing));
            var fitted=FITTED.computeIfAbsent(profile.fit,geometry()::fitted);
            for(var surface:fitted.joined(profile,joins)) for(var vertex:surface.vertices()) {
                double x=transform.x(vertex.x(),vertex.z()),z=transform.z(vertex.x(),vertex.z());
                if(shape.getBoundingBoxes().stream().noneMatch(box -> x>=box.minX-.00003 && x<=box.maxX+.00003
                        && z>=box.minZ-.00003 && z<=box.maxZ+.00003 && vertex.y()>=box.minY-.00003 && vertex.y()<=box.maxY+.00003))
                    throw new AssertionError("Actual support-derived junction vertex is outside outline/collision: "+state+" "+owner+" "+vertex+" "+joins);
                junctionShapeVertices++;
            }
        }
        return samples;
    }
    public static void verifySavedShallowContour(BlockView view,BlockState corner,BlockPos owner,boolean right,net.minecraft.util.BlockRotation rotation) throws Exception {
        var joins=CopingConnections.resolve(view,corner,owner);
        var mesh=geometry().joined(CopingBlock.Surface.FLAT,joins);
        int endEdge=joins.horizontal().get(0).edge();
        if(mesh.stream().anyMatch(surface -> surface.edge()==endEdge))
            throw new AssertionError("Connected 63-degree corner retains its free end bevel");
        // Independent continued exterior rays: incoming x=1.125; outgoing
        // broadLEFT/SOUTH has centre(.4,1.3), tangent(-2,1)/sqrt5,
        // width2/sqrt5, and the same .125 base overhang.
        var targetOwner=owner.add(new BlockPos(0,0,1).rotate(rotation)); var target=view.getBlockState(targetOwner);
        var targetProfile=target.get(CopingBlock.SURFACE); var targetFacing=CopingConnections.facing(view,target,targetOwner);
        var targetJoins=CopingConnections.resolve(view,target,targetOwner,targetFacing);
        var targetMesh=FITTED.computeIfAbsent(targetProfile.fit,geometry()::fitted).joined(targetProfile,targetJoins);
        if(targetMesh.stream().anyMatch(surface -> surface.edge()==CopingConnections.WEST))
            throw new AssertionError("Actual shallow corner retains its free native-west bevel toe");
        var targetTransform=new CopingHorizontalTransform(targetProfile.fit.pose(targetFacing));
        int turns=rotation==net.minecraft.util.BlockRotation.CLOCKWISE_90 ? 1 : rotation==net.minecraft.util.BlockRotation.CLOCKWISE_180 ? 2
                : rotation==net.minecraft.util.BlockRotation.COUNTERCLOCKWISE_90 ? 3 : 0;
        for(double overhang:new double[]{.125,.03125}) {
            double height=overhang==.125 ? 0 : .1875;
            double half=1/Math.sqrt(5)+overhang,edgeX=.4+half/Math.sqrt(5),edgeZ=1.3+2*half/Math.sqrt(5);
            double ex=1+overhang,ez=edgeZ-(ex-edgeX)/2; if(right) ex=1-ex;
            for(int turn=0;turn<turns;turn++) { double next=1-ez; ez=ex; ex=next; }
            final double expectedX=ex,expectedZ=ez;
            if(mesh.stream().flatMap(surface -> surface.vertices().stream()).noneMatch(v -> Math.abs(v.y()-height)<.00001
                    && Math.abs(v.x()-expectedX)<.00003 && Math.abs(v.z()-expectedZ)<.00003))
                throw new AssertionError("Actual straight contour misses continued exterior corner "+expectedX+","+expectedZ+" at "+height);
            if(targetMesh.stream().flatMap(surface -> surface.vertices().stream()).noneMatch(v -> Math.abs(v.y()-height)<.00001
                    && Math.abs(targetOwner.getX()-owner.getX()+targetTransform.x(v.x(),v.z())-expectedX)<.00003
                    && Math.abs(targetOwner.getZ()-owner.getZ()+targetTransform.z(v.x(),v.z())-expectedZ)<.00003))
                throw new AssertionError("Actual shallow contour misses the same exterior corner");
        }
        for(var surface:targetMesh) for(var v:surface.vertices()) if(v.y()<.12151) {
            double x=targetOwner.getX()-owner.getX()+targetTransform.x(v.x(),v.z());
            double z=targetOwner.getZ()-owner.getZ()+targetTransform.z(v.x(),v.z());
            for(int turn=0;turn<turns;turn++) { double next=z; z=1-x; x=next; }
            if((!right && x>1.125+.00003) || (right && x<-.125-.00003))
                throw new AssertionError("A shallow free-end base toe still protrudes beyond the continued straight exterior");
        }
    }
    public static void run(CopingBlock coping) throws Exception {
        CopingGeometry geometry=geometry();
        BlockState flat=coping.getDefaultState();
        BlockState diagonal=flat.with(CopingBlock.SURFACE,CopingBlock.Surface.DIAGONAL);
        BlockState broad=flat.with(CopingBlock.SURFACE,CopingBlock.Surface.SHALLOW_BROAD_RIGHT).with(CopingBlock.FACING,Direction.NORTH);
        int checked=check(geometry,flat,Map.of(BlockPos.ORIGIN,flat,new BlockPos(0,0,1),diagonal,new BlockPos(1,0,0),diagonal),true);
        checked+=check(geometry,flat,Map.of(BlockPos.ORIGIN,flat,new BlockPos(0,0,1),diagonal,new BlockPos(1,0,0),broad),true);
        var left=flat.with(CopingBlock.SURFACE,CopingBlock.Surface.SHALLOW_BROAD_LEFT).with(CopingBlock.FACING,Direction.SOUTH);
        checked+=check(geometry,flat,Map.of(BlockPos.ORIGIN,flat,new BlockPos(0,0,1),diagonal,new BlockPos(1,0,0),left),true);
        for(boolean east:new boolean[]{false,true}) for(boolean reverse:new boolean[]{false,true}) {
            BlockState pitched=flat.with(CopingBlock.SURFACE,CopingBlock.Surface.SLOPE)
                    .with(CopingBlock.FACING,reverse ? Direction.WEST : Direction.EAST);
            BlockPos owner=new BlockPos(east ? 1 : -1,east==reverse ? 1 : 0,0);
            checked+=check(geometry,flat,Map.of(BlockPos.ORIGIN,flat,new BlockPos(0,0,1),diagonal,owner,pitched),false);
        }
        System.out.println("ERYDON_COPING_JUNCTION_MESH_SHAPE_OK: "+checked+" actual raw vertices in multi-port and both-sign pitch/horizontal placed outlines");
        System.out.println("ERYDON_COPING_SUPPORTED_JUNCTION_SHAPES_OK: "+junctionShapeVertices+" actual raw vertices in all twelve rotated support-derived junction outlines/collisions");
        System.out.println("ERYDON_COPING_PLACED_PAIR_MESH_SEAMS_OK: "+pairCount+" actual resolver-selected saved owner/facing/profile pairs, "+pairSamples+" visible envelope and raw-solid seam samples, "+concealedSamples+" bevel lip samples proved strictly inside their own body");
    }
    private static int check(CopingGeometry geometry,BlockState flat,Map<BlockPos,BlockState> states,boolean twoPorts) {
        BlockView view=(BlockView)Proxy.newProxyInstance(BlockView.class.getClassLoader(),new Class[]{BlockView.class},(proxy,method,args) -> {
            if(method.getName().equals("getBlockState")) return states.getOrDefault(args[0],Blocks.AIR.getDefaultState());
            if(method.getName().equals("getHeight")) return 384;
            if(method.getName().equals("getBottomY")) return -64;
            if(method.isDefault()) return InvocationHandler.invokeDefault(proxy,method,args);
            throw new UnsupportedOperationException(method.toString());
        });
        Direction facing=CopingConnections.facing(view,flat,BlockPos.ORIGIN);
        var joins=CopingConnections.resolve(view,flat,BlockPos.ORIGIN,facing);
        if(joins.horizontal().isEmpty() || (twoPorts && joins.horizontal().size()<2)) throw new AssertionError("Junction fixture lost its horizontal joins");
        if(twoPorts) System.out.println("ERYDON_COPING_THREE_PIECE_UNION_OK: "+unionSeams(geometry,view,states)+" actual top/base/bevel seam endpoints and midpoints covered by neighbouring raw meshes");
        var shape=flat.getOutlineShape(view,BlockPos.ORIGIN); var transform=new CopingHorizontalTransform(CopingBlock.Surface.FLAT.fit.pose(facing));
        int vertices=0;
        for(var surface:geometry.joined(CopingBlock.Surface.FLAT,joins)) for(var vertex:surface.vertices()) {
            double x=transform.x(vertex.x(),vertex.z()),z=transform.z(vertex.x(),vertex.z());
            if(shape.getBoundingBoxes().stream().noneMatch(box -> x>=box.minX-.00003 && x<=box.maxX+.00003
                    && z>=box.minZ-.00003 && z<=box.maxZ+.00003 && vertex.y()>=box.minY-.00003 && vertex.y()<=box.maxY+.00003))
                throw new AssertionError("Actual horizontal/pitch junction mesh vertex is outside its placed outline: "+vertex+" "+joins);
            if(x<=-2 || x>=3 || z<=-2 || z>=3) throw new AssertionError("Actual multi-port junction escaped two-owner picking extent");
            vertices++;
        }
        return vertices;
    }
    private record Placed(BlockPos owner,BlockState state,CopingConnections.Joins joins,
                          List<CopingGeometry.Surface> surfaces,CopingHorizontalTransform transform) { }
    private static int unionSeams(CopingGeometry flat,BlockView view,Map<BlockPos,BlockState> states) {
        List<Placed> pieces=new ArrayList<>();
        for(var entry:states.entrySet()) {
            var state=entry.getValue(); var profile=state.get(CopingBlock.SURFACE);
            Direction facing=CopingConnections.facing(view,state,entry.getKey());
            var joins=CopingConnections.resolve(view,state,entry.getKey(),facing);
            var fitted=FITTED.computeIfAbsent(profile.fit,flat::fitted);
            pieces.add(new Placed(entry.getKey(),state,joins,fitted.joined(profile,joins),
                    new CopingHorizontalTransform(profile.fit.pose(facing))));
        }
        int samples=0;
        for(var piece:pieces) for(var surface:piece.surfaces) for(int edge=0;edge<surface.vertices().size();edge++) {
            var a=surface.vertices().get(edge); var b=surface.vertices().get((edge+1)%surface.vertices().size());
            for(var end:piece.joins.horizontal()) {
                if(!sharedCut(surface,a,b,end)) continue;
                for(float t:new float[]{0,.5F,1}) {
                    var v=a.blend(b,t); double x=piece.owner.getX()+piece.transform.x(v.x(),v.z());
                    double z=piece.owner.getZ()+piece.transform.z(v.x(),v.z()),y=piece.owner.getY()+v.y();
                    if(pieces.stream().filter(other -> other!=piece).noneMatch(other -> covers(other,x,y,z))
                            && !insideBody(piece,v,x,y,z))
                        throw new AssertionError("Actual raw union has an uncovered shared bevel/base/top seam at "+x+","+y+","+z
                                +"; source="+piece.state+" owner="+piece.owner+" joins="+piece.joins
                                +"; raw surface="+surface+"; states="+states+"; peers="+pieces.stream().filter(other -> other!=piece)
                                .map(other -> other.state+" owner="+other.owner+" joins="+other.joins).toList());
                    samples++;
                }
            }
        }
        if(samples==0) throw new AssertionError("Three-piece union did not exercise real seam intervals");
        return samples;
    }
    static boolean sharedCut(CopingGeometry.Surface surface,CopingGeometry.Vertex a,CopingGeometry.Vertex b,
                             CopingConnections.HorizontalEnd end) {
        int fixed=CopingGeometry.branch(surface.edge(),end);
        var midpoint=a.blend(b,.5F);
        double lateral=end.lateral(midpoint.x(),midpoint.z());
        int index=fixed<0 ? 0 : fixed>0 ? 2 : lateral < -end.halfWidth() ? 0 : lateral > end.halfWidth() ? 2 : 1;
        var cut=end.cuts().get(index);
        for(var v:List.of(a,midpoint,b))
            if(Math.abs(end.distance(v.x(),v.z(),fixed))>.00003
                    || Math.abs(end.run(v.x(),v.z())-cut.at(end.lateral(v.x(),v.z())))>.00003) return false;
        return true;
    }
    /** Concealed inward bevel lips need no peer surface; exposed cut/perimeter points still do. */
    private static boolean insideBody(Placed piece,CopingGeometry.Vertex vertex,double x,double y,double z) {
        double margin=.00005;
        // A lip on the actual body cut is exposed, even if vertical min/max ranges overlap.
        if(piece.joins.horizontal().stream().anyMatch(end -> end.sign()*end.distance(vertex.x(),vertex.z(),0)>=-margin)) return false;
        // Require a small open neighbourhood in the actual clipped, non-bevel body volume.
        for(double[] offset:new double[][]{{margin,0},{-margin,0},{0,margin},{0,-margin}})
            if(!insideBodyColumn(piece,x+offset[0],y,z+offset[1],margin)) return false;
        concealedSamples++;
        return true;
    }
    private record BodyHit(double height,int entering) { }
    private static boolean insideBodyColumn(Placed piece,double x,double y,double z,double margin) {
        List<BodyHit> hits=new ArrayList<>();
        for(var surface:piece.surfaces) {
            if(surface.normals().isEmpty() || surface.face().getAxis()!=Direction.Axis.Y) continue;
            for(int vertex=1;vertex<surface.vertices().size()-1;vertex++) {
                double[] a=point(piece,surface.vertices().get(0)),b=point(piece,surface.vertices().get(vertex)),c=point(piece,surface.vertices().get(vertex+1));
                double det=(b[2]-c[2])*(a[0]-c[0])+(c[0]-b[0])*(a[2]-c[2]);
                if(Math.abs(det)<.00000001) continue;
                double u=((b[2]-c[2])*(x-c[0])+(c[0]-b[0])*(z-c[2]))/det;
                double v=((c[2]-a[2])*(x-c[0])+(a[0]-c[0])*(z-c[2]))/det;
                if(u<-.0000001 || v<-.0000001 || u+v>1.0000001) continue;
                hits.add(new BodyHit(u*a[1]+v*b[1]+(1-u-v)*c[1],surface.face()==Direction.DOWN ? 1 : -1));
                break; // A triangulation diagonal must not count a body face twice.
            }
        }
        int below=0,above=0;
        for(var hit:hits) {
            if(hit.height()<y-margin) below+=hit.entering();
            if(hit.height()<y+margin) above+=hit.entering();
        }
        return below>0 && above>0;
    }
    private static boolean covers(Placed piece,double x,double y,double z) {
        double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;
        for(var surface:piece.surfaces) for(int vertex=1;vertex<surface.vertices().size()-1;vertex++) {
            double[] a=point(piece,surface.vertices().get(0)),b=point(piece,surface.vertices().get(vertex)),c=point(piece,surface.vertices().get(vertex+1));
            double det=(b[2]-c[2])*(a[0]-c[0])+(c[0]-b[0])*(a[2]-c[2]);
            if(Math.abs(det)<.00000001) continue;
            double u=((b[2]-c[2])*(x-c[0])+(c[0]-b[0])*(z-c[2]))/det;
            double v=((c[2]-a[2])*(x-c[0])+(a[0]-c[0])*(z-c[2]))/det;
            if(u<-.00003 || v<-.00003 || u+v>1.00003) continue;
            double hit=u*a[1]+v*b[1]+(1-u-v)*c[1]; low=Math.min(low,hit); high=Math.max(high,hit);
        }
        return y>=low-.00003 && y<=high+.00003;
    }
    private static double[] point(Placed piece,CopingGeometry.Vertex v) {
        return new double[]{piece.owner.getX()+piece.transform.x(v.x(),v.z()),piece.owner.getY()+v.y(),piece.owner.getZ()+piece.transform.z(v.x(),v.z())};
    }
}
