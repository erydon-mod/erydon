package com.oliver.erydon.client.profile;

import com.google.gson.Gson;
import com.oliver.erydon.Erydon;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in counters for the bounded Gothic-column shared-geometry experiment.
 * The default production path does not emit events or update hot-path counters.
 */
final class ErydonSharedGeometryMetricsProvider {
    public static final String ENABLED_PROPERTY = "erydon.shared_geometry.metrics";
    public static final String ITERATION_PROPERTY = "erydon.shared_geometry.iteration";
    public static final String EVENTS_PROPERTY = "erydon.shared_geometry.metrics.events";
    private static final String EVENT_PREFIX = "ERYDON_GEOMETRY_METRIC ";
    private static final Gson GSON = new Gson();
    private static final boolean ENABLED = Boolean.getBoolean(ENABLED_PROPERTY);
    private static final boolean EVENTS_ENABLED = Boolean.getBoolean(EVENTS_PROPERTY);
    private static final int ITERATION = Integer.getInteger(ITERATION_PROPERTY, 0);
    private static final Object IDENTITY_LOCK = new Object();
    private static final Map<Object, Integer> BACKING_IDENTITIES =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final AtomicInteger NEXT_BACKING_ID = new AtomicInteger();
    private static final AtomicInteger RELOAD_GENERATION = new AtomicInteger();
    private static final AtomicInteger AUTHORING_RESOURCE_OPENS = new AtomicInteger();
    private static final AtomicInteger GOTHIC_AUTHORING_RESOURCE_OPENS = new AtomicInteger();
    private static final AtomicInteger AUTHORING_MODELS_PARSED = new AtomicInteger();
    private static final AtomicInteger GOTHIC_AUTHORING_MODELS_PARSED = new AtomicInteger();
    private static final AtomicInteger MATERIAL_MODELS_BAKED = new AtomicInteger();
    private static final AtomicInteger BASELINE_GEOMETRY_OBJECTS = new AtomicInteger();
    private static final AtomicInteger SHARED_GEOMETRY_OBJECTS = new AtomicInteger();
    private static final AtomicInteger SHARED_GEOMETRY_CACHE_HITS = new AtomicInteger();
    private static final AtomicInteger SHARED_GEOMETRY_CACHE_MISSES = new AtomicInteger();
    private static final AtomicInteger MATERIAL_BINDINGS = new AtomicInteger();
    private static final AtomicInteger STRUCTURAL_OVERRIDE_FALLBACKS = new AtomicInteger();
    private static final AtomicInteger AXIOM_FALLBACK_GEOMETRIES = new AtomicInteger();
    private static final AtomicLong AUTHORING_OPEN_NANOS = new AtomicLong();
    private static final AtomicLong AUTHORING_PARSE_NANOS = new AtomicLong();
    private static final AtomicLong GOTHIC_GEOMETRY_BAKE_NANOS = new AtomicLong();
    private static final AtomicLong MATERIAL_BINDING_NANOS = new AtomicLong();
    private static final AtomicLong BASELINE_VERTEX_PAYLOAD_BYTES = new AtomicLong();
    private static final AtomicLong BASELINE_EQUIVALENT_VERTEX_PAYLOAD_BYTES = new AtomicLong();
    private static final AtomicLong SHARED_VERTEX_PAYLOAD_ESTIMATE_BYTES = new AtomicLong();
    private static final AtomicLong SHARED_COMPATIBILITY_PAYLOAD_BYTES = new AtomicLong();
    private static final AtomicLong BLOCK_EMISSIONS = new AtomicLong();
    private static final AtomicLong EMITTED_SURFACES = new AtomicLong();
    private static final Map<String, AtomicInteger> BAKED_BY_GEOMETRY_KEY = new ConcurrentHashMap<>();
    private static volatile String mode = "baseline";

