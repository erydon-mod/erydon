package com.oliver.erydon.client.profile;

import com.google.gson.Gson;
import com.oliver.erydon.Erydon;
import com.oliver.erydon.block.ColumnBlock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Automated development-only benchmark driver; excluded from production JARs. */
public final class ErydonSharedGeometryBenchmarkHarness {
    private static final String SCENARIO_PROPERTY = "erydon.shared_geometry.benchmark_scenario";
    private static final String OUTPUT_PROPERTY = "erydon.shared_geometry.benchmark_output";
    private static final String WORLD_PROPERTY = "erydon.shared_geometry.benchmark_world";
    private static final String MODE_PROPERTY = "erydon.shared_geometry.mode";
    private static final String SCREENSHOT_NAME_PROPERTY = "erydon.shared_geometry.screenshot_name";
    private static final Gson GSON = new Gson();
    private static final int RESOURCE_RELOADS = 10;
    private static final int CHUNK_REBUILDS = 10;
    private static final int STATIONARY_FPS_SECONDS = 30;
    private static final int BENCHMARK_FLOOR_Y = 104;
    private static final List<String> BATCH_SAMPLE_BLOCK_IDS = List.of(
            "aganite_column_circular",
            "aganite_column_square",
            "aganite_cornice_georgian",
            "aganite_ceiling_coffered_georgian_black_small",
            "aganite_ashlar_layer_vertical",
            "glazing_crystal_layer_vertical",
            "aganite_surround_georgian",
            "aganite_window_arch",
            "aganite_arch_romanesque",
            "aganite_arch_modern",
            "aganite_alcove_georgian",
            "aganite_alcove_gothic",
            "aganite_arch_gothic"
    );

    private ErydonSharedGeometryBenchmarkHarness() {
    }

    public static void register() {
        Harness harness = new Harness();
        ClientTickEvents.END_CLIENT_TICK.register(harness::tick);
        Erydon.LOGGER.info(
                "[{}] Development shared-geometry benchmark registered: scenario={}, mode={}.",
                Erydon.MOD_ID,
                harness.scenario.configValue,
                harness.mode
        );
    }

    private static final class Harness {
        private final long registeredNanos = System.nanoTime();
        private final Scenario scenario = Scenario.configured();
        private final Path output = configuredOutput();
        private final String expectedWorld = System.getProperty(
                WORLD_PROPERTY, "Erydon Shared Geometry Benchmark");
        private final String mode = System.getProperty(MODE_PROPERTY, "baseline");
        private final String screenshotName = System.getProperty(SCREENSHOT_NAME_PROPERTY, "");
        private final int processIteration = Integer.getInteger(
                ErydonSharedGeometryMetrics.ITERATION_PROPERTY, 0);
        private int readyTicks;
        private int cooldownTicks;
        private int reloadIteration;
        private long reloadStartedNanos;
        private CompletableFuture<Void> reloadFuture;
        private volatile boolean sceneReady;
        private volatile Throwable sceneFailure;
        private volatile int sceneFloorY = Integer.MIN_VALUE;
        private boolean sceneStarted;
        private boolean clientCameraPositioned;
        private int clientSceneSyncWaitTicks;
        private boolean clientSceneRendererReloaded;
        private WorldStage worldStage = WorldStage.WAITING_FOR_WORLD;
        private int worldTicks;
        private int fpsTicks;
        private int fpsWindowTicks;
        private long fpsWindowTotal;
        private int fpsSecond;
        private int chunkIteration;
        private int chunkWaitTicks;
        private long chunkStartedNanos;
        private boolean finished;
        private boolean screenshotStarted;
        private volatile boolean screenshotReady;
        private int screenshotWaitTicks;
        private boolean itemPrepared;
        private boolean itemScreenOpened;
        private int itemWaitTicks;
        private int originalSelectedSlot = -1;
        private ItemStack originalHotbarStack = ItemStack.EMPTY;
        private boolean originalHudHidden;
        private boolean visualHudPrepared;

