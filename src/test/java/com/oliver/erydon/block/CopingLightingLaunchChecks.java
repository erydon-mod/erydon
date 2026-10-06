package com.oliver.erydon.block;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Lighting must never enter a chunk lookup while resolving coping shapes. */
final class CopingLightingLaunchChecks {
    static void run(CopingBlock coping) throws Exception {
        var world = DoubleColumnRepairLaunchProbe.allocate(TestWorld.class);
        world.server = DoubleColumnRepairLaunchProbe.allocate(TestServer.class);
        world.server.owner = Thread.currentThread();
        var worker = Executors.newSingleThreadExecutor();
        try {
            int cases = worker.submit(() -> {
                int checked = 0;
                // An unloaded coordinate on a chunk edge exposes neighbour reads.
                var pos = new BlockPos(159999, 80, 159999);
                for (var profile : CopingBlock.Surface.values())
                    for (var facing : Direction.Type.HORIZONTAL)
                        for (boolean offset : new boolean[]{false, true})
                            for (boolean water : new boolean[]{false, true}) {
                                BlockState state = coping.getDefaultState().with(CopingBlock.SURFACE, profile)
                                        .with(CopingBlock.FACING, facing).with(CopingBlock.OFFSET, offset)
                                        .with(CopingBlock.WATERLOGGED, water);
                                var cached = coping.getOutlineShape(state, null, pos, ShapeContext.absent());
                                require(coping.getOutlineShape(state, world, pos, ShapeContext.absent()) == cached,
                                        "Lighting outline must use the cached profile");
                                require(coping.getCollisionShape(state, world, pos, ShapeContext.absent()) == cached,
                                        "Lighting collision must use the cached profile");
                                checked++;
                            }
                return checked;
            }).get(5, TimeUnit.SECONDS);
            require(world.reads == 0, "Lighting queried neighbouring chunks");
            for (var profile : CopingBlock.Surface.values()) {
                int before = world.reads;
                coping.getOutlineShape(coping.getDefaultState().with(CopingBlock.SURFACE, profile),
                        world, BlockPos.ORIGIN, ShapeContext.absent());
                if (profile == CopingBlock.Surface.FLAT || profile.aligned())
                    require(world.reads > before, "Server interaction lost dynamic joins");
            }
            System.out.println("ERYDON_COPING_LIGHTING_OK cases=" + cases);
        } finally {
            worker.shutdownNow();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** Test-only constructor bypass avoids loading a real save or starting a server. */
    private static final class TestServer extends IntegratedServer {
        Thread owner;
        private TestServer() { super(null, null, null, null, null, null, null); }
        @Override public Thread getThread() { return owner; }
    }

    private static final class TestWorld extends ServerWorld {
        TestServer server;
        int reads;
        private TestWorld() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
        @Override public MinecraftServer getServer() { return server; }
        @Override public BlockState getBlockState(BlockPos pos) {
            require(server.isOnThread(), "Lighting attempted a synchronous chunk lookup at " + pos);
            reads++;
            return Blocks.AIR.getDefaultState();
        }
    }
}
