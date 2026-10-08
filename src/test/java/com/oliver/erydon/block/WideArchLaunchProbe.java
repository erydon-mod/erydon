package com.oliver.erydon.block;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.AutomaticItemPlacementContext;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.server.world.ServerWorld;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.oliver.erydon.block.ArchRomanesqueBlock.*;
import static com.oliver.erydon.block.DoubleColumnRepairLaunchProbe.require;

/** Real state/cluster coverage; renderer tests use synthetic surfaces without opening Minecraft. */
public final class WideArchLaunchProbe implements PreLaunchEntrypoint {
    private int cases;
    private final java.util.Set<List<Object>> comparedShapes = new java.util.HashSet<>();

    @Override public void onPreLaunch() {
        try {
            SharedConstants.createGameVersion(); Bootstrap.initialize();
            if (Boolean.getBoolean("erydon.arch.fullStartup")) {
                fullStartup();
                System.exit(0);
            }
            ArchRomanesqueBlock[] families = {
                    new ArchRomanesqueBlock(AbstractBlock.Settings.create()),
                    new ArchModernBlock(AbstractBlock.Settings.create()),
                    new ArchGothicBlock(AbstractBlock.Settings.create())};
            String[] styles = {"romanesque", "modern", "gothic"};
            Method layout = ArchRomanesqueBlock.class.getDeclaredMethod("computeArrangement",
                    int.class, int.class, int.class, int.class, StyleSet.class);
            layout.setAccessible(true);
            for (int family = 0; family < families.length; family++) {
                ArchRomanesqueBlock block = families[family];
                Registry.register(Registries.BLOCK, new Identifier("erydon", "probe_arch_" + styles[family]), block);
                require(block.getDefaultState().get(WIDTH) == 3, "New placement default is not 3");
                transitions(block);
                for (int width = 1; width <= 6; width++) for (int height = 1; height <= 7; height++) {
                    for (StyleSet style : family == 0 ? StyleSet.values() : new StyleSet[]{StyleSet.BASE}) {
                        for (int row = 0; row < height; row++) for (int x = 0; x < width; x++) {
                            Arrangement arr = (Arrangement) layout.invoke(block, width, height, x, row, style);
                            BlockState state = block.getDefaultState().with(WIDTH, width).with(ARRANGEMENT, arr);
                            if (width > 3 && row < Math.min(height, (2 * width + 2) / 3)) {
                                require(arr.isWide(), "Wide crown fell back to legacy layout");
                                require(arr.wideX(width) == x && arr.wideRow() == row, "Incorrect crown cell");
                                require(arr.mirrored().wideX(width) == width-1-x, "Wide mirror moved to the wrong cell");
                            }
                            for (Direction facing : Direction.Type.HORIZONTAL) {
                                BlockState oriented = state.with(FACING, facing);
                                for (BlockMirror mirror : BlockMirror.values()) {
                                    require(oriented.mirror(mirror).mirror(mirror) == oriented, "Mirror lost state");
                                    require(oriented.mirror(mirror).get(WIDTH) == width, "Mirror lost width");
                                }
                                require(oriented.rotate(BlockRotation.CLOCKWISE_90).get(WIDTH) == width, "Rotation lost width");
                                var collision = block.getCollisionShape(oriented, EmptyBlockView.INSTANCE, BlockPos.ORIGIN,
                                        net.minecraft.block.ShapeContext.absent());
                                var outline = block.getOutlineShape(oriented, EmptyBlockView.INSTANCE, BlockPos.ORIGIN,
                                        net.minecraft.block.ShapeContext.absent());
                                sharedShapeCache(oriented);
                                sharedShapeCache(oriented.with(WATERLOGGED, true));
                                if (arr.isWide()) {
                                    compareApprovedShape(oriented);
                                    compareApprovedShape(oriented.with(REFLECTED, true));
                                }
                                require(!outline.isEmpty(), "Empty cell cannot be targeted: " + styles[family] + " " + width + "x" + height + " " + arr);
                                if (arr.isWide() && !collision.isEmpty()) {
                                    require(collision.getMin(Direction.Axis.Y) >= -1e-6 && collision.getMax(Direction.Axis.Y) <= 1+1e-6,
                                            "Crown collision escaped its vertical cell");
                                }
                                cases++;
                            }
                        }
                    }
                }
                callbacks(block);
            }
            com.oliver.erydon.client.model.WideArchMeshLaunchChecks.run(families);
            System.out.println("ERYDON_WIDE_ARCHES_OK cases=" + cases);
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }

    private void compareApprovedShape(BlockState state) {
        if (!comparedShapes.add(List.of(state.getBlock().getClass(), state.get(ARRANGEMENT), state.get(WIDTH),
                state.get(FACING), state.get(REFLECTED)))) return;
        // The approved implementation simplified every union. Keep it as the independent
        // reference while production batches those same boxes before simplifying.
        var arrangement = state.get(ARRANGEMENT);
        int width = state.get(WIDTH);
        net.minecraft.util.shape.VoxelShape[] reference = {net.minecraft.util.shape.VoxelShapes.empty()};
        for (var component : WideArchLayout.components(arrangement)) {
            var source = state.with(ARRANGEMENT, component.arrangement()).with(WIDTH, 3).with(FACING, Direction.NORTH);
            source.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN).forEachBox((x0,y0,z0,x1,y1,z1) -> {
                double left = Math.max(0, WideArchLayout.x(x0, component, arrangement, width));
                double right = Math.min(1, WideArchLayout.x(x1, component, arrangement, width));
                double bottom = Math.max(0, WideArchLayout.y(y0, component, arrangement, width));
                double top = Math.min(1, WideArchLayout.y(y1, component, arrangement, width));
                if (right > left && top > bottom) reference[0] = net.minecraft.util.shape.VoxelShapes.union(reference[0],
                        net.minecraft.util.shape.VoxelShapes.cuboid(left, bottom, z0, right, top, z1));
            });
        }
        int turns = switch (state.get(FACING)) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
        for (int i = 0; i < turns; i++) {
            net.minecraft.util.shape.VoxelShape[] rotated = {net.minecraft.util.shape.VoxelShapes.empty()};
            reference[0].forEachBox((x0,y0,z0,x1,y1,z1) -> rotated[0] = net.minecraft.util.shape.VoxelShapes.union(rotated[0],
                    net.minecraft.util.shape.VoxelShapes.cuboid(1-z1, y0, x0, 1-z0, y1, x1)));
            reference[0] = rotated[0];
        }
        var actual = state.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
        require(!net.minecraft.util.shape.VoxelShapes.matchesAnywhere(reference[0], actual,
                net.minecraft.util.function.BooleanBiFunction.NOT_SAME), "Optimized collision changed: " + state);
    }

