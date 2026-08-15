package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.ErydonConfig;
import net.minecraft.client.util.ModelIdentifier;

import java.util.concurrent.atomic.AtomicInteger;

final class ErydonModelReloadDebug {
    private static final String INVENTORY_VARIANT = "inventory";
    private static final AtomicInteger SLOPE_MODEL_IDS_EVALUATED = new AtomicInteger();
    private static final AtomicInteger SLOPE_BLOCK_MODELS_WRAPPED = new AtomicInteger();
    private static final AtomicInteger SLOPE_ITEM_MODELS_WRAPPED = new AtomicInteger();
    private static final AtomicInteger WRAPPERS_REUSED = new AtomicInteger();
    private static final AtomicInteger WRAPPERS_CREATED = new AtomicInteger();
    private static final AtomicInteger OLD_STEPPED_SLOPE_REFERENCES_SEEN = new AtomicInteger();

    private ErydonModelReloadDebug() {
    }

    static void slopeModelIdEvaluated() {
        if (enabled()) {
            SLOPE_MODEL_IDS_EVALUATED.incrementAndGet();
        }
    }

    static void slopeModelWrapped(ModelIdentifier id) {
        if (!enabled()) {
            return;
        }

        if (INVENTORY_VARIANT.equals(id.getVariant())) {
            SLOPE_ITEM_MODELS_WRAPPED.incrementAndGet();
        } else {
            SLOPE_BLOCK_MODELS_WRAPPED.incrementAndGet();
        }
        maybeLogSummary();
    }

    static DynamicBakedModelCache.WrapObserver wrapObserver() {
        return new DynamicBakedModelCache.WrapObserver() {
            @Override
            public void created() {
                if (enabled()) {
                    WRAPPERS_CREATED.incrementAndGet();
                }
            }

            @Override
            public void reused() {
                if (enabled()) {
                    WRAPPERS_REUSED.incrementAndGet();
                }
            }
        };
    }

    static void oldSteppedSlopeReferenceSeen() {
        if (enabled()) {
            OLD_STEPPED_SLOPE_REFERENCES_SEEN.incrementAndGet();
        }
    }

    static void logPluginRegistration(String label, long startedNanos) {
        if (!enabled()) {
            return;
        }

        Erydon.LOGGER.info("[erydon/model] {} slope model plugin registration took {} ms.",
                label, elapsedMs(startedNanos));
    }

    private static void maybeLogSummary() {
        int wrapped = SLOPE_BLOCK_MODELS_WRAPPED.get() + SLOPE_ITEM_MODELS_WRAPPED.get();
        if (wrapped > 0 && wrapped % 500 == 0) {
            Erydon.LOGGER.info("[erydon/model] slope model ids evaluated: {}; slope block models wrapped: {}; slope item models wrapped: {}; wrappers reused from cache: {}; wrappers created: {}; old stepped slope model references seen: {}.",
                    SLOPE_MODEL_IDS_EVALUATED.get(),
                    SLOPE_BLOCK_MODELS_WRAPPED.get(),
                    SLOPE_ITEM_MODELS_WRAPPED.get(),
                    WRAPPERS_REUSED.get(),
                    WRAPPERS_CREATED.get(),
                    OLD_STEPPED_SLOPE_REFERENCES_SEEN.get());
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static boolean enabled() {
        return ErydonConfig.debugModelReload();
    }
}
