package com.oliver.erydon.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import java.util.HashMap;
import java.util.Map;

/** Production placement, saved poses, reciprocal horizontal discovery and sharp-corner outlines. */
final class CopingHorizontalPlacementLaunchChecks {
    static void run(CopingBlock coping) {
        int fits=0,joins=0,extended=0,rejected=0; boolean axis45=false,axisShallow=false,mixed=false,handed=false;
        var rayEntity=new net.minecraft.entity.MarkerEntity(net.minecraft.entity.EntityType.MARKER,null);
        for(Direction facing:Direction.Type.HORIZONTAL) for(boolean left:new boolean[]{false,true}) {
            for(int kind=0;kind<5;kind++) {
                boolean sourceBroad=kind<3,partnerBroad=kind<2 || kind==3;
                int partnerX=kind==0 ? 0 : -1,partnerZ=kind==0 ? (left ? 1 : -1) : 0;
                for(int turn=0;turn<CopingHorizontalFit.quarter(facing.rotateYClockwise());turn++) { int next=-partnerZ; partnerZ=partnerX; partnerX=next; }
                BlockState source=vertical(sourceBroad,left,facing),partner=vertical(partnerBroad,left,facing.getOpposite());
                BlockPos targetPos=new BlockPos(partnerX,0,partnerZ);
                var states=new HashMap<BlockPos,BlockState>(); states.put(BlockPos.ORIGIN,source); states.put(targetPos,partner);
                var view=CopingPlacementLaunchProbe.view(states);
                var one=coping.forSupport(source,view,BlockPos.ORIGIN,false,false);
                var two=coping.forSupport(partner,view,targetPos,false,false);
                require(one!=null && two!=null,"Valid shallow pair did not auto-fit "+kind+" "+facing+" "+left);
                require(Math.abs(one.get(CopingBlock.SURFACE).fit.width-two.get(CopingBlock.SURFACE).fit.width)<.00001,"Half-pair widths differ");
                require(CopingBlock.supportPos(one,BlockPos.ORIGIN.up()).equals(BlockPos.ORIGIN),"Horizontal cap changed owner cell");
                states.put(BlockPos.ORIGIN.up(),one); states.put(targetPos.up(),two);
                require(CopingConnections.mask(view,one,BlockPos.ORIGIN.up())!=0,"Paired cap run has no join");
                require(CopingConnections.mask(view,two,targetPos.up())!=0,"Paired cap run is not reciprocal");
                checkTransforms(coping,one); checkTransforms(coping,two); fits+=2;
            }
            BlockState standard=block("glacium_slope_vertical").getDefaultState().with(SlopeVerticalBlock.FACING,facing);
            var one=coping.forSupport(standard,CopingPlacementLaunchProbe.view(Map.of(BlockPos.ORIGIN,standard)),BlockPos.ORIGIN,false,false);
            require(one!=null && one.get(CopingBlock.SURFACE)==CopingBlock.Surface.DIAGONAL,"Standalone 45-degree wedge lost its tip cap");
            checkTransforms(coping,one); fits++;
            BlockState unsupported=vertical(true,left,facing);
            require(coping.forSupport(unsupported,CopingPlacementLaunchProbe.view(Map.of(BlockPos.ORIGIN,unsupported)),BlockPos.ORIGIN,false,false)==null,
                    "Unpaired shallow wedge guessed a wall width");
        }
        finiteSideBevelSpan(coping);
        for(var a:CopingBlock.Surface.values()) if(a==CopingBlock.Surface.FLAT || a.aligned())
            for(var b:CopingBlock.Surface.values()) if(b==CopingBlock.Surface.FLAT || b.aligned())
                for(Direction af:Direction.Type.HORIZONTAL) for(Direction bf:Direction.Type.HORIZONTAL)
                    for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++) if(x!=0 || z!=0) {
                        BlockState first=coping.getDefaultState().with(CopingBlock.SURFACE,a).with(CopingBlock.FACING,af);
                        BlockState second=coping.getDefaultState().with(CopingBlock.SURFACE,b).with(CopingBlock.FACING,bf);
                        BlockPos other=new BlockPos(x,0,z);
                        var view=CopingPlacementLaunchProbe.view(Map.of(BlockPos.ORIGIN,first,other,second));
                        var result=CopingConnections.resolve(view,first,BlockPos.ORIGIN);
                        if(result.horizontal().isEmpty()) { rejected++; continue; }
                        require(!CopingConnections.resolve(view,second,other).horizontal().isEmpty(),"Horizontal join is not reciprocal");
                        try { com.oliver.erydon.client.model.CopingHorizontalPlacedMeshLaunchChecks.verifyPair(view,first,second,other); }
                        catch(Exception failure) { throw new AssertionError(failure); }
                        var shape=first.getOutlineShape(view,BlockPos.ORIGIN);
                        require(shape==first.getCollisionShape(view,BlockPos.ORIGIN),"Joined outline/collision differ");
                        var pose=a.fit.pose(af);
                        for(var end:result.horizontal()) for(int side:new int[]{-1,1}) {
                            double lateral=side*(end.halfWidth()+.125),run=end.cut(lateral,side);
                            double nx=end.axis()==0 ? .5+run : .5-lateral,nz=end.axis()==0 ? .5+lateral : .5+run;
                            if(result.horizontal().stream().anyMatch(otherEnd -> otherEnd!=end && otherEnd.sign()*otherEnd.distance(nx,nz,0)>.00002)) continue;
                            double wx=pose.centreX()+(nx-.5)*Math.cos(pose.yawRadians())-(nz-.5)*Math.sin(pose.yawRadians());
                            double wz=pose.centreZ()+(nx-.5)*Math.sin(pose.yawRadians())+(nz-.5)*Math.cos(pose.yawRadians());
                            require(shape.getBoundingBoxes().stream().anyMatch(box -> wx>=box.minX-.00002 && wx<=box.maxX+.00002
                                    && wz>=box.minZ-.00002 && wz<=box.maxZ+.00002),"Actual sharp corner lies outside selection "+a+" -> "+b);
                            if(Math.abs(run)>end.halfLength()+.125) {
                                if(extended<24) {
                                    double rx=wx*.999+pose.centreX()*.001,rz=wz*.999+pose.centreZ()*.001;
                                    var hit=view.raycast(new net.minecraft.world.RaycastContext(new Vec3d(rx,3,rz),new Vec3d(rx,-1,rz),
                                            net.minecraft.world.RaycastContext.ShapeType.OUTLINE,net.minecraft.world.RaycastContext.FluidHandling.NONE,rayEntity));
                                    require(view.getBlockState(hit.getBlockPos()).getBlock() instanceof CopingBlock,
                                            "Extended mitre cannot be selected through its actual world raycast");
                                }
                                extended++;
                            }
                        }
                        axis45|=(a==CopingBlock.Surface.FLAT && b==CopingBlock.Surface.DIAGONAL);
                        axisShallow|=(a==CopingBlock.Surface.FLAT && b.aligned() && b!=CopingBlock.Surface.DIAGONAL);
                        mixed|=(a==CopingBlock.Surface.DIAGONAL && b.aligned() && b!=CopingBlock.Surface.DIAGONAL);
                        handed|=(a.fit.angle>0 && b.fit.angle<0 && a!=CopingBlock.Surface.DIAGONAL && b!=CopingBlock.Surface.DIAGONAL);
                        joins++;
                    }
        require(axis45 && axisShallow && mixed && handed,"Mixed straight/45/shallow/handed production fixtures did not discover all transition families");
        require(extended>0,"Fixtures did not prove selection for an extended outer mitre");
        physicalJunctions(coping); savedShallowCorner(coping); performanceBounds(coping); inferredFlatJoin(coping);
        try { com.oliver.erydon.client.model.CopingHorizontalPlacedMeshLaunchChecks.run(coping); }
        catch(Exception failure) { throw new AssertionError(failure); }
        System.out.println("ERYDON_COPING_HORIZONTAL_OK: "+fits+" paired placement/transforms, "+joins+" reciprocal owner/facing/profile joins, "+rejected+" detached/incompatible candidates retained free ends, "+extended+" extended corner selection bounds");
    }
    private static void savedShallowCorner(CopingBlock coping) {
        for(boolean right:new boolean[]{false,true}) for(var rotation:BlockRotation.values()) {
        var states=new HashMap<BlockPos,BlockState>();
        for(int z=-2;z<=0;z++) states.put(new BlockPos(0,0,z),net.minecraft.block.Blocks.STONE.getDefaultState());
        int side=right ? 1 : -1;
        states.put(new BlockPos(0,0,1),vertical(true,!right,Direction.EAST));
        states.put(new BlockPos(side,0,1),vertical(true,!right,Direction.WEST));
        states.put(new BlockPos(side*2,0,1),vertical(false,!right,Direction.WEST));
        states.put(new BlockPos(side,0,2),vertical(false,!right,Direction.EAST));
        var transformed=new HashMap<BlockPos,BlockState>();
        for(var entry:states.entrySet()) transformed.put(entry.getKey().rotate(rotation),entry.getValue().rotate(rotation));
        states=transformed;
        var view=CopingPlacementLaunchProbe.view(states); var caps=new HashMap<BlockPos,BlockState>();
        for(var original:java.util.List.of(new BlockPos(0,0,-2),new BlockPos(0,0,-1),BlockPos.ORIGIN,new BlockPos(0,0,1),new BlockPos(side,0,1))) {
            var owner=original.rotate(rotation);
            var fitted=coping.forSupport(states.get(owner),view,owner,false,false);
            require(fitted!=null,"Saved straight/broad-left corner lost its real automatic support fit"); caps.put(owner.up(),fitted);
        }
        states.putAll(caps);
        var corner=caps.get(BlockPos.ORIGIN.up()); var joins=CopingConnections.resolve(view,corner,BlockPos.ORIGIN.up());
        require(!joins.horizontal().isEmpty(),
                "Real 63-degree corner still keeps its unused square SOUTH end");
        var previous=new BlockPos(0,0,-1).rotate(rotation);
        int incoming=previous.getX()<0 ? CopingConnections.WEST : previous.getX()>0 ? CopingConnections.EAST
                : previous.getZ()<0 ? CopingConnections.NORTH : CopingConnections.SOUTH;
        require((joins.mask()&incoming)!=0,"Corner lost the preceding flat run");
        try {
            com.oliver.erydon.client.model.CopingHorizontalPlacedMeshLaunchChecks.verifyJunction(view,caps);
            com.oliver.erydon.client.model.CopingHorizontalPlacedMeshLaunchChecks.verifySavedShallowContour(view,corner,BlockPos.ORIGIN.up(),right,rotation);
        } catch(Exception failure) { throw new AssertionError(failure); }
        }
        System.out.println("ERYDON_COPING_SAVED_SHALLOW_CORNER_OK: eight real flat rows and broad/broad paired turns cover both hands/all rotations without retaining free end toes");
    }
    private static void physicalJunctions(CopingBlock coping) {
        int samples=0,fixtures=0;
        var stone=net.minecraft.block.Blocks.STONE.getDefaultState();
        var diagonal=block("glacium_slope_vertical").getDefaultState().with(SlopeVerticalBlock.FACING,Direction.NORTH);
        for(int kind=0;kind<3;kind++) for(var rotation:BlockRotation.values()) {
            var nativeWalls=new HashMap<BlockPos,BlockState>();
            nativeWalls.put(BlockPos.ORIGIN,stone); nativeWalls.put(new BlockPos(0,0,1),diagonal);
            nativeWalls.put(new BlockPos(1,0,0),kind==0 ? diagonal : vertical(true,kind==2,kind==2 ? Direction.EAST : Direction.WEST));
            if(kind==1) nativeWalls.put(new BlockPos(1,0,1),vertical(false,false,Direction.EAST));
            if(kind==2) nativeWalls.put(new BlockPos(1,0,-1),vertical(false,true,Direction.WEST));
            var worldStates=new HashMap<BlockPos,BlockState>();
            for(var entry:nativeWalls.entrySet()) worldStates.put(entry.getKey().rotate(rotation),entry.getValue().rotate(rotation));
            var view=CopingPlacementLaunchProbe.view(worldStates); var caps=new HashMap<BlockPos,BlockState>();
            for(var owner:java.util.List.of(BlockPos.ORIGIN,new BlockPos(1,0,0).rotate(rotation),new BlockPos(0,0,1).rotate(rotation))) {
                var placed=coping.forSupport(worldStates.get(owner),view,owner,false,false);
                require(placed!=null,"Compatible multi-port wall lost its automatic coping fit");
                caps.put(owner.up(),placed);
            }
            worldStates.putAll(caps);
            require(CopingConnections.resolve(view,caps.get(BlockPos.ORIGIN.up()),BlockPos.ORIGIN.up()).horizontal().size()==2,
                    "Physical straight/45/shallow junction lost its two reciprocal ports");
            for(var entry:caps.entrySet()) {
                var owner=entry.getKey().down();
                require(coping.forSupport(worldStates.get(owner),view,owner,false,false).equals(entry.getValue()),
                        "Saved multi-port coping differs from its actual wall support profile");
            }
            try { samples+=com.oliver.erydon.client.model.CopingHorizontalPlacedMeshLaunchChecks.verifyJunction(view,caps); }
            catch(Exception failure) { throw new AssertionError(failure); }
            fixtures++;
        }
        System.out.println("ERYDON_COPING_SUPPORTED_JUNCTIONS_OK: "+fixtures+" automatically placed three-cap wall junctions, "+samples+" actual raw union seam samples");
    }
    private static void finiteSideBevelSpan(CopingBlock coping) {
        var source=coping.getDefaultState().with(CopingBlock.SURFACE,CopingBlock.Surface.SHALLOW_BROAD_WIDE_RIGHT)
                .with(CopingBlock.FACING,Direction.NORTH);
        var target=coping.getDefaultState().with(CopingBlock.SURFACE,CopingBlock.Surface.SHALLOW_NARROW_RIGHT)
                .with(CopingBlock.FACING,Direction.EAST);
        var other=new BlockPos(1,0,1);
        var view=CopingPlacementLaunchProbe.view(Map.of(BlockPos.ORIGIN,source,other,target));
        require(CopingConnections.resolve(view,source,BlockPos.ORIGIN).horizontal().isEmpty()
                && CopingConnections.resolve(view,target,other).horizontal().isEmpty(),
                "A side bevel borrowed the base overhang and lost its opposite free end");
    }
    private static void performanceBounds(CopingBlock coping) {
        int[] reads={0};
        var view=(net.minecraft.world.BlockView)java.lang.reflect.Proxy.newProxyInstance(net.minecraft.world.BlockView.class.getClassLoader(),
                new Class[]{net.minecraft.world.BlockView.class},(proxy,method,args) -> {
                    if(method.getName().equals("getBlockState")) { reads[0]++; return net.minecraft.block.Blocks.AIR.getDefaultState(); }
                    throw new UnsupportedOperationException(method.toString());
                });
        CopingRaycast.closest(view,new Vec3d(-1,.8,.5),new Vec3d(2,.8,.5),BlockPos.ORIGIN,net.minecraft.block.Blocks.AIR.getDefaultState(),null);
        require(reads[0]==0,"High ray still scans coping owner neighbours");
        CopingRaycast.closest(view,new Vec3d(-1,.1,.5),new Vec3d(2,.1,.5),BlockPos.ORIGIN,net.minecraft.block.Blocks.AIR.getDefaultState(),null);
        require(reads[0]==24,"Thin-slab empty ray did not keep the 24-owner lookup bound");
        reads[0]=0;
        var flat=coping.getDefaultState();
        var free=coping.getOutlineShape(flat,null,BlockPos.ORIGIN,net.minecraft.block.ShapeContext.absent());
        var ordinary=flat.getOutlineShape(view,BlockPos.ORIGIN);
        require(ordinary==free && reads[0]==8,"Ordinary flat outline no longer uses its cached shape after eight local reads");
        var diagonal=flat.with(CopingBlock.SURFACE,CopingBlock.Surface.DIAGONAL);
        var mixedView=CopingPlacementLaunchProbe.view(Map.of(BlockPos.ORIGIN,flat,new BlockPos(0,0,1),diagonal));
        require(flat.getOutlineShape(mixedView,BlockPos.ORIGIN)!=free,"Adjacent diagonal cap did not select the actual joined outline");
    }
    private static void inferredFlatJoin(CopingBlock coping) {
        boolean exercised=false;
        BlockState flat=coping.getDefaultState(),pitch=coping.getDefaultState().with(CopingBlock.SURFACE,CopingBlock.Surface.SHALLOW_UPPER)
                .with(CopingBlock.FACING,Direction.SOUTH);
        for(Direction facing:Direction.Type.HORIZONTAL) for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++) if(x!=0 || z!=0) {
            BlockPos other=new BlockPos(x,0,z); if(other.equals(new BlockPos(0,0,1))) continue;
            BlockState diagonal=coping.getDefaultState().with(CopingBlock.SURFACE,CopingBlock.Surface.DIAGONAL).with(CopingBlock.FACING,facing);
            var view=CopingPlacementLaunchProbe.view(Map.of(BlockPos.ORIGIN,flat,other,diagonal,new BlockPos(0,0,1),pitch));
            if(CopingConnections.facing(view,flat,BlockPos.ORIGIN)==Direction.EAST) continue;
            var a=CopingConnections.resolve(view,flat,BlockPos.ORIGIN);
            if(a.horizontal().isEmpty()) continue;
            require(!CopingConnections.resolve(view,diagonal,other).horizontal().isEmpty(),"Inferred flat facing loses reciprocal diagonal join beside a third pitched cap");
            exercised=true;
        }
        require(exercised,"Three-coping inferred-flat fixture did not exercise the original reciprocity defect");
    }
    private static void checkTransforms(CopingBlock coping,BlockState state) {
        var pose=CopingBlock.attachmentPose(state);
        for(BlockRotation rotation:BlockRotation.values()) {
            var rotated=CopingBlock.attachmentPose(state.rotate(rotation));
            int turns=rotation==BlockRotation.CLOCKWISE_90 ? 1 : rotation==BlockRotation.CLOCKWISE_180 ? 2 : rotation==BlockRotation.COUNTERCLOCKWISE_90 ? 3 : 0;
            double x=pose.centreX(),z=pose.centreZ();
            for(int turn=0;turn<turns;turn++) { double next=1-z; z=x; x=next; }
            require(Math.abs(rotated.centreX()-x)<.00001 && Math.abs(rotated.centreZ()-z)<.00001,"Rotated offset lost its support centre");
        }
        for(BlockMirror mirror:BlockMirror.values()) {
            var reflected=CopingBlock.attachmentPose(state.mirror(mirror));
            double x=mirror==BlockMirror.FRONT_BACK ? 1-pose.centreX() : pose.centreX();
            double z=mirror==BlockMirror.LEFT_RIGHT ? 1-pose.centreZ() : pose.centreZ();
            require(Math.abs(reflected.centreX()-x)<.00001 && Math.abs(reflected.centreZ()-z)<.00001,"Mirrored translated fit lost its wall centre");
            require(state.mirror(mirror).mirror(mirror).equals(state),"Mirror cannot round-trip");
        }
        require(pose.gradientX()==0 && pose.gradientZ()==0 && Math.abs(pose.topCentreY()-CopingBlock.THICKNESS)<.00001,"Horizontal finial pose was pitched");
    }
    private static BlockState vertical(boolean broad,boolean left,Direction facing) {
        return broad ? block("glacium_slope_vertical_shallow_broad").getDefaultState().with(SlopeVerticalShallowBroadBlock.FACING,facing)
                .with(SlopeVerticalShallowBroadBlock.HAND,left ? SlopeVerticalShallowBroadBlock.Handedness.LEFT : SlopeVerticalShallowBroadBlock.Handedness.RIGHT)
                : block("glacium_slope_vertical_shallow_narrow").getDefaultState().with(SlopeVerticalShallowNarrowBlock.FACING,facing)
                .with(SlopeVerticalShallowNarrowBlock.HAND,left ? SlopeVerticalShallowNarrowBlock.Handedness.LEFT : SlopeVerticalShallowNarrowBlock.Handedness.RIGHT);
    }
    private static Block block(String path) { return Registries.BLOCK.get(new Identifier("erydon",path)); }
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
