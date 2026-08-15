package com.oliver.erydon.client.model;

import com.oliver.erydon.block.StairsSpiralLargeBlock;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.fabricmc.fabric.api.renderer.v1.model.ModelHelper;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Renders the large spiral stair in world-aligned repeat-CTM cells. Its rotated treads
 * physically span neighbouring blocks, which cannot be represented by one atlas sprite.
 */
public final class SpiralStairBakedModel extends ForwardingBakedModel {
    private static final Object MATERIAL_LOCK = new Object();
    private static final Object GEOMETRY_LOCK = new Object();
    private static final Map<StateKey, List<QuadTemplate>> GEOMETRY_BY_STATE = new ConcurrentHashMap<>();

    private static volatile RenderMaterial standardMaterial;
    private static volatile RenderMaterial noAmbientOcclusionMaterial;

    private final String ctmSet;

    public SpiralStairBakedModel(BakedModel wrapped, String ctmSet) {
        this.wrapped = wrapped;
        this.ctmSet = ctmSet;
    }

    @Override
    public boolean isVanillaAdapter() {
        return false;
    }

    @Override
    @SuppressWarnings("removal")
    public void emitBlockQuads(BlockRenderView view,
                               BlockState state,
                               BlockPos pos,
                               Supplier<Random> randomSupplier,
                               RenderContext context) {
        if (!(state.getBlock() instanceof StairsSpiralLargeBlock)
                || !state.contains(StairsSpiralLargeBlock.FACING)
                || !state.contains(StairsSpiralLargeBlock.PART)
                || !state.contains(StairsSpiralLargeBlock.CAP)
                || !ensureMaterials()) {
            wrapped.emitBlockQuads(view, state, pos, randomSupplier, context);
            return;
        }

        if (ctmSet == null) {
            wrapped.emitBlockQuads(view, state, pos, randomSupplier, context);
            return;
        }
        ErydonCtmService ctmService = ErydonCtmService.get(null);
        Sprite[] repeatSprites = ctmService.repeatSprites(ctmSet);

        StateKey key = new StateKey(
                state.get(StairsSpiralLargeBlock.FACING),
                state.get(StairsSpiralLargeBlock.PART),
                state.get(StairsSpiralLargeBlock.CAP)
        );
        List<QuadTemplate> templates = geometryForState(key, state, randomSupplier, context.getEmitter());
        QuadEmitter emitter = context.getEmitter();

        for (QuadTemplate template : templates) {
            if (!context.hasTransform()
                    && template.cullFace != null
                    && context.isFaceCulled(template.cullFace)) {
                continue;
            }

            int tileIndex = ErydonCtmService.repeatTileIndex(
                    pos.getX() + template.offsetX,
                    pos.getY() + template.offsetY,
                    pos.getZ() + template.offsetZ,
                    template.lightFace
            );
            Sprite sprite = repeatSprites[tileIndex];
            emitTemplate(emitter, template, sprite);
        }
    }

    static void clearGeometryCache() {
        GEOMETRY_BY_STATE.clear();
    }

    private List<QuadTemplate> geometryForState(StateKey key,
                                                BlockState state,
                                                Supplier<Random> randomSupplier,
                                                QuadEmitter decoder) {
        List<QuadTemplate> cached = GEOMETRY_BY_STATE.get(key);
        if (cached != null) {
            return cached;
        }

        synchronized (GEOMETRY_LOCK) {
            cached = GEOMETRY_BY_STATE.get(key);
            if (cached == null) {
                cached = buildGeometry(state, randomSupplier, decoder);
                GEOMETRY_BY_STATE.put(key, cached);
            }
            return cached;
        }
    }

