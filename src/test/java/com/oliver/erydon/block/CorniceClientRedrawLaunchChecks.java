package com.oliver.erydon.block;

import com.oliver.erydon.client.CorniceClientRedraw;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.PendingUpdateManager;
import net.minecraft.client.render.BuiltChunkStorage;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Actual client packet tail, render listener and renderer scheduling, without a game window or GL context. */
public final class CorniceClientRedrawLaunchChecks {
    private CorniceClientRedrawLaunchChecks() { }

    public static void run() throws Exception {
        TestClientWorld world = DoubleColumnRepairLaunchProbe.allocate(TestClientWorld.class);
        world.states = new HashMap<>();
        world.redraws = new HashSet<>();
        field(World.class, "isClient").setBoolean(world, true);
        CaptureStorage storage = DoubleColumnRepairLaunchProbe.allocate(CaptureStorage.class);
        storage.sections = new HashSet<>();
        WorldRenderer renderer = DoubleColumnRepairLaunchProbe.allocate(WorldRenderer.class);
        field(WorldRenderer.class, "chunks").set(renderer, storage);
        try {
            Field sodiumField = field(WorldRenderer.class, "renderer");
            if (sodiumField.getType() == me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer.class) {
                CaptureSodiumRenderer sodium = DoubleColumnRepairLaunchProbe.allocate(CaptureSodiumRenderer.class);
                sodium.storage = storage;
                sodiumField.set(renderer, sodium);
            }
        } catch (NoSuchFieldException vanillaRenderer) {
            // Vanilla reaches the captured BuiltChunkStorage directly. Sodium
            // replaces that terminal path with a block-area rebuild callback.
        }
        field(ClientWorld.class, "worldRenderer").set(world, renderer);
        // Reproduce vanilla's no-write exit without creating a real client
        // chunk: only the injected packet tail can schedule a render here.
        field(ClientWorld.class, "pendingUpdateManager").set(world, new NoWritePendingUpdates());
        BlockPos owner = new BlockPos(19, 7, 15);
        int cases = 0;
        for (String style : new String[]{"modern", "georgian", "byzantine", "gothic"}) {
            Block block = Registries.BLOCK.get(new Identifier("erydon", "glacium_cornice_" + style));
            require(block instanceof CorniceBlock, "Missing client redraw fixture " + style);
            for (CorniceBlock.CorniceShape shape : CorniceBlock.CorniceShape.values()) for (Direction facing : Direction.Type.HORIZONTAL) {
                BlockState state = block.getDefaultState().with(CorniceBlock.SHAPE, shape).with(CorniceBlock.FACING, facing);
                world.states.put(owner, state);
                storage.sections.clear(); storage.calls = 0;
                world.handleBlockUpdate(owner, state, Block.NOTIFY_LISTENERS);
                require(world.states.get(owner) == state, "Client redraw changed the saved owner state");
                require(storage.calls > 0 && storage.sections.contains(new Section(1, 0, 0))
                        && storage.sections.contains(new Section(1, 0, 1)),
                        "Same-state cornice packet failed to reach actual renderer scheduling across the owner chunk border: " + state);
                cases++;
            }
        }
        storage.sections.clear(); storage.calls = 0;
        int reads = world.reads;
        world.states.put(owner, Blocks.STONE.getDefaultState());
        world.handleBlockUpdate(owner, Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
        require(storage.calls == 0 && world.reads == reads, "Ordinary block packet entered cornice redraw or world lookup logic");
        BlockState pendingCornice = Registries.BLOCK.get(new Identifier("erydon", "glacium_cornice_modern")).getDefaultState();
        world.handleBlockUpdate(owner, pendingCornice, Block.NOTIFY_LISTENERS);
        require(storage.calls == 0 && world.states.get(owner).isOf(Blocks.STONE),
                "A rejected pending cornice packet redrew a different current client state");
        require(cases == 208, "Client redraw skipped saved cornice profiles");
        packetOrdering(world, storage, owner, pendingCornice);
        System.out.println("ERYDON_CORNICE_CLIENT_REDRAW_OK: 208 same-state actual packet tails reach ClientWorld listeners and unconditional WorldRenderer scheduling, including both chunk sections at a border; ordinary and rejected pending packets remain untouched");
    }

    private static void packetOrdering(TestClientWorld world, CaptureStorage storage, BlockPos peer, BlockState state) throws Exception {
        BlockPos changedOwner = peer.add(-4, 0, 0), returnWall = changedOwner.north();
        world.states.clear(); world.states.put(peer, state);
        for (boolean removal : new boolean[]{false, true}) {
            world.handleBlockUpdate(peer, state, Block.NOTIFY_LISTENERS);
            world.redraws.clear(); storage.calls = 0;
            BlockState old = removal ? state : Blocks.AIR.getDefaultState();
            BlockState current = removal ? Blocks.AIR.getDefaultState() : state;
            if (removal) world.states.remove(changedOwner); else world.states.put(changedOwner, state);
            CorniceClientRedraw.afterChange(world, changedOwner, old, current);
            require(world.redraws.contains(peer) && storage.calls > 0,
                    "An early peer packet left geometry stale after the later owner " + (removal ? "removal" : "addition"));
        }
        world.states.put(changedOwner, state.with(CorniceBlock.SHAPE, CorniceBlock.CorniceShape.DIAGONAL_PRIMARY));
        for (boolean removal : new boolean[]{false, true}) {
            world.handleBlockUpdate(peer, state, Block.NOTIFY_LISTENERS);
            world.redraws.clear(); storage.calls = 0;
            BlockState old = removal ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState();
            BlockState current = removal ? Blocks.AIR.getDefaultState() : Blocks.STONE.getDefaultState();
            if (removal) world.states.remove(returnWall); else world.states.put(returnWall, current);
            CorniceClientRedraw.afterChange(world, returnWall, old, current);
            require(world.redraws.contains(peer) && world.redraws.contains(changedOwner) && storage.calls > 0,
                    "An early peer packet left its same-owner return stale after the later wall change");
        }
        world.redraws.clear(); storage.calls = 0;
        int reads = world.reads;
        BlockPos ordinary = new BlockPos(100, 7, 100);
        world.states.put(ordinary, Blocks.STONE.getDefaultState());
        CorniceClientRedraw.afterChange(world, ordinary, Blocks.AIR.getDefaultState(), Blocks.STONE.getDefaultState());
        require(world.reads == reads + 4 && storage.calls == 0,
                "An unrelated actual client change exceeded four adjacent angle checks or scheduled a cornice redraw");
        reads = world.reads;
        CorniceClientRedraw.afterChange(world, peer, state, state);
        require(world.reads == reads && storage.calls == 0, "An unchanged block write entered changed-scene discovery");
        field(World.class, "isClient").setBoolean(world, false);
        CorniceClientRedraw.afterChange(world, changedOwner, Blocks.AIR.getDefaultState(), state);
        require(world.reads == reads && storage.calls == 0, "A server block change entered client redraw discovery");
        field(World.class, "isClient").setBoolean(world, true);
        System.out.println("ERYDON_CORNICE_CLIENT_PACKET_ORDER_OK: later owner add/remove and return-wall add/remove redraw peers after an early same-state packet through the actual renderer; unrelated actual client changes use four local reads, unchanged writes and server changes use none");
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private record Section(int x, int y, int z) { }

    private static final class NoWritePendingUpdates extends PendingUpdateManager {
        @Override public boolean hasPendingUpdate(BlockPos pos, BlockState state) { return true; }
    }

    /** Only the actual inherited packet/listener methods run; state reads stay in memory. */
    private static final class TestClientWorld extends ClientWorld {
        Map<BlockPos, BlockState> states;
        Set<BlockPos> redraws;
        int reads;
        private TestClientWorld() { super(null, null, null, null, 0, 0, null, null, false, 0); }
        @Override public BlockState getBlockState(BlockPos pos) {
            reads++;
            return states.getOrDefault(pos, Blocks.AIR.getDefaultState());
        }
        @Override public void updateListeners(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
            redraws.add(pos.toImmutable());
            super.updateListeners(pos, oldState, newState, flags);
        }
    }

    /** Capture the actual renderer's terminal section rebuild call; no graphics objects are initialized. */
    private static final class CaptureStorage extends BuiltChunkStorage {
        Set<Section> sections;
        int calls;
        private CaptureStorage() { super(null, null, 0, null); }
        @Override public void scheduleRebuild(int x, int y, int z, boolean important) {
            calls++;
            sections.add(new Section(x, y, z));
        }
    }

    /** Capture Sodium's real public terminal rebuild API after the actual WorldRenderer dispatch. */
    private static final class CaptureSodiumRenderer extends me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer {
        CaptureStorage storage;
        private CaptureSodiumRenderer() { super(null); }
        @Override public void scheduleRebuildForBlockArea(int minX, int minY, int minZ,
                                                         int maxX, int maxY, int maxZ, boolean important) {
            for (int x = minX >> 4; x <= maxX >> 4; x++)
                for (int y = minY >> 4; y <= maxY >> 4; y++)
                    for (int z = minZ >> 4; z <= maxZ >> 4; z++) storage.scheduleRebuild(x, y, z, important);
        }
    }
}
