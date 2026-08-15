package com.oliver.erydon.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.Waterloggable;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

@SuppressWarnings("deprecation")
public class LayerMultifaceBlock extends Block implements Waterloggable {

    public static final IntProperty LAYERS = Properties.LAYERS; // 1..8
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;

    // Covered faces (all 6)
    public static final BooleanProperty NORTH = BooleanProperty.of("north");
    public static final BooleanProperty SOUTH = BooleanProperty.of("south");
    public static final BooleanProperty EAST  = BooleanProperty.of("east");
    public static final BooleanProperty WEST  = BooleanProperty.of("west");
    public static final BooleanProperty UP    = BooleanProperty.of("up");
    public static final BooleanProperty DOWN  = BooleanProperty.of("down");

    // Vertical face shapes per direction
    private static final VoxelShape[][] V_FACE = new VoxelShape[4][8]; // [dirIndex][layers-1]
    // Horizontal shapes
    private static final VoxelShape[] H_BOTTOM = new VoxelShape[8];
    private static final VoxelShape[] H_TOP = new VoxelShape[8];

    // Combined shapes for a 6-bit mask (0..63) at each layer thickness
    private static final VoxelShape[][] SHAPES = new VoxelShape[64][8];

    /**
     * Raycast helper shape:
     * A small internal "post" so the block is always targetable from above/below,
     * without turning raycast into a full cube (which steals clicks meant for adjacent blocks).
     *
     * Bonus: players can deliberately click a specific side of this post to choose the face to cover.
     */
    private static final VoxelShape TARGET_POST = Block.createCuboidShape(6, 0, 6, 10, 16, 10);

    // Boundary detection tolerance
    private static final double FACE_EPS = 1.0e-3;

    static {
        for (int i = 1; i <= 8; i++) {
            int d = i * 2; // 2..16 px

            // Vertical veneers
            V_FACE[idx(Direction.NORTH)][i - 1] = Block.createCuboidShape(0, 0, 0, 16, 16, d);
            V_FACE[idx(Direction.SOUTH)][i - 1] = Block.createCuboidShape(0, 0, 16 - d, 16, 16, 16);
            V_FACE[idx(Direction.WEST)][i - 1]  = Block.createCuboidShape(0, 0, 0, d, 16, 16);
            V_FACE[idx(Direction.EAST)][i - 1]  = Block.createCuboidShape(16 - d, 0, 0, 16, 16, 16);

            // Horizontal veneers
            H_BOTTOM[i - 1] = Block.createCuboidShape(0, 0, 0, 16, d, 16);
            H_TOP[i - 1] = Block.createCuboidShape(0, 16 - d, 0, 16, 16, 16);

            for (int mask = 0; mask < 64; mask++) {
                VoxelShape s = VoxelShapes.empty();

                if ((mask & bit(Direction.NORTH)) != 0) s = VoxelShapes.union(s, V_FACE[idx(Direction.NORTH)][i - 1]);
                if ((mask & bit(Direction.SOUTH)) != 0) s = VoxelShapes.union(s, V_FACE[idx(Direction.SOUTH)][i - 1]);
                if ((mask & bit(Direction.EAST))  != 0) s = VoxelShapes.union(s, V_FACE[idx(Direction.EAST)][i - 1]);
                if ((mask & bit(Direction.WEST))  != 0) s = VoxelShapes.union(s, V_FACE[idx(Direction.WEST)][i - 1]);

                if ((mask & bit(Direction.DOWN)) != 0) s = VoxelShapes.union(s, H_BOTTOM[i - 1]);
                if ((mask & bit(Direction.UP))   != 0) s = VoxelShapes.union(s, H_TOP[i - 1]);

                SHAPES[mask][i - 1] = s;
            }
        }
    }

    private static int idx(Direction d) {
        return switch (d) {
            case SOUTH -> 1;
            case WEST  -> 2;
            case EAST  -> 3;
            default    -> 0; // NORTH + verticals
        };
    }

    private static int bit(Direction d) {
        return switch (d) {
            case NORTH -> 1;
            case SOUTH -> 2;
            case EAST  -> 4;
            case WEST  -> 8;
            case UP    -> 16;
            case DOWN  -> 32;
            default -> 0;
        };
    }

    private static BooleanProperty prop(Direction d) {
        return switch (d) {
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case EAST -> EAST;
            case WEST -> WEST;
            case UP -> UP;
            case DOWN -> DOWN;
            default -> throw new IllegalArgumentException("Unexpected direction: " + d);
        };
    }

    public LayerMultifaceBlock(Settings settings) {
        super(settings.nonOpaque());
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(LAYERS, 1)
                .with(WATERLOGGED, false)
                .with(NORTH, false)
                .with(SOUTH, false)
                .with(EAST, false)
                .with(WEST, false)
                .with(UP, false)
                .with(DOWN, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(LAYERS, WATERLOGGED, NORTH, SOUTH, EAST, WEST, UP, DOWN);
    }

    @Override
    public boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        return true;
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return VoxelShapes.union(shapeFor(state), TARGET_POST);
    }

