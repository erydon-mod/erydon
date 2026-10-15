package com.oliver.erydon.block;

import com.oliver.erydon.item.CopingItem;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.Registries;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.RaycastContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Both registered designs must share placement rules while retaining their own physical dimensions. */
final class GrandCopingPlacementLaunchChecks {
    static void run(CopingBlock original,CopingBlock grand) throws Exception {
        var counts=new java.util.EnumMap<CopingDesign,Integer>(CopingDesign.class);
        for(var block:Registries.BLOCK) if(block instanceof CopingBlock coping) counts.merge(coping.design(),1,Integer::sum);
        for(var design:CopingDesign.values()) require(counts.get(design)==135,"Incomplete coping design registration: "+design);
        require(original.design()==CopingDesign.GEORGIAN && grand.design()==CopingDesign.GEORGIAN_GRAND,"Existing coping was replaced by Grand");
        require(original.asItem() instanceof CopingItem && grand.asItem() instanceof CopingItem,"Grand lost normal placement handling");
        int states=0,placements=0,mixed=0,rays=0;
        for(var profile:CopingBlock.Surface.values()) for(Direction facing:Direction.Type.HORIZONTAL)
            for(boolean offset:new boolean[]{false,true}) for(boolean water:new boolean[]{false,true}) {
                BlockState before=original.getDefaultState().with(CopingBlock.SURFACE,profile).with(CopingBlock.FACING,facing)
                        .with(CopingBlock.OFFSET,offset).with(CopingBlock.WATERLOGGED,water);
                BlockState after=grand.getDefaultState().with(CopingBlock.SURFACE,profile).with(CopingBlock.FACING,facing)
                        .with(CopingBlock.OFFSET,offset).with(CopingBlock.WATERLOGGED,water);
                var view=CopingPlacementLaunchProbe.view(Map.of(BlockPos.ORIGIN,after));
                var shape=after.getOutlineShape(view,BlockPos.ORIGIN);
                require(!shape.isEmpty() && shape==after.getCollisionShape(view,BlockPos.ORIGIN),"Grand collision diverged from targeting");
                require(shape==after.getOutlineShape(view,BlockPos.ORIGIN),"Unjoined Grand outlines are rebuilt per query");
                require(before.getOutlineShape(CopingPlacementLaunchProbe.view(Map.of(BlockPos.ORIGIN,before)),BlockPos.ORIGIN)!=shape,
                        "Old and Grand designs share the same cached outline");
                require(after.getFluidState().getFluid()==(water ? Fluids.WATER : Fluids.EMPTY),"Grand waterlogging changed");
                require(grand.getBreakParticleShape(after).getBoundingBoxes().size()==1,"Grand break particles use a detailed mesh");
                for(var rotation:BlockRotation.values()) require(after.rotate(rotation).rotate(rotation==BlockRotation.CLOCKWISE_90
                        ? BlockRotation.COUNTERCLOCKWISE_90 : rotation==BlockRotation.COUNTERCLOCKWISE_90 ? BlockRotation.CLOCKWISE_90 : rotation)==after,
                        "Grand rotation failed to preserve its saved profile");
                for(var mirror:BlockMirror.values()) require(after.mirror(mirror).mirror(mirror)==after,"Grand mirror lost its handed profile");
                var pose=CopingBlock.attachmentPose(after);
                double middle=(profile.start+profile.end)/2;
                require(Math.abs(pose.topCentreY()-profile.height(middle)-.5/Math.sqrt(1+profile.gradient()*profile.gradient()))<.000001,
                        "Grand attachment still uses the thin coping height");
                states++;
            }
        for(Direction facing:Direction.Type.HORIZONTAL) {
            for(String supportName:List.of("glacium_block","glacium_slope","glacium_slope_shallow_lower","glacium_slope_shallow_upper",
                    "glacium_slope_steep_lower","glacium_slope_steep_upper","glacium_slope_vertical")) {
                BlockState support=Registries.BLOCK.get(new Identifier("erydon",supportName)).getDefaultState();
                if(support.contains(CopingBlock.FACING)) support=support.with(CopingBlock.FACING,facing);
                var view=CopingPlacementLaunchProbe.view(Map.of(BlockPos.ORIGIN,support));
                for(boolean water:new boolean[]{false,true}) {
                    BlockState a=original.forSupport(support,view,BlockPos.ORIGIN,water,false);
                    BlockState b=grand.forSupport(support,view,BlockPos.ORIGIN,water,false);
                    require(a!=null && b!=null && a.get(CopingBlock.SURFACE)==b.get(CopingBlock.SURFACE)
                            && a.get(CopingBlock.FACING)==b.get(CopingBlock.FACING),"Grand selected a different support profile");
                    placements++;
                }
            }
            BlockState slab=Blocks.STONE_SLAB.getDefaultState().with(SlabBlock.TYPE,SlabType.BOTTOM);
            BlockState half=grand.forSupport(slab,CopingPlacementLaunchProbe.view(Map.of(BlockPos.ORIGIN,slab)),BlockPos.ORIGIN,false,false)
                    .with(CopingBlock.FACING,facing);
            require(half.get(CopingBlock.SURFACE)==CopingBlock.Surface.FLAT_HALF,"Grand missed a bottom slab's true support height");
            BlockState flat=half.with(CopingBlock.SURFACE,CopingBlock.Surface.FLAT);
            var fullShape=flat.getOutlineShape(CopingPlacementLaunchProbe.view(Map.of(BlockPos.ORIGIN,flat)),BlockPos.ORIGIN);
            var halfShape=half.getOutlineShape(CopingPlacementLaunchProbe.view(Map.of(BlockPos.ORIGIN,half)),BlockPos.ORIGIN);
            require(Math.abs(fullShape.getMax(Direction.Axis.Y)-.5)<.000001,"Grand is not half a block tall");
            require(!VoxelShapes.matchesAnywhere(fullShape.offset(0,-.5,0),halfShape,BooleanBiFunction.NOT_SAME),"Half-height Grand changed its silhouette");
            rays+=rayProof(grand,flat,Blocks.STONE.getDefaultState())+rayProof(grand,half,slab);
            for(var a:CopingBlock.Surface.values()) for(var b:CopingBlock.Surface.values()) {
                var first=grand.getDefaultState().with(CopingBlock.SURFACE,a).with(CopingBlock.FACING,facing);
                var second=original.getDefaultState().with(CopingBlock.SURFACE,b).with(CopingBlock.FACING,facing);
                for(BlockPos next:List.of(new BlockPos(1,0,0),new BlockPos(0,0,1))) {
                    var view=CopingPlacementLaunchProbe.view(Map.of(BlockPos.ORIGIN,first,next,second));
                    require(CopingConnections.mask(view,first,BlockPos.ORIGIN)==0 && CopingConnections.mask(view,second,next)==0,
                            "Different coping designs silently joined and removed their free ends");
                    mixed++;
                }
            }
            for(var surface:CopingBlock.Surface.values()) if(surface.aligned() || surface==CopingBlock.Surface.FLAT) {
                var joins=new CopingConnections.Joins(0);
                var a=CopingHorizontalShape.shape(CopingDesign.GEORGIAN,surface,facing,joins);
                var b=CopingHorizontalShape.shape(CopingDesign.GEORGIAN_GRAND,surface,facing,joins);
                require(a!=b && Math.abs(a.getMax(Direction.Axis.Y)-.225)<.000001
                        && Math.abs(b.getMax(Direction.Axis.Y)-.5)<.000001,"Joined shape cache conflated coping designs");
            }
        }
        BlockState one=grand.getDefaultState(),two=Registries.BLOCK.get(new Identifier("erydon","aterzon_coping_georgian_grand")).getDefaultState();
        var neighbours=CopingPlacementLaunchProbe.view(Map.of(BlockPos.ORIGIN,one,BlockPos.ORIGIN.east(),two));
        require((CopingConnections.mask(neighbours,one,BlockPos.ORIGIN)&CopingConnections.EAST)!=0,
                "Grand copings of different stone materials stopped joining");
        com.oliver.erydon.client.model.GrandCopingMeshLaunchChecks.run(grand);
        System.out.println("ERYDON_GRAND_COPING_PLACEMENT_OK registered=135 original=135 states="+states+" autoFits="+placements
                +" differentDesignPairs="+mixed+" ownedRays="+rays+" caches=designScoped halfHeight=exact");
    }

    private static int rayProof(CopingBlock grand,BlockState state,BlockState support) {
        BlockPos owner=BlockPos.ORIGIN.up(); var entity=new net.minecraft.entity.MarkerEntity(net.minecraft.entity.EntityType.MARKER,null);
        var view=CopingPlacementLaunchProbe.view(Map.of(owner,state,BlockPos.ORIGIN,support));
        double y=state.get(CopingBlock.SURFACE)==CopingBlock.Surface.FLAT_HALF ? .75 : 1.25;
        var rays=List.of(new Vec3d[]{new Vec3d(.5,3,.5),new Vec3d(.5,-1,.5)},
                new Vec3d[]{new Vec3d(-2,y,.5),new Vec3d(2,y,.5)},
                new Vec3d[]{new Vec3d(-2,y,-.16),new Vec3d(2,y,-.16)});
        for(var ray:rays) {
            var hit=view.raycast(new RaycastContext(ray[0],ray[1],RaycastContext.ShapeType.OUTLINE,RaycastContext.FluidHandling.NONE,entity));
            require(hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(owner),"Grand's taller body/overhang could not be targeted through its owning cell");
        }
        return rays.size();
    }
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
