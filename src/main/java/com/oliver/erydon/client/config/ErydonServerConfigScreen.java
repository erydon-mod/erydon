package com.oliver.erydon.client.config;

import com.oliver.erydon.ErydonConfig;
import com.oliver.erydon.client.ErydonConfigNetworkingClient;
import com.oliver.erydon.network.ErydonConfigNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Shared save, permission, and status behaviour for server-owned settings. */
abstract class ErydonServerConfigScreen extends Screen {
    protected final Screen parent;

    private boolean saving;
    private boolean controlsEditable;
    private boolean initialSyncStarted;
    private boolean awaitingInitialSync;
    private int initialSyncNonce;
    private ErydonConfig.ServerSnapshot baselineSnapshot;

    protected ErydonServerConfigScreen(Screen parent, Text title) {
        super(title);
        this.parent = parent;
    }

    protected final void finishServerConfigInit() {
        if (!initialSyncStarted) {
            initialSyncStarted = true;
            initialSyncNonce = ErydonConfigNetworkingClient.requestServerSettings();
            awaitingInitialSync = initialSyncNonce > 0;
            if (!awaitingInitialSync) {
                installSnapshot(ErydonConfigNetworkingClient.settings());
            }
        }
        refreshControlState(true);
    }

    protected final void submitServerSettings(ErydonConfig.ServerSnapshot snapshot, int allowedFields) {
        if (saving || !ErydonConfigNetworkingClient.canEdit()) {
            refreshControlState(true);
            return;
        }

        int changedFields = baselineSnapshot == null
                ? allowedFields
                : ErydonConfigNetworking.changedFields(baselineSnapshot, snapshot) & allowedFields;
        if (changedFields == 0) {
            close();
            return;
        }

        if (!ErydonConfigNetworkingClient.save(snapshot, changedFields)) {
            saving = false;
            refreshControlState(true);
            return;
        }

        ErydonConfigNetworkingClient.Status status = ErydonConfigNetworkingClient.status();
        if (status == ErydonConfigNetworkingClient.Status.LOCAL_SAVED
                || status == ErydonConfigNetworkingClient.Status.SAVED) {
            close();
            return;
        }

        saving = status == ErydonConfigNetworkingClient.Status.SAVING;
        refreshControlState(true);
    }

    @Override
    public void tick() {
        super.tick();
        ErydonConfigNetworkingClient.Status status = ErydonConfigNetworkingClient.status();
        if (awaitingInitialSync
                && ErydonConfigNetworkingClient.lastCompletedSyncNonce() == initialSyncNonce) {
            awaitingInitialSync = false;
            installSnapshot(ErydonConfigNetworkingClient.settings());
        }
        if (saving && (status == ErydonConfigNetworkingClient.Status.SAVED
                || status == ErydonConfigNetworkingClient.Status.LOCAL_SAVED)) {
            saving = false;
            close();
            return;
        }
        if (saving && isSaveFailure(status)) {
            saving = false;
            refreshControlState(true);
            return;
        }
        refreshControlState(false);
    }

    @Override
    public void close() {
        if (!saving && client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public Text getNarratedTitle() {
        return Text.empty()
                .append(super.getNarratedTitle())
                .append(". ")
                .append(serverStatusText());
    }

    protected final void drawServerStatus(DrawContext context, int x, int y, int width) {
        ErydonConfigUi.drawStatusStrip(context, serverStatusText(), x, y, width, serverStatusTone());
    }

    protected final boolean serverControlsEditable() {
        return controlsEditable;
    }

    protected final boolean serverSaveInProgress() {
        return saving;
    }

    protected abstract void setServerControlsActive(boolean active);

    protected abstract void applyServerSnapshot(ErydonConfig.ServerSnapshot snapshot);

    private void refreshControlState(boolean force) {
        boolean editable = !saving && !awaitingInitialSync && ErydonConfigNetworkingClient.canEdit();
        if (force || editable != controlsEditable) {
            controlsEditable = editable;
            setServerControlsActive(editable);
        }
    }

    private static boolean isSaveFailure(ErydonConfigNetworkingClient.Status status) {
        return status == ErydonConfigNetworkingClient.Status.LOCAL
                || status == ErydonConfigNetworkingClient.Status.PERMISSION_DENIED
                || status == ErydonConfigNetworkingClient.Status.SAVE_FAILED
                || status == ErydonConfigNetworkingClient.Status.SERVER_UNSUPPORTED
                || status == ErydonConfigNetworkingClient.Status.INVALID_RESPONSE
                || status == ErydonConfigNetworkingClient.Status.RATE_LIMITED
                || status == ErydonConfigNetworkingClient.Status.TIMED_OUT;
    }

    private void installSnapshot(ErydonConfig.ServerSnapshot snapshot) {
        baselineSnapshot = snapshot;
        applyServerSnapshot(snapshot);
    }

    private static Text serverStatusText() {
        String suffix = switch (ErydonConfigNetworkingClient.status()) {
            case LOCAL -> "local";
            case LOCAL_SAVED -> "local_saved";
            case WAITING_FOR_SERVER -> "waiting_for_server";
            case SYNCED_EDITABLE -> "synced_editable";
            case SYNCED_READ_ONLY -> "synced_read_only";
            case SAVING -> "saving";
            case SAVED -> "saved";
            case PERMISSION_DENIED -> "permission_denied";
            case SAVE_FAILED -> "save_failed";
            case SERVER_UNSUPPORTED -> "server_unsupported";
            case INVALID_RESPONSE -> "invalid_response";
            case RATE_LIMITED -> "rate_limited";
            case TIMED_OUT -> "timed_out";
        };
        return Text.translatable("message.erydon.config.status." + suffix);
    }

    private static ErydonConfigUi.StatusTone serverStatusTone() {
        return switch (ErydonConfigNetworkingClient.status()) {
            case LOCAL_SAVED, SYNCED_EDITABLE, SAVED -> ErydonConfigUi.StatusTone.SUCCESS;
            case WAITING_FOR_SERVER, SAVING -> ErydonConfigUi.StatusTone.WARNING;
            case PERMISSION_DENIED, SAVE_FAILED, SERVER_UNSUPPORTED, INVALID_RESPONSE, RATE_LIMITED, TIMED_OUT -> ErydonConfigUi.StatusTone.ERROR;
            default -> ErydonConfigUi.StatusTone.NEUTRAL;
        };
    }
}
