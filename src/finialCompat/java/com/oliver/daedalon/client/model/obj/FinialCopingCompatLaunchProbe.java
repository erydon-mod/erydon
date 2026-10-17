package com.oliver.daedalon.client.model.obj;

import com.google.gson.JsonParser;
import com.oliver.daedalon.block.FinialBlock;
import com.oliver.daedalon.block.FinialRaycast;
import com.oliver.daedalon.block.FinialSupport;
import com.oliver.daedalon.block.FinialSupportPlacement;
import com.oliver.daedalon.block.FixedDecorBlock;
import com.oliver.daedalon.block.TwoSizeDecorBlock;
import com.oliver.erydon.block.CopingBlock;
import com.oliver.erydon.block.CopingDesign;
import com.oliver.erydon.block.LayerBlock;
import com.oliver.erydon.block.LayerMultifaceBlock;
import com.oliver.erydon.block.ShallowSlopeBlock;
import com.oliver.erydon.block.SlopeBlock;
import com.oliver.erydon.block.SlopeSteepBlock;
import com.oliver.erydon.block.SlopeVerticalBlock;
import com.oliver.erydon.block.SlopeVerticalShallowBroadBlock;
import com.oliver.erydon.block.SlopeVerticalShallowNarrowBlock;
import com.oliver.erydon.client.model.CopingFinialMeshContact;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.fabricmc.fabric.impl.client.indigo.renderer.helper.NormalHelper;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldView;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.RaycastContext;
import net.minecraft.util.hit.HitResult;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MarkerEntity;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Current source-to-source mounting proof, independent of installed companion JARs or a client model manager. */
public final class FinialCopingCompatLaunchProbe implements PreLaunchEntrypoint {
    private static final BlockPos COPING_OWNER = new BlockPos(14, 64, -23);
    private static final BlockPos FINIAL_OWNER = COPING_OWNER.up();
    private static final double EPSILON = 0.00001;
    private static int rawMeshContacts,extrapolatedCorners;
    private static int outsideOwnerRaycasts;
    private static int replayedBaseQuads;
    private static MarkerEntity rayEntity;

