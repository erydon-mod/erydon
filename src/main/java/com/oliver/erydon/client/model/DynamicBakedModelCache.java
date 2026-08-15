package com.oliver.erydon.client.model;

import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.util.ModelIdentifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class DynamicBakedModelCache {
    private static final String INVENTORY_VARIANT = "inventory";

    private final Map<String, BakedModel> blockStateModels = new ConcurrentHashMap<>();

    BakedModel wrap(ModelIdentifier id, BakedModel model, Function<BakedModel, BakedModel> factory) {
        return wrap(id, model, factory, null);
    }

    BakedModel wrap(ModelIdentifier id,
                    BakedModel model,
                    Function<BakedModel, BakedModel> factory,
                    WrapObserver observer) {
        if (INVENTORY_VARIANT.equals(id.getVariant())) {
            long startedNanos = System.nanoTime();
            BakedModel created = factory.apply(model);
            if (observer != null) {
                observer.created(elapsedNanos(startedNanos));
            }
            return created;
        }

        String key = id.getNamespace() + ":" + id.getPath();
        BakedModel cached = blockStateModels.get(key);
        if (cached != null) {
            if (observer != null) {
                observer.reused(0L);
            }
            return cached;
        }

        long startedNanos = System.nanoTime();
        BakedModel created = factory.apply(model);
        long elapsedNanos = elapsedNanos(startedNanos);
        BakedModel existing = blockStateModels.putIfAbsent(key, created);
        if (existing != null) {
            if (observer != null) {
                observer.reused(elapsedNanos);
            }
            return existing;
        }

        if (observer != null) {
            observer.created(elapsedNanos);
        }
        return created;
    }

    private static long elapsedNanos(long startedNanos) {
        return System.nanoTime() - startedNanos;
    }

    public interface WrapObserver {
        void created();

        void reused();

        default void created(long durationNanos) {
            created();
        }

        default void reused(long durationNanos) {
            reused();
        }
    }
}
