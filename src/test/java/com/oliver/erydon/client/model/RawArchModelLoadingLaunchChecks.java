package com.oliver.erydon.client.model;

import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelResolver;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.mesh.MeshBuilder;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.EncodingFormat;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.MutableQuadViewImpl;
import net.fabricmc.fabric.api.resource.ModResourcePack;
import net.fabricmc.fabric.impl.resource.loader.FabricModResourcePack;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.ModelRotation;
import net.minecraft.client.render.model.UnbakedModel;
import net.minecraft.client.texture.Sprite;
import net.minecraft.resource.DirectoryResourcePack;
import net.minecraft.resource.LifecycledResourceManagerImpl;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.ZipResourcePack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/** Goes through the production resource scan, resolver, raw bake and Fabric emission. */
final class RawArchModelLoadingLaunchChecks {
    private static final BlockRenderView VIEW = (BlockRenderView) Proxy.newProxyInstance(BlockRenderView.class.getClassLoader(),
            new Class<?>[]{BlockRenderView.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getBlockState" -> Blocks.AIR.getDefaultState();
                case "getFluidState" -> Blocks.AIR.getDefaultState().getFluidState();
                case "getBlockEntity" -> null;
                case "getBrightness" -> 1.0F;
                case "getColor" -> -1;
                case "getHeight" -> 384;
                case "getBottomY" -> -64;
                default -> throw new AssertionError("Unexpected render view query " + method);
            });
    private RawArchModelLoadingLaunchChecks() { }

