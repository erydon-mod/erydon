package com.oliver.erydon.block;

import com.oliver.erydon.item.CorniceItem;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.Registries;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/** Real clicked faces, paid placement, persistence and placed baked geometry on half-depth supports. */
public final class CorniceHalfSupportLaunchChecks {
    private static int placements, transforms, rays;
    private CorniceHalfSupportLaunchChecks() { }

    public static void run() throws Exception {
        List<BlockState> supports = new ArrayList<>();
        for (String id : new String[]{"glacium_layer_vertical", "glacium_slice_vertical"}) {
            Block block = fixture(id);
            for (Direction facing : Direction.Type.HORIZONTAL) for (boolean water : new boolean[]{false, true})
                supports.add(block.getDefaultState().with(LayerVerticalBlock.LAYERS, 4)
                        .with(LayerVerticalBlock.FACING, facing).with(LayerVerticalBlock.WATERLOGGED, water));
        }
        for (int mask = 0; mask < 16; mask++) for (boolean water : new boolean[]{false, true}) {
            BlockState state = fixture("glacium_layer_multiface").getDefaultState().with(LayerMultifaceBlock.LAYERS, 4)
                    .with(LayerMultifaceBlock.WATERLOGGED, water).with(LayerMultifaceBlock.NORTH, (mask & 1) != 0)
                    .with(LayerMultifaceBlock.EAST, (mask & 2) != 0).with(LayerMultifaceBlock.SOUTH, (mask & 4) != 0)
                    .with(LayerMultifaceBlock.WEST, (mask & 8) != 0);
            supports.add(state);
            require(CorniceHalfSupports.faces(state.with(LayerMultifaceBlock.UP, true)).isEmpty()
                            && CorniceHalfSupports.faces(state.with(LayerMultifaceBlock.DOWN, true)).isEmpty(),
                    "A changing-height multiface cap was treated as a constant vertical wall");
        }
        Block broad = Registries.BLOCK.get(new Identifier("erydon", "glacium_slope_vertical_shallow_broad"));
        for (Direction facing : Direction.Type.HORIZONTAL) for (var hand : SlopeVerticalShallowBroadBlock.Handedness.values())
            for (boolean water : new boolean[]{false, true}) supports.add(broad.getDefaultState()
                    .with(SlopeVerticalShallowBroadBlock.FACING, facing).with(SlopeVerticalShallowBroadBlock.HAND, hand)
                    .with(SlopeVerticalShallowBroadBlock.WATERLOGGED, water));
        Block narrow = Registries.BLOCK.get(new Identifier("erydon", "glacium_slope_vertical_shallow_narrow"));
        for (Direction facing : Direction.Type.HORIZONTAL) for (var hand : SlopeVerticalShallowNarrowBlock.Handedness.values())
            for (boolean water : new boolean[]{false, true}) supports.add(narrow.getDefaultState()
                    .with(SlopeVerticalShallowNarrowBlock.FACING, facing).with(SlopeVerticalShallowNarrowBlock.HAND, hand)
                    .with(SlopeVerticalShallowNarrowBlock.WATERLOGGED, water));
        int faces = supports.stream().mapToInt(s -> CorniceHalfSupports.faces(s).size()).sum();
        require(faces == 144, "Half-support face coverage changed: " + faces);
        for (String style : List.of("modern", "georgian", "byzantine", "gothic")) {
            CorniceBlock cornice = cornice(style);
            require(cornice.getStateManager().getStates().size() == 72, "Expected52 legacy poses plus20 half-support poses");
            for (BlockState support : supports) for (var face : CorniceHalfSupports.faces(support)) verify(cornice, support, face);
        }
        fullAndUnrequestedDepths();
        sharedFaces();
        outerCorners();
        com.oliver.erydon.client.model.CorniceCroppedProfileLaunchChecks.run();
        require(placements == 576 && rays == placements && transforms == placements * 7, "Incomplete half-support proof");
        System.out.println("ERYDON_CORNICE_HALF_SUPPORTS_OK placements=" + placements + " worldRays=" + rays
                + " transforms=" + transforms + " supports=verticalLayers+cornerSlices+exactMultifaceUnion+broadShortFace+narrowShortFace"
                + " water=both paidPlacement=actual ceilings=preserved legacyHypotenuses=unchanged distinctFaces=shared");
    }