    @Override public void onPreLaunch() {
        try {
            SharedConstants.createGameVersion();
            Bootstrap.initialize();
            require(!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("daedalon"),
                    "This proof must use selected current sources, not an installed Daedalon JAR");
            if (RendererAccess.INSTANCE.getRenderer() == null) {
                RendererAccess.INSTANCE.registerRenderer(IndigoRenderer.INSTANCE);
            }
            rayEntity=new MarkerEntity(EntityType.MARKER,null);
            CopingBlock coping = new CopingBlock(Block.Settings.copy(Blocks.STONE));
            register("erydon:glacium_coping_georgian", coping);
            coping.getStateManager().getStates().forEach(BlockState::initShapeCache);
            CopingBlock grand = new CopingBlock(Block.Settings.copy(Blocks.STONE),CopingDesign.GEORGIAN_GRAND);
            register("erydon:glacium_coping_georgian_grand",grand);
            grand.getStateManager().getStates().forEach(BlockState::initShapeCache);
            List<CopingBlock> designs=List.of(coping,grand);
            long before = retainedHeapBytes();
            List<FinialBlock> finials = registerActualFinialCatalogue();
            for (FinialBlock finial : finials) finial.getStateManager().getStates().forEach(BlockState::initShapeCache);
            long after = retainedHeapBytes();
            int stateCount = finials.stream().mapToInt(block -> block.getStateManager().getStates().size()).sum();
            int packedStates = 280 * 2 * 4 * FinialSupport.Profile.values().length;
            require(finials.size() == 280 && stateCount == packedStates && FinialSupport.Profile.values().length==51,
                    "Finial IDs or packed state space changed: blocks=" + finials.size() + ", states=" + stateCount);
            System.out.println("ERYDON_FINIAL_STATE_SPACE_OK: 280 actual catalogue IDs, " + stateCount
                    + " states with 51 saved support profiles and three properties; prior 32-profile state space for this catalogue=71680"
                    + "; retained heap delta=" + (after - before) + " bytes");
            for (FinialBlock finial : finials) {
                require(finial.getStateManager().getProperties().size() == 3,
                        "Mounting metadata added redundant state properties");
            }
            List<FinialBlock> styles = finials.stream().filter(block ->
                    Registries.BLOCK.getId(block).getPath().startsWith("bronze_")).toList();
            require(styles.size() == 5, "Missing finial style");
            int mounted = 0, invalidAlignedOffsets = 0;
            Box bounds = new Box(0, 0, 0, 1, 1, 1);
            for (CopingBlock design:designs)
            for (FinialBlock finial : styles) for (TwoSizeDecorBlock.Size size : TwoSizeDecorBlock.Size.values()) {
                for (CopingBlock.Surface surface : CopingBlock.Surface.values()) {
                    for (Direction facing : Direction.Type.HORIZONTAL) {
                        BlockState support = design.getDefaultState().with(CopingBlock.SURFACE, surface)
                                .with(CopingBlock.FACING, facing);
                        BlockState fitted = assertMount(finial, size, support);
                        bounds = bounds.union(fitted.getCollisionShape(view(Map.of(COPING_OWNER, support)),
                                FINIAL_OWNER).getBoundingBox());
                        mounted++;
                        if (surface == CopingBlock.Surface.STEEP_UPPER) {
                            BlockState offset = support.with(CopingBlock.OFFSET, true);
                            BlockState offsetFitted = assertMount(finial, size, offset);
                            require(offsetFitted.get(FinialBlock.SUPPORT) == FinialSupport.Profile.find("steep_upper_offset",true,
                                            design.design()==CopingDesign.GEORGIAN_GRAND),
                                    "Steep owner offset was not packed into saved support metadata");
                            bounds = bounds.union(offsetFitted.getCollisionShape(view(Map.of(COPING_OWNER, offset)),
                                    FINIAL_OWNER).getBoundingBox());
                            mounted++;
                        }
                        if (surface.aligned()) {
                            // Real coping deliberately ignores OFFSET for horizontal diagonal fits.
                            BlockState invalidOffset = support.with(CopingBlock.OFFSET, true);
                            BlockState invalidFitted = assertMount(finial, size, invalidOffset);
                            require(FinialSupport.fit(invalidFitted, finial.style())
                                    .equals(FinialSupport.fit(fitted, finial.style())),
                                    "An irrelevant aligned coping OFFSET changed finial geometry");
                            invalidAlignedOffsets++;
                        }
                    }
                }
            }
            require(mounted == designs.size() * 5 * 2 * (CopingBlock.Surface.values().length + 1) * 4,
                    "Valid mounting matrix was incomplete");
            require(Double.isFinite(bounds.minX) && Double.isFinite(bounds.maxY)
                    && bounds.minX > -3 && bounds.minY > -4 && bounds.minZ > -3
                    && bounds.maxX < 4 && bounds.maxY < 4 && bounds.maxZ < 4,
                    "Saved mounting geometry exceeded its finite interaction envelope: " + bounds);
            int horizontalReach = (int)Math.ceil(Math.max(Math.max(-bounds.minX, bounds.maxX - 1),
                    Math.max(-bounds.minZ, bounds.maxZ - 1)));
            int below = (int)Math.ceil(-bounds.minY), above = (int)Math.ceil(bounds.maxY - 1);
            require(horizontalReach <= 2 && below <= 3 && above <= 2,
                    "Finial's outside-owner interaction search grew beyond its narrow proven envelope");
            System.out.println("ERYDON_FINIAL_COPING_SOURCE_OK: " + mounted + " valid real-class poses, "
                    + invalidAlignedOffsets + " irrelevant aligned-offset checks; both coping designs, half-height, all five styles/sizes/facings and transforms");
            require(replayedBaseQuads == (mounted + invalidAlignedOffsets) * 6,
                    "Actual fitted base replay matrix was incomplete");
            System.out.println("ERYDON_FINIAL_BASE_REPLAY_TAGS_OK: " + replayedBaseQuads
                    + " real FRAPI replayed quads retain OBJ ownership and variant material slot zero");
            System.out.println("ERYDON_FINIAL_OWNER_BOUNDS: " + bounds + "; neighbour reach=" + horizontalReach
                    + ", cells below=" + below + ", cells above=" + above);
            require(rawMeshContacts>=mounted,"Actual approved coping mesh did not support every mounting centre");
            require(extrapolatedCorners>0,"Fixture did not exercise the larger foot's coplanar overhang");
            System.out.println("ERYDON_FINIAL_RAW_MESH_CONTACT_OK: " + rawMeshContacts + " centre/foot samples inside actual "
                    + "raw tops; " + extrapolatedCorners + " overhanging corners share the same measured plane");
            require(outsideOwnerRaycasts>0,"Actual fitted mesh never exercised an outside-owner interaction");
            System.out.println("ERYDON_FINIAL_OUTSIDE_OWNER_RAYCAST_OK: " + outsideOwnerRaycasts
                    + " actual lower-base/overhang hits through Minecraft raycast from empty cells, with index removal verified");
            assertCombinedRaycast(coping,styles);
            directHalfHeightSupports(styles,designs);
            standaloneAndSlopeFallback(styles);
            directWallFallback(styles);
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static List<FinialBlock> registerActualFinialCatalogue() throws Exception {
        List<FinialBlock> blocks = new ArrayList<>();
        for (FixedDecorBlock.Style style : FixedDecorBlock.Style.values()) {
            if (!style.isFinial()) continue;
            String resource = "/data/daedalon/tags/blocks/" + style.idSuffix() + ".json";
            try (var stream = FinialCopingCompatLaunchProbe.class.getResourceAsStream(resource)) {
                require(stream != null, "Missing current source catalogue " + resource);
                var values = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonArray("values");
                require(values.size() == 56, "A finial style no longer has all 56 current IDs");
                for (var value : values) {
                    FinialBlock block = new FinialBlock(Block.Settings.copy(Blocks.STONE), style);
                    register(value.getAsString(), block);
                    blocks.add(block);
                }
            }
        }
        return blocks;
    }

    private static BlockState assertMount(FinialBlock finial, TwoSizeDecorBlock.Size size, BlockState coping) {
        BlockState state = FinialSupportPlacement.fitted(finial.getDefaultState().with(TwoSizeDecorBlock.SIZE, size),
                view(Map.of(COPING_OWNER, coping)), COPING_OWNER);
        require(state.get(FinialBlock.SUPPORT) != FinialSupport.Profile.NONE, "Actual coping did not attach the finial");
        require(state.get(FinialBlock.FACING) == coping.get(CopingBlock.FACING), "Coping facing was not retained");
        assertPose(FinialSupport.fit(state, finial.style()), CopingBlock.attachmentPose(coping), state.toString());
        assertRawMeshContact(state,finial,coping);
        for (BlockRotation rotation : BlockRotation.values()) {
            BlockState rotated = finial.rotate(state, rotation);
            assertPose(FinialSupport.fit(rotated, finial.style()), CopingBlock.attachmentPose(coping.rotate(rotation)),
                    "Rotated " + state + "/" + rotation);
        }
        for (BlockMirror mirror : BlockMirror.values()) {
            BlockState mirrored = finial.mirror(state, mirror);
            assertPose(FinialSupport.fit(mirrored, finial.style()), CopingBlock.attachmentPose(coping.mirror(mirror)),
                    "Mirrored " + state + "/" + mirror);
            require(finial.mirror(mirrored, mirror) == state, "Saved finial mirror is not reversible");
        }
        BlockView world = view(Map.of(COPING_OWNER, coping));
        var shape = finial.getCollisionShape(state, world, FINIAL_OWNER, ShapeContext.absent());
        require(shape == finial.getOutlineShape(state, world, FINIAL_OWNER, ShapeContext.absent()),
                "Outline and collision did not share the actual fitted geometry");
        assertActualMeshes(state, finial, shape.getBoundingBox());
        assertOutsideOwnerRaycast(state,coping,shape);
        assertActualClearance(state,coping,finial);
        return state;
    }

    private static void assertActualClearance(BlockState state,BlockState coping,FinialBlock finial) {
        require(FinialSupportPlacement.isClear(state,worldView(Map.of(COPING_OWNER,coping)),FINIAL_OWNER),
                "Actual rendered coping contact incorrectly blocked mounting "+state);
        var fit=FinialSupport.fit(state,finial.style());
        BlockPos obstruction=BlockPos.ofFloored(fit.centreX()+FINIAL_OWNER.getX(),fit.bodyY()+.5+FINIAL_OWNER.getY(),
                fit.centreZ()+FINIAL_OWNER.getZ());
        Map<BlockPos,BlockState> blocked=new HashMap<>(Map.of(COPING_OWNER,coping));
        blocked.put(obstruction,Blocks.STONE.getDefaultState());
        require(!FinialSupportPlacement.isClear(state,worldView(blocked),FINIAL_OWNER),
                "Actual finial body accepted an obstruction above its mounting plane");
    }

    private static void assertOutsideOwnerRaycast(BlockState state,BlockState coping,
            net.minecraft.util.shape.VoxelShape shape) {
        BlockView world=view(Map.of(COPING_OWNER,coping,FINIAL_OWNER,state));
        for(Box box:shape.getBoundingBoxes()) {
            double y=(box.minY+box.maxY)/2,z=(box.minZ+box.maxZ)/2;
            Vec3d start=new Vec3d(box.minX-.125,y,z).add(Vec3d.of(FINIAL_OWNER));
            Vec3d end=new Vec3d(box.maxX+.125,y,z).add(Vec3d.of(FINIAL_OWNER));
            var expected=shape.raycast(start,end,FINIAL_OWNER);
            if(expected==null) continue;
            BlockPos cell=BlockPos.ofFloored(expected.getPos());
            if(cell.equals(FINIAL_OWNER) || !world.getBlockState(cell).isAir()) continue;
            FinialRaycast.update(world,FINIAL_OWNER,state);
            var hit=FinialRaycast.closest(world,start,end,cell,Blocks.AIR.getDefaultState(),null);
            require(hit!=null && hit.getBlockPos().equals(FINIAL_OWNER),
                    "Actual indexed finial raycast lost its fitted owner from empty cell "+cell);
            close(hit.getPos().squaredDistanceTo(expected.getPos()),0,"Actual outside-owner raycast geometry");
            var minecraft=world.raycast(rayContext(start,end));
            require(minecraft.getType()==HitResult.Type.BLOCK && minecraft.getBlockPos().equals(FINIAL_OWNER),
                    "Combined actual mixins lost the finial during Minecraft raycast from empty cell "+cell);
            close(minecraft.getPos().squaredDistanceTo(expected.getPos()),0,"Minecraft outside-owner finial hit position");
            FinialRaycast.update(world,FINIAL_OWNER,Blocks.AIR.getDefaultState());
            require(FinialRaycast.closest(world,start,end,cell,Blocks.AIR.getDefaultState(),null)==null,
                    "Removed finial left an indexed outside-owner interaction");
            FinialRaycast.clearWorld(world);
            outsideOwnerRaycasts++;
            return;
        }
    }

    private static void assertCombinedRaycast(CopingBlock coping,List<FinialBlock> styles) {
        BlockPos capOwner=BlockPos.ORIGIN,finialOwner=new BlockPos(0,0,-1);
        BlockState cap=coping.getDefaultState().with(CopingBlock.SURFACE,CopingBlock.Surface.DIAGONAL)
                .with(CopingBlock.FACING,Direction.EAST);
        FinialBlock finial=styles.stream().max(java.util.Comparator.comparingDouble(block -> block.style().width())).orElseThrow();
        BlockState ornament=finial.getDefaultState().with(TwoSizeDecorBlock.SIZE,TwoSizeDecorBlock.Size.LARGE);
        BlockView world=view(Map.of(capOwner,cap,finialOwner,ornament));
        var capShape=cap.getOutlineShape(world,capOwner);
        var finialShape=ornament.getOutlineShape(world,finialOwner);
        FinialRaycast.update(world,finialOwner,ornament);
        boolean selectedCap=false,selectedFinial=false;
        for(boolean reverse:new boolean[]{false,true}) {
            // Both real shapes extend into this empty x=-1 column. The ordinary
            // vanilla owner shapes cannot produce either hit in the traversed cells.
            Vec3d start=new Vec3d(-.08,.1,reverse ? 2 : -2),end=new Vec3d(-.08,.1,reverse ? -2 : 2);
            var capHit=capShape.raycast(start,end,capOwner);
            var finialHit=finialShape.raycast(start,end,finialOwner);
            require(capHit!=null && finialHit!=null,"Combined ray did not meet both real overhanging shapes");
            var expected=capHit.getPos().squaredDistanceTo(start)<finialHit.getPos().squaredDistanceTo(start) ? capHit : finialHit;
            var actual=world.raycast(rayContext(start,end));
            require(actual.getType()==HitResult.Type.BLOCK && actual.getBlockPos().equals(expected.getBlockPos()),
                    "Coping and finial mixins did not compose to retain the nearest overhang");
            close(actual.getPos().squaredDistanceTo(expected.getPos()),0,"Combined ordinary raycast nearest position");
            selectedCap|=actual.getBlockPos().equals(capOwner); selectedFinial|=actual.getBlockPos().equals(finialOwner);
        }
        require(selectedCap && selectedFinial,"Combined Minecraft raycasts did not independently select both families");
        FinialRaycast.clearWorld(world);
        BlockPos vanillaOwner=new BlockPos(-1,0,2);
        BlockView obstructed=view(Map.of(capOwner,cap,finialOwner,ornament,vanillaOwner,Blocks.STONE.getDefaultState()));
        FinialRaycast.update(obstructed,finialOwner,ornament);
        var stoneHit=obstructed.raycast(rayContext(new Vec3d(-.08,.1,3.5),new Vec3d(-.08,.1,-2)));
        require(stoneHit.getType()==HitResult.Type.BLOCK && stoneHit.getBlockPos().equals(vanillaOwner),
                "Combined mixins replaced a nearer original vanilla hit");
        FinialRaycast.clearWorld(obstructed);
        System.out.println("ERYDON_COPING_FINIAL_MIXINS_COMPOSE_OK: both real mixins select indexed finials and coping "
                +"from empty traversed cells; forward/reverse choose the nearest family and retain a nearer vanilla hit");
    }

    private static RaycastContext rayContext(Vec3d start,Vec3d end) {
        return new RaycastContext(start,end,RaycastContext.ShapeType.OUTLINE,RaycastContext.FluidHandling.NONE,rayEntity);
    }

    private static void assertRawMeshContact(BlockState state,FinialBlock finial,BlockState coping) {
        var fit=FinialSupport.fit(state,finial.style());
        var top=CopingFinialMeshContact.top(coping);
        close(fit.planeY(),top.height(fit.centreX(),fit.centreZ()),"Actual editable coping top at mounting centre");
        require(top.contains(fit.centreX(),fit.centreZ()),"Finial mounting centre missed the actual coping top");
        rawMeshContacts++;
        double half=fit.baseWidth()/2;
        for(double localX:new double[]{-half,half}) for(double localZ:new double[]{-half,half}) {
            double x=fit.x(localX,localZ),z=fit.z(localX,localZ);
            close(fit.bottom(x,z),top.height(x,z),"Actual fitted foot against measured raw coping plane");
            if(top.contains(x,z)) rawMeshContacts++; else extrapolatedCorners++;
        }
    }

    private static void assertPose(FinialSupport.Fit fit, CopingBlock.AttachmentPose coping, String context) {
        require(fit.mounted(), "Fitted pose lost its support");
        close(fit.centreX(), coping.centreX(), context + " centreX");
        close(fit.centreZ(), coping.centreZ(), context + " centreZ");
        close(fit.planeY(), coping.topCentreY() - 1, context + " mounting plane");
        close(fit.gradientX(), coping.gradientX(), context + " gradientX");
        close(fit.gradientZ(), coping.gradientZ(), context + " gradientZ");
        close(Math.sin(fit.yaw()), Math.sin(coping.yawRadians()), context + " yaw sine");
        close(Math.cos(fit.yaw()), Math.cos(coping.yawRadians()), context + " yaw cosine");
        double half = fit.baseWidth() / 2;
        for (double x : new double[]{-half, half}) for (double z : new double[]{-half, half}) {
            double worldX = fit.x(x, z), worldZ = fit.z(x, z);
            double reference = coping.topCentreY() - 1 + coping.gradientX() * (worldX - coping.centreX())
                    + coping.gradientZ() * (worldZ - coping.centreZ());
            close(fit.bottom(worldX, worldZ), reference, context + " actual mounting corner");
            require(fit.bodyY() > reference, "Finial's level upper base intersected its lower support plane");
        }
    }

    private static void assertActualMeshes(BlockState state, FinialBlock finial, Box bounds) {
        FinialSupport.Fit fit = FinialSupport.fit(state, finial.style());
        int[] quads = {0};
        var base = FinialBaseMesh.forFit(fit);
        require(base == FinialBaseMesh.forFit(fit), "Fitted base did not reuse its actual cached mesh");
        var replay = RendererAccess.INSTANCE.getRenderer().meshBuilder();
        base.outputTo(replay.getEmitter());
        replay.build().forEach(quad -> {
            require(ShaderTerrainNormalBridge.isObjQuad(quad.tag()),
                    "Replayed fitted base lost Daedalon OBJ ownership: " + state + "/" + quad.tag());
            require(ShaderTerrainNormalBridge.decodeObjMaterialTag(quad.tag()) == 0,
                    "Replayed fitted base selected another material instead of the shared variant surface");
            for (int i = 0; i < 4; i++) {
                require(quad.hasNormal(i), "Replayed fitted base lost its actual shader normal");
                double x = quad.x(i), y = quad.y(i), z = quad.z(i);
                require(x >= bounds.minX - EPSILON && x <= bounds.maxX + EPSILON
                        && y >= bounds.minY - EPSILON && y <= bounds.maxY + EPSILON
                        && z >= bounds.minZ - EPSILON && z <= bounds.maxZ + EPSILON,
                        "Rendered fitted base exceeded real interaction bounds");
                require(Math.abs(y - fit.bodyY()) < EPSILON || Math.abs(y - fit.bottom(x, z)) < EPSILON,
                        "Actual base mesh did not follow the shared support plane");
            }
            quads[0]++;
            replayedBaseQuads++;
        });
        require(quads[0] == 6, "Fitted square base no longer has six actual quads");
        var emitter = RendererAccess.INSTANCE.getRenderer().meshBuilder().getEmitter();
        for (int i = 0; i < 4; i++) {
            emitter.pos(i, (float)(.5 + (i == 0 || i == 3 ? -1 : 1) * finial.style().width()),
                    i < 2 ? 0 : 2, .5F);
            emitter.normal(i, 0, 0, -1);
            emitter.uv(i, .5F, .5F);
        }
        emitter.nominalFace(Direction.NORTH);
        require(FinialMeshTransform.forState(state, finial).transform(emitter), "Finial body transform rejected geometry");
        close(emitter.y(0), fit.bodyY(), "Actual finial body foot");
        close(emitter.y(2) - emitter.y(0), state.get(TwoSizeDecorBlock.SIZE) == TwoSizeDecorBlock.Size.SMALL ? 1 : 2,
                "Actual finial upright body height");
        close(emitter.normalY(0), 0, "Actual ornament normal was pitched");
        float yawX=(float)Math.sin(fit.yaw()),yawZ=(float)-Math.cos(fit.yaw());
        int packed=NormalHelper.packNormal(yawX,0,yawZ);
        close(emitter.normalX(0),NormalHelper.unpackNormalX(packed),"Actual packed ornament yaw normal X");
        close(emitter.normalZ(0),NormalHelper.unpackNormalZ(packed),"Actual packed ornament yaw normal Z");
        double nx=emitter.normalX(0),nz=emitter.normalZ(0),length=Math.hypot(nx,nz);
        require(Math.abs(length-1)<=2.0/127,"Packed ornament normal lost its unit-vector length");
        double yawCosine=(nx*yawX+nz*yawZ)/length;
        require(yawCosine>=1-2.0/(127*127),"Packed ornament normal no longer points along the actual upright yaw");
    }

    private static void directHalfHeightSupports(List<FinialBlock> finials,List<CopingBlock> designs) {
        LayerBlock layer=new LayerBlock(Block.Settings.copy(Blocks.STONE));
        LayerMultifaceBlock multiface=new LayerMultifaceBlock(Block.Settings.copy(Blocks.STONE));
        register("erydon:glacium_layer",layer);
        register("erydon:glacium_layer_multiface",multiface);
        layer.getStateManager().getStates().forEach(BlockState::initShapeCache);
        multiface.getStateManager().getStates().forEach(BlockState::initShapeCache);
        BlockState bottom=Blocks.STONE_SLAB.getDefaultState().with(net.minecraft.block.SlabBlock.TYPE,net.minecraft.block.enums.SlabType.BOTTOM);
        BlockState top=bottom.with(net.minecraft.block.SlabBlock.TYPE,net.minecraft.block.enums.SlabType.TOP);
        BlockState doubled=bottom.with(net.minecraft.block.SlabBlock.TYPE,net.minecraft.block.enums.SlabType.DOUBLE);
        BlockState halfLayer=layer.getDefaultState().with(LayerBlock.LAYERS,4).with(LayerBlock.TOP,false);
        BlockState halfMultiface=multiface.getDefaultState().with(LayerMultifaceBlock.LAYERS,4)
                .with(LayerMultifaceBlock.DOWN,true).with(LayerMultifaceBlock.UP,false)
                .with(LayerMultifaceBlock.NORTH,false).with(LayerMultifaceBlock.SOUTH,false)
                .with(LayerMultifaceBlock.EAST,false).with(LayerMultifaceBlock.WEST,false);
        int direct=0,capped=0;
        for(FinialBlock finial:finials) for(var size:TwoSizeDecorBlock.Size.values()) {
            BlockState original=finial.getDefaultState().with(TwoSizeDecorBlock.SIZE,size);
            for(Direction facing:List.of(Direction.NORTH,Direction.WEST)) {
                BlockState refitted=original.with(FinialBlock.FACING,facing);
                for(BlockState support:List.of(bottom,top,doubled,halfLayer,halfMultiface)) {
                    refitted=FinialSupportPlacement.fitted(refitted,view(Map.of(COPING_OWNER,support)),COPING_OWNER);
                    require(refitted.get(FinialBlock.FACING)==facing,
                            "Real slab/layer refit changed copied or mirrored finial facing: "+support);
                }
            }
            for(BlockState support:List.of(bottom,top,doubled,halfLayer,halfMultiface)) {
                boolean half=support!=top && support!=doubled;
                BlockState fitted=FinialSupportPlacement.fitted(original,view(Map.of(COPING_OWNER,support)),COPING_OWNER);
                var fit=FinialSupport.fit(fitted,finial.style());
                require(fitted.get(FinialBlock.SUPPORT)==(half ? FinialSupport.Profile.HALF_HEIGHT : FinialSupport.Profile.NONE),
                        "Real horizontal support lost its saved height "+support);
                close(fit.bodyY(),half ? -.5 : 0,"Real slab/layer ornament foot");
                require(!fit.mounted(),"Horizontal slab placement added an unwanted fitted pedestal");
                require(FinialSupportPlacement.isClear(fitted,worldView(Map.of(COPING_OWNER,support)),FINIAL_OWNER),
                        "Actual horizontal support incorrectly blocked its touching finial");
                if(half) {
                    BlockState refreshed=FinialSupportPlacement.fitted(fitted,view(Map.of(COPING_OWNER,top)),COPING_OWNER);
                    require(refreshed.get(FinialBlock.SUPPORT)==FinialSupport.Profile.NONE,"Top-slab refit kept its lowered pose");
                    require(FinialSupportPlacement.fitted(fitted,view(Map.of()),COPING_OWNER)==fitted,
                            "Support removal discarded the saved half-height pose");
                    BlockView rayWorld=view(Map.of(COPING_OWNER,support,FINIAL_OWNER,fitted));
                    FinialRaycast.update(rayWorld,FINIAL_OWNER,fitted);
                    Vec3d point=Vec3d.of(FINIAL_OWNER).add(.5,-.45,.5);
                    Vec3d rayStart=point.add(0,.1,0),rayEnd=point.add(0,-.1,0);
                    var raw=fitted.getOutlineShape(rayWorld,FINIAL_OWNER).raycast(rayStart,rayEnd,FINIAL_OWNER);
                    var indexed=FinialRaycast.closest(rayWorld,rayStart,rayEnd,COPING_OWNER,support,null);
                    var hit=rayWorld.raycast(rayContext(rayStart,rayEnd));
                    var lowerBounds=fitted.getOutlineShape(rayWorld,FINIAL_OWNER).getBoundingBox();
                    Vec3d outsideStart=Vec3d.of(FINIAL_OWNER).add(lowerBounds.minX-.25,lowerBounds.minY+.125,fit.centreZ());
                    Vec3d outsideEnd=Vec3d.of(FINIAL_OWNER).add(lowerBounds.maxX+.25,lowerBounds.minY+.125,fit.centreZ());
                    var expected=fitted.getOutlineShape(rayWorld,FINIAL_OWNER).raycast(outsideStart,outsideEnd,FINIAL_OWNER);
                    require(expected!=null && BlockPos.ofFloored(expected.getPos()).getY()==COPING_OWNER.getY(),
                            "Lowered ornament witness missed the actual body below its owner cell");
                    var outsideHit=rayWorld.raycast(rayContext(outsideStart,outsideEnd));
                    require(outsideHit.getType()==HitResult.Type.BLOCK && outsideHit.getBlockPos().equals(FINIAL_OWNER),
                            "Actual outside-body lower-cell ray did not select its ornament owner");
                    close(outsideHit.getPos().squaredDistanceTo(expected.getPos()),0,"Actual lower-cell ornament hit position");
                    require(raw!=null && indexed!=null && indexed.getBlockPos().equals(FINIAL_OWNER),
                            "Direct lower-cell geometry/index lookup lost the ornament owner");
                    if(support==halfMultiface) {
                        // This artificial ray begins inside both the ornament and
                        // LayerMultifaceBlock's deliberate full-height targeting post.
                        // Equal-distance vanilla hits retain their existing owner.
                        var post=support.getOutlineShape(rayWorld,COPING_OWNER).raycast(rayStart,rayEnd,COPING_OWNER);
                        require(post!=null && hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(COPING_OWNER),
                                "Inside targeting-post tie did not preserve the existing layer owner");
                        close(post.getPos().squaredDistanceTo(raw.getPos()),0,"Inside targeting-post/ornament tie");
                        close(hit.getPos().squaredDistanceTo(post.getPos()),0,"Inside targeting-post owner hit position");
                    } else {
                        require(hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(FINIAL_OWNER),
                                "Actual lowered ornament was not selectable through its support cell: "+fitted+"/"+support);
                    }
                    FinialRaycast.clearWorld(rayWorld);
                    for(CopingBlock design:designs) {
                        BlockState cap=design.forSupport(support,view(Map.of(COPING_OWNER.down(),support)),COPING_OWNER.down(),false,false);
                        require(cap!=null && cap.get(CopingBlock.SURFACE)==CopingBlock.Surface.FLAT_HALF,
                                "Actual coping design did not inherit its half-height support");
                        BlockState onCap=FinialSupportPlacement.fitted(original,view(Map.of(COPING_OWNER,cap,COPING_OWNER.down(),support)),COPING_OWNER);
                        assertPose(FinialSupport.fit(onCap,finial.style()),CopingBlock.attachmentPose(cap),"Actual capped half-height support");
                        assertRawMeshContact(onCap,finial,cap);
                        capped++;
                    }
                }
                direct++;
            }
            for(BlockState unsupported:List.of(halfLayer.with(LayerBlock.LAYERS,3),halfLayer.with(LayerBlock.TOP,true),
                    halfMultiface.with(LayerMultifaceBlock.EAST,true),halfMultiface.with(LayerMultifaceBlock.DOWN,false).with(LayerMultifaceBlock.UP,true))) {
                require(FinialSupportPlacement.fitted(original,view(Map.of(COPING_OWNER,unsupported)),COPING_OWNER)==original,
                        "An unsupported actual layer arrangement acquired a lowered finial");
            }
        }
        require(direct==50 && capped==60,"Actual slab/layer support matrix was incomplete");
        System.out.println("ERYDON_FINIAL_REAL_HALF_HEIGHT_SUPPORT_OK: "+direct+" actual slab/layer fits, "+capped
                +" actual original/Grand coping-on-half-support poses; selection, refit and unsupported-layer rejection");
    }

    private static void standaloneAndSlopeFallback(List<FinialBlock> finials) {
        List<Block> slopes = List.of(new SlopeBlock(Block.Settings.copy(Blocks.STONE)),
                new ShallowSlopeBlock(Block.Settings.copy(Blocks.STONE), ShallowSlopeBlock.Variant.LOWER),
                new ShallowSlopeBlock(Block.Settings.copy(Blocks.STONE), ShallowSlopeBlock.Variant.UPPER),
                new SlopeSteepBlock(Block.Settings.copy(Blocks.STONE), SlopeSteepBlock.Variant.LOWER),
                new SlopeSteepBlock(Block.Settings.copy(Blocks.STONE), SlopeSteepBlock.Variant.UPPER));
        String[] suffixes = {"slope", "slope_shallow_lower", "slope_shallow_upper", "slope_steep_lower", "slope_steep_upper"};
        for (int i = 0; i < slopes.size(); i++) {
            register("erydon:glacium_" + suffixes[i], slopes.get(i));
            slopes.get(i).getStateManager().getStates().forEach(BlockState::initShapeCache);
        }
        int cases = 0,offsetCases=0;
        for (FinialBlock finial : finials) for (TwoSizeDecorBlock.Size size : TwoSizeDecorBlock.Size.values()) {
            BlockState original = finial.getDefaultState().with(TwoSizeDecorBlock.SIZE, size);
            for (BlockState support : List.of(Blocks.STONE.getDefaultState(), Blocks.AIR.getDefaultState())) {
                BlockState standalone = FinialSupportPlacement.fitted(original, view(Map.of(COPING_OWNER, support)), COPING_OWNER);
                require(standalone == original && !FinialSupport.fit(standalone, finial.style()).mounted(),
                        "Standalone use unexpectedly added a fitted base");
            }
            for (Block slope : slopes) for (Direction facing : Direction.Type.HORIZONTAL) {
                BlockState support = slope.getDefaultState().with(SlopeBlock.FACING, facing);
                BlockState fitted = FinialSupportPlacement.fitted(original, view(Map.of(COPING_OWNER, support)), COPING_OWNER);
                var fit = FinialSupport.fit(fitted, finial.style());
                CopingBlock.Support profile = CopingBlock.support(support, view(Map.of(COPING_OWNER, support)), COPING_OWNER);
                require(profile != null && fit.mounted() && !fitted.get(FinialBlock.SUPPORT).coping,
                        "Direct actual slope fallback did not mount correctly");
                close(fit.planeY(), profile.surface().height((profile.surface().start + profile.surface().end) / 2),
                        "Direct slope plane included coping thickness");
                if(slope==slopes.get(4)) {
                    BlockState offset=FinialSupportPlacement.fitted(original.with(FinialBlock.SUPPORT,
                            FinialSupport.Profile.STEEP_UPPER_OFFSET),view(Map.of(COPING_OWNER,support)),COPING_OWNER);
                    var shifted=FinialSupport.fit(offset,finial.style());
                    require(offset.get(FinialBlock.SUPPORT)==FinialSupport.Profile.STEEP_UPPER_OFFSET,
                            "Saved direct steep upper owner offset was lost");
                    close(shifted.centreX(),fit.centreX()-facing.getOffsetX(),"Direct steep upper offset X");
                    close(shifted.centreZ(),fit.centreZ()-facing.getOffsetZ(),"Direct steep upper offset Z");
                    close(shifted.planeY(),fit.planeY(),"Direct steep upper offset height");
                    require(FinialSupportPlacement.supportPos(offset,FINIAL_OWNER.offset(facing)).equals(COPING_OWNER),
                            "Shifted direct steep finial no longer resolves its true support cell");
                    offsetCases++;
                }
                cases++;
            }
        }
        System.out.println("ERYDON_FINIAL_STANDALONE_OK: unchanged full-block/unsupported use and " + cases
                + " direct real-slope fallback poses, "+offsetCases+" saved steep upper owner offsets");
    }

    private static void directWallFallback(List<FinialBlock> finials) {
        Block diagonal=new SlopeVerticalBlock(Block.Settings.copy(Blocks.STONE));
        Block broad=new SlopeVerticalShallowBroadBlock(Block.Settings.copy(Blocks.STONE));
        Block narrow=new SlopeVerticalShallowNarrowBlock(Block.Settings.copy(Blocks.STONE));
        register("erydon:glacium_slope_vertical",diagonal);
        register("erydon:glacium_slope_vertical_shallow_broad",broad);
        register("erydon:glacium_slope_vertical_shallow_narrow",narrow);
        for(Block wall:List.of(diagonal,broad,narrow)) wall.getStateManager().getStates().forEach(BlockState::initShapeCache);
        int cases=0;
        for(FinialBlock finial:finials) for(TwoSizeDecorBlock.Size size:TwoSizeDecorBlock.Size.values())
            for(CopingBlock.Surface surface:CopingBlock.Surface.values()) {
                if(!surface.aligned()) continue;
                for(Direction facing:Direction.Type.HORIZONTAL) {
                    boolean left=surface.asString().endsWith("_left");
                    boolean sourceBroad=surface.asString().contains("broad");
                    boolean partnerBroad=surface.asString().contains("wide") || surface.asString().contains("narrow")
                            && !surface.asString().contains("thin");
                    Direction wallFacing=facing.rotateYCounterclockwise();
                    Block source=surface==CopingBlock.Surface.DIAGONAL ? diagonal : sourceBroad ? broad : narrow;
                    BlockState wall=wallState(source,wallFacing,left);
                    Map<BlockPos,BlockState> supports=new HashMap<>(Map.of(COPING_OWNER,wall));
                    if(source!=diagonal) supports.put(COPING_OWNER.offset(facing.getOpposite()),
                            wallState(partnerBroad ? broad : narrow,wallFacing.getOpposite(),left));
                    var resolved=CopingBlock.support(wall,view(supports),COPING_OWNER);
                    require(resolved!=null && resolved.surface()==surface && resolved.facing()==facing,
                            "Actual paired-wall fixture did not resolve its independent target profile "+surface+"/"+facing);
                    BlockState original=finial.getDefaultState().with(TwoSizeDecorBlock.SIZE,size);
                    BlockState fitted=FinialSupportPlacement.fitted(original,view(supports),COPING_OWNER);
                    assertWallPose(fitted,finial,resolved);
                    require(FinialSupportPlacement.isClear(fitted,worldView(supports),FINIAL_OWNER),
                            "Direct actual paired-wall contact blocked its fitted finial");
                    for(BlockRotation rotation:BlockRotation.values()) {
                        Map<BlockPos,BlockState> rotated=transformed(supports,rotation,BlockMirror.NONE);
                        var support=CopingBlock.support(rotated.get(COPING_OWNER),view(rotated),COPING_OWNER);
                        require(support!=null,"Rotated real wall pair lost its support");
                        assertWallPose(finial.rotate(fitted,rotation),finial,support);
                    }
                    for(BlockMirror mirror:BlockMirror.values()) {
                        Map<BlockPos,BlockState> mirrored=transformed(supports,BlockRotation.NONE,mirror);
                        if(mirror==BlockMirror.NONE) require(mirrored.equals(supports),
                                "No-mirror changed actual wall hand/facing: "+wall+"/"+surface+"/"+facing);
                        var support=CopingBlock.support(mirrored.get(COPING_OWNER),view(mirrored),COPING_OWNER);
                        require(support!=null,"Mirrored real wall pair lost its support");
                        assertWallPose(finial.mirror(fitted,mirror),finial,support);
                    }
                    if(source!=diagonal) require(FinialSupportPlacement.fitted(original,
                            view(Map.of(COPING_OWNER,wall)),COPING_OWNER)==original,
                            "Unpaired shallow wall received a fitted base");
                    cases++;
                }
            }
        require(cases==360,"Direct 45-degree and all eight shallow pair profiles were incomplete");
        System.out.println("ERYDON_FINIAL_REAL_WALL_SUPPORT_OK: "+cases
                +" direct actual resolver poses; both hands/four facings/sizes/styles, rotation/mirror and unpaired rejection");
    }

    private static BlockState wallState(Block block,Direction facing,boolean left) {
        BlockState state=block.getDefaultState().with(SlopeVerticalBlock.FACING,facing);
        if(block instanceof SlopeVerticalShallowBroadBlock) return state.with(SlopeVerticalShallowBroadBlock.HAND,
                left ? SlopeVerticalShallowBroadBlock.Handedness.LEFT : SlopeVerticalShallowBroadBlock.Handedness.RIGHT);
        if(block instanceof SlopeVerticalShallowNarrowBlock) return state.with(SlopeVerticalShallowNarrowBlock.HAND,
                left ? SlopeVerticalShallowNarrowBlock.Handedness.LEFT : SlopeVerticalShallowNarrowBlock.Handedness.RIGHT);
        return state;
    }

    private static void assertWallPose(BlockState state,FinialBlock finial,CopingBlock.Support support) {
        var fit=FinialSupport.fit(state,finial.style());
        var pose=support.surface().fit.pose(support.facing());
        String context=" finial="+state+", actual wall support="+support;
        require(fit.mounted() && !state.get(FinialBlock.SUPPORT).coping,"Direct wall used coping thickness metadata");
        close(fit.centreX(),pose.centreX(),"Real paired-wall centre X"+context);
        close(fit.centreZ(),pose.centreZ(),"Real paired-wall centre Z"+context);
        close(fit.planeY(),0,"Real full-height wall mounting top");
        close(fit.gradientX(),0,"Direct wall gradient X"); close(fit.gradientZ(),0,"Direct wall gradient Z");
        close(Math.sin(fit.yaw()),Math.sin(pose.yawRadians()),"Real paired-wall yaw sine");
        close(Math.cos(fit.yaw()),Math.cos(pose.yawRadians()),"Real paired-wall yaw cosine");
    }

    private static Map<BlockPos,BlockState> transformed(Map<BlockPos,BlockState> source,
            BlockRotation rotation,BlockMirror mirror) {
        Map<BlockPos,BlockState> target=new HashMap<>();
        source.forEach((pos,state) -> {
            BlockPos local=pos.subtract(COPING_OWNER).rotate(rotation);
            if(mirror==BlockMirror.LEFT_RIGHT) local=new BlockPos(local.getX(),local.getY(),-local.getZ());
            if(mirror==BlockMirror.FRONT_BACK) local=new BlockPos(-local.getX(),local.getY(),local.getZ());
            target.put(COPING_OWNER.add(local),state.rotate(rotation).mirror(mirror));
        });
        return target;
    }

    private static void register(String id, Block block) {
        Identifier identifier = new Identifier(id);
        require(!Registries.BLOCK.containsId(identifier), "Fixture attempted to replace a registered block " + identifier);
        Registry.register(Registries.BLOCK, identifier, block);
        Registry.register(Registries.ITEM, identifier, new BlockItem(block, new Item.Settings()));
    }

    private static BlockView view(Map<BlockPos, BlockState> source) {
        Map<BlockPos, BlockState> states = new HashMap<>(source);
        return new BlockView() {
            @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.getDefaultState()); }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public int getHeight() { return 384; }
            @Override public int getBottomY() { return -64; }
        };
    }

