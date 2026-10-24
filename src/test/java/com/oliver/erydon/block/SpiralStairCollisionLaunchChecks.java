package com.oliver.erydon.block;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockCollisionSpliterator;
import net.minecraft.world.BlockView;
import net.minecraft.world.CollisionView;
import net.minecraft.world.border.WorldBorder;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Actual engine discovery must expose exactly the already-approved cached spiral outline. */
final class SpiralStairCollisionLaunchChecks {
    private static int states, transforms, samples, diagonal, gaps;
    private static Box diagonalQuery;
    private static VoxelShape diagonalShape;

    private SpiralStairCollisionLaunchChecks() { }

    static void run() {
        var block = new StairsSpiralLargeBlock(AbstractBlock.Settings.copy(Blocks.STONE));
        var other = new StairsSpiralLargeBlock(AbstractBlock.Settings.copy(Blocks.OAK_PLANKS));
        block.getStateManager().getStates().forEach(BlockState::initShapeCache);
        other.getStateManager().getStates().forEach(BlockState::initShapeCache);
        for (BlockState state : block.getStateManager().getStates()) {
            VoxelShape outline = state.getOutlineShape(null, BlockPos.ORIGIN);
            require(outline == state.getCollisionShape(null, BlockPos.ORIGIN), "Spiral collision changed the approved outline");
            require(outline == state.getRaycastShape(null, BlockPos.ORIGIN), "Spiral raycast changed the approved outline");
            BlockState material = other.getDefaultState().with(StairsSpiralLargeBlock.FACING, state.get(StairsSpiralLargeBlock.FACING))
                    .with(StairsSpiralLargeBlock.PART, state.get(StairsSpiralLargeBlock.PART)).with(StairsSpiralLargeBlock.CAP, state.get(StairsSpiralLargeBlock.CAP));
            require(outline == material.getCollisionShape(null, BlockPos.ORIGIN), "Spiral material lost the shared cached shape");
            for (BlockRotation rotation : BlockRotation.values()) for (BlockMirror mirror : BlockMirror.values()) {
                BlockState posed = state.rotate(rotation).mirror(mirror);
                require(posed.get(StairsSpiralLargeBlock.PART) == state.get(StairsSpiralLargeBlock.PART)
                                && posed.get(StairsSpiralLargeBlock.CAP) == state.get(StairsSpiralLargeBlock.CAP),
                        "Spiral transform changed published part/cap properties");
                require(posed.getCollisionShape(null, BlockPos.ORIGIN) == posed.getOutlineShape(null, BlockPos.ORIGIN),
                        "Rotated/mirrored collision diverged from outline");
                transforms++;
            }
            for (BlockPos owner : new BlockPos[]{BlockPos.ORIGIN, new BlockPos(16, -2, 16), new BlockPos(-17, 3, -17)}) {
                Fixture fixture = new Fixture(Map.of(owner, state));
                VoxelShape worldShape = outline.offset(owner.getX(), owner.getY(), owner.getZ());
                for (Box box : outline.getBoundingBoxes()) {
                    Box query = around(owner, (box.minX + box.maxX) * .5, (box.minY + box.maxY) * .5, (box.minZ + box.maxZ) * .5);
                    requireCollision(fixture, owner, query, worldShape);
                    // The player's swept foot/body must also find the owner on an exposed tread.
                    double x = owner.getX() + (box.minX + box.maxX) * .5, z = owner.getZ() + (box.minZ + box.maxZ) * .5;
                    Box foot = new Box(x - .3, owner.getY() + box.maxY - .001, z - .3,
                            x + .3, owner.getY() + box.maxY + 1.8, z + .3);
                    requireCollision(fixture, owner, foot, worldShape);
                }
                for (double x : new double[]{-.75, -.25, .25, .75, 1.25, 1.75})
                    for (double z : new double[]{-.75, -.25, .25, .75, 1.25, 1.75})
                        for (double y : new double[]{.125, .375, .625, .875}) {
                            Box query = around(owner, x, y, z);
                            if (overlap(worldShape, query)) requireCollision(fixture, owner, query, worldShape);
                            else { require(owners(fixture, query).isEmpty(), "Spiral collision filled an outline gap"); gaps++; }
                        }
            }
            states++;
        }
        strictNonSpiralControl();
        ordinaryReadBudget();
        require(states == 32 && transforms == 384 && diagonal > 0 && gaps > 1000,
                "Incomplete spiral saved-state/transform/diagonal/gap coverage");
        System.out.println("ERYDON_SPIRAL_ENGINE_COLLISION_OK states=" + states + " transforms=" + transforms
                + " samples=" + samples + " diagonalPivotQueries=" + diagonal + " gapQueries=" + gaps
                + " outline=unchanged cache=shared legacyParts=preserved negativeAndChunkOrigins=true nonSpiralGate=unchanged");
    }

