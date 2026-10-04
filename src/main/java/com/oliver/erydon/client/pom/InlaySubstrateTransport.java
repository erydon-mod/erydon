package com.oliver.erydon.client.pom;

import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Carries an existing CTM substrate record through Iris's unused block render-type short. */
public final class InlaySubstrateTransport {
    public static final int MAX_RECORD = ErydonCuPomLookupLayout.MAX_RECORDS - 1;
    /** Upper half of the signed payload marks UV-rotated perimeter ribbons. */
    public static final int RIBBON_FLAG = 1 << 14;
    // Pre-box once: synchronous per-quad transport must not allocate a payload.
    private static final Integer[] PAYLOADS = payloads();
    private static final Integer NO_PAYLOAD = -1;
    private static final ThreadLocal<Integer> CURRENT = new ThreadLocal<>();
    private static volatile Map<Identifier, Integer> records = Map.of();
    private static volatile boolean sourceSupported;

    private InlaySubstrateTransport() { }

    /** The shader loader resets this before preflight and enables it only for the matching shader path. */
    public static void setSourceSupported(boolean supported) { sourceSupported = supported; }

    static void clearRecords() { records = Map.of(); }

    static void installRecords(List<ErydonCuPomFamilyDiscovery.Family> families) {
        Map<Identifier, Integer> next = new LinkedHashMap<>();
        int index = 0;
        for (var family : families) {
            for (Identifier sprite : family.phases()) {
                if (index > MAX_RECORD || next.putIfAbsent(sprite, index) != null) {
                    throw new IllegalArgumentException("Invalid inlay substrate CTM record ordering");
                }
                index++;
            }
        }
        records = Map.copyOf(next);
    }

    public static int recordForSprite(Identifier sprite) { return records.getOrDefault(sprite, -1); }

    public static boolean supportsContext(RenderContext context) {
        // This emitter calls renderQuad -> bufferQuad -> the vertex encoder synchronously.
        // A mesh-building/caching context must never capture this transient payload.
        return enabled() && context != null && context.getClass().getName().equals(
                "link.infra.indium.renderer.render.TerrainRenderContext");
    }

    public static boolean enabled() {
        return sourceSupported && ErydonCuPomRuntimeState.state() == ErydonCuPomRuntimeState.State.EXACT_BOUNDS
                && RendererSupport.SUPPORTED;
    }

    public static void emit(QuadEmitter emitter, int record) {
        int previous = pushRecord(enabled() ? record : -1);
        try {
            emitter.emit();
        } finally {
            restoreRecord(previous);
        }
    }

    /** Ribbon albedo UVs differ from the substrate's world-cell projection. */
    public static void emitRibbon(QuadEmitter emitter, int record) {
        int payload = enabled() && record >= 0 && record < MAX_RECORD ? record | RIBBON_FLAG : -1;
        int previous = pushPayload(payload);
        try {
            emitter.emit();
        } finally {
            restoreRecord(previous);
        }
    }

    static int pushRecord(int record) {
        return pushPayload(record >= 0 && record <= MAX_RECORD ? record : -1);
    }

    private static int pushPayload(int record) {
        Integer previous = CURRENT.get();
        if (record < 0) CURRENT.set(NO_PAYLOAD);
        else CURRENT.set(PAYLOADS[record]);
        return previous == null ? -1 : previous;
    }

    static void restoreRecord(int record) {
        if (record < 0) CURRENT.set(NO_PAYLOAD);
        else CURRENT.set(PAYLOADS[record]);
    }

    /** Called only by the supported Iris encoder after its ordinary 40-byte vertex writes. */
    public static int currentRecord() {
        if (!enabled()) return -1;
        Integer record = CURRENT.get();
        return record == null || record < 0 ? -1 : record & (RIBBON_FLAG - 1);
    }

    public static boolean currentRibbon() {
        Integer record = enabled() ? CURRENT.get() : null;
        return record != null && record >= 0 && (record & RIBBON_FLAG) != 0;
    }

    public static short encodedRenderType(int record) {
        if (record < 0 || record > MAX_RECORD) throw new IllegalArgumentException("Invalid substrate record");
        return (short) (-2 - record);
    }

    public static short encodedRenderType(int record, boolean ribbon) {
        if (!ribbon) return encodedRenderType(record);
        // -32768 is valid; the final possible base record has no ribbon encoding.
        if (record < 0 || record >= MAX_RECORD) throw new IllegalArgumentException("Invalid ribbon substrate record");
        return (short) (-2 - (record | RIBBON_FLAG));
    }

    private static Integer[] payloads() {
        Integer[] values = new Integer[2 * RIBBON_FLAG - 1];
        for (int index = 0; index < values.length; index++) values[index] = index;
        return values;
    }

    static boolean supportedVersions(String iris, String sodium, String indium) {
        return "1.7.6+mc1.20.1".equals(iris) && "0.5.13+mc1.20.1".equals(sodium)
                && "1.0.36+mc1.20.1".equals(indium);
    }

    public static boolean rendererSupported() { return RendererSupport.SUPPORTED; }

    private static final class RendererSupport {
        private static final boolean SUPPORTED = check();
        private static boolean check() {
            try {
                var loader = FabricLoader.getInstance();
                return supportedVersions(version(loader, "iris"), version(loader, "sodium"), version(loader, "indium"))
                        && InlaySubstrateVertexFormat.supported();
            } catch (RuntimeException | LinkageError unavailable) {
                return false;
            }
        }
        private static String version(FabricLoader loader, String id) {
            return loader.getModContainer(id).map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("");
        }
    }
}
