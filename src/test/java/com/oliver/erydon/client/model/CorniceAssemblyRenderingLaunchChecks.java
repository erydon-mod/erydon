package com.oliver.erydon.client.model;

import com.oliver.erydon.block.CorniceAssembly;
import com.oliver.erydon.block.CorniceBlock;
import com.oliver.erydon.block.CoverBlock;
import com.oliver.erydon.client.pom.CornicePartMaterialTransport;
import com.oliver.erydon.client.pom.InlaySubstrateTransport;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.api.rendering.data.v1.RenderAttachedBlockView;
import net.fabricmc.fabric.api.util.TriState;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.compat.sodium.impl.block_context.BlockContextHolder;
import net.irisshaders.iris.compat.sodium.impl.vertex_format.terrain_xhfp.XHFPTerrainVertex;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Real Fabric quad output and Iris vertex writes retain each shared-cell part's authored identity. */
public final class CorniceAssemblyRenderingLaunchChecks {
    private CorniceAssemblyRenderingLaunchChecks() { }

    public static void run() {
        if (!RendererAccess.INSTANCE.hasRenderer()) RendererAccess.INSTANCE.registerRenderer(IndigoRenderer.INSTANCE);
        BlockState carrier = block("cornice_assembly").getBlock().getStateManager().getStates().stream()
                .filter(state -> state.getLuminance() == 15).findFirst().orElseThrow();
        BlockState first = block("glacium_cornice_modern");
        BlockState second = block("glacium_cornice_georgian").with(CorniceBlock.FACING, Direction.EAST);
        BlockState cover = block("glacium_cover").getBlock().getStateManager().getStates().stream()
                .filter(state -> state.getLuminance() == 15 && state.get(CoverBlock.FINISH) == CoverBlock.CoverFinish.GLOSS)
                .findFirst().orElseThrow();
        require(cover.getLuminance() == 15, "Lit cover fixture must exercise carrier-versus-part AO");
        var contents = new CorniceAssembly.Contents(List.of(first, second), cover);
        BlockRenderView view = (BlockRenderView) Proxy.newProxyInstance(BlockRenderView.class.getClassLoader(),
                new Class<?>[]{RenderAttachedBlockView.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getBlockState" -> BlockPos.ORIGIN.equals(args[0]) ? carrier : Blocks.AIR.getDefaultState();
                    case "getBlockEntityRenderAttachment" -> BlockPos.ORIGIN.equals(args[0]) ? contents : null;
                    case "getBlockEntity" -> null;
                    default -> throw new AssertionError("Unexpected assembly view query " + method.getName());
                });
        int[] originalModels = {0}, fallbackSelections = {0};
        Map<BlockState, BakedModel> models = Map.of(
                first, direct(first, contents, 0x101, false, originalModels),
                second, direct(second, contents, 0x202, true, originalModels),
                cover, new CoverBakedModel(vanillaCover(cover, fallbackSelections)));
        var model = new CorniceAssemblyBakedModel(vanillaCover(cover, fallbackSelections), models::get, () -> true);
        var builder = RendererAccess.INSTANCE.getRenderer().meshBuilder();
        var emitter = builder.getEmitter();
        List<RenderContext.QuadTransform> transforms = new ArrayList<>();
        QuadEmitter captureEmitter = (QuadEmitter) Proxy.newProxyInstance(QuadEmitter.class.getClassLoader(),
                new Class<?>[]{QuadEmitter.class}, (proxy, method, args) -> {
                    if (method.getName().equals("emit")) {
                        for (int index = transforms.size() - 1; index >= 0; index--)
                            require(transforms.get(index).transform((MutableQuadView) emitter), "Unexpected filtered fixture quad");
                    }
                    Object value = method.invoke(emitter, args);
                    return value == emitter ? proxy : value;
                });
        RenderContext context = (RenderContext) Proxy.newProxyInstance(RenderContext.class.getClassLoader(),
                new Class<?>[]{RenderContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getEmitter" -> captureEmitter;
                    case "hasTransform" -> !transforms.isEmpty();
                    case "pushTransform" -> { transforms.add((RenderContext.QuadTransform) args[0]); yield null; }
                    case "popTransform" -> { transforms.remove(transforms.size() - 1); yield null; }
                    case "bakedModelConsumer", "fallbackConsumer" -> consumer(captureEmitter, carrier);
                    default -> throw new AssertionError("Unexpected assembly context query " + method.getName());
                });
        model.emitBlockQuads(view, carrier, BlockPos.ORIGIN, () -> Random.create(0), context);
        int[] count = {0};
        builder.build().forEach(quad -> {
            count[0]++;
            if (quad.tag() == 0x101) require(quad.material().ambientOcclusion() == TriState.TRUE,
                    "Lit carrier disabled AO on its unlit cornice");
            else if (quad.tag() == 0x202) require(quad.material().ambientOcclusion() == TriState.FALSE,
                    "Explicit authored AO was overwritten");
            else require(quad.material().ambientOcclusion() == TriState.FALSE, "Lit cover lost its native AO policy");
        });
        require(count[0] == 3 && originalModels[0] == 2 && fallbackSelections[0] == 1,
                "Shared cell duplicated or omitted an original cornice/Cover model");
        require(transforms.isEmpty() && CornicePartMaterialTransport.current() == null,
                "Assembly emission leaked a transform or source identity");
        require(model.getQuads(carrier, null, Random.create(0)).isEmpty(),
                "NBT-free preview invented part material or missing-model geometry");
        new CorniceAssemblyBakedModel(vanillaCover(cover, fallbackSelections), models::get, () -> false)
                .emitBlockQuads(view, carrier, BlockPos.ORIGIN, () -> Random.create(0), context);
        int[] flat = {0};
        builder.build().forEach(quad -> {
            flat[0]++;
            require(quad.material().ambientOcclusion() == TriState.FALSE, "Global AO OFF was overridden by a shared-cell part");
        });
        require(flat[0] == 3 && transforms.isEmpty(), "AO OFF draw duplicated parts or leaked transforms");
        verifyIrisIdentity(first, cover);
        System.out.println("ERYDON_CORNICE_ASSEMBLY_RENDER_OK: original child models, explicit Cover fallback state, immutable sibling attachments, tags and per-part AO retained; three source parts emit once; state-only preview requires entity contents");
    }