        private void tick(MinecraftClient client) {
            if (finished) {
                return;
            }
            try {
                switch (scenario) {
                    case LAUNCH -> tickLaunch(client);
                    case RELOAD -> tickReload(client);
                    case WORLD, VISUAL -> tickWorld(client);
                    case ITEM -> tickItem(client);
                }
            } catch (Throwable throwable) {
                fail(client, throwable);
            }
        }

        private void tickLaunch(MinecraftClient client) {
            if (!titleReady(client)) {
                return;
            }
            writeSample(
                    "client_initialization_to_stable_title",
                    System.nanoTime() - registeredNanos,
                    "ns",
                    "initial_load",
                    processIteration
            );
            writeSnapshot("after_initial_model_baking", processIteration);
            finish(client);
        }

        private void tickReload(MinecraftClient client) {
            if (reloadFuture != null) {
                if (!reloadFuture.isDone()) {
                    return;
                }
                reloadFuture.join();
                reloadIteration++;
                writeSample(
                        "full_resource_reload",
                        System.nanoTime() - reloadStartedNanos,
                        "ns",
                        "resource_reload",
                        reloadIteration
                );
                writeSnapshot("after_resource_reload", reloadIteration);
                reloadFuture = null;
                if (reloadIteration >= RESOURCE_RELOADS) {
                    finish(client);
                } else {
                    cooldownTicks = 20;
                }
                return;
            }
            if (!titleReady(client)) {
                return;
            }
            if (cooldownTicks > 0) {
                cooldownTicks--;
                return;
            }
            reloadStartedNanos = System.nanoTime();
            reloadFuture = client.reloadResources();
        }

        private void tickWorld(MinecraftClient client) {
            switch (worldStage) {
                case WAITING_FOR_WORLD -> waitForWorld(client);
                case WARMING_UP -> warmUpWorld(client);
                case STATIONARY_FPS -> sampleFps(client);
                case START_CHUNK_REBUILD -> startChunkRebuild(client);
                case WAIT_FOR_CHUNK_REBUILD -> waitForChunkRebuild(client);
                case WAIT_FOR_SCREENSHOT -> waitForScreenshot(client);
            }
        }

        private void tickItem(MinecraftClient client) {
            IntegratedServer server = client.getServer();
            if (server == null || client.world == null || client.player == null) {
                return;
            }
            String actualWorld = server.getSaveProperties().getLevelName();
            if (!expectedWorld.equals(actualWorld)) {
                throw new IllegalStateException(
                        "Refusing to alter non-benchmark world '" + actualWorld + "'.");
            }
            if (screenshotStarted) {
                waitForScreenshot(client);
                return;
            }
            if (!itemPrepared) {
                Block gothicColumn = Registries.BLOCK.get(
                        new Identifier(Erydon.MOD_ID, "aganite_column_gothic"));
                if (gothicColumn == Blocks.AIR) {
                    throw new IllegalStateException("Missing ERYDON Aganite Gothic-column item.");
                }
                originalSelectedSlot = client.player.getInventory().selectedSlot;
                originalHotbarStack = client.player.getInventory().getStack(0).copy();
                originalHudHidden = client.options.hudHidden;
                client.player.getInventory().selectedSlot = 0;
                client.player.getInventory().setStack(0, new ItemStack(gothicColumn));
                client.options.hudHidden = false;
                itemPrepared = true;
                return;
            }
            if (!itemScreenOpened) {
                itemWaitTicks++;
                if (itemWaitTicks >= 40) {
                    client.setScreen(new InventoryScreen(client.player));
                    itemScreenOpened = true;
                    itemWaitTicks = 0;
                }
                return;
            }
            if (!(client.currentScreen instanceof HandledScreen<?>)) {
                String screenName = client.currentScreen == null
                        ? "none"
                        : client.currentScreen.getClass().getName();
                throw new IllegalStateException(
                        "Inventory screen closed before item parity capture; current screen="
                                + screenName + ".");
            }
            itemWaitTicks++;
            if (itemWaitTicks >= 60) {
                startScreenshot(client);
            }
        }