    @Override
    public VoxelShape getRaycastShape(BlockState state, BlockView world, BlockPos pos) {
        return VoxelShapes.union(shapeFor(state), TARGET_POST);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapeFor(state);
    }

    private static VoxelShape shapeFor(BlockState state) {
        int mask = faceMask(state);
        int layers = state.get(LAYERS);
        return SHAPES[mask][layers - 1];
    }

    private static int faceMask(BlockState s) {
        int m = 0;
        if (s.get(NORTH)) m |= bit(Direction.NORTH);
        if (s.get(SOUTH)) m |= bit(Direction.SOUTH);
        if (s.get(EAST))  m |= bit(Direction.EAST);
        if (s.get(WEST))  m |= bit(Direction.WEST);
        if (s.get(UP))    m |= bit(Direction.UP);
        if (s.get(DOWN))  m |= bit(Direction.DOWN);
        return m;
    }

    private static int faceCount(BlockState s) {
        int c = 0;
        if (s.get(NORTH)) c++;
        if (s.get(SOUTH)) c++;
        if (s.get(EAST))  c++;
        if (s.get(WEST))  c++;
        if (s.get(UP))    c++;
        if (s.get(DOWN))  c++;
        return c;
    }

    /**
     * Returns the boundary face of THIS block position if the hit is on a boundary plane.
     * Otherwise returns null.
     */
    private static Direction boundaryFaceFromHitPos(ItemPlacementContext ctx) {
        BlockPos pos = ctx.getBlockPos();
        Vec3d hit = ctx.getHitPos();

        double rx = hit.x - pos.getX(); // 0..1-ish
        double ry = hit.y - pos.getY();
        double rz = hit.z - pos.getZ();

        if (Math.abs(rx - 0.0) <= FACE_EPS) return Direction.WEST;
        if (Math.abs(rx - 1.0) <= FACE_EPS) return Direction.EAST;
        if (Math.abs(ry - 0.0) <= FACE_EPS) return Direction.DOWN;
        if (Math.abs(ry - 1.0) <= FACE_EPS) return Direction.UP;
        if (Math.abs(rz - 0.0) <= FACE_EPS) return Direction.NORTH;
        if (Math.abs(rz - 1.0) <= FACE_EPS) return Direction.SOUTH;

        return null;
    }

    private static boolean isAdjacentFaceClick(ItemPlacementContext ctx) {
        Direction boundary = boundaryFaceFromHitPos(ctx);
        return boundary != null && boundary == ctx.getSide().getOpposite();
    }

    /**
     * If this is an adjacent-face click, returns the face of THIS block that touches the clicked neighbor.
     */
    private static Direction adjacentClickedFace(ItemPlacementContext ctx) {
        return boundaryFaceFromHitPos(ctx);
    }

    /**
     * Face selection rule (v6):
     * 1) If clicking an ADJACENT block face -> cover the touching face (ctx.getSide().getOpposite()).
     * 2) Else, if clicking THIS block (post/veneer), and ctx.getSide() is a valid direction -> cover that side.
     *    This makes the center post a deliberate "choose side" tool.
     * 3) Else (rare) -> choose face most opposite to player POSITION (approximated using look vector).
     *    IMPORTANT: we use LOOK (not opposite) so the chosen face is AWAY from the player.
     */
    private static Direction requestedFace(BlockState state, ItemPlacementContext ctx) {
        if (isAdjacentFaceClick(ctx)) {
            return adjacentClickedFace(ctx);
        }

        Direction side = ctx.getSide();
        // If you clicked the post or an existing veneer, "side" is the face you clicked.
        // Using it directly makes left/right (and up/down) selection predictable.
        if (side != null) {
            // side can be any of the 6.
            return side;
        }

        return bestAwayFromPlayerAvailableFace(state, ctx.getPlayer());
    }

    private static Direction bestAwayFromPlayerAvailableFace(BlockState state, PlayerEntity p) {
        if (p == null) {
            // stable fallback order
            if (!state.get(SOUTH)) return Direction.SOUTH;
            if (!state.get(NORTH)) return Direction.NORTH;
            if (!state.get(WEST))  return Direction.WEST;
            if (!state.get(EAST))  return Direction.EAST;
            if (!state.get(DOWN))  return Direction.DOWN;
            if (!state.get(UP))    return Direction.UP;
            return null;
        }

        // Player is (almost always) looking at the block when placing.
        // Using LOOK (not opposite) selects the face that points away from the player.
        Vec3d look = p.getRotationVec(1.0f);

        Direction[] dirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN};
        double bestScore = -9999;
        Direction best = null;