    private static BakedModel direct(BlockState expected, CorniceAssembly.Contents contents,
                                     int tag, boolean explicitAo, int[] calls) {
        return new ForwardingBakedModel() {
            @Override public boolean isVanillaAdapter() { return false; }
            @Override public boolean useAmbientOcclusion() { return true; }
            @Override public void emitBlockQuads(BlockRenderView view, BlockState state, BlockPos pos,
                                                 Supplier<Random> random, RenderContext context) {
                require(state == expected && view.getBlockState(pos) == expected, "Child CTM/model received carrier identity");
                require(CorniceAssembly.contents(view, pos).equals(contents), "Part view hid its co-cell peer attachments");
                require(CornicePartMaterialTransport.unwrap(context) != context, "Source context lost its original renderer");
                calls[0]++;
                var material = RendererAccess.INSTANCE.getRenderer().materialFinder().clear()
                        .ambientOcclusion(explicitAo ? TriState.FALSE : TriState.DEFAULT).find();
                context.getEmitter().square(Direction.UP, 0, 0, 1, 1, .25F).material(material)
                        .color(-1, -1, -1, -1).tag(tag).emit();
            }
        };
    }

    private static BakedModel vanillaCover(BlockState expected, int[] calls) {
        return new ForwardingBakedModel() {
            @Override public boolean useAmbientOcclusion() { return true; }
            @Override public net.minecraft.client.texture.Sprite getParticleSprite() { return null; }
            @Override public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
                require(state == expected, "Vanilla Cover multipart queried with the assembly carrier state");
                calls[0]++;
                int[] data = new int[32];
                float[][] points = {{0,.5F,0},{0,.5F,1},{1,.5F,1},{1,.5F,0}};
                for (int vertex = 0; vertex < 4; vertex++) {
                    for (int axis = 0; axis < 3; axis++) data[vertex * 8 + axis] = Float.floatToRawIntBits(points[vertex][axis]);
                    data[vertex * 8 + 3] = -1;
                }
                return List.of(new BakedQuad(data, -1, Direction.UP, null, true));
            }
        };
    }

    @SuppressWarnings("removal")
    private static RenderContext.BakedModelConsumer consumer(QuadEmitter emitter, BlockState carrier) {
        return new RenderContext.BakedModelConsumer() {
            @Override public void accept(BakedModel model) { accept(model, carrier); }
            @Override public void accept(BakedModel model, BlockState state) {
                for (BakedQuad quad : model.getQuads(state, null, Random.create(0))) {
                    emitter.fromVanilla(quad, RendererAccess.INSTANCE.getRenderer().materialFinder().clear().find(), null).emit();
                }
            }
        };
    }

    private static void verifyIrisIdentity(BlockState cornice, BlockState cover) {
        if (!FabricLoader.getInstance().isModLoaded("iris")) return;
        require(InlaySubstrateTransport.rendererSupported(), "Pinned encoder contract must support assembly material identity");
        var ids = new Object2IntOpenHashMap<BlockState>();
        ids.put(cornice, 321); ids.put(cover, 12061);
        var holder = new BlockContextHolder(ids);
        holder.blockId = 17; holder.renderType = 0; holder.lightValue = 8;
        var writer = new XHFPTerrainVertex(); writer.iris$setContextHolder(holder);
        var vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();
        for (int index = 0; index < 4; index++) {
            vertices[index].x = index >= 2 ? 1 : 0; vertices[index].y = .75F;
            vertices[index].z = index == 1 || index == 2 ? 1 : 0;
            vertices[index].u = index >= 2 ? .5F : .25F; vertices[index].v = index == 1 || index == 2 ? .5F : .25F;
            vertices[index].color = -1; vertices[index].light = 0x00f000f0;
        }
        var material = new Material(null, AlphaCutoffParameter.HALF, true);
        long memory = MemoryUtil.nmemAlloc(160);
        try {
            writer.write(memory, material, vertices, 0);
            byte[] baseline = new byte[160];
            for (int index = 0; index < 160; index++) baseline[index] = MemoryUtil.memGetByte(memory + index);
            for (BlockState part : List.of(cornice, cover)) {
                BlockState previous = CornicePartMaterialTransport.push(part);
                try { writer.write(memory, material, vertices, 0); }
                finally { CornicePartMaterialTransport.restore(previous); }
                for (int vertex = 0; vertex < 4; vertex++) {
                    require(MemoryUtil.memGetShort(memory + vertex * 40L + 32) == ids.getInt(part), "Real Iris encoder lost source material");
                    require(MemoryUtil.memGetByte(memory + vertex * 40L + 39) == part.getLuminance(), "Real Iris encoder lost source luminance");
                }
                for (int index = 0; index < 160; index++) if (index % 40 != 32 && index % 40 != 33 && index % 40 != 39)
                    require(MemoryUtil.memGetByte(memory + index) == baseline[index], "Part identity modified unrelated vertex byte " + index);
            }
            require(holder.blockId == 17 && holder.lightValue == 8 && holder.renderType == 0,
                    "Part transport mutated or leaked the carrier's Iris context");
            BlockState previous = CornicePartMaterialTransport.push(cornice);
            require(CornicePartMaterialTransport.push(cover) == cornice, "Nested part identity lost its parent");
            CornicePartMaterialTransport.restore(cornice);
            require(CompletableFuture.supplyAsync(CornicePartMaterialTransport::current).join() == null,
                    "Part identity escaped its render worker");
            CornicePartMaterialTransport.restore(previous);
        } finally { MemoryUtil.nmemFree(memory); }
        System.out.println("ERYDON_CORNICE_ASSEMBLY_IRIS_IDENTITY_OK: actual pinned Iris encoder preserves original cornice/Cover mapped IDs and native luminance, changes only bytes32/33/39, and retains carrier context and worker isolation");
    }

    private static BlockState block(String path) {
        Identifier id = new Identifier("erydon", path);
        require(Registries.BLOCK.containsId(id), "Missing assembly render fixture " + id);
        return Registries.BLOCK.get(id).getDefaultState();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