        private void waitForWorld(MinecraftClient client) {
            IntegratedServer server = client.getServer();
            if (server == null || client.world == null || client.player == null) {
                return;
            }
            String actualWorld = server.getSaveProperties().getLevelName();
            if (!expectedWorld.equals(actualWorld)) {
                throw new IllegalStateException(
                        "Refusing to alter non-benchmark world '" + actualWorld + "'.");
            }
            if (!sceneStarted) {
                sceneStarted = true;
                server.execute(() -> buildScene(server, client.player.getUuid()));
                return;
            }
            if (sceneFailure != null) {
                throw new IllegalStateException("Benchmark scene creation failed.", sceneFailure);
            }
            if (!sceneReady) {
                return;
            }
            if (scenario == Scenario.VISUAL) {
                if (!visualHudPrepared) {
                    originalHudHidden = client.options.hudHidden;
                    visualHudPrepared = true;
                }
                client.options.hudHidden = true;
                if (!clientCameraPositioned) {
                    client.player.updatePositionAndAngles(
                            26.5D, sceneFloorY + 10.0D, 26.5D, 135.0F, 20.0F);
                    clientCameraPositioned = true;
                    Erydon.LOGGER.info(
                            "[{}] Visual benchmark client camera positioned at x={}, y={}, z={}, yaw={}, pitch={}.",
                            Erydon.MOD_ID,
                            client.player.getX(),
                            client.player.getY(),
                            client.player.getZ(),
                            client.player.getYaw(),
                            client.player.getPitch()
                    );
                }
            }
            int clientColumnBlocks = countClientColumnBlocks(client);
            if (clientColumnBlocks != 664) {
                clientSceneSyncWaitTicks++;
                if (clientSceneSyncWaitTicks == 1 || clientSceneSyncWaitTicks % 100 == 0) {
                    Identifier centreFloor = Registries.BLOCK.getId(
                            client.world.getBlockState(new BlockPos(0, sceneFloorY, 0)).getBlock());
                    Erydon.LOGGER.info(
                            "[{}] Waiting for benchmark scene client sync: column blocks={}/664, centre floor={}.",
                            Erydon.MOD_ID,
                            clientColumnBlocks,
                            centreFloor
                    );
                }
                if (clientSceneSyncWaitTicks > 1200) {
                    throw new IllegalStateException(
                            "Benchmark scene did not reach the client after 60 seconds: Gothic column blocks="
                                    + clientColumnBlocks + "/664.");
                }
                return;
            }
            if (!clientSceneRendererReloaded) {
                client.worldRenderer.reload();
                clientSceneRendererReloaded = true;
            }
            worldStage = WorldStage.WARMING_UP;
            worldTicks = 0;
        }

        private void warmUpWorld(MinecraftClient client) {
            worldTicks++;
            if (worldTicks >= 300 && client.worldRenderer.isTerrainRenderComplete()) {
                writeSnapshot("world_warm", processIteration);
                if (scenario == Scenario.VISUAL) {
                    startScreenshot(client);
                } else {
                    worldStage = WorldStage.STATIONARY_FPS;
                }
            }
        }

        private void startScreenshot(MinecraftClient client) {
            if (screenshotStarted) {
                return;
            }
            if (!screenshotName.matches("[A-Za-z0-9._-]+\\.png")) {
                throw new IllegalArgumentException(
                        "Visual benchmark requires a safe PNG screenshot filename, found '"
                                + screenshotName + "'.");
            }
            screenshotStarted = true;
            Vec3d lookDirection = client.player.getRotationVec(1.0F);
            HitResult hit = client.player.raycast(100.0D, 1.0F, false);
            String hitDescription = hit.getType().name().toLowerCase();
            if (hit instanceof BlockHitResult blockHit) {
                Identifier hitBlock = Registries.BLOCK.getId(
                        client.world.getBlockState(blockHit.getBlockPos()).getBlock());
                hitDescription = hitBlock + "@" + blockHit.getBlockPos().toShortString();
            }
            Erydon.LOGGER.info(
                    "[{}] Visual benchmark capture camera: x={}, y={}, z={}, yaw={}, pitch={}, look={}, ray-hit={}.",
                    Erydon.MOD_ID,
                    client.player.getX(),
                    client.player.getY(),
                    client.player.getZ(),
                    client.player.getYaw(),
                    client.player.getPitch(),
                    lookDirection,
                    hitDescription
            );
            ScreenshotRecorder.saveScreenshot(
                    client.runDirectory,
                    screenshotName,
                    client.getFramebuffer(),
                    message -> {
                        Erydon.LOGGER.info("[{}] Visual benchmark screenshot: {}", Erydon.MOD_ID, message.getString());
                        screenshotReady = true;
                    }
            );
            worldStage = WorldStage.WAIT_FOR_SCREENSHOT;
        }