    static void run(Sprite sprite) throws Exception {
        if (RendererAccess.INSTANCE.getRenderer() == null) RendererAccess.INSTANCE.registerRenderer(IndigoRenderer.INSTANCE);
        String property = ErydonRawModelLoadingPlugin.SHARED_GEOMETRY_MODE_PROPERTY;
        String previousMode = System.getProperty(property);
        String anchor = "assets/erydon/authoring_models/block/arch/romanesque/arch_romanesque_icon.json";
        Path root = Path.of(RawArchModelLoadingLaunchChecks.class.getClassLoader().getResource(anchor).toURI());
        for (int i = 0; i < Path.of(anchor).getNameCount(); i++) root = root.getParent();
        try (var resources = new LifecycledResourceManagerImpl(ResourceType.CLIENT_RESOURCES, packStack(root))) {
            Identifier nativeParent = new Identifier("erydon", "models/block/arch/romanesque/arch_romanesque_side_small.json");
            String grouped = resources.getResource(nativeParent).orElseThrow().getResourcePackName();
            String underlying = resources.getAllResources(nativeParent).get(0).getResourcePackName();
            require(grouped.equals("fabric") && underlying.equals("erydon"),
                    "Native fixture did not reproduce Fabric's grouped resource provenance: " + grouped + "/" + underlying);
            System.out.println("ERYDON_RAW_ARCH_FABRIC_PROVENANCE_OK getResource=" + grouped + " getAllResources=" + underlying
                    + " legacyOverrideMisclassification=confirmed");
            ModelResolver baseline = load(resources, "baseline");
            // This reproduces the old omission without changing production code: the strict
            // index sees neither new arch family and silently returns their empty JSON anchors.
            ResourceManager missingRoots = (ResourceManager) Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),
                    new Class<?>[]{ResourceManager.class}, (proxy, method, args) -> {
                        if (method.getName().equals("findResources") &&
                                (args[0].equals("models/block/arch/romanesque") || args[0].equals("models/block/arch/georgian")))
                            return java.util.Map.of();
                        return method.invoke(resources, args);
                    });
            ModelResolver omitted = load(missingRoots, "shared_geometry");
            for (String style : List.of("romanesque", "georgian")) {
                Identifier id = modelId("aganite_arch_" + style, style.equals("romanesque") ? "side_small" : "base_left", style);
                require(resolve(omitted, id) == null, "Negative control no longer reproduces omitted-family fallback");
            }
            // Clear the property to cover the actual new default, rather than forcing it on.
            ModelResolver shared = load(resources, null);
            externalOverrideProof(resources, shared, sprite);
            int components = 0, emptyCells = 0, emitted = 0, wrapped = 0;
            try (ContinuityFixture continuity = new ContinuityFixture(sprite)) {
                for (String style : List.of("romanesque", "georgian"))
                    for (String material : List.of("aganite", "aganite_aged", "aganite_ashlar", "aterzon")) {
                        String block = material + "_arch_" + style;
                        Iterable<String> suffixes = style.equals("romanesque")
                                ? List.of(ArchRomanesqueBakedModel.modelSuffixes(block)) : ArchGeorgianBakedModel.modelSuffixes(block);
                        for (String suffix : suffixes) {
                            Identifier id = modelId(block, suffix, style);
                            Identifier source = new Identifier("erydon", "authoring_models/block/arch/" + style
                                    + "/arch_" + style + "_" + suffix + ".json");
                            try (var reader = resources.getResource(source).orElseThrow().getReader()) {
                                if (JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("elements").isEmpty()) {
                                    require(resolve(baseline, id) == null && resolve(shared, id) == null,
                                            "An authored empty crown cell acquired raw geometry: " + id);
                                    emptyCells++;
                                    continue;
                                }
                            }
                            BakedModel before = bake(baseline, id, sprite);
                            BakedModel after = bake(shared, id, sprite);
                            require(after instanceof SharedGeometryChildModel, "Default mode bypassed shared raw geometry: " + id);
                            List<float[]> expected = emit(before, sprite);
                            List<float[]> actual = emit(after, sprite);
                            require(!actual.isEmpty(), "Actual Fabric emission is empty: " + id);
                            same(expected, actual, id.toString());
                            if (continuity.present) {
                                BakedModel child = continuity.wrap(after);
                                same(actual, emit(child, sprite), "Continuity child " + id);
                                wrapped++;
                            }
                            components++; emitted += actual.size();
                        }
                    }
                require(!continuity.present || continuity.processed > 0,
                        "Continuity fixture did not execute its connected-texture processor");
                System.out.println("ERYDON_RAW_ARCH_MODEL_LOADING_OK components=" + components + " authoredEmptyCells=" + emptyCells + " emittedQuads=" + emitted
                        + " baselineSharedParity=exactPositions atlasUvs=1e-6 realContinuityChildren=" + wrapped
                        + " ctmProcessorCalls=" + continuity.processed + " omittedRootNegativeControl=confirmed defaultMode=shared_geometry");
            }
        } finally {
            if (previousMode == null) System.clearProperty(property); else System.setProperty(property, previousMode);
        }
    }

    private static List<ResourcePack> packStack(Path nativeRoot) throws Exception {
        class NativePack extends DirectoryResourcePack implements ModResourcePack {
            NativePack() { super("erydon", nativeRoot, true); }
            @Override public ModMetadata getFabricModMetadata() {
                return FabricLoader.getInstance().getModContainer("erydon").orElseThrow().getMetadata();
            }
        }
        List<ResourcePack> packs = new ArrayList<>();
        packs.add(new FabricModResourcePack(ResourceType.CLIENT_RESOURCES, List.of(new NativePack())));
        String configured = System.getProperty("erydon.raw_arch.devPackRoot");
        if (configured != null && !configured.isBlank()) {
            Path dev = Path.of(configured).toAbsolutePath().normalize();
            String selected = Files.readAllLines(dev.resolve("options.txt")).stream()
                    .filter(line -> line.startsWith("resourcePacks:")).findFirst().orElseThrow()
                    .substring("resourcePacks:".length());
            for (var entry : JsonParser.parseString(selected).getAsJsonArray()) {
                String name = entry.getAsString();
                if (name.equals("vanilla") || name.equals("fabric")) continue;
                if (name.startsWith("file/")) {
                    Path packRoot = dev.resolve("resourcepacks").normalize();
                    Path pack = packRoot.resolve(name.substring("file/".length())).normalize();
                    require(pack.startsWith(packRoot) && Files.exists(pack), "Missing or invalid active test pack " + name);
                    packs.add(Files.isDirectory(pack) ? new DirectoryResourcePack(name, pack, false)
                            : new ZipResourcePack(name, pack.toFile(), false));
                } else if (name.equals("erydon:high_polish_glazing")) {
                    Path pack = nativeRoot.resolve("resourcepacks/high_polish_glazing");
                    require(Files.isDirectory(pack), "Missing active built-in glazing test pack");
                    packs.add(new DirectoryResourcePack(name, pack, true));
                } else throw new AssertionError("Unsupported active pack in the raw arch regression: " + name);
            }
        }
        System.out.println("ERYDON_RAW_ARCH_PACK_STACK " + packs.stream().map(ResourcePack::getName).toList()
                + " vanillaScope=erydon-resource-queries-only");
        return packs;
    }

    private static void externalOverrideProof(ResourceManager resources, ModelResolver nativeShared, Sprite sprite) throws Exception {
        Identifier model = new Identifier("erydon", "block/arch/romanesque/aganite_arch_romanesque_side_small");
        Identifier child = new Identifier("erydon", "models/" + model.getPath() + ".json");
        Identifier parent = new Identifier("erydon", "models/block/arch/romanesque/arch_romanesque_side_small.json");
        String texture = """
                {"parent":"erydon:block/arch/romanesque/arch_romanesque_side_small",
                 "textures":{"stone":"external:block/stone","particle":"external:block/particle"}}
                """;
        Sprite surfaceSprite = new Sprite(new Identifier("erydon", "raw_arch_override_atlas"),
                sprite.getContents(), 64, 64, 32, 16) {};
        Sprite particleSprite = new Sprite(new Identifier("erydon", "raw_arch_override_particle_atlas"),
                sprite.getContents(), 64, 64, 48, 48) {};
        int textureCases = 0;
        for (boolean retainLower : new boolean[]{false, true}) {
            ModelResolver changed = load(overridden(resources, child, texture, retainLower), null);
            UnbakedModel raw = resolve(changed, model);
            require(raw != null, "A safe external texture-only override fell back to an empty parent");
            BakedModel baked = raw.bake(null, id -> switch (id.getTextureId().toString()) {
                case "external:block/stone" -> surfaceSprite;
                case "external:block/particle" -> particleSprite;
                default -> sprite;
            }, ModelRotation.X0_Y0, model);
            require(baked instanceof SharedGeometryChildModel && baked.getParticleSprite() == particleSprite,
                    "External single-provider/stacked override lost sharing or its exact particle binding");
            List<float[]> emitted = emit(baked, surfaceSprite);
            require(!emitted.isEmpty() && emitted.size() == emit(bake(nativeShared, model, sprite), sprite).size(),
                    "Texture-only override changed the number of rendered faces");
            for (float[] quad : emitted) for (int vertex = 0; vertex < 4; vertex++) {
                float u = quad[3 + vertex * 5 + 3], v = quad[3 + vertex * 5 + 4];
                require(u >= surfaceSprite.getMinU() - 1e-6 && u <= surfaceSprite.getMaxU() + 1e-6
                        && v >= surfaceSprite.getMinV() - 1e-6 && v <= surfaceSprite.getMaxV() + 1e-6,
                        "External stone texture escaped its requested atlas sprite");
            }
            textureCases++;
        }
        String structuralChild = texture.replace("\"textures\":", "\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],"
                + "\"faces\":{\"north\":{\"texture\":\"#stone\"}}}],\"textures\":");
        require(resolve(load(overridden(resources, child, structuralChild, false), null), model) == null,
                "A single external provider's authored child geometry was ignored");
        String structuralParent = """
                {"parent":"minecraft:block/block","textures":{"particle":"#stone"},
                 "elements":[{"from":[0,0,0],"to":[16,16,16],"faces":{"north":{"texture":"#stone"}}}]}
                """;
        ModelResolver parentChanged = load(overridden(resources, parent, structuralParent, false), null);
        require(resolve(parentChanged, model) == null, "An authored parent override was overwritten by shared geometry");
        require(resolve(parentChanged, modelId("aganite_arch_romanesque", "non_column_side_small", "romanesque")) != null,
                "One structural parent override disabled unrelated shared components");
        System.out.println("ERYDON_RAW_ARCH_OVERRIDES_OK textureCases=" + textureCases
                + " singleExternalProvider=preserved stackedTexture=preserved structuralChild=vanilla structuralParent=vanilla unrelatedComponent=shared");
    }

    private static ResourceManager overridden(ResourceManager resources, Identifier id, String json, boolean retainLower) {
        ResourcePack provider = (ResourcePack) Proxy.newProxyInstance(ResourcePack.class.getClassLoader(),
                new Class<?>[]{ResourcePack.class}, (proxy, method, args) -> {
                    require(method.getName().equals("getName"), "Unexpected override provider query " + method);
                    return "external-arch-models";
                });
        Resource changed = new Resource(provider, () -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        return (ResourceManager) Proxy.newProxyInstance(ResourceManager.class.getClassLoader(), new Class<?>[]{ResourceManager.class},
                (proxy, method, args) -> {
                    if (args != null && args.length > 0 && id.equals(args[0])) {
                        if (method.getName().equals("getResource")) return Optional.of(changed);
                        if (method.getName().equals("getAllResources")) {
                            List<Resource> stack = new ArrayList<>(retainLower ? resources.getAllResources(id) : List.of());
                            stack.add(changed); return List.copyOf(stack);
                        }
                    }
                    return method.invoke(resources, args);
                });
    }

    private static Identifier modelId(String block, String suffix, String style) {
        return style.equals("romanesque") ? ArchRomanesqueBakedModel.modelId(block, suffix)
                : ArchGeorgianBakedModel.modelId(block, suffix);
    }

    private static ModelResolver load(ResourceManager resources, String mode) {
        if (mode == null) System.clearProperty(ErydonRawModelLoadingPlugin.SHARED_GEOMETRY_MODE_PROPERTY);
        else System.setProperty(ErydonRawModelLoadingPlugin.SHARED_GEOMETRY_MODE_PROPERTY, mode);
        var prepared = ErydonRawModelLoadingPlugin.load(resources, Runnable::run).join();
        Event<ModelResolver> event = EventFactory.createArrayBacked(ModelResolver.class, callbacks -> context -> {
            for (ModelResolver callback : callbacks) {
                UnbakedModel model = callback.resolveModel(context);
                if (model != null) return model;
            }
            return null;
        });
        ModelLoadingPlugin.Context context = (ModelLoadingPlugin.Context) Proxy.newProxyInstance(
                ModelLoadingPlugin.Context.class.getClassLoader(), new Class<?>[]{ModelLoadingPlugin.Context.class},
                (proxy, method, args) -> {
                    require(method.getName().equals("resolveModel"), "Unexpected raw loader registration " + method);
                    return event;
                });
        new ErydonRawModelLoadingPlugin().onInitializeModelLoader(prepared, context);
        return event.invoker();
    }

    private static UnbakedModel resolve(ModelResolver resolver, Identifier id) {
        ModelResolver.Context context = (ModelResolver.Context) Proxy.newProxyInstance(ModelResolver.Context.class.getClassLoader(),
                new Class<?>[]{ModelResolver.Context.class}, (proxy, method, args) -> {
                    require(method.getName().equals("id"), "Unexpected resolver query " + method);
                    return id;
                });
        return resolver.resolveModel(context);
    }

    private static BakedModel bake(ModelResolver resolver, Identifier id, Sprite sprite) {
        UnbakedModel model = resolve(resolver, id);
        require(model != null, "Raw family silently fell through to its empty vanilla anchor: " + id);
        return model.bake(null, ignored -> sprite, ModelRotation.X0_Y0, id);
    }

    private static List<float[]> emit(BakedModel model, Sprite sprite) throws Exception {
        MeshBuilder builder = IndigoRenderer.INSTANCE.meshBuilder();
        QuadEmitter delegate = builder.getEmitter();
        var transforms = new ArrayDeque<RenderContext.QuadTransform>();
        // Indigo's Mesh.outputTo uses its native emitter directly. Preserve that
        // real path while applying the render context's transform stack.
        QuadEmitter emitter = new MutableQuadViewImpl() {
            { data = new int[EncodingFormat.TOTAL_STRIDE]; clear(); }
            @Override public void emitDirectly() {
                for (var transform : transforms) if (!transform.transform(this)) return;
                delegate.copyFrom(this).emit();
            }
        };
        RenderContext[] holder = new RenderContext[1];
        holder[0] = (RenderContext) Proxy.newProxyInstance(RenderContext.class.getClassLoader(), new Class<?>[]{RenderContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getEmitter" -> emitter;
                    case "pushTransform" -> { transforms.push((RenderContext.QuadTransform) args[0]); yield null; }
                    case "popTransform" -> { transforms.pop(); yield null; }
                    case "hasTransform" -> !transforms.isEmpty();
                    case "isFaceCulled" -> false;
                    case "fallbackConsumer" -> (Consumer<BakedModel>) child -> output(child, holder[0]);
                    default -> throw new AssertionError("Unexpected context query " + method);
                });
        output(model, holder[0]);
        require(transforms.isEmpty(), "Child render leaked a transform");
        List<float[]> result = new ArrayList<>();
        builder.build().forEach(quad -> {
            float[] values = new float[23];
            values[0] = quad.cullFace() == null ? -1 : quad.cullFace().ordinal();
            values[1] = quad.nominalFace() == null ? -1 : quad.nominalFace().ordinal();
            values[2] = quad.colorIndex();
            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = 3 + vertex * 5;
                values[offset] = quad.x(vertex); values[offset + 1] = quad.y(vertex); values[offset + 2] = quad.z(vertex);
                values[offset + 3] = quad.u(vertex); values[offset + 4] = quad.v(vertex);
            }
            result.add(values);
        });
        // Vanilla buckets group cull directions; shared meshes retain authored
        // ordering. Compare the complete multiset without deduplicating faces.
        result.sort((a, b) -> {
            for (int i = 0; i < a.length; i++) {
                int order = Float.compare(a[i], b[i]);
                if (order != 0) return order;
            }
            return 0;
        });
        return result;
    }

    @SuppressWarnings("removal")
    private static void output(BakedModel model, RenderContext context) {
        if (!model.isVanillaAdapter()) {
            ((FabricBakedModel) model).emitBlockQuads(VIEW, Blocks.STONE.getDefaultState(), BlockPos.ORIGIN,
                    () -> Random.create(0), context);
            return;
        }
        for (int face = -1; face < 6; face++) {
            Direction direction = face < 0 ? null : Direction.values()[face];
            for (var quad : model.getQuads(null, direction, Random.create(0)))
                context.getEmitter().fromVanilla(quad, IndigoRenderer.INSTANCE.materialFinder().find(), direction).emit();
        }
    }

    private static void same(List<float[]> before, List<float[]> after, String label) {
        require(before.size() == after.size(), label + " lost or duplicated emitted faces");
        for (int q = 0; q < before.size(); q++) for (int i = 0; i < before.get(q).length; i++) {
            float a = before.get(q)[i], b = after.get(q)[i];
            boolean uv = i >= 3 && (i - 3) % 5 >= 3;
            require(uv ? Math.abs(a - b) <= 1e-6F : Float.floatToIntBits(a) == Float.floatToIntBits(b),
                    label + " changed emitted face " + q + " attribute " + i + ": " + a + " != " + b);
        }
    }

    /** The real Continuity wrapper with an instrumented processor and a deterministic atlas lookup. */
    private static final class ContinuityFixture implements AutoCloseable {
        final boolean present = FabricLoader.getInstance().isModLoaded("continuity");
        int processed;
        Field singleton, finder;
        Object previousClient, previousFinder, slice;
        Class<?> ctm;

        ContinuityFixture(Sprite sprite) throws Exception {
            if (!present) return;
            singleton = field(MinecraftClient.class, "instance"); previousClient = singleton.get(null);
            if (previousClient == null) singleton.set(null, allocate(MinecraftClient.class));
            Class<?> util = Class.forName("me.pepperbell.continuity.client.util.RenderUtil");
            finder = field(util, "blockAtlasSpriteFinder"); previousFinder = finder.get(null);
            finder.set(null, (SpriteFinder) Proxy.newProxyInstance(SpriteFinder.class.getClassLoader(),
                    new Class<?>[]{SpriteFinder.class}, (proxy, method, args) -> sprite));
            Class<?> processor = Class.forName("me.pepperbell.continuity.api.client.QuadProcessor");
            Class<?> result = Class.forName("me.pepperbell.continuity.api.client.QuadProcessor$ProcessingResult");
            Object next = result.getField("NEXT_PROCESSOR").get(null);
            Object instrument = Proxy.newProxyInstance(processor.getClassLoader(), new Class<?>[]{processor},
                    (proxy, method, args) -> { processed++; return next; });
            Object processors = Array.newInstance(processor, 1); Array.set(processors, 0, instrument);
            Object none = Array.newInstance(processor, 0);
            Class<?> sliceType = Class.forName("me.pepperbell.continuity.client.model.QuadProcessors$Slice");
            slice = sliceType.getConstructor(processors.getClass(), processors.getClass()).newInstance(processors, none);
            ctm = Class.forName("me.pepperbell.continuity.client.model.CtmBakedModel");
        }

        BakedModel wrap(BakedModel source) throws Exception {
            BakedModel model = (BakedModel) ctm.getConstructor(BakedModel.class, BlockState.class)
                    .newInstance(source, Blocks.STONE.getDefaultState());
            field(ctm, "defaultSliceFunc").set(model, (Function<Sprite, Object>) ignored -> slice);
            return model;
        }

        @Override public void close() throws Exception {
            if (!present) return;
            finder.set(null, previousFinder); singleton.set(null, previousClient);
        }
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object allocate(Class<?> owner) throws Exception {
        return ((sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null)).allocateInstance(owner);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
