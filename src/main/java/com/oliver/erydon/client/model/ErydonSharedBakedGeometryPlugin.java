package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.client.profile.ErydonSharedGeometryMetrics;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MeshBuilder;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.ModelHelper;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BasicBakedModel;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Reload-scoped pooling for ordinary JSON component models. Geometry is taken
 * from Minecraft's completed vanilla bake, so unsupported loaders and any
 * model that cannot round-trip exactly stay on the original path.
 */
public final class ErydonSharedBakedGeometryPlugin implements ModelLoadingPlugin {
    @Override
    public void onInitializeModelLoader(Context context) {
        if (!ErydonRawModelLoadingPlugin.sharedGeometryEnabled()) {
            return;
        }

        Pool pool = new Pool();
        context.modifyModelAfterBake().register((model, bakeContext) -> {
            Identifier id = bakeContext.id();
            String family = family(id, model);
            if (family == null) {
                return model;
            }
            try {
                BakedModel pooled = pool.materialize(family, id, model);
                return pooled == null ? model : pooled;
            } catch (RuntimeException exception) {
                if (ErydonSharedGeometryMetrics.isEnabled()) {
                    ErydonSharedGeometryMetrics.structuralOverrideFallback(id.toString());
                }
                Erydon.LOGGER.warn(
                        "[{}] Shared geometry skipped incompatible component {}.",
                        Erydon.MOD_ID,
                        id,
                        exception
                );
                return model;
            }
        });
    }

    private static String family(Identifier id, BakedModel model) {
        if (id == null
                || id instanceof ModelIdentifier
                || !Erydon.MOD_ID.equals(id.getNamespace())
                || model instanceof SharedGeometryChildModel
                || !(model instanceof BasicBakedModel)
                || !model.isVanillaAdapter()) {
            return null;
        }

        String path = id.getPath();
        String file = path.substring(path.lastIndexOf('/') + 1);
        if (path.startsWith("block/column/circular/") && file.contains("_column_")) {
            return "column/circular";
        }
        if (path.startsWith("block/column/square/") && file.contains("_column_")) {
            return "column/square";
        }
        if (path.startsWith("block/cornice/") && file.contains("_cornice_")) {
            return directoryFamily(path, "cornice");
        }
        if (path.startsWith("block/ceiling/coffered/") && file.contains("_ceiling_")) {
            return directoryFamily(path, "ceiling/coffered");
        }
        if ((path.startsWith("block/layer/layer/") || path.startsWith("block/glazing/layer/"))
                && file.contains("_layer_")) {
            return path.startsWith("block/glazing/") ? "layer/glazing" : "layer/solid";
        }
        if (path.startsWith("block/surround/") && file.contains("_surround_")) {
            return directoryFamily(path, "surround");
        }
        if (path.startsWith("block/window/") && file.contains("_window_")) {
            return directoryFamily(path, "window");
        }
        if ((path.startsWith("block/arch/romanesque/") || path.startsWith("block/arch/modern/"))
                && file.contains("_arch_")) {
            return directoryFamily(path, "arch");
        }
        return null;
    }

    private static String directoryFamily(String path, String root) {
        String prefix = "block/" + root + "/";
        int separator = path.indexOf('/', prefix.length());
        return separator < 0 ? root : root + "/" + path.substring(prefix.length(), separator);
    }

    static String familyForTest(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        if (path.startsWith("block/column/circular/") && file.contains("_column_")) {
            return "column/circular";
        }
        if (path.startsWith("block/column/square/") && file.contains("_column_")) {
            return "column/square";
        }
        if (path.startsWith("block/cornice/") && file.contains("_cornice_")) {
            return directoryFamily(path, "cornice");
        }
        if (path.startsWith("block/ceiling/coffered/") && file.contains("_ceiling_")) {
            return directoryFamily(path, "ceiling/coffered");
        }
        if ((path.startsWith("block/layer/layer/") || path.startsWith("block/glazing/layer/"))
                && file.contains("_layer_")) {
            return path.startsWith("block/glazing/") ? "layer/glazing" : "layer/solid";
        }
        if (path.startsWith("block/surround/") && file.contains("_surround_")) {
            return directoryFamily(path, "surround");
        }
        if (path.startsWith("block/window/") && file.contains("_window_")) {
            return directoryFamily(path, "window");
        }
        if ((path.startsWith("block/arch/romanesque/") || path.startsWith("block/arch/modern/"))
                && file.contains("_arch_")) {
            return directoryFamily(path, "arch");
        }
        return null;
    }

