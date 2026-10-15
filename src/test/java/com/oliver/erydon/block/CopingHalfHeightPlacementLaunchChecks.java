package com.oliver.erydon.block;

import com.oliver.erydon.item.CopingItem;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.Hand;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.WorldAccess;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Real placement contexts and mixed-in rays must agree on the saved half-height owner. */
final class CopingHalfHeightPlacementLaunchChecks {
    static void run(CopingBlock coping) throws Exception {
        SlabBlock modSlab = (SlabBlock) block("glacium_slab");
        LayerBlock layer = (LayerBlock) block("glacium_layer");
        LayerMultifaceBlock multiface = (LayerMultifaceBlock) block("glacium_layer_multiface");
        BlockState bottomLayer = layer.getDefaultState().with(LayerBlock.LAYERS, 4).with(LayerBlock.TOP, false);
        BlockState horizontal = horizontal(multiface, 4, false);
        List<BlockState> lowered = List.of(
                Blocks.STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM),
                modSlab.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM), bottomLayer, horizontal);
        List<BlockState> full = List.of(
                Blocks.STONE.getDefaultState(),
                Blocks.STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP),
                Blocks.STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.DOUBLE),
                modSlab.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP),
                modSlab.getDefaultState().with(SlabBlock.TYPE, SlabType.DOUBLE),
                bottomLayer.with(LayerBlock.TOP, true), bottomLayer.with(LayerBlock.LAYERS, 8),
                horizontal(multiface, 8, false), horizontal(multiface, 4, true),
                Blocks.STONE_STAIRS.getDefaultState().with(net.minecraft.state.property.Properties.BLOCK_HALF, BlockHalf.TOP));
        int placements = 0;
        for (BlockState support : lowered) placements += placements(coping, support, true);
        for (BlockState support : full) placements += placements(coping, support, false);
        int rejected = rejectedHalfProfiles(layer, multiface);
        int shapes = shapesAndAttachment(coping);
        int rays = rays(coping, lowered) + occludedRays(coping);
        refitAndRemoval(coping, bottomLayer);
        System.out.println("ERYDON_COPING_HALF_HEIGHT_OK placements=" + placements + " rejectedProfiles=" + rejected
                + " translatedShapes=" + shapes + " ownedRays=" + rays);
    }

    private static int placements(CopingBlock coping, BlockState support, boolean half) throws Exception {
        var item = (CopingItem) coping.asItem();
        int checked = 0;
        Direction[] faces = support.getBlock() instanceof SlabBlock || half
                ? Direction.values() : new Direction[]{Direction.UP};
        for (Direction facing : Direction.Type.HORIZONTAL) for (Direction face : faces) {
            for (boolean water : new boolean[]{false, true}) {
                TestWorld world = DoubleColumnRepairLaunchProbe.allocate(TestWorld.class);
                world.states = new HashMap<>();
                world.states.put(BlockPos.ORIGIN, support);
                if (water) world.states.put(BlockPos.ORIGIN.up(), Blocks.WATER.getDefaultState());
                Map<BlockPos, BlockState> before = Map.copyOf(world.states);
                var original = new ItemPlacementContext(world, null, Hand.MAIN_HAND, new ItemStack(item),
                        new BlockHitResult(Vec3d.ofCenter(BlockPos.ORIGIN), face, BlockPos.ORIGIN, false)) {
                    @Override public Direction getHorizontalPlayerFacing() { return facing; }
                };
                ItemPlacementContext context = item.getPlacementContext(original);
                require(context != null && context.getBlockPos().equals(BlockPos.ORIGIN.up()),
                        "Partial support lost its above-cell owner: " + support + " " + face);
                BlockState placed = coping.getPlacementState(context);
                require(placed != null && placed.get(CopingBlock.SURFACE)
                                == (half ? CopingBlock.Surface.FLAT_HALF : CopingBlock.Surface.FLAT),
                        "Wrong horizontal coping profile: " + support + " " + face);
                require(placed.get(CopingBlock.WATERLOGGED) == water, "Owner-cell waterlogging was lost");
                require(CopingBlock.supportPos(placed, context.getBlockPos()).equals(BlockPos.ORIGIN),
                        "Horizontal coping changed support ownership");
                require(world.states.equals(before), "Placement context overwrote the support");
                var target = CopingItem.target(world, BlockPos.ORIGIN);
                require(target != null && target.pos().equals(BlockPos.ORIGIN.up()) && !target.offset(),
                        "Horizontal support received a pitched offset target");
                world.states.put(BlockPos.ORIGIN.up(), Blocks.STONE.getDefaultState());
                require(CopingItem.target(world, BlockPos.ORIGIN) == null, "Occupied owner cell was replaced");
                checked++;
            }
        }
        return checked;
    }

    private static int rejectedHalfProfiles(LayerBlock layer, LayerMultifaceBlock multiface) {
        int checked = 0;
        for (int layers = 1; layers <= 8; layers++) for (boolean top : new boolean[]{false, true}) {
            BlockState state = layer.getDefaultState().with(LayerBlock.LAYERS, layers).with(LayerBlock.TOP, top);
            require(CopingBlock.hasHalfHeightTop(state) == (layers == 4 && !top),
                    "An unsupported layer thickness was treated as a bottom slab");
            checked++;
        }
        for (int layers = 1; layers <= 8; layers++) for (int mask = 0; mask < 64; mask++) {
            BlockState state = multiface.getDefaultState().with(LayerMultifaceBlock.LAYERS, layers);
            var properties = List.of(LayerMultifaceBlock.DOWN, LayerMultifaceBlock.UP, LayerMultifaceBlock.NORTH,
                    LayerMultifaceBlock.SOUTH, LayerMultifaceBlock.EAST, LayerMultifaceBlock.WEST);
            for (int face = 0; face < properties.size(); face++) state = state.with(properties.get(face), (mask & 1 << face) != 0);
            require(CopingBlock.hasHalfHeightTop(state) == (layers == 4 && mask == 1),
                    "A vertical, upper or mixed-face layer was treated as a bottom slab");
            checked++;
        }
        return checked;
    }

    private static int shapesAndAttachment(CopingBlock coping) {
        int checked = 0;
        BlockView empty = CopingPlacementLaunchProbe.view(Map.of());
        for (Direction facing : Direction.Type.HORIZONTAL) for (boolean offset : new boolean[]{false, true}) {
            for (boolean water : new boolean[]{false, true}) {
                BlockState full = coping.getDefaultState().with(CopingBlock.FACING, facing)
                        .with(CopingBlock.OFFSET, offset).with(CopingBlock.WATERLOGGED, water);
                BlockState half = full.with(CopingBlock.SURFACE, CopingBlock.Surface.FLAT_HALF);
                var expected = full.getOutlineShape(empty, BlockPos.ORIGIN).offset(0, -.5, 0);
                var actual = half.getOutlineShape(empty, BlockPos.ORIGIN);
                require(!VoxelShapes.matchesAnywhere(expected, actual, BooleanBiFunction.NOT_SAME),
                        "Half-height outline is not the exact translated full profile");
                require(actual == half.getCollisionShape(empty, BlockPos.ORIGIN), "Half-height collision differs from its outline");
                require(!VoxelShapes.matchesAnywhere(coping.getBreakParticleShape(full).offset(0, -.5, 0),
                                coping.getBreakParticleShape(half), BooleanBiFunction.NOT_SAME),
                        "Break-particle bounds did not follow the lowered cap");
                var a = CopingBlock.attachmentPose(full);
                var b = CopingBlock.attachmentPose(half);
                require(Math.abs(a.topCentreY() - .5 - b.topCentreY()) < 1e-8
                                && a.centreX() == b.centreX() && a.centreZ() == b.centreZ()
                                && a.yawRadians() == b.yawRadians() && a.gradientX() == b.gradientX()
                                && a.gradientZ() == b.gradientZ() && a.runLength() == b.runLength() && a.width() == b.width(),
                        "Family attachment pose did not follow the exact half-height translation");
                checked++;
            }
        }
        return checked;
    }

    private static int rays(CopingBlock coping, List<BlockState> supports) {
        int checked = 0;
        var entity = new net.minecraft.entity.MarkerEntity(net.minecraft.entity.EntityType.MARKER, null);
        double y = .5 + CopingBlock.THICKNESS / 2;
        for (BlockState support : supports) for (Direction facing : Direction.Type.HORIZONTAL) {
            BlockPos owner = BlockPos.ORIGIN.up();
            BlockState cap = coping.forSupport(support, CopingPlacementLaunchProbe.view(Map.of(BlockPos.ORIGIN, support)),
                    BlockPos.ORIGIN, false, false).with(CopingBlock.FACING, facing);
            BlockView view = CopingPlacementLaunchProbe.view(Map.of(BlockPos.ORIGIN, support, owner, cap));
            Vec3d[][] rays = {
                    {new Vec3d(.5, 3, .5), new Vec3d(.5, -1, .5)},
                    {new Vec3d(-2, y, .5), new Vec3d(2, y, .5)},
                    {new Vec3d(.5, y, -2), new Vec3d(.5, y, 2)},
                    {new Vec3d(-2, y, -.08), new Vec3d(2, y, -.08)},
                    {new Vec3d(1.08, y, -2), new Vec3d(1.08, y, 2)}
            };
            for (Vec3d[] ray : rays) {
                var hit = view.raycast(new RaycastContext(ray[0], ray[1], RaycastContext.ShapeType.OUTLINE,
                        RaycastContext.FluidHandling.NONE, entity));
                require(hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(owner),
                        "Half-height cap or its overhang cannot be selected at its owning cell");
                checked++;
            }
        }
        return checked;
    }

    private static int occludedRays(CopingBlock coping) {
        var entity = new net.minecraft.entity.MarkerEntity(net.minecraft.entity.EntityType.MARKER, null);
        double y = .5 + CopingBlock.THICKNESS / 2;
        BlockPos owner = new BlockPos(1, 1, 1), wall = new BlockPos(1, 0, 0);
        Vec3d start = new Vec3d(.5, y, .2), end = new Vec3d(2.5, y, 2.2);
        Direction facing = Direction.EAST;
        for (int turn = 0; turn < 4; turn++) {
            BlockState cap = coping.getDefaultState().with(CopingBlock.SURFACE, CopingBlock.Surface.FLAT_HALF)
                    .with(CopingBlock.FACING, facing);
            BlockView view = CopingPlacementLaunchProbe.view(Map.of(owner, cap, wall, Blocks.STONE.getDefaultState(),
                    owner.down(), Blocks.STONE_SLAB.getDefaultState()));
            var hit = view.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.NONE, entity));
            require(hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(wall),
                    "A neighbouring half-height cap was selected through an opaque wall");
            owner = new BlockPos(-owner.getZ(), owner.getY(), owner.getX());
            wall = new BlockPos(-wall.getZ(), wall.getY(), wall.getX());
            start = new Vec3d(1 - start.z, start.y, start.x);
            end = new Vec3d(1 - end.z, end.y, end.x);
            facing = facing.rotateYClockwise();
        }
        return 4;
    }

    private static void refitAndRemoval(CopingBlock coping, BlockState halfSupport) {
        BlockPos owner = BlockPos.ORIGIN.up();
        BlockState half = coping.getDefaultState().with(CopingBlock.SURFACE, CopingBlock.Surface.FLAT_HALF)
                .with(CopingBlock.WATERLOGGED, true);
        for (BlockState support : List.of(Blocks.STONE.getDefaultState(), halfSupport, Blocks.AIR.getDefaultState())) {
            WorldAccess world = access(Map.of(BlockPos.ORIGIN, support));
            BlockState result = coping.getStateForNeighborUpdate(half, Direction.DOWN, support, world, owner, BlockPos.ORIGIN);
            require(result.get(CopingBlock.WATERLOGGED), "Support refit lost saved waterlogging");
            require(result.get(CopingBlock.SURFACE) == (support.isOf(Blocks.STONE)
                    ? CopingBlock.Surface.FLAT : CopingBlock.Surface.FLAT_HALF), "Support refit chose the wrong height");
            if (support.isAir()) require(result == half, "Removed support changed the saved half-height pose");
        }
    }

    private static BlockState horizontal(LayerMultifaceBlock block, int layers, boolean upper) {
        return block.getDefaultState().with(LayerMultifaceBlock.LAYERS, layers)
                .with(LayerMultifaceBlock.DOWN, !upper).with(LayerMultifaceBlock.UP, upper)
                .with(LayerMultifaceBlock.NORTH, false).with(LayerMultifaceBlock.SOUTH, false)
                .with(LayerMultifaceBlock.EAST, false).with(LayerMultifaceBlock.WEST, false);
    }

    private static Block block(String path) {
        Block block = Registries.BLOCK.get(new Identifier("erydon_coping_placement_probe", path));
        require(block != Blocks.AIR, "Missing half-height support fixture " + path);
        return block;
    }

    private static WorldAccess access(Map<BlockPos, BlockState> states) {
        return (WorldAccess) Proxy.newProxyInstance(WorldAccess.class.getClassLoader(), new Class[]{WorldAccess.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBlockState" -> states.getOrDefault(args[0], Blocks.AIR.getDefaultState());
                    case "scheduleFluidTick" -> null;
                    default -> {
                        if (method.isDefault()) yield InvocationHandler.invokeDefault(proxy, method, args);
                        throw new UnsupportedOperationException(method.toString());
                    }
                });
    }

    /** Constructor bypass is limited to the placement context's block/fluid reads. */
    private static final class TestWorld extends ServerWorld {
        Map<BlockPos, BlockState> states;
        private TestWorld() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.getDefaultState()); }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
