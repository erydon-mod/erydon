package com.oliver.erydon.recorder;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Queue;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

/** Additional, allocation-free terrain checks for the locally supported Sodium renderer. */
public final class TerrainGuard {
    private static final String UNSUPPORTED = "Unsupported Sodium version; terrain stability cannot be verified";
    private static final String CONTRACT_FAILED = "Sodium terrain inspection contract unavailable";
    private static final String NOT_READY = "Waiting for Sodium terrain renderer";
    private static final String CHANGED = "Visible terrain section count changed";
    private static final Access ACCESS = access();
    private static boolean accessFailed;
    private static int previousVisible = -1;

    private TerrainGuard() {}

    /** Called only on the render thread; vanilla eligibility checks remain the caller's responsibility. */
    public static String unavailableReason(MinecraftClient client) {
        if (!ACCESS.installed) return null;
        if (ACCESS.failure != null) return ACCESS.failure;
        if (accessFailed) return CONTRACT_FAILED;
        try {
            Object renderer = (Object) ACCESS.instance.invokeExact();
            if (renderer == null || client.world == null) {
                previousVisible = -1;
                return NOT_READY;
            }
            Object manager = (Object) ACCESS.manager.invokeExact(renderer);
            if (manager == null) return NOT_READY;
            Object builder = (Object) ACCESS.builder.invokeExact(manager);
            if (builder == null) return NOT_READY;

            if ((int) ACCESS.scheduled.invokeExact(builder) != 0)
                return "Waiting for queued terrain builds";
            if ((int) ACCESS.busy.invokeExact(builder) != 0)
                return "Waiting for active terrain builders";
            Object results = (Object) ACCESS.results.invokeExact(manager);
            if (!(results instanceof Queue<?> resultQueue)) return failed();
            if (!resultQueue.isEmpty()) return "Waiting for terrain uploads";

            Object pending = (Object) ACCESS.rebuilds.invokeExact(manager);
            if (!(pending instanceof Map<?, ?> rebuilds)) return failed();
            for (Object updateType : ACCESS.updateTypes) {
                Object queue = rebuilds.get(updateType);
                if (!(queue instanceof Queue<?> buildQueue)) return failed();
                if (!buildQueue.isEmpty()) return "Waiting for scheduled terrain rebuilds";
            }
            if ((boolean) ACCESS.needsUpdate.invokeExact(manager))
                return "Waiting for terrain visibility update";

            int visible = (int) ACCESS.visible.invokeExact(renderer);
            boolean changed = previousVisible >= 0 && visible != previousVisible;
            previousVisible = visible;
            return changed ? CHANGED : null;
        } catch (Throwable unavailable) {
            if (unavailable instanceof VirtualMachineError fatal) throw fatal;
            if (unavailable instanceof ThreadDeath stopped) throw stopped;
            return failed();
        }
    }

    private static String failed() {
        accessFailed = true;
        return CONTRACT_FAILED;
    }

    private static Access access() {
        var loader = FabricLoader.getInstance();
        var sodium = loader.getModContainer("sodium");
        if (sodium.isEmpty()) return new Access(false, null);
        if (!"0.5.13+mc1.20.1".equals(sodium.get().getMetadata().getVersion().getFriendlyString()))
            return new Access(true, UNSUPPORTED);
        try {
            ClassLoader classes = TerrainGuard.class.getClassLoader();
            Class<?> renderer = Class.forName("me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer", false, classes);
            Class<?> manager = Class.forName("me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager", false, classes);
            Class<?> builder = Class.forName("me.jellysquid.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder", false, classes);
            Class<?> updateType = Class.forName("me.jellysquid.mods.sodium.client.render.chunk.ChunkUpdateType", false, classes);
            Object[] updates = updateType.getEnumConstants();
            if (updates == null || updates.length != 3) return new Access(true, CONTRACT_FAILED);
            return new Access(
                    MethodHandles.publicLookup().unreflect(renderer.getMethod("instanceNullable"))
                            .asType(MethodType.methodType(Object.class)),
                    getter(renderer, "renderSectionManager"),
                    method(manager, "getBuilder", Object.class),
                    method(builder, "getScheduledJobCount", int.class),
                    method(builder, "getBusyThreadCount", int.class),
                    getter(manager, "buildResults"), getter(manager, "rebuildLists"),
                    method(manager, "needsUpdate", boolean.class),
                    method(renderer, "getVisibleChunkCount", int.class), updates);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return new Access(true, CONTRACT_FAILED);
        }
    }

    private static MethodHandle getter(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return MethodHandles.lookup().unreflectGetter(field)
                .asType(MethodType.methodType(Object.class, Object.class));
    }

    private static MethodHandle method(Class<?> type, String name, Class<?> result)
            throws ReflectiveOperationException {
        return MethodHandles.publicLookup().unreflect(type.getMethod(name))
                .asType(MethodType.methodType(result, Object.class));
    }

    private static final class Access {
        final boolean installed;
        final String failure;
        final MethodHandle instance, manager, builder, scheduled, busy, results, rebuilds, needsUpdate, visible;
        final Object[] updateTypes;

        Access(boolean installed, String failure) {
            this.installed = installed;
            this.failure = failure;
            instance = manager = builder = scheduled = busy = results = rebuilds = needsUpdate = visible = null;
            updateTypes = null;
        }

        Access(MethodHandle instance, MethodHandle manager, MethodHandle builder,
               MethodHandle scheduled, MethodHandle busy, MethodHandle results, MethodHandle rebuilds,
               MethodHandle needsUpdate, MethodHandle visible, Object[] updateTypes) {
            installed = true;
            failure = null;
            this.instance = instance;
            this.manager = manager;
            this.builder = builder;
            this.scheduled = scheduled;
            this.busy = busy;
            this.results = results;
            this.rebuilds = rebuilds;
            this.needsUpdate = needsUpdate;
            this.visible = visible;
            this.updateTypes = updateTypes;
        }
    }
}
