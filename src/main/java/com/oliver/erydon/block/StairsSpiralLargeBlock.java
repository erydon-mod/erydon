package com.oliver.erydon.block;

import com.oliver.erydon.util.ClusterRecalcSafety;
import net.minecraft.block.*;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.*;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StairsSpiralLargeBlock extends HorizontalFacingBlock implements ClusterRebuildableBlock {

    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;

    public enum Part implements StringIdentifiable {
        A, B, C, D;
        @Override public String asString() { return name().toLowerCase(); }
    }

    public static final EnumProperty<Part> PART = EnumProperty.of("part", Part.class);

    public static final BooleanProperty CAP = BooleanProperty.of("cap");

    /**
     * You said the *models* need to be rotated 90° CW "in place".
     * If you are doing that via the blockstate y-rotation (+90), keep this as rotateYClockwise().
     * If you later re-export models correctly and return to normal blockstate y values, change this to: return state.get(FACING);
     */
    private static Direction visualFacing(BlockState state) {
        return state.get(FACING).rotateYClockwise();
    }


    // Per-part collision (each quadrant has its own collision VoxelShape; rotate per-facing).
    private static final VoxelShape COLLISION_A_NORTH = makeCollisionShapeA();
    private static final VoxelShape COLLISION_A_EAST  = rotateShape(Direction.NORTH, Direction.EAST,  COLLISION_A_NORTH);
    private static final VoxelShape COLLISION_A_SOUTH = rotateShape(Direction.NORTH, Direction.SOUTH, COLLISION_A_NORTH);
    private static final VoxelShape COLLISION_A_WEST  = rotateShape(Direction.NORTH, Direction.WEST,  COLLISION_A_NORTH);

    private static final VoxelShape COLLISION_B_NORTH = makeCollisionShapeB();
    private static final VoxelShape COLLISION_B_EAST  = rotateShape(Direction.NORTH, Direction.EAST,  COLLISION_B_NORTH);
    private static final VoxelShape COLLISION_B_SOUTH = rotateShape(Direction.NORTH, Direction.SOUTH, COLLISION_B_NORTH);
    private static final VoxelShape COLLISION_B_WEST  = rotateShape(Direction.NORTH, Direction.WEST,  COLLISION_B_NORTH);

    private static final VoxelShape COLLISION_C_NORTH = makeCollisionShapeC();
    private static final VoxelShape COLLISION_C_EAST  = rotateShape(Direction.NORTH, Direction.EAST,  COLLISION_C_NORTH);
    private static final VoxelShape COLLISION_C_SOUTH = rotateShape(Direction.NORTH, Direction.SOUTH, COLLISION_C_NORTH);
    private static final VoxelShape COLLISION_C_WEST  = rotateShape(Direction.NORTH, Direction.WEST,  COLLISION_C_NORTH);

    private static final VoxelShape COLLISION_D_NORTH = makeCollisionShapeD();
    private static final VoxelShape COLLISION_D_EAST  = rotateShape(Direction.NORTH, Direction.EAST,  COLLISION_D_NORTH);
    private static final VoxelShape COLLISION_D_SOUTH = rotateShape(Direction.NORTH, Direction.SOUTH, COLLISION_D_NORTH);
    private static final VoxelShape COLLISION_D_WEST  = rotateShape(Direction.NORTH, Direction.WEST,  COLLISION_D_NORTH);

    // Off-step collision (top cap only; part D)
    private static final VoxelShape OFFSTEP_D_NORTH = makeOffstepShapeD();
    private static final VoxelShape OFFSTEP_D_EAST  = rotateShape(Direction.NORTH, Direction.EAST,  OFFSTEP_D_NORTH);
    private static final VoxelShape OFFSTEP_D_SOUTH = rotateShape(Direction.NORTH, Direction.SOUTH, OFFSTEP_D_NORTH);
    private static final VoxelShape OFFSTEP_D_WEST  = rotateShape(Direction.NORTH, Direction.WEST,  OFFSTEP_D_NORTH);

    public StairsSpiralLargeBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(PART, Part.A)
                .with(CAP, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART, CAP);
    }

    // Placement:
    // - First block: use player facing
    // - Blocks placed directly on top: rotate 90° clockwise from block below
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        World world = ctx.getWorld();
        BlockPos pos = ctx.getBlockPos();

        Direction facing;
        BlockPos originA;
        Part partToPlace;

        BlockState below = world.getBlockState(pos.down());
        BlockState above = world.getBlockState(pos.up());

        // 1) Stacking UP: use the multiblock below and rotate 90° CW
        if (below.getBlock() instanceof StairsSpiralLargeBlock) {
            // Find the *true* origin of the multiblock below, no matter which quadrant we clicked
            Direction belowFacing = below.get(FACING);
            Part belowPart = below.get(PART);
            BlockPos belowOriginA = getOriginPosFromAnyPart(pos.down(), belowFacing, belowPart);
            BlockPos belowB = getBPosFromOriginA(belowOriginA, belowFacing);

            // New layer rotates 90° clockwise from the layer below
            facing = belowFacing.rotateYClockwise();

            // Pivot B stays fixed in X/Z, goes up one layer
            BlockPos thisLayerB = belowB.up();

            // Derive new origin A for this layer from the pivot
            originA = getOriginAFromBPos(thisLayerB, facing);

            // Determine which part we are placing at the clicked position
            partToPlace = getPartAt(originA, facing, pos);
            if (partToPlace == null) {
                // Clicked outside the 2x2 footprint
                return null;
            }

            // 2) Stacking DOWN: use the multiblock above and rotate 90° CCW
        } else if (above.getBlock() instanceof StairsSpiralLargeBlock) {
            Direction aboveFacing = above.get(FACING);
            Part abovePart = above.get(PART);
            BlockPos aboveOriginA = getOriginPosFromAnyPart(pos.up(), aboveFacing, abovePart);
            BlockPos aboveB = getBPosFromOriginA(aboveOriginA, aboveFacing);

            // New lower layer rotates opposite (CCW) to maintain the same spiral direction
            facing = aboveFacing.rotateYCounterclockwise();

            // Pivot B stays fixed in X/Z, goes down one layer
            BlockPos thisLayerB = aboveB.down();

            // Derive origin A for this lower layer
            originA = getOriginAFromBPos(thisLayerB, facing);

            // Work out which quadrant we’re actually placing at
            partToPlace = getPartAt(originA, facing, pos);
            if (partToPlace == null) {
                return null;
            }

            // 3) Fresh placement: clicked pos is A of a new 2x2 stack
        } else {
            facing = ctx.getHorizontalPlayerFacing();
            originA = pos;
            partToPlace = Part.A;
        }

        // Validate space for the full 2x2 footprint at this layer
        for (BlockPos p : getAllPartPositions(originA, facing).values()) {
            BlockState existing = world.getBlockState(p);
            if (!existing.isAir() && !existing.isReplaceable()) {
                return null;
            }
        }

        return getDefaultState()
                .with(FACING, facing)
                .with(PART, partToPlace);
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state,
                         @Nullable LivingEntity placer, ItemStack itemStack) {
        if (world.isClient) return;

        Direction facing = state.get(FACING);
        Part placedPart = state.get(PART);

        // Compute origin from whichever quadrant was clicked (A/B/C/D)
        BlockPos originA = getOriginPosFromAnyPart(pos, facing, placedPart);

        // Place/ensure all 4 parts exist
        for (Map.Entry<Part, BlockPos> e : getAllPartPositions(originA, facing).entrySet()) {
            Part part = e.getKey();
            BlockPos p = e.getValue();

            BlockState target = state.with(PART, part);
            if (world.getBlockState(p) != target) {
                world.setBlockState(p, target, Block.NOTIFY_ALL);
            }
        }

        // Update cap state for this layer
        syncCapForLayer(world, originA, facing);

        // If there's a layer below (same pivot B), update it too (it may no longer be the top)
        BlockPos bPos = getBPosFromOriginA(originA, facing);
        BlockState below = world.getBlockState(bPos.down());
        if (below.getBlock() instanceof StairsSpiralLargeBlock) {
            BlockPos belowOriginA = getOriginPosFromAnyPart(bPos.down(), below.get(FACING), below.get(PART));
            syncCapForLayer(world, belowOriginA, below.get(FACING));
        }
    }

    @Nullable
    private static Part getPartAt(BlockPos originA, Direction facing, BlockPos query) {
        for (Map.Entry<Part, BlockPos> e : getAllPartPositions(originA, facing).entrySet()) {
            if (e.getValue().equals(query)) return e.getKey();
        }
        return null;
    }

    private static boolean computeCap(BlockView world, BlockPos originA, Direction facing) {
        BlockPos bPos = getBPosFromOriginA(originA, facing);
        return !(world.getBlockState(bPos.up()).getBlock() instanceof StairsSpiralLargeBlock);
    }

    private static void syncCapForLayer(World world, BlockPos originA, Direction facing) {
        boolean cap = computeCap(world, originA, facing);

        for (Map.Entry<Part, BlockPos> e : getAllPartPositions(originA, facing).entrySet()) {
            BlockPos p = e.getValue();
            BlockState s = world.getBlockState(p);
            if (s.getBlock() instanceof StairsSpiralLargeBlock && s.contains(CAP)) {
                if (s.get(CAP) != cap) {
                    world.setBlockState(p, s.with(CAP, cap), Block.NOTIFY_LISTENERS);
                }
            }
        }
    }

    @Override
    public ClusterRecalcResult recalcCluster(World world, BlockPos seed) {
        BlockState seedState = world.getBlockState(seed);
        if (!seedState.isOf(this)) {
            return ClusterRecalcResult.none();
        }

        LayerPlan seedLayer = detectLayer(world, seed, seedState.get(FACING));
        if (seedLayer == null) {
            return ClusterRecalcResult.none();
        }

        List<LayerPlan> stack = collectLayerStack(world, seedLayer);
        if (stack.isEmpty()) {
            return ClusterRecalcResult.none();
        }

        Set<BlockPos> touched = new LinkedHashSet<>();
        for (LayerPlan layer : stack) {
            for (BlockPos pos : getAllPartPositions(layer.originA(), layer.facing()).values()) {
                if (!ClusterRecalcSafety.claim(pos)) {
                    break;
                }
                touched.add(pos);
            }
        }
        ClusterRecalcResult unsafe = ClusterRecalcSafety.unsafeResult(touched);
        if (unsafe != null) {
            return unsafe;
        }

        for (LayerPlan layer : stack) {
            rebuildLayer(world, layer);
        }
        for (LayerPlan layer : stack) {
            syncCapForLayer(world, layer.originA(), layer.facing());
        }

        return new ClusterRecalcResult(touched, true);
    }

    private List<LayerPlan> collectLayerStack(World world, LayerPlan seedLayer) {
        List<LayerPlan> stack = new ArrayList<>();
        stack.add(seedLayer);

        LayerPlan cursor = seedLayer;
        while (true) {
            LayerPlan below = findLayerByPivot(world, getBPosFromOriginA(cursor.originA(), cursor.facing()).down());
            if (below == null || stack.contains(below)) {
                break;
            }
            if (ClusterRecalcSafety.isActive()
                    && stack.size() >= ClusterRecalcSafety.MAX_SPIRAL_LAYERS) {
                ClusterRecalcSafety.markTooLarge();
                break;
            }
            stack.add(0, below);
            cursor = below;
        }

        cursor = seedLayer;
        while (true) {
            LayerPlan above = findLayerByPivot(world, getBPosFromOriginA(cursor.originA(), cursor.facing()).up());
            if (above == null || stack.contains(above)) {
                break;
            }
            if (ClusterRecalcSafety.isActive()
                    && stack.size() >= ClusterRecalcSafety.MAX_SPIRAL_LAYERS) {
                ClusterRecalcSafety.markTooLarge();
                break;
            }
            stack.add(above);
            cursor = above;
        }

        return stack;
    }

    @Nullable
    private LayerPlan findLayerByPivot(World world, BlockPos pivotBPos) {
        LayerPlan best = null;
        LayerScore bestScore = LayerScore.NONE;

        for (int z = pivotBPos.getZ() - 1; z <= pivotBPos.getZ() + 1; z++) {
            for (int x = pivotBPos.getX() - 1; x <= pivotBPos.getX() + 1; x++) {
                BlockPos candidatePos = new BlockPos(x, pivotBPos.getY(), z);
                BlockState candidateState = ClusterRecalcSafety.getBlockState(world, candidatePos);
                if (!candidateState.isOf(this)) {
                    continue;
                }

                LayerPlan candidate = detectLayer(world, candidatePos, candidateState.get(FACING));
                if (candidate == null) {
                    continue;
                }
                if (!getBPosFromOriginA(candidate.originA(), candidate.facing()).equals(pivotBPos)) {
                    continue;
                }

                LayerScore score = scoreLayer(world, candidate.originA(), candidate.facing());
                if (score.betterThan(bestScore)) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }

        return best;
    }

    @Nullable
    private LayerPlan detectLayer(World world, BlockPos pos, Direction facing) {
        BlockState seedState = ClusterRecalcSafety.getBlockState(world, pos);
        LayerPlan best = null;
        LayerScore bestScore = LayerScore.NONE;

        for (Part assumedPart : Part.values()) {
            BlockPos originA = getOriginPosFromAnyPart(pos, facing, assumedPart);
            LayerScore score = scoreLayer(world, originA, facing);

            if (seedState.isOf(this) && seedState.get(PART) == assumedPart) {
                score = score.withPartTieBreak();
            }

            if (score.isUsable() && score.betterThan(bestScore)) {
                best = new LayerPlan(originA, facing);
                bestScore = score;
            }
        }

        return best;
    }

    private LayerScore scoreLayer(World world, BlockPos originA, Direction facing) {
        int blockMatches = 0;
        int facingMatches = 0;
        int partMatches = 0;

        for (Map.Entry<Part, BlockPos> entry : getAllPartPositions(originA, facing).entrySet()) {
            BlockState state = ClusterRecalcSafety.getBlockState(world, entry.getValue());
            if (!state.isOf(this)) {
                continue;
            }

            blockMatches++;
            if (state.get(FACING) == facing) {
                facingMatches++;
            }
            if (state.get(PART) == entry.getKey()) {
                partMatches++;
            }
        }

        return new LayerScore(blockMatches, facingMatches, partMatches);
    }

    private void rebuildLayer(World world, LayerPlan layer) {
        BlockState baseState = getDefaultState()
                .with(FACING, layer.facing())
                .with(CAP, false);

        for (Map.Entry<Part, BlockPos> entry : getAllPartPositions(layer.originA(), layer.facing()).entrySet()) {
            BlockState target = baseState.with(PART, entry.getKey());
            if (ClusterRecalcSafety.getBlockState(world, entry.getValue()) != target) {
                world.setBlockState(entry.getValue(), target,
                        ClusterRecalcSafety.updateFlags(Block.NOTIFY_ALL));
            }
        }
    }

    @Override
    public void neighborUpdate(BlockState state, World world, BlockPos pos,
                               Block sourceBlock, BlockPos sourcePos, boolean notify) {
        super.neighborUpdate(state, world, pos, sourceBlock, sourcePos, notify);
        if (world.isClient) return;

        // CAP only depends on vertical stacking; ignore purely horizontal updates
        if (sourcePos.getY() != pos.getY() + 1 && sourcePos.getY() != pos.getY() - 1) return;

        Direction facing = state.get(FACING);
        Part part = state.get(PART);
        BlockPos originA = getOriginPosFromAnyPart(pos, facing, part);
        syncCapForLayer(world, originA, facing);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (state.get(CAP) && state.get(PART) == Part.D) {
            Direction facing = visualFacing(state);

            VoxelShape base = switch (facing) {
                case EAST  -> COLLISION_D_EAST;
                case SOUTH -> COLLISION_D_SOUTH;
                case WEST  -> COLLISION_D_WEST;
                default    -> COLLISION_D_NORTH;
            };

            VoxelShape extra = switch (facing) {
                case EAST  -> OFFSTEP_D_EAST;
                case SOUTH -> OFFSTEP_D_SOUTH;
                case WEST  -> OFFSTEP_D_WEST;
                default    -> OFFSTEP_D_NORTH;
            };

            return VoxelShapes.union(base, extra);
        }

        return VoxelShapes.fullCube();
    }


    @Override
    public VoxelShape getRaycastShape(BlockState state, BlockView world, BlockPos pos) {
        if (state.get(CAP) && state.get(PART) == Part.D) {
            Direction facing = visualFacing(state);

            VoxelShape base = switch (facing) {
                case EAST  -> COLLISION_D_EAST;
                case SOUTH -> COLLISION_D_SOUTH;
                case WEST  -> COLLISION_D_WEST;
                default    -> COLLISION_D_NORTH;
            };

            VoxelShape extra = switch (facing) {
                case EAST  -> OFFSTEP_D_EAST;
                case SOUTH -> OFFSTEP_D_SOUTH;
                case WEST  -> OFFSTEP_D_WEST;
                default    -> OFFSTEP_D_NORTH;
            };

            return VoxelShapes.union(base, extra);
        }

        return VoxelShapes.fullCube();
    }



    // Collision: only the anchor part contributes the big 2x2 collision (covers the entire structure).
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        Direction facing = visualFacing(state);
        return switch (state.get(PART)) {
            case A -> switch (facing) {
                case EAST  -> COLLISION_A_EAST;
                case SOUTH -> COLLISION_A_SOUTH;
                case WEST  -> COLLISION_A_WEST;
                default    -> COLLISION_A_NORTH;
            };
            case B -> {
                VoxelShape base = switch (facing) {
                    case EAST  -> COLLISION_B_EAST;
                    case SOUTH -> COLLISION_B_SOUTH;
                    case WEST  -> COLLISION_B_WEST;
                    default    -> COLLISION_B_NORTH;
                };

                if (state.get(CAP)) {
                    VoxelShape extra = switch (facing) {
                        case EAST  -> OFFSTEP_D_EAST;
                        case SOUTH -> OFFSTEP_D_SOUTH;
                        case WEST  -> OFFSTEP_D_WEST;
                        default    -> OFFSTEP_D_NORTH;
                    };
                    yield VoxelShapes.union(base, extra);
                }

                yield base;
            }

            case C -> switch (facing) {
                case EAST  -> COLLISION_C_EAST;
                case SOUTH -> COLLISION_C_SOUTH;
                case WEST  -> COLLISION_C_WEST;
                default    -> COLLISION_C_NORTH;
            };
            case D -> {
                VoxelShape base = switch (facing) {
                    case EAST  -> COLLISION_D_EAST;
                    case SOUTH -> COLLISION_D_SOUTH;
                    case WEST  -> COLLISION_D_WEST;
                    default    -> COLLISION_D_NORTH;
                };
                if (state.get(CAP)) {
                    VoxelShape extra = switch (facing) {
                        case EAST  -> OFFSTEP_D_EAST;
                        case SOUTH -> OFFSTEP_D_SOUTH;
                        case WEST  -> OFFSTEP_D_WEST;
                        default    -> OFFSTEP_D_NORTH;
                    };
                    yield VoxelShapes.union(base, extra);
                }
                yield base;
            }
        };
    }
