package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.client.profile.ErydonLoadProfiler;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.util.Identifier;

public final class ErydonSlopeModelLoadingPlugin implements ModelLoadingPlugin {
    static final Identifier MINI_CTM_PHASE = new Identifier(Erydon.MOD_ID, "slope_mini_ctm");

    @Override
    public void onInitializeModelLoader(Context pluginContext) {
        long startedNanos = System.nanoTime();
        DynamicBakedModelCache cache = new DynamicBakedModelCache();

        pluginContext.modifyModelAfterBake().addPhaseOrdering(ModelModifier.WRAP_LAST_PHASE, MINI_CTM_PHASE);
        pluginContext.modifyModelAfterBake().register(MINI_CTM_PHASE, (model, context) -> {
            Identifier id = context.id();
            if (!Erydon.MOD_ID.equals(id.getNamespace())) {
                return model;
            }
            if (ErydonSlopeModelClassifier.isOldSteppedSlopeModelPath(id.getPath())) {
                ErydonModelReloadDebug.oldSteppedSlopeReferenceSeen();
                ErydonLoadProfiler.oldSlopeParentModelBaked();
                return model;
            }
            if (!(id instanceof ModelIdentifier mid)) {
                return model;
            }

            ErydonSlopeModelClassifier.Family family = ErydonSlopeModelClassifier.familyForId(mid);
            if (family == ErydonSlopeModelClassifier.Family.NONE) {
                return model;
            }

            ErydonModelReloadDebug.slopeModelIdEvaluated();
            ErydonModelReloadDebug.slopeModelWrapped(mid);
            return wrapByFamily(cache, mid, model, family, combinedObserver(mid, family));
        });
        ErydonModelReloadDebug.logPluginRegistration("combined", startedNanos);
    }

    private static DynamicBakedModelCache.WrapObserver combinedObserver(ModelIdentifier id,
                                                                        ErydonSlopeModelClassifier.Family family) {
        DynamicBakedModelCache.WrapObserver debugObserver = ErydonModelReloadDebug.wrapObserver();
        DynamicBakedModelCache.WrapObserver profileObserver = ErydonLoadProfiler.wrapObserver(family, "inventory".equals(id.getVariant()));
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
                debugObserver.created();
                profileObserver.created(durationNanos);
            }

            @Override
            public void reused(long durationNanos) {
                debugObserver.reused();
                profileObserver.reused(durationNanos);
            }
        };
    }

    private static BakedModel wrapByFamily(DynamicBakedModelCache cache,
                                           ModelIdentifier id,
                                           BakedModel model,
                                           ErydonSlopeModelClassifier.Family family,
                                           DynamicBakedModelCache.WrapObserver observer) {
        return switch (family) {
            case STANDARD -> cache.wrap(id, model, wrapped -> new SlopeBakedModel(wrapped, family), observer);
            case SHALLOW_LOWER, SHALLOW_UPPER ->
                    cache.wrap(id, model, wrapped -> new ShallowSlopeBakedModel(wrapped, family), observer);
            case STEEP_LOWER, STEEP_UPPER ->
                    cache.wrap(id, model, wrapped -> new SlopeSteepBakedModel(wrapped, family), observer);
            case VERTICAL, VERTICAL_SHALLOW_BROAD, VERTICAL_SHALLOW_NARROW ->
                    cache.wrap(id, model, wrapped -> new SlopeVerticalBakedModel(wrapped, family), observer);
            case NONE -> model;
        };
    }
}
