package com.oliver.erydon.block;

import com.oliver.erydon.state.ClusterManualLockState;
import com.oliver.erydon.util.ClusterRecalcSafety;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Hand;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;
import net.minecraft.block.ShapeContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * WindowArchBlock
 *
 * New simplified arch window system driven by cluster/rect layout logic (similar to WindowFrenchGeorgianBlock),
 * but with a smaller piece set. Rendering is expected to be built from parent models + multipart entries,
 * including optional 180° Y-rotated parts and 'void' (empty) parts for the open state.
 */
public class WindowArchBlock extends Block implements ClusterRebuildableBlock {

    private static final String MANUAL_LOCK_SCOPE = ClusterManualLockState.WINDOW_ARCH_SCOPE;

    // --- Properties (kept close to the French Georgian system) ---
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final BooleanProperty OPEN = Properties.OPEN;

    public static final EnumProperty<Piece> PIECE = EnumProperty.of("piece", Piece.class);
    public static final BooleanProperty SILL = BooleanProperty.of("sill");

    // --- Sync guard (prevents feedback loops while we setBlockState across clusters) ---
    private static final ThreadLocal<Integer> SYNC_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static boolean beginSync() {
        int d = SYNC_DEPTH.get();
        SYNC_DEPTH.set(d + 1);
        return d == 0;
    }
    private static void endSync(boolean started) {
        int d = SYNC_DEPTH.get();
        SYNC_DEPTH.set(Math.max(0, d - 1));
    }
    private static boolean isSyncing() {
        return SYNC_DEPTH.get() > 0;
    }

    // --- Shapes (strike/collision) ---
    enum ShapeKey {
        CLOSED_UPPER_SINGLE,
        CLOSED_UPPER_LEFT,
        CLOSED_UPPER_MID,
        CLOSED_UPPER_RIGHT,

        CLOSED_LOWER_SINGLE,
        CLOSED_LOWER_LEFT,
        CLOSED_LOWER_GLASS,
        CLOSED_LOWER_RIGHT,

        OPEN_LOWER_SINGLE,
        OPEN_LOWER_LEFT,
        OPEN_LOWER_RIGHT,

        SILL
    }

    private static final double[][] WALL_BOXES = new double[][] {
            {14.0D, 0.0D, 1.0D, 15.0D, 16.0D, 2.07143D},
            {14.0D, 0.0D, 7.42857D, 15.0D, 16.0D, 8.5D},
            {14.0D, 0.0D, 9.57143D, 15.0D, 16.0D, 10.64286D},
            {14.0D, 0.0D, 3.14286D, 15.0D, 16.0D, 4.21429D},
            {14.0D, 0.0D, 11.71429D, 15.0D, 16.0D, 12.78571D},
            {14.0D, 0.0D, 5.28571D, 15.0D, 16.0D, 6.35714D},
            {14.0D, 0.0D, 14.0D, 15.0D, 16.0D, 15.07143D},
            {15.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D}
    };

    private static final double[][] SILL_BOXES = new double[][] {
            {0.0D, 0.0D, 0.0D, 16.0D, 1.478D, 16.05D}
    };

    private static final double[][] GLASS_LOWER_BOXES = new double[][] {
            {0.0D, 0.0D, 7.67857D, 16.0D, 16.0D, 8.25D},
            {9.125D, 0.0D, 7.574D, 9.375D, 16.0D, 8.378D},
            {14.469D, 0.0D, 7.574D, 14.719D, 16.0D, 8.378D},
            {11.813D, 0.0D, 7.574D, 12.063D, 16.0D, 8.378D},
            {0.0D, 9.077D, 7.56D, 16.0D, 9.327D, 8.364D},
            {0.0D, 11.813D, 7.56D, 16.0D, 12.063D, 8.364D},
            {0.0D, 14.437D, 7.56D, 16.0D, 14.687D, 8.364D},
            {0.0D, 6.453D, 7.56D, 16.0D, 6.703D, 8.364D},
            {0.0D, 3.813D, 7.56D, 16.0D, 4.063D, 8.364D},
            {0.0D, 1.26D, 7.56D, 16.0D, 1.51D, 8.364D},
            {3.781D, 0.0D, 7.574D, 4.031D, 16.0D, 8.378D},
            {6.453D, 0.0D, 7.574D, 6.703D, 16.0D, 8.378D},
            {1.26D, 0.0D, 7.574D, 1.51D, 16.0D, 8.378D}
    };