        private void waitForScreenshot(MinecraftClient client) {
            screenshotWaitTicks++;
            if (screenshotReady) {
                writeSample("screenshot_captured", 1, "count", "visual_parity", processIteration);
                finish(client);
                return;
            }
            if (screenshotWaitTicks > 200) {
                throw new IllegalStateException("Visual benchmark screenshot timed out after 10 seconds.");
            }
        }

        private void sampleFps(MinecraftClient client) {
            fpsTicks++;
            fpsWindowTicks++;
            fpsWindowTotal += client.getCurrentFps();
            if (fpsWindowTicks >= 20) {
                fpsSecond++;
                writeSample(
                        "stationary_fps",
                        (double) fpsWindowTotal / fpsWindowTicks,
                        "fps",
                        "stationary_rendering",
                        fpsSecond
                );
                fpsWindowTicks = 0;
                fpsWindowTotal = 0L;
            }
            if (fpsTicks >= STATIONARY_FPS_SECONDS * 20) {
                worldStage = WorldStage.START_CHUNK_REBUILD;
                cooldownTicks = 20;
            }
        }

        private void startChunkRebuild(MinecraftClient client) {
            if (cooldownTicks > 0) {
                cooldownTicks--;
                return;
            }
            chunkStartedNanos = System.nanoTime();
            chunkWaitTicks = 0;
            client.worldRenderer.reload();
            worldStage = WorldStage.WAIT_FOR_CHUNK_REBUILD;
        }

        private void waitForChunkRebuild(MinecraftClient client) {
            chunkWaitTicks++;
            if (!client.worldRenderer.isTerrainRenderComplete()) {
                if (chunkWaitTicks > 2400) {
                    throw new IllegalStateException("Chunk rebuild timed out after 120 seconds.");
                }
                return;
            }
            chunkIteration++;
            writeSample(
                    "chunk_rebuild",
                    System.nanoTime() - chunkStartedNanos,
                    "ns",
                    "representative_scene",
                    chunkIteration
            );
            if (chunkIteration >= CHUNK_REBUILDS) {
                writeSnapshot("after_chunk_rebuilds", processIteration);
                finish(client);
            } else {
                cooldownTicks = 20;
                worldStage = WorldStage.START_CHUNK_REBUILD;
            }
        }

        private boolean titleReady(MinecraftClient client) {
            if (client.currentScreen instanceof TitleScreen && client.getOverlay() == null) {
                readyTicks++;
            } else {
                readyTicks = 0;
            }
            return readyTicks >= 40;
        }