    private static final class Pool {
        private final Map<GeometryKey, SharedGeometry> geometries = new LinkedHashMap<>();

        private BakedModel materialize(String family, Identifier id, BakedModel source) {
            long started = System.nanoTime();
            Candidate candidate = Candidate.capture(family, source);
            if (candidate == null) {
                if (ErydonSharedGeometryMetrics.isEnabled()) {
                    ErydonSharedGeometryMetrics.structuralOverrideFallback(id.toString());
                }
                return null;
            }

            SharedGeometry geometry = geometries.get(candidate.key);
            boolean hit = geometry != null;
            if (!hit) {
                geometry = SharedGeometry.create(
                        candidate.key, candidate.templates, candidate.binding);
                geometries.put(candidate.key, geometry);
            }
            if (ErydonSharedGeometryMetrics.isEnabled()) {
                String geometryId = candidate.key.stableValue();
                ErydonSharedGeometryMetrics.geometryCacheLookup(
                        hit, geometryId, id.toString(), hit ? geometry.mesh : null);
                ErydonSharedGeometryMetrics.materialModelBaked(
                        geometry.mesh,
                        geometryId,
                        id.toString(),
                        candidate.binding.stableValue(),
                        geometry.templates.size(),
                        System.nanoTime() - started
                );
            }
            return new SharedModel(geometry, candidate.binding, source);
        }
    }

    private record Candidate(GeometryKey key,
                             List<QuadTemplate> templates,
                             MaterialBinding binding) {
        private static Candidate capture(String family, BakedModel model) {
            List<QuadTemplate> templates = new ArrayList<>();
            Map<Identifier, Integer> slotBySprite = new LinkedHashMap<>();
            List<Sprite> sprites = new ArrayList<>();

            for (int faceIndex = 0; faceIndex <= ModelHelper.NULL_FACE_ID; faceIndex++) {
                Direction cullFace = ModelHelper.faceFromIndex(faceIndex);
                List<BakedQuad> quads = model.getQuads(null, cullFace, Random.create(0L));
                for (BakedQuad quad : quads) {
                    Sprite sprite = quad.getSprite();
                    Identifier spriteId = sprite.getContents().getId();
                    Integer slot = slotBySprite.get(spriteId);
                    if (slot == null) {
                        slot = sprites.size();
                        slotBySprite.put(spriteId, slot);
                        sprites.add(sprite);
                    }
                    QuadTemplate template = QuadTemplate.capture(quad, cullFace, slot, sprite);
                    if (template == null) {
                        return null;
                    }
                    templates.add(template);
                }
            }
            if (templates.isEmpty()) {
                return null;
            }

            MaterialBinding binding = new MaterialBinding(
                    List.copyOf(sprites),
                    model.getParticleSprite()
            );
            return new Candidate(
                    new GeometryKey(family, model.useAmbientOcclusion(), List.copyOf(templates)),
                    List.copyOf(templates),
                    binding
            );
        }
    }

    private static final class SharedGeometry {
        private final Mesh mesh;
        private final List<QuadTemplate> templates;

        private SharedGeometry(Mesh mesh, List<QuadTemplate> templates) {
            this.mesh = mesh;
            this.templates = templates;
        }

        private static SharedGeometry create(GeometryKey key,
                                             List<QuadTemplate> templates,
                                             MaterialBinding binding) {
            long started = System.nanoTime();
            Renderer renderer = RendererAccess.INSTANCE.getRenderer();
            if (renderer == null) {
                throw new IllegalStateException("No Fabric Renderer API implementation is active");
            }
            RenderMaterial material = key.ambientOcclusion
                    ? renderer.materialFinder().clear().find()
                    : renderer.materialFinder().clear().ambientOcclusion(TriState.FALSE).find();
            MeshBuilder builder = renderer.meshBuilder();
            QuadEmitter emitter = builder.getEmitter();
            for (QuadTemplate template : templates) {
                emitter.fromVanilla(
                        template.normalizedQuad(binding.sprites.get(template.spriteSlot)),
                        material,
                        template.cullFace
                );
                emitter.tag(template.spriteSlot);
                emitter.emit();
            }
            Mesh mesh = builder.build();
            if (ErydonSharedGeometryMetrics.isEnabled()) {
                ErydonSharedGeometryMetrics.sharedGeometryCreated(
                        mesh, key.stableValue(), templates.size(), System.nanoTime() - started);
                ErydonSharedGeometryMetrics.sharedCompatibilityPayloadCreated(templates.size());
            }
            return new SharedGeometry(mesh, List.copyOf(templates));
        }