// Break/removal: remove all 4 parts if any is broken/replaced
    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos,
                                BlockState newState, boolean moved) {
        if (!world.isClient && state.getBlock() != newState.getBlock()) {
            removeWholeStructure(world, state, pos);
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }

    @Override
    public void onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (!world.isClient) {
            BlockPos origin = getOriginPosFromAnyPart(pos, state.get(FACING), state.get(PART));
            BlockState originState = world.getBlockState(origin);

            removeWholeStructure(world, state, pos);

            if (!player.isCreative() && originState.getBlock() == this) {
                Block.dropStacks(originState, world, origin, null, player, player.getMainHandStack());
            }
            return;
        }
        super.onBreak(world, pos, state, player);
    }

    private void removeWholeStructure(World world, BlockState state, BlockPos pos) {
        Direction facing = state.get(FACING);
        BlockPos origin = getOriginPosFromAnyPart(pos, facing, state.get(PART));

        for (BlockPos p : getAllPartPositions(origin, facing).values()) {
            if (world.getBlockState(p).getBlock() == this) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        if (mirror == BlockMirror.NONE) {
            return state;
        }

        return state.rotate(mirror.getRotation(state.get(FACING)))
                .with(PART, mirrorPart(state.get(PART)));
    }

    // ===== 2x2 footprint math (uses visualFacing so parts stay correct with the +90 model offset) =====

    // Layout for NORTH (origin at A):
    //      C D
    //      A B
    private static Map<Part, BlockPos> getAllPartPositions(BlockPos originA, Direction facing) {
        Map<Part, BlockPos> out = new EnumMap<>(Part.class);
        put(out, Part.A, originA, facing, 0, 0);
        put(out, Part.B, originA, facing, 1, 0);
        put(out, Part.C, originA, facing, 0, -1);
        put(out, Part.D, originA, facing, 1, -1);
        return out;
    }

    private static void put(Map<Part, BlockPos> out, Part part, BlockPos origin, Direction facing, int dx, int dz) {
        Vec2i r = rotateOffset(dx, dz, facing);
        out.put(part, origin.add(r.x, 0, r.z));
    }

    private static BlockPos getOriginPosFromAnyPart(BlockPos pos, Direction facing, Part part) {
        int dx = 0, dz = 0;
        switch (part) {
            case A -> { dx = 0; dz = 0; }
            case B -> { dx = 1; dz = 0; }
            case C -> { dx = 0; dz = -1; }
            case D -> { dx = 1; dz = -1; }
        }
        Vec2i r = rotateOffset(dx, dz, facing);
        return pos.add(-r.x, 0, -r.z);
    }

    private static Vec2i rotateOffset(int dx, int dz, Direction facing) {
        return switch (facing) {
            case NORTH -> new Vec2i(dx, dz);
            case EAST  -> new Vec2i(-dz, dx);
            case SOUTH -> new Vec2i(-dx, -dz);
            case WEST  -> new Vec2i(dz, -dx);
            default    -> new Vec2i(dx, dz);
        };
    }
    private static BlockPos getBPosFromOriginA(BlockPos originA, Direction facing) {
        Vec2i r = rotateOffset(1, 0, facing); // B is (1,0) in NORTH layout
        return originA.add(r.x, 0, r.z);
    }

    private static BlockPos getOriginAFromBPos(BlockPos bPos, Direction facing) {
        Vec2i r = rotateOffset(1, 0, facing);
        return bPos.add(-r.x, 0, -r.z);
    }

    private static Part mirrorPart(Part part) {
        return switch (part) {
            case A -> Part.B;
            case B -> Part.A;
            case C -> Part.D;
            case D -> Part.C;
        };
    }

    private record Vec2i(int x, int z) {}

    private record LayerPlan(BlockPos originA, Direction facing) {}

    private record LayerScore(int blockMatches, int facingMatches, int partMatches) {
        private static final LayerScore NONE = new LayerScore(0, 0, 0);

        private boolean isUsable() {
            return blockMatches >= 2 && facingMatches >= 2;
        }

        private boolean betterThan(LayerScore other) {
            if (facingMatches != other.facingMatches) {
                return facingMatches > other.facingMatches;
            }
            if (blockMatches != other.blockMatches) {
                return blockMatches > other.blockMatches;
            }
            return partMatches > other.partMatches;
        }

        private LayerScore withPartTieBreak() {
            return new LayerScore(blockMatches, facingMatches, partMatches + 1);
        }
    }

    // ===== Full 2x2 collision shape (your attached voxels) =====

    // Rotate a shape from one horizontal direction to another, 90° steps


    private static VoxelShape makeOffstepShapeD() {
        VoxelShape shape = VoxelShapes.empty();

        // Off-step (model e) from E.txt
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(
                0.0006249985694886107, 0.7516874974966049, -0.004249998629093199,
                0.7978749985694886,    0.999812497496605,  0.4407500013709068
        ));

        return shape;
    }



    private static VoxelShape makeCollisionShapeA() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.201404375, 0, 0, 0.798595, 0.25, 1));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0061875000000000124, 0.25, 0.5406249999999999, 0.3359375, 0.5, 0.6656249999999999));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.002437500000000037, 0.25, 0.4156249999999999, 0.3921875, 0.5, 0.5406249999999999));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0004375000000000351, 0.25, 0.2906249999999999, 0.4421875, 0.5, 0.4156249999999999));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.004687500000000011, 0.25, 0.1656249999999999, 0.4921875, 0.5, 0.2906249999999999));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.005312499999999998, 0.25, 0.6656249999999999, 0.2890625, 0.5, 0.7906249999999999));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0078125, 0.25, 0.7906249999999999, 0.2265625, 0.5, 0.9156249999999999));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.25, 0.04062499999999991, 0.546875, 0.5, 0.1656249999999999));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.25, 0.002375000000000016, 0.59375, 0.5, 0.04062499999999991));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.5, 0.0036249999999999893, 0.375, 0.75, 0.04062499999999991));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.5, 0.1656249999999999, 0.12262499999999998, 0.75, 0.2906249999999999));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.5, 0.04062499999999991, 0.25, 0.75, 0.1656249999999999));

        return shape;
    }

    private static VoxelShape makeCollisionShapeB() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.201404375, 0, 0.4375, 0.798595, 0.25, 1));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.25, 0, 0.25, 0.75, 1, 0.75));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.10625, 0.25, 0.665625, 0.7, 0.5, 0.790625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.05625, 0.25, 0.790625, 0.65, 0.5, 0.915625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.25, 0.915625, 0.59375, 0.5, 0.999875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.15625, 0.25, 0.540625, 0.5625, 0.5, 0.665625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.1640625, 0.5, 0.415625, 0.4921875, 0.75, 0.540625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.5, 0.540625, 0.62, 0.75, 0.665625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.5, 0.665625, 0.620125, 0.75, 0.790625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.5, 0.790625, 0.493125, 0.75, 0.915625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.5, 0.915625, 0.369125, 0.75, 1.0006249999999999));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.3359375, 0.75, 0.25, 0.4609375, 1, 0.84375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.2109375, 0.75, 0.29375, 0.3359375, 1, 0.8875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0859375, 0.75, 0.346875, 0.2109375, 1, 0.940625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0, 0.75, 0.4, 0.086, 1, 1));

        return shape;
    }

    private static VoxelShape makeCollisionShapeC() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.6751249859333038, 0.25, 0.6656249999999999, 0.9999999859333039, 0.5, 0.7906249999999999));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.7421249897480011, 0.25, 0.5406249999999999, 0.9999999897480011, 0.5, 0.6656249999999999));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.796875, 0.25, 0.4156249999999999, 1, 0.5, 0.5406249999999999));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.5546875, 0.5, 0.5406249999999999, 0.7421875, 0.75, 0.6656249999999999));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.4234375, 0.5, 0.4156249999999999, 0.8765625, 0.75, 0.5406249999999999));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.296875, 0.5, 0.2906249999999999, 1, 0.75, 0.4156249999999999));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.18037499189376827, 0.5, 0.1656249999999999, 0.9999999918937683, 0.75, 0.2906249999999999));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.546875, 0.5, 0.04062499999999991, 1, 0.75, 0.1656249999999999));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.3359375, 0.75, 0, 0.4609375, 1, 0.2517499999999998));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.7109375, 0.75, 0, 0.8359375, 1, 0.09175));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.5859375, 0.75, 0, 0.7109375, 1, 0.141625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.4609375, 0.75, 0, 0.5859375, 1, 0.19425000000000003));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.8359375, 0.75, 0, 0.9609375, 1, 0.04174999999999984));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.2109375, 0.75, 0, 0.3359375, 1, 0.30325));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0859375, 0.75, 0, 0.2109375, 1, 0.35499999999999987));

        return shape;
    }

    private static VoxelShape makeCollisionShapeD() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.9609375, 0.75, 0.4022499680519106, 1, 1, 0.9999999680519106));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.8359375, 0.75, 0.4492499589920046, 0.9609375, 1, 0.9999999589920044));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.7109375, 0.75, 0.49999997615814223, 0.8359375, 1, 0.9999999761581422));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.5859375, 0.75, 0.5482499957084657, 0.7109375, 1, 0.9999999957084656));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.4609375, 0.75, 0.6013749599456787, 0.5859375, 1, 0.9999999599456788));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.3359375, 0.75, 0.6582499504089357, 0.4609375, 1, 0.9999999504089356));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.2109375, 0.75, 0.7097500324249267, 0.3359375, 1, 1.0000000324249267));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0859375, 0.75, 0.7625, 0.2109375, 1, 1));

        return shape;
    }

private static VoxelShape rotateShape(Direction from, Direction to, VoxelShape shape) {
        if (from == to) return shape;

        VoxelShape[] buffer = new VoxelShape[]{shape, VoxelShapes.empty()};
        int times = (to.getHorizontal() - from.getHorizontal() + 4) % 4;

        for (int i = 0; i < times; i++) {
            VoxelShape current = buffer[0];
            buffer[1] = VoxelShapes.empty();

            current.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
                // rotate 90° clockwise around block centre (x/z pivot at 0.5)
                double newMinX = 1 - maxZ;
                double newMinZ = minX;
                double newMaxX = 1 - minZ;
                double newMaxZ = maxX;

                buffer[1] = VoxelShapes.union(
                        buffer[1],
                        VoxelShapes.cuboid(newMinX, minY, newMinZ, newMaxX, maxY, newMaxZ)
                );
            });

            buffer[0] = buffer[1];
        }

        return buffer[0];
    }
}
