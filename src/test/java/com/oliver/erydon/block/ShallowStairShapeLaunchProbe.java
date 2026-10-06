package com.oliver.erydon.block;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.StairShape;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Compares original and shared stair voxels under Fabric's Minecraft runtime. */
public final class ShallowStairShapeLaunchProbe implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        try {
            SharedConstants.createGameVersion();
            Bootstrap.initialize();
            GlazingShallowSlopeLaunchChecks.run();
            Method original = ShallowStairsBlockBase.class.getDeclaredMethod("calculateShape", BlockState.class);
            Method shared = ShallowStairsBlockBase.class.getDeclaredMethod("sharedShape", BlockState.class);
            original.setAccessible(true);
            shared.setAccessible(true);
            List<ShallowStairsBlockBase> blocks = List.of(
                    bottom(Blocks.STONE), top(Blocks.STONE),
                    bottom(Blocks.OAK_PLANKS), top(Blocks.OAK_PLANKS),
                    bottom(Blocks.GLASS), top(Blocks.GLASS));
            var executor = Executors.newFixedThreadPool(12);
            try {
                BlockState concurrentState = blocks.get(0).getDefaultState()
                        .with(StairsBlock.SHAPE, StairShape.INNER_RIGHT).with(StairsBlock.FACING, Direction.EAST);
                var calls = new java.util.ArrayList<java.util.concurrent.Callable<VoxelShape>>();
                for (int i = 0; i < 120; i++) {
                    calls.add(() -> blocks.get(0).getOutlineShape(concurrentState, null, BlockPos.ORIGIN, null));
                }
                var results = executor.invokeAll(calls);
                VoxelShape first = results.get(0).get(20, TimeUnit.SECONDS);
                for (var result : results) {
                    if (result.get(20, TimeUnit.SECONDS) != first) {
                        throw new AssertionError("Concurrent first calls did not publish one shape");
                    }
                }
            } finally {
                executor.shutdownNow();
            }
            Map<Integer, VoxelShape> slots = new HashMap<>();
            int cases = 0;
            for (ShallowStairsBlockBase block : blocks) {
                if (block.getClass() != ShallowStairsTopBlock.class
                        && block.getClass() != ShallowStairsBottomBlock.class) {
                    throw new AssertionError("Unreviewed shallow-stair subclass: " + block.getClass());
                }
                for (Direction facing : Direction.Type.HORIZONTAL) {
                    for (StairShape shape : StairShape.values()) {
                        for (BlockHalf half : BlockHalf.values()) {
                            for (boolean waterlogged : new boolean[]{false, true}) {
                                BlockState state = block.getDefaultState()
                                        .with(StairsBlock.FACING, facing)
                                        .with(StairsBlock.SHAPE, shape)
                                        .with(StairsBlock.HALF, half)
                                        .with(ShallowStairsBlockBase.WATERLOGGED, waterlogged);
                                VoxelShape expected = (VoxelShape) original.invoke(block, state);
                                VoxelShape actual = block.getOutlineShape(state, null, BlockPos.ORIGIN, null);
                                if (VoxelShapes.matchesAnywhere(expected, actual, BooleanBiFunction.NOT_SAME)
                                        || VoxelShapes.matchesAnywhere(actual,
                                        block.getCollisionShape(state, null, BlockPos.ORIGIN, null),
                                        BooleanBiFunction.NOT_SAME)) {
                                    throw new AssertionError("Changed voxels: " + block.getClass() + " " + state);
                                }
                                if ((block.getFluidState(state).getFluid() == Fluids.WATER) != waterlogged) {
                                    throw new AssertionError("Changed waterlogging: " + block.getClass() + " " + state);
                                }
                                VoxelShape sharedShape = (VoxelShape) shared.invoke(block, state);
                                if (VoxelShapes.matchesAnywhere(expected, sharedShape, BooleanBiFunction.NOT_SAME)) {
                                    throw new AssertionError("Shared slot changed voxels: " + block.getClass() + " " + state);
                                }
                                int facingIndex = switch (facing) {
                                    case NORTH -> 0;
                                    case EAST -> 1;
                                    case SOUTH -> 2;
                                    case WEST -> 3;
                                    default -> throw new AssertionError(facing);
                                };
                                int slot = StairShapeSlots.index(block instanceof ShallowStairsTopBlock,
                                        facingIndex, shape.ordinal());
                                VoxelShape prior = slots.putIfAbsent(slot, sharedShape);
                                if (prior != null && prior != sharedShape) {
                                    throw new AssertionError("Shape reference was not shared for slot " + slot);
                                }
                                cases++;
                            }
                        }
                    }
                }
            }
            if (slots.size() != StairShapeSlots.COUNT) throw new AssertionError("Incomplete shared slots");
            BlockState bottomStraight = blocks.get(0).getDefaultState()
                    .with(StairsBlock.SHAPE, StairShape.STRAIGHT);
            if (blocks.get(0).getOutlineShape(bottomStraight, null, BlockPos.ORIGIN, null)
                    .getMin(Direction.Axis.Y) >= 0) {
                throw new AssertionError("Bottom stair lost negative-Y overhang");
            }
            VoxelShape outerLeft = blocks.get(0).getOutlineShape(
                    bottomStraight.with(StairsBlock.SHAPE, StairShape.OUTER_LEFT), null, BlockPos.ORIGIN, null);
            VoxelShape outerRight = blocks.get(0).getOutlineShape(
                    bottomStraight.with(StairsBlock.SHAPE, StairShape.OUTER_RIGHT), null, BlockPos.ORIGIN, null);
            if (!VoxelShapes.matchesAnywhere(outerLeft, outerRight, BooleanBiFunction.NOT_SAME)) {
                throw new AssertionError("Outer corners lost handedness");
            }
            System.out.println("ERYDON_SHALLOW_STAIRS_OK: blocks=" + blocks.size() + " states=" + cases
                    + " sharedSlots=" + slots.size()
                    + " enabled=" + Boolean.getBoolean("erydon.perf.shared_stair_shapes"));
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static ShallowStairsBlockBase bottom(Block base) {
        return new ShallowStairsBottomBlock(base.getDefaultState(), AbstractBlock.Settings.copy(base));
    }

    private static ShallowStairsBlockBase top(Block base) {
        return new ShallowStairsTopBlock(base.getDefaultState(), AbstractBlock.Settings.copy(base));
    }
}
