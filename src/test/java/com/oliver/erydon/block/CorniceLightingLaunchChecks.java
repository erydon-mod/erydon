package com.oliver.erydon.block;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Exercise the real ServerWorld lighting-thread guard without a save, server or chunk loading. */
final class CorniceLightingLaunchChecks {
    private CorniceLightingLaunchChecks() { }

    static void run() throws Exception {
        var world = DoubleColumnRepairLaunchProbe.allocate(TestWorld.class);
        world.server = DoubleColumnRepairLaunchProbe.allocate(TestServer.class);
        world.server.owner = Thread.currentThread();
        var worker = Executors.newSingleThreadExecutor();
        try {
            int cases = worker.submit(() -> {
                int checked = 0;
                BlockPos edge = new BlockPos(159999, 80, 159999);
                for (String style : List.of("modern", "georgian", "byzantine", "gothic")) {
                    CorniceBlock block = cornice(style);
                    for (CorniceBlock.CorniceShape shape : CorniceBlock.CorniceShape.values())
                        for (Direction facing : Direction.Type.HORIZONTAL) {
                            BlockState state = block.getDefaultState().with(CorniceBlock.SHAPE, shape).with(CorniceBlock.FACING, facing);
                            var nativeShape = block.getOutlineShape(state, null, edge, ShapeContext.absent());
                            require(block.getOutlineShape(state, world, edge, ShapeContext.absent()) == nativeShape,
                                    "Lighting outline lost its cached native pose: " + state);
                            require(block.getCollisionShape(state, world, edge, ShapeContext.absent()) == nativeShape,
                                    "Lighting collision entered world-dependent joins: " + state);
                            checked++;
                        }
                }
                return checked;
            }).get(10, TimeUnit.SECONDS);
            require(cases == 288 && world.reads == 0, "Lighting shape resolution queried unloaded chunks or skipped states");
            for (String style : List.of("modern", "georgian", "byzantine", "gothic"))
                for (CorniceBlock.CorniceShape shape : List.of(CorniceBlock.CorniceShape.STRAIGHT, CorniceBlock.CorniceShape.DIAGONAL_PRIMARY)) {
                    int before = world.reads;
                    CorniceBlock block = cornice(style);
                    block.getOutlineShape(block.getDefaultState().with(CorniceBlock.SHAPE, shape), world,
                            BlockPos.ORIGIN, ShapeContext.absent());
                    require(world.reads > before, "Main-thread interaction lost dynamic neighbour joins");
                }
            System.out.println("ERYDON_CORNICE_LIGHTING_OK: 4 profiles x 72 saved poses; off-thread outline/collision use cached native geometry with zero world/chunk reads; main-thread joins remain dynamic");
        } finally {
            worker.shutdownNow();
        }
    }

    private static CorniceBlock cornice(String style) {
        var block = Registries.BLOCK.get(new Identifier("erydon", "glacium_cornice_" + style));
        require(block instanceof CorniceBlock, "Missing lighting fixture: " + style);
        return (CorniceBlock) block;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    /** Constructor bypass is confined to tests and never creates a save or starts a server. */
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