    private ErydonSharedGeometryMetricsProvider() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void beginReload(String configuredMode) {
        if (!ENABLED) {
            return;
        }

        mode = configuredMode;
        RELOAD_GENERATION.incrementAndGet();
        AUTHORING_RESOURCE_OPENS.set(0);
        GOTHIC_AUTHORING_RESOURCE_OPENS.set(0);
        AUTHORING_MODELS_PARSED.set(0);
        GOTHIC_AUTHORING_MODELS_PARSED.set(0);
        MATERIAL_MODELS_BAKED.set(0);
        BASELINE_GEOMETRY_OBJECTS.set(0);
        SHARED_GEOMETRY_OBJECTS.set(0);
        SHARED_GEOMETRY_CACHE_HITS.set(0);
        SHARED_GEOMETRY_CACHE_MISSES.set(0);
        MATERIAL_BINDINGS.set(0);
        STRUCTURAL_OVERRIDE_FALLBACKS.set(0);
        AXIOM_FALLBACK_GEOMETRIES.set(0);
        AUTHORING_OPEN_NANOS.set(0L);
        AUTHORING_PARSE_NANOS.set(0L);
        GOTHIC_GEOMETRY_BAKE_NANOS.set(0L);
        MATERIAL_BINDING_NANOS.set(0L);
        BASELINE_VERTEX_PAYLOAD_BYTES.set(0L);
        BASELINE_EQUIVALENT_VERTEX_PAYLOAD_BYTES.set(0L);
        SHARED_VERTEX_PAYLOAD_ESTIMATE_BYTES.set(0L);
        SHARED_COMPATIBILITY_PAYLOAD_BYTES.set(0L);
        BLOCK_EMISSIONS.set(0L);
        EMITTED_SURFACES.set(0L);
        BAKED_BY_GEOMETRY_KEY.clear();
        synchronized (IDENTITY_LOCK) {
            BACKING_IDENTITIES.clear();
            NEXT_BACKING_ID.set(0);
        }
    }

    public static void resourceOpened(Identifier resourceId,
                                      String resourcePack,
                                      long durationNanos,
                                      boolean success,
                                      boolean gothicColumn) {
        if (!ENABLED) {
            return;
        }

        if (success) {
            AUTHORING_RESOURCE_OPENS.incrementAndGet();
            AUTHORING_OPEN_NANOS.addAndGet(durationNanos);
            if (gothicColumn) {
                GOTHIC_AUTHORING_RESOURCE_OPENS.incrementAndGet();
            }
        }
        if (!EVENTS_ENABLED) {
            return;
        }
        Map<String, Object> event = baseEvent("model_resource_opened");
        event.put("resource_identifier", resourceId.toString());
        event.put("resource_pack", resourcePack == null ? "unknown" : resourcePack);
        event.put("duration_ns", durationNanos);
        event.put("success", success);
        emit(event);
    }

    public static void modelParsed(Identifier resourceId,
                                   long durationNanos,
                                   boolean success,
                                   boolean gothicColumn) {
        if (!ENABLED) {
            return;
        }

        if (success) {
            AUTHORING_MODELS_PARSED.incrementAndGet();
            AUTHORING_PARSE_NANOS.addAndGet(durationNanos);
            if (gothicColumn) {
                GOTHIC_AUTHORING_MODELS_PARSED.incrementAndGet();
            }
        }
        if (!EVENTS_ENABLED) {
            return;
        }
        Map<String, Object> event = baseEvent("model_parsed");
        event.put("model_identifier", resourceId.toString());
        event.put("parent_identifier", null);
        event.put("duration_ns", durationNanos);
        event.put("success", success);
        emit(event);
    }

    public static void baselineGeometryCreated(Object backing,
                                               String geometryKey,
                                               String modelIdentifier,
                                               String materialBinding,
                                               int surfaceCount,
                                               long durationNanos) {
        if (!ENABLED) {
            return;
        }

        BASELINE_GEOMETRY_OBJECTS.incrementAndGet();
        BASELINE_VERTEX_PAYLOAD_BYTES.addAndGet((long) surfaceCount * 32L * Integer.BYTES);
        GOTHIC_GEOMETRY_BAKE_NANOS.addAndGet(durationNanos);
        recordBakedModel(backing, geometryKey, modelIdentifier, surfaceCount, durationNanos,
                materialBindingHash(materialBinding));
        geometryCreated(backing, geometryKey, surfaceCount,
                (long) surfaceCount * 32L * Integer.BYTES, "exact BakedQuad int payload");
    }

    public static void sharedGeometryCreated(Object backing,
                                             String geometryKey,
                                             int surfaceCount,
                                             long durationNanos) {
        if (!ENABLED) {
            return;
        }

        SHARED_GEOMETRY_OBJECTS.incrementAndGet();
        GOTHIC_GEOMETRY_BAKE_NANOS.addAndGet(durationNanos);
        long estimatedBytes = (long) surfaceCount * 4L * 36L;
        SHARED_VERTEX_PAYLOAD_ESTIMATE_BYTES.addAndGet(estimatedBytes);
        geometryCreated(backing, geometryKey, surfaceCount, estimatedBytes,
                "Fabric mesh estimate at 36 bytes per vertex");
    }

