package com.oliver.erydon.block;

import com.oliver.erydon.ErydonConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import org.jetbrains.annotations.Nullable;

public final class OilBurnerBlock extends Block {

    public static final BooleanProperty OFFSET = BooleanProperty.of("offset");
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;

    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.NORTH,
            Direction.EAST,
            Direction.SOUTH,
            Direction.WEST
    };

    /*
     * A stepped octagonal profile follows the six stone rings in the model.
     * The flame remains selectable, but its outline is a thin crossed pair of
     * planes instead of the old near-full-block rectangular prism.
     */
    private static final VoxelShape BODY_SHAPE = VoxelShapes.union(
            DecorShapeTransforms.radialLayer(3.65, 0.0, 0.32),
            DecorShapeTransforms.radialLayer(4.25, 0.28, 0.65),
            DecorShapeTransforms.radialLayer(4.8, 0.60, 1.17),
            DecorShapeTransforms.radialLayer(5.45, 1.05, 1.75),
            DecorShapeTransforms.radialLayer(6.2, 1.65, 2.63),
            DecorShapeTransforms.radialLayer(7.0, 2.50, 3.60)
    ).simplify();
    private static final VoxelShape FLAME_SHAPE = VoxelShapes.union(
            Block.createCuboidShape(7.5, 3.5, 0.5, 8.5, 18.5, 15.5),
            Block.createCuboidShape(0.5, 3.5, 7.5, 15.5, 18.5, 8.5)
    ).simplify();
    private static final VoxelShape[][] COLLISION_SHAPES = createShapeCache(false);
    private static final VoxelShape[][] OUTLINE_SHAPES = createShapeCache(true);

    public OilBurnerBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.getStateManager().getDefaultState()
                .with(OFFSET, false)
                .with(FACING, Direction.NORTH));
    }

    public static int luminance(BlockState state) {
        return ErydonConfig.oilBurnerLightLevel();
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(OFFSET, FACING);
    }

    @Override
    public @Nullable BlockState getPlacementState(ItemPlacementContext ctx) {
        return this.getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        return state.rotate(mirror.getRotation(state.get(FACING)));
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return outlineShapeFor(state.get(OFFSET), state.get(FACING));
    }

    @Override
    public VoxelShape getRaycastShape(BlockState state, BlockView world, BlockPos pos) {
        return outlineShapeFor(state.get(OFFSET), state.get(FACING));
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return collisionShapeFor(state.get(OFFSET), state.get(FACING));
    }

    @Override
    public VoxelShape getCullingShape(BlockState state, BlockView world, BlockPos pos) {
        return VoxelShapes.empty();
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    static VoxelShape collisionShapeFor(boolean offset, Direction facing) {
        return COLLISION_SHAPES[offset ? 1 : 0][DecorShapeTransforms.horizontalIndex(facing)];
    }

    static VoxelShape outlineShapeFor(boolean offset, Direction facing) {
        return OUTLINE_SHAPES[offset ? 1 : 0][DecorShapeTransforms.horizontalIndex(facing)];
    }

    private static VoxelShape[][] createShapeCache(boolean includeFlameAndTarget) {
        VoxelShape[][] cache = new VoxelShape[2][HORIZONTAL_DIRECTIONS.length];
        VoxelShape sourceShape = includeFlameAndTarget
                ? VoxelShapes.union(BODY_SHAPE, FLAME_SHAPE).simplify()
                : BODY_SHAPE;
        for (int offsetIndex = 0; offsetIndex < 2; offsetIndex++) {
            boolean offset = offsetIndex == 1;
            for (Direction facing : HORIZONTAL_DIRECTIONS) {
                VoxelShape transformed = DecorShapeTransforms.transformFromNorth(
                        sourceShape,
                        offset ? DecorShapeTransforms.OIL_BURNER_OFFSET_SCALE : 1.0,
                        offset ? DecorShapeTransforms.OFFSET_DISTANCE : 0.0,
                        offset ? DecorShapeTransforms.OFFSET_BASE_Y : 0.0,
                        facing
                );
                cache[offsetIndex][DecorShapeTransforms.horizontalIndex(facing)] =
                        includeFlameAndTarget && offset
                                ? VoxelShapes.union(transformed, DecorShapeTransforms.TARGET_POST).simplify()
                                : transformed;
            }
        }
        return cache;
    }
}
