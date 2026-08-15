package com.oliver.erydon.network;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.ErydonConfig;
import com.oliver.erydon.util.ErydonLightUpdateQueue;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-authoritative transport for Mod Menu gameplay settings. */
public final class ErydonConfigNetworking {
    public static final Identifier SYNC_PACKET_ID = new Identifier(Erydon.MOD_ID, "server_config_sync");
    public static final Identifier UPDATE_PACKET_ID = new Identifier(Erydon.MOD_ID, "server_config_update");
    public static final Identifier REQUEST_PACKET_ID = new Identifier(Erydon.MOD_ID, "server_config_request");
    public static final int PROTOCOL_VERSION = 2;
    public static final int UPDATE_MODERN_LIGHT_LEVEL = 1;
    public static final int UPDATE_WALL_LIGHT_LEVEL = 1 << 1;
    public static final int UPDATE_PENDANT_LIGHT_LEVEL = 1 << 2;
    public static final int UPDATE_BRAZIER_LIGHT_LEVEL = 1 << 3;
    public static final int UPDATE_OIL_BURNER_LIGHT_LEVEL = 1 << 4;
    public static final int UPDATE_COFFERED_CEILING_LIGHT_LEVEL = 1 << 5;
    public static final int UPDATE_LOADING_SOCIAL = 1 << 6;
    public static final int UPDATE_LOADING_RECALC = 1 << 7;
    public static final int UPDATE_RECALC_SHORT_COMMAND = 1 << 8;
    public static final int UPDATE_RECALC_DEFAULT_RADIUS = 1 << 9;
    public static final int UPDATE_RECALC_MAX_RADIUS = 1 << 10;
    public static final int UPDATE_LIGHT_SETTINGS = UPDATE_MODERN_LIGHT_LEVEL
            | UPDATE_WALL_LIGHT_LEVEL
            | UPDATE_PENDANT_LIGHT_LEVEL
            | UPDATE_BRAZIER_LIGHT_LEVEL
            | UPDATE_OIL_BURNER_LIGHT_LEVEL
            | UPDATE_COFFERED_CEILING_LIGHT_LEVEL;
    public static final int UPDATE_LOADING_MESSAGES = UPDATE_LOADING_SOCIAL | UPDATE_LOADING_RECALC;
    public static final int UPDATE_RECALC_SETTINGS = UPDATE_RECALC_SHORT_COMMAND
            | UPDATE_RECALC_DEFAULT_RADIUS
            | UPDATE_RECALC_MAX_RADIUS;
    private static final int ALL_UPDATE_FIELDS = UPDATE_LIGHT_SETTINGS
            | UPDATE_LOADING_MESSAGES
            | UPDATE_RECALC_SETTINGS;
    private static final long RATE_WINDOW_NANOS = 1_000_000_000L;
    private static final int MAX_PACKETS_PER_WINDOW = 12;
    private static final int MAX_UPDATES_PER_WINDOW = 4;
    private static final Map<UUID, PacketRate> PACKET_RATES = new ConcurrentHashMap<>();
    private static boolean registered;

    private ErydonConfigNetworking() {
    }