        private List<BakedQuad> boundQuads(MaterialBinding binding, Direction face) {
            List<BakedQuad> result = new ArrayList<>();
            for (QuadTemplate template : templates) {
                if (template.cullFace == face) {
                    result.add(template.boundQuad(binding.sprites.get(template.spriteSlot)));
                }
            }
            return List.copyOf(result);
        }
    }

    private static final class SharedModel implements BakedModel, SharedGeometryChildModel {
        private final SharedGeometry geometry;
        private final MaterialBinding binding;
        private final SlotTransform transform;
        private final boolean ambientOcclusion;
        private final boolean depth;
        private final boolean sideLit;
        private final boolean builtin;
        private final ModelTransformation transformation;
        private final ModelOverrideList overrides;

        private SharedModel(SharedGeometry geometry, MaterialBinding binding, BakedModel source) {
            this.geometry = geometry;
            this.binding = binding;
            this.transform = new SlotTransform(binding.sprites);
            this.ambientOcclusion = source.useAmbientOcclusion();
            this.depth = source.hasDepth();
            this.sideLit = source.isSideLit();
            this.builtin = source.isBuiltin();
            this.transformation = source.getTransformation();
            this.overrides = source.getOverrides();
        }

        @Override
        public boolean isVanillaAdapter() {
            return false;
        }

        @Override
        public void emitSharedGeometry(RenderContext context) {
            context.pushTransform(transform);
            try {
                geometry.mesh.outputTo(context.getEmitter());
                ErydonSharedGeometryMetrics.blockEmitted(geometry.templates.size());
            } finally {
                context.popTransform();
            }
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
        public void emitItemQuads(ItemStack stack,
                                  Supplier<Random> randomSupplier,
                                  RenderContext context) {
            emitSharedGeometry(context);
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
            return geometry.boundQuads(binding, face);
        }

        @Override public boolean useAmbientOcclusion() { return ambientOcclusion; }
        @Override public boolean hasDepth() { return depth; }
        @Override public boolean isSideLit() { return sideLit; }
        @Override public boolean isBuiltin() { return builtin; }
        @Override public Sprite getParticleSprite() { return binding.particle; }
        @Override public ModelTransformation getTransformation() { return transformation; }
        @Override public ModelOverrideList getOverrides() { return overrides; }
    }

    private record SlotTransform(List<Sprite> sprites) implements RenderContext.QuadTransform {
        @Override
        public boolean transform(MutableQuadView quad) {
            int slot = quad.tag();
            if (slot < 0 || slot >= sprites.size()) {
                return false;
            }
            quad.spriteBake(sprites.get(slot), MutableQuadView.BAKE_NORMALIZED);
            return true;
        }
    }

    private record MaterialBinding(List<Sprite> sprites,
                                   Sprite particle) {
        private String stableValue() {
            StringBuilder value = new StringBuilder("particle=")
                    .append(particle.getContents().getId())
                    .append(";sprites=");
            for (int index = 0; index < sprites.size(); index++) {
                if (index > 0) value.append(',');
                value.append(sprites.get(index).getContents().getId());
            }
            return value.toString();
        }
    }

    private static final class GeometryKey {
        private final String family;
        private final boolean ambientOcclusion;
        private final List<QuadTemplate> templates;
        private final int hash;

        private GeometryKey(String family,
                            boolean ambientOcclusion,
                            List<QuadTemplate> templates) {
            this.family = family;
            this.ambientOcclusion = ambientOcclusion;
            this.templates = templates;
            this.hash = Objects.hash(family, ambientOcclusion, templates);
        }

