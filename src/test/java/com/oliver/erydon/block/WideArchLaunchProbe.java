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
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.server.world.ServerWorld;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.oliver.erydon.block.ArchRomanesqueBlock.*;
import static com.oliver.erydon.block.DoubleColumnRepairLaunchProbe.require;

/** Real state/cluster coverage; renderer tests use synthetic surfaces without opening Minecraft. */
public final class WideArchLaunchProbe implements PreLaunchEntrypoint {
    private int styleInteractionCases;
    private int styleToggleCases;
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
                    new ArchGothicBlock(AbstractBlock.Settings.create()),
                    new ArchGeorgianBlock(AbstractBlock.Settings.create())};
            String[] styles = {"romanesque", "modern", "gothic", "georgian"};
            Method layout = ArchRomanesqueBlock.class.getDeclaredMethod("computeArrangement",
                    int.class, int.class, int.class, int.class, StyleSet.class);
            layout.setAccessible(true);
            for (int family = 0; family < families.length; family++) {
                ArchRomanesqueBlock block = families[family];
                Registry.register(Registries.BLOCK, new Identifier("erydon", "probe_arch_" + styles[family]), block);
                require(block.getDefaultState().get(WIDTH) == 6, "New placement must automatically grow through six");
                styleInteractionFaces(block);
                transitions(block);
                for (int width = 1; width <= 6; width++) for (int height = 1; height <= 7; height++) {
                    for (StyleSet style : family == 0 ? StyleSet.values() : new StyleSet[]{StyleSet.BASE}) {
                        for (int row = 0; row < height; row++) for (int x = 0; x < width; x++) {
                            Arrangement arr = (Arrangement) layout.invoke(block, width, height, x, row, style);
                            if (family == 0) {
                                if (width > 1) require(arr.isColumnStyle() == (style == StyleSet.COLUMN)
                                                || arr.isVoid(), "Romanesque owner lost its style: " + arr);
                                if (style == StyleSet.BASE && row == height - 1
                                        && row >= (width == 1 ? 1 : width == 2 ? 1 : Math.min(height, (2 * width + 2) / 3))
                                        && (width <= 2 || x != width / 2 || width % 2 == 0)
                                        && (!arr.isVoid())) {
                                    require(arr.isNonColumnBase(), "Plain bottom row lost its edited base: " + arr);
                                }
                            }
                            BlockState state = block.getDefaultState().with(WIDTH, width).with(ARRANGEMENT, arr);
                            if (width > 3 && row < Math.min(height, block instanceof ArchGeorgianBlock ? (width + 2) / 3 : (2 * width + 2) / 3)) {
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
                                if (arr.isWide() && !(block instanceof ArchGeorgianBlock)) {
                                    compareApprovedShape(oriented);
                                    compareApprovedShape(oriented.with(REFLECTED, true));
                                }
                                require(!outline.isEmpty(), "Empty cell cannot be targeted: " + styles[family] + " " + width + "x" + height + " " + arr);
                                if (arr.isWide() && !(block instanceof ArchGeorgianBlock) && !collision.isEmpty()) {
                                    require(collision.getMin(Direction.Axis.Y) >= -1e-6 && collision.getMax(Direction.Axis.Y) <= 1+1e-6,
                                            "Crown collision escaped its vertical cell");
                                }
                                cases++;
                            }
                        }
                    }
                }
                callbacks(block);
                if (block instanceof ArchGeorgianBlock georgian) georgianOwners(georgian);
                automaticGrowth(block);
                debugWidthCyclesOnShortRuns(block);
            }
            System.out.println("ERYDON_ARCH_STYLE_INTERACTION_OK cases=" + styleInteractionCases
                    + " toggles=" + styleToggleCases);
            com.oliver.erydon.client.model.WideArchMeshLaunchChecks.run(java.util.Arrays.copyOf(families, 3));
            com.oliver.erydon.client.model.ArchContinuityLaunchChecks.run();
            System.out.println("ERYDON_WIDE_ARCHES_OK cases=" + cases);
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }

    private void compareApprovedShape(BlockState state) {
        if (!comparedShapes.add(List.of(state.getBlock().getClass(), state.get(ARRANGEMENT), state.get(WIDTH),
                state.get(FACING), state.get(REFLECTED)))) return;
        // Derive the reference from authored boxes independently of the production
        // cache, collecting them before building one exact coordinate grid.
        var arrangement = state.get(ARRANGEMENT);
        int width = state.get(WIDTH);
        var boxes = new java.util.ArrayList<double[]>();
        int turns = switch (state.get(FACING)) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
        for (var component : WideArchLayout.components(arrangement)) {
            var source = state.with(ARRANGEMENT, component.arrangement()).with(WIDTH, 3).with(FACING, Direction.NORTH);
            source.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN).forEachBox((x0,y0,z0,x1,y1,z1) -> {
                double left = Math.max(0, WideArchLayout.x(x0, component, arrangement, width));
                double right = Math.min(1, WideArchLayout.x(x1, component, arrangement, width));
                double bottom = Math.max(0, WideArchLayout.y(y0, component, arrangement, width));
                double top = Math.min(1, WideArchLayout.y(y1, component, arrangement, width));
                if (right <= left || top <= bottom) return;
                double[] box = {left, bottom, z0, right, top, z1};
                for (int turn = 0; turn < turns; turn++) {
                    box = new double[]{1-box[5], box[1], box[0], 1-box[2], box[4], box[3]};
                }
                boxes.add(box);
            });
        }
        var actual = state.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
        require(!net.minecraft.util.shape.VoxelShapes.matchesAnywhere(AlcoveVoxelShapes.fromBoxes(boxes), actual,
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
            } else if (expected instanceof net.minecraft.util.shape.VoxelShape shape) {
                require(actual instanceof net.minecraft.util.shape.VoxelShape cached
                                && !net.minecraft.util.shape.VoxelShapes.matchesAnywhere(shape, cached,
                                net.minecraft.util.function.BooleanBiFunction.NOT_SAME),
                        "Shared collision geometry changed: " + field.getName());
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
        Object table = field.get(block.getDefaultState());
        if (Boolean.getBoolean("erydon.arch.foreignInitializer")) {
            require(table instanceof com.oliver.erydon.compat.state.ForeignArchStateTable,
                    "Arch startup replaced another optimizer's table");
        } else if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("ferritecore")) {
            require(table != null && !(table instanceof com.oliver.erydon.state.ArchStateTransitions),
                    "Arch startup bypassed FerriteCore initialization");
            var fastMap = block.getDefaultState().getClass().getMethod("getStateMap");
            require(fastMap.invoke(block.getDefaultState()) != null, "FerriteCore lookup was not initialized");
        } else {
            require(table instanceof com.oliver.erydon.state.ArchStateTransitions,
                    "Arch startup fell back to dense vanilla transition tables");
        }
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
        require(arches == 648, "Full startup omitted registered arches: " + arches);
        require(modelIds.size() == arches, "Arch model registrations grew with state count");
        com.oliver.erydon.client.model.ArchContinuityLaunchChecks.run();
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

    private void styleInteractionFaces(ArchRomanesqueBlock block) throws Exception {
        TestPlayer player = DoubleColumnRepairLaunchProbe.allocate(TestPlayer.class);
        for (Direction facing : Direction.Type.HORIZONTAL) for (Direction face : Direction.values()) {
            for (Hand hand : Hand.values()) for (boolean sneaking : new boolean[]{false, true}) {
                for (var held : List.of(ItemStack.EMPTY, new ItemStack(Items.STONE), new ItemStack(Items.DEBUG_STICK))) {
                    TestWorld world = world();
                    Direction right = block.getClusterRightDirection(facing);
                    for (int row = 0; row < 4; row++) for (int x = 0; x < 3; x++) {
                        world.states.put(BlockPos.ORIGIN.offset(right, x).down(row),
                                block.getDefaultState().with(FACING, facing));
                    }
                    block.recalcCluster(world, BlockPos.ORIGIN);
                    Map<BlockPos, BlockState> before = Map.copyOf(world.states);
                    player.held = held;
                    player.sneaking = sneaking;
                    BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(BlockPos.ORIGIN), face, BlockPos.ORIGIN, false);
                    boolean toggle = block.getClass() == ArchRomanesqueBlock.class
                            && face.getAxis() == facing.getAxis() && hand == Hand.MAIN_HAND
                            && !sneaking && !held.isOf(Items.DEBUG_STICK);
                    ActionResult result = block.onUse(world.getBlockState(BlockPos.ORIGIN), world,
                            BlockPos.ORIGIN, player, hand, hit);
                    String label = block.getClass().getSimpleName() + " " + facing + " hit=" + face
                            + " hand=" + hand + " sneak=" + sneaking + " held=" + held;
                    require(result == (toggle ? ActionResult.SUCCESS : ActionResult.PASS),
                            "Wrong arch interaction result: " + label);
                    if (toggle) {
                        require(world.states.values().stream().allMatch(state -> state.get(ARRANGEMENT).isColumnStyle()),
                                "Front/back click did not toggle the whole cluster: " + label);
                        require(block.onUse(world.getBlockState(BlockPos.ORIGIN), world,
                                        BlockPos.ORIGIN, player, hand, hit) == ActionResult.SUCCESS,
                                "Front/back return toggle was not consumed: " + label);
                        require(world.states.equals(before), "Second toggle did not restore the plain cluster: " + label);
                        styleToggleCases += 2;
                    } else {
                        require(world.states.equals(before), "Placement/pass click changed the cluster: " + label);
                    }
                    styleInteractionCases++;
                }
            }
        }
    }

    /** Only the fields read by the interaction handler exist in this in-memory player. */
    private static final class TestPlayer extends ServerPlayerEntity {
        private ItemStack held;
        private boolean sneaking;
        private TestPlayer() { super(null, null, null); }
        @Override public ItemStack getStackInHand(Hand hand) { return held; }
        @Override public boolean isSneaking() { return sneaking; }
    }

    private void callbacks(ArchRomanesqueBlock block) throws Exception {
        ArchRomanesqueBlock mixed = block instanceof ArchGeorgianBlock ? new ArchGeorgianBlock(AbstractBlock.Settings.create())
                : block instanceof ArchGothicBlock ? new ArchGothicBlock(AbstractBlock.Settings.create())
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
            if (width == 6) block.recalcCluster(world, BlockPos.ORIGIN);
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

    private void debugWidthCyclesOnShortRuns(ArchRomanesqueBlock block) throws Exception {
        for (int initial : new int[]{1, 3}) {
            TestWorld world = world();
            Direction right = block.getClusterRightDirection(Direction.NORTH);
            for (int x = 0; x < initial; x++) world.states.put(BlockPos.ORIGIN.offset(right, x), block.getDefaultState());
            block.recalcCluster(world, BlockPos.ORIGIN);
            for (int expected = 1; expected <= 6; expected++) {
                require(block.cycleDebugWidth(world, BlockPos.ORIGIN, false) == expected,
                        "Short run trapped the debug cap at its rendered width");
            }
            require(block.cycleDebugWidth(world, BlockPos.ORIGIN, true) == 5, "Reverse cycle missed cap5");
            require(block.cycleDebugWidth(world, BlockPos.ORIGIN, false) == 6, "Automatic reset is unreachable");
            for (int x = initial; x < 6; x++) {
                var pos = BlockPos.ORIGIN.offset(right, x);
                var placed = block.getDefaultState(); world.states.put(pos, placed);
                block.onPlaced(world, pos, placed, null, ItemStack.EMPTY);
            }
            require(world.states.values().stream().allMatch(state -> state.get(WIDTH) == 6),
                    "Returning to automatic mode did not grow the short run");
            block.cycleDebugWidth(world, BlockPos.ORIGIN, false); // cap1
            block.cycleDebugWidth(world, BlockPos.ORIGIN, false); // cap2
            var added = BlockPos.ORIGIN.offset(right, 6);
            world.states.put(added, block.getDefaultState());
            block.onPlaced(world, added, block.getDefaultState(), null, ItemStack.EMPTY);
            // Newly attached members must carry the cap if all original members disappear.
            for (int x = 0; x < 6; x++) {
                var pos = BlockPos.ORIGIN.offset(right, x);
                var removed = world.states.remove(pos);
                block.onStateReplaced(removed, world, pos, Blocks.AIR.getDefaultState(), false);
            }
            for (int x = 7; x < 11; x++) {
                var pos = BlockPos.ORIGIN.offset(right, x);
                var placed = block.getDefaultState(); world.states.put(pos, placed);
                block.onPlaced(world, pos, placed, null, ItemStack.EMPTY);
            }
            require(world.states.values().stream().allMatch(state -> state.get(WIDTH) <= 2),
                    "Manual cap was lost when original members were removed");
            cases++;
        }
    }

    private void automaticGrowth(ArchRomanesqueBlock block) throws Exception {
        for (Direction facing : Direction.Type.HORIZONTAL) {
            TestWorld world = world();
            Direction right = block.getClusterRightDirection(facing);
            for (int length = 1; length <= 13; length++) {
                for (int row = 0; row < 4; row++) {
                    BlockPos pos = BlockPos.ORIGIN.offset(right, length - 1).down(row);
                    BlockState placed = block.getDefaultState().with(FACING, facing);
                    world.states.put(pos, placed);
                    block.onPlaced(world, pos, placed, null, ItemStack.EMPTY);
                }
                for (int x = 0; x < length; x++) {
                    int expected = Math.min(6, length - x / 6 * 6);
                    for (int row = 0; row < 4; row++) {
                        var state = world.getBlockState(BlockPos.ORIGIN.offset(right, x).down(row));
                        require(state.get(WIDTH) == expected,
                                "Automatic run did not widen/restart: " + length + " at " + x + " " + state);
                    }
                }
                Map<BlockPos, BlockState> settled = Map.copyOf(world.states);
                block.recalcCluster(world, BlockPos.ORIGIN);
                require(world.states.equals(settled), "Recalc treated an automatic width as a manual cap");
                cases++;
            }
        }
    }

    private void georgianOwners(ArchGeorgianBlock block) throws Exception {
        var stream = getClass().getClassLoader().getResourceAsStream(
                "assets/erydon/interaction_shapes/arch_georgian.json");
        require(stream != null, "Missing Georgian authored collision fixture");
        com.google.gson.JsonObject source;
        try (var reader = new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)) {
            source = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("components");
        }
        for (int width = 1; width <= 6; width++) for (int height = 1; height <= 5; height++) {
            int crownRows = Math.min(height, (width + 2) / 3);
            for (Direction facing : Direction.Type.HORIZONTAL) {
                TestWorld world = world();
                // The source faces +Z. Its +X axis therefore points this way
                // after rotating the complete design to the placed facing.
                Direction sourceX = facing.rotateYCounterclockwise();
                for (int row = 0; row < height; row++) for (int column = 0; column < width; column++) {
                    world.states.put(BlockPos.ORIGIN.offset(sourceX, column).down(row),
                            block.getDefaultState().with(FACING, facing));
                }
                block.recalcCluster(world, BlockPos.ORIGIN);
                var authored = new java.util.ArrayList<double[]>();
                var placed = new java.util.ArrayList<double[]>();
                for (int row = 0; row < height; row++) for (int column = 0; column < width; column++) {
                    BlockPos owner = BlockPos.ORIGIN.offset(sourceX, column).down(row);
                    BlockState state = world.getBlockState(owner);
                    List<String> expected;
                    if (row < crownRows) {
                        String suffix = width > 3 && height == 1
                                ? "width" + width + "_short_top_column" + column
                                : "width" + width + "_top_row" + row + "_column" + column;
                        expected = List.of(suffix);
                    } else {
                        String part = row == height - 1 ? "base_" : "middle_";
                        expected = width == 1 ? List.of(part + "left", part + "right")
                                : column == 0 ? List.of(part + "left")
                                : column == width - 1 ? List.of(part + "right") : List.of();
                    }
                    require(GeorgianArchLayout.components(state.get(ARRANGEMENT), state.get(WIDTH)).equals(expected),
                            "Georgian child was attached to the wrong owner: " + width + "x" + height
                                    + " " + facing + " column=" + column + " row=" + row + " " + state);
                    for (String suffix : expected) for (var encoded : source.getAsJsonArray(suffix)) {
                        var box = encoded.getAsJsonArray();
                        double[] bounds = new double[6];
                        for (int axis = 0; axis < 6; axis++) bounds[axis] = box.get(axis).getAsDouble() / 16;
                        bounds[0] += column; bounds[3] += column;
                        bounds[1] -= row; bounds[4] -= row;
                        authored.add(georgianRotateBox(bounds, facing));
                    }
                    var collision = state.getCollisionShape(EmptyBlockView.INSTANCE, owner);
                    collision.forEachBox((x0,y0,z0,x1,y1,z1) -> placed.add(new double[]{
                            x0 + owner.getX(), y0 + owner.getY(), z0 + owner.getZ(),
                            x1 + owner.getX(), y1 + owner.getY(), z1 + owner.getZ()}));
                    require(!net.minecraft.util.shape.VoxelShapes.matchesAnywhere(collision,
                            state.with(REFLECTED, true).getCollisionShape(EmptyBlockView.INSTANCE, owner),
                            net.minecraft.util.function.BooleanBiFunction.NOT_SAME),
                            "Paired Georgian geometry applied a second reflection");
                }
                var reference = AlcoveVoxelShapes.fromBoxes(authored);
                require(!net.minecraft.util.shape.VoxelShapes.matchesAnywhere(reference,
                        AlcoveVoxelShapes.fromBoxes(placed), net.minecraft.util.function.BooleanBiFunction.NOT_SAME),
                        "Placed Georgian owners do not assemble the source shape: " + width + "x" + height + " " + facing);
                for (BlockMirror mirror : new BlockMirror[]{BlockMirror.LEFT_RIGHT, BlockMirror.FRONT_BACK}) {
                    var expected = new java.util.ArrayList<double[]>();
                    var actual = new java.util.ArrayList<double[]>();
                    int axis = mirror == BlockMirror.FRONT_BACK ? 0 : 2;
                    for (double[] box : authored) {
                        double[] reflected = box.clone();
                        reflected[axis] = 1 - box[axis + 3];
                        reflected[axis + 3] = 1 - box[axis];
                        expected.add(reflected);
                    }
                    for (var entry : world.states.entrySet()) {
                        BlockPos old = entry.getKey();
                        BlockPos owner = axis == 0 ? new BlockPos(-old.getX(), old.getY(), old.getZ())
                                : new BlockPos(old.getX(), old.getY(), -old.getZ());
                        entry.getValue().mirror(mirror).getCollisionShape(EmptyBlockView.INSTANCE, owner)
                                .forEachBox((x0,y0,z0,x1,y1,z1) -> actual.add(new double[]{
                                        x0 + owner.getX(), y0 + owner.getY(), z0 + owner.getZ(),
                                        x1 + owner.getX(), y1 + owner.getY(), z1 + owner.getZ()}));
                    }
                    require(!net.minecraft.util.shape.VoxelShapes.matchesAnywhere(AlcoveVoxelShapes.fromBoxes(expected),
                            AlcoveVoxelShapes.fromBoxes(actual), net.minecraft.util.function.BooleanBiFunction.NOT_SAME),
                            "Georgian assembled mirror differs from a physical reflection: "
                                    + width + "x" + height + " " + facing + " " + mirror);
                }
                cases++;
            }
        }
    }

    private static double[] georgianRotateBox(double[] bounds, Direction facing) {
        // Rotate assembled coordinates about the first owner's centre. This
        // deliberately differs from rotating each child before attaching it.
        return switch (facing) {
            case NORTH -> new double[]{1-bounds[3], bounds[1], 1-bounds[5], 1-bounds[0], bounds[4], 1-bounds[2]};
            case EAST -> new double[]{bounds[2], bounds[1], 1-bounds[3], bounds[5], bounds[4], 1-bounds[0]};
            case WEST -> new double[]{1-bounds[5], bounds[1], bounds[0], 1-bounds[2], bounds[4], bounds[3]};
            default -> bounds;
        };
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