    private static final double[][] MID_UPPER_BOXES = new double[][] {
            {0.0D, 1.612D, 6.47286D, 16.0D, 2.112D, 9.44086D},
            {1.26D, 0.01D, 7.574D, 1.51D, 15.99D, 8.378D},
            {6.453D, 0.01D, 7.574D, 6.703D, 15.99D, 8.378D},
            {3.781D, 0.01D, 7.574D, 4.031D, 15.99D, 8.378D},
            {0.01D, 1.26D, 7.56D, 15.99D, 1.51D, 8.364D},
            {0.01D, 3.813D, 7.56D, 15.99D, 4.063D, 8.364D},
            {0.01D, 6.453D, 7.56D, 15.99D, 6.703D, 8.364D},
            {0.01D, 9.077D, 7.56D, 15.99D, 9.327D, 8.364D},
            {11.813D, 0.01D, 7.574D, 12.063D, 15.99D, 8.378D},
            {14.469D, 0.01D, 7.574D, 14.719D, 15.99D, 8.378D},
            {9.125D, 0.01D, 7.574D, 9.375D, 15.99D, 8.378D},
            {0.0D, 0.0D, 7.67857D, 16.0D, 16.0D, 8.25D},
            {0.0D, 15.0D, 0.0D, 16.0D, 16.0D, 16.0D},
            {0.0D, 11.256D, 0.372D, 16.0D, 12.272D, 15.372D},
            {0.0D, 12.272D, 1.0D, 16.0D, 15.0D, 15.0D}
    };

    private static final double[][] MULTI_UPPER_BOXES = new double[][] {
            {0.0D, 1.612D, 6.47286D, 16.0D, 2.112D, 9.44086D},
            {1.26D, 0.01D, 7.574D, 1.51D, 15.99D, 8.378D},
            {6.453D, 0.01D, 7.574D, 6.703D, 15.99D, 8.378D},
            {3.781D, 0.01D, 7.574D, 4.031D, 15.99D, 8.378D},
            {0.01D, 1.26D, 7.56D, 15.99D, 1.51D, 8.364D},
            {0.01D, 3.813D, 7.56D, 15.99D, 4.063D, 8.364D},
            {0.01D, 6.453D, 7.56D, 15.99D, 6.703D, 8.364D},
            {0.01D, 9.077D, 7.56D, 15.99D, 9.327D, 8.364D},
            {11.813D, 0.01D, 7.574D, 12.063D, 15.99D, 8.378D},
            {14.469D, 0.01D, 7.574D, 14.719D, 15.99D, 8.378D},
            {9.125D, 0.01D, 7.574D, 9.375D, 15.99D, 8.378D},
            {0.0D, 0.0D, 7.67857D, 16.0D, 16.0D, 8.25D},
            {13.0D, 1.604D, 0.096D, 15.968D, 2.104D, 15.92D},
            {15.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D},
            {0.0D, 15.0D, 0.0D, 14.992D, 16.0D, 16.0D},
            {0.0D, 11.256D, 0.372D, 5.442D, 12.272D, 15.372D},
            {0.0D, 11.272D, 1.0D, 15.004D, 15.0D, 15.0D},
            {11.5D, 7.58D, 1.0D, 12.88D, 11.272D, 15.0D},
            {7.892D, 10.332D, 1.0D, 10.12D, 11.272D, 15.0D},
            {10.12D, 8.972D, 1.0D, 11.5D, 11.272D, 15.0D},
            {12.88D, 5.66D, 1.0D, 13.892D, 11.272D, 15.0D},
            {13.896D, 3.096D, 1.0D, 15.004D, 11.272D, 15.0D}
    };