    private List<QuadTemplate> buildGeometry(BlockState state,
                                             Supplier<Random> randomSupplier,
                                             QuadEmitter decoder) {
        RenderMaterial defaultMaterial = wrapped.useAmbientOcclusion()
                ? standardMaterial
                : noAmbientOcclusionMaterial;
        List<QuadTemplate> result = new ArrayList<>();

        for (int faceIndex = 0; faceIndex <= ModelHelper.NULL_FACE_ID; faceIndex++) {
            Direction sourceCullFace = ModelHelper.faceFromIndex(faceIndex);
            List<BakedQuad> quads = wrapped.getQuads(state, sourceCullFace, randomSupplier.get());
            for (BakedQuad quad : quads) {
                decoder.fromVanilla(quad, defaultMaterial, sourceCullFace);
                Direction lightFace = decoder.lightFace();
                if (lightFace == null) {
                    continue;
                }

                List<SpiralStairCtmGeometry.Vertex> vertices = new ArrayList<>(4);
                for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++) {
                    boolean hasNormal = decoder.hasNormal(vertexIndex);
                    vertices.add(new SpiralStairCtmGeometry.Vertex(
                            decoder.x(vertexIndex),
                            decoder.y(vertexIndex),
                            decoder.z(vertexIndex),
                            decoder.color(vertexIndex),
                            decoder.lightmap(vertexIndex),
                            hasNormal,
                            hasNormal ? decoder.normalX(vertexIndex) : 0.0F,
                            hasNormal ? decoder.normalY(vertexIndex) : 0.0F,
                            hasNormal ? decoder.normalZ(vertexIndex) : 0.0F
                    ));
                }

                SourceProperties properties = new SourceProperties(
                        lightFace,
                        decoder.nominalFace(),
                        sourceCullFace,
                        decoder.material(),
                        decoder.colorIndex(),
                        decoder.tag()
                );
                for (SpiralStairCtmGeometry.Fragment fragment : SpiralStairCtmGeometry.split(lightFace, vertices)) {
                    appendFragment(result, properties, fragment);
                }
            }
        }
        return List.copyOf(result);
    }

    private static void appendFragment(List<QuadTemplate> output,
                                       SourceProperties properties,
                                       SpiralStairCtmGeometry.Fragment fragment) {
        List<SpiralStairCtmGeometry.CellVertex> vertices = fragment.vertices();
        int offsetX = SpiralStairCtmGeometry.offsetX(properties.lightFace, fragment);
        int offsetY = SpiralStairCtmGeometry.offsetY(properties.lightFace, fragment);
        int offsetZ = SpiralStairCtmGeometry.offsetZ(properties.lightFace, fragment);
        Direction cullFace = offsetX == 0 && offsetY == 0 && offsetZ == 0
                ? properties.cullFace
                : null;

        if (vertices.size() == 3) {
            output.add(template(properties, cullFace, offsetX, offsetY, offsetZ,
                    vertices.get(0), vertices.get(1), vertices.get(2), vertices.get(2)));
            return;
        }
        if (vertices.size() == 4) {
            output.add(template(properties, cullFace, offsetX, offsetY, offsetZ,
                    vertices.get(0), vertices.get(1), vertices.get(2), vertices.get(3)));
            return;
        }

        SpiralStairCtmGeometry.CellVertex first = vertices.get(0);
        for (int index = 1; index < vertices.size() - 1; index++) {
            SpiralStairCtmGeometry.CellVertex second = vertices.get(index);
            SpiralStairCtmGeometry.CellVertex third = vertices.get(index + 1);
            output.add(template(properties, cullFace, offsetX, offsetY, offsetZ,
                    first, second, third, third));
        }
    }

    private static QuadTemplate template(SourceProperties properties,
                                         Direction cullFace,
                                         int offsetX,
                                         int offsetY,
                                         int offsetZ,
                                         SpiralStairCtmGeometry.CellVertex first,
                                         SpiralStairCtmGeometry.CellVertex second,
                                         SpiralStairCtmGeometry.CellVertex third,
                                         SpiralStairCtmGeometry.CellVertex fourth) {
        return new QuadTemplate(
                properties.lightFace,
                properties.nominalFace,
                cullFace,
                properties.material,
                properties.colorIndex,
                properties.tag,
                offsetX,
                offsetY,
                offsetZ,
                new SpiralStairCtmGeometry.CellVertex[]{first, second, third, fourth}
        );
    }

    private static void emitTemplate(QuadEmitter emitter, QuadTemplate template, Sprite sprite) {
        emitter.material(template.material);
        emitter.colorIndex(template.colorIndex);
        emitter.tag(template.tag);
        emitter.cullFace(template.cullFace);
        emitter.nominalFace(template.nominalFace == null ? template.lightFace : template.nominalFace);

        for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++) {
            SpiralStairCtmGeometry.CellVertex cellVertex = template.vertices[vertexIndex];
            SpiralStairCtmGeometry.Vertex vertex = cellVertex.vertex();
            emitter.pos(vertexIndex, vertex.x(), vertex.y(), vertex.z());
            emitter.uv(
                    vertexIndex,
                    SpiralStairCtmGeometry.u(template.lightFace, cellVertex),
                    SpiralStairCtmGeometry.v(template.lightFace, cellVertex)
            );
            emitter.color(vertexIndex, vertex.color());
            emitter.lightmap(vertexIndex, vertex.lightmap());
            if (vertex.hasNormal()) {
                emitter.normal(vertexIndex, vertex.normalX(), vertex.normalY(), vertex.normalZ());
            }
        }
        emitter.spriteBake(sprite, MutableQuadView.BAKE_NORMALIZED);
        emitter.emit();
    }

    private static boolean ensureMaterials() {
        if (standardMaterial != null && noAmbientOcclusionMaterial != null) {
            return true;
        }
        Renderer renderer = RendererAccess.INSTANCE.getRenderer();
        if (renderer == null) {
            return false;
        }

        synchronized (MATERIAL_LOCK) {
            if (standardMaterial == null || noAmbientOcclusionMaterial == null) {
                standardMaterial = renderer.materialFinder().clear().find();
                noAmbientOcclusionMaterial = renderer.materialFinder()
                        .clear()
                        .ambientOcclusion(TriState.FALSE)
                        .find();
            }
        }
        return true;
    }

    private record StateKey(Direction facing,
                            StairsSpiralLargeBlock.Part part,
                            boolean cap) {
    }

    private record SourceProperties(Direction lightFace,
                                    Direction nominalFace,
                                    Direction cullFace,
                                    RenderMaterial material,
                                    int colorIndex,
                                    int tag) {
    }

    private record QuadTemplate(Direction lightFace,
                                Direction nominalFace,
                                Direction cullFace,
                                RenderMaterial material,
                                int colorIndex,
                                int tag,
                                int offsetX,
                                int offsetY,
                                int offsetZ,
                                SpiralStairCtmGeometry.CellVertex[] vertices) {
    }
}
