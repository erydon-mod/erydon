package com.oliver.erydon.client;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.ErydonConfig;
import com.oliver.erydon.network.ErydonConfigNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;

/** Client-only facade used by the Mod Menu screens. */
public final class ErydonConfigNetworkingClient {
    private static final long REQUEST_TIMEOUT_NANOS = 10_000_000_000L;
    private static volatile Status status = Status.LOCAL;
    private static int nextRequestNonce = 1;
    private static int pendingSaveNonce;
    private static int pendingSyncNonce;
    private static int lastCompletedSyncNonce;
    private static long saveStartedNanos;
    private static long syncStartedNanos;
    private static volatile boolean registered;

    private ErydonConfigNetworkingClient() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;

        ClientPlayNetworking.registerGlobalReceiver(ErydonConfigNetworking.SYNC_PACKET_ID,
                (client, handler, buffer, responseSender) -> receiveSnapshot(client, buffer));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> requestServerSettings());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ErydonConfig.clearServerSettingsMirror();
            pendingSaveNonce = 0;
            pendingSyncNonce = 0;
            lastCompletedSyncNonce = 0;
            status = Status.LOCAL;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> expirePendingRequests());
    }

    public static ErydonConfig.ServerSnapshot settings() {
        return ErydonConfig.serverSettings();
    }

    public static boolean isServerManaged() {
        return ErydonConfig.hasServerSettingsMirror();
    }

    public static boolean canEdit() {
        return !isConnectedToServer()
                || (ErydonConfig.hasServerSettingsMirror() && ErydonConfig.canEditServerSettings());
    }

    public static Status status() {
        return status;
    }

    public static int lastCompletedSyncNonce() {
        return lastCompletedSyncNonce;
    }

    public static int requestServerSettings() {
        if (canSend(ErydonConfigNetworking.REQUEST_PACKET_ID)) {
            int requestNonce = nextRequestNonce();
            PacketByteBuf request = PacketByteBufs.create();
            request.writeVarInt(requestNonce);
            pendingSyncNonce = requestNonce;
            syncStartedNanos = System.nanoTime();
            status = Status.WAITING_FOR_SERVER;
            ClientPlayNetworking.send(ErydonConfigNetworking.REQUEST_PACKET_ID, request);
            return requestNonce;
        } else if (isConnectedToServer()) {
            status = Status.SERVER_UNSUPPORTED;
        } else {
            status = Status.LOCAL;
        }
        pendingSyncNonce = 0;
        return 0;
    }

    /**
     * Saves locally when no world/server connection exists, otherwise submits
     * one complete snapshot for permission-checked server persistence.
     */
    public static boolean save(ErydonConfig.ServerSnapshot requested, int updateFields) {
        if (!isConnectedToServer()) {
            boolean saved = ErydonConfig.replaceServerSettings(requested);
            status = saved ? Status.LOCAL_SAVED : Status.SAVE_FAILED;
            return saved;
        }
        if (!canSend(ErydonConfigNetworking.UPDATE_PACKET_ID)) {
            status = Status.SERVER_UNSUPPORTED;
            return false;
        }
        if (!ErydonConfig.hasServerSettingsMirror()) {
            status = Status.WAITING_FOR_SERVER;
            return false;
        }
        if (!ErydonConfig.canEditServerSettings()) {
            status = Status.PERMISSION_DENIED;
            return false;
        }

        PacketByteBuf update = PacketByteBufs.create();
        int requestNonce = nextRequestNonce();
        update.writeVarInt(requestNonce);
        update.writeVarInt(updateFields);
        ErydonConfigNetworking.writeSnapshot(update, requested);
        pendingSaveNonce = requestNonce;
        saveStartedNanos = System.nanoTime();
        status = Status.SAVING;
        ClientPlayNetworking.send(ErydonConfigNetworking.UPDATE_PACKET_ID, update);
        return true;
    }

    private static void receiveSnapshot(MinecraftClient client, PacketByteBuf buffer) {
        final ErydonConfig.ServerSnapshot snapshot;
        final boolean canEdit;
        final ErydonConfigNetworking.Result result;
        final int requestNonce;
        try {
            snapshot = ErydonConfigNetworking.readSnapshot(buffer);
            canEdit = buffer.readBoolean();
            result = buffer.readEnumConstant(ErydonConfigNetworking.Result.class);
            requestNonce = buffer.readVarInt();
            if (requestNonce < 0 || buffer.isReadable()) {
                throw new IllegalArgumentException("Trailing ERYDON settings data");
            }
        } catch (RuntimeException exception) {
            Erydon.LOGGER.warn("[{}] Ignored malformed server settings sync.", Erydon.MOD_ID);
            client.execute(() -> {
                pendingSaveNonce = 0;
                pendingSyncNonce = 0;
                status = Status.INVALID_RESPONSE;
            });
            return;
        }

        client.execute(() -> {
            ErydonConfig.installServerSettingsMirror(snapshot, canEdit);
            if (result == ErydonConfigNetworking.Result.SYNCED) {
                if (requestNonce > 0 && requestNonce == pendingSyncNonce) {
                    pendingSyncNonce = 0;
                    lastCompletedSyncNonce = requestNonce;
                    status = canEdit ? Status.SYNCED_EDITABLE : Status.SYNCED_READ_ONLY;
                } else if (requestNonce == 0
                        && pendingSaveNonce == 0
                        && pendingSyncNonce == 0
                        && !isSaveResult(status)) {
                    status = canEdit ? Status.SYNCED_EDITABLE : Status.SYNCED_READ_ONLY;
                }
                return;
            }

            if (requestNonce > 0 && requestNonce == pendingSaveNonce) {
                pendingSaveNonce = 0;
                status = switch (result) {
                    case SYNCED -> throw new IllegalStateException("Handled above");
                    case SAVED -> Status.SAVED;
                    case PERMISSION_DENIED -> Status.PERMISSION_DENIED;
                    case SAVE_FAILED -> Status.SAVE_FAILED;
                    case INVALID -> Status.INVALID_RESPONSE;
                    case RATE_LIMITED -> Status.RATE_LIMITED;
                };
                return;
            }
            if (requestNonce > 0 && requestNonce == pendingSyncNonce) {
                pendingSyncNonce = 0;
                status = result == ErydonConfigNetworking.Result.RATE_LIMITED
                        ? Status.RATE_LIMITED
                        : Status.INVALID_RESPONSE;
            }
        });
    }

    public static boolean isConnectedToServer() {
        return MinecraftClient.getInstance().getNetworkHandler() != null;
    }

    private static boolean canSend(net.minecraft.util.Identifier packetId) {
        return isConnectedToServer() && ClientPlayNetworking.canSend(packetId);
    }

    private static int nextRequestNonce() {
        int nonce = nextRequestNonce++;
        if (nextRequestNonce <= 0) {
            nextRequestNonce = 1;
        }
        return nonce;
    }

    private static void expirePendingRequests() {
        long now = System.nanoTime();
        if (pendingSaveNonce != 0 && now - saveStartedNanos >= REQUEST_TIMEOUT_NANOS) {
            pendingSaveNonce = 0;
            status = Status.TIMED_OUT;
        }
        if (pendingSyncNonce != 0 && now - syncStartedNanos >= REQUEST_TIMEOUT_NANOS) {
            pendingSyncNonce = 0;
            status = Status.TIMED_OUT;
        }
    }

    private static boolean isSaveResult(Status candidate) {
        return candidate == Status.SAVED
                || candidate == Status.PERMISSION_DENIED
                || candidate == Status.SAVE_FAILED
                || candidate == Status.INVALID_RESPONSE
                || candidate == Status.RATE_LIMITED
                || candidate == Status.TIMED_OUT;
    }

    public enum Status {
        LOCAL,
        LOCAL_SAVED,
        WAITING_FOR_SERVER,
        SYNCED_EDITABLE,
        SYNCED_READ_ONLY,
        SAVING,
        SAVED,
        PERMISSION_DENIED,
        SAVE_FAILED,
        SERVER_UNSUPPORTED,
        INVALID_RESPONSE,
        RATE_LIMITED,
        TIMED_OUT
    }
}