    private static final double[][] SINGLE_UPPER_BOXES = new double[][] {
            {0.01D, 1.612D, 6.47286D, 15.99D, 2.112D, 9.44086D},
            {1.26D, 0.01D, 7.574D, 1.51D, 15.99D, 8.378D},
            {6.453D, 0.01D, 7.574D, 6.703D, 15.99D, 8.378D},
            {3.781D, 0.01D, 7.574D, 4.031D, 15.99D, 8.378D},
            {0.01D, 1.26D, 7.56D, 15.99D, 1.51D, 8.364D},
            {0.01D, 3.813D, 7.56D, 15.99D, 4.063D, 8.364D},
            {0.01D, 6.453D, 7.56D, 15.99D, 6.703D, 8.364D},
            {0.01D, 9.077D, 7.56D, 15.99D, 9.327D, 8.364D},
            {11.813D, 0.01D, 7.574D, 12.063D, 15.99D, 8.378D},
            {14.469D, 0.01D, 7.574D, 14.719D, 15.99D, 8.378D},
            {9.125D, 0.01D, 7.574D, 9.375D, 15.99D, 8.378D},
            {0.0D, 0.01D, 7.67857D, 16.0D, 15.99D, 8.25D},
            {15.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D},
            {0.0D, 0.0D, 0.0D, 1.0D, 16.0D, 16.0D},
            {1.0D, 15.0D, 0.0D, 15.0D, 16.0D, 16.0D},
            {6.844D, 11.256D, 0.372D, 9.21D, 12.272D, 15.372D},
            {4.5D, 11.272D, 1.0D, 11.504D, 15.272D, 15.0D},
            {11.5D, 9.836D, 1.0D, 12.88D, 15.272D, 15.0D},
            {12.88D, 8.428D, 1.0D, 14.004D, 15.272D, 15.0D},
            {3.12D, 9.836D, 1.0D, 4.5D, 15.272D, 15.0D},
            {1.996D, 8.428D, 1.0D, 3.12D, 15.272D, 15.0D},
            {14.004D, 5.928D, 1.0D, 15.128D, 15.272D, 15.0D},
            {14.0D, 2.0D, 1.0D, 15.0D, 6.0D, 2.07143D},
            {14.0D, 2.0D, 3.14286D, 15.0D, 6.0D, 4.21429D},
            {14.0D, 2.0D, 5.28571D, 15.0D, 6.0D, 6.35714D},
            {14.0D, 2.0D, 7.42857D, 15.0D, 6.0D, 8.5D},
            {14.0D, 2.0D, 9.57143D, 15.0D, 6.0D, 10.64286D},
            {14.0D, 2.0D, 11.71429D, 15.0D, 6.0D, 12.78571D},
            {14.0D, 2.0D, 14.0D, 15.0D, 6.0D, 15.07143D},
            {13.0D, 1.604D, 0.096D, 15.968D, 2.104D, 15.92D},
            {0.872D, 5.928D, 1.0D, 1.996D, 15.272D, 15.0D},
            {1.0D, 2.0D, 1.0D, 2.0D, 6.0D, 2.07143D},
            {1.0D, 2.0D, 3.14286D, 2.0D, 6.0D, 4.21429D},
            {1.0D, 2.0D, 5.28571D, 2.0D, 6.0D, 6.35714D},
            {1.0D, 2.0D, 7.42857D, 2.0D, 6.0D, 8.5D},
            {1.0D, 2.0D, 9.57143D, 2.0D, 6.0D, 10.64286D},
            {1.0D, 2.0D, 11.71429D, 2.0D, 6.0D, 12.78571D},
            {1.0D, 2.0D, 14.0D, 2.0D, 6.0D, 15.07143D},
            {0.032D, 1.604D, 0.096D, 3.0D, 2.104D, 15.92D}
    };

    private static final EnumMap<ShapeKey, VoxelShape[]> SHAPES = new EnumMap<>(ShapeKey.class);

    static {
        // Components (unrotated for "north" baseline). We pre-bake a few combined shapes for convenience.
        VoxelShape wall = makeWallShape();
        VoxelShape wall180 = rotateY180(wall);

        VoxelShape glassLower = makeGlassLowerShape();

        // Uppers
        register(ShapeKey.CLOSED_UPPER_SINGLE, makeSingleUpperShape());
        register(ShapeKey.CLOSED_UPPER_LEFT, makeMultiUpperShape());
        register(ShapeKey.CLOSED_UPPER_MID, makeMidUpperShape());
        register(ShapeKey.CLOSED_UPPER_RIGHT, rotateY180(makeMultiUpperShape()));

        // Lowers (closed)
        register(ShapeKey.CLOSED_LOWER_GLASS, glassLower);
        register(ShapeKey.CLOSED_LOWER_LEFT, VoxelShapes.union(wall, glassLower).simplify());
        register(ShapeKey.CLOSED_LOWER_RIGHT, VoxelShapes.union(glassLower, wall180).simplify());
        register(ShapeKey.CLOSED_LOWER_SINGLE, VoxelShapes.union(wall, glassLower, wall180).simplify());

        // Lowers (open) - opening is handled by returning null for Piece.LOWER_GLASS in shapeKeyForState()
        register(ShapeKey.OPEN_LOWER_LEFT, wall);
        register(ShapeKey.OPEN_LOWER_RIGHT, wall180);
        register(ShapeKey.OPEN_LOWER_SINGLE, VoxelShapes.union(wall, wall180).simplify());

        // Sill
        register(ShapeKey.SILL, makeSillShape());
    }

    private static void register(ShapeKey key, VoxelShape base) {
        VoxelShape[] arr = new VoxelShape[4];
        arr[0] = base.simplify();
        arr[1] = rotateYClockwise(arr[0]); // EAST
        arr[2] = rotateYClockwise(arr[1]); // SOUTH
        arr[3] = rotateYClockwise(arr[2]); // WEST
        SHAPES.put(key, arr);
    }

    private static VoxelShape makeShape(double[][] boxes) {
        VoxelShape shape = VoxelShapes.empty();
        for (double[] box : boxes) {
            shape = VoxelShapes.combine(
                    shape,
                    Block.createCuboidShape(box[0], box[1], box[2], box[3], box[4], box[5]),
                    BooleanBiFunction.OR
            );
        }
        return shape.simplify();
    }