        for (Direction d : dirs) {
            if (state.get(prop(d))) continue;

            Vec3d n = new Vec3d(d.getOffsetX(), d.getOffsetY(), d.getOffsetZ());
            double score = n.dotProduct(look);

            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return best;
    }

    /**
     * When layers==1, clicking a face that's already present should thicken (more intuitive),
     * whether that face came from an adjacent click or a post-side click.
     */
    private static Direction impliedFaceClick(ItemPlacementContext ctx) {
        if (isAdjacentFaceClick(ctx)) return adjacentClickedFace(ctx);
        return ctx.getSide(); // post/veneer click -> the side you clicked
    }

    @Override
    public boolean canReplace(BlockState state, ItemPlacementContext ctx) {
        if (!ctx.getStack().isOf(this.asItem())) return false;

        PlayerEntity p = ctx.getPlayer();
        boolean sneaking = (p != null && p.isSneaking());

        int layers = state.get(LAYERS);
        boolean locked = layers > 1;

        // Thickening: sneak always thickens; once layers>1, any click thickens
        if ((sneaking || locked) && layers < 8) return true;

        // Face-adding phase (layers==1, not sneaking)
        if (layers == 1 && !sneaking) {
            if (faceCount(state) >= 6) return false;

            Direction implied = impliedFaceClick(ctx);
            if (implied != null && state.get(prop(implied))) {
                return layers < 8; // allow thickening by re-clicking the same face
            }

            Direction want = requestedFace(state, ctx);
            return want != null && !state.get(prop(want));
        }

        return false;
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockPos pos = ctx.getBlockPos();
        BlockState existing = ctx.getWorld().getBlockState(pos);

        boolean water = ctx.getWorld().getFluidState(pos).getFluid() == Fluids.WATER;

        // Modify existing
        if (existing.isOf(this)) {
            return nextState(existing, ctx);
        }

        // New placement: start with exactly one face
        BlockState s = this.getDefaultState()
                .with(WATERLOGGED, water)
                .with(LAYERS, 1);

        Direction first = requestedFace(s, ctx);
        if (first == null) first = Direction.SOUTH;

        return s.with(prop(first), true);
    }

    private BlockState nextState(BlockState state, ItemPlacementContext ctx) {
        PlayerEntity p = ctx.getPlayer();
        boolean sneaking = (p != null && p.isSneaking());

        int layers = state.get(LAYERS);
        boolean locked = layers > 1;

        // Thickening
        if ((sneaking || locked) && layers < 8) {
            return state.with(LAYERS, layers + 1);
        }

        // Face-adding phase (layers==1)
        if (layers == 1) {
            Direction implied = impliedFaceClick(ctx);
            if (implied != null && state.get(prop(implied)) && layers < 8) {
                return state.with(LAYERS, layers + 1);
            }

            if (faceCount(state) < 6) {
                Direction want = requestedFace(state, ctx);
                if (want != null && !state.get(prop(want))) {
                    return state.with(prop(want), true);
                }
            }
        }

        return state;
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.get(WATERLOGGED) ? Fluids.WATER.getStill(false) : super.getFluidState(state);
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return withFaceMask(state, rotateMask(faceMask(state), rotation));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        return withFaceMask(state, mirrorMask(faceMask(state), mirror));
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction dir, BlockState neighbour,
                                                WorldAccess world, BlockPos pos, BlockPos neighbourPos) {
        if (state.get(WATERLOGGED)) {
            world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
        }
        return super.getStateForNeighborUpdate(state, dir, neighbour, world, pos, neighbourPos);
    }

    private static BlockState withFaceMask(BlockState state, int mask) {
        return state
                .with(NORTH, (mask & bit(Direction.NORTH)) != 0)
                .with(SOUTH, (mask & bit(Direction.SOUTH)) != 0)
                .with(EAST, (mask & bit(Direction.EAST)) != 0)
                .with(WEST, (mask & bit(Direction.WEST)) != 0)
                .with(UP, (mask & bit(Direction.UP)) != 0)
                .with(DOWN, (mask & bit(Direction.DOWN)) != 0);
    }

    private static int rotateMask(int mask, BlockRotation rotation) {
        int rotatedMask = 0;
        for (Direction direction : Direction.values()) {
            if ((mask & bit(direction)) != 0) {
                rotatedMask |= bit(rotateDirection(direction, rotation));
            }
        }
        return rotatedMask;
    }

    private static int mirrorMask(int mask, BlockMirror mirror) {
        int mirroredMask = 0;
        for (Direction direction : Direction.values()) {
            if ((mask & bit(direction)) != 0) {
                mirroredMask |= bit(mirror.apply(direction));
            }
        }
        return mirroredMask;
    }

    private static Direction rotateDirection(Direction direction, BlockRotation rotation) {
        return direction.getAxis().isHorizontal() ? rotation.rotate(direction) : direction;
    }
}