    private static void sharedShapeCache(BlockState state) throws Exception {
        state.initShapeCache();
        var cacheField = AbstractBlock.AbstractBlockState.class.getDeclaredField("shapeCache");
        cacheField.setAccessible(true);
        Object shared = cacheField.get(state);
        require(shared == com.oliver.erydon.state.ArchShapeCaches.create(state), "Shared collision cache is inactive");
        var constructor = shared.getClass().getDeclaredConstructor(BlockState.class);
        constructor.setAccessible(true);
        // Start with no cached metadata, just as vanilla does during initial registration.
        Object vanilla;
        cacheField.set(state, null);
        try { vanilla = constructor.newInstance(state); }
        finally { cacheField.set(state, shared); }
        for (var field : shared.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object expected = field.get(vanilla), actual = field.get(shared);
            if (expected instanceof boolean[] flags) {
                require(java.util.Arrays.equals(flags, (boolean[]) actual), "Shared solid-side flags changed");
            } else if (expected instanceof net.minecraft.util.shape.VoxelShape[] shapes) {
                var cached = (net.minecraft.util.shape.VoxelShape[]) actual;
                for (int i = 0; i < shapes.length; i++) require(!net.minecraft.util.shape.VoxelShapes.matchesAnywhere(
                        shapes[i], cached[i], net.minecraft.util.function.BooleanBiFunction.NOT_SAME), "Shared culling changed");
            } else require(java.util.Objects.equals(expected, actual), "Shared shape/light field changed: " + field.getName());
        }
        var id = net.minecraft.client.render.block.BlockModels.getModelId(state);
        require(id.getVariant().isEmpty(), "Arch still allocates a model ID per state");
        require(id == net.minecraft.client.render.block.BlockModels.getModelId(state.getBlock().getDefaultState()),
                "Arch model identifiers are not shared by material");
    }