        private String stableValue() {
            return "baked:" + family + ":" + Integer.toUnsignedString(hash, 16)
                    + ":" + templates.size();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof GeometryKey key)) return false;
            return ambientOcclusion == key.ambientOcclusion
                    && family.equals(key.family)
                    && templates.equals(key.templates);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class QuadTemplate {
        private static final double[] ROUNDING_SCALES = {1_000_000.0, 100_000.0, 10_000.0};
        private final int[] normalizedData;
        private final int colorIndex;
        private final Direction face;
        private final Direction cullFace;
        private final boolean shade;
        private final int spriteSlot;
        private final int hash;

        private QuadTemplate(int[] normalizedData,
                             int colorIndex,
                             Direction face,
                             Direction cullFace,
                             boolean shade,
                             int spriteSlot) {
            this.normalizedData = normalizedData;
            this.colorIndex = colorIndex;
            this.face = face;
            this.cullFace = cullFace;
            this.shade = shade;
            this.spriteSlot = spriteSlot;
            this.hash = 31 * Arrays.hashCode(normalizedData)
                    + Objects.hash(colorIndex, face, cullFace, shade, spriteSlot);
        }

        private static QuadTemplate capture(BakedQuad quad,
                                            Direction cullFace,
                                            int spriteSlot,
                                            Sprite sprite) {
            int[] original = quad.getVertexData();
            int[] normalized = original.clone();
            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = vertex * 8;
                float atlasU = Float.intBitsToFloat(original[offset + 4]);
                float atlasV = Float.intBitsToFloat(original[offset + 5]);
                float normalizedU = exactNormalized(atlasU, sprite, true);
                float normalizedV = exactNormalized(atlasV, sprite, false);
                if (!Float.isFinite(normalizedU) || !Float.isFinite(normalizedV)) {
                    return null;
                }
                normalized[offset + 4] = Float.floatToRawIntBits(normalizedU);
                normalized[offset + 5] = Float.floatToRawIntBits(normalizedV);
            }
            QuadTemplate template = new QuadTemplate(
                    normalized,
                    quad.getColorIndex(),
                    quad.getFace(),
                    cullFace,
                    quad.hasShade(),
                    spriteSlot
            );
            return Arrays.equals(original, template.boundData(sprite)) ? template : null;
        }

        private static float exactNormalized(float atlas, Sprite sprite, boolean uAxis) {
            float frame = uAxis ? sprite.getFrameFromU(atlas) : sprite.getFrameFromV(atlas);
            for (double scale : ROUNDING_SCALES) {
                float rounded = (float) (Math.rint(frame * scale) / scale);
                float normalized = rounded / 16.0F;
                if (sameAtlas(atlas, normalized, sprite, uAxis)) {
                    return normalized;
                }
            }

            float normalized = frame / 16.0F;
            if (sameAtlas(atlas, normalized, sprite, uAxis)) {
                return normalized;
            }
            float upper = normalized;
            float lower = normalized;
            for (int step = 0; step < 16; step++) {
                upper = Math.nextUp(upper);
                if (sameAtlas(atlas, upper, sprite, uAxis)) return upper;
                lower = Math.nextDown(lower);
                if (sameAtlas(atlas, lower, sprite, uAxis)) return lower;
            }
            return Float.NaN;
        }

        private static boolean sameAtlas(float expected,
                                         float normalized,
                                         Sprite sprite,
                                         boolean uAxis) {
            float actual = uAxis
                    ? sprite.getFrameU(normalized * 16.0F)
                    : sprite.getFrameV(normalized * 16.0F);
            return Float.floatToRawIntBits(expected) == Float.floatToRawIntBits(actual);
        }

        private int[] boundData(Sprite sprite) {
            int[] bound = normalizedData.clone();
            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = vertex * 8;
                float normalizedU = Float.intBitsToFloat(bound[offset + 4]);
                float normalizedV = Float.intBitsToFloat(bound[offset + 5]);
                bound[offset + 4] = Float.floatToRawIntBits(sprite.getFrameU(normalizedU * 16.0F));
                bound[offset + 5] = Float.floatToRawIntBits(sprite.getFrameV(normalizedV * 16.0F));
            }
            return bound;
        }

        private BakedQuad normalizedQuad(Sprite sprite) {
            return new BakedQuad(
                    normalizedData.clone(), colorIndex, face, sprite, shade);
        }

        private BakedQuad boundQuad(Sprite sprite) {
            return new BakedQuad(boundData(sprite), colorIndex, face, sprite, shade);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof QuadTemplate template)) return false;
            return colorIndex == template.colorIndex
                    && shade == template.shade
                    && spriteSlot == template.spriteSlot
                    && face == template.face
                    && cullFace == template.cullFace
                    && Arrays.equals(normalizedData, template.normalizedData);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
