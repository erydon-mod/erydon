package com.oliver.erydon.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.oliver.erydon.Erydon;
import com.oliver.erydon.ErydonConfig;
import com.oliver.erydon.util.ClusterRecalcSupport;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayDeque;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class ErydonRecalcCommand {
    private static final int MAX_QUEUED_SCANS = 4;
    private static final int CANDIDATES_PER_TICK = 256;
    private static final int PROGRESS_INTERVAL_TICKS = 100;
    private static final ArrayDeque<QueuedScan> QUEUED_SCANS = new ArrayDeque<>();
    private static boolean tickEventsRegistered;

    private ErydonRecalcCommand() {
    }

    public static void register() {
        registerTickEvents();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // Keep the literal registered so changing the setting at runtime is
            // safe; the live predicate controls whether the alias is visible.
            dispatcher.register(createRecalcCommand("recalc", true));
            register(dispatcher);
        });
    }

    private static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                literal("erydon")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(ErydonSwapCommand.createCommand())
                        .then(ErydonShowcaseCommand.createCommand())
                        .then(createRecalcCommand("recalc", false))
        );
    }

    private static LiteralArgumentBuilder<ServerCommandSource> createRecalcCommand(String name, boolean shortAlias) {
        return literal(name)
                .requires(source -> source.hasPermissionLevel(2)
                        && (!shortAlias || ErydonConfig.recalcShortCommandEnabled()))
                .executes(ctx -> executeHere(ctx.getSource()))
                .then(literal("here")
                        .executes(ctx -> executeHere(ctx.getSource())))
                .then(literal("chunk")
                        .executes(ctx -> executeChunk(ctx.getSource())))
                .then(literal("cancel")
                        .executes(ctx -> cancelScans(ctx.getSource())))
                .then(literal("radius")
                        .then(argument("radius", IntegerArgumentType.integer(
                                ErydonConfig.MIN_RECALC_RADIUS,
                                ErydonConfig.MAX_RECALC_RADIUS))
                                .executes(ctx -> executeRadius(
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "radius")))));
    }

    private static int executeHere(ServerCommandSource source) {
        return executeRadius(source, ErydonConfig.recalcDefaultRadius());
    }

    private static int executeChunk(ServerCommandSource source) {
        ChunkPos chunkPos = new ChunkPos(BlockPos.ofFloored(source.getPosition()));
        return queueScan(
                source,
                Text.translatable("command.erydon.recalc.scope.chunk", chunkPos.x, chunkPos.z),
                new ClusterRecalcSupport.Box(
                        chunkPos.getStartX(),
                        source.getWorld().getBottomY(),
                        chunkPos.getStartZ(),
                        chunkPos.getEndX(),
                        source.getWorld().getTopY() - 1,
                        chunkPos.getEndZ()
                )
        );
    }

    private static int executeRadius(ServerCommandSource source, int radius) {
        int currentMax = ErydonConfig.recalcMaxRadius();
        if (radius < ErydonConfig.MIN_RECALC_RADIUS || radius > currentMax) {
            source.sendError(Text.translatable("command.erydon.recalc.radius_too_large", radius, currentMax));
            return 0;
        }
        BlockPos origin = BlockPos.ofFloored(source.getPosition());
        Text scope = Text.translatable(
                "command.erydon.recalc.scope.radius",
                radius,
                origin.getX(),
                origin.getY(),
                origin.getZ()
        );
        return queueScan(
                source,
                scope,
                new ClusterRecalcSupport.Box(
                        origin.getX() - radius,
                        origin.getY() - radius,
                        origin.getZ() - radius,
                        origin.getX() + radius,
                        origin.getY() + radius,
                        origin.getZ() + radius
                )
        );
    }

    private static int queueScan(ServerCommandSource source, Text scope, ClusterRecalcSupport.Box box) {
        String ownerKey = ownerKey(source);
        if (QUEUED_SCANS.stream().anyMatch(queued -> queued.ownerKey().equals(ownerKey))) {
            source.sendError(Text.translatable("command.erydon.recalc.already_queued"));
            return 0;
        }
        if (QUEUED_SCANS.size() >= MAX_QUEUED_SCANS) {
            source.sendError(Text.translatable("command.erydon.recalc.queue_full"));
            return 0;
        }
        QUEUED_SCANS.addLast(new QueuedScan(
                source,
                ownerKey,
                scope,
                ClusterRecalcSupport.stagedBox(source.getWorld(), box)
        ));
        source.sendFeedback(() -> Text.translatable("command.erydon.recalc.queued", scope), false);
        return 1;
    }

    private static int cancelScans(ServerCommandSource source) {
        int before = QUEUED_SCANS.size();
        if (source.getEntity() instanceof ServerPlayerEntity) {
            String ownerKey = ownerKey(source);
            QUEUED_SCANS.removeIf(queued -> queued.ownerKey().equals(ownerKey));
        } else {
            QUEUED_SCANS.clear();
        }
        int cancelled = before - QUEUED_SCANS.size();
        if (cancelled == 0) {
            source.sendError(Text.translatable("command.erydon.recalc.cancel_none"));
            return 0;
        }
        source.sendFeedback(() -> Text.translatable("command.erydon.recalc.cancelled", cancelled), false);
        return cancelled;
    }

    private static String ownerKey(ServerCommandSource source) {
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            return "player:" + player.getUuid();
        }
        return "source:" + source.getName();
    }

    private static synchronized void registerTickEvents() {
        if (tickEventsRegistered) {
            return;
        }
        tickEventsRegistered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> tickQueuedScan());
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            String ownerKey = "player:" + handler.getPlayer().getUuid();
            QUEUED_SCANS.removeIf(queued -> queued.ownerKey().equals(ownerKey));
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> QUEUED_SCANS.clear());
    }

    private static void tickQueuedScan() {
        QueuedScan queued = QUEUED_SCANS.peekFirst();
        if (queued == null) {
            return;
        }
        try {
            queued.scan().advance(1, CANDIDATES_PER_TICK);
        } catch (RuntimeException exception) {
            QUEUED_SCANS.removeFirst();
            Erydon.LOGGER.error("[{}] Recalculation failed for {}.",
                    Erydon.MOD_ID, queued.ownerKey(), exception);
            queued.source().sendError(Text.translatable("command.erydon.recalc.failed", queued.scope()));
            return;
        }
        queued.ticks++;

        if (queued.scan().isComplete()) {
            QUEUED_SCANS.removeFirst();
            sendSummary(queued.source(), queued.scope(), queued.scan().outcome());
        } else if (queued.ticks % PROGRESS_INTERVAL_TICKS == 0) {
            queued.source().sendFeedback(() -> Text.translatable(
                    "command.erydon.recalc.progress",
                    queued.scope(),
                    queued.scan().scannedChunks(),
                    queued.scan().totalChunks(),
                    queued.scan().pendingCandidates()
            ), false);
        }
    }

    private static void sendSummary(ServerCommandSource source, Text scope, ClusterRecalcSupport.ScanOutcome outcome) {
        Text message;
        if (outcome.skippedClusters() > 0 || outcome.unloadedChunks() > 0) {
            message = Text.translatable(
                    "command.erydon.recalc.summary_with_reasons",
                    outcome.rebuiltClusters(),
                    scope,
                    outcome.touchedBlocks(),
                    outcome.manualLockedClusters(),
                    outcome.unloadedEdgeClusters(),
                    outcome.oversizedClusters(),
                    outcome.unloadedChunks()
            );
        } else {
            message = Text.translatable(
                    "command.erydon.recalc.summary",
                    outcome.rebuiltClusters(),
                    scope,
                    outcome.touchedBlocks()
            );
        }
        Text finalMessage = message;
        source.sendFeedback(() -> finalMessage, false);
    }

    private static final class QueuedScan {
        private final ServerCommandSource source;
        private final String ownerKey;
        private final Text scope;
        private final ClusterRecalcSupport.StagedScan scan;
        private int ticks;

        private QueuedScan(ServerCommandSource source, String ownerKey, Text scope,
                           ClusterRecalcSupport.StagedScan scan) {
            this.source = source;
            this.ownerKey = ownerKey;
            this.scope = scope;
            this.scan = scan;
        }

        private ServerCommandSource source() {
            return source;
        }

        private Text scope() {
            return scope;
        }

        private String ownerKey() {
            return ownerKey;
        }

        private ClusterRecalcSupport.StagedScan scan() {
            return scan;
        }
    }
}