    private void transitions(ArchRomanesqueBlock block) throws Exception {
        var field = net.minecraft.state.State.class.getDeclaredField("withTable");
        field.setAccessible(true);
        require(field.get(block.getDefaultState()) instanceof com.oliver.erydon.state.ArchStateTransitions,
                "Arch startup fell back to dense vanilla transition tables");
        for (BlockState state : block.getStateManager().getStates()) {
            for (int width = 1; width <= 6; width++) {
                BlockState changed = state.with(WIDTH, width);
                require(changed.get(WIDTH) == width && changed.with(WIDTH, state.get(WIDTH)) == state,
                        "Width transition lost canonical state");
            }
            for (Arrangement arr : Arrangement.values()) {
                BlockState changed = state.with(ARRANGEMENT, arr);
                require(changed.get(ARRANGEMENT) == arr && changed.with(ARRANGEMENT, state.get(ARRANGEMENT)) == state,
                        "Arrangement transition lost canonical state");
            }
            require(state.with(WATERLOGGED, !state.get(WATERLOGGED)).with(WATERLOGGED, state.get(WATERLOGGED)) == state,
                    "Water transition lost canonical state");
            require(state.with(REFLECTED, !state.get(REFLECTED)).with(REFLECTED, state.get(REFLECTED)) == state,
                    "Reflection transition lost canonical state");
            for (Direction facing : Direction.Type.HORIZONTAL) {
                require(state.with(FACING, facing).with(FACING, state.get(FACING)) == state,
                        "Facing transition lost canonical state");
            }
        }
    }

    private static void fullStartup() throws Exception {
        long started = System.nanoTime();
        com.oliver.erydon.ModBlocks.registerModBlocks();
        long registered = System.nanoTime();
        long states = 0;
        var modelIds = new java.util.HashSet<net.minecraft.client.util.ModelIdentifier>();
        int arches = 0;
        for (Block block : Registries.BLOCK) if (block instanceof ArchRomanesqueBlock) {
            arches++;
            for (BlockState state : block.getStateManager().getStates()) {
                state.initShapeCache();
                modelIds.add(net.minecraft.client.render.block.BlockModels.getModelId(state));
                states++;
            }
            sharedShapeCache(block.getDefaultState());
            sharedShapeCache(block.getDefaultState().with(WIDTH, 6).with(ARRANGEMENT, Arrangement.WIDE_H4_Y0_L0)
                    .with(WATERLOGGED, true));
        }
        require(arches == 486, "Full startup omitted registered arches: " + arches);
        require(modelIds.size() == arches, "Arch model registrations grew with state count");
        long completed = System.nanoTime();
        System.gc();
        Runtime runtime = Runtime.getRuntime();
        System.out.println("ERYDON_ARCH_STARTUP_OK arches=" + arches + " states=" + states
                + " registrationSeconds=" + (registered-started)/1_000_000_000L
                + " shapeSeconds=" + (completed-registered)/1_000_000_000L
                + " sharedShapes=" + com.oliver.erydon.state.ArchShapeCaches.size()
                + " modelIds=" + modelIds.size()
                + " usedMiB=" + (runtime.totalMemory()-runtime.freeMemory())/1024/1024);
    }