    private static void requireCollision(Fixture fixture, BlockPos owner, Box query, VoxelShape direct) {
        require(overlap(direct, query), "Spiral engine fixture does not intersect the approved outline");
        List<BlockPos> owners = owners(fixture, query);
        require(owners.equals(List.of(owner)), "Engine missed/duplicated spiral owner " + owner + " for " + query + ": " + owners);
        int returned = 0;
        for (VoxelShape shape : fixture.view.getBlockCollisions(null, query)) {
            require(!VoxelShapes.matchesAnywhere(shape, direct, BooleanBiFunction.NOT_SAME), "Engine changed the spiral collision voxels");
            returned++;
        }
        require(returned == 1, "World collision path did not return the spiral exactly once");
        if (boundaryAxes(owner, query) == 2) {
            diagonal++;
            if (owner.equals(BlockPos.ORIGIN) && diagonalQuery == null) { diagonalQuery = query; diagonalShape = direct; }
        }
        samples++;
    }

    private static void strictNonSpiralControl() {
        require(diagonalQuery != null, "Missing actual two-boundary spiral regression query");
        Block ordinary = new Block(AbstractBlock.Settings.copy(Blocks.STONE)) {
            @Override public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return diagonalShape; }
        };
        ordinary.getDefaultState().initShapeCache();
        require(ordinary.getDefaultState().exceedsCube() && overlap(diagonalShape, diagonalQuery), "Nonspiral negative control is ineffective");
        require(owners(new Fixture(Map.of(BlockPos.ORIGIN, ordinary.getDefaultState())), diagonalQuery).isEmpty(),
                "Spiral gate incorrectly admitted an unrelated block with the same wide voxels");
    }

    private static void ordinaryReadBudget() {
        Box query = new Box(.2, .2, .2, .3, .3, .3);
        for (Map<BlockPos, BlockState> map : List.of(Map.<BlockPos, BlockState>of(), Map.of(BlockPos.ORIGIN, Blocks.STONE.getDefaultState()))) {
            Fixture fixture = new Fixture(map);
            owners(fixture, query);
            require(fixture.reads == 19, "Spiral fix expanded ordinary collision queries: " + fixture.reads);
        }
    }

    private static List<BlockPos> owners(Fixture fixture, Box query) {
        List<BlockPos> result = new ArrayList<>();
        var iterator = new BlockCollisionSpliterator<>(fixture.view, null, query, false, (pos, shape) -> pos.toImmutable());
        iterator.forEachRemaining(result::add);
        return result;
    }

    private static boolean overlap(VoxelShape shape, Box query) { return VoxelShapes.matchesAnywhere(shape, VoxelShapes.cuboid(query), BooleanBiFunction.AND); }
    private static Box around(BlockPos owner, double x, double y, double z) {
        return new Box(owner.getX() + x - .002, owner.getY() + y - .002, owner.getZ() + z - .002,
                owner.getX() + x + .002, owner.getY() + y + .002, owner.getZ() + z + .002);
    }
    private static int boundaryAxes(BlockPos owner, Box box) {
        double[] low = {box.minX, box.minY, box.minZ}, high = {box.maxX, box.maxY, box.maxZ};
        int[] position = {owner.getX(), owner.getY(), owner.getZ()};
        int count = 0;
        for (int axis = 0; axis < 3; axis++) {
            int first = MathHelper.floor(low[axis] - 1e-7) - 1, last = MathHelper.floor(high[axis] + 1e-7) + 1;
            if (position[axis] < first || position[axis] > last) return -1;
            if (position[axis] == first || position[axis] == last) count++;
        }
        return count;
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final class Fixture {
        final CollisionView view;
        int reads;
        Fixture(Map<BlockPos, BlockState> states) {
            view = (CollisionView) Proxy.newProxyInstance(CollisionView.class.getClassLoader(), new Class<?>[]{CollisionView.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getBlockState" -> { reads++; yield states.getOrDefault(args[0], Blocks.AIR.getDefaultState()); }
                        case "getChunkAsView" -> proxy;
                        case "getFluidState" -> states.getOrDefault(args[0], Blocks.AIR.getDefaultState()).getFluidState();
                        case "getEntityCollisions" -> List.of();
                        case "getWorldBorder" -> new WorldBorder();
                        case "getBlockEntity" -> null;
                        case "getHeight" -> 384;
                        case "getBottomY" -> -64;
                        default -> {
                            if (method.isDefault()) yield InvocationHandler.invokeDefault(proxy, method, args);
                            throw new UnsupportedOperationException(method.toString());
                        }
                    });
        }
    }
}
