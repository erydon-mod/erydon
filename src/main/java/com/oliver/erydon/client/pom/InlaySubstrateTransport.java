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

    static int pushRecord(int record) {
        Integer previous = CURRENT.get();
        if (record < 0 || record > MAX_RECORD) CURRENT.remove();
        else CURRENT.set(record);
        return previous == null ? -1 : previous;
    }

    static void restoreRecord(int record) {
        if (record < 0) CURRENT.remove();
        else CURRENT.set(record);
    }

    /** Called only by the supported Iris encoder after its ordinary 40-byte vertex writes. */
    public static int currentRecord() {
        if (!enabled()) return -1;
        Integer record = CURRENT.get();
        return record == null ? -1 : record;
    }

    public static short encodedRenderType(int record) {
        if (record < 0 || record > MAX_RECORD) throw new IllegalArgumentException("Invalid substrate record");
        return (short) (-2 - record);
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