    public static void materialModelBaked(Object geometryBacking,
                                          String geometryKey,
                                          String modelIdentifier,
                                          String materialBinding,
                                          int surfaceCount,
                                          long durationNanos) {
        if (!ENABLED) {
            return;
        }

        MATERIAL_BINDINGS.incrementAndGet();
        MATERIAL_BINDING_NANOS.addAndGet(durationNanos);
        BASELINE_EQUIVALENT_VERTEX_PAYLOAD_BYTES.addAndGet((long) surfaceCount * 32L * Integer.BYTES);
        recordBakedModel(geometryBacking, geometryKey, modelIdentifier, surfaceCount, durationNanos,
                materialBindingHash(materialBinding));
    }

    public static void sharedCompatibilityPayloadCreated(int surfaceCount) {
        if (ENABLED) {
            SHARED_COMPATIBILITY_PAYLOAD_BYTES.addAndGet((long) surfaceCount * 32L * Integer.BYTES);
        }
    }

    public static void geometryCacheLookup(boolean hit,
                                           String geometryKey,
                                           String modelIdentifier,
                                           Object backing) {
        if (!ENABLED) {
            return;
        }

        if (hit) {
            SHARED_GEOMETRY_CACHE_HITS.incrementAndGet();
        } else {
            SHARED_GEOMETRY_CACHE_MISSES.incrementAndGet();
        }
        if (!EVENTS_ENABLED) {
            return;
        }
        Map<String, Object> event = baseEvent(hit ? "geometry_cache_hit" : "geometry_cache_miss");
        event.put("metric", hit ? "geometry_cache_hits" : "geometry_cache_misses");
        event.put("value", 1);
        event.put("unit", "count");
        event.put("geometry_key", geometryKey);
        event.put("model_identifier", modelIdentifier);
        if (backing != null) {
            event.put("cache_backing_object_identifier", backingIdentifier(backing));
        }
        emit(event);
    }

    public static void structuralOverrideFallback(String modelIdentifier) {
        if (!ENABLED) {
            return;
        }
        STRUCTURAL_OVERRIDE_FALLBACKS.incrementAndGet();
        if (!EVENTS_ENABLED) {
            return;
        }
        Map<String, Object> event = baseEvent("structural_override_fallback");
        event.put("model_identifier", modelIdentifier);
        emit(event);
    }

    public static void axiomFallbackCreated(String geometryKey, int surfaceCount, long durationNanos) {
        if (!ENABLED) {
            return;
        }
        AXIOM_FALLBACK_GEOMETRIES.incrementAndGet();
        if (!EVENTS_ENABLED) {
            return;
        }
        Map<String, Object> event = baseEvent("axiom_fallback_geometry_created");
        event.put("geometry_key", geometryKey);
        event.put("surface_count", surfaceCount);
        event.put("duration_ns", durationNanos);
        emit(event);
    }

    public static void blockEmitted(int surfaceCount) {
        if (!ENABLED) {
            return;
        }
        BLOCK_EMISSIONS.incrementAndGet();
        EMITTED_SURFACES.addAndGet(surfaceCount);
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("enabled", ENABLED);
        snapshot.put("mode", mode);
        snapshot.put("iteration", ITERATION);
        snapshot.put("reloadGeneration", RELOAD_GENERATION.get());
        snapshot.put("authoringResourceOpens", AUTHORING_RESOURCE_OPENS.get());
        snapshot.put("gothicAuthoringResourceOpens", GOTHIC_AUTHORING_RESOURCE_OPENS.get());
        snapshot.put("authoringModelsParsed", AUTHORING_MODELS_PARSED.get());
        snapshot.put("gothicAuthoringModelsParsed", GOTHIC_AUTHORING_MODELS_PARSED.get());
        snapshot.put("materialModelsBaked", MATERIAL_MODELS_BAKED.get());
        snapshot.put("baselineGeometryObjects", BASELINE_GEOMETRY_OBJECTS.get());
        snapshot.put("sharedGeometryObjects", SHARED_GEOMETRY_OBJECTS.get());
        snapshot.put("uniqueGeometryBackingObjects", uniqueBackingObjects());
        snapshot.put("sharedGeometryCacheHits", SHARED_GEOMETRY_CACHE_HITS.get());
        snapshot.put("sharedGeometryCacheMisses", SHARED_GEOMETRY_CACHE_MISSES.get());
        snapshot.put("materialBindings", MATERIAL_BINDINGS.get());
        snapshot.put("structuralOverrideFallbacks", STRUCTURAL_OVERRIDE_FALLBACKS.get());
        snapshot.put("axiomFallbackGeometries", AXIOM_FALLBACK_GEOMETRIES.get());
        snapshot.put("authoringOpenNanos", AUTHORING_OPEN_NANOS.get());
        snapshot.put("authoringParseNanos", AUTHORING_PARSE_NANOS.get());
        snapshot.put("gothicGeometryBakeNanos", GOTHIC_GEOMETRY_BAKE_NANOS.get());
        snapshot.put("materialBindingNanos", MATERIAL_BINDING_NANOS.get());
        snapshot.put("gothicBakeNanos",
                GOTHIC_GEOMETRY_BAKE_NANOS.get() + MATERIAL_BINDING_NANOS.get());
        snapshot.put("baselineVertexPayloadBytes", BASELINE_VERTEX_PAYLOAD_BYTES.get());
        snapshot.put("baselineEquivalentVertexPayloadBytes", BASELINE_EQUIVALENT_VERTEX_PAYLOAD_BYTES.get());
        snapshot.put("sharedVertexPayloadEstimateBytes", SHARED_VERTEX_PAYLOAD_ESTIMATE_BYTES.get());
        snapshot.put("sharedCompatibilityPayloadBytes", SHARED_COMPATIBILITY_PAYLOAD_BYTES.get());
        snapshot.put("blockEmissions", BLOCK_EMISSIONS.get());
        snapshot.put("emittedSurfaces", EMITTED_SURFACES.get());
        snapshot.put("bakedByGeometryKey", countSnapshot());
        Runtime runtime = Runtime.getRuntime();
        snapshot.put("usedHeapBytes", runtime.totalMemory() - runtime.freeMemory());
        snapshot.put("usedHeapMeasurement", "JVM used heap at report write; not retained-size proof");
        return snapshot;
    }

