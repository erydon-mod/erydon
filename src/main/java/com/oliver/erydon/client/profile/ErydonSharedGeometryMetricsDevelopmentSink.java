package com.oliver.erydon.client.profile;

import net.minecraft.util.Identifier;

import java.util.Map;

/** Development-only bridge; excluded from production JARs. */
final class ErydonSharedGeometryMetricsDevelopmentSink implements ErydonSharedGeometryMetrics.Sink {
    ErydonSharedGeometryMetricsDevelopmentSink() {
    }

    @Override
    public boolean enabled() {
        return ErydonSharedGeometryMetricsProvider.isEnabled();
    }

    @Override
    public void beginReload(String configuredMode) {
        ErydonSharedGeometryMetricsProvider.beginReload(configuredMode);
    }

    @Override
    public void resourceOpened(Identifier resourceId,
                               String resourcePack,
                               long durationNanos,
                               boolean success,
                               boolean gothicColumn) {
        ErydonSharedGeometryMetricsProvider.resourceOpened(
                resourceId, resourcePack, durationNanos, success, gothicColumn);
    }

    @Override
    public void modelParsed(Identifier resourceId,
                            long durationNanos,
                            boolean success,
                            boolean gothicColumn) {
        ErydonSharedGeometryMetricsProvider.modelParsed(
                resourceId, durationNanos, success, gothicColumn);
    }

    @Override
    public void baselineGeometryCreated(Object backing,
                                        String geometryKey,
                                        String modelIdentifier,
                                        String materialBinding,
                                        int surfaceCount,
                                        long durationNanos) {
        ErydonSharedGeometryMetricsProvider.baselineGeometryCreated(
                backing, geometryKey, modelIdentifier, materialBinding, surfaceCount, durationNanos);
    }

    @Override
    public void sharedGeometryCreated(Object backing,
                                      String geometryKey,
                                      int surfaceCount,
                                      long durationNanos) {
        ErydonSharedGeometryMetricsProvider.sharedGeometryCreated(
                backing, geometryKey, surfaceCount, durationNanos);
    }

    @Override
    public void sharedCompatibilityPayloadCreated(int surfaceCount) {
        ErydonSharedGeometryMetricsProvider.sharedCompatibilityPayloadCreated(surfaceCount);
    }

    @Override
    public void materialModelBaked(Object geometryBacking,
                                   String geometryKey,
                                   String modelIdentifier,
                                   String materialBinding,
                                   int surfaceCount,
                                   long durationNanos) {
        ErydonSharedGeometryMetricsProvider.materialModelBaked(
                geometryBacking, geometryKey, modelIdentifier, materialBinding, surfaceCount, durationNanos);
    }

    @Override
    public void geometryCacheLookup(boolean hit,
                                    String geometryKey,
                                    String modelIdentifier,
                                    Object backing) {
        ErydonSharedGeometryMetricsProvider.geometryCacheLookup(
                hit, geometryKey, modelIdentifier, backing);
    }

    @Override
    public void structuralOverrideFallback(String modelIdentifier) {
        ErydonSharedGeometryMetricsProvider.structuralOverrideFallback(modelIdentifier);
    }

    @Override
    public void axiomFallbackCreated(String geometryKey,
                                     int surfaceCount,
                                     long durationNanos) {
        ErydonSharedGeometryMetricsProvider.axiomFallbackCreated(
                geometryKey, surfaceCount, durationNanos);
    }

    @Override
    public void blockEmitted(int surfaceCount) {
        ErydonSharedGeometryMetricsProvider.blockEmitted(surfaceCount);
    }

    @Override
    public Map<String, Object> snapshot() {
        return ErydonSharedGeometryMetricsProvider.snapshot();
    }
}
