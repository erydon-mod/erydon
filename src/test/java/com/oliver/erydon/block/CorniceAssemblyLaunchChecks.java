package com.oliver.erydon.block;

import com.oliver.erydon.block.entity.CorniceAssemblyBlockEntity;
import com.oliver.erydon.block.entity.ModBlockEntities;
import com.oliver.erydon.item.CorniceItem;
import com.oliver.erydon.item.CoverItem;
import com.sun.management.ThreadMXBean;
import net.fabricmc.fabric.api.rendering.data.v1.RenderAttachedBlockView;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.BlockCollisionSpliterator;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.event.GameEvent;

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Real shared-cell mutation, immutable attachments and closed-room joins; no save or game window. */
public final class CorniceAssemblyLaunchChecks {
    private static CorniceAssemblyBlock carrier;
    private static CoverBlock cover;
    private static volatile Object blackhole;
    private static int rings, seams, orderings, transforms, maximumParticles;
    private CorniceAssemblyLaunchChecks() { }

    public static CorniceAssemblyBlock assemblyBlock() { return carrier; }
    public static CoverBlock coverBlock() { return cover; }

    public static void run() throws Exception {
        registerFixtures();
        immutableAndMalformedNbt();
        compoundExactPlanes();
        for (String style : List.of("modern", "georgian", "byzantine", "gothic")) {
            for (BlockRotation rotation : BlockRotation.values()) {
                Scene forward = ring(style, rotation, false);
                Scene reverse = ring(style, rotation, true);
                sameScene(forward, reverse);
                verifyRing(forward);
                transforms(forward);
            }
        }
        allTopCellOrders();
        capacityAndRemoval();
        duplicateSupport();
        legacyOuterConversion();
        oldCoverPlane();
        externalCoverRemoval();
        rejectedReconciliation();
        localRecalc();
        paidItemPaths();
        ordinaryItemExtension();
        lightingAndAttachments();
        performance();
        require(carrier.getStateManager().getStates().size() == 48,
                "Assembly pose/light/water state matrix grew; review state/cache budget");
        System.out.println("ERYDON_CORNICE_ASSEMBLY_OK: " + rings + " closed eight-edge rooms, " + seams
                + " reciprocal depth seams, " + orderings + " placement-order comparisons, " + transforms
                + " NBT/transform cases; global break sampler maximum " + maximumParticles
                + "; two distinct slope supports share a covered cell without ceiling writes");
    }

    private static void registerFixtures() {
        carrier = Registry.register(Registries.BLOCK, new Identifier("erydon", "cornice_assembly"),
                new CorniceAssemblyBlock(Block.Settings.copy(Blocks.STONE)));
        ModBlockEntities.registerCorniceAssembly(carrier);
        cover = Registry.register(Registries.BLOCK, new Identifier("erydon", "glacium_cover"),
                new CoverBlock(Block.Settings.copy(Blocks.STONE).nonOpaque().luminance(CoverBlock::luminance)));
        Registry.register(Registries.ITEM, new Identifier("erydon", "glacium_cover"), new CoverItem(cover, new Item.Settings()));
        carrier.getStateManager().getStates().forEach(BlockState::initShapeCache);
        cover.getStateManager().getStates().forEach(BlockState::initShapeCache);
    }

    private record Paid(BlockPos pos, BlockState state) { }
    private record Scene(MemoryWorld world, List<Paid> paid, Set<BlockPos> owners, Map<BlockPos, BlockState> ceilings) { }

    /** The small octagon forces two different sloped supports and one straight arm into owner (0,0). */
    private static Scene ring(String style, BlockRotation rotation, boolean coverFirst) {
        MemoryWorld world = world();
        BlockState cornice = cornice(style).getDefaultState();
        List<Paid> paid = List.of(
                new Paid(new BlockPos(0, 0, 0), pose(cornice, CorniceBlock.CorniceShape.STRAIGHT, Direction.NORTH)),
                new Paid(new BlockPos(0, 0, 0), pose(cornice, CorniceBlock.CorniceShape.DIAGONAL_SECONDARY, Direction.EAST)),
                new Paid(new BlockPos(1, 0, 1), pose(cornice, CorniceBlock.CorniceShape.STRAIGHT, Direction.EAST)),
                new Paid(new BlockPos(0, 0, 2), pose(cornice, CorniceBlock.CorniceShape.DIAGONAL_PRIMARY, Direction.SOUTH)),
                new Paid(new BlockPos(0, 0, 2), pose(cornice, CorniceBlock.CorniceShape.STRAIGHT, Direction.SOUTH)),
                new Paid(new BlockPos(-1, 0, 1), pose(cornice, CorniceBlock.CorniceShape.DIAGONAL_PRIMARY, Direction.WEST)),
                new Paid(new BlockPos(-1, 0, 1), pose(cornice, CorniceBlock.CorniceShape.STRAIGHT, Direction.WEST)),
                new Paid(new BlockPos(0, 0, 0), pose(cornice, CorniceBlock.CorniceShape.DIAGONAL_PRIMARY, Direction.NORTH)));
        List<Paid> actual = paid.stream().map(part -> new Paid(part.pos.rotate(rotation), part.state.rotate(rotation))).toList();
        Set<BlockPos> owners = new LinkedHashSet<>();
        for (Paid part : actual) {
            owners.add(part.pos);
            BlockPos support = part.state.get(CorniceBlock.SHAPE).isAngled()
                    ? CorniceSlopePlacement.supportPos(part.state, part.pos) : part.pos.offset(part.state.get(CorniceBlock.FACING));
            world.states.put(support, part.state.get(CorniceBlock.SHAPE).isAngled()
                    ? slope(part.state.get(CorniceBlock.FACING)) : Blocks.STONE.getDefaultState());
        }
        Map<BlockPos, BlockState> ceilings = new HashMap<>();
        for (BlockPos pos : new ArrayList<>(world.states.keySet())) ceilings.put(pos.up(), Blocks.STONE.getDefaultState());
        for (BlockPos owner : owners) ceilings.put(owner.up(), Blocks.STONE.getDefaultState());
        world.states.putAll(ceilings);
        if (coverFirst) for (BlockPos owner : owners) add(world, owner, ceiling());
        for (int index = 0; index < actual.size(); index++) {
            Paid part = actual.get(coverFirst ? actual.size() - 1 - index : index);
            add(world, part.pos, part.state);
        }
        if (!coverFirst) for (BlockPos owner : owners) add(world, owner, ceiling());
        for (var entry : ceilings.entrySet()) require(world.states.get(entry.getKey()) == entry.getValue(), "Assembly moved/replaced a ceiling");
        require(owners.containsAll(world.writes), "Assembly mutation wrote a support or ceiling cell: " + world.writes);
        CorniceAssembly.Contents top = CorniceAssembly.contents(world, BlockPos.ORIGIN);
        require(top.cornices().size() == 3 && top.cover() != null, "Closed room did not retain three paid top-cell cornices and cover");
        require(top.cornices().stream().filter(s -> s.get(CorniceBlock.SHAPE).isAngled()).count() == 2,
                "Closed room fixture no longer exercises two slope owners in one cell");
        return new Scene(world, actual, Set.copyOf(owners), Map.copyOf(ceilings));
    }