    static VoxelShape rotateYClockwise(VoxelShape shape) {
        final VoxelShape[] acc = new VoxelShape[]{ VoxelShapes.empty() };
        shape.forEachBox((minX, minY, minZ, maxX, maxY, maxZ) -> {
            // 90° clockwise around Y: (x,z) -> (1 - z, x)
            double nMinX = 1.0 - maxZ;
            double nMaxX = 1.0 - minZ;
            double nMinZ = minX;
            double nMaxZ = maxX;
            acc[0] = VoxelShapes.combine(acc[0], VoxelShapes.cuboid(nMinX, minY, nMinZ, nMaxX, maxY, nMaxZ), BooleanBiFunction.OR);
        });
        return acc[0].simplify();
    }

    static VoxelShape rotateY180(VoxelShape shape) {
        return rotateYClockwise(rotateYClockwise(shape));
    }

    private static int facingIndex(Direction facing) {
        return switch (facing) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }

    public WindowArchBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.getStateManager().getDefaultState()
                .with(FACING, Direction.NORTH)
                .with(OPEN, false)
                .with(PIECE, Piece.LOWER_SINGLE)
                .with(SILL, false)
        );
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, OPEN, PIECE, SILL);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        World world = ctx.getWorld();
        BlockPos pos = ctx.getBlockPos();

        // Default: player-based
        Direction facing = ctx.getHorizontalPlayerFacing().getOpposite();
        boolean open = world.isReceivingRedstonePower(pos);

        // If we are placing against an existing arch window, inherit its cluster identity.
        BlockPos clickedPos = pos.offset(ctx.getSide().getOpposite());
        BlockState clicked = world.getBlockState(clickedPos);

        BlockState inherit = null;
        if (clicked.isOf(this)) {
            inherit = clicked;
        } else {
            // Fallback: any adjacent arch window
            for (Direction d : Direction.values()) {
                BlockState n = world.getBlockState(pos.offset(d));
                if (n.isOf(this)) {
                    inherit = n;
                    break;
                }
            }
        }

        if (inherit != null) {
            facing = inherit.get(FACING);
            open = inherit.get(OPEN);
        }

        return this.getDefaultState()
                .with(FACING, facing)
                .with(OPEN, open);

    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);
        if (world.isClient()) return;

        clearManualLock(world, pos);
        reflowConnectedAutoComponent(world, pos);
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        super.onStateReplaced(state, world, pos, newState, moved);
        if (world.isClient()) return;

        if (!state.isOf(newState.getBlock())) {
            clearManualLock(world, pos);
            for (BlockPos n : planeNeighbours(pos, state.get(FACING))) {
                BlockState ns = world.getBlockState(n);
                if (ns.isOf(this)) {
                    reflowConnectedAutoComponent(world, n);
                }
            }
        }
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        if (world.isClient()) return;

        // Placement is handled in onPlaced(); this hook is mainly for single-block state edits (e.g. debug stick).
        if (!oldState.isOf(this)) return;
        if (isSyncing()) return;

        boolean openChanged = state.get(OPEN) != oldState.get(OPEN);
        if (!openChanged) {
            return;
        }

        boolean started = beginSync();
        try {
            if (openChanged) {
                applyOpenToCluster(world, pos, state.get(OPEN));
            }
        } finally {
            endSync(started);
        }
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos,
                             PlayerEntity player, Hand hand, BlockHitResult hit) {

        ItemStack held = player.getStackInHand(hand);

        if (held.isOf(Items.DEBUG_STICK)) {
            if (player.isSneaking()) {
                if (world.isClient) {
                    return ActionResult.SUCCESS;
                }

                boolean locked = toggleManualLock(world, pos);
                handleManualLockChanged(world, pos, state.get(FACING), locked);
                player.sendMessage(Text.literal("Arch window mode: " + (locked ? "manual" : "auto")), true);
                return ActionResult.CONSUME;
            }

            // Let vanilla handle property selection + cycling, without consuming the click.
            return ActionResult.PASS;
        }

        boolean placementClick = player.isSneaking() || hit.getSide().getAxis() == Direction.Axis.Y;

        if (placementClick && held.getItem() instanceof BlockItem bi && bi.getBlock() == this) {
            if (!world.isClient) {
                if (tryPlaceAdjacentOnPlane(world, player, hand, pos, state, hit)) {
                    return ActionResult.CONSUME;
                }
            }
            return ActionResult.SUCCESS;
        }

        if (placementClick) {
            return ActionResult.PASS;
        }

        // Normal side-click interaction: toggle OPEN across the connected component.
        if (world.isClient) return ActionResult.SUCCESS;

        boolean newOpen = !state.get(OPEN);
        world.setBlockState(pos, state.with(OPEN, newOpen), Block.NOTIFY_ALL);

        world.playSound(null, pos, newOpen ? SoundEvents.BLOCK_WOODEN_DOOR_OPEN : SoundEvents.BLOCK_WOODEN_DOOR_CLOSE,
                SoundCategory.BLOCKS, 1.0F, world.random.nextFloat() * 0.1F + 0.9F);

        world.emitGameEvent(player, newOpen ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
        return ActionResult.CONSUME;
    }

    @Override
    public void neighborUpdate(BlockState state, World world, BlockPos pos,
                               Block block, BlockPos fromPos, boolean notify) {
        if (world.isClient()) return;

        // Match existing one-way behavior: power can force open, but dropping power does not force-close.
        if (world.isReceivingRedstonePower(pos) && !state.get(OPEN)) {
            applyOpenToCluster(world, pos, true);
        }
    }

    // --- Collision/outline/raycast ---
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getWindowShape(state);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return getWindowShape(state);
    }

    @Override
    public VoxelShape getRaycastShape(BlockState state, BlockView world, BlockPos pos) {
        return VoxelShapes.fullCube(); // keep targeting easy (incl. openings)
    }

    private VoxelShape getWindowShape(BlockState state) {
        int idx = facingIndex(state.get(FACING));

        VoxelShape base = VoxelShapes.empty();
        ShapeKey key = shapeKeyForState(state);

        if (key != null) {
            base = SHAPES.get(key)[idx];
        }

        if (state.get(SILL)) {
            // Avoid simplify-heavy unions while Minecraft rebuilds the global shape cache.
            base = VoxelShapes.combine(base, SHAPES.get(ShapeKey.SILL)[idx], BooleanBiFunction.OR);
        }

        return base;
    }

    private ShapeKey shapeKeyForState(BlockState state) {
        boolean open = state.get(OPEN);
        Piece piece = state.get(PIECE);

        // Uppers do not visually change with OPEN in the table.
        return switch (piece) {
            case UPPER_SINGLE -> ShapeKey.CLOSED_UPPER_SINGLE;
            case UPPER_LEFT -> ShapeKey.CLOSED_UPPER_LEFT;
            case UPPER_MID -> ShapeKey.CLOSED_UPPER_MID;
            case UPPER_RIGHT -> ShapeKey.CLOSED_UPPER_RIGHT;

            case LOWER_SINGLE -> open ? ShapeKey.OPEN_LOWER_SINGLE : ShapeKey.CLOSED_LOWER_SINGLE;
            case LOWER_LEFT -> open ? ShapeKey.OPEN_LOWER_LEFT : ShapeKey.CLOSED_LOWER_LEFT;
            case LOWER_RIGHT -> open ? ShapeKey.OPEN_LOWER_RIGHT : ShapeKey.CLOSED_LOWER_RIGHT;
            case LOWER_GLASS -> open ? null : ShapeKey.CLOSED_LOWER_GLASS; // opening ('void')
        };
    }

    // --- Cluster sync operations (AUTO must self-heal) ---
    private void applyOpenToCluster(World world, BlockPos anchor, boolean open) {
        boolean started = beginSync();
        try {
            BlockState anchorState = world.getBlockState(anchor);
            if (!anchorState.isOf(this)) return;

            Direction facing = anchorState.get(FACING);

            // Open/closed is cluster-level (door-like), even if some blocks are MANUAL.
            Set<BlockPos> component = collectPlaneComponentAnyMode(world, anchor, facing);

            for (BlockPos p : component) {
                BlockState s = world.getBlockState(p);
                if (!s.isOf(this)) continue;
                if (s.get(FACING) != facing) continue;

                BlockState ns = s.with(OPEN, open);
                if (!Objects.equals(ns, s)) {
                    world.setBlockState(p, ns, Block.NOTIFY_ALL);
                }
            }
        } finally {
            endSync(started);
        }
    }

    // --- Auto layout: collect component (AUTO only), partition to rects, apply layout per rect ---
    private void reflowConnectedAutoComponent(World world, BlockPos seed) {
        reflowConnectedAutoComponent(world, seed, null);
    }

    private void reflowConnectedAutoComponent(World world, BlockPos seed, Set<BlockPos> component) {
        boolean started = beginSync();
        try {
            ClusterPartition partition = partitionComponent(world, seed, component);
            for (Rect rect : partition.rects) {
                applyRectLayout(world, rect);
            }
        } finally {
            endSync(started);
        }
    }

    @Override
    public ClusterRecalcResult recalcCluster(World world, BlockPos seed) {
        BlockState seedState = world.getBlockState(seed);
        if (!seedState.isOf(this)) {
            return ClusterRecalcResult.none();
        }

        Direction facing = seedState.get(FACING);
        if (isManualLocked(world, seed)) {
            Set<BlockPos> lockedComponent = collectPlaneComponentWithLock(world, seed, facing, true);
            if (lockedComponent.isEmpty()) {
                return ClusterRecalcResult.none();
            }
            ClusterRecalcResult unsafe = ClusterRecalcSafety.unsafeResult(lockedComponent);
            if (unsafe != null) {
                return unsafe;
            }
            return new ClusterRecalcResult(lockedComponent, false);
        }

        Set<BlockPos> component = collectPlaneComponentWithLock(world, seed, facing, false);
        if (component.isEmpty()) {
            return ClusterRecalcResult.none();
        }
        ClusterRecalcResult unsafe = ClusterRecalcSafety.unsafeResult(component);
        if (unsafe != null) {
            return unsafe;
        }

        reflowConnectedAutoComponent(world, seed, component);
        unsafe = ClusterRecalcSafety.unsafeResult(component);
        if (unsafe != null) {
            return unsafe;
        }
        return new ClusterRecalcResult(component, true);
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, BlockMirror mirror) {
        Direction facing = state.get(FACING);
        Piece piece = state.get(PIECE);

        if (mirrorSwapsLeftRight(facing, mirror)) {
            piece = swapLeftRight(piece);
        }

        return rotate(state, mirror.getRotation(facing)).with(PIECE, piece);
    }

    private void applyRectLayout(World world, Rect rect) {
        BlockState seedState = world.getBlockState(rect.bottomLeft);
        if (!seedState.isOf(this)) return;

        boolean open = seedState.get(OPEN);

        int w = rect.width;
        int h = rect.height;

        for (int dy = 0; dy < h; dy++) {
            int y = rect.bottomLeft.getY() + dy;
            boolean isBottom = dy == 0;
            boolean isTop = dy == (h - 1);

            for (int dx = 0; dx < w; dx++) {
                BlockPos p = rect.bottomLeft.offset(rect.rightDir, dx).withY(y);

                BlockState s = world.getBlockState(p);
                if (!s.isOf(this)) continue;

                Piece piece = computePiece(w, isTop, dx);

                BlockState ns = s
                        .with(OPEN, open)
                        .with(PIECE, piece)
                        .with(SILL, isBottom)
                        .with(FACING, rect.facing);

                if (!Objects.equals(ns, s)) {
                    world.setBlockState(p, ns, ClusterRecalcSafety.updateFlags(Block.NOTIFY_ALL));
                }
            }
        }
    }

    /**
     * Table-driven piece computation:
     * - top row: single_upper (w=1), else multi_upper at edges + mid_upper in middle columns
     * - lower rows: wall+glass combinations; open state renders void in lower mid columns via blockstates
     */
    private Piece computePiece(int width, boolean isTop, int col) {
        if (isTop) {
            if (width == 1) return Piece.UPPER_SINGLE;
            if (col == 0) return Piece.UPPER_LEFT;
            if (col == width - 1) return Piece.UPPER_RIGHT;
            return Piece.UPPER_MID;
        }

        // Not top row
        if (width == 1) return Piece.LOWER_SINGLE;
        if (col == 0) return Piece.LOWER_LEFT;
        if (col == width - 1) return Piece.LOWER_RIGHT;
        return Piece.LOWER_GLASS;
    }

    // --- Component collection ---
    private Set<BlockPos> collectPlaneComponent(World world, BlockPos start, Direction facing) {
        return collectPlaneComponentWithLock(world, start, facing, false);
    }

    private Set<BlockPos> collectPlaneComponentAnyMode(World world, BlockPos start, Direction facing) {
        Set<BlockPos> out = new HashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();

        BlockState startState = ClusterRecalcSafety.getBlockState(world, start);
        if (!startState.isOf(this)) return out;
        if (startState.get(FACING) != facing) return out;

        if (!ClusterRecalcSafety.claim(start)) return out;
        out.add(start);
        q.add(start);

        while (!q.isEmpty()) {
            BlockPos p = q.removeFirst();
            for (BlockPos n : planeNeighbours(p, facing)) {
                if (out.contains(n)) continue;
                BlockState s = ClusterRecalcSafety.getBlockState(world, n);
                if (!s.isOf(this)) continue;
                if (s.get(FACING) != facing) continue;
                if (!ClusterRecalcSafety.claim(n)) return out;
                out.add(n);
                q.add(n);
            }
        }

        return out;
    }

    private Set<BlockPos> collectPlaneComponentWithLock(World world, BlockPos start, Direction facing, boolean locked) {
        Set<BlockPos> out = new HashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();

        BlockState startState = ClusterRecalcSafety.getBlockState(world, start);
        if (!startState.isOf(this)) return out;
        if (startState.get(FACING) != facing) return out;
        if (isManualLocked(world, start) != locked) return out;

        if (!ClusterRecalcSafety.claim(start)) return out;
        out.add(start);
        q.add(start);

        while (!q.isEmpty()) {
            BlockPos p = q.removeFirst();
            for (BlockPos n : planeNeighbours(p, facing)) {
                if (out.contains(n)) continue;
                BlockState s = ClusterRecalcSafety.getBlockState(world, n);
                if (!s.isOf(this)) continue;
                if (s.get(FACING) != facing) continue;
                if (isManualLocked(world, n) != locked) continue;
                if (!ClusterRecalcSafety.claim(n)) return out;
                out.add(n);
                q.add(n);
            }
        }

        return out;
    }

    private static List<BlockPos> planeNeighbours(BlockPos pos, Direction facing) {
        Direction right = facing.rotateYCounterclockwise();
        Direction left = right.getOpposite();

        return List.of(
                pos.up(),
                pos.down(),
                pos.offset(left),
                pos.offset(right)
        );
    }

    private static boolean mirrorSwapsLeftRight(Direction facing, BlockMirror mirror) {
        return (mirror == BlockMirror.LEFT_RIGHT && facing.getAxis() == Direction.Axis.X)
                || (mirror == BlockMirror.FRONT_BACK && facing.getAxis() == Direction.Axis.Z);
    }

    private static Piece swapLeftRight(Piece piece) {
        return switch (piece) {
            case UPPER_LEFT -> Piece.UPPER_RIGHT;
            case UPPER_RIGHT -> Piece.UPPER_LEFT;
            case LOWER_LEFT -> Piece.LOWER_RIGHT;
            case LOWER_RIGHT -> Piece.LOWER_LEFT;
            default -> piece;
        };
    }

    // --- Rectangle partitioning (same approach as WindowFrenchGeorgianBlock) ---
    private static final class Rect {
        final BlockPos bottomLeft;
        final int width;
        final int height;
        final Direction facing;
        final Direction rightDir;

        Rect(BlockPos bottomLeft, int width, int height, Direction facing) {
            this.bottomLeft = bottomLeft;
            this.width = width;
            this.height = height;
            this.facing = facing;
            this.rightDir = facing.rotateYCounterclockwise();
        }
    }

    private static final class ClusterPartition {
        final List<Rect> rects;
        final Direction facing;
        ClusterPartition(List<Rect> rects, Direction facing) {
            this.rects = rects;
            this.facing = facing;
        }
    }

    private ClusterPartition partitionComponent(World world, BlockPos seed) {
        return partitionComponent(world, seed, null);
    }

    private ClusterPartition partitionComponent(World world, BlockPos seed, Set<BlockPos> discoveredComponent) {
        BlockState seedState = world.getBlockState(seed);
        if (!seedState.isOf(this)) return new ClusterPartition(List.of(), Direction.NORTH);

        Direction facing   = seedState.get(FACING);
        Direction rightDir = facing.rotateYCounterclockwise();

        Set<BlockPos> component = discoveredComponent == null
                ? collectPlaneComponent(world, seed, facing)
                : discoveredComponent;
        if (component.isEmpty()) {
            return new ClusterPartition(List.of(), facing);
        }

        // Project to a 2D grid using dot-product coords (same approach as WindowFrenchGeorgianBlock)
        int dx = rightDir.getOffsetX();
        int dz = rightDir.getOffsetZ();

        int fx = facing.getOffsetX();
        int fz = facing.getOffsetZ();

        int minY    = Integer.MAX_VALUE;
        int maxY    = Integer.MIN_VALUE;
        int minCol  = Integer.MAX_VALUE;
        int maxCol  = Integer.MIN_VALUE;
        Integer dep = null;

        for (BlockPos p : component) {
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());

            int col = (p.getX() * dx) + (p.getZ() * dz);
            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);

            int d = (p.getX() * fx) + (p.getZ() * fz);
            if (dep == null) dep = d;
            else if (!dep.equals(d)) {
                // Non-planar component: fail safe (treat each block as its own cluster)
                List<Rect> singles = new ArrayList<>();
                for (BlockPos q : component) singles.add(new Rect(q, 1, 1, facing));
                return new ClusterPartition(singles, facing);
            }
        }

        int width  = (maxCol - minCol) + 1;
        int height = (maxY   - minY)   + 1;

        ClusterRecalcSafety.requireLayoutArea((long) width * height);
        if (ClusterRecalcSafety.unsafeResult(component) != null) {
            return new ClusterPartition(List.of(), facing);
        }

        BlockPos[][] grid  = new BlockPos[height][width];
        boolean[][] filled = new boolean[height][width];
        boolean[][] used   = new boolean[height][width];

        for (BlockPos p : component) {
            int col = (p.getX() * dx) + (p.getZ() * dz);
            int x   = col - minCol;
            int y   = p.getY() - minY;   // y=0 is bottom row

            if (x < 0 || x >= width || y < 0 || y >= height) continue;

            filled[y][x] = true;
            grid[y][x]   = p;
        }

        List<Rect> rects = new ArrayList<>();

        // Greedy rectangle packing: guarantees vertical growth works
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!filled[y][x] || used[y][x]) continue;

                int w = 0;
                while (x + w < width && filled[y][x + w] && !used[y][x + w]) w++;

                int h = 1;
                outer:
                while (y + h < height) {
                    for (int xx = 0; xx < w; xx++) {
                        if (!filled[y + h][x + xx] || used[y + h][x + xx]) break outer;
                    }
                    h++;
                }

                for (int yy = 0; yy < h; yy++) {
                    for (int xx = 0; xx < w; xx++) {
                        used[y + yy][x + xx] = true;
                    }
                }

                BlockPos bottomLeft = grid[y][x];
                rects.add(new Rect(bottomLeft, w, h, facing));
            }
        }

        return new ClusterPartition(rects, facing);
    }
