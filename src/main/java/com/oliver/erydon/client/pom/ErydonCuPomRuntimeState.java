package com.oliver.erydon.client.pom;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks whether the current Iris shader load can use bounded projected spiral
 * geometry without relying on geometry-only POM tessellation.
 */
public final class ErydonCuPomRuntimeState {
    public enum State {
        PENDING,
        SOURCE_READY,
        EXACT_BOUNDS,
        NO_POM,
        UNSUPPORTED,
        FAILED
    }

    private static volatile State state = State.PENDING;
    private static final AtomicBoolean TERRAIN_UPDATE_SCHEDULED = new AtomicBoolean();

    public static void beginShaderLoad(boolean eligible) {
        setState(eligible ? State.PENDING : State.UNSUPPORTED);
    }

    public static void acceptProgramStatus(String status) {
        setState(afterProgramStatus(state, status));
    }

    /**
     * Demotes a previously compiled adapter while Iris creates a new set of
     * Sodium terrain programs. If compilation or linking throws, the RETURN
     * hook never promotes it again and spiral geometry remains fail-safe.
     */
    public static void beginTerrainProgramCompilation() {
        setState(beforeTerrainCompilation(state));
    }

    /** Called only after Iris has compiled and linked every terrain pass. */
    public static void confirmTerrainProgramsCompiled() {
        setState(afterTerrainCompilation(state));
    }

    public static State state() {
        return state;
    }

    public static boolean requiresGeometryFallback() {
        if (!FabricLoader.getInstance().isModLoaded("iris")) {
            return false;
        }
        try {
            if (!IrisAccess.isShaderPackInUse()) {
                return false;
            }
        } catch (LinkageError | RuntimeException unavailableIrisApi) {
            return true;
        }
        State current = state;
        return current != State.EXACT_BOUNDS && current != State.NO_POM;
    }

    static State afterProgramStatus(State current, String status) {
        if (current == State.FAILED || current == State.UNSUPPORTED) {
            return current;
        }
        if ("ANCHOR_MISMATCH_NO_CHANGE".equals(status)
                || "POSTCONDITION_FAILED_NO_CHANGE".equals(status)
                || "INCOMPLETE_TRANSFORM_NO_CHANGE".equals(status)) {
            return State.FAILED;
        }
        if ("TRANSFORMED".equals(status) || "ALREADY_TRANSFORMED".equals(status)) {
            return State.SOURCE_READY;
        }
        if ("POM_NOT_COMPILED".equals(status) && current == State.PENDING) {
            return State.NO_POM;
        }
        return current;
    }

    static State beforeTerrainCompilation(State current) {
        return current == State.EXACT_BOUNDS ? State.SOURCE_READY : current;
    }

    static State afterTerrainCompilation(State current) {
        return current == State.SOURCE_READY ? State.EXACT_BOUNDS : current;
    }

    private static void setState(State next) {
        State previous = state;
        state = next;
        if (previous != next) {
            scheduleTerrainUpdate();
        }
    }

    private static void scheduleTerrainUpdate() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || !TERRAIN_UPDATE_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        client.execute(() -> {
            TERRAIN_UPDATE_SCHEDULED.set(false);
            if (client.world != null) {
                client.worldRenderer.scheduleTerrainUpdate();
            }
        });
    }

    private static final class IrisAccess {
        private static boolean isShaderPackInUse() {
            return net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse();
        }
    }

    private ErydonCuPomRuntimeState() {
    }
}