        private void buildScene(IntegratedServer server, java.util.UUID playerUuid) {
            try {
                ServerWorld world = server.getOverworld();
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
                if (player == null) {
                    throw new IllegalStateException("Benchmark player is unavailable.");
                }

                int flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
                int floorY = BENCHMARK_FLOOR_Y;
                sceneFloorY = floorY;
                Block floor = Registries.BLOCK.get(new Identifier(Erydon.MOD_ID, "aganite_block"));
                if (floor == Blocks.AIR) {
                    throw new IllegalStateException("Missing ERYDON aganite benchmark floor block.");
                }
                for (int x = -26; x <= 26; x++) {
                    for (int z = -26; z <= 26; z++) {
                        world.setBlockState(new BlockPos(x, floorY, z), floor.getDefaultState(), flags);
                        for (int y = floorY + 1; y <= floorY + 8; y++) {
                            world.setBlockState(new BlockPos(x, y, z), Blocks.AIR.getDefaultState(), flags);
                        }
                    }
                }

                List<Block> gothicColumns = new ArrayList<>();
                Registries.BLOCK.stream()
                        .filter(block -> {
                            Identifier id = Registries.BLOCK.getId(block);
                            return Erydon.MOD_ID.equals(id.getNamespace())
                                    && id.getPath().endsWith("_column_gothic");
                        })
                        .sorted(Comparator.comparing(block -> Registries.BLOCK.getId(block).toString()))
                        .forEach(gothicColumns::add);
                if (gothicColumns.size() != 54) {
                    throw new IllegalStateException(
                            "Expected 54 Gothic-column blocks, found " + gothicColumns.size() + ".");
                }

                int cell = 0;
                int expectedColumnBlocks = 0;
                for (int gridZ = 0; gridZ < 14; gridZ++) {
                    for (int gridX = 0; gridX < 14; gridX++) {
                        Block column = gothicColumns.get(cell % gothicColumns.size());
                        int x = -20 + gridX * 3;
                        int z = -20 + gridZ * 3;
                        if (cell % 5 == 0) {
                            world.setBlockState(
                                    new BlockPos(x, floorY + 1, z),
                                    columnState(column, ColumnBlock.ColumnPart.PLINTH),
                                    flags
                            );
                            expectedColumnBlocks++;
                        } else {
                            world.setBlockState(
                                    new BlockPos(x, floorY + 1, z),
                                    columnState(column, ColumnBlock.ColumnPart.BASE),
                                    flags
                            );
                            world.setBlockState(
                                    new BlockPos(x, floorY + 2, z),
                                    columnState(column, ColumnBlock.ColumnPart.PILLAR),
                                    flags
                            );
                            world.setBlockState(
                                    new BlockPos(x, floorY + 3, z),
                                    columnState(column, ColumnBlock.ColumnPart.PILLAR),
                                    flags
                            );
                            world.setBlockState(
                                    new BlockPos(x, floorY + 4, z),
                                    columnState(column, ColumnBlock.ColumnPart.CAPITAL),
                                    flags
                            );
                            expectedColumnBlocks += 4;
                        }
                        cell++;
                    }
                }

                int actualColumnBlocks = 0;
                for (int x = -20; x <= 19; x++) {
                    for (int z = -20; z <= 19; z++) {
                        for (int y = floorY + 1; y <= floorY + 4; y++) {
                            Identifier id = Registries.BLOCK.getId(
                                    world.getBlockState(new BlockPos(x, y, z)).getBlock());
                            if (Erydon.MOD_ID.equals(id.getNamespace())
                                    && id.getPath().endsWith("_column_gothic")) {
                                actualColumnBlocks++;
                            }
                        }
                    }
                }
                if (actualColumnBlocks != expectedColumnBlocks) {
                    throw new IllegalStateException(
                            "Expected " + expectedColumnBlocks + " placed Gothic-column blocks, found "
                                    + actualColumnBlocks + ".");
                }

                List<Block> batchSamples = new ArrayList<>();
                for (String blockId : BATCH_SAMPLE_BLOCK_IDS) {
                    Block block = Registries.BLOCK.get(new Identifier(Erydon.MOD_ID, blockId));
                    if (block == Blocks.AIR) {
                        throw new IllegalStateException("Missing ERYDON batch benchmark block " + blockId + ".");
                    }
                    batchSamples.add(block);
                }
                int batchSampleBlocks = 0;
                for (int row = 0; row < 2; row++) {
                    int z = 22 + row * 2;
                    for (int x = -24; x <= 24; x++) {
                        Block block = batchSamples.get(batchSampleBlocks % batchSamples.size());
                        world.setBlockState(
                                new BlockPos(x, floorY + 1, z),
                                block.getDefaultState(),
                                flags
                        );
                        batchSampleBlocks++;
                    }
                }

                world.setTimeOfDay(6000L);
                world.setWeather(100000, 0, false, false);
                if (scenario == Scenario.VISUAL) {
                    player.getInventory().clear();
                    player.teleport(world, 26.5D, floorY + 10.0D, 26.5D, 135.0F, 20.0F);
                } else {
                    player.teleport(world, 0.5D, floorY + 11.0D, 37.5D, 180.0F, 20.0F);
                }
                server.saveAll(false, false, false);
                Erydon.LOGGER.info(
                        "[{}] Benchmark scene verified: materials={}, column blocks={}, batch samples={}, camera=x{},y{},z{}.",
                        Erydon.MOD_ID,
                        gothicColumns.size(),
                        actualColumnBlocks,
                        batchSampleBlocks,
                        player.getX(),
                        player.getY(),
                        player.getZ()
                );
                sceneReady = true;
            } catch (Throwable throwable) {
                sceneFailure = throwable;
            }
        }

