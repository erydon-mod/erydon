package com.oliver.erydon.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oliver.erydon.Erydon;
import com.oliver.erydon.client.profile.ErydonSharedGeometryMetrics;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MeshBuilder;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.Baker;
import net.minecraft.client.render.model.ModelBakeSettings;
import net.minecraft.client.render.model.UnbakedModel;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.item.ItemStack;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;
import org.joml.Vector3f;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ErydonRawModelLoadingPlugin implements PreparableModelLoadingPlugin<ErydonRawModelLoadingPlugin.PreparedModels> {
    private static final Pattern GOTHIC_COLUMN_COMPONENT = Pattern.compile("^block/column/gothic/(.+)_column_gothic_(plinth|base|pillar|capital)(_aged)?$");
    private static final Pattern GEORGIAN_ALCOVE_COMPONENT = Pattern.compile("^block/alcove/georgian/(.+)_alcove_georgian_(back|sides|base|top|icon|double_side_left|double_side_right|double_top_left|double_top_right|triple_side_left|triple_side_center|triple_side_right|triple_top_left|triple_top_center|triple_top_right)(_aged)?$");
    private static final Pattern GOTHIC_ALCOVE_COMPONENT = Pattern.compile("^block/alcove/gothic/(.+)_alcove_gothic_(back|sides|base|top|icon|double_side_left|double_side_right|double_top_left|double_top_right|triple_side_left|triple_side_center|triple_side_right|triple_top_left|triple_top_center|triple_top_right)(_aged)?$");
    private static final Pattern GOTHIC_ARCH_COMPONENT = Pattern.compile("^block/arch/gothic/(.+)_arch_gothic_(corner_small|corner_medium|corner_large_upper|corner_large_lower|side_small|side_medium|side_large|top_large|icon)(_aged)?$");
    private static final Map<String, Identifier> AUTHORING_MODELS = Map.ofEntries(
            Map.entry("column_gothic/plinth", new Identifier(Erydon.MOD_ID, "authoring_models/block/column/gothic/column_gothic_plinth.json")),
            Map.entry("column_gothic/base", new Identifier(Erydon.MOD_ID, "authoring_models/block/column/gothic/column_gothic_base.json")),
            Map.entry("column_gothic/pillar", new Identifier(Erydon.MOD_ID, "authoring_models/block/column/gothic/column_gothic_pillar.json")),
            Map.entry("column_gothic/capital", new Identifier(Erydon.MOD_ID, "authoring_models/block/column/gothic/column_gothic_capital.json")),
            Map.entry("alcove_georgian/back", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_georgian_single_back.json")),
            Map.entry("alcove_georgian/sides", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_georgian_single_sides.json")),
            Map.entry("alcove_georgian/base", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_georgian_single_base.json")),
            Map.entry("alcove_georgian/top", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_georgian_single_top.json")),
            Map.entry("alcove_georgian/icon", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_georgian_icon.json")),
            Map.entry("alcove_georgian/double_side_left", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_georgian_double_side_left.json")),
            Map.entry("alcove_georgian/double_side_right", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_georgian_double_side_right.json")),
            Map.entry("alcove_georgian/double_top_left", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_georgian_double_top_left.json")),
            Map.entry("alcove_georgian/double_top_right", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_georgian_double_top_right.json")),
            Map.entry("alcove_georgian/triple_side_left", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_georgian_triple_side_left.json")),
            Map.entry("alcove_georgian/triple_side_center", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_georgian_triple_side_center.json")),
            Map.entry("alcove_georgian/triple_side_right", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_georgian_triple_side_right.json")),
            Map.entry("alcove_georgian/triple_top_left", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_georgian_triple_top_left.json")),
            Map.entry("alcove_georgian/triple_top_center", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_georgian_triple_top_center.json")),
            Map.entry("alcove_georgian/triple_top_right", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_georgian_triple_top_right.json")),
            Map.entry("alcove_gothic/back", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_gothic_single_back.json")),
            Map.entry("alcove_gothic/sides", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_gothic_single_sides.json")),
            Map.entry("alcove_gothic/base", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_gothic_single_base.json")),
            Map.entry("alcove_gothic/top", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_gothic_single_top.json")),
            Map.entry("alcove_gothic/icon", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_gothic_icon.json")),
            Map.entry("alcove_gothic/double_side_left", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_gothic_double_side_left.json")),
            Map.entry("alcove_gothic/double_side_right", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_gothic_double_side_right.json")),
            Map.entry("alcove_gothic/double_top_left", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_gothic_double_top_left.json")),
            Map.entry("alcove_gothic/double_top_right", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_gothic_double_top_right.json")),
            Map.entry("alcove_gothic/triple_side_left", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_gothic_triple_side_left.json")),
            Map.entry("alcove_gothic/triple_side_center", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_gothic_triple_side_center.json")),
            Map.entry("alcove_gothic/triple_side_right", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_gothic_triple_side_right.json")),
            Map.entry("alcove_gothic/triple_top_left", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_gothic_triple_top_left.json")),
            Map.entry("alcove_gothic/triple_top_center", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_gothic_triple_top_center.json")),
            Map.entry("alcove_gothic/triple_top_right", new Identifier(Erydon.MOD_ID, "authoring_models/block/alcove/alcove_gothic_triple_top_right.json")),
            Map.entry("arch_gothic/corner_small", new Identifier(Erydon.MOD_ID, "authoring_models/block/arch/gothic/arch_gothic_corner_small.json")),
            Map.entry("arch_gothic/corner_medium", new Identifier(Erydon.MOD_ID, "authoring_models/block/arch/gothic/arch_gothic_corner_medium.json")),
            Map.entry("arch_gothic/corner_large_upper", new Identifier(Erydon.MOD_ID, "authoring_models/block/arch/gothic/arch_gothic_corner_large_upper.json")),
            Map.entry("arch_gothic/corner_large_lower", new Identifier(Erydon.MOD_ID, "authoring_models/block/arch/gothic/arch_gothic_corner_large_lower.json")),
            Map.entry("arch_gothic/side_small", new Identifier(Erydon.MOD_ID, "authoring_models/block/arch/gothic/arch_gothic_side_small.json")),
            Map.entry("arch_gothic/side_medium", new Identifier(Erydon.MOD_ID, "authoring_models/block/arch/gothic/arch_gothic_side_medium.json")),
            Map.entry("arch_gothic/side_large", new Identifier(Erydon.MOD_ID, "authoring_models/block/arch/gothic/arch_gothic_side_large.json")),
            Map.entry("arch_gothic/top_large", new Identifier(Erydon.MOD_ID, "authoring_models/block/arch/gothic/arch_gothic_top_large.json")),
            Map.entry("arch_gothic/icon", new Identifier(Erydon.MOD_ID, "authoring_models/block/arch/gothic/arch_gothic_icon.json")),
            Map.entry("wall_georgian_slope/27_upper", new Identifier(Erydon.MOD_ID, "authoring_models/block/wall/georgian/wall_georgian_27_upper.json")),
            Map.entry("wall_georgian_slope/27_lower", new Identifier(Erydon.MOD_ID, "authoring_models/block/wall/georgian/wall_georgian_27_lower.json")),
            Map.entry("wall_georgian_slope/27_lower_onramp", new Identifier(Erydon.MOD_ID, "authoring_models/block/wall/georgian/wall_georgian_27_lower_onramp.json")),
            Map.entry("wall_georgian_slope/27_upper_offramp", new Identifier(Erydon.MOD_ID, "authoring_models/block/wall/georgian/wall_georgian_27_upper_offramp.json")),
            Map.entry("wall_georgian_slope/45", new Identifier(Erydon.MOD_ID, "authoring_models/block/wall/georgian/wall_georgian_45.json")),
            Map.entry("wall_georgian_slope/45_onramp", new Identifier(Erydon.MOD_ID, "authoring_models/block/wall/georgian/wall_georgian_45_onramp.json")),
            Map.entry("wall_georgian_slope/45_offramp", new Identifier(Erydon.MOD_ID, "authoring_models/block/wall/georgian/wall_georgian_45_offramp.json"))
    );
    private static final float EPSILON = 0.0005F;
    static final String SHARED_GEOMETRY_MODE_PROPERTY = "erydon.shared_geometry.mode";

    private static boolean isGothicColumnKey(String modelKey) {
        return modelKey.startsWith("column_gothic/");
    }

    private static boolean isSharedRawFamilyKey(String modelKey) {
        return modelKey.startsWith("column_gothic/")
                || modelKey.startsWith("alcove_georgian/")
                || modelKey.startsWith("alcove_gothic/")
                || modelKey.startsWith("arch_gothic/");
    }

    public static CompletableFuture<PreparedModels> load(ResourceManager resourceManager, Executor executor) {
        SharedGeometryMode mode = SharedGeometryMode.configured();
        ErydonSharedGeometryMetrics.beginReload(mode.configValue);
        return CompletableFuture.supplyAsync(() -> loadPreparedModels(resourceManager, mode), executor);
    }

    private static PreparedModels loadPreparedModels(ResourceManager resourceManager, SharedGeometryMode mode) {
        Map<String, RawModelData> models = new LinkedHashMap<>();
        for (Map.Entry<String, Identifier> entry : AUTHORING_MODELS.entrySet()) {
            // Georgian slope geometry is registered here for complete raw-model
            // source auditing, but its dedicated renderer owns parsing and reuse.
            if (!isSharedRawFamilyKey(entry.getKey())) {
                continue;
            }
            Optional<Resource> resource = resourceManager.getResource(entry.getValue());
            if (resource.isEmpty()) {
                Erydon.LOGGER.warn("[{}] Missing raw authoring model {}.", Erydon.MOD_ID, entry.getValue());
                ErydonSharedGeometryMetrics.resourceOpened(
                        entry.getValue(), "missing", 0L, false, isGothicColumnKey(entry.getKey()));
                continue;
            }

            long openStarted = System.nanoTime();
            try (BufferedReader reader = resource.get().getReader()) {
                long openNanos = System.nanoTime() - openStarted;
                ErydonSharedGeometryMetrics.resourceOpened(
                        entry.getValue(), resource.get().getResourcePackName(), openNanos, true,
                        isGothicColumnKey(entry.getKey()));
                long parseStarted = System.nanoTime();
                JsonElement root = JsonParser.parseReader(reader);
                RawModelData parsed = RawModelData.parse(entry.getKey(), entry.getValue(), root);
                models.put(entry.getKey(), parsed);
                ErydonSharedGeometryMetrics.modelParsed(
                        entry.getValue(), System.nanoTime() - parseStarted, true,
                        isGothicColumnKey(entry.getKey()));
            } catch (IOException | RuntimeException exception) {
                ErydonSharedGeometryMetrics.modelParsed(
                        entry.getValue(), 0L, false, isGothicColumnKey(entry.getKey()));
                Erydon.LOGGER.warn("[{}] Failed to load raw authoring model {}.", Erydon.MOD_ID, entry.getValue(), exception);
            }
        }

        ModelOverrideIndex overrides = mode == SharedGeometryMode.SHARED_GEOMETRY
                ? ModelOverrideIndex.scan(resourceManager)
                : ModelOverrideIndex.EMPTY;
        return new PreparedModels(models, mode, overrides);
    }

    @Override
    public void onInitializeModelLoader(PreparedModels data, ModelLoadingPlugin.Context pluginContext) {
        pluginContext.resolveModel().register(context -> {
            RawComponent component = RawComponent.from(context.id());
            if (component == null) {
                return null;
            }

            RawModelData model = data.models.get(component.modelKey);
            if (model == null || model.elements.isEmpty()) {
                return null;
            }

            if (data.mode == SharedGeometryMode.SHARED_GEOMETRY) {
                ModelOverrideDecision decision = data.overrides.decision(context.id(), component);
                if (decision.structuralFallback) {
                    ErydonSharedGeometryMetrics.structuralOverrideFallback(context.id().toString());
                    return null;
                }
                SharedGeometry geometry = data.sharedGeometries.get(component.modelKey);
                if (geometry != null) {
                    return new RawUnbakedModel(
                            model,
                            decision.materialBinding,
                            geometry,
                            new AssemblyKey(component.modelKey)
                    );
                }
            }

            return new RawUnbakedModel(
                    model,
                    new MaterialBinding(component.textureId, component.textureId),
                    null,
                    new AssemblyKey(component.modelKey)
            );
        });
    }

    public static final class PreparedModels {
        private final Map<String, RawModelData> models;
        private final Map<String, SharedGeometry> sharedGeometries;
        private final SharedGeometryMode mode;
        private final ModelOverrideIndex overrides;

        private PreparedModels(Map<String, RawModelData> models,
                               SharedGeometryMode mode,
                               ModelOverrideIndex overrides) {
            this.models = Map.copyOf(models);
            Map<String, SharedGeometry> geometry = new LinkedHashMap<>();
            if (mode == SharedGeometryMode.SHARED_GEOMETRY) {
                models.forEach((modelKey, model) -> {
                    geometry.put(modelKey, new SharedGeometry(
                            new GeometryKey(model.sourceId.toString()), model));
                });
            }
            this.sharedGeometries = Map.copyOf(geometry);
            this.mode = mode;
            this.overrides = overrides;
        }
    }

    private static final class RawComponent {
        private final String modelKey;
        private final Identifier textureId;

        private RawComponent(String modelKey, Identifier textureId) {
            this.modelKey = modelKey;
            this.textureId = textureId;
        }

        private boolean gothicColumn() {
            return isGothicColumnKey(modelKey);
        }

        private Identifier parentModelId() {
            int separator = modelKey.indexOf('/');
            String family = modelKey.substring(0, separator);
            String suffix = modelKey.substring(separator + 1);
            String path = switch (family) {
                case "column_gothic" -> "block/column/gothic/column_gothic_" + suffix;
                case "alcove_georgian" -> "block/alcove/georgian/alcove_georgian_" + suffix;
                case "alcove_gothic" -> "block/alcove/gothic/alcove_gothic_" + suffix;
                case "arch_gothic" -> "block/arch/gothic/arch_gothic_" + suffix;
                default -> throw new IllegalStateException("Unexpected raw-model family " + family);
            };
            return new Identifier(Erydon.MOD_ID, path);
        }

        private Identifier parentResourceId() {
            Identifier parent = parentModelId();
            return new Identifier(parent.getNamespace(), "models/" + parent.getPath() + ".json");
        }

        private static RawComponent from(Identifier id) {
            if (!Erydon.MOD_ID.equals(id.getNamespace())) {
                return null;
            }

            Matcher matcher = GOTHIC_COLUMN_COMPONENT.matcher(id.getPath());
            if (matcher.matches()) {
                String material = matcher.group(1);
                String suffix = matcher.group(2);
                boolean aged = matcher.group(3) != null;
                return new RawComponent("column_gothic/" + suffix, textureId(material, aged));
            }

            matcher = GEORGIAN_ALCOVE_COMPONENT.matcher(id.getPath());
            if (matcher.matches()) {
                String textureBase = matcher.group(1);
                String suffix = matcher.group(2);
                boolean aged = matcher.group(3) != null;
                return new RawComponent("alcove_georgian/" + suffix, textureId(textureBase, aged));
            }

            matcher = GOTHIC_ALCOVE_COMPONENT.matcher(id.getPath());
            if (matcher.matches()) {
                String textureBase = matcher.group(1);
                String suffix = matcher.group(2);
                boolean aged = matcher.group(3) != null;
                return new RawComponent("alcove_gothic/" + suffix, textureId(textureBase, aged));
            }

            matcher = GOTHIC_ARCH_COMPONENT.matcher(id.getPath());
            if (matcher.matches()) {
                String textureBase = matcher.group(1);
                String suffix = matcher.group(2);
                boolean aged = matcher.group(3) != null;
                return new RawComponent("arch_gothic/" + suffix, textureId(textureBase, aged));
            }

            return null;
        }

        private static Identifier textureId(String textureBase, boolean aged) {
            String texturePath = "block/" + textureBase + "_block" + (aged ? "_aged" : "");
            return new Identifier(Erydon.MOD_ID, texturePath);
        }
    }

    private static final class RawUnbakedModel implements UnbakedModel {
        private final RawModelData data;
        private final MaterialBinding materialBinding;
        private final SharedGeometry sharedGeometry;
        private final AssemblyKey assemblyKey;

        private RawUnbakedModel(RawModelData data,
                                MaterialBinding materialBinding,
                                SharedGeometry sharedGeometry,
                                AssemblyKey assemblyKey) {
            this.data = data;
            this.materialBinding = materialBinding;
            this.sharedGeometry = sharedGeometry;
            this.assemblyKey = assemblyKey;
        }

        @Override
        public Collection<Identifier> getModelDependencies() {
            return List.of();
        }

        @Override
        public void setParents(Function<Identifier, UnbakedModel> modelLoader) {
        }

        @Override
        public BakedModel bake(Baker baker,
                               Function<SpriteIdentifier, Sprite> textureGetter,
                               ModelBakeSettings rotationContainer,
                               Identifier modelId) {
            Sprite sprite = textureGetter.apply(new SpriteIdentifier(
                    PlayerScreenHandler.BLOCK_ATLAS_TEXTURE,
                    materialBinding.surfaceTextureId));
            Sprite particle = textureGetter.apply(new SpriteIdentifier(
                    PlayerScreenHandler.BLOCK_ATLAS_TEXTURE,
                    materialBinding.particleTextureId));
            Map<String, Sprite> authoredSprites = new HashMap<>();
            for (Map.Entry<String, Identifier> entry : data.textures.entrySet()) {
                authoredSprites.put(entry.getKey(),
                        textureGetter.apply(new SpriteIdentifier(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE, entry.getValue())));
            }
            if (sharedGeometry != null) {
                return sharedGeometry.materialize(
                        materialBinding,
                        assemblyKey,
                        modelId,
                        sprite,
                        particle,
                        authoredSprites
                );
            }
            return RawBakedModel.bake(
                    data,
                    sprite,
                    particle,
                    authoredSprites,
                    modelId,
                    materialBinding.stableValue(),
                    BakePurpose.BASELINE
            );
        }
    }

    private static final class RawBakedModel implements BakedModel {
        private final List<BakedQuad> unculled;
        private final Map<Direction, List<BakedQuad>> culled;
        private final Sprite particle;

        private RawBakedModel(List<BakedQuad> unculled, Map<Direction, List<BakedQuad>> culled, Sprite particle) {
            this.unculled = unculled;
            this.culled = culled;
            this.particle = particle;
        }

        private static RawBakedModel bake(RawModelData data,
                                          Sprite fallbackSprite,
                                          Sprite particleSprite,
                                          Map<String, Sprite> authoredSprites,
                                          Identifier modelId,
                                          String materialBinding,
                                          BakePurpose purpose) {
            long startedNanos = System.nanoTime();
            List<BakedQuad> unculled = new ArrayList<>();
            Map<Direction, List<BakedQuad>> culled = new EnumMap<>(Direction.class);
            for (Direction direction : Direction.values()) {
                culled.put(direction, new ArrayList<>());
            }

            List<RawSurface> surfaces = rawSurfaces(data);
            for (RawSurface surface : surfaces) {
                Sprite sprite = spriteFor(surface.face, fallbackSprite, authoredSprites);
                BakedQuad quad = bakeQuad(surface, sprite);
                if (surface.cullFace != null) {
                    culled.get(surface.cullFace).add(quad);
                } else {
                    unculled.add(quad);
                }
            }

            Map<Direction, List<BakedQuad>> immutableCulled = new EnumMap<>(Direction.class);
            for (Direction direction : Direction.values()) {
                immutableCulled.put(direction, List.copyOf(culled.get(direction)));
            }
            RawBakedModel baked = new RawBakedModel(
                    List.copyOf(unculled), immutableCulled, particleSprite);
            long durationNanos = System.nanoTime() - startedNanos;
            if (purpose == BakePurpose.BASELINE) {
                ErydonSharedGeometryMetrics.baselineGeometryCreated(
                        baked,
                        data.geometryKey(),
                        modelId.toString(),
                        materialBinding,
                        surfaces.size(),
                        durationNanos
                );
            } else {
                ErydonSharedGeometryMetrics.axiomFallbackCreated(
                        data.geometryKey(), surfaces.size(), durationNanos);
            }
            return baked;
        }

        private static Sprite spriteFor(RawFace face, Sprite fallbackSprite, Map<String, Sprite> authoredSprites) {
            String key = face.textureKey;
            if (key == null || key.isBlank()) {
                return fallbackSprite;
            }
            if (key.startsWith("#")) {
                key = key.substring(1);
            }
            if (key.equals("missing") || key.equals("stone") || key.equals("all") || key.equals("particle")) {
                return fallbackSprite;
            }
            return authoredSprites.getOrDefault(key, fallbackSprite);
        }

        private static BakedQuad bakeQuad(RawSurface surface, Sprite sprite) {
            int[] data = new int[32];
            for (int vertex = 0; vertex < 4; vertex++) {
                writeVertex(
                        data,
                        vertex,
                        surface.vertices[vertex],
                        surface.uv[vertex * 2],
                        surface.uv[vertex * 2 + 1],
                        sprite
                );
            }
            return new BakedQuad(data, -1, surface.nominalFace, sprite, true);
        }

        private static float[] rectUv(float[] uv) {
            return new float[]{uv[0], uv[3], uv[2], uv[3], uv[2], uv[1], uv[0], uv[1]};
        }

        private static void applyUvOffset(float[] uv, float[] offset) {
            for (int vertex = 0; vertex < 4; vertex++) {
                uv[vertex * 2] += offset[0];
                uv[vertex * 2 + 1] += offset[1];
            }
        }

        private static void writeVertex(int[] data, int vertex, Vector3f position, float u, float v, Sprite sprite) {
            int offset = vertex * 8;
            data[offset] = Float.floatToRawIntBits(position.x / 16.0F);
            data[offset + 1] = Float.floatToRawIntBits(position.y / 16.0F);
            data[offset + 2] = Float.floatToRawIntBits(position.z / 16.0F);
            data[offset + 3] = -1;
            data[offset + 4] = Float.floatToRawIntBits(sprite.getFrameU(u));
            data[offset + 5] = Float.floatToRawIntBits(sprite.getFrameV(v));
        }

        private static Direction closestDirection(Vector3f[] vertices) {
            Vector3f a = new Vector3f(vertices[1]).sub(vertices[0]);
            Vector3f b = new Vector3f(vertices[2]).sub(vertices[0]);
            Vector3f normal = a.cross(b);
            if (normal.lengthSquared() <= EPSILON) {
                return Direction.UP;
            }
            return Direction.getFacing(normal.x, normal.y, normal.z);
        }

        private static boolean isOnCullBoundary(Direction cullFace, Vector3f[] vertices) {
            float expected = cullFace.getDirection() == Direction.AxisDirection.POSITIVE ? 16.0F : 0.0F;
            for (Vector3f vertex : vertices) {
                float value = switch (cullFace.getAxis()) {
                    case X -> vertex.x;
                    case Y -> vertex.y;
                    case Z -> vertex.z;
                };
                if (Math.abs(value - expected) > EPSILON) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
            if (face == null) {
                return unculled;
            }
            return culled.getOrDefault(face, Collections.emptyList());
        }

        @Override
        public boolean useAmbientOcclusion() {
            return true;
        }

        @Override
        public boolean hasDepth() {
            return true;
        }

        @Override
        public boolean isSideLit() {
            return true;
        }

        @Override
        public boolean isBuiltin() {
            return false;
        }

        @Override
        public Sprite getParticleSprite() {
            return particle;
        }

        @Override
        public ModelTransformation getTransformation() {
            return ModelTransformation.NONE;
        }

        @Override
        public ModelOverrideList getOverrides() {
            return ModelOverrideList.EMPTY;
        }
    }

    private static final class SharedGeometry {
        private final GeometryKey key;
        private final RawModelData data;
        private SharedMesh baked;

        private SharedGeometry(GeometryKey key, RawModelData data) {
            this.key = key;
            this.data = data;
        }

        private SharedRawBakedModel materialize(MaterialBinding materialBinding,
                                                AssemblyKey assemblyKey,
                                                Identifier modelId,
                                                Sprite surfaceSprite,
                                                Sprite particleSprite,
                                                Map<String, Sprite> authoredSprites) {
            SharedMesh sharedMesh = bake(modelId);
            long startedNanos = System.nanoTime();
            SharedRawBakedModel model = new SharedRawBakedModel(
                    sharedMesh,
                    data,
                    key,
                    assemblyKey,
                    materialBinding,
                    modelId,
                    surfaceSprite,
                    particleSprite,
                    authoredSprites
            );
            ErydonSharedGeometryMetrics.materialModelBaked(
                    sharedMesh.mesh,
                    key.value,
                    modelId.toString(),
                    materialBinding.stableValue(),
                    sharedMesh.surfaceCount,
                    System.nanoTime() - startedNanos
            );
            return model;
        }

        private synchronized SharedMesh bake(Identifier modelId) {
            if (baked != null) {
                ErydonSharedGeometryMetrics.geometryCacheLookup(
                        true, key.value, modelId.toString(), baked.mesh);
                return baked;
            }

            ErydonSharedGeometryMetrics.geometryCacheLookup(
                    false, key.value, modelId.toString(), null);
            long startedNanos = System.nanoTime();
            List<RawSurface> surfaces = rawSurfaces(data);
            Renderer renderer = RendererAccess.INSTANCE.getRenderer();
            if (renderer == null) {
                throw new IllegalStateException(
                        "No Fabric Renderer API implementation is active while baking " + key.value);
            }

            MeshBuilder builder = renderer.meshBuilder();
            QuadEmitter emitter = builder.getEmitter();
            for (RawSurface surface : surfaces) {
                for (int vertex = 0; vertex < 4; vertex++) {
                    Vector3f position = surface.vertices[vertex];
                    emitter.pos(vertex,
                            position.x / 16.0F,
                            position.y / 16.0F,
                            position.z / 16.0F);
                    emitter.uv(vertex,
                            surface.uv[vertex * 2] / 16.0F,
                            surface.uv[vertex * 2 + 1] / 16.0F);
                }
                emitter.color(-1, -1, -1, -1);
                emitter.cullFace(surface.cullFace);
                emitter.nominalFace(surface.nominalFace);
                emitter.emit();
            }

            SharedMesh created = new SharedMesh(builder.build(), surfaces.size());
            baked = created;
            ErydonSharedGeometryMetrics.sharedGeometryCreated(
                    created.mesh,
                    key.value,
                    created.surfaceCount,
                    System.nanoTime() - startedNanos
            );
            return created;
        }
    }

    private static final class SharedRawBakedModel
            implements BakedModel, SharedGeometryChildModel {
        private final SharedMesh sharedMesh;
        private final RawModelData data;
        private final GeometryKey geometryKey;
        private final AssemblyKey assemblyKey;
        private final MaterialBinding materialBinding;
        private final Identifier modelId;
        private final Sprite surfaceSprite;
        private final Sprite particleSprite;
        private final Map<String, Sprite> authoredSprites;
        private final MaterialTransform materialTransform;
        private volatile RawBakedModel axiomFallback;

        private SharedRawBakedModel(SharedMesh sharedMesh,
                                    RawModelData data,
                                    GeometryKey geometryKey,
                                    AssemblyKey assemblyKey,
                                    MaterialBinding materialBinding,
                                    Identifier modelId,
                                    Sprite surfaceSprite,
                                    Sprite particleSprite,
                                    Map<String, Sprite> authoredSprites) {
            this.sharedMesh = sharedMesh;
            this.data = data;
            this.geometryKey = geometryKey;
            this.assemblyKey = assemblyKey;
            this.materialBinding = materialBinding;
            this.modelId = modelId;
            this.surfaceSprite = surfaceSprite;
            this.particleSprite = particleSprite;
            this.authoredSprites = Map.copyOf(authoredSprites);
            this.materialTransform = new MaterialTransform(surfaceSprite);
        }

        @Override
        public boolean isVanillaAdapter() {
            return false;
        }

        @Override
        public void emitBlockQuads(BlockRenderView view,
                                   BlockState state,
                                   BlockPos pos,
                                   Supplier<Random> randomSupplier,
                                   RenderContext context) {
            emitSharedGeometry(context);
        }

        @Override
        public void emitSharedGeometry(RenderContext context) {
            context.pushTransform(materialTransform);
            try {
                sharedMesh.mesh.outputTo(context.getEmitter());
                ErydonSharedGeometryMetrics.blockEmitted(sharedMesh.surfaceCount);
            } finally {
                context.popTransform();
            }
        }

        @Override
        public void emitItemQuads(ItemStack stack,
                                  Supplier<Random> randomSupplier,
                                  RenderContext context) {
            emitSharedGeometry(context);
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
            return axiomFallback().getQuads(state, face, random);
        }

        private RawBakedModel axiomFallback() {
            RawBakedModel fallback = axiomFallback;
            if (fallback != null) {
                return fallback;
            }
            synchronized (this) {
                fallback = axiomFallback;
                if (fallback == null) {
                    fallback = RawBakedModel.bake(
                            data,
                            surfaceSprite,
                            particleSprite,
                            authoredSprites,
                            modelId,
                            materialBinding.stableValue(),
                            BakePurpose.AXIOM_FALLBACK
                    );
                    axiomFallback = fallback;
                }
                return fallback;
            }
        }

        @Override
        public boolean useAmbientOcclusion() {
            return true;
        }

        @Override
        public boolean hasDepth() {
            return true;
        }

        @Override
        public boolean isSideLit() {
            return true;
        }

        @Override
        public boolean isBuiltin() {
            return false;
        }

        @Override
        public Sprite getParticleSprite() {
            return particleSprite;
        }

        @Override
        public ModelTransformation getTransformation() {
            return ModelTransformation.NONE;
        }

        @Override
        public ModelOverrideList getOverrides() {
            return ModelOverrideList.EMPTY;
        }
    }

    private record MaterialTransform(Sprite sprite) implements RenderContext.QuadTransform {
        @Override
        public boolean transform(MutableQuadView quad) {
            quad.spriteBake(sprite, MutableQuadView.BAKE_NORMALIZED);
            return true;
        }
    }

    private record SharedMesh(Mesh mesh, int surfaceCount) {
    }

    private record GeometryKey(String value) {
    }

    private record MaterialBinding(Identifier surfaceTextureId, Identifier particleTextureId) {
        private String stableValue() {
            return "particle=" + particleTextureId + ";surface=" + surfaceTextureId;
        }
    }

    private record AssemblyKey(String value) {
    }

    private record RawSurface(Vector3f[] vertices,
                              float[] uv,
                              Direction nominalFace,
                              Direction cullFace,
                              RawFace face) {
    }

    private enum BakePurpose {
        BASELINE,
        AXIOM_FALLBACK
    }

    private enum SharedGeometryMode {
        BASELINE("baseline"),
        SHARED_GEOMETRY("shared_geometry");

        private final String configValue;

        SharedGeometryMode(String configValue) {
            this.configValue = configValue;
        }

        private static SharedGeometryMode configured() {
            String configured = System.getProperty(
                    SHARED_GEOMETRY_MODE_PROPERTY, BASELINE.configValue).trim();
            for (SharedGeometryMode candidate : values()) {
                if (candidate.configValue.equalsIgnoreCase(configured)) {
                    return candidate;
                }
            }
            Erydon.LOGGER.warn(
                    "[{}] Unsupported shared-geometry mode '{}'; using baseline.",
                    Erydon.MOD_ID,
                    configured
            );
            return BASELINE;
        }
    }

    private static final class ModelOverrideIndex {
        private static final ModelOverrideIndex EMPTY = new ModelOverrideIndex(Map.of(), false);
        private final Map<String, ModelOverrideDecision> decisions;
        private final boolean strict;

        private ModelOverrideIndex(Map<String, ModelOverrideDecision> decisions, boolean strict) {
            this.decisions = Map.copyOf(decisions);
            this.strict = strict;
        }

        private static ModelOverrideIndex scan(ResourceManager resourceManager) {
            String builtInPack = mostFrequentAuthoringPack(resourceManager);
            Set<String> unsafeParentKeys = new HashSet<>();
            for (String modelKey : AUTHORING_MODELS.keySet()) {
                if (!isSharedRawFamilyKey(modelKey)) {
                    continue;
                }
                RawComponent representative = representative(modelKey);
                Identifier resourceId = representative.parentResourceId();
                if (!isOverridden(resourceManager, resourceId, builtInPack)) {
                    continue;
                }
                JsonObject parent = readActiveModel(resourceManager, resourceId);
                if (!safeSharedParent(parent)) {
                    unsafeParentKeys.add(modelKey);
                }
            }

            Map<String, ModelOverrideDecision> decisions = new LinkedHashMap<>();
            Map<Identifier, Resource> activeModels = new LinkedHashMap<>();
            for (String root : List.of(
                    "models/block/column/gothic",
                    "models/block/alcove/georgian",
                    "models/block/alcove/gothic",
                    "models/block/arch/gothic")) {
                activeModels.putAll(resourceManager.findResources(
                        root, id -> id.getPath().endsWith(".json")));
            }
            for (Identifier resourceId : activeModels.keySet()) {
                Identifier modelId = modelIdForResource(resourceId);
                RawComponent component = modelId == null ? null : RawComponent.from(modelId);
                if (component == null) {
                    continue;
                }

                MaterialBinding defaultBinding = new MaterialBinding(
                        component.textureId, component.textureId);
                if (unsafeParentKeys.contains(component.modelKey)) {
                    decisions.put(modelId.toString(), ModelOverrideDecision.fallback(defaultBinding));
                    continue;
                }

                if (!isOverridden(resourceManager, resourceId, builtInPack)) {
                    decisions.put(modelId.toString(), ModelOverrideDecision.shared(defaultBinding));
                    continue;
                }

                JsonObject child = readActiveModel(resourceManager, resourceId);
                if (!safeSharedChild(child, component.parentModelId().toString())) {
                    decisions.put(modelId.toString(), ModelOverrideDecision.fallback(defaultBinding));
                    continue;
                }

                MaterialBinding binding = bindingFromOverride(child);
                decisions.put(modelId.toString(), binding == null
                        ? ModelOverrideDecision.fallback(defaultBinding)
                        : ModelOverrideDecision.shared(binding));
            }

            long fallbacks = decisions.values().stream()
                    .filter(decision -> decision.structuralFallback)
                    .count();
            Erydon.LOGGER.info(
                    "[{}] Shared raw-family override scan: models={}, structuralFallbacks={}, sourcePack={}.",
                    Erydon.MOD_ID,
                    decisions.size(),
                    fallbacks,
                    builtInPack == null ? "unknown" : builtInPack
            );
            return new ModelOverrideIndex(decisions, true);
        }

        private static RawComponent representative(String modelKey) {
            String material = "shared_geometry_probe";
            Identifier id;
            if (modelKey.startsWith("column_gothic/")) {
                id = new Identifier(Erydon.MOD_ID, "block/column/gothic/" + material
                        + "_column_gothic_" + modelKey.substring("column_gothic/".length()));
            } else if (modelKey.startsWith("alcove_georgian/")) {
                id = new Identifier(Erydon.MOD_ID, "block/alcove/georgian/" + material
                        + "_alcove_georgian_" + modelKey.substring("alcove_georgian/".length()));
            } else if (modelKey.startsWith("alcove_gothic/")) {
                id = new Identifier(Erydon.MOD_ID, "block/alcove/gothic/" + material
                        + "_alcove_gothic_" + modelKey.substring("alcove_gothic/".length()));
            } else if (modelKey.startsWith("arch_gothic/")) {
                id = new Identifier(Erydon.MOD_ID, "block/arch/gothic/" + material
                        + "_arch_gothic_" + modelKey.substring("arch_gothic/".length()));
            } else {
                throw new IllegalArgumentException("Unexpected raw-model key " + modelKey);
            }
            RawComponent component = RawComponent.from(id);
            if (component == null) {
                throw new IllegalStateException("Could not construct raw-model representative for " + modelKey);
            }
            return component;
        }

        private ModelOverrideDecision decision(Identifier modelId, RawComponent component) {
            ModelOverrideDecision decision = decisions.get(modelId.toString());
            if (decision != null) {
                return decision;
            }
            MaterialBinding binding = new MaterialBinding(component.textureId, component.textureId);
            return strict
                    ? ModelOverrideDecision.fallback(binding)
                    : ModelOverrideDecision.shared(binding);
        }

        private static String mostFrequentAuthoringPack(ResourceManager resourceManager) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Map.Entry<String, Identifier> entry : AUTHORING_MODELS.entrySet()) {
                if (!isSharedRawFamilyKey(entry.getKey())) {
                    continue;
                }
                Identifier resourceId = entry.getValue();
                for (Resource resource : resourceManager.getAllResources(resourceId)) {
                    counts.merge(resource.getResourcePackName(), 1, Integer::sum);
                }
            }
            String selected = null;
            int maximum = -1;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > maximum) {
                    selected = entry.getKey();
                    maximum = entry.getValue();
                }
            }
            return selected;
        }

        private static boolean isOverridden(ResourceManager resourceManager,
                                            Identifier resourceId,
                                            String builtInPack) {
            List<Resource> resources = resourceManager.getAllResources(resourceId);
            if (resources.size() > 1) {
                return true;
            }
            Optional<Resource> active = resourceManager.getResource(resourceId);
            return active.isEmpty()
                    || (builtInPack != null
                    && !builtInPack.equals(active.get().getResourcePackName()));
        }

        private static JsonObject readActiveModel(ResourceManager resourceManager, Identifier resourceId) {
            Optional<Resource> resource = resourceManager.getResource(resourceId);
            if (resource.isEmpty()) {
                return null;
            }
            try (BufferedReader reader = resource.get().getReader()) {
                JsonElement root = JsonParser.parseReader(reader);
                return root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
            } catch (IOException | RuntimeException exception) {
                Erydon.LOGGER.warn(
                        "[{}] Unable to inspect active model override {}; using vanilla fallback.",
                        Erydon.MOD_ID,
                        resourceId,
                        exception
                );
                return null;
            }
        }

        private static Identifier modelIdForResource(Identifier resourceId) {
            String path = resourceId.getPath();
            if (!path.startsWith("models/") || !path.endsWith(".json")) {
                return null;
            }
            return new Identifier(
                    resourceId.getNamespace(),
                    path.substring("models/".length(), path.length() - ".json".length())
            );
        }

        private static boolean safeSharedParent(JsonObject model) {
            if (model == null
                    || !onlyKeys(model, Set.of("parent", "textures", "elements"))
                    || !hasString(model, "parent", "minecraft:block/block")) {
                return false;
            }
            JsonArray elements = model.getAsJsonArray("elements");
            return elements != null && elements.isEmpty();
        }

        private static boolean safeSharedChild(JsonObject model, String expectedParent) {
            return model != null
                    && onlyKeys(model, Set.of("parent", "textures"))
                    && hasString(model, "parent", expectedParent);
        }

        private static boolean onlyKeys(JsonObject object, Set<String> allowed) {
            return object.keySet().stream().allMatch(allowed::contains);
        }

        private static boolean hasString(JsonObject object, String key, String expected) {
            JsonElement element = object.get(key);
            return element != null
                    && element.isJsonPrimitive()
                    && element.getAsJsonPrimitive().isString()
                    && expected.equals(element.getAsString());
        }

        private static MaterialBinding bindingFromOverride(JsonObject model) {
            JsonObject textures = model.getAsJsonObject("textures");
            if (textures == null) {
                return null;
            }
            Identifier surface = resolveTextureSlot(textures, "stone", new HashSet<>());
            if (surface == null) {
                surface = resolveTextureSlot(textures, "all", new HashSet<>());
            }
            if (surface == null) {
                return null;
            }
            Identifier particle = resolveTextureSlot(textures, "particle", new HashSet<>());
            return particle == null ? null : new MaterialBinding(surface, particle);
        }

        private static Identifier resolveTextureSlot(JsonObject textures,
                                                     String slot,
                                                     Set<String> visited) {
            if (!visited.add(slot)) {
                return null;
            }
            JsonElement element = textures.get(slot);
            if (element == null
                    || !element.isJsonPrimitive()
                    || !element.getAsJsonPrimitive().isString()) {
                return null;
            }
            String value = element.getAsString();
            if (value.startsWith("#")) {
                return resolveTextureSlot(textures, value.substring(1), visited);
            }
            try {
                return value.contains(":")
                        ? new Identifier(value)
                        : new Identifier(Erydon.MOD_ID, value);
            } catch (RuntimeException exception) {
                return null;
            }
        }
    }

    private static final class ModelOverrideDecision {
        private final boolean structuralFallback;
        private final MaterialBinding materialBinding;

        private ModelOverrideDecision(boolean structuralFallback,
                                      MaterialBinding materialBinding) {
            this.structuralFallback = structuralFallback;
            this.materialBinding = materialBinding;
        }

        private static ModelOverrideDecision shared(MaterialBinding materialBinding) {
            return new ModelOverrideDecision(false, materialBinding);
        }

        private static ModelOverrideDecision fallback(MaterialBinding materialBinding) {
            return new ModelOverrideDecision(true, materialBinding);
        }
    }

    private static List<RawSurface> rawSurfaces(RawModelData data) {
        List<RawSurface> surfaces = new ArrayList<>();
        for (RawElement element : data.elements) {
            for (Map.Entry<Direction, RawFace> entry : element.faces.entrySet()) {
                RawFace face = entry.getValue();
                Vector3f[] vertices = element.transformedVertices(entry.getKey());
                Direction nominalFace = RawBakedModel.closestDirection(vertices);
                float[] uv = face.uv == null
                        ? RawElement.defaultUv(vertices, nominalFace)
                        : RawBakedModel.rectUv(face.uv);
                RawBakedModel.applyUvOffset(uv, face.uvOffset);
                Direction cullFace = face.cullFace != null
                        && (face.cullBoundaryOverride
                        || RawBakedModel.isOnCullBoundary(face.cullFace, vertices))
                        ? face.cullFace
                        : null;
                surfaces.add(new RawSurface(vertices, uv, nominalFace, cullFace, face));
            }
        }
        return List.copyOf(surfaces);
    }

    static List<SurfaceSnapshot> surfaceSnapshots(String modelKey,
                                                  Identifier sourceId,
                                                  JsonElement root) {
        RawModelData data = RawModelData.parse(modelKey, sourceId, root);
        List<SurfaceSnapshot> snapshots = new ArrayList<>();
        for (RawSurface surface : rawSurfaces(data)) {
            float[][] positions = new float[4][3];
            float[][] sourceUvs = new float[4][2];
            float[][] normalizedUvs = new float[4][2];
            for (int vertex = 0; vertex < 4; vertex++) {
                positions[vertex][0] = surface.vertices[vertex].x / 16.0F;
                positions[vertex][1] = surface.vertices[vertex].y / 16.0F;
                positions[vertex][2] = surface.vertices[vertex].z / 16.0F;
                sourceUvs[vertex][0] = surface.uv[vertex * 2];
                sourceUvs[vertex][1] = surface.uv[vertex * 2 + 1];
                normalizedUvs[vertex][0] = surface.uv[vertex * 2] / 16.0F;
                normalizedUvs[vertex][1] = surface.uv[vertex * 2 + 1] / 16.0F;
            }
            snapshots.add(new SurfaceSnapshot(
                    positions,
                    sourceUvs,
                    normalizedUvs,
                    surface.nominalFace.getName(),
                    surface.cullFace == null ? null : surface.cullFace.getName()
            ));
        }
        return List.copyOf(snapshots);
    }

    static boolean sharedChildOverrideIsSafe(JsonObject model, String suffix) {
        return ModelOverrideIndex.safeSharedChild(
                model,
                Erydon.MOD_ID + ":block/column/gothic/column_gothic_" + suffix
        );
    }

    static String sharedOverrideBinding(JsonObject model) {
        MaterialBinding binding = ModelOverrideIndex.bindingFromOverride(model);
        return binding == null ? null : binding.stableValue();
    }

    static String configuredModeForTest() {
        return SharedGeometryMode.configured().configValue;
    }

    static boolean sharedGeometryEnabled() {
        return SharedGeometryMode.configured() == SharedGeometryMode.SHARED_GEOMETRY;
    }

    record SurfaceSnapshot(float[][] vertexPositions,
                           float[][] sourceUvs,
                           float[][] normalizedUvs,
                           String faceDirection,
                           String cullFace) {
    }

    private static final class RawModelData {
        private final String modelKey;
        private final Identifier sourceId;
        private final List<RawElement> elements;
        private final Map<String, Identifier> textures;

        private RawModelData(String modelKey,
                             Identifier sourceId,
                             List<RawElement> elements,
                             Map<String, Identifier> textures) {
            this.modelKey = modelKey;
            this.sourceId = sourceId;
            this.elements = List.copyOf(elements);
            this.textures = Map.copyOf(textures);
        }

        private static RawModelData parse(String modelKey, Identifier id, JsonElement root) {
            if (!root.isJsonObject()) {
                throw new IllegalArgumentException("Raw model root is not an object: " + id);
            }

            JsonObject object = root.getAsJsonObject();
            Map<String, Identifier> textures = parseTextures(object.getAsJsonObject("textures"));
            JsonArray elementArray = object.getAsJsonArray("elements");
            if (elementArray == null) {
                return new RawModelData(modelKey, id, List.of(), textures);
            }

            Map<Integer, List<RawRotation>> groupRotations = collectGroupRotations(object);
            List<RawElement> elements = new ArrayList<>();
            for (int index = 0; index < elementArray.size(); index++) {
                JsonElement element = elementArray.get(index);
                if (element != null && element.isJsonObject()) {
                    elements.add(RawElement.parse(element.getAsJsonObject(), groupRotations.getOrDefault(index, List.of())));
                }
            }
            return new RawModelData(modelKey, id, elements, textures);
        }

        @SuppressWarnings("unused")
        private static RawModelData parse(Identifier id, JsonElement root) {
            return parse(id.getPath(), id, root);
        }

        private boolean gothicColumn() {
            return isGothicColumnKey(modelKey);
        }

        private String geometryKey() {
            return sourceId.toString();
        }

        private static Map<String, Identifier> parseTextures(JsonObject textureObject) {
            if (textureObject == null) {
                return Map.of();
            }

            Map<String, Identifier> textures = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : textureObject.entrySet()) {
                if ("particle".equals(entry.getKey())
                        || entry.getValue() == null
                        || !entry.getValue().isJsonPrimitive()
                        || !entry.getValue().getAsJsonPrimitive().isString()) {
                    continue;
                }

                String value = entry.getValue().getAsString();
                if (value.startsWith("#")) {
                    continue;
                }

                textures.put(entry.getKey(), parseTextureId(value));
            }
            return textures;
        }

        private static Identifier parseTextureId(String value) {
            if (value.contains(":")) {
                return new Identifier(value);
            }
            return new Identifier(Erydon.MOD_ID, value);
        }

        private static Map<Integer, List<RawRotation>> collectGroupRotations(JsonObject model) {
            JsonArray groups = model.getAsJsonArray("groups");
            if (groups == null) {
                return Map.of();
            }

            Map<Integer, List<RawRotation>> rotationsByElement = new HashMap<>();
            for (JsonElement group : groups) {
                if (group != null && group.isJsonObject()) {
                    collectGroupRotations(group.getAsJsonObject(), List.of(), rotationsByElement);
                }
            }
            return rotationsByElement;
        }

        private static void collectGroupRotations(JsonObject group,
                                                  List<RawRotation> inherited,
                                                  Map<Integer, List<RawRotation>> rotationsByElement) {
            List<RawRotation> rotations = inherited;
            RawRotation groupRotation = RawRotation.parse(group.get("rotation"), vector3OrDefault(group.get("origin"), 8.0F, 8.0F, 8.0F));
            if (!groupRotation.identity) {
                rotations = new ArrayList<>(inherited);
                rotations.add(groupRotation);
            }

            JsonArray children = group.getAsJsonArray("children");
            if (children == null) {
                return;
            }

            for (JsonElement child : children) {
                if (child == null) {
                    continue;
                }
                if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isNumber()) {
                    int elementIndex = child.getAsInt();
                    if (!rotations.isEmpty()) {
                        List<RawRotation> elementRotations = new ArrayList<>(rotations);
                        Collections.reverse(elementRotations);
                        rotationsByElement.computeIfAbsent(elementIndex, ignored -> new ArrayList<>()).addAll(elementRotations);
                    }
                } else if (child.isJsonObject()) {
                    collectGroupRotations(child.getAsJsonObject(), rotations, rotationsByElement);
                }
            }
        }
    }

    private static final class RawElement {
        private final float[] from;
        private final float[] to;
        private final List<RawRotation> rotations;
        private final Map<Direction, RawFace> faces;

        private RawElement(float[] from, float[] to, List<RawRotation> rotations, Map<Direction, RawFace> faces) {
            this.from = from;
            this.to = to;
            this.rotations = List.copyOf(rotations);
            this.faces = Map.copyOf(faces);
        }

        private static RawElement parse(JsonObject object, List<RawRotation> groupRotations) {
            float[] from = vector3(object.get("from"), "from");
            float[] to = vector3(object.get("to"), "to");

            List<RawRotation> rotations = new ArrayList<>();
            RawRotation elementRotation = RawRotation.parse(object.get("rotation"), vector3OrDefault(object.get("origin"), 8.0F, 8.0F, 8.0F));
            if (!elementRotation.identity) {
                rotations.add(elementRotation);
            }
            rotations.addAll(groupRotations);

            Map<Direction, RawFace> faces = new EnumMap<>(Direction.class);
            JsonObject faceObject = object.getAsJsonObject("faces");
            if (faceObject != null) {
                for (Map.Entry<String, JsonElement> entry : faceObject.entrySet()) {
                    Direction direction = Direction.byName(entry.getKey());
                    if (direction != null && entry.getValue() != null && entry.getValue().isJsonObject()) {
                        faces.put(direction, RawFace.parse(entry.getValue().getAsJsonObject()));
                    }
                }
            }
            return new RawElement(from, to, rotations, faces);
        }

        private Vector3f[] transformedVertices(Direction face) {
            Vector3f[] vertices = faceVertices(face);
            if (rotations.isEmpty()) {
                return vertices;
            }

            for (int i = 0; i < vertices.length; i++) {
                Vector3f vertex = vertices[i];
                for (RawRotation rotation : rotations) {
                    vertex = rotation.transform(vertex);
                }
                vertices[i] = vertex;
            }
            return vertices;
        }

        private Vector3f[] faceVertices(Direction face) {
            float x1 = from[0];
            float y1 = from[1];
            float z1 = from[2];
            float x2 = to[0];
            float y2 = to[1];
            float z2 = to[2];
            return switch (face) {
                case NORTH -> new Vector3f[]{
                        new Vector3f(x2, y1, z1),
                        new Vector3f(x1, y1, z1),
                        new Vector3f(x1, y2, z1),
                        new Vector3f(x2, y2, z1)
                };
                case SOUTH -> new Vector3f[]{
                        new Vector3f(x1, y1, z2),
                        new Vector3f(x2, y1, z2),
                        new Vector3f(x2, y2, z2),
                        new Vector3f(x1, y2, z2)
                };
                case WEST -> new Vector3f[]{
                        new Vector3f(x1, y1, z1),
                        new Vector3f(x1, y1, z2),
                        new Vector3f(x1, y2, z2),
                        new Vector3f(x1, y2, z1)
                };
                case EAST -> new Vector3f[]{
                        new Vector3f(x2, y1, z2),
                        new Vector3f(x2, y1, z1),
                        new Vector3f(x2, y2, z1),
                        new Vector3f(x2, y2, z2)
                };
                case UP -> new Vector3f[]{
                        new Vector3f(x1, y2, z1),
                        new Vector3f(x1, y2, z2),
                        new Vector3f(x2, y2, z2),
                        new Vector3f(x2, y2, z1)
                };
                case DOWN -> new Vector3f[]{
                        new Vector3f(x1, y1, z2),
                        new Vector3f(x1, y1, z1),
                        new Vector3f(x2, y1, z1),
                        new Vector3f(x2, y1, z2)
                };
            };
        }

        private static float[] defaultUv(Vector3f[] vertices, Direction face) {
            float[] uv = new float[8];
            for (int i = 0; i < vertices.length; i++) {
                Vector3f vertex = vertices[i];
                int offset = i * 2;
                switch (face) {
                    case NORTH -> {
                        uv[offset] = 16.0F - vertex.x;
                        uv[offset + 1] = 16.0F - vertex.y;
                    }
                    case SOUTH -> {
                        uv[offset] = vertex.x;
                        uv[offset + 1] = 16.0F - vertex.y;
                    }
                    case WEST -> {
                        uv[offset] = vertex.z;
                        uv[offset + 1] = 16.0F - vertex.y;
                    }
                    case EAST -> {
                        uv[offset] = 16.0F - vertex.z;
                        uv[offset + 1] = 16.0F - vertex.y;
                    }
                    case UP -> {
                        uv[offset] = vertex.x;
                        uv[offset + 1] = vertex.z;
                    }
                    case DOWN -> {
                        uv[offset] = vertex.x;
                        uv[offset + 1] = 16.0F - vertex.z;
                    }
                }
            }
            return uv;
        }
    }

    private static final class RawFace {
        private final Direction cullFace;
        private final String textureKey;
        private final float[] uv;
        private final float[] uvOffset;
        private final boolean cullBoundaryOverride;

        private RawFace(Direction cullFace, String textureKey, float[] uv, float[] uvOffset,
                        boolean cullBoundaryOverride) {
            this.cullFace = cullFace;
            this.textureKey = textureKey;
            this.uv = uv == null ? null : uv.clone();
            this.uvOffset = uvOffset.clone();
            this.cullBoundaryOverride = cullBoundaryOverride;
        }

        private static RawFace parse(JsonObject object) {
            Direction cullFace = null;
            if (object.has("cullface")) {
                cullFace = Direction.byName(object.get("cullface").getAsString());
            }

            String textureKey = null;
            if (object.has("texture") && object.get("texture").isJsonPrimitive()) {
                textureKey = object.get("texture").getAsString();
            }

            float[] uv = null;
            if (object.has("uv") && object.get("uv").isJsonArray()) {
                JsonArray uvArray = object.getAsJsonArray("uv");
                if (uvArray.size() >= 4) {
                    uv = new float[]{
                            uvArray.get(0).getAsFloat(),
                            uvArray.get(1).getAsFloat(),
                            uvArray.get(2).getAsFloat(),
                            uvArray.get(3).getAsFloat()
                    };
                }
            }

            float[] uvOffset = new float[]{0.0F, 0.0F};
            if (object.has("erydon_uv_offset")) {
                JsonElement offsetElement = object.get("erydon_uv_offset");
                if (!offsetElement.isJsonArray() || offsetElement.getAsJsonArray().size() != 2) {
                    throw new IllegalArgumentException("Expected exactly 2 finite numbers for erydon_uv_offset");
                }
                JsonArray offsetArray = offsetElement.getAsJsonArray();
                for (int index = 0; index < 2; index++) {
                    JsonElement value = offsetArray.get(index);
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                        throw new IllegalArgumentException("Expected exactly 2 finite numbers for erydon_uv_offset");
                    }
                    float parsed = value.getAsFloat();
                    if (!Float.isFinite(parsed)) {
                        throw new IllegalArgumentException("Expected exactly 2 finite numbers for erydon_uv_offset");
                    }
                    uvOffset[index] = parsed;
                }
            }

            boolean cullBoundaryOverride = false;
            if (object.has("erydon_cull_boundary_override")) {
                JsonElement overrideElement = object.get("erydon_cull_boundary_override");
                if (!overrideElement.isJsonPrimitive()
                        || !overrideElement.getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException(
                            "Expected a boolean for erydon_cull_boundary_override");
                }
                cullBoundaryOverride = overrideElement.getAsBoolean();
            }
            return new RawFace(cullFace, textureKey, uv, uvOffset, cullBoundaryOverride);
        }
    }

    private static final class RawRotation {
        private static final RawRotation NONE = new RawRotation(new float[]{8.0F, 8.0F, 8.0F}, 0.0F, 0.0F, 0.0F);

        private final float[] origin;
        private final float xRadians;
        private final float yRadians;
        private final float zRadians;
        private final boolean identity;

        private RawRotation(float[] origin, float xDegrees, float yDegrees, float zDegrees) {
            this.origin = origin;
            this.identity = Math.abs(xDegrees) <= EPSILON && Math.abs(yDegrees) <= EPSILON && Math.abs(zDegrees) <= EPSILON;
            this.xRadians = (float) Math.toRadians(xDegrees);
            this.yRadians = (float) Math.toRadians(yDegrees);
            this.zRadians = (float) Math.toRadians(zDegrees);
        }

        private static RawRotation parse(JsonElement element, float[] defaultOrigin) {
            if (element == null || element.isJsonNull()) {
                return NONE;
            }

            if (element.isJsonArray()) {
                float[] angles = vector3(element, "rotation");
                return new RawRotation(defaultOrigin, angles[0], angles[1], angles[2]);
            }

            if (!element.isJsonObject()) {
                return NONE;
            }

            JsonObject object = element.getAsJsonObject();
            float[] origin = vector3OrDefault(object.get("origin"), defaultOrigin[0], defaultOrigin[1], defaultOrigin[2]);

            if (object.has("angles") && object.get("angles").isJsonArray()) {
                float[] angles = vector3(object.get("angles"), "angles");
                return new RawRotation(origin, angles[0], angles[1], angles[2]);
            }

            if (object.has("angle") && object.get("angle").isJsonArray()) {
                float[] angles = vector3(object.get("angle"), "angle");
                return new RawRotation(origin, angles[0], angles[1], angles[2]);
            }

            if (hasNumber(object, "x") || hasNumber(object, "y") || hasNumber(object, "z")) {
                return new RawRotation(origin, numberOrZero(object, "x"), numberOrZero(object, "y"), numberOrZero(object, "z"));
            }

            if (object.has("axis") && object.has("angle")) {
                float angle = object.get("angle").getAsFloat();
                return switch (object.get("axis").getAsString()) {
                    case "x" -> new RawRotation(origin, angle, 0.0F, 0.0F);
                    case "y" -> new RawRotation(origin, 0.0F, angle, 0.0F);
                    case "z" -> new RawRotation(origin, 0.0F, 0.0F, angle);
                    default -> NONE;
                };
            }

            return NONE;
        }

        private Vector3f transform(Vector3f vertex) {
            if (identity) {
                return vertex;
            }

            Vector3f transformed = new Vector3f(vertex.x - origin[0], vertex.y - origin[1], vertex.z - origin[2]);
            rotateX(transformed);
            rotateY(transformed);
            rotateZ(transformed);
            return transformed.add(origin[0], origin[1], origin[2]);
        }

        private void rotateX(Vector3f vertex) {
            if (Math.abs(xRadians) <= EPSILON) {
                return;
            }
            float cos = (float) Math.cos(xRadians);
            float sin = (float) Math.sin(xRadians);
            float y = vertex.y * cos - vertex.z * sin;
            float z = vertex.y * sin + vertex.z * cos;
            vertex.y = y;
            vertex.z = z;
        }

        private void rotateY(Vector3f vertex) {
            if (Math.abs(yRadians) <= EPSILON) {
                return;
            }
            float cos = (float) Math.cos(yRadians);
            float sin = (float) Math.sin(yRadians);
            float x = vertex.x * cos + vertex.z * sin;
            float z = -vertex.x * sin + vertex.z * cos;
            vertex.x = x;
            vertex.z = z;
        }

        private void rotateZ(Vector3f vertex) {
            if (Math.abs(zRadians) <= EPSILON) {
                return;
            }
            float cos = (float) Math.cos(zRadians);
            float sin = (float) Math.sin(zRadians);
            float x = vertex.x * cos - vertex.y * sin;
            float y = vertex.x * sin + vertex.y * cos;
            vertex.x = x;
            vertex.y = y;
        }
    }

    private static float[] vector3(JsonElement element, String name) {
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException("Expected 3-number array for " + name);
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() < 3) {
            throw new IllegalArgumentException("Expected 3-number array for " + name);
        }
        return new float[]{
                array.get(0).getAsFloat(),
                array.get(1).getAsFloat(),
                array.get(2).getAsFloat()
        };
    }

    private static float[] vector3OrDefault(JsonElement element, float x, float y, float z) {
        if (element == null || element.isJsonNull()) {
            return new float[]{x, y, z};
        }
        return vector3(element, "origin");
    }

    private static boolean hasNumber(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }

    private static float numberOrZero(JsonObject object, String name) {
        return hasNumber(object, name) ? object.get(name).getAsFloat() : 0.0F;
    }
}