    private static void verify(CorniceBlock cornice, BlockState support, CorniceHalfSupports.Face face) throws Exception {
        BlockPos supportPos = new BlockPos(15, 7, 15), owner = supportPos.offset(face.outward());
        var world = CorniceAssemblyLaunchChecks.world();
        world.states.put(supportPos, support); world.states.put(supportPos.up(), Blocks.STONE.getDefaultState());
        world.states.put(owner.up(), Blocks.STONE.getDefaultState());
        CorniceRun rear = face.supportRun();
        VoxelShape physical = support.getCollisionShape(world, supportPos, ShapeContext.absent());
        for (double u : new double[]{.25, .5, .75}) for (double y : new double[]{.3, .75, .96875}) {
            var point = rear.point(u, 0);
            require(contains(physical, point.x() - rear.normalX() * .00001, y, point.z() - rear.normalZ() * .00001)
                            && !contains(physical, point.x() + rear.normalX() * .00001, y, point.z() + rear.normalZ() * .00001),
                    "Fitted support edge is not an actually exposed vertical collision face: " + support + " " + face);
        }
        var midpoint = rear.point(.5, 0);
        Vec3d hit = Vec3d.of(supportPos).add(midpoint.x(), .85, midpoint.z());
        CorniceItem.Target target = CorniceItem.target(world, supportPos, face.outward(), cornice.getDefaultState(), hit);
        require(target != null && target.pos().equals(owner) && target.fittedShape() == face.shape(),
                "Actual clicked half face selected another pose or owner");
        ItemStack stack = new ItemStack(cornice.asItem(), 2);
        ItemUsageContext usage = new ItemUsageContext(world, null, Hand.MAIN_HAND, stack,
                new BlockHitResult(hit, face.outward(), supportPos, false));
        require(stack.getItem().useOnBlock(usage).isAccepted() && stack.getCount() == 1,
                "Real cornice item failed half-face placement or consumed another amount");
        BlockState placed = world.getBlockState(owner);
        require(placed.isOf(cornice) && placed.get(CorniceBlock.SHAPE) == face.shape()
                        && placed.get(CorniceBlock.FACING) == face.facing() && CorniceSlopePlacement.attached(placed, world, owner),
                "Placed/saved cornice lost its actual half support face");
        require(world.states.get(supportPos) == support && world.states.get(supportPos.up()).isOf(Blocks.STONE)
                        && world.states.get(owner.up()).isOf(Blocks.STONE) && world.writes.stream().allMatch(owner::equals),
                "Half-face placement modified a wall/ceiling or another owner cell");
        require(NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), NbtHelper.fromBlockState(placed)) == placed,
                "Half-support pose did not survive saved-state decoding");
        require(CorniceItem.target(world, supportPos, face.outward(), cornice("modern".equals(style(cornice))
                        ? "georgian" : "modern").getDefaultState(), hit) == null,
                "A second style can duplicate the same physical half face");
        com.oliver.erydon.client.model.CorniceMeshLaunchChecks.verify(world, placed, owner);
        CorniceRun run = CorniceSlopePlacement.run(placed);
        require(Math.abs(run.length() - rear.length()) < .000001, "Half-width support was stretched to a full block");
        var centre = run.point(.5, .125);
        VoxelShape outline = placed.getOutlineShape(world, owner);
        require(outline == placed.getCollisionShape(world, owner) && contains(outline, centre.x(), .96875, centre.z()),
                "Fitted half-face geometry lost its cached collision/outline");
        Vec3d edge = Vec3d.of(owner).add(run.point(.5, 0).x(), .96875, run.point(.5, 0).z());
        var marker = new net.minecraft.entity.MarkerEntity(net.minecraft.entity.EntityType.MARKER, null);
        var ray = world.raycast(new net.minecraft.world.RaycastContext(edge.add(run.normalX(), 0, run.normalZ()),
                edge.add(-run.normalX() * .01, 0, -run.normalZ() * .01),
                net.minecraft.world.RaycastContext.ShapeType.OUTLINE, net.minecraft.world.RaycastContext.FluidHandling.NONE, marker));
        require(ray.getBlockPos().equals(owner), "Actual half-support ray selected a wall instead of the cornice owner: " + placed);
        rays++;
        for (BlockRotation rotation : BlockRotation.values()) {
            require(CorniceSlopePlacement.matches(support.rotate(rotation), placed.rotate(rotation)),
                    "Quarter-turn lost a half support face"); transforms++;
        }
        for (BlockMirror mirror : BlockMirror.values()) {
            BlockState reflected = placed.mirror(mirror);
            require(reflected.mirror(mirror) == placed && CorniceSlopePlacement.matches(support.mirror(mirror), reflected),
                    "Reflection lost a physical half support face or reversible saved pose"); transforms++;
        }
        world.states.put(supportPos, Blocks.AIR.getDefaultState());
        require(cornice.getStateForNeighborUpdate(placed, face.outward().getOpposite(), Blocks.AIR.getDefaultState(),
                        world, owner, supportPos).isAir(), "Actual support-removal callback retained a floating half pose");
        require(!CorniceSlopePlacement.attached(placed, world, owner), "Removed support retained its half-face attachment");
        world.states.put(supportPos, support);
        require(CorniceSlopePlacement.attached(placed, world, owner), "Undoing support removal lost the original attachment");
        if (!(support.getBlock() instanceof SlopeVerticalShallowBroadBlock)
                && !(support.getBlock() instanceof SlopeVerticalShallowNarrowBlock)) {
            BlockState thinner = support.with(LayerVerticalBlock.LAYERS, 3);
            world.states.put(supportPos, thinner);
            require(cornice.getStateForNeighborUpdate(placed, face.outward().getOpposite(), thinner, world, owner, supportPos).isAir(),
                    "Changing the4/8 support thickness silently retained or moved its saved pose");
            world.states.put(supportPos, support);
        }
        placements++;
    }

    private static void fullAndUnrequestedDepths() {
        for (String id : new String[]{"glacium_layer_vertical", "glacium_slice_vertical", "glacium_layer_multiface"}) {
            Block block = fixture(id);
            for (int layers : new int[]{1, 2, 3, 5, 6, 7, 8}) {
                BlockState state = block.getDefaultState().with(LayerVerticalBlock.LAYERS, layers);
                require(CorniceHalfSupports.faces(state).isEmpty(), "Half-depth classifier fitted an unrequested thickness");
            }
        }
        // Existing full8/8 supporting walls still use published native cardinal states.
        CorniceBlock cornice = cornice("modern");
        for (String id : new String[]{"glacium_layer_vertical", "glacium_slice_vertical", "glacium_layer_multiface"}) for (Direction side : Direction.Type.HORIZONTAL) {
            BlockState support = fixture(id).getDefaultState().with(LayerVerticalBlock.LAYERS, 8);
            if (support.getBlock() instanceof LayerMultifaceBlock) support = support.with(LayerMultifaceBlock.NORTH, true);
            var world = CorniceAssemblyLaunchChecks.world(); world.states.put(BlockPos.ORIGIN, support);
            ItemStack stack = new ItemStack(cornice.asItem());
            var context = new ItemUsageContext(world, null, Hand.MAIN_HAND, stack,
                    new BlockHitResult(Vec3d.ofCenter(BlockPos.ORIGIN).add(side.getOffsetX() * .5, 0, side.getOffsetZ() * .5), side, BlockPos.ORIGIN, false));
            require(stack.getItem().useOnBlock(context).isAccepted()
                            && world.getBlockState(BlockPos.ORIGIN.offset(side)).get(CorniceBlock.SHAPE) == CorniceBlock.CorniceShape.STRAIGHT,
                    "Full8/8 support lost the published ordinary wall placement");
        }
    }

    private static void sharedFaces() {
        BlockState support = fixture("glacium_layer_multiface").getDefaultState().with(LayerMultifaceBlock.LAYERS, 4)
                .with(LayerMultifaceBlock.NORTH, true).with(LayerMultifaceBlock.WEST, true);
        var faces = CorniceHalfSupports.faces(support).stream().filter(f -> f.outward() == Direction.EAST).toList();
        require(faces.size() == 2, "An L-shaped shell lost a real exposed edge or filled its pocket");
        var world = CorniceAssemblyLaunchChecks.world(); world.states.put(BlockPos.ORIGIN, support);
        BlockPos owner = BlockPos.ORIGIN.east();
        for (var face : faces) {
            BlockState part = cornice("modern").getDefaultState().with(CorniceBlock.SHAPE, face.shape()).with(CorniceBlock.FACING, face.facing());
            require(CorniceAssembly.add(world, owner, part), "Distinct physical shell faces could not share an owner");
        }
        require(CorniceAssembly.contents(world, owner).cornices().size() == 2,
                "Shared half-support assembly dropped/merged a paid physical face");
    }

    private static void outerCorners() throws Exception {
        int joins = 0;
        for (String style : List.of("modern", "georgian", "byzantine", "gothic"))
            for (String id : new String[]{"glacium_layer_vertical", "glacium_slice_vertical", "glacium_layer_multiface"})
                for (Direction facing : Direction.Type.HORIZONTAL) {
            BlockState support = fixture(id).getDefaultState().with(LayerVerticalBlock.LAYERS, 4);
            if (support.getBlock() instanceof LayerMultifaceBlock) {
                Direction first = facing, second = facing.rotateYCounterclockwise();
                support = support.with(LayerMultifaceBlock.NORTH, first == Direction.NORTH || second == Direction.NORTH)
                        .with(LayerMultifaceBlock.EAST, first == Direction.EAST || second == Direction.EAST)
                        .with(LayerMultifaceBlock.SOUTH, first == Direction.SOUTH || second == Direction.SOUTH)
                        .with(LayerMultifaceBlock.WEST, first == Direction.WEST || second == Direction.WEST);
            } else support = support.with(LayerVerticalBlock.FACING, facing);
            var faces = CorniceHalfSupports.faces(support);
            for (int first = 0; first < faces.size(); first++) for (int second = first + 1; second < faces.size(); second++) {
                var a = faces.get(first); var b = faces.get(second);
                if (a.outward() == b.outward() || a.outward() == b.outward().getOpposite()) continue;
                for (int end : new int[]{-1, 1}) {
                    var join = CorniceRun.join(a.supportRun(), end, b.supportRun(), -end);
                    if (join == null || (end < 0 ? join.sourceCut().at(.25) >= 0 : join.sourceCut().at(.25) <= 1)) continue;
                    var world = CorniceAssemblyLaunchChecks.world(); BlockPos supportPos = new BlockPos(15, 9, 15);
                    world.states.put(supportPos, support); world.states.put(supportPos.up(), Blocks.STONE.getDefaultState());
                    BlockState ca = placeCorner(world, supportPos, support, a, cornice(style));
                    BlockState cb = placeCorner(world, supportPos, support, b, cornice(style));
                    BlockPos pa = supportPos.offset(a.outward()), pb = supportPos.offset(b.outward());
                    var sa = CorniceConnections.components(world, ca, pa).get(0);
                    var sb = CorniceConnections.components(world, cb, pb).get(0);
                    require((end < 0 ? sa.joinedStart() : sa.joinedEnd())
                                    && (end < 0 ? sb.joinedEnd() : sb.joinedStart()) && sa.usesProfileCrop() && sb.usesProfileCrop(),
                            "Real half-support exterior corner rejected its reciprocal mitre: " + support + " " + a + " " + b);
                    for (int step = 0; step <= 64; step++) {
                        double depth = step / 128.;
                        var one = sa.point(end < 0 ? 0 : 1, depth); var two = sb.point(end < 0 ? 1 : 0, depth);
                        require(Math.abs(one.x() + pa.getX() - two.x() - pb.getX()) < .000001
                                        && Math.abs(one.z() + pa.getZ() - two.z() - pb.getZ()) < .000001,
                                "Half-support outside corner has a depth-dependent crack");
                    }
                    com.oliver.erydon.client.model.CorniceMeshLaunchChecks.verify(world, ca, pa);
                    com.oliver.erydon.client.model.CorniceMeshLaunchChecks.verify(world, cb, pb);
                    require(world.states.get(supportPos) == support && world.states.get(supportPos.up()).isOf(Blocks.STONE)
                                    && world.writes.stream().allMatch(p -> p.equals(pa) || p.equals(pb)),
                            "Outside mitre changed a wall or ceiling owner");
                    joins++;
                }
            }
        }
        require(joins >= 64, "Outer-corner fixtures did not exercise all half-support families/facings: " + joins);
        System.out.println("ERYDON_CORNICE_HALF_OUTER_CORNERS_OK pairs=" + joins + " depthSamples=" + (joins * 65)
                + " paidPlacement=actual nativeWorldMeshes=true ceilingCells=preserved");
    }

    private static BlockState placeCorner(CorniceAssemblyLaunchChecks.MemoryWorld world, BlockPos supportPos, BlockState support,
                                          CorniceHalfSupports.Face face, CorniceBlock cornice) {
        var midpoint = face.supportRun().point(.5, 0);
        Vec3d hit = Vec3d.of(supportPos).add(midpoint.x(), .85, midpoint.z());
        ItemStack stack = new ItemStack(cornice.asItem(), 2);
        require(stack.getItem().useOnBlock(new ItemUsageContext(world, null, Hand.MAIN_HAND, stack,
                        new BlockHitResult(hit, face.outward(), supportPos, false))).isAccepted() && stack.getCount() == 1,
                "Actual paid half-support corner placement failed: " + support + " " + face);
        return CorniceAssembly.contents(world, supportPos.offset(face.outward())).cornices().get(0);
    }

    static BlockState supportFor(BlockState cornice) {
        for (String id : new String[]{"glacium_layer_vertical", "glacium_slice_vertical"}) for (Direction facing : Direction.Type.HORIZONTAL) {
            BlockState support = fixture(id).getDefaultState().with(LayerVerticalBlock.LAYERS, 4).with(LayerVerticalBlock.FACING, facing);
            if (CorniceSlopePlacement.matches(support, cornice)) return support;
        }
        throw new AssertionError("Missing physical support fixture for " + cornice);
    }

    private static Block fixture(String id) { return Registries.BLOCK.get(new Identifier("erydon_coping_placement_probe", id)); }
    private static CorniceBlock cornice(String style) { return (CorniceBlock) Registries.BLOCK.get(new Identifier("erydon", "glacium_cornice_" + style)); }
    private static String style(CorniceBlock block) { return Registries.BLOCK.getId(block).getPath().substring("glacium_cornice_".length()); }
    private static boolean contains(VoxelShape shape, double x, double y, double z) { return shape.getBoundingBoxes().stream().anyMatch(b -> b.contains(x, y, z)); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