        private static BlockState columnState(Block block, ColumnBlock.ColumnPart part) {
            if (!(block instanceof ColumnBlock)) {
                throw new IllegalArgumentException("Benchmark block is not a ColumnBlock.");
            }
            return block.getDefaultState()
                    .with(ColumnBlock.PART, part)
                    .with(ColumnBlock.BASE, ColumnBlock.BaseStyle.FULL)
                    .with(ColumnBlock.CAPITAL, ColumnBlock.CapitalStyle.GEORGIAN);
        }

        private int countClientColumnBlocks(MinecraftClient client) {
            int actualColumnBlocks = 0;
            for (int x = -20; x <= 19; x++) {
                for (int z = -20; z <= 19; z++) {
                    for (int y = sceneFloorY + 1; y <= sceneFloorY + 4; y++) {
                        Identifier id = Registries.BLOCK.getId(
                                client.world.getBlockState(new BlockPos(x, y, z)).getBlock());
                        if (Erydon.MOD_ID.equals(id.getNamespace())
                                && id.getPath().endsWith("_column_gothic")) {
                            actualColumnBlocks++;
                        }
                    }
                }
            }
            return actualColumnBlocks;
        }

        private void writeSnapshot(String phase, int iteration) {
            Map<String, Object> snapshot = ErydonSharedGeometryMetrics.snapshot();
            writeSnapshotNumber(snapshot, "authoringResourceOpens", "model_resource_open_count", "count", phase, iteration);
            writeSnapshotNumber(snapshot, "authoringModelsParsed", "model_parse_count", "count", phase, iteration);
            writeSnapshotNumber(snapshot, "materialModelsBaked", "material_models_baked", "count", phase, iteration);
            writeSnapshotNumber(snapshot, "baselineGeometryObjects", "baseline_geometry_objects", "count", phase, iteration);
            writeSnapshotNumber(snapshot, "sharedGeometryObjects", "shared_geometry_objects", "count", phase, iteration);
            writeSnapshotNumber(snapshot, "uniqueGeometryBackingObjects", "unique_geometry_objects", "count", phase, iteration);
            writeSnapshotNumber(snapshot, "sharedGeometryCacheHits", "geometry_cache_hits", "count", phase, iteration);
            writeSnapshotNumber(snapshot, "sharedGeometryCacheMisses", "geometry_cache_misses", "count", phase, iteration);
            writeSnapshotNumber(snapshot, "materialBindings", "material_bindings", "count", phase, iteration);
            writeSnapshotNumber(snapshot, "gothicGeometryBakeNanos", "gothic_geometry_bake", "ns", phase, iteration);
            writeSnapshotNumber(snapshot, "materialBindingNanos", "material_binding", "ns", phase, iteration);
            writeSnapshotNumber(snapshot, "baselineVertexPayloadBytes", "baseline_vertex_payload", "bytes", phase, iteration);
            writeSnapshotNumber(snapshot, "baselineEquivalentVertexPayloadBytes", "baseline_equivalent_vertex_payload", "bytes", phase, iteration);
            writeSnapshotNumber(snapshot, "sharedVertexPayloadEstimateBytes", "shared_vertex_payload_estimate", "bytes", phase, iteration);
            writeSnapshotNumber(snapshot, "sharedCompatibilityPayloadBytes", "shared_compatibility_payload", "bytes", phase, iteration);
            writeSnapshotNumber(snapshot, "structuralOverrideFallbacks", "structural_override_fallbacks", "count", phase, iteration);
            writeSnapshotNumber(snapshot, "axiomFallbackGeometries", "axiom_fallback_geometries", "count", phase, iteration);
            writeSnapshotNumber(snapshot, "blockEmissions", "block_emissions", "count", phase, iteration);
            writeSnapshotNumber(snapshot, "emittedSurfaces", "emitted_surfaces", "count", phase, iteration);

            Runtime runtime = Runtime.getRuntime();
            writeSample(
                    "used_heap",
                    runtime.totalMemory() - runtime.freeMemory(),
                    "bytes",
                    phase + "_non_retained_heap_reading",
                    iteration
            );
        }