    private void callbacks(ArchRomanesqueBlock block) throws Exception {
        ArchRomanesqueBlock mixed = block instanceof ArchGothicBlock ? new ArchGothicBlock(AbstractBlock.Settings.create())
                : block instanceof ArchModernBlock ? new ArchModernBlock(AbstractBlock.Settings.create())
                : new ArchRomanesqueBlock(AbstractBlock.Settings.create());
        for (int width = 1; width <= 6; width++) for (Direction facing : Direction.Type.HORIZONTAL) {
            int selectedWidth = width;
            TestWorld world = world();
            Direction right = facing.rotateYCounterclockwise();
            for (int row = 0; row < 7; row++) for (int x = 0; x < width; x++) {
                BlockPos pos = BlockPos.ORIGIN.offset(right, x).down(row);
                world.states.put(pos, (x%2 == 0 ? block : mixed).getDefaultState().with(FACING, facing)
                        .with(WATERLOGGED, x%2 == 1));
            }
            BlockState before = world.getBlockState(BlockPos.ORIGIN);
            BlockState changed = before.with(WIDTH, width);
            world.states.put(BlockPos.ORIGIN, changed);
            block.onBlockAdded(changed, world, BlockPos.ORIGIN, before, false);
            if (width == 3) block.recalcCluster(world, BlockPos.ORIGIN);
            require(world.states.values().stream().allMatch(state -> state.get(WIDTH) == selectedWidth), "Debug width did not propagate");
            for (int row = 0; row < 7; row++) for (int x = 0; x < width; x++) {
                require(world.getBlockState(BlockPos.ORIGIN.offset(right,x).down(row)).get(WATERLOGGED) == (x%2 == 1),
                        "Width change lost waterlogging");
            }
            if (block.getClass() == ArchRomanesqueBlock.class) {
                Method toggle = ArchRomanesqueBlock.class.getDeclaredMethod("toggleStyleForComponent",
                        net.minecraft.world.World.class, BlockPos.class);
                toggle.setAccessible(true);
                toggle.invoke(block, world, BlockPos.ORIGIN);
                if (width >= 2) require(world.states.values().stream().anyMatch(state -> state.get(ARRANGEMENT).columnL()),
                        "Romanesque column option did not propagate");
            }
            Map<BlockPos, BlockState> settled = Map.copyOf(world.states);
            block.recalcCluster(world, BlockPos.ORIGIN);
            require(world.states.equals(settled), "Recalc changed settled layout");
            if (width > 3) require(world.states.values().stream().anyMatch(state -> state.get(ARRANGEMENT).isWide()), "No wide cluster");
            BlockPos attach = BlockPos.ORIGIN.up();
            ItemPlacementContext ctx = new AutomaticItemPlacementContext(world, attach, facing,
                    new ItemStack(Items.STONE), Direction.DOWN);
            BlockState placed = block.getPlacementState(ctx);
            require(placed.get(WIDTH) == width && placed.get(FACING) == facing, "Placement did not inherit settings");
            world.states.put(attach, placed);
            block.onPlaced(world, attach, placed, null, ItemStack.EMPTY);
            require(world.states.values().stream().allMatch(state -> state.get(WIDTH) == selectedWidth), "Placement reset selected width");
            BlockPos removed = BlockPos.ORIGIN.offset(right, width / 2).down(3);
            BlockState old = world.states.remove(removed);
            block.onStateReplaced(old, world, removed, Blocks.AIR.getDefaultState(), false);
            require(!world.states.containsKey(removed), "Reflow recreated a removed cell");
            require(world.states.values().stream().allMatch(state -> state.get(WIDTH) == selectedWidth), "Removal reset selected width");
            cases++;
        }
    }

    private static TestWorld world() throws Exception {
        TestWorld world = DoubleColumnRepairLaunchProbe.allocate(TestWorld.class);
        world.states = new HashMap<>();
        world.persistentStates = new net.minecraft.world.PersistentStateManager(new java.io.File("in-memory-state"), null);
        return world;
    }

    static final class TestWorld extends ServerWorld {
        Map<BlockPos, BlockState> states;
        net.minecraft.world.PersistentStateManager persistentStates;
        private TestWorld() { super(null,null,null,null,null,null,null,false,0,List.of(),false,null); }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.getDefaultState()); }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public net.minecraft.world.PersistentStateManager getPersistentStateManager() { return persistentStates; }
        @Override public boolean isChunkLoaded(BlockPos pos) { return true; }
        @Override public int getBottomY() { return -64; }
        @Override public int getHeight() { return 512; }
        @Override public boolean setBlockState(BlockPos pos, BlockState state, int flags) {
            if (state == getBlockState(pos)) return false;
            states.put(pos.toImmutable(), state);
            return true;
        }
    }
}