    public static synchronized void registerServer() {
        if (registered) {
            return;
        }
        registered = true;

        ServerPlayNetworking.registerGlobalReceiver(UPDATE_PACKET_ID,
                (server, player, handler, buffer, responseSender) -> receiveUpdate(server, player, buffer));
        ServerPlayNetworking.registerGlobalReceiver(REQUEST_PACKET_ID,
                (server, player, handler, buffer, responseSender) -> receiveRequest(server, player, buffer));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                sendSnapshot(handler.getPlayer(), Result.SYNCED, 0));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                PACKET_RATES.remove(handler.getPlayer().getUuid()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> PACKET_RATES.clear());
    }

    public static void writeSnapshot(PacketByteBuf buffer, ErydonConfig.ServerSnapshot snapshot) {
        buffer.writeVarInt(PROTOCOL_VERSION);
        buffer.writeByte(snapshot.modernLightLevel());
        buffer.writeByte(snapshot.wallLightLevel());
        buffer.writeByte(snapshot.pendantLightLevel());
        buffer.writeByte(snapshot.brazierLightLevel());
        buffer.writeByte(snapshot.oilBurnerLightLevel());
        buffer.writeByte(snapshot.cofferedCeilingLightLevel());
        buffer.writeBoolean(snapshot.loadingMessageSocialDiscord());
        buffer.writeBoolean(snapshot.loadingMessageSuggestionsRecalc());
        buffer.writeBoolean(snapshot.recalcShortCommandEnabled());
        buffer.writeVarInt(snapshot.recalcDefaultRadius());
        buffer.writeVarInt(snapshot.recalcMaxRadius());
    }

    public static ErydonConfig.ServerSnapshot readSnapshot(PacketByteBuf buffer) {
        int protocol = buffer.readVarInt();
        if (protocol != PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Unsupported ERYDON config protocol " + protocol);
        }
        return new ErydonConfig.ServerSnapshot(
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte(),
                buffer.readUnsignedByte(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readVarInt(),
                buffer.readVarInt()
        );
    }

    private static void receiveUpdate(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buffer) {
        final int requestNonce;
        try {
            requestNonce = buffer.readVarInt();
            if (requestNonce <= 0) {
                throw new IllegalArgumentException("Invalid ERYDON settings request nonce");
            }
        } catch (RuntimeException exception) {
            rejectMalformed(server, player, true, 0, "settings packet");
            return;
        }

        RateDecision rateDecision = checkRate(player, true);
        if (rateDecision != RateDecision.ALLOW) {
            if (rateDecision == RateDecision.REJECT_AND_NOTIFY) {
                server.execute(() -> sendSnapshot(player, Result.RATE_LIMITED, requestNonce));
            }
            return;
        }

        final int updateFields;
        final ErydonConfig.ServerSnapshot requested;
        try {
            updateFields = buffer.readVarInt();
            requested = readSnapshot(buffer);
            if (updateFields == 0
                    || (updateFields & ~ALL_UPDATE_FIELDS) != 0
                    || buffer.isReadable()
                    || !isWireValid(requested)) {
                throw new IllegalArgumentException("Invalid ERYDON server settings payload");
            }
        } catch (RuntimeException exception) {
            Erydon.LOGGER.warn("[{}] Rejected malformed settings packet from {}.",
                    Erydon.MOD_ID, player.getGameProfile().getName());
            server.execute(() -> sendSnapshot(player, Result.INVALID, requestNonce));
            return;
        }

        server.execute(() -> applyUpdate(server, player, requestNonce, updateFields, requested));
    }

    private static void receiveRequest(MinecraftServer server, ServerPlayerEntity player, PacketByteBuf buffer) {
        final int requestNonce;
        try {
            requestNonce = buffer.readVarInt();
            if (requestNonce <= 0) {
                throw new IllegalArgumentException("Invalid ERYDON sync request nonce");
            }
        } catch (RuntimeException exception) {
            rejectMalformed(server, player, false, 0, "settings request");
            return;
        }

        RateDecision rateDecision = checkRate(player, false);
        if (rateDecision != RateDecision.ALLOW) {
            if (rateDecision == RateDecision.REJECT_AND_NOTIFY) {
                server.execute(() -> sendSnapshot(player, Result.RATE_LIMITED, requestNonce));
            }
            return;
        }
        if (buffer.isReadable()) {
            Erydon.LOGGER.warn("[{}] Rejected malformed settings request from {}.",
                    Erydon.MOD_ID, player.getGameProfile().getName());
            server.execute(() -> sendSnapshot(player, Result.INVALID, requestNonce));
            return;
        }
        server.execute(() -> sendSnapshot(player, Result.SYNCED, requestNonce));
    }

    private static void rejectMalformed(MinecraftServer server, ServerPlayerEntity player,
                                        boolean update, int requestNonce, String packetKind) {
        RateDecision rateDecision = checkRate(player, update);
        if (rateDecision == RateDecision.ALLOW) {
            Erydon.LOGGER.warn("[{}] Rejected malformed {} from {}.",
                    Erydon.MOD_ID, packetKind, player.getGameProfile().getName());
            server.execute(() -> sendSnapshot(player, Result.INVALID, requestNonce));
        } else if (rateDecision == RateDecision.REJECT_AND_NOTIFY) {
            server.execute(() -> sendSnapshot(player, Result.RATE_LIMITED, requestNonce));
        }
    }

    private static void applyUpdate(MinecraftServer server, ServerPlayerEntity player,
                                    int requestNonce, int updateFields,
                                    ErydonConfig.ServerSnapshot requested) {
        if (!player.hasPermissionLevel(2)) {
            sendSnapshot(player, Result.PERMISSION_DENIED, requestNonce);
            return;
        }

        ErydonConfig.ServerSnapshot previous = ErydonConfig.authoritativeServerSettings();
        ErydonConfig.ServerSnapshot merged = mergeUpdate(previous, requested, updateFields);
        if (!ErydonConfig.replaceServerSettings(merged)) {
            sendSnapshot(player, Result.SAVE_FAILED, requestNonce);
            return;
        }

        ErydonConfig.ServerSnapshot saved = ErydonConfig.authoritativeServerSettings();
        if (!previous.sameLightLevels(saved)) {
            ErydonLightUpdateQueue.queueChangedLoadedChunks(server, previous, saved);
        }

        boolean commandTreeChanged = previous.recalcShortCommandEnabled() != saved.recalcShortCommandEnabled();

        for (ServerPlayerEntity onlinePlayer : server.getPlayerManager().getPlayerList()) {
            sendSnapshot(
                    onlinePlayer,
                    onlinePlayer == player ? Result.SAVED : Result.SYNCED,
                    onlinePlayer == player ? requestNonce : 0
            );
            if (commandTreeChanged) {
                server.getCommandManager().sendCommandTree(onlinePlayer);
            }
        }
    }

    private static ErydonConfig.ServerSnapshot mergeUpdate(
            ErydonConfig.ServerSnapshot current,
            ErydonConfig.ServerSnapshot requested,
            int updateFields
    ) {
        return new ErydonConfig.ServerSnapshot(
                selected(updateFields, UPDATE_MODERN_LIGHT_LEVEL) ? requested.modernLightLevel() : current.modernLightLevel(),
                selected(updateFields, UPDATE_WALL_LIGHT_LEVEL) ? requested.wallLightLevel() : current.wallLightLevel(),
                selected(updateFields, UPDATE_PENDANT_LIGHT_LEVEL) ? requested.pendantLightLevel() : current.pendantLightLevel(),
                selected(updateFields, UPDATE_BRAZIER_LIGHT_LEVEL) ? requested.brazierLightLevel() : current.brazierLightLevel(),
                selected(updateFields, UPDATE_OIL_BURNER_LIGHT_LEVEL) ? requested.oilBurnerLightLevel() : current.oilBurnerLightLevel(),
                selected(updateFields, UPDATE_COFFERED_CEILING_LIGHT_LEVEL) ? requested.cofferedCeilingLightLevel() : current.cofferedCeilingLightLevel(),
                selected(updateFields, UPDATE_LOADING_SOCIAL) ? requested.loadingMessageSocialDiscord() : current.loadingMessageSocialDiscord(),
                selected(updateFields, UPDATE_LOADING_RECALC) ? requested.loadingMessageSuggestionsRecalc() : current.loadingMessageSuggestionsRecalc(),
                selected(updateFields, UPDATE_RECALC_SHORT_COMMAND) ? requested.recalcShortCommandEnabled() : current.recalcShortCommandEnabled(),
                selected(updateFields, UPDATE_RECALC_DEFAULT_RADIUS) ? requested.recalcDefaultRadius() : current.recalcDefaultRadius(),
                selected(updateFields, UPDATE_RECALC_MAX_RADIUS) ? requested.recalcMaxRadius() : current.recalcMaxRadius()
        );
    }

    public static int changedFields(
            ErydonConfig.ServerSnapshot baseline,
            ErydonConfig.ServerSnapshot edited
    ) {
        int fields = 0;
        if (baseline.modernLightLevel() != edited.modernLightLevel()) fields |= UPDATE_MODERN_LIGHT_LEVEL;
        if (baseline.wallLightLevel() != edited.wallLightLevel()) fields |= UPDATE_WALL_LIGHT_LEVEL;
        if (baseline.pendantLightLevel() != edited.pendantLightLevel()) fields |= UPDATE_PENDANT_LIGHT_LEVEL;
        if (baseline.brazierLightLevel() != edited.brazierLightLevel()) fields |= UPDATE_BRAZIER_LIGHT_LEVEL;
        if (baseline.oilBurnerLightLevel() != edited.oilBurnerLightLevel()) fields |= UPDATE_OIL_BURNER_LIGHT_LEVEL;
        if (baseline.cofferedCeilingLightLevel() != edited.cofferedCeilingLightLevel()) {
            fields |= UPDATE_COFFERED_CEILING_LIGHT_LEVEL;
        }
        if (baseline.loadingMessageSocialDiscord() != edited.loadingMessageSocialDiscord()) {
            fields |= UPDATE_LOADING_SOCIAL;
        }
        if (baseline.loadingMessageSuggestionsRecalc() != edited.loadingMessageSuggestionsRecalc()) {
            fields |= UPDATE_LOADING_RECALC;
        }
        if (baseline.recalcShortCommandEnabled() != edited.recalcShortCommandEnabled()) {
            fields |= UPDATE_RECALC_SHORT_COMMAND;
        }
        if (baseline.recalcDefaultRadius() != edited.recalcDefaultRadius()) {
            fields |= UPDATE_RECALC_DEFAULT_RADIUS;
        }
        if (baseline.recalcMaxRadius() != edited.recalcMaxRadius()) {
            fields |= UPDATE_RECALC_MAX_RADIUS;
        }
        return fields;
    }

    private static boolean selected(int fields, int field) {
        return (fields & field) != 0;
    }

    private static boolean isWireValid(ErydonConfig.ServerSnapshot snapshot) {
        return inRange(snapshot.modernLightLevel(), ErydonConfig.MIN_LIGHT_LEVEL, ErydonConfig.MAX_LIGHT_LEVEL)
                && inRange(snapshot.wallLightLevel(), ErydonConfig.MIN_LIGHT_LEVEL, ErydonConfig.MAX_LIGHT_LEVEL)
                && inRange(snapshot.pendantLightLevel(), ErydonConfig.MIN_LIGHT_LEVEL, ErydonConfig.MAX_LIGHT_LEVEL)
                && inRange(snapshot.brazierLightLevel(), ErydonConfig.MIN_LIGHT_LEVEL, ErydonConfig.MAX_LIGHT_LEVEL)
                && inRange(snapshot.oilBurnerLightLevel(), ErydonConfig.MIN_LIGHT_LEVEL, ErydonConfig.MAX_LIGHT_LEVEL)
                && inRange(snapshot.cofferedCeilingLightLevel(), ErydonConfig.MIN_LIGHT_LEVEL, ErydonConfig.MAX_LIGHT_LEVEL)
                && inRange(snapshot.recalcDefaultRadius(), ErydonConfig.MIN_RECALC_RADIUS, ErydonConfig.MAX_RECALC_RADIUS)
                && inRange(snapshot.recalcMaxRadius(), ErydonConfig.MIN_RECALC_RADIUS, ErydonConfig.MAX_RECALC_RADIUS)
                && snapshot.recalcDefaultRadius() <= snapshot.recalcMaxRadius();
    }

    private static boolean inRange(int value, int min, int max) {
        return value >= min && value <= max;
    }

    private static void sendSnapshot(ServerPlayerEntity player, Result result, int requestNonce) {
        if (!ServerPlayNetworking.canSend(player, SYNC_PACKET_ID)) {
            return;
        }
        PacketByteBuf response = PacketByteBufs.create();
        writeSnapshot(response, ErydonConfig.authoritativeServerSettings());
        response.writeBoolean(player.hasPermissionLevel(2));
        response.writeEnumConstant(result);
        response.writeVarInt(requestNonce);
        ServerPlayNetworking.send(player, SYNC_PACKET_ID, response);
    }

    private static RateDecision checkRate(ServerPlayerEntity player, boolean update) {
        PacketRate rate = PACKET_RATES.computeIfAbsent(player.getUuid(), ignored -> new PacketRate());
        RateDecision decision = rate.record(update, System.nanoTime());
        if (decision == RateDecision.REJECT_AND_NOTIFY) {
            Erydon.LOGGER.warn("[{}] Rate-limited settings packets from {}.",
                    Erydon.MOD_ID, player.getGameProfile().getName());
        }
        return decision;
    }

    public enum Result {
        SYNCED,
        SAVED,
        PERMISSION_DENIED,
        SAVE_FAILED,
        INVALID,
        RATE_LIMITED
    }

    private enum RateDecision {
        ALLOW,
        REJECT_AND_NOTIFY,
        REJECT
    }

    private static final class PacketRate {
        private long windowStartedNanos;
        private int packets;
        private int updates;
        private boolean rejectionReported;

        private synchronized RateDecision record(boolean update, long nowNanos) {
            if (windowStartedNanos == 0L || nowNanos - windowStartedNanos >= RATE_WINDOW_NANOS) {
                windowStartedNanos = nowNanos;
                packets = 0;
                updates = 0;
                rejectionReported = false;
            }
            packets++;
            if (update) {
                updates++;
            }
            if (packets <= MAX_PACKETS_PER_WINDOW && updates <= MAX_UPDATES_PER_WINDOW) {
                return RateDecision.ALLOW;
            }
            if (!rejectionReported) {
                rejectionReported = true;
                return RateDecision.REJECT_AND_NOTIFY;
            }
            return RateDecision.REJECT;
        }
    }
}