        private void writeSnapshotNumber(Map<String, Object> snapshot,
                                         String sourceKey,
                                         String metric,
                                         String unit,
                                         String phase,
                                         int iteration) {
            Object value = snapshot.get(sourceKey);
            if (value instanceof Number number) {
                writeSample(metric, number.doubleValue(), unit, phase, iteration);
            }
        }

        private synchronized void writeSample(String metric,
                                              double value,
                                              String unit,
                                              String phase,
                                              int iteration) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("schema_version", 1);
            record.put("event", "benchmark_sample");
            record.put("metric", metric);
            record.put("value", value);
            record.put("unit", unit);
            record.put("phase", phase);
            if (iteration > 0) {
                record.put("iteration", iteration);
            }
            record.put("context", Map.of(
                    "mode", mode,
                    "scenario", scenario.configValue
            ));
            record.put("generated_at", Instant.now().toString());
            try {
                Path parent = output.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(
                        output,
                        GSON.toJson(record) + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to write benchmark record.", exception);
            }
        }

        private void finish(MinecraftClient client) {
            if (finished) {
                return;
            }
            finished = true;
            restoreClientState(client);
            client.scheduleStop();
        }

        private void restoreClientState(MinecraftClient client) {
            if (visualHudPrepared) {
                client.options.hudHidden = originalHudHidden;
                visualHudPrepared = false;
            }
            if (!itemPrepared || client.player == null) {
                return;
            }
            client.player.getInventory().setStack(0, originalHotbarStack);
            if (originalSelectedSlot >= 0) {
                client.player.getInventory().selectedSlot = originalSelectedSlot;
            }
            client.options.hudHidden = originalHudHidden;
            client.setScreen(null);
            itemPrepared = false;
        }

        private void fail(MinecraftClient client, Throwable throwable) {
            Erydon.LOGGER.error(
                    "[{}] Shared-geometry benchmark failed in scenario {}.",
                    Erydon.MOD_ID,
                    scenario.configValue,
                    throwable
            );
            try {
                writeSample("benchmark_failure", 1, "count", "failed", processIteration);
            } catch (Throwable ignored) {
                // Preserve the original failure in the log.
            }
            finish(client);
        }
    }

    private static Path configuredOutput() {
        String configured = System.getProperty(OUTPUT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Missing shared-geometry benchmark output path.");
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private enum Scenario {
        LAUNCH("launch"),
        RELOAD("reload"),
        WORLD("world"),
        VISUAL("visual"),
        ITEM("item");

        private final String configValue;

        Scenario(String configValue) {
            this.configValue = configValue;
        }

        private static Scenario configured() {
            String configured = System.getProperty(SCENARIO_PROPERTY, "launch").trim();
            for (Scenario scenario : values()) {
                if (scenario.configValue.equalsIgnoreCase(configured)) {
                    return scenario;
                }
            }
            throw new IllegalArgumentException("Unsupported benchmark scenario '" + configured + "'.");
        }
    }

    private enum WorldStage {
        WAITING_FOR_WORLD,
        WARMING_UP,
        STATIONARY_FPS,
        START_CHUNK_REBUILD,
        WAIT_FOR_CHUNK_REBUILD,
        WAIT_FOR_SCREENSHOT
    }
}
