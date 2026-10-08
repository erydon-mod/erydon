package com.oliver.erydon.block;

import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.EmptyBlockView;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Shapes are derived once from the same authored components as the wide mesh. */
final class WideArchShapes {
    private record Key(Class<?> family, ArchRomanesqueBlock.Arrangement arrangement,
                       int width, Direction facing, boolean reflected) { }
    private static final Map<Key, VoxelShape> CACHE = new ConcurrentHashMap<>();

    private WideArchShapes() { }

    static VoxelShape shape(BlockState state) {
        Key key = new Key(state.getBlock().getClass(), state.get(ArchRomanesqueBlock.ARRANGEMENT),
                state.get(ArchRomanesqueBlock.WIDTH), state.get(ArchRomanesqueBlock.FACING),
                state.get(ArchRomanesqueBlock.REFLECTED));
        VoxelShape cached = CACHE.get(key);
        if (cached != null) return cached;
        // Resolve dependencies before computeIfAbsent: recursive map updates can share a hash bin.
        VoxelShape previous = key.facing == Direction.NORTH ? null
                : shape(state.with(ArchRomanesqueBlock.FACING, key.facing.rotateYCounterclockwise()));
        return CACHE.computeIfAbsent(key, ignored -> create(state, key, previous));
    }

    private static VoxelShape create(BlockState state, Key key, VoxelShape previous) {
        if (previous != null) {
            VoxelShape[] rotated = {VoxelShapes.empty()};
            previous.forEachBox((x0, y0, z0, x1, y1, z1) -> rotated[0] = VoxelShapes.combine(rotated[0],
                    VoxelShapes.cuboid(1-z1, y0, x0, 1-z0, y1, x1), BooleanBiFunction.OR));
            return rotated[0].simplify();
        }
        VoxelShape[] result = {VoxelShapes.empty()};
        for (var component : WideArchLayout.components(key.arrangement)) {
            BlockState source = state.with(ArchRomanesqueBlock.ARRANGEMENT, component.arrangement())
                    .with(ArchRomanesqueBlock.WIDTH, 3).with(ArchRomanesqueBlock.FACING, Direction.NORTH);
            VoxelShape shape = source.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN, ShapeContext.absent());
            shape.forEachBox((x0, y0, z0, x1, y1, z1) -> {
                double left = Math.max(0, WideArchLayout.x(x0, component, key.arrangement, key.width));
                double right = Math.min(1, WideArchLayout.x(x1, component, key.arrangement, key.width));
                double bottom = Math.max(0, WideArchLayout.y(y0, component, key.arrangement, key.width));
                double top = Math.min(1, WideArchLayout.y(y1, component, key.arrangement, key.width));
                if (right > left && top > bottom) result[0] = VoxelShapes.combine(result[0],
                        VoxelShapes.cuboid(left, bottom, z0, right, top, z1), BooleanBiFunction.OR);
            });
        }
        return result[0].simplify();
    }
}