// --- Placement helper (extend cluster by placing an adjacent block on the plane) ---
    private boolean tryPlaceAdjacentOnPlane(World world, PlayerEntity player, Hand hand,
                                            BlockPos pos, BlockState state, BlockHitResult hit) {

        Direction facing = state.get(FACING);

        // Only allow extending on-plane sides (not into/out of the wall).
        Direction side = hit.getSide();
        Direction right = facing.rotateYCounterclockwise();
        Direction left = right.getOpposite();

        boolean onPlane = (side == Direction.UP || side == Direction.DOWN || side == left || side == right);
        if (!onPlane) return false;

        BlockPos target = pos.offset(side);
        if (!world.getBlockState(target).isAir()) return false;

        // Create a placement context that places at target but inherits our facing.
        ItemPlacementContext ctx = new ItemPlacementContext(player, hand, player.getStackInHand(hand),
                new BlockHitResult(hit.getPos(), side, target, false));

        BlockState placeState = this.getPlacementState(ctx);
        if (placeState == null) return false;

        // Ensure it matches the cluster facing.
        placeState = placeState.with(FACING, facing);

        if (!world.setBlockState(target, placeState, Block.NOTIFY_ALL)) return false;

        reflowConnectedAutoComponent(world, target);

        return true;
    }

    private void handleManualLockChanged(World world, BlockPos pos, Direction facing, boolean locked) {
        if (!locked) {
            reflowConnectedAutoComponent(world, pos);
            return;
        }

        for (BlockPos neighbourPos : planeNeighbours(pos, facing)) {
            BlockState neighbourState = world.getBlockState(neighbourPos);
            if (!neighbourState.isOf(this) || neighbourState.get(FACING) != facing) {
                continue;
            }
            if (isManualLocked(world, neighbourPos)) {
                continue;
            }
            reflowConnectedAutoComponent(world, neighbourPos);
        }
    }

    private static boolean isManualLocked(World world, BlockPos pos) {
        return ClusterManualLockState.isLocked(world, MANUAL_LOCK_SCOPE, pos);
    }

    private static boolean toggleManualLock(World world, BlockPos pos) {
        return ClusterManualLockState.toggleLocked(world, MANUAL_LOCK_SCOPE, pos);
    }

    private static void clearManualLock(World world, BlockPos pos) {
        if (ClusterManualLockState.isPreservedForSwap(pos)) {
            return;
        }
        ClusterManualLockState.clear(world, MANUAL_LOCK_SCOPE, pos);
    }

    // --- Enums ---
    public enum Piece implements StringIdentifiable {
        // Top row
        UPPER_SINGLE("upper_single"),
        UPPER_LEFT("upper_left"),
        UPPER_MID("upper_mid"),
        UPPER_RIGHT("upper_right"),

        // Lower rows
        LOWER_SINGLE("lower_single"),
        LOWER_LEFT("lower_left"),
        LOWER_GLASS("lower_glass"),
        LOWER_RIGHT("lower_right");

        private final String id;
        Piece(String id) { this.id = id; }
        @Override public String asString() { return id; }
    }

    // --- Voxel shape definitions (from provided model voxel tables) ---
    private static VoxelShape makeWallShape() {
        return makeShape(WALL_BOXES);
    }

    private static VoxelShape makeSillShape() {
        return makeShape(SILL_BOXES);
    }

    private static VoxelShape makeGlassLowerShape() {
        return makeShape(GLASS_LOWER_BOXES);
    }

    private static VoxelShape makeMidUpperShape() {
        return makeShape(MID_UPPER_BOXES);
    }

    private static VoxelShape makeMultiUpperShape() {
        return makeShape(MULTI_UPPER_BOXES);
    }

    private static VoxelShape makeSingleUpperShape() {
        return makeShape(SINGLE_UPPER_BOXES);
    }
}
