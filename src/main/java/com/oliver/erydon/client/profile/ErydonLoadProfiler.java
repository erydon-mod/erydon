package com.oliver.erydon.client.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oliver.erydon.Erydon;
import com.oliver.erydon.ErydonConfig;
import com.oliver.erydon.client.model.DynamicBakedModelCache;
import com.oliver.erydon.client.model.ErydonSlopeModelClassifier;
import com.oliver.erydon.client.model.LayerVerticalBakedModel;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourceType;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ErydonLoadProfiler {
    private static final Identifier RELOAD_LISTENER_ID = new Identifier(Erydon.MOD_ID, "load_profile");
    private static final String RUNTIME_REPORT_PATH_KEY = "erydon.model_performance.runtime_report";
    private static final String INVENTORY_VARIANT = "inventory";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final boolean ENABLED = ErydonConfig.debugLoadProfile();
    private static final DynamicBakedModelCache.WrapObserver NOOP_WRAP_OBSERVER = new DynamicBakedModelCache.WrapObserver() {
        @Override
        public void created() {
        }

        @Override
        public void reused() {
        }
    };
    private static final ScheduledExecutorService SUMMARY_EXECUTOR = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "erydon-load-profile");
        thread.setDaemon(true);
        return thread;
    });
    private static final EnumMap<WrapBucket, AtomicInteger> CREATED_BY_BUCKET = countersByBucket();
    private static final EnumMap<WrapBucket, AtomicInteger> REUSED_BY_BUCKET = countersByBucket();
    private static final AtomicInteger OLD_SLOPE_PARENT_MODELS_BAKED = new AtomicInteger();
    private static final AtomicInteger CONTINUITY_SKIP_REQUESTED = new AtomicInteger();
    private static final AtomicInteger CONTINUITY_SKIP_APPLIED = new AtomicInteger();
    private static final AtomicInteger SUMMARY_GENERATION = new AtomicInteger();
    private static final Map<String, AtomicInteger> CONTINUITY_SKIP_REASONS = new ConcurrentHashMap<>();
    private static final Map<String, FamilyRuntimeCounters> CUSTOM_FAMILY_COUNTERS = new ConcurrentHashMap<>();
    private static final AtomicInteger CUSTOM_MODELS_CREATED = new AtomicInteger();
    private static final AtomicInteger CUSTOM_MODEL_CACHE_HITS = new AtomicInteger();
    private static final AtomicInteger CUSTOM_MODEL_CACHE_MISSES = new AtomicInteger();
    private static final AtomicInteger CUSTOM_MODEL_INVENTORY_CREATIONS = new AtomicInteger();
    private static final AtomicLong CUSTOM_MODEL_WRAP_NANOS = new AtomicLong();
    private static final AtomicLong RESOURCE_SCAN_NANOS = new AtomicLong();
    private static final AtomicInteger FINAL_MODELS_INSPECTED = new AtomicInteger();
    private static final AtomicInteger FINAL_ERYDON_NON_SLOPE_MODELS = new AtomicInteger();
    private static final AtomicInteger FINAL_ERYDON_NON_SLOPE_WITH_CONTINUITY = new AtomicInteger();
    private static final AtomicInteger FINAL_ERYDON_NON_SLOPE_WITHOUT_CONTINUITY = new AtomicInteger();
    private static final AtomicInteger FINAL_ERYDON_NON_SLOPE_OUTER_CONTINUITY = new AtomicInteger();
    private static final AtomicInteger FINAL_ERYDON_NON_SLOPE_INNER_CONTINUITY = new AtomicInteger();
    private static final AtomicInteger FINAL_ERYDON_SLOPE_MODELS = new AtomicInteger();
    private static final AtomicInteger FINAL_ERYDON_SLOPE_WITH_CONTINUITY = new AtomicInteger();
    private static final AtomicInteger FINAL_FOREIGN_MODELS_WITH_ERYDON_WRAPPERS = new AtomicInteger();
    private static final Map<String, String> FINAL_MODEL_CHAIN_SAMPLES = new ConcurrentHashMap<>();
    private static final Map<String, String> TRACKED_FINAL_MODEL_CHAINS = new ConcurrentHashMap<>();
    private static final AtomicInteger CONTINUITY_TRANSFORM_CALLS = new AtomicInteger();
    private static volatile ResourceSnapshot lastResourceSnapshot;

    private ErydonLoadProfiler() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void registerClientReloadListener() {
        if (!enabled()) {
            return;
        }

        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public Identifier getFabricId() {
                return RELOAD_LISTENER_ID;
            }

            @Override
            public void reload(ResourceManager manager) {
                if (!enabled()) {
                    return;
                }

                long startedNanos = System.nanoTime();
                ResourceSnapshot snapshot = scanResources(manager);
                lastResourceSnapshot = snapshot;
                long elapsedNanos = System.nanoTime() - startedNanos;
                RESOURCE_SCAN_NANOS.set(elapsedNanos);
                Erydon.LOGGER.info("[erydon/prof] active_resource_packs={}", snapshot.activeResourcePacks());
                Erydon.LOGGER.info("[erydon/prof] slope_item_models_total={}; slope_item_models_old_parent_refs={}; slope_item_models_internal_wrapped_parent_refs={}; slope_item_models_no_parent_or_unexpected_parent={}",
                        snapshot.slopeItemModelsTotal(),
                        snapshot.slopeItemModelsOldParentRefs(),
                        snapshot.slopeItemModelsInternalWrappedParentRefs(),
                        snapshot.slopeItemModelsUnexpectedParentRefs());
                Erydon.LOGGER.info("[erydon/prof] slope_blockstates_total={}; old_slope_parent_models_seen={}; internal_wrapped_models_seen={}",
                        snapshot.slopeBlockstatesTotal(),
                        snapshot.oldSlopeParentModelsSeen(),
                        snapshot.internalWrappedModelsSeen());
                Erydon.LOGGER.info("[erydon/prof] ctm_properties_seen_by_namespace={}; ctm_methods_seen=repeat:{},overlay_ctm:{},other:{}; ctm_rules_handled_by_erydon={}",
                        snapshot.ctmPropertiesSeenByNamespace(),
                        snapshot.repeatCtmRulesSeen(),
                        snapshot.overlayCtmRulesSeen(),
                        snapshot.otherCtmRulesSeen(),
                        snapshot.ctmRulesHandledByErydon());
                Erydon.LOGGER.info("[erydon/prof] resource profile scan took {} ms.", toMillis(elapsedNanos));
                writeRuntimeReport("resource-scan");
            }
        });
    }

    public static void beginModelReload() {
        if (enabled()) {
            resetReloadCounters();
        }
    }

    public static void oldSlopeParentModelBaked() {
        if (!enabled()) {
            return;
        }

        OLD_SLOPE_PARENT_MODELS_BAKED.incrementAndGet();
        scheduleModelSummary();
    }

    public static DynamicBakedModelCache.WrapObserver wrapObserver(ErydonSlopeModelClassifier.Family family, boolean itemModel) {
        if (!enabled()) {
            return NOOP_WRAP_OBSERVER;
        }

        return new DynamicBakedModelCache.WrapObserver() {
            @Override
            public void created() {
                created(0L);
            }

            @Override
            public void reused() {
                reused(0L);
            }

            @Override
            public void created(long durationNanos) {
                recordSlopeWrap(family, itemModel, true, durationNanos);
            }

            @Override
            public void reused(long durationNanos) {
                recordSlopeWrap(family, itemModel, false, durationNanos);
            }
        };
    }

    public static DynamicBakedModelCache.WrapObserver customWrapObserver(String family, boolean itemModel) {
        if (!enabled()) {
            return NOOP_WRAP_OBSERVER;
        }

        return new DynamicBakedModelCache.WrapObserver() {
            @Override
            public void created() {
                created(0L);
            }

            @Override
            public void reused() {
                reused(0L);
            }

            @Override
            public void created(long durationNanos) {
                recordCustomWrap(family, itemModel, true, durationNanos);
            }

            @Override
            public void reused(long durationNanos) {
                recordCustomWrap(family, itemModel, false, durationNanos);
            }
        };
    }

    public static void inspectFinalModel(Identifier id, BakedModel model) {
        if (!enabled() || !(id instanceof ModelIdentifier mid)) {
            return;
        }

        WrapperInspection inspection = inspectModel(model);
        FINAL_MODELS_INSPECTED.incrementAndGet();

        if (!Erydon.MOD_ID.equals(mid.getNamespace())) {
            if (inspection.hasErydonWrapper()) {
                FINAL_FOREIGN_MODELS_WITH_ERYDON_WRAPPERS.incrementAndGet();
            }
            return;
        }

        trackFinalModelChain(mid, inspection);
        if (INVENTORY_VARIANT.equals(mid.getVariant())) {
            return;
        }

        ErydonSlopeModelClassifier.Family slopeFamily = ErydonSlopeModelClassifier.familyForId(mid);
        if (slopeFamily != ErydonSlopeModelClassifier.Family.NONE) {
            FINAL_ERYDON_SLOPE_MODELS.incrementAndGet();
            if (inspection.hasContinuityWrapper()) {
                FINAL_ERYDON_SLOPE_WITH_CONTINUITY.incrementAndGet();
            }
            return;
        }

        String customFamily = customFamilyForPath(mid.getPath());
        if (customFamily != null) {
            FINAL_ERYDON_NON_SLOPE_MODELS.incrementAndGet();
            if (inspection.hasContinuityWrapper()) {
                FINAL_ERYDON_NON_SLOPE_WITH_CONTINUITY.incrementAndGet();
                if (inspection.outerContinuity()) {
                    FINAL_ERYDON_NON_SLOPE_OUTER_CONTINUITY.incrementAndGet();
                } else {
                    FINAL_ERYDON_NON_SLOPE_INNER_CONTINUITY.incrementAndGet();
                }
            } else {
                FINAL_ERYDON_NON_SLOPE_WITHOUT_CONTINUITY.incrementAndGet();
            }
            FINAL_MODEL_CHAIN_SAMPLES.putIfAbsent(customFamily, inspection.chain());
        }
    }

    public static void continuityTransformCalled() {
        if (enabled()) {
            CONTINUITY_TRANSFORM_CALLS.incrementAndGet();
        }
    }

    public static void continuitySkipRequested(String reason) {
        if (!enabled()) {
            return;
        }

        CONTINUITY_SKIP_REQUESTED.incrementAndGet();
        incrementReason("requested_" + reason, 1);
    }

    public static void continuitySkipApplied(String reason, int count) {
        if (!enabled()) {
            return;
        }

        CONTINUITY_SKIP_APPLIED.addAndGet(Math.max(count, 0));
        incrementReason("applied_" + reason, Math.max(count, 0));
    }

    public static void logContinuitySummary() {
        if (!enabled()) {
            return;
        }

        Erydon.LOGGER.info("[erydon/prof] continuity_skip_requested={}; continuity_skip_applied={}; continuity_skip_reasons={}",
                CONTINUITY_SKIP_REQUESTED.get(),
                CONTINUITY_SKIP_APPLIED.get(),
                sortedCounts(CONTINUITY_SKIP_REASONS));
    }

    private static ResourceSnapshot scanResources(ResourceManager manager) {
        String activeResourcePacks = manager.streamResourcePacks()
                .map(ResourcePack::getName)
                .sorted()
                .toList()
                .toString();
        Map<Identifier, Resource> itemModels = manager.findResources("models/item", id -> isErydonJson(id) && id.getPath().contains("slope"));
        Map<Identifier, Resource> blockstates = manager.findResources("blockstates", id -> isErydonJson(id) && id.getPath().contains("slope"));
        Map<Identifier, Resource> oldSlopeModels = manager.findResources("models/block/slope", ErydonLoadProfiler::isErydonJson);
        Map<Identifier, Resource> internalWrappedModels = manager.findResources("models/block/internal/wrapped", id -> isErydonJson(id) && id.getPath().contains("slope"));

        int oldItemParents = 0;
        int internalItemParents = 0;
        int unexpectedItemParents = 0;
        for (Resource resource : itemModels.values()) {
            String parent = readJsonParent(resource);
            if (parent != null && parent.startsWith("erydon:block/slope/")) {
                oldItemParents++;
            } else if (parent != null && parent.startsWith("erydon:block/internal/wrapped/")) {
                internalItemParents++;
            } else {
                unexpectedItemParents++;
            }
        }

        CtmSnapshot ctmSnapshot = scanCtmResources(manager);
        return new ResourceSnapshot(
                activeResourcePacks,
                itemModels.size(),
                oldItemParents,
                internalItemParents,
                unexpectedItemParents,
                blockstates.size(),
                oldSlopeModels.size(),
                internalWrappedModels.size(),
                ctmSnapshot.propertiesByNamespace(),
                ctmSnapshot.repeatRulesSeen(),
                ctmSnapshot.overlayCtmRulesSeen(),
                ctmSnapshot.otherRulesSeen(),
                ctmSnapshot.rulesHandledByErydon()
        );
    }

    private static CtmSnapshot scanCtmResources(ResourceManager manager) {
        Map<String, Integer> propertiesByNamespace = new TreeMap<>();
        int repeatRules = 0;
        int overlayCtmRules = 0;
        int otherRules = 0;
        int rulesHandledByErydon = 0;

        for (String root : List.of("optifine/ctm", "mcpatcher/ctm")) {
            Map<Identifier, List<Resource>> resources = manager.findAllResources(root, id -> id.getPath().endsWith(".properties"));
            for (Map.Entry<Identifier, List<Resource>> entry : resources.entrySet()) {
                propertiesByNamespace.merge(entry.getKey().getNamespace(), entry.getValue().size(), Integer::sum);
                for (Resource resource : entry.getValue()) {
                    Properties properties = new Properties();
                    try (InputStream input = resource.getInputStream()) {
                        properties.load(input);
                    } catch (IOException exception) {
                        Erydon.LOGGER.warn("[{}] Failed to read CTM properties from {}.", Erydon.MOD_ID, resource.getPack().getName(), exception);
                        continue;
                    }

                    String method = properties.getProperty("method", "").trim();
                    if ("repeat".equalsIgnoreCase(method)) {
                        repeatRules++;
                    } else if ("overlay_ctm".equalsIgnoreCase(method)) {
                        overlayCtmRules++;
                    } else {
                        otherRules++;
                    }

                    if (matchBlocksIncludeHandledErydonSlope(properties.getProperty("matchBlocks"))) {
                        rulesHandledByErydon++;
                    }
                }
            }
        }

        return new CtmSnapshot(propertiesByNamespace, repeatRules, overlayCtmRules, otherRules, rulesHandledByErydon);
    }

    private static boolean matchBlocksIncludeHandledErydonSlope(String matchBlocks) {
        if (matchBlocks == null || matchBlocks.isBlank()) {
            return false;
        }

        for (String rawToken : matchBlocks.split("\\s+")) {
            Identifier id = parseBlockId(rawToken);
            if (ErydonSlopeModelClassifier.isHandledSlopeId(id)) {
                return true;
            }
        }
        return false;
    }

    private static Identifier parseBlockId(String rawToken) {
        String token = rawToken.trim();
        if (token.isEmpty() || token.equals("\\")) {
            return null;
        }
        while (token.startsWith("!")) {
            token = token.substring(1);
        }

        int stateStart = token.indexOf('[');
        if (stateStart >= 0) {
            token = token.substring(0, stateStart);
        }

        int metadataStart = token.indexOf(':', token.indexOf(':') + 1);
        if (metadataStart >= 0) {
            token = token.substring(0, metadataStart);
        }

        if (token.isEmpty() || token.indexOf('=') >= 0) {
            return null;
        }

        return token.indexOf(':') >= 0 ? Identifier.tryParse(token) : Identifier.tryParse("minecraft:" + token);
    }

    private static String readJsonParent(Resource resource) {
        try (InputStream input = resource.getInputStream();
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonObject()) {
                return null;
            }

            JsonObject object = element.getAsJsonObject();
            JsonElement parent = object.get("parent");
            return parent != null && parent.isJsonPrimitive() ? parent.getAsString() : null;
        } catch (RuntimeException | IOException exception) {
            Erydon.LOGGER.warn("[{}] Failed to inspect model JSON from {}.", Erydon.MOD_ID, resource.getPack().getName(), exception);
            return null;
        }
    }

    private static boolean isErydonJson(Identifier id) {
        return Erydon.MOD_ID.equals(id.getNamespace()) && id.getPath().endsWith(".json");
    }

    private static void recordSlopeWrap(ErydonSlopeModelClassifier.Family family,
                                        boolean itemModel,
                                        boolean created,
                                        long durationNanos) {
        if (!enabled()) {
            return;
        }

        Map<WrapBucket, AtomicInteger> counters = created ? CREATED_BY_BUCKET : REUSED_BY_BUCKET;
        counters.get(bucketFor(family, itemModel)).incrementAndGet();
        recordCustomWrap("slopes", itemModel, created, durationNanos);
    }

    private static void recordCustomWrap(String family, boolean itemModel, boolean created, long durationNanos) {
        if (!enabled()) {
            return;
        }

        if (created) {
            CUSTOM_MODELS_CREATED.incrementAndGet();
            if (itemModel) {
                CUSTOM_MODEL_INVENTORY_CREATIONS.incrementAndGet();
            } else {
                CUSTOM_MODEL_CACHE_MISSES.incrementAndGet();
            }
        } else if (!itemModel) {
            CUSTOM_MODEL_CACHE_HITS.incrementAndGet();
        }

        if (durationNanos > 0L) {
            CUSTOM_MODEL_WRAP_NANOS.addAndGet(durationNanos);
        }

        familyCounters(family).record(itemModel, created, durationNanos);
        scheduleModelSummary();
    }

    private static WrapBucket bucketFor(ErydonSlopeModelClassifier.Family family, boolean itemModel) {
        if (itemModel) {
            return WrapBucket.ITEM;
        }

        return switch (family) {
            case STANDARD -> WrapBucket.STANDARD;
            case SHALLOW_LOWER, SHALLOW_UPPER -> WrapBucket.SHALLOW;
            case STEEP_LOWER, STEEP_UPPER -> WrapBucket.STEEP;
            case VERTICAL, VERTICAL_SHALLOW_BROAD, VERTICAL_SHALLOW_NARROW -> WrapBucket.VERTICAL;
            case NONE -> WrapBucket.STANDARD;
        };
    }

    private static void resetReloadCounters() {
        CREATED_BY_BUCKET.values().forEach(counter -> counter.set(0));
        REUSED_BY_BUCKET.values().forEach(counter -> counter.set(0));
        OLD_SLOPE_PARENT_MODELS_BAKED.set(0);
        CONTINUITY_SKIP_REQUESTED.set(0);
        CONTINUITY_SKIP_APPLIED.set(0);
        CONTINUITY_SKIP_REASONS.clear();
        CUSTOM_FAMILY_COUNTERS.clear();
        CUSTOM_MODELS_CREATED.set(0);
        CUSTOM_MODEL_CACHE_HITS.set(0);
        CUSTOM_MODEL_CACHE_MISSES.set(0);
        CUSTOM_MODEL_INVENTORY_CREATIONS.set(0);
        CUSTOM_MODEL_WRAP_NANOS.set(0L);
        RESOURCE_SCAN_NANOS.set(0L);
        FINAL_MODELS_INSPECTED.set(0);
        FINAL_ERYDON_NON_SLOPE_MODELS.set(0);
        FINAL_ERYDON_NON_SLOPE_WITH_CONTINUITY.set(0);
        FINAL_ERYDON_NON_SLOPE_WITHOUT_CONTINUITY.set(0);
        FINAL_ERYDON_NON_SLOPE_OUTER_CONTINUITY.set(0);
        FINAL_ERYDON_NON_SLOPE_INNER_CONTINUITY.set(0);
        FINAL_ERYDON_SLOPE_MODELS.set(0);
        FINAL_ERYDON_SLOPE_WITH_CONTINUITY.set(0);
        FINAL_FOREIGN_MODELS_WITH_ERYDON_WRAPPERS.set(0);
        FINAL_MODEL_CHAIN_SAMPLES.clear();
        TRACKED_FINAL_MODEL_CHAINS.clear();
        CONTINUITY_TRANSFORM_CALLS.set(0);
        SUMMARY_GENERATION.incrementAndGet();
    }

    private static void scheduleModelSummary() {
        int generation = SUMMARY_GENERATION.incrementAndGet();
        SUMMARY_EXECUTOR.schedule(() -> {
            if (generation == SUMMARY_GENERATION.get() && enabled()) {
                Erydon.LOGGER.info("[erydon/prof] old_slope_parent_models_baked={}; wrapped_models_created_by_family={}; wrapped_models_reused_by_family={}",
                        OLD_SLOPE_PARENT_MODELS_BAKED.get(),
                        formatBuckets(CREATED_BY_BUCKET),
                        formatBuckets(REUSED_BY_BUCKET));
                Erydon.LOGGER.info("[erydon/prof] custom_models_created={}; cache_hits={}; cache_misses={}; inventory_creations={}; wrapper_generation_ms={}.",
                        CUSTOM_MODELS_CREATED.get(),
                        CUSTOM_MODEL_CACHE_HITS.get(),
                        CUSTOM_MODEL_CACHE_MISSES.get(),
                        CUSTOM_MODEL_INVENTORY_CREATIONS.get(),
                        toMillis(CUSTOM_MODEL_WRAP_NANOS.get()));
                logContinuitySummary();
                writeRuntimeReport("model-summary");
            }
        }, 2L, TimeUnit.SECONDS);
    }

    private static FamilyRuntimeCounters familyCounters(String family) {
        return CUSTOM_FAMILY_COUNTERS.computeIfAbsent(family, ignored -> new FamilyRuntimeCounters());
    }

    private static WrapperInspection inspectModel(BakedModel model) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object> stack = new ArrayDeque<>();
        stack.push(model);
        boolean hasContinuityWrapper = false;
        boolean hasErydonWrapper = false;
        boolean outerContinuity = false;
        StringBuilder chain = new StringBuilder();
        int visited = 0;

        while (!stack.isEmpty() && visited < 32) {
            Object current = stack.pop();
            if (current == null || !seen.add(current)) {
                continue;
            }

            visited++;
            Class<?> type = current.getClass();
            String className = type.getName();
            String lowerClassName = className.toLowerCase(Locale.ROOT);
            boolean continuityWrapper = lowerClassName.contains("continuity");
            boolean erydonWrapper = className.startsWith("com.oliver.erydon.client.model.")
                    && className.endsWith("BakedModel");
            if (visited == 1) {
                outerContinuity = continuityWrapper;
            }
            if (chain.length() > 0) {
                chain.append(" -> ");
            }
            chain.append(shortClassName(className));
            hasContinuityWrapper |= continuityWrapper;
            hasErydonWrapper |= erydonWrapper;

            for (Class<?> inspectType = type; inspectType != null && inspectType != Object.class; inspectType = inspectType.getSuperclass()) {
                for (Field field : inspectType.getDeclaredFields()) {
                    if (!BakedModel.class.isAssignableFrom(field.getType()) || !field.trySetAccessible()) {
                        continue;
                    }
                    try {
                        Object child = field.get(current);
                        if (child instanceof BakedModel) {
                            stack.push(child);
                        }
                    } catch (IllegalAccessException ignored) {
                        // Some third-party wrappers keep internals private. The probe is best-effort only.
                    }
                }
            }
        }

        return new WrapperInspection(hasContinuityWrapper, hasErydonWrapper, outerContinuity, chain.toString());
    }

    private static String shortClassName(String className) {
        int packageEnd = className.lastIndexOf('.');
        return packageEnd >= 0 ? className.substring(packageEnd + 1) : className;
    }

    private static String customFamilyForPath(String path) {
        if (path.contains("column_circular") || path.contains("column_gothic") || path.contains("column_square")) {
            return "columns";
        }
        if (path.contains("_cornice_")) {
            return "cornices";
        }
        if (path.contains("_ceiling_coffered_") || path.startsWith("ceiling_coffered_")) {
            return "coffered_ceilings";
        }
        if (LayerVerticalBakedModel.isLayerVerticalBlock(path)) {
            return "layer_vertical";
        }
        if (path.contains("_surround_")) {
            return "surrounds";
        }
        if (path.contains("_window_french_georgian")) {
            return "french_georgian_windows";
        }
        if (path.contains("_window_arch")) {
            return "arch_windows";
        }
        if (path.contains("_arch_romanesque")) {
            return "romanesque_arches";
        }
        if (path.contains("_arch_modern")) {
            return "modern_arches";
        }
        if (path.contains("_arch_gothic")) {
            return "gothic_arches";
        }
        return null;
    }

    private static void writeRuntimeReport(String reason) {
        Path path = runtimeReportPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(runtimeReport(reason)) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            Erydon.LOGGER.warn("[{}] Failed to write model performance runtime report to {}.", Erydon.MOD_ID, path, exception);
        }
    }

    private static Path runtimeReportPath() {
        String configuredPath = System.getProperty(RUNTIME_REPORT_PATH_KEY);
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath.trim());
        }

        return FabricLoader.getInstance().getGameDir()
                .resolve("..")
                .resolve("build")
                .resolve("reports")
                .resolve("erydon-model-performance-runtime.json")
                .normalize();
    }

    private static Map<String, Object> runtimeReport(String reason) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("reason", reason);
        Map<String, Object> timings = new LinkedHashMap<>();
        timings.put("erydonModelBakingMs", toMillis(CUSTOM_MODEL_WRAP_NANOS.get()));
        timings.put("totalMeasuredResourceReloadMs", null);
        timings.put("resourceProfileScanMs", toMillis(RESOURCE_SCAN_NANOS.get()));
        timings.put("note", "Runtime report measures ERYDON custom wrapper creation; full resource reload timing is not estimated.");
        report.put("timings", timings);
        report.put("counters", Map.of(
                "customModelsCreated", CUSTOM_MODELS_CREATED.get(),
                "customModelCacheHits", CUSTOM_MODEL_CACHE_HITS.get(),
                "customModelCacheMisses", CUSTOM_MODEL_CACHE_MISSES.get(),
                "customModelInventoryCreations", CUSTOM_MODEL_INVENTORY_CREATIONS.get(),
                "customModelFamilies", customFamilySnapshot(),
                "continuityWrapperCounts", continuityWrapperSnapshot(),
                "erydonSlopeWrapperCounts", slopeWrapperSnapshot()
        ));
        report.put("ctmDiagnostics", ctmDiagnosticsSnapshot());
        report.put("sharedGeometryPilot", ErydonSharedGeometryMetrics.snapshot());
        ResourceSnapshot snapshot = lastResourceSnapshot;
        if (snapshot != null) {
            report.put("resourceSnapshot", snapshot.snapshot());
        }
        return report;
    }

    private static Map<String, Object> customFamilySnapshot() {
        Map<String, Object> snapshot = new TreeMap<>();
        CUSTOM_FAMILY_COUNTERS.forEach((family, counters) -> snapshot.put(family, counters.snapshot()));
        return snapshot;
    }

    private static Map<String, Object> continuityWrapperSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("finalModelsInspected", FINAL_MODELS_INSPECTED.get());
        snapshot.put("continuityTransformCalls", CONTINUITY_TRANSFORM_CALLS.get());
        snapshot.put("erydonNonSlopeFinalModels", FINAL_ERYDON_NON_SLOPE_MODELS.get());
        snapshot.put("erydonNonSlopeWithContinuityWrapper", FINAL_ERYDON_NON_SLOPE_WITH_CONTINUITY.get());
        snapshot.put("erydonNonSlopeWithoutContinuityWrapper", FINAL_ERYDON_NON_SLOPE_WITHOUT_CONTINUITY.get());
        snapshot.put("erydonNonSlopeOuterContinuityWrapper", FINAL_ERYDON_NON_SLOPE_OUTER_CONTINUITY.get());
        snapshot.put("erydonNonSlopeInnerContinuityWrapper", FINAL_ERYDON_NON_SLOPE_INNER_CONTINUITY.get());
        snapshot.put("erydonSlopeFinalModels", FINAL_ERYDON_SLOPE_MODELS.get());
        snapshot.put("erydonSlopeWithContinuityWrapper", FINAL_ERYDON_SLOPE_WITH_CONTINUITY.get());
        snapshot.put("foreignFinalModelsWithErydonWrappers", FINAL_FOREIGN_MODELS_WITH_ERYDON_WRAPPERS.get());
        snapshot.put("sampleFinalModelChains", new TreeMap<>(FINAL_MODEL_CHAIN_SAMPLES));
        snapshot.put("trackedFinalModelChains", new TreeMap<>(TRACKED_FINAL_MODEL_CHAINS));
        return snapshot;
    }

    private static void trackFinalModelChain(ModelIdentifier id, WrapperInspection inspection) {
        String path = id.getPath();
        if (!INVENTORY_VARIANT.equals(id.getVariant()) && (
                "aganite_block".equals(path)
                        || "aganite_cornice_gothic".equals(path)
                        || "aganite_column_gothic".equals(path)
                        || "aganite_arch_romanesque".equals(path)
        )) {
            TRACKED_FINAL_MODEL_CHAINS.putIfAbsent(path + "#" + id.getVariant(), inspection.chain());
        }
    }

    private static Map<String, Object> ctmDiagnosticsSnapshot() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("continuityTransformCalls", CONTINUITY_TRANSFORM_CALLS.get());
        diagnostics.put("trackedModelChains", new TreeMap<>(TRACKED_FINAL_MODEL_CHAINS));
        diagnostics.put("processorSlices", continuityProcessorSlices());
        return diagnostics;
    }

    private static Map<String, Object> continuityProcessorSlices() {
        Map<String, Object> slices = new TreeMap<>();
        addProcessorSlice(slices, "aganite_block", "block/aganite_block");
        addProcessorSlice(slices, "aganite_cornice_gothic", "block/aganite_block");
        addProcessorSlice(slices, "aganite_column_gothic", "block/aganite_block");
        addProcessorSlice(slices, "aganite_arch_romanesque", "block/aganite_block");
        return slices;
    }

    @SuppressWarnings("unchecked")
    private static void addProcessorSlice(Map<String, Object> slices, String blockPath, String texturePath) {
        Map<String, Object> slice = new LinkedHashMap<>();
        slices.put(blockPath, slice);

        try {
            Block block = Registries.BLOCK.get(new Identifier(Erydon.MOD_ID, blockPath));
            BlockState state = block.getDefaultState();
            Sprite sprite = MinecraftClient.getInstance()
                    .getBakedModelManager()
                    .getAtlas(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE)
                    .getSprite(new Identifier(Erydon.MOD_ID, texturePath));

            Class<?> quadProcessors = Class.forName("me.pepperbell.continuity.client.model.QuadProcessors");
            Method getCache = quadProcessors.getDeclaredMethod("getCache", BlockState.class);
            Function<Sprite, Object> spriteCache = (Function<Sprite, Object>) getCache.invoke(null, state);
            Object processorSlice = spriteCache.apply(sprite);
            Method processorsMethod = processorSlice.getClass().getDeclaredMethod("processors");
            Method multipassProcessorsMethod = processorSlice.getClass().getDeclaredMethod("multipassProcessors");
            processorsMethod.trySetAccessible();
            multipassProcessorsMethod.trySetAccessible();

            Object[] processors = (Object[]) processorsMethod.invoke(processorSlice);
            Object[] multipassProcessors = (Object[]) multipassProcessorsMethod.invoke(processorSlice);
            slice.put("state", Registries.BLOCK.getId(block).toString());
            slice.put("sprite", Erydon.MOD_ID + ":" + texturePath);
            slice.put("processors", processors.length);
            slice.put("multipassProcessors", multipassProcessors.length);
            slice.put("processorTypes", processorTypes(processors));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            slice.put("error", exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private static List<String> processorTypes(Object[] processors) {
        List<String> types = new ArrayList<>(processors.length);
        for (Object processor : processors) {
            types.add(shortClassName(processor.getClass().getName()));
        }
        return types;
    }

    private static Map<String, Object> slopeWrapperSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("createdByBucket", bucketSnapshot(CREATED_BY_BUCKET));
        snapshot.put("reusedByBucket", bucketSnapshot(REUSED_BY_BUCKET));
        snapshot.put("oldSlopeParentModelsBaked", OLD_SLOPE_PARENT_MODELS_BAKED.get());
        return snapshot;
    }

    private static Map<String, Integer> bucketSnapshot(EnumMap<WrapBucket, AtomicInteger> counters) {
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        for (WrapBucket bucket : WrapBucket.values()) {
            snapshot.put(bucket.name().toLowerCase(Locale.ROOT), counters.get(bucket).get());
        }
        return snapshot;
    }

    private static void incrementReason(String reason, int amount) {
        CONTINUITY_SKIP_REASONS.computeIfAbsent(reason, ignored -> new AtomicInteger()).addAndGet(amount);
    }

    private static EnumMap<WrapBucket, AtomicInteger> countersByBucket() {
        EnumMap<WrapBucket, AtomicInteger> counters = new EnumMap<>(WrapBucket.class);
        for (WrapBucket bucket : WrapBucket.values()) {
            counters.put(bucket, new AtomicInteger());
        }
        return counters;
    }

    private static String formatBuckets(EnumMap<WrapBucket, AtomicInteger> counters) {
        return "standard:" + counters.get(WrapBucket.STANDARD).get()
                + ",shallow:" + counters.get(WrapBucket.SHALLOW).get()
                + ",steep:" + counters.get(WrapBucket.STEEP).get()
                + ",vertical:" + counters.get(WrapBucket.VERTICAL).get()
                + ",item:" + counters.get(WrapBucket.ITEM).get();
    }

    private static String sortedCounts(Map<String, AtomicInteger> counters) {
        return counters.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> entry.getKey() + ":" + entry.getValue().get())
                .toList()
                .toString();
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static double toMillis(long nanos) {
        return Math.round((nanos / 1_000_000.0d) * 100.0d) / 100.0d;
    }

    private static boolean enabled() {
        return ENABLED;
    }

    private enum WrapBucket {
        STANDARD,
        SHALLOW,
        STEEP,
        VERTICAL,
        ITEM
    }

    private record ResourceSnapshot(String activeResourcePacks,
                                    int slopeItemModelsTotal,
                                    int slopeItemModelsOldParentRefs,
                                    int slopeItemModelsInternalWrappedParentRefs,
                                    int slopeItemModelsUnexpectedParentRefs,
                                    int slopeBlockstatesTotal,
                                    int oldSlopeParentModelsSeen,
                                    int internalWrappedModelsSeen,
                                    Map<String, Integer> ctmPropertiesSeenByNamespace,
                                    int repeatCtmRulesSeen,
                                    int overlayCtmRulesSeen,
                                    int otherCtmRulesSeen,
                                    int ctmRulesHandledByErydon) {
        private Map<String, Object> snapshot() {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("activeResourcePacks", activeResourcePacks);
            snapshot.put("slopeItemModelsTotal", slopeItemModelsTotal);
            snapshot.put("slopeItemModelsOldParentRefs", slopeItemModelsOldParentRefs);
            snapshot.put("slopeItemModelsInternalWrappedParentRefs", slopeItemModelsInternalWrappedParentRefs);
            snapshot.put("slopeItemModelsUnexpectedParentRefs", slopeItemModelsUnexpectedParentRefs);
            snapshot.put("slopeBlockstatesTotal", slopeBlockstatesTotal);
            snapshot.put("oldSlopeParentModelsSeen", oldSlopeParentModelsSeen);
            snapshot.put("internalWrappedModelsSeen", internalWrappedModelsSeen);
            snapshot.put("ctmPropertiesSeenByNamespace", ctmPropertiesSeenByNamespace);
            snapshot.put("repeatCtmRulesSeen", repeatCtmRulesSeen);
            snapshot.put("overlayCtmRulesSeen", overlayCtmRulesSeen);
            snapshot.put("otherCtmRulesSeen", otherCtmRulesSeen);
            snapshot.put("ctmRulesHandledByErydon", ctmRulesHandledByErydon);
            return snapshot;
        }
    }

    private record CtmSnapshot(Map<String, Integer> propertiesByNamespace,
                               int repeatRulesSeen,
                               int overlayCtmRulesSeen,
                               int otherRulesSeen,
                               int rulesHandledByErydon) {
    }

    private record WrapperInspection(boolean hasContinuityWrapper,
                                     boolean hasErydonWrapper,
                                     boolean outerContinuity,
                                     String chain) {
    }

    private static final class FamilyRuntimeCounters {
        private final AtomicInteger created = new AtomicInteger();
        private final AtomicInteger reused = new AtomicInteger();
        private final AtomicInteger cacheHits = new AtomicInteger();
        private final AtomicInteger cacheMisses = new AtomicInteger();
        private final AtomicInteger inventoryCreations = new AtomicInteger();
        private final AtomicLong wrapNanos = new AtomicLong();

        private void record(boolean itemModel, boolean wasCreated, long durationNanos) {
            if (wasCreated) {
                created.incrementAndGet();
                if (itemModel) {
                    inventoryCreations.incrementAndGet();
                } else {
                    cacheMisses.incrementAndGet();
                }
            } else {
                reused.incrementAndGet();
                if (!itemModel) {
                    cacheHits.incrementAndGet();
                }
            }
            if (durationNanos > 0L) {
                wrapNanos.addAndGet(durationNanos);
            }
        }

        private Map<String, Object> snapshot() {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("created", created.get());
            snapshot.put("reused", reused.get());
            snapshot.put("cacheHits", cacheHits.get());
            snapshot.put("cacheMisses", cacheMisses.get());
            snapshot.put("inventoryCreations", inventoryCreations.get());
            snapshot.put("wrapperGenerationMs", toMillis(wrapNanos.get()));
            return snapshot;
        }
    }
}
