package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.block.GeorgianWallSlopeResolver;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class GeorgianWallSlopeModelLoadingPlugin
        implements PreparableModelLoadingPlugin<GeorgianWallSlopeModelLoadingPlugin.PreparedModels> {
    private static final Identifier SHALLOW_UPPER_MODEL = new Identifier(
            Erydon.MOD_ID,
            "authoring_models/block/wall/georgian/wall_georgian_27_upper.json"
    );
    private static final Identifier SHALLOW_LOWER_MODEL = new Identifier(
            Erydon.MOD_ID,
            "authoring_models/block/wall/georgian/wall_georgian_27_lower.json"
    );
    private static final Identifier SHALLOW_UPPER_OFFRAMP_MODEL = new Identifier(
            Erydon.MOD_ID,
            "authoring_models/block/wall/georgian/wall_georgian_27_upper_offramp.json"
    );
    private static final Identifier SHALLOW_LOWER_ONRAMP_MODEL = new Identifier(
            Erydon.MOD_ID,
            "authoring_models/block/wall/georgian/wall_georgian_27_lower_onramp.json"
    );
    private static final Identifier STEEP_MODEL = new Identifier(
            Erydon.MOD_ID,
            "authoring_models/block/wall/georgian/wall_georgian_45.json"
    );
    private static final Identifier STEEP_ONRAMP_MODEL = new Identifier(
            Erydon.MOD_ID,
            "authoring_models/block/wall/georgian/wall_georgian_45_onramp.json"
    );
    private static final Identifier STEEP_OFFRAMP_MODEL = new Identifier(
            Erydon.MOD_ID,
            "authoring_models/block/wall/georgian/wall_georgian_45_offramp.json"
    );
    private static final Identifier FLAT_SIDE_MODEL = new Identifier(
            Erydon.MOD_ID,
            "models/block/wall/georgian/wall_georgian_side.json"
    );
    private static final Identifier MODEL_PHASE = new Identifier(
            Erydon.MOD_ID,
            "georgian_wall_slope"
    );

    public static CompletableFuture<PreparedModels> load(ResourceManager resourceManager, Executor executor) {
        return CompletableFuture.supplyAsync(() -> new PreparedModels(
                loadModel(resourceManager, SHALLOW_UPPER_MODEL),
                loadModel(resourceManager, SHALLOW_LOWER_MODEL),
                loadModel(resourceManager, SHALLOW_LOWER_ONRAMP_MODEL),
                loadModel(resourceManager, SHALLOW_UPPER_OFFRAMP_MODEL),
                loadModel(resourceManager, STEEP_MODEL),
                loadModel(resourceManager, STEEP_ONRAMP_MODEL),
                loadModel(resourceManager, STEEP_OFFRAMP_MODEL),
                loadFlatSideArm(resourceManager)
        ), executor);
    }

    private static GeorgianWallSlopeGeometry loadFlatSideArm(ResourceManager resourceManager) {
        try {
            GeorgianWallSlopeGeometry geometry = GeorgianWallSlopeGeometry.loadFlatSideArm(
                    resourceManager,
                    FLAT_SIDE_MODEL
            );
            Erydon.LOGGER.info(
                    "[{}] Loaded {} Georgian wall flat corner-arm surfaces from {}.",
                    Erydon.MOD_ID,
                    geometry.surfaceCount(),
                    FLAT_SIDE_MODEL
            );
            return geometry;
        } catch (IOException exception) {
            Erydon.LOGGER.error(
                    "[{}] Failed to load Georgian wall flat corner arm {}.",
                    Erydon.MOD_ID,
                    FLAT_SIDE_MODEL,
                    exception
            );
            return null;
        }
    }

    private static GeorgianWallSlopeGeometry loadModel(ResourceManager resourceManager, Identifier id) {
        try {
            GeorgianWallSlopeGeometry geometry = GeorgianWallSlopeGeometry.load(resourceManager, id);
            Erydon.LOGGER.info(
                    "[{}] Loaded {} Georgian wall slope surfaces from {}.",
                    Erydon.MOD_ID,
                    geometry.surfaceCount(),
                    id
            );
            return geometry;
        } catch (IOException exception) {
            Erydon.LOGGER.error(
                    "[{}] Failed to load Georgian wall slope model {}.",
                    Erydon.MOD_ID,
                    id,
                    exception
            );
            return null;
        }
    }

    @Override
    public void onInitializeModelLoader(PreparedModels data, ModelLoadingPlugin.Context context) {
        context.modifyModelAfterBake().addPhaseOrdering(ModelModifier.WRAP_LAST_PHASE, MODEL_PHASE);
        context.modifyModelAfterBake().addPhaseOrdering(
                MODEL_PHASE,
                SynapheiaModelLoadingPlugin.CTM_PHASE
        );
        context.modifyModelAfterBake().register(MODEL_PHASE, (model, modificationContext) -> {
            Identifier id = modificationContext.id();
            if (!(id instanceof ModelIdentifier modelId)
                    || !Erydon.MOD_ID.equals(modelId.getNamespace())
                    || !isGeorgianWall(modelId.getPath())) {
                return model;
            }
            return new GeorgianWallSlopeBakedModel(model, data);
        });
    }

    static boolean isGeorgianWall(String path) {
        return path != null && path.endsWith("_wall_georgian");
    }

    public static final class PreparedModels {
        private final GeorgianWallSlopeGeometry shallowUpper;
        private final GeorgianWallSlopeGeometry shallowLower;
        private final GeorgianWallSlopeGeometry shallowLowerOnramp;
        private final GeorgianWallSlopeGeometry shallowUpperOfframp;
        private final GeorgianWallSlopeGeometry steep;
        private final GeorgianWallSlopeGeometry steepOnramp;
        private final GeorgianWallSlopeGeometry steepOfframp;
        private final GeorgianWallSlopeGeometry flatSideArm;

        private PreparedModels(GeorgianWallSlopeGeometry shallowUpper,
                               GeorgianWallSlopeGeometry shallowLower,
                               GeorgianWallSlopeGeometry shallowLowerOnramp,
                               GeorgianWallSlopeGeometry shallowUpperOfframp,
                               GeorgianWallSlopeGeometry steep,
                               GeorgianWallSlopeGeometry steepOnramp,
                               GeorgianWallSlopeGeometry steepOfframp,
                               GeorgianWallSlopeGeometry flatSideArm) {
            this.shallowUpper = shallowUpper;
            this.shallowLower = shallowLower;
            this.shallowLowerOnramp = shallowLowerOnramp;
            this.shallowUpperOfframp = shallowUpperOfframp;
            this.steep = steep;
            this.steepOnramp = steepOnramp;
            this.steepOfframp = steepOfframp;
            this.flatSideArm = flatSideArm;
        }

        GeorgianWallSlopeGeometry flatSideArm() {
            return flatSideArm;
        }

        GeorgianWallSlopeGeometry forMode(GeorgianWallSlopeResolver.Mode mode) {
            if (!mode.isSlope()) {
                return null;
            }
            if (mode.profile() == GeorgianWallSlopeResolver.Profile.STEEP_45) {
                return switch (mode.variant()) {
                    case ONRAMP -> steepOnramp;
                    case OFFRAMP -> steepOfframp;
                    case REGULAR -> steep;
                };
            }

            return switch (mode.part()) {
                case UPPER -> mode.variant() == GeorgianWallSlopeResolver.Variant.OFFRAMP
                        ? shallowUpperOfframp
                        : shallowUpper;
                case LOWER -> mode.variant() == GeorgianWallSlopeResolver.Variant.ONRAMP
                        ? shallowLowerOnramp
                        : shallowLower;
                case NONE -> null;
            };
        }
    }
}