    private static void verifyRing(Scene scene) {
        List<Located> runs = new ArrayList<>();
        for (Paid paid : scene.paid) {
            List<CorniceConnections.Segment> components = CorniceConnections.components(scene.world, paid.state, paid.pos);
            require(components.size() == 1, "Explicit paid arm also emitted an inferred duplicate return: " + paid);
            CorniceConnections.Segment segment = components.get(0);
            require(segment.joinedStart() && segment.joinedEnd(), "Closed room retained a free end: " + paid + " " + segment);
            runs.add(new Located(paid.pos, segment));
            for (double u : new double[]{.2, .5, .8}) {
                var point = segment.point(u, .125);
                VoxelShape outline = scene.world.getBlockState(paid.pos).getOutlineShape(scene.world, paid.pos);
                require(contains(outline, point.x(), .95, point.z()), "Carrier outline lost a real cornice body: " + paid);
            }
            var point = segment.point(.5, .125);
            double x = paid.pos.getX() + point.x(), z = paid.pos.getZ() + point.z();
            Box query = new Box(x - .002, .941, z - .002, x + .002, .959, z + .002);
            require(scene.world.getBlockCollisions(null, query).iterator().hasNext(), "Actual engine omitted a shared cornice body");
            var owners = new BlockCollisionSpliterator<BlockPos>(scene.world, null, query, false, (pos, shape) -> pos.toImmutable());
            boolean found = false; while (owners.hasNext()) found |= owners.next().equals(paid.pos);
            require(found, "Actual engine collision was provided only by a different owner");
            double nx = segment.run().normalX(), nz = segment.run().normalZ();
            Vec3d start = new Vec3d(x + nx * .45, .95, z + nz * .45), end = new Vec3d(x - nx * .25, .95, z - nz * .25);
            var entity = new net.minecraft.entity.MarkerEntity(net.minecraft.entity.EntityType.MARKER, null);
            var hit = scene.world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, entity));
            require(hit.getBlockPos().equals(paid.pos), "Actual world ray missed the owning shared cornice cell: " + paid);
            int selected = CorniceAssembly.partIndexAt(scene.world, paid.pos, start, end);
            require(selected >= 0 && CorniceAssembly.contents(scene.world, paid.pos).parts().get(selected) == paid.state,
                    "Shared owner ray selected another paid component: " + paid);
        }
        require(runs.size() == 8, "Closed room edge count changed");
        for (BlockPos owner : scene.owners) particleBudget(scene.world, owner);
        for (int index = 0; index < runs.size(); index++) {
            Located a = runs.get(index), b = runs.get((index + 1) % runs.size());
            for (double depth : new double[]{0, .0625, .1875, .3125, .5}) {
                var end = a.segment.point(1, depth); var start = b.segment.point(0, depth);
                require(Math.hypot(a.pos.getX() + end.x() - b.pos.getX() - start.x(),
                        a.pos.getZ() + end.z() - b.pos.getZ() - start.z()) < .00003,
                        "Closed octagon has a profile-depth gap at edge " + index);
                seams++;
            }
        }
        rings++;
    }

    private record Located(BlockPos pos, CorniceConnections.Segment segment) { }

    private static void sameScene(Scene a, Scene b) {
        for (BlockPos owner : a.owners) {
            require(partKey(CorniceAssembly.contents(a.world, owner)).equals(partKey(CorniceAssembly.contents(b.world, owner))),
                    "Placement order changed the paid parts");
            VoxelShape first = a.world.getBlockState(owner).getOutlineShape(a.world, owner);
            VoxelShape second = b.world.getBlockState(owner).getOutlineShape(b.world, owner);
            require(!VoxelShapes.matchesAnywhere(first, second, BooleanBiFunction.NOT_SAME), "Placement order changed physical geometry");
        }
        orderings++;
    }

    private static void allTopCellOrders() {
        List<List<Integer>> orders = new ArrayList<>(); permutations(new ArrayList<>(), new boolean[4], orders);
        Scene reference = ring("modern", BlockRotation.NONE, false);
        List<BlockState> parts = CorniceAssembly.contents(reference.world, BlockPos.ORIGIN).parts();
        VoxelShape expected = reference.world.getBlockState(BlockPos.ORIGIN).getOutlineShape(reference.world, BlockPos.ORIGIN);
        for (List<Integer> order : orders) {
            MemoryWorld world = world(); world.states.putAll(reference.world.states);
            for (var entry : reference.world.entities.entrySet()) {
                var original = (CorniceAssemblyBlockEntity) entry.getValue();
                var copy = new CorniceAssemblyBlockEntity(entry.getKey(), original.getCachedState());
                copy.readNbt(original.createNbt()); copy.setWorld(world); world.entities.put(entry.getKey(), copy);
            }
            world.states.remove(BlockPos.ORIGIN); world.entities.remove(BlockPos.ORIGIN);
            for (int index : order) add(world, BlockPos.ORIGIN, parts.get(index));
            require(partKey(CorniceAssembly.contents(world, BlockPos.ORIGIN)).equals(partKey(CorniceAssembly.contents(reference.world, BlockPos.ORIGIN))),
                    "One of 24 cover/two-slope/cardinal orders lost a part");
            require(!VoxelShapes.matchesAnywhere(expected, world.getBlockState(BlockPos.ORIGIN).getOutlineShape(world, BlockPos.ORIGIN), BooleanBiFunction.NOT_SAME),
                    "One of 24 top-cell placement orders changed its physical outline");
            orderings++;
        }
    }

    private static void permutations(List<Integer> selected, boolean[] used, List<List<Integer>> result) {
        if (selected.size() == used.length) { result.add(List.copyOf(selected)); return; }
        for (int index = 0; index < used.length; index++) if (!used[index]) {
            used[index] = true; selected.add(index); permutations(selected, used, result);
            selected.remove(selected.size() - 1); used[index] = false;
        }
    }

    private static void transforms(Scene scene) {
        for (BlockPos owner : scene.owners) {
            BlockState basis = scene.world.getBlockState(owner);
            var entity = (CorniceAssemblyBlockEntity) scene.world.getBlockEntity(owner);
            NbtCompound saved = entity.createNbt();
            CorniceAssembly.Contents expected = CorniceAssembly.contents(scene.world, owner);
            for (BlockRotation rotation : BlockRotation.values()) for (BlockMirror mirror : BlockMirror.values()) {
                BlockState transformed = basis.rotate(rotation).mirror(mirror);
                var copy = new CorniceAssemblyBlockEntity(owner, transformed); copy.readNbt(saved.copy());
                CorniceAssembly.Contents actual = copy.worldContents(transformed);
                List<BlockState> parts = expected.parts().stream().map(p -> p.rotate(rotation).mirror(mirror)).toList();
                require(partKey(actual).equals(parts.stream().map(Object::toString).sorted().toList()), "NBT move/rotate/mirror changed component states");
                require(CorniceAssembly.toWorld(CorniceAssembly.toLocal(actual, transformed), transformed).equals(actual),
                        "Assembly basis conversion is not reversible");
                require(copy.getRenderAttachmentData() == copy.getRenderAttachmentData(), "Unchanged transformed attachment was rebuilt");
                transforms++;
            }
            var restored = new CorniceAssemblyBlockEntity(owner, basis); restored.readNbt(entity.toInitialChunkDataNbt());
            require(restored.worldContents(basis).equals(expected), "Initial client chunk NBT lost parts/cover settings");
            require(entity.toUpdatePacket() != null, "Assembly has no client entity update packet");
        }
    }

    private static void immutableAndMalformedNbt() {
        List<BlockState> mutable = new ArrayList<>(List.of(cornice("modern").getDefaultState()));
        var snapshot = new CorniceAssembly.Contents(mutable, ceiling()); mutable.clear();
        require(snapshot.cornices().size() == 1 && snapshot.parts().size() == 2, "Attachment retained a mutable caller list");
        try { snapshot.parts().clear(); throw new AssertionError("Attachment parts are mutable"); } catch (UnsupportedOperationException expected) { }
        NbtCompound invalid = new NbtCompound(); NbtList entries = new NbtList();
        for (int index = 0; index < 12; index++) entries.add(NbtHelper.fromBlockState(cornice("modern").getDefaultState()));
        invalid.put("Cornices", entries); invalid.put("Cover", NbtHelper.fromBlockState(Blocks.STONE.getDefaultState()));
        var entity = new CorniceAssemblyBlockEntity(BlockPos.ORIGIN, carrier.getDefaultState()); entity.readNbt(invalid);
        require(entity.contents().cornices().size() <= 4 && entity.contents().cover() == null, "Untrusted NBT escaped the four-part/type bounds");
    }

    private static void compoundExactPlanes() throws ReflectiveOperationException {
        var combine = CorniceAssemblyBlock.class.getDeclaredMethod("combined", List.class); combine.setAccessible(true);
        VoxelShape body = VoxelShapes.cuboid(-.375, .25, .0625, 1.75, 1, .4375);
        VoxelShape ornament = VoxelShapes.cuboid(.28125, .6875, -.0625, .3125, .75, .5);
        for (Direction face : Direction.values()) {
            VoxelShape thinCover = cover.getDefaultState().with(CoverBlock.ATTACH, face)
                    .getOutlineShape(net.minecraft.world.EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
            List<VoxelShape> parts = List.of(body, ornament, thinCover);
            VoxelShape actual = (VoxelShape) combine.invoke(null, parts);
            VoxelShape reference = VoxelShapes.union(VoxelShapes.union(body, ornament), thinCover);
            require(!VoxelShapes.matchesAnywhere(actual, reference, BooleanBiFunction.NOT_SAME),
                    "Compound shape rounded a thin cover or changed an ornament/overhang: " + face);
            require(actual == combine.invoke(null, parts), "Identical compound inputs missed the immutable union cache");
            require(actual.getBoundingBoxes() == actual.getBoundingBoxes(), "Compound ray boxes are rebuilt on every selection");
            try { actual.getBoundingBoxes().clear(); throw new AssertionError("Compound boxes are mutable"); }
            catch (UnsupportedOperationException expected) { }
        }
        System.out.println("ERYDON_CORNICE_ASSEMBLY_EXACT_UNION_OK: all six off-grid thin-cover planes, ornament envelopes and negative/extended bounds match native union; immutable boxes reused");
    }

    private static void capacityAndRemoval() {
        MemoryWorld world = world(); BlockPos pos = new BlockPos(16, 0, 16);
        world.states.put(pos.up(), Blocks.STONE.getDefaultState());
        for (Direction facing : Direction.Type.HORIZONTAL) world.states.put(pos.offset(facing), Blocks.STONE.getDefaultState());
        VoxelShape beforeFourth = null; BlockState samePose = null; int installed = 0;
        for (Direction facing : Direction.Type.HORIZONTAL) {
            if (installed++ == 3) { samePose = world.getBlockState(pos); beforeFourth = samePose.getCollisionShape(world, pos); }
            add(world, pos, cornice("modern").getDefaultState().with(CorniceBlock.FACING, facing));
        }
        require(world.getBlockState(pos) == samePose && VoxelShapes.matchesAnywhere(beforeFourth,
                samePose.getCollisionShape(world, pos), BooleanBiFunction.NOT_SAME),
                "Unchanged carrier state cached collision instead of following its new fourth paid part");
        add(world, pos, ceiling());
        var contents = CorniceAssembly.contents(world, pos);
        require(contents.cornices().size() == 4 && contents.parts().size() == 5, "Four paid cornices and cover do not fit");
        require(!CorniceAssembly.canAdd(world, pos, cornice("georgian").getDefaultState()), "Fifth paid cornice entered a full owner");
        require(!CorniceAssembly.canAdd(world, pos, ceiling()), "A second cover entered the same owner");
        require(world.getBlockState(pos).getLuminance() == 15, "Lit cover did not set carrier block light");
        Object oldAttachment = world.getBlockEntity(pos) instanceof CorniceAssemblyBlockEntity entity ? entity.getRenderAttachmentData() : null;
        require(CorniceAssembly.removePart(world, pos, 4) == contents.cover(), "Removing a cover returned a different paid component");
        require(CorniceAssembly.contents(world, pos).cornices().size() == 4 && world.getBlockState(pos).getLuminance() == 0,
                "Removing a cover removed moulding or retained its light");
        require(oldAttachment instanceof CorniceAssembly.Contents old && old.parts().size() == 5, "Publishing new contents mutated an old render snapshot");
        for (int index = 0; index < 4; index++) require(CorniceAssembly.removePart(world, pos, 0) != null, "Paid cornice disappeared during partial breaking");
        require(world.getBlockState(pos).isAir(), "Empty assembly left an invisible carrier");
        MemoryWorld wet = world(); wet.states.put(pos, Blocks.WATER.getDefaultState()); wet.states.put(pos.up(), Blocks.STONE.getDefaultState());
        wet.states.put(pos.north(), Blocks.STONE.getDefaultState());
        add(wet, pos, cornice("modern").getDefaultState()); add(wet, pos, ceiling());
        require(wet.getBlockState(pos).get(CorniceAssemblyBlock.WATERLOGGED), "Assembly lost original water");
        CorniceAssembly.removePart(wet, pos, 1); CorniceAssembly.removePart(wet, pos, 0);
        require(wet.getBlockState(pos).isOf(Blocks.WATER), "Last removed paid part did not restore original water");
    }

    private static void duplicateSupport() {
        MemoryWorld world = world(); BlockPos owner = BlockPos.ORIGIN;
        world.states.put(owner.west(), slope(Direction.NORTH)); world.states.put(owner.up(), Blocks.STONE.getDefaultState());
        BlockState first = pose(cornice("modern").getDefaultState(), CorniceBlock.CorniceShape.DIAGONAL_PRIMARY, Direction.NORTH);
        add(world, owner, first); add(world, owner, ceiling());
        BlockState otherMaterial = pose(cornice("georgian").getDefaultState(), CorniceBlock.CorniceShape.DIAGONAL_PRIMARY, Direction.NORTH);
        require(!CorniceAssembly.canAdd(world, owner, otherMaterial), "A different style duplicated the same slope face");
        BlockPos alternate = owner.west().south();
        BlockState alternatePose = first.with(CorniceBlock.SHAPE, CorniceBlock.CorniceShape.DIAGONAL_SECONDARY);
        require(!CorniceAssembly.canAdd(world, alternate, alternatePose), "Alternate front cell duplicated an already paid slope face");
        require(CorniceItem.target(world, owner.west(), Direction.SOUTH, first) == null, "Item target bypassed physical support duplicate rejection");
        world.states.remove(owner.west());
        require(!CorniceAssembly.canAdd(world, owner, otherMaterial), "Unsupported angled part was accepted");
    }

    private static void paidItemPaths() {
        for (boolean coverFirst : new boolean[]{false, true}) {
            MemoryWorld world = world(); BlockPos pos = BlockPos.ORIGIN;
            world.states.put(pos.up(), Blocks.STONE.getDefaultState()); world.states.put(pos.west(), slope(Direction.NORTH));
            BlockState initial = coverFirst ? ceiling() : pose(cornice("modern").getDefaultState(), CorniceBlock.CorniceShape.DIAGONAL_PRIMARY, Direction.NORTH);
            add(world, pos, initial);
            ItemStack stack = new ItemStack(coverFirst ? cornice("modern").asItem() : cover.asItem(), 3);
            if (!coverFirst) { var tag = stack.getOrCreateSubNbt("BlockStateTag"); tag.putString("light", "bright"); tag.putString("finish", "gloss"); }
            BlockPos clicked = coverFirst ? pos.west() : pos;
            Direction side = coverFirst ? Direction.EAST : Direction.DOWN;
            ItemUsageContext context = new ItemUsageContext(world, null, Hand.MAIN_HAND, stack,
                    new BlockHitResult(Vec3d.ofCenter(clicked), side, clicked, false));
            ActionResult result = stack.getItem().useOnBlock(context);
            require(result.isAccepted() && stack.getCount() == 2, "Actual shared item path did not consume exactly one paid item");
            var stored = CorniceAssembly.contents(world, pos);
            require(stored.cornices().size() == 1 && stored.cover() != null, "Actual cover-first/cornice-first item placement lost a component");
            require(stored.cover().get(CoverBlock.FINISH) == CoverBlock.CoverFinish.GLOSS
                    && CoverBlock.luminance(stored.cover()) == 15, "Actual item path lost cover stack settings");
            require(world.states.get(pos.up()).isOf(Blocks.STONE), "Actual item placement replaced the ceiling");
            require(world.sounds == 1 && world.events == 1, "Shared item placement omitted or duplicated vanilla sound/event side effects");
        }
        MemoryWorld blocked = world(); blocked.placementAllowed = false;
        blocked.states.put(BlockPos.ORIGIN.up(), Blocks.STONE.getDefaultState()); blocked.states.put(BlockPos.ORIGIN.west(), slope(Direction.NORTH));
        add(blocked, BlockPos.ORIGIN, ceiling()); blocked.writes.clear();
        ItemStack stack = new ItemStack(cornice("modern").asItem(), 3);
        var context = new ItemUsageContext(blocked, null, Hand.MAIN_HAND, stack,
                new BlockHitResult(Vec3d.ofCenter(BlockPos.ORIGIN.west()), Direction.EAST, BlockPos.ORIGIN.west(), false));
        require(!stack.getItem().useOnBlock(context).isAccepted() && stack.getCount() == 3 && blocked.writes.isEmpty(),
                "A rejected valid shared placement fell back to a different cell or consumed an item");
    }

    private static void ordinaryItemExtension() {
        MemoryWorld world = world(); BlockPos owner = BlockPos.ORIGIN, next = owner.east();
        BlockState nativeCornice = cornice("modern").getDefaultState();
        world.states.put(owner, nativeCornice); world.states.put(owner.north(), Blocks.STONE.getDefaultState());
        world.states.put(next.north(), Blocks.STONE.getDefaultState());
        ItemStack stack = new ItemStack(cornice("modern").asItem(), 3);
        var context = new ItemUsageContext(world, null, Hand.MAIN_HAND, stack,
                new BlockHitResult(new Vec3d(1, .95, .25), Direction.EAST, owner, false));
        require(stack.getItem().useOnBlock(context).isAccepted() && stack.getCount() == 2,
                "Clicking a duplicate same-cell attachment blocked ordinary paid run extension");
        require(world.getBlockState(owner) == nativeCornice && world.getBlockState(next).isOf(nativeCornice.getBlock()),
                "Ordinary end-face extension replaced its source or missed the next supported cell");
    }

    private static void legacyOuterConversion() {
        MemoryWorld world = world(); world.lifecycle = true;
        BlockState block = cornice("modern").getDefaultState();
        BlockPos outer = BlockPos.ORIGIN, north = outer.north(), east = outer.east();
        BlockState oldOuter = pose(block, CorniceBlock.CorniceShape.OUTER_CORNER, Direction.EAST);
        BlockState arm = pose(block, CorniceBlock.CorniceShape.STRAIGHT, Direction.EAST);
        world.states.put(outer, oldOuter); world.states.put(north, arm);
        world.states.put(east, pose(block, CorniceBlock.CorniceShape.STRAIGHT, Direction.NORTH));
        world.states.put(outer.east().north(), Blocks.STONE.getDefaultState());
        world.states.put(north.up(), Blocks.STONE.getDefaultState());
        add(world, north, ceiling());
        require(world.getBlockState(outer) == oldOuter, "Cover conversion made a supported native outer corner disappear or change pose");
        require(CorniceAssembly.contents(world, north).cornices().equals(List.of(arm))
                && CorniceAssembly.contents(world, north).cover() != null
                && world.getBlockState(east).isOf(block.getBlock()), "Conversion observer lost one of the three paid native cornices");
        System.out.println("ERYDON_CORNICE_ASSEMBLY_OBSERVER_OK: actual old-cornice replacement callback runs before carrier entity publication; supported outer corner and both arms survive");
    }

    private static void oldCoverPlane() {
        MemoryWorld world = world(); BlockPos owner = BlockPos.ORIGIN, west = owner.west();
        world.states.put(owner.north(), Blocks.STONE.getDefaultState());
        world.states.put(owner.up(), Blocks.STONE.getDefaultState()); world.states.put(west.up(), Blocks.STONE.getDefaultState());
        BlockState large = ceiling().with(CoverBlock.SIZE, CoverBlock.CoverSize.LARGE);
        world.states.put(west, large);
        add(world, owner, cornice("modern").getDefaultState()); add(world, owner, large);
        require((world.getBlockState(west).get(CoverBlock.EXT).mask & 2) == 0, "Large neighbour did not hide its east strip against the shared UP cover");
        var contents = CorniceAssembly.contents(world, owner);
        require(CorniceAssembly.replacePart(world, owner, contents.cornices().size(), contents.cover().with(CoverBlock.ATTACH, Direction.NORTH)),
                "Supported cover plane change was rejected");
        require((world.getBlockState(west).get(CoverBlock.EXT).mask & 2) != 0, "Changing a shared cover plane left the old neighbour's east strip hidden");
        require(CorniceAssembly.contents(world, owner).cornices().size() == 1, "Cover plane change removed its paid moulding");
        particleBudget(world, owner);
    }

    private static void particleBudget(MemoryWorld world, BlockPos pos) {
        int[] particles = {0};
        com.oliver.erydon.client.particle.BlockBreakParticleBudget.emit(world.getBlockState(pos).getOutlineShape(world, pos),
                (x, y, z, vx, vy, vz) -> particles[0]++);
        require(particles[0] > 0 && particles[0] <= com.oliver.erydon.client.particle.BlockBreakParticleBudget.MAX_PARTICLES,
                "Fragmented shared shape escaped the actual global break-particle cap");
        maximumParticles = Math.max(maximumParticles, particles[0]);
    }

    private static void rejectedReconciliation() {
        MemoryWorld world = world(); BlockPos pos = BlockPos.ORIGIN;
        world.states.put(pos.north(), Blocks.STONE.getDefaultState()); world.states.put(pos.up(), Blocks.STONE.getDefaultState());
        BlockState cornice = cornice("modern").getDefaultState(); add(world, pos, cornice); add(world, pos, ceiling());
        var before = CorniceAssembly.contents(world, pos); BlockState host = world.getBlockState(pos);
        world.states.remove(pos.north()); world.writeAllowed = false;
        carrier.scheduledTick(host, world, pos, net.minecraft.util.math.random.Random.create(0));
        require(CorniceAssembly.contents(world, pos).equals(before) && world.getBlockState(pos) == host && world.spawnedItems == 0,
                "Rejected normalization changed paid contents or emitted a duplicate drop");
        world.writeAllowed = true; carrier.scheduledTick(host, world, pos, net.minecraft.util.math.random.Random.create(0));
        require(CorniceAssembly.contents(world, pos).cornices().isEmpty() && CorniceAssembly.contents(world, pos).cover() == before.cover()
                        && world.spawnedItems == 1 && world.lastSpawnedStack.isOf(cornice.getBlock().asItem()),
                "Accepted normalization did not emit exactly the removed paid component");
        System.out.println("ERYDON_CORNICE_ASSEMBLY_REJECTED_WRITE_OK: rejected support reconciliation keeps paid contents and emits no drop; successful retry emits one source item");
    }

    private static void externalCoverRemoval() {
        MemoryWorld world = world(); world.lifecycle = true; BlockPos pos = BlockPos.ORIGIN, west = pos.west(), diagonal = west.north();
        world.states.put(pos.north(), Blocks.STONE.getDefaultState());
        for (BlockPos owner : List.of(pos, west, diagonal)) world.states.put(owner.up(), Blocks.STONE.getDefaultState());
        BlockState large = ceiling().with(CoverBlock.SIZE, CoverBlock.CoverSize.LARGE);
        world.states.put(west, large); world.states.put(diagonal, large);
        add(world, pos, cornice("modern").getDefaultState()); add(world, pos, large);
        require((world.getBlockState(west).get(CoverBlock.EXT).mask & 2) == 0, "Whole-host removal fixture has no hidden east strip");
        world.states.put(diagonal, world.getBlockState(diagonal).with(CoverBlock.EXT, CoverBlock.CoverExt.M0));
        world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        require((world.getBlockState(west).get(CoverBlock.EXT).mask & 2) != 0, "External shared-host removal left its old neighbouring cover strip hidden");
        require(world.getBlockState(diagonal).get(CoverBlock.EXT) != CoverBlock.CoverExt.M0,
                "External shared-host removal skipped the old plane's diagonal refresh");
        System.out.println("ERYDON_CORNICE_ASSEMBLY_EXTERNAL_REMOVAL_OK: whole-host replacement captures old cover before entity removal and refreshes cardinal plus diagonal old-plane neighbours");
    }

    private static void localRecalc() {
        Scene scene = ring("modern", BlockRotation.NONE, false); MemoryWorld world = scene.world;
        var original = CorniceAssembly.contents(world, BlockPos.ORIGIN); world.writes.clear(); world.loaded = false;
        var blocked = carrier.recalcCluster(world, BlockPos.ORIGIN);
        require(blocked.status() == ClusterRebuildableBlock.RecalcStatus.UNLOADED_EDGE && world.writes.isEmpty()
                && CorniceAssembly.contents(world, BlockPos.ORIGIN).equals(original), "Recalc mutated a shared cell before loaded-edge validation");
        world.loaded = true;
        var result = carrier.recalcCluster(world, BlockPos.ORIGIN);
        require(result.recalculated() && result.positions().equals(Set.of(BlockPos.ORIGIN)), "Assembly recalc grew into a neighbour BFS");
        require(CorniceAssembly.contents(world, BlockPos.ORIGIN).equals(original), "Safe recalc lost an existing paid part");
        MemoryWorld edge = world(); BlockPos seed = new BlockPos(11, 0, 0);
        edge.states.put(seed.north(), Blocks.STONE.getDefaultState()); edge.states.put(seed.up(), Blocks.STONE.getDefaultState());
        add(edge, seed, cornice("modern").getDefaultState()); add(edge, seed, ceiling());
        var contents = CorniceAssembly.contents(edge, seed); BlockState state = edge.getBlockState(seed); edge.writes.clear();
        edge.unloadedChunks.add(net.minecraft.util.math.ChunkPos.toLong(1, 0));
        var boundary = carrier.recalcCluster(edge, seed);
        require(boundary.status() == ClusterRebuildableBlock.RecalcStatus.UNLOADED_EDGE && edge.writes.isEmpty()
                        && edge.getBlockState(seed) == state && CorniceAssembly.contents(edge, seed).equals(contents),
                "Radius-five cover/redraw dependency crossed the x=16 unloaded boundary from seed x=11");
        System.out.println("ERYDON_CORNICE_ASSEMBLY_RECALC_EDGE_OK: seed x=11 preflights radius-five chunk x=16 before world/entity reads or mutation; paid contents retained");
    }

    private static void lightingAndAttachments() throws Exception {
        Scene scene = ring("gothic", BlockRotation.NONE, false);
        Map<BlockPos, Object> attachments = new HashMap<>();
        for (BlockPos owner : scene.owners) attachments.put(owner, ((CorniceAssemblyBlockEntity) scene.world.getBlockEntity(owner)).getRenderAttachmentData());
        BlockView snapshot = (BlockView) Proxy.newProxyInstance(BlockView.class.getClassLoader(),
                new Class[]{BlockView.class, RenderAttachedBlockView.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getBlockState" -> scene.world.states.getOrDefault(args[0], Blocks.AIR.getDefaultState());
                    case "getBlockEntityRenderAttachment" -> attachments.get(args[0]);
                    case "getBlockEntity" -> throw new AssertionError("Render attachment path queried a live mutable entity");
                    case "getFluidState" -> scene.world.states.getOrDefault(args[0], Blocks.AIR.getDefaultState()).getFluidState();
                    case "getHeight" -> 384;
                    case "getBottomY" -> -64;
                    default -> { if (method.isDefault()) yield InvocationHandler.invokeDefault(proxy, method, args); throw new UnsupportedOperationException(method.toString()); }
                });
        for (BlockPos owner : scene.owners) {
            require(CorniceAssembly.contents(snapshot, owner) == attachments.get(owner), "Render worker did not reuse its immutable attachment");
            require(!snapshot.getBlockState(owner).getOutlineShape(snapshot, owner).isEmpty(), "Attachment-only view lost composite shape");
            for (BlockState part : CorniceAssembly.contents(snapshot, owner).cornices()) {
                BlockView partView = (BlockView) Proxy.newProxyInstance(BlockView.class.getClassLoader(),
                        new Class[]{BlockView.class, RenderAttachedBlockView.class}, (proxy, method, args) -> {
                            if (method.getName().equals("getBlockState") && owner.equals(args[0])) return part;
                            return method.invoke(snapshot, args);
                        });
                require(CorniceConnections.components(partView, part, owner).equals(CorniceConnections.components(snapshot, part, owner)),
                        "Original-part render view lost shared geometry after the native-state fast path");
            }
        }
        BlockState[] missingState = {carrier.getDefaultState()};
        BlockView missingAttachment = (BlockView) Proxy.newProxyInstance(BlockView.class.getClassLoader(),
                new Class[]{BlockView.class, RenderAttachedBlockView.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getBlockState" -> missingState[0];
                    case "getBlockEntityRenderAttachment" -> null;
                    case "getBlockEntity" -> throw new AssertionError("Missing captured attachment fell back to a live entity");
                    case "getFluidState" -> missingState[0].getFluidState();
                    case "getHeight" -> 384;
                    case "getBottomY" -> -64;
                    default -> { if (method.isDefault()) yield InvocationHandler.invokeDefault(proxy, method, args); throw new UnsupportedOperationException(method.toString()); }
                });
        for (BlockState state : carrier.getStateManager().getStates()) {
            missingState[0] = state;
            require(CorniceAssembly.contents(missingAttachment, BlockPos.ORIGIN).isEmpty()
                            && state.getOutlineShape(missingAttachment, BlockPos.ORIGIN).isEmpty()
                            && state.getCollisionShape(missingAttachment, BlockPos.ORIGIN).isEmpty(),
                    "Packet-gap carrier inferred uncaptured parts or a live entity");
        }
        missingState[0] = cornice("modern").getDefaultState();
        require(CorniceAssembly.contents(missingAttachment, BlockPos.ORIGIN).cornices().equals(List.of(missingState[0])),
                "Missing attachment hid an ordinary standalone cornice");
        missingState[0] = ceiling();
        require(CorniceAssembly.contents(missingAttachment, BlockPos.ORIGIN).cover() == missingState[0],
                "Missing attachment hid an ordinary standalone cover");
        System.out.println("ERYDON_CORNICE_ASSEMBLY_NULL_ATTACHMENT_OK: all 48 captured carrier states stay empty without live entity access during packet gap; ordinary native parts retain standalone fallback");
        var executor = Executors.newSingleThreadExecutor();
        int before = scene.world.reads, beBefore = scene.world.entityReads;
        try {
            executor.submit(() -> {
                for (BlockState state : carrier.getStateManager().getStates()) {
                    carrier.getOutlineShape(state, scene.world, BlockPos.ORIGIN, ShapeContext.absent());
                    carrier.getCollisionShape(state, scene.world, BlockPos.ORIGIN, ShapeContext.absent());
                    require(carrier.getOpacity(state, scene.world, BlockPos.ORIGIN) == 0, "Carrier blocks background light");
                }
            }).get(10, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        require(scene.world.reads == before && scene.world.entityReads == beBefore, "Off-thread assembly shape entered world/entity lookup");
        require(carrier.getTicker(scene.world, carrier.getDefaultState(), ModBlockEntities.corniceAssembly()) == null, "Assembly added a per-tick entity workload");
        System.out.println("ERYDON_CORNICE_ASSEMBLY_LIGHTING_OK: immutable attachment-only shape view; all 48 carrier states avoid world/entity queries off-thread; no entity ticker");
    }

    private static void performance() throws ReflectiveOperationException {
        System.out.printf(Locale.ROOT, "ERYDON_CORNICE_ASSEMBLY_PERF_ENV: Java %s; %s/%s; %d CPUs; max heap %.0f MiB; seven trials; proxy/map fixture, no chunks/GPU/FPS%n",
                System.getProperty("java.version"), System.getProperty("os.name"), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() / 1048576.0);
        for (String style : List.of("modern", "georgian", "byzantine", "gothic")) {
            Scene scene = ring(style, BlockRotation.NONE, false); BlockPos pos = BlockPos.ORIGIN;
            BlockState state = scene.world.getBlockState(pos);
            Supplier<?> outlines = () -> state.getOutlineShape(scene.world, pos);
            outlines.get(); scene.world.reads = 0; scene.world.entityReads = 0; outlines.get();
            int reads = scene.world.reads, entities = scene.world.entityReads;
            // A discovery reads 25 owners, each with <=4 parts and <=8 support/explicit-arm reads.
            // <=2 components x2 reciprocal ports need at most five discoveries per paid cornice.
            int discoveryBound = 25 + 25 * CorniceAssembly.MAX_CORNICES * 8;
            int partBound = 8 + 5 * discoveryBound + 2;
            require(reads <= 1 + CorniceAssembly.MAX_CORNICES * partBound && entities <= reads,
                    "Composite outline escaped its fixed local scan budget");
            System.out.printf(Locale.ROOT, "ERYDON_CORNICE_ASSEMBLY_PERF: %s top-cell 3cornices+cover; %s; %d state/%d entity reads; %d boxes%n",
                    style, measure(outlines, 96, 256), reads, entities, ((VoxelShape) outlines.get()).getBoundingBoxes().size());
            var cacheField = CorniceAssemblyBlock.class.getDeclaredField("SHAPES"); cacheField.setAccessible(true);
            var cache = (com.google.common.cache.Cache<?, ?>) cacheField.get(null);
            double[] cold = new double[7];
            for (int trial = 0; trial < cold.length; trial++) {
                cache.invalidateAll(); long started = System.nanoTime(); blackhole = outlines.get(); cold[trial] = (System.nanoTime() - started) / 1e6;
            }
            Arrays.sort(cold);
            System.out.printf(Locale.ROOT, "ERYDON_CORNICE_ASSEMBLY_COMPOSE_COLD: %s; median %.3f ms (%.3f..%.3f); component rasters warm, union cache independently cleared seven times; retained union estimate %d bytes%n",
                    style, cold[3], cold[0], cold[6], CorniceInteractionShapes.estimatedBytes((VoxelShape) blackhole));
            System.out.printf(Locale.ROOT, "ERYDON_CORNICE_ASSEMBLY_CONTENTS_PERF: %s; %s%n", style,
                    measure(() -> CorniceAssembly.contents(scene.world, pos), 1000, 4000));
            var marker = new net.minecraft.entity.MarkerEntity(net.minecraft.entity.EntityType.MARKER, null);
            var context = new RaycastContext(new Vec3d(.5, .95, 1.5), new Vec3d(.5, .95, -.5),
                    RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, marker);
            Supplier<?> selection = () -> scene.world.raycast(context);
            scene.world.reads = 0; scene.world.entityReads = 0;
            require(((BlockHitResult) selection.get()).getBlockPos().equals(pos), "Timed closed-room selection missed its top shared owner");
            int rayReads = scene.world.reads, rayEntities = scene.world.entityReads;
            System.out.printf(Locale.ROOT, "ERYDON_CORNICE_ASSEMBLY_SELECTION_PERF: %s actual mixed-in world ray; %s; %d state/%d entity reads%n",
                    style, measure(selection, 64, 128), rayReads, rayEntities);
        }
        maximumCapacityPerformance();
    }

    private static void maximumCapacityPerformance() throws ReflectiveOperationException {
        MemoryWorld world = world(); BlockPos pos = BlockPos.ORIGIN;
        world.states.put(pos.up(), Blocks.STONE.getDefaultState());
        List<String> styles = List.of("modern", "georgian", "byzantine", "gothic"); int style = 0;
        for (Direction facing : Direction.Type.HORIZONTAL) {
            world.states.put(pos.offset(facing), Blocks.STONE.getDefaultState());
            add(world, pos, cornice(styles.get(style++)).getDefaultState().with(CorniceBlock.FACING, facing));
        }
        add(world, pos, ceiling()); BlockState state = world.getBlockState(pos);
        require(CorniceAssembly.contents(world, pos).parts().size() == 5, "Maximum-cost fixture lost a paid part");
        Supplier<?> outlines = () -> state.getOutlineShape(world, pos); outlines.get();
        world.reads = 0; world.entityReads = 0; outlines.get(); int reads = world.reads, entities = world.entityReads;
        var cacheField = CorniceAssemblyBlock.class.getDeclaredField("SHAPES"); cacheField.setAccessible(true);
        var cache = (com.google.common.cache.Cache<?, ?>) cacheField.get(null); double[] cold = new double[7];
        for (int trial = 0; trial < cold.length; trial++) {
            cache.invalidateAll(); long started = System.nanoTime(); blackhole = outlines.get(); cold[trial] = (System.nanoTime() - started) / 1e6;
        }
        Arrays.sort(cold); VoxelShape shape = (VoxelShape) outlines.get();
        System.out.printf(Locale.ROOT, "ERYDON_CORNICE_ASSEMBLY_MAX_CAPACITY_PERF: four styles/four cardinal arms+cover; %s; %d state/%d entity reads; cold union median %.3f ms (%.3f..%.3f); %d boxes; retained union estimate %d bytes; child shapes warm, seven independent cache clears%n",
                measure(outlines, 96, 256), reads, entities, cold[3], cold[0], cold[6], shape.getBoundingBoxes().size(),
                CorniceInteractionShapes.estimatedBytes(shape));
        particleBudget(world, pos);
    }

    private static String measure(Supplier<?> operation, int warmup, int count) {
        var bean = ManagementFactory.getThreadMXBean();
        ThreadMXBean meter = bean instanceof ThreadMXBean supported && supported.isThreadAllocatedMemorySupported() ? supported : null;
        if (meter != null && !meter.isThreadAllocatedMemoryEnabled()) meter.setThreadAllocatedMemoryEnabled(true);
        for (int index = 0; index < warmup; index++) blackhole = operation.get();
        double[] times = new double[7], bytes = new double[7]; long thread = Thread.currentThread().getId();
        for (int trial = 0; trial < times.length; trial++) {
            long allocation = meter == null ? 0 : meter.getThreadAllocatedBytes(thread); long start = System.nanoTime();
            for (int index = 0; index < count; index++) blackhole = operation.get();
            times[trial] = (System.nanoTime() - start) / (double) count;
            bytes[trial] = meter == null ? -1 : (meter.getThreadAllocatedBytes(thread) - allocation) / (double) count;
        }
        Arrays.sort(times); Arrays.sort(bytes);
        return String.format(Locale.ROOT, "median %.0f ns (%.0f..%.0f), %.0f allocated bytes; 7x%d after%d warmups", times[3], times[0], times[6], bytes[3], count, warmup);
    }

    private static void add(MemoryWorld world, BlockPos pos, BlockState state) {
        require(CorniceAssembly.canAdd(world, pos, state) && CorniceAssembly.add(world, pos, state), "Supported paid assembly part was rejected: " + pos + " " + state);
    }
    private static BlockState ceiling() { return cover.getDefaultState().with(CoverBlock.ATTACH, Direction.UP)
            .with(CoverBlock.SIZE, CoverBlock.CoverSize.SMALL).with(CoverBlock.FINISH, CoverBlock.CoverFinish.GLOSS).with(CoverBlock.LIGHT, CoverBlock.CoverLight.BRIGHT); }
    private static CorniceBlock cornice(String style) { return (CorniceBlock) Registries.BLOCK.get(new Identifier("erydon", "glacium_cornice_" + style)); }
    private static BlockState pose(BlockState state, CorniceBlock.CorniceShape shape, Direction facing) { return state.with(CorniceBlock.SHAPE, shape).with(CorniceBlock.FACING, facing); }
    private static BlockState slope(Direction facing) { return Registries.BLOCK.get(new Identifier("erydon", "glacium_slope_vertical")).getDefaultState().with(SlopeVerticalBlock.FACING, facing); }
    private static List<String> partKey(CorniceAssembly.Contents contents) { return contents.parts().stream().map(Object::toString).sorted().toList(); }
    private static boolean contains(VoxelShape shape, double x, double y, double z) { return shape.getBoundingBoxes().stream().anyMatch(box -> box.expand(.00001).contains(x, y, z)); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static MemoryWorld world() {
        try {
            MemoryWorld world = DoubleColumnRepairLaunchProbe.allocate(MemoryWorld.class);
            world.server = DoubleColumnRepairLaunchProbe.allocate(MemoryServer.class); world.server.owner = Thread.currentThread();
            world.states = new HashMap<>(); world.entities = new HashMap<>(); world.writes = new LinkedHashSet<>(); world.border = new WorldBorder(); world.placementAllowed = true; world.loaded = true; world.modificationAllowed = true; world.writeAllowed = true;
            world.unloadedChunks = new java.util.HashSet<>();
            world.rules = new net.minecraft.world.GameRules();
            var random = net.minecraft.world.World.class.getDeclaredField("random"); random.setAccessible(true);
            random.set(world, net.minecraft.util.math.random.Random.create(0));
            return world;
        } catch (Exception exception) { throw new AssertionError(exception); }
    }
    private static final class MemoryServer extends IntegratedServer {
        Thread owner;
        private MemoryServer() { super(null, null, null, null, null, null, null); }
        @Override public Thread getThread() { return owner; }
    }
    /** Only required world operations are replaced; production part/shape/NBT/item code runs normally. */
    private static final class MemoryWorld extends ServerWorld {
        MemoryServer server;
        Map<BlockPos, BlockState> states; Map<BlockPos, BlockEntity> entities; Set<BlockPos> writes; WorldBorder border;
        Set<Long> unloadedChunks;
        int reads, entityReads, sounds, events, breakEvents, lastBreakRawId, spawnedItems;
        ItemStack lastSpawnedStack; net.minecraft.world.GameRules rules;
        boolean lifecycle, placementAllowed, loaded, modificationAllowed, writeAllowed;
        private MemoryWorld() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
        @Override public MinecraftServer getServer() { return server; }
        @Override public BlockState getBlockState(BlockPos pos) { require(server.isOnThread(), "Background assembly performed a world read"); require(!unloadedChunks.contains(net.minecraft.util.math.ChunkPos.toLong(pos)), "Assembly read an unloaded dependency"); reads++; return states.getOrDefault(pos, Blocks.AIR.getDefaultState()); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { require(server.isOnThread(), "Background assembly performed an entity read"); require(!unloadedChunks.contains(net.minecraft.util.math.ChunkPos.toLong(pos)), "Assembly read an unloaded entity dependency"); entityReads++; return entities.get(pos); }
        @Override public BlockView getChunkAsView(int x, int z) { return this; }
        @Override public boolean isChunkLoaded(BlockPos pos) { return loaded && !unloadedChunks.contains(net.minecraft.util.math.ChunkPos.toLong(pos)); }
        @Override public FluidState getFluidState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.getDefaultState()).getFluidState(); }
        @Override public boolean setBlockState(BlockPos pos, BlockState state, int flags) {
            require(!unloadedChunks.contains(net.minecraft.util.math.ChunkPos.toLong(pos)), "Assembly wrote an unloaded dependency");
            if (!writeAllowed) return false;
            BlockPos fixed = pos.toImmutable(); BlockState old = states.getOrDefault(pos, Blocks.AIR.getDefaultState());
            writes.add(fixed); states.put(fixed, state);
            if (lifecycle && (old.getBlock() instanceof CorniceBlock || old.getBlock() instanceof CorniceAssemblyBlock)
                    && old.getBlock() != state.getBlock())
                old.getBlock().onStateReplaced(old, this, fixed, state, false);
            if (old.getBlock() != state.getBlock()) entities.remove(fixed);
            if (state.getBlock() instanceof CorniceAssemblyBlock block) {
                BlockEntity entity = entities.get(fixed);
                if (entity == null) { entity = block.createBlockEntity(fixed, state); entities.put(fixed, entity); entity.setWorld(this); }
                entity.setCachedState(state);
            }
            return old != state;
        }
        @Override public void removeBlockEntity(BlockPos pos) { entities.remove(pos); }
        @Override public void markDirty(BlockPos pos) { }
        @Override public void updateComparators(BlockPos pos, Block block) { }
        @Override public void updateNeighbors(BlockPos pos, Block block) { }
        @Override public void updateListeners(BlockPos pos, BlockState old, BlockState state, int flags) { }
        @Override public WorldBorder getWorldBorder() { return border; }
        @Override public net.minecraft.resource.featuretoggle.FeatureSet getEnabledFeatures() {
            return net.minecraft.resource.featuretoggle.FeatureFlags.VANILLA_FEATURES;
        }
        @Override public int getHeight() { return 384; }
        @Override public int getBottomY() { return -64; }
        @Override public boolean canPlace(BlockState state, BlockPos pos, ShapeContext context) { return placementAllowed; }
        @Override public boolean canPlayerModifyAt(PlayerEntity player, BlockPos pos) { return modificationAllowed; }
        @Override public net.minecraft.world.GameRules getGameRules() { return rules; }
        @Override public boolean spawnEntity(net.minecraft.entity.Entity entity) {
            if (entity instanceof net.minecraft.entity.ItemEntity item) { spawnedItems++; lastSpawnedStack = item.getStack().copy(); }
            return true;
        }
        @Override public void syncWorldEvent(PlayerEntity player, int eventId, BlockPos pos, int data) {
            if (eventId == 2001) { breakEvents++; lastBreakRawId = data; }
        }
        @Override public void playSound(PlayerEntity player, BlockPos pos, SoundEvent sound, SoundCategory category, float volume, float pitch) { sounds++; }
        @Override public void emitGameEvent(GameEvent event, Vec3d pos, GameEvent.Emitter emitter) { events++; }
    }
}