    private static void recordBakedModel(Object bakedModel,
                                         String geometryKey,
                                         String modelIdentifier,
                                         int surfaceCount,
                                         long durationNanos,
                                         String materialBindingHash) {
        MATERIAL_MODELS_BAKED.incrementAndGet();
        BAKED_BY_GEOMETRY_KEY.computeIfAbsent(geometryKey, ignored -> new AtomicInteger()).incrementAndGet();
        backingIdentifier(bakedModel);
        if (!EVENTS_ENABLED) {
            return;
        }
        Map<String, Object> event = baseEvent("model_baked");
        event.put("model_identifier", modelIdentifier);
        event.put("geometry_key", geometryKey);
        event.put("material_binding_hash", materialBindingHash);
        event.put("surface_count", surfaceCount);
        event.put("vertex_count", surfaceCount * 4L);
        event.put("duration_ns", durationNanos);
        event.put("cache_backing_object_identifier", backingIdentifier(bakedModel));
        emit(event);
    }

    private static void geometryCreated(Object backing,
                                        String geometryKey,
                                        int surfaceCount,
                                        long estimatedBytes,
                                        String estimateKind) {
        backingIdentifier(backing);
        if (!EVENTS_ENABLED) {
            return;
        }
        Map<String, Object> event = baseEvent("geometry_object_created");
        event.put("geometry_key", geometryKey);
        event.put("cache_backing_object_identifier", backingIdentifier(backing));
        event.put("surface_count", surfaceCount);
        event.put("vertex_count", surfaceCount * 4L);
        event.put("estimated_bytes", estimatedBytes);
        event.put("estimate_kind", estimateKind);
        emit(event);
    }

    private static Map<String, Object> baseEvent(String eventName) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schema_version", 1);
        event.put("event", eventName);
        if (ITERATION > 0) {
            event.put("iteration", ITERATION);
        }
        event.put("phase", mode);
        event.put("context", "gothic_column_pilot");
        return event;
    }

    private static void emit(Map<String, Object> event) {
        synchronized (System.out) {
            System.out.println(EVENT_PREFIX + GSON.toJson(event));
        }
    }

    private static String backingIdentifier(Object object) {
        synchronized (IDENTITY_LOCK) {
            int id = BACKING_IDENTITIES.computeIfAbsent(object,
                    ignored -> NEXT_BACKING_ID.incrementAndGet());
            return "within-run-object-" + id;
        }
    }

    private static int uniqueBackingObjects() {
        synchronized (IDENTITY_LOCK) {
            return BACKING_IDENTITIES.size();
        }
    }

    private static Map<String, Integer> countSnapshot() {
        Map<String, Integer> result = new TreeMap<>();
        BAKED_BY_GEOMETRY_KEY.forEach((key, value) -> result.put(key, value.get()));
        return result;
    }

    private static String materialBindingHash(String value) {
        if (!EVENTS_ENABLED) {
            return "";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            Erydon.LOGGER.warn("[{}] SHA-256 unavailable for shared-geometry metrics.", Erydon.MOD_ID, exception);
            return "unavailable";
        }
    }
}
