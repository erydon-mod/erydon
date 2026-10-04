package com.oliver.erydon.block;

import com.oliver.erydon.item.CopingItem;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.Registries;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Real Minecraft registries/state hooks and the actual mixed-in raycast entry point. */
public final class CopingPlacementLaunchProbe implements PreLaunchEntrypoint {
    @Override public void onPreLaunch() {
        try {
            SharedConstants.createGameVersion(); Bootstrap.initialize();
            com.oliver.erydon.compat.daedalon.CapitalDebugControlsLaunchChecks.run();
            // Exercise the production registration matrix without loading unrelated architectural families.
            CopingBlocks.register(source -> Blocks.STONE, (name,block) -> {
                Identifier id=new Identifier("erydon",name);
                net.minecraft.registry.Registry.register(Registries.BLOCK,id,block);
                net.minecraft.registry.Registry.register(Registries.ITEM,id,new CopingItem(block,new net.minecraft.item.Item.Settings()));
            });
            fixture("glacium_block",new Block(Block.Settings.copy(Blocks.STONE)));
            fixture("glacium_slope",new SlopeBlock(Block.Settings.copy(Blocks.STONE)));
            fixture("glacium_slope_shallow_lower",new ShallowSlopeBlock(Block.Settings.copy(Blocks.STONE),ShallowSlopeBlock.Variant.LOWER));
            fixture("glacium_slope_shallow_upper",new ShallowSlopeBlock(Block.Settings.copy(Blocks.STONE),ShallowSlopeBlock.Variant.UPPER));
            fixture("glacium_slope_steep_lower",new SlopeSteepBlock(Block.Settings.copy(Blocks.STONE),SlopeSteepBlock.Variant.LOWER));
            fixture("glacium_slope_steep_upper",new SlopeSteepBlock(Block.Settings.copy(Blocks.STONE),SlopeSteepBlock.Variant.UPPER));
            fixture("glacium_slope_vertical",new SlopeVerticalBlock(Block.Settings.copy(Blocks.STONE)));
            fixture("glacium_slope_vertical_shallow_broad",new SlopeVerticalShallowBroadBlock(Block.Settings.copy(Blocks.STONE)));
            fixture("glacium_slope_vertical_shallow_narrow",new SlopeVerticalShallowNarrowBlock(Block.Settings.copy(Blocks.STONE)));
            // Minecraft normally initialises these caches after mod registration, later than preLaunch.
            for (Block block:Registries.BLOCK) if (Registries.BLOCK.getId(block).getNamespace().equals("erydon")) {
                block.getStateManager().getStates().forEach(BlockState::initShapeCache);
            }
            com.oliver.erydon.client.model.SlopeShapeLaunchChecks.run();
            com.oliver.erydon.client.model.CopingMeshProjectionLaunchChecks.run();
            com.oliver.erydon.client.model.SynapheiaCubeConnectionsLaunchChecks.run();
            com.oliver.erydon.migration.ReiSearchLaunchChecks.run();
            com.oliver.erydon.migration.ViewerSearchLaunchChecks.run();
            long registered=Registries.BLOCK.stream().filter(block -> block instanceof CopingBlock).count();
            require(registered==135,"Expected all 135 material/finish copings, got "+registered);
            for (Block block:Registries.BLOCK) if (block instanceof CopingBlock) {
                require(block.asItem() instanceof CopingItem,"Missing coping placement item");
                require(Registries.BLOCK.getId(block).equals(Registries.ITEM.getId(block.asItem())),"Block/item ID mismatch");
            }
            CopingBlock coping=(CopingBlock)block("glacium_coping_georgian");
            CopingHorizontalPlacementLaunchChecks.run(coping);
            mixedJoins(coping);
            steepSupportRemoval(coping);
            String[] supports={"glacium_block","glacium_slope","glacium_slope_shallow_lower",
                    "glacium_slope_shallow_upper","glacium_slope_steep_lower","glacium_slope_steep_upper"};
            var rayEntity=new net.minecraft.entity.MarkerEntity(net.minecraft.entity.EntityType.MARKER,null);
            int cases=0;
            for (Direction facing:Direction.Type.HORIZONTAL) for (int index=0;index<supports.length;index++) {
                BlockState support=block(supports[index]).getDefaultState();
                if (support.contains(CopingBlock.FACING)) support=support.with(CopingBlock.FACING,facing);
                Map<BlockPos,BlockState> states=new HashMap<>(); states.put(BlockPos.ORIGIN,support);
                BlockView view=view(states);
                for (boolean water:new boolean[]{false,true}) {
                    BlockState placed=coping.forSupport(support,view,BlockPos.ORIGIN,water,false);
                    require(placed!=null && placed.get(CopingBlock.SURFACE)==CopingBlock.Surface.values()[index],"Wrong automatically selected profile");
                    require(placed.get(CopingBlock.FACING)==(index==0 ? Direction.EAST : facing),"Wrong slope direction");
                    require((placed.getFluidState().getFluid()==Fluids.WATER)==water,"Lost waterlogging");
                    var shape=placed.getOutlineShape(view,BlockPos.ORIGIN.up());
                    require(shape==placed.getCollisionShape(view,BlockPos.ORIGIN.up()),"Outline/collision diverged");
                    for (BlockRotation rotation:BlockRotation.values()) {
                        require(placed.rotate(rotation).get(CopingBlock.FACING)==rotation.rotate(placed.get(CopingBlock.FACING)),"Wrong Axiom/world rotation");
                    }
                    for (BlockMirror mirror:BlockMirror.values()) {
                        require(placed.mirror(mirror).mirror(mirror).equals(placed),"Mirror is not reversible");
                    }
                    cases++;
                }
            }
            for (Direction facing:Direction.Type.HORIZONTAL) {
                BlockPos upper=new BlockPos(0,0,0),lower=upper.up();
                BlockState upperSlope=block("glacium_slope_steep_upper").getDefaultState().with(SlopeSteepBlock.FACING,facing);
                BlockState lowerSlope=block("glacium_slope_steep_lower").getDefaultState().with(SlopeSteepBlock.FACING,facing);
                Map<BlockPos,BlockState> states=new HashMap<>(); states.put(upper,upperSlope); states.put(lower,lowerSlope);
                BlockView view=view(states);
                var target=CopingItem.target(view,upper);
                require(target!=null && target.offset() && target.pos().equals(upper.up().offset(facing)),"Stacked steep upper has no usable placement cell");
                BlockState upperCap=coping.forSupport(upperSlope,view,upper,false,true);
                BlockState lowerCap=coping.forSupport(lowerSlope,view,lower,false,false);
                states.put(target.pos(),upperCap); states.put(lower.up(),lowerCap);
                require(CopingBlock.supportPos(upperCap,target.pos()).equals(upper),"Offset cap lost its actual support");
                require((CopingConnections.mask(view,lowerCap,lower.up())&CopingConnections.EAST)!=0,"Steep lower cannot join offset upper");
                require((CopingConnections.mask(view,upperCap,target.pos())&CopingConnections.WEST)!=0,"Offset upper cannot join steep lower");
                // Aim through the visible strip's centre; thickening a pitched
                // cap shifts that centre downhill along its surface normal.
                double downhill=.25+CopingBlock.THICKNESS/Math.sqrt(5);
                Vec3d rayPoint=Vec3d.ofCenter(upper).add(facing.getOffsetX()*downhill,0,facing.getOffsetZ()*downhill);
                Vec3d start=new Vec3d(rayPoint.x,4,rayPoint.z),end=new Vec3d(rayPoint.x,-2,rayPoint.z);
                var hit=view.raycast(new RaycastContext(start,end,RaycastContext.ShapeType.OUTLINE,RaycastContext.FluidHandling.NONE,rayEntity));
                require(hit.getBlockPos().equals(target.pos()),"Pitched coping cannot be selected/broken: "+hit.getBlockPos()+" expected "+target.pos());
                require(CopingItem.target(view,upper)==null,"An already placed offset cap can be overwritten");
                for (BlockState changed:new BlockState[]{upperSlope.with(SlopeSteepBlock.FACING,facing.rotateYClockwise()),
                        upperSlope.with(SlopeSteepBlock.SHAPE,SlopeSteepBlock.SlopeShape.INNER_LEFT)}) {
                    var updated=coping.getStateForNeighborUpdate(upperCap,facing.getOpposite(),changed,
                            access(states,changed),target.pos(),upper);
                    require(updated==upperCap,"Offset cap changed after losing its matching support");
                }
                cases+=3;
            }
            Map<BlockPos,BlockState> states=new HashMap<>();
            BlockState ramp=block("glacium_slope").getDefaultState().with(SlopeBlock.FACING,Direction.EAST);
            states.put(BlockPos.ORIGIN,ramp);
            BlockState cap=coping.forSupport(ramp,view(states),BlockPos.ORIGIN,false,false);
            states.put(BlockPos.ORIGIN.up(),cap);
            var selected=view(states).raycast(new RaycastContext(new Vec3d(.75,3,.5),new Vec3d(.75,-1,.5),
                    RaycastContext.ShapeType.OUTLINE,RaycastContext.FluidHandling.NONE,rayEntity));
            require(selected.getBlockPos().equals(BlockPos.ORIGIN.up()),"Standard pitched cap cannot be selected");
            require(coping.forSupport(ramp.with(SlopeBlock.SHAPE,SlopeBlock.SlopeShape.INNER_LEFT),view(states),BlockPos.ORIGIN,false,false)==null,"Non-planar slope was treated as planar");
            var changed=coping.getStateForNeighborUpdate(cap,Direction.DOWN,Blocks.STONE.getDefaultState(),
                    access(states,Blocks.STONE.getDefaultState()),BlockPos.ORIGIN.up(),BlockPos.ORIGIN);
            require(changed.get(CopingBlock.SURFACE)==CopingBlock.Surface.FLAT,"Cap did not follow a changed support");
            for (var profile:CopingBlock.Surface.values()) for(Direction facing:Direction.Type.HORIZONTAL)
                for(boolean offset:new boolean[]{false,true}) {
                    BlockState unsupported=coping.getDefaultState().with(CopingBlock.SURFACE,profile)
                            .with(CopingBlock.FACING,facing).with(CopingBlock.OFFSET,offset).with(CopingBlock.WATERLOGGED,true);
                    for(Direction direction:Direction.values()) {
                        var retained=coping.getStateForNeighborUpdate(unsupported,direction,Blocks.AIR.getDefaultState(),
                                access(Map.of(),Blocks.AIR.getDefaultState()),BlockPos.ORIGIN,BlockPos.ORIGIN.offset(direction));
                        require(retained==unsupported,"Unsupported coping changed its profile or waterlogging");
                    }
                    require(coping.getBreakParticleShape(unsupported).getBoundingBoxes().size()==1,
                            "Coping debris still uses the detailed slope outline");
                    require(profile.aligned() || coping.getBreakParticleShape(unsupported).getBoundingBox().equals(
                            coping.getOutlineShape(unsupported,view(Map.of()),BlockPos.ORIGIN,net.minecraft.block.ShapeContext.absent()).getBoundingBox()),
                            "Coping debris does not follow its visible offset/profile");
                    if(profile.aligned()) {
                        var box=coping.getBreakParticleShape(unsupported).getBoundingBox();
                        require(box.maxX-box.minX<=1.00001 && box.maxZ-box.minZ<=1.00001,"Aligned cap debris became excessive");
                    }
                    require(coping.canPlaceAt(unsupported,null,BlockPos.ORIGIN),"Coping still requires support");
                }
            System.out.println("ERYDON_COPING_OK: registered="+registered+" support/transform/water/stack/raycast cases="+(cases+3));
            System.out.println("ERYDON_COPING_FREE_OK: "+(CopingBlock.Surface.values().length*48)+" unsupported neighbour updates, "+(CopingBlock.Surface.values().length*8)+" profile/facing/offset particle bounds");
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }

    private static void steepSupportRemoval(CopingBlock coping) throws ReflectiveOperationException {
        // Support replacement must use Minecraft's inherited hook. The former
        // override broke offset owners before their retention update could run.
        var hook=SlopeSteepBlock.class.getMethod("onStateReplaced",BlockState.class,
                net.minecraft.world.World.class,BlockPos.class,BlockState.class,boolean.class);
        require(hook.getDeclaringClass()!=SlopeSteepBlock.class,
                "Steep support replacement still has a coping destruction callback");
        for(Direction facing:Direction.Type.HORIZONTAL) {
            BlockState cap=coping.getDefaultState().with(CopingBlock.SURFACE,CopingBlock.Surface.STEEP_UPPER)
                    .with(CopingBlock.FACING,facing).with(CopingBlock.OFFSET,true).with(CopingBlock.WATERLOGGED,true);
            BlockPos owner=BlockPos.ORIGIN.up().offset(facing);
            Map<BlockPos,BlockState> states=Map.of(owner,cap);
            BlockState retained=coping.getStateForNeighborUpdate(cap,facing.getOpposite(),Blocks.AIR.getDefaultState(),
                    access(states,Blocks.AIR.getDefaultState()),owner,BlockPos.ORIGIN);
            require(retained==cap,"Removed steep support changed its offset upper coping");
            var free=coping.getOutlineShape(cap,null,owner,net.minecraft.block.ShapeContext.absent());
            require(retained.getOutlineShape(view(states),owner)==free
                            && retained.getCollisionShape(view(states),owner)==free,
                    "Unsupported offset steep-upper outline/collision lost its saved pose");
            require(CopingBlock.supportPos(retained,owner).equals(BlockPos.ORIGIN)
                            && retained.getFluidState().getFluid()==Fluids.WATER,
                    "Removed steep support lost coping ownership or waterlogging");
        }
        System.out.println("ERYDON_COPING_STEEP_SUPPORT_REMOVAL_OK: inherited support hook and four unsupported offset upper profiles retain pose, collision and waterlogging");
    }

    private static void mixedJoins(CopingBlock coping) {
        int joins=0;
        for(Direction facing:Direction.Type.HORIZONTAL) for(var a:CopingBlock.Surface.values()) for(var b:CopingBlock.Surface.values())
            for(int x=-1;x<=1;x++) for(int y=-1;y<=1;y++) {
                if(x==0 && y==0) continue;
                BlockPos otherPos=new BlockPos(x*facing.getOffsetX(),y,x*facing.getOffsetZ());
                int expected=CopingConnections.joins(a,facing,b,facing,otherPos.getX(),y,otherPos.getZ());
                if(expected==0) continue;
                BlockState first=coping.getDefaultState().with(CopingBlock.SURFACE,a).with(CopingBlock.FACING,facing);
                BlockState other=coping.getDefaultState().with(CopingBlock.SURFACE,b).with(CopingBlock.FACING,facing);
                BlockView world=view(Map.of(BlockPos.ORIGIN,first,otherPos,other));
                var result=CopingConnections.resolve(world,first,BlockPos.ORIGIN);
                require(result.mask()==expected,"Wrong mixed coping connection: "+a+" -> "+b+" "+facing);
                require(CopingConnections.mask(world,other,otherPos)!=0,"Missing reciprocal coping joint");
                require((expected&CopingConnections.WEST)==0 || result.westGradient()==b.gradient(),"Wrong uphill mitre incline");
                require((expected&CopingConnections.EAST)==0 || result.eastGradient()==b.gradient(),"Wrong downhill mitre incline");
                joins++;
            }
        for(Direction facing:Direction.Type.HORIZONTAL) {
            BlockPos upperOwner=BlockPos.ORIGIN.offset(facing),flatOwner=upperOwner.down();
            BlockState upper=coping.getDefaultState().with(CopingBlock.FACING,facing)
                    .with(CopingBlock.SURFACE,CopingBlock.Surface.STEEP_UPPER).with(CopingBlock.OFFSET,true);
            BlockState flat=coping.getDefaultState();
            BlockView world=view(Map.of(upperOwner,upper,flatOwner,flat));
            require(CopingConnections.mask(world,upper,upperOwner)==CopingConnections.EAST,"Offset steep/flat joint lost");
            require(CopingConnections.mask(world,flat,flatOwner)==CopingConnections.WEST,"Flat cap missed the offset owner's physical edge");
            joins++;
        }
        System.out.println("ERYDON_COPING_JOINS_OK: "+joins+" reciprocal surface/height/facing/offset cases");
    }
    private static void fixture(String path,Block block) {
        Identifier id=new Identifier("erydon",path);
        net.minecraft.registry.Registry.register(Registries.BLOCK,id,block);
        net.minecraft.registry.Registry.register(Registries.ITEM,id,new net.minecraft.item.BlockItem(block,new net.minecraft.item.Item.Settings()));
    }
    private static Block block(String path) {
        Block block=Registries.BLOCK.get(new Identifier("erydon",path));
        require(block!=Blocks.AIR,"Missing registered block "+path); return block;
    }
    static BlockView view(Map<BlockPos,BlockState> states) {
        return (BlockView)Proxy.newProxyInstance(BlockView.class.getClassLoader(),new Class[]{BlockView.class},handler(states));
    }
    private static net.minecraft.world.WorldAccess access(Map<BlockPos,BlockState> states,BlockState replacement) {
        Map<BlockPos,BlockState> copy=new HashMap<>(states); copy.put(BlockPos.ORIGIN,replacement);
        return (net.minecraft.world.WorldAccess)Proxy.newProxyInstance(BlockView.class.getClassLoader(),new Class[]{net.minecraft.world.WorldAccess.class},handler(copy));
    }
    private static InvocationHandler handler(Map<BlockPos,BlockState> states) {
        return (proxy,method,args) -> {
            return switch(method.getName()) {
                case "getBlockState" -> states.getOrDefault(args[0],Blocks.AIR.getDefaultState());
                case "getFluidState" -> states.getOrDefault(args[0],Blocks.AIR.getDefaultState()).getFluidState();
                case "scheduleFluidTick" -> null;
                case "getBlockEntity" -> null;
                case "getHeight" -> 384;
                case "getBottomY" -> -64;
                default -> {
                    if (method.isDefault()) yield InvocationHandler.invokeDefault(proxy,method,args);
                    throw new UnsupportedOperationException(method.toString());
                }
            };
        };
    }
    private static void require(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