    private static WorldView worldView(Map<BlockPos,BlockState> source) {
        Map<BlockPos,BlockState> states=new HashMap<>(source);
        WorldBorder border=new WorldBorder();
        return (WorldView)java.lang.reflect.Proxy.newProxyInstance(WorldView.class.getClassLoader(),
                new Class<?>[]{WorldView.class},(proxy,method,args) -> switch(method.getName()) {
                    case "getBlockState" -> states.getOrDefault(args[0],Blocks.AIR.getDefaultState());
                    case "getFluidState" -> states.getOrDefault(args[0],Blocks.AIR.getDefaultState()).getFluidState();
                    case "getBlockEntity" -> null;
                    case "getWorldBorder" -> border;
                    case "getHeight" -> 384;
                    case "getBottomY" -> -64;
                    case "getTopY" -> 320;
                    case "isOutOfHeightLimit" -> {
                        int y=args[0] instanceof BlockPos pos ? pos.getY() : (Integer)args[0];
                        yield y < -64 || y >= 320;
                    }
                    case "equals" -> proxy==args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "RealFinialCopingWorldViewFixture";
                    default -> throw new AssertionError("Unexpected real clearance world method "+method);
                });
    }

    private static long retainedHeapBytes() {
        System.gc();
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static void close(double actual, double expected, String context) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) < EPSILON,
                context + ": expected=" + expected + ", actual=" + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
