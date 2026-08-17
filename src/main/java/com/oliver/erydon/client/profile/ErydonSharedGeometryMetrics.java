package com.oliver.erydon.client.profile;

import com.oliver.erydon.Erydon;
import net.minecraft.util.Identifier;

import java.util.Map;

/**
 * Dormant production facade for the prototype's development-only metrics.
 * The implementation is omitted from release JARs and loaded only when the
 * explicit development property is enabled.
 */
public final class ErydonSharedGeometryMetrics {
    public static final String ENABLED_PROPERTY = "erydon.shared_geometry.metrics";
    public static final String ITERATION_PROPERTY = "erydon.shared_geometry.iteration";
    private static final Sink SINK = createSink();

    private ErydonSharedGeometryMetrics() {
    }

    public static boolean isEnabled() {
        return SINK.enabled();
    }

    public static void beginReload(String configuredMode) {
        SINK.beginReload(configuredMode);
    }

    public static void resourceOpened(Identifier resourceId,
                                      String resourcePack,
                                      long durationNanos,
                                      boolean success,
                                      boolean gothicColumn) {
        SINK.resourceOpened(resourceId, resourcePack, durationNanos, success, gothicColumn);
    }

    public static void modelParsed(Identifier resourceId,
                                   long durationNanos,
                                   boolean success,
                                   boolean gothicColumn) {
        SINK.modelParsed(resourceId, durationNanos, success, gothicColumn);
    }

    public static void baselineGeometryCreated(Object backing,
                                               String geometryKey,
                                               String modelIdentifier,
                                               String materialBinding,
                                               int surfaceCount,
                                               long durationNanos) {
        SINK.baselineGeometryCreated(
                backing, geometryKey, modelIdentifier, materialBinding, surfaceCount, durationNanos);
    }

    public static void sharedGeometryCreated(Object backing,
                                             String geometryKey,
                                             int surfaceCount,
                                             long durationNanos) {
        SINK.sharedGeometryCreated(backing, geometryKey, surfaceCount, durationNanos);
    }

    public static void sharedCompatibilityPayloadCreated(int surfaceCount) {
        SINK.sharedCompatibilityPayloadCreated(surfaceCount);
    }

    public static void materialModelBaked(Object geometryBacking,
                                          String geometryKey,
                                          String modelIdentifier,
                                          String materialBinding,
                                          int surfaceCount,
                                          long durationNanos) {
        SINK.materialModelBaked(
                geometryBacking, geometryKey, modelIdentifier, materialBinding, surfaceCount, durationNanos);
    }

    public static void geometryCacheLookup(boolean hit,
                                           String geometryKey,
                                           String modelIdentifier,
                                           Object backing) {
        SINK.geometryCacheLookup(hit, geometryKey, modelIdentifier, backing);
    }

    public static void structuralOverrideFallback(String modelIdentifier) {
        SINK.structuralOverrideFallback(modelIdentifier);
    }

    public static void axiomFallbackCreated(String geometryKey,
                                            int surfaceCount,
                                            long durationNanos) {
        SINK.axiomFallbackCreated(geometryKey, surfaceCount, durationNanos);
    }

    public static void blockEmitted(int surfaceCount) {
        SINK.blockEmitted(surfaceCount);
    }

    public static Map<String, Object> snapshot() {
        return SINK.snapshot();
    }

    private static Sink createSink() {
        if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
            return NoopSink.INSTANCE;
        }
        try {
            return (Sink) Class.forName(
                            "com.oliver.erydon.client.profile.ErydonSharedGeometryMetricsDevelopmentSink")
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (ReflectiveOperationException | LinkageError exception) {
            Erydon.LOGGER.warn(
                    "[{}] Shared-geometry development metrics were requested but are unavailable.",
                    Erydon.MOD_ID,
                    exception
            );
            return NoopSink.INSTANCE;
        }
    }

    interface Sink {
        default boolean enabled() {
            return false;
        }

        default void beginReload(String configuredMode) {
        }

        default void resourceOpened(Identifier resourceId,
                                    String resourcePack,
                                    long durationNanos,
                                    boolean success,
                                    boolean gothicColumn) {
        }

        default void modelParsed(Identifier resourceId,
                                 long durationNanos,
                                 boolean success,
                                 boolean gothicColumn) {
        }

        default void baselineGeometryCreated(Object backing,
                                             String geometryKey,
                                             String modelIdentifier,
                                             String materialBinding,
                                             int surfaceCount,
                                             long durationNanos) {
        }

        default void sharedGeometryCreated(Object backing,
                                           String geometryKey,
                                           int surfaceCount,
                                           long durationNanos) {
        }

        default void sharedCompatibilityPayloadCreated(int surfaceCount) {
        }

        default void materialModelBaked(Object geometryBacking,
                                        String geometryKey,
                                        String modelIdentifier,
                                        String materialBinding,
                                        int surfaceCount,
                                        long durationNanos) {
        }

        default void geometryCacheLookup(boolean hit,
                                         String geometryKey,
                                         String modelIdentifier,
                                         Object backing) {
        }

        default void structuralOverrideFallback(String modelIdentifier) {
        }

        default void axiomFallbackCreated(String geometryKey,
                                          int surfaceCount,
                                          long durationNanos) {
        }

        default void blockEmitted(int surfaceCount) {
        }

        default Map<String, Object> snapshot() {
            return Map.of("enabled", false);
        }
    }

    private enum NoopSink implements Sink {
        INSTANCE
    }
}
