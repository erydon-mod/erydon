package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.BlendMode;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Applies all active ERYDON repeat and connected-overlay rules to emitted world quads. */
final class SynapheiaRepeatBakedModel extends ForwardingBakedModel {
    private static final Object MATERIAL_LOCK = new Object();
    private static final float UNIT_EPSILON = 0.0001F;

    private static volatile SpriteFinder spriteFinder;
    private static volatile RenderMaterial translucentAoMaterial;
    private static volatile RenderMaterial translucentNoAoMaterial;

    private final ModelIdentifier modelId;
    private final Identifier blockId;
    private final SynapheiaService.Snapshot snapshot;

    SynapheiaRepeatBakedModel(BakedModel wrapped,
                             ModelIdentifier modelId,
                             Identifier blockId,
                             SynapheiaService.Snapshot snapshot) {
        this.wrapped = wrapped;
        this.modelId = modelId;
        this.blockId = blockId;
        this.snapshot = snapshot;
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
        if (snapshot.generation() != SynapheiaService.current().generation()
                || !blockId.equals(Registries.BLOCK.getId(state.getBlock()))) {
            wrapped.emitBlockQuads(view, state, pos, randomSupplier, context);
            return;
        }

        long startedNanos = System.nanoTime();
        List<CapturedQuad> captured = new ArrayList<>();
        context.pushTransform(quad -> {
            captured.add(capture(quad));
            return false;
        });
        try {
            wrapped.emitBlockQuads(view, state, pos, randomSupplier, context);
        } finally {
            context.popTransform();
        }

        QuadEmitter emitter = context.getEmitter();
        Map<OverlayKey, SynapheiaManifest.Rule> overlays = new LinkedHashMap<>();
        int emitted = 0;
        for (CapturedQuad quad : captured) {
            SynapheiaManifest.Rule repeatRule = snapshot.repeatRuleFor(
                    blockId, quad.lightFace(), quad.sourceSprite());
            if (repeatRule == null) {
                emitOriginal(emitter, quad);
                emitted++;
            } else {
                emitted += emitRepeat(emitter, quad, repeatRule, state, pos);
            }

            if (isUnitSquare(quad)) {
                for (SynapheiaManifest.Rule overlayRule : snapshot.overlayRulesFor(
                        blockId, quad.lightFace(), quad.sourceSprite())) {
                    overlays.putIfAbsent(new OverlayKey(overlayRule.id(), quad.lightFace()), overlayRule);
                }
            }
        }
        for (Map.Entry<OverlayKey, SynapheiaManifest.Rule> entry : overlays.entrySet()) {
            emitOverlay(emitter, view, state, pos, entry.getKey().face(), entry.getValue());
            emitted++;
        }

        SynapheiaMetrics.event("chunk_rebuild_phase", SynapheiaMode.SYNAPHEIA,
                snapshot.generation(), fields(
                        "phase", "synapheia_ctm", "state", "sample",
                        "duration_ns", System.nanoTime() - startedNanos,
                        "input_surface_count", captured.size(),
                        "emitted_surface_count", emitted,
                        "overlay_surface_count", overlays.size(),
                        "model_identifier", modelId.toString(), "block_id", blockId.toString()
                ));
    }

    static void clearCaches() {
        spriteFinder = null;
        translucentAoMaterial = null;
        translucentNoAoMaterial = null;
    }

    static Direction cullFaceForOffset(Direction sourceCullFace,
                                       int offsetX,
                                       int offsetY,
                                       int offsetZ) {
        return offsetX == 0 && offsetY == 0 && offsetZ == 0 ? sourceCullFace : null;
    }

    static int connectedTileIndex(int mask) {
        return switch (mask) {
            case 0 -> 0;
            case 1 -> 3;
            case 4 -> 12;
            case 5 -> 5;
            case 7 -> 15;
            case 16 -> 1;
            case 17 -> 2;
            case 20 -> 4;
            case 21 -> 7;
            case 23 -> 29;
            case 28 -> 13;
            case 29 -> 31;
            case 31 -> 14;
            case 64 -> 36;
            case 65 -> 17;
            case 68 -> 24;
            case 69 -> 19;
            case 71 -> 43;
            case 80 -> 16;
            case 81 -> 18;
            case 84 -> 6;
            case 85 -> 46;
            case 87 -> 21;
            case 92 -> 28;
            case 93 -> 9;
            case 95 -> 22;
            case 112 -> 37;
            case 113 -> 40;
            case 116 -> 30;
            case 117 -> 8;
            case 119 -> 34;
            case 124 -> 25;
            case 125 -> 23;
            case 127 -> 45;
            case 193 -> 39;
            case 197 -> 41;
            case 199 -> 27;
            case 209 -> 42;
            case 213 -> 20;
            case 215 -> 10;
            case 221 -> 35;
            case 223 -> 44;
            case 241 -> 38;
            case 245 -> 11;
            case 247 -> 32;
            case 253 -> 33;
            case 255 -> 26;
            default -> throw new IllegalArgumentException("Invalid connected-texture mask " + mask + ".");
        };
    }

    private static CapturedQuad capture(MutableQuadView quad) {
        Sprite sprite = spriteFinder().find(quad);
        List<SpiralStairCtmGeometry.Vertex> vertices = new ArrayList<>(4);
        for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++) {
            boolean hasNormal = quad.hasNormal(vertexIndex);
            vertices.add(new SpiralStairCtmGeometry.Vertex(
                    quad.x(vertexIndex), quad.y(vertexIndex), quad.z(vertexIndex),
                    quad.color(vertexIndex), quad.lightmap(vertexIndex), hasNormal,
                    hasNormal ? quad.normalX(vertexIndex) : 0.0F,
                    hasNormal ? quad.normalY(vertexIndex) : 0.0F,
                    hasNormal ? quad.normalZ(vertexIndex) : 0.0F,
                    quad.u(vertexIndex), quad.v(vertexIndex)
            ));
        }
        return new CapturedQuad(quad.lightFace(), quad.nominalFace(), quad.cullFace(),
                quad.material(), quad.colorIndex(), quad.tag(), sprite.getContents().getId(),
                List.copyOf(vertices));
    }

    private int emitRepeat(QuadEmitter emitter,
                           CapturedQuad quad,
                           SynapheiaManifest.Rule rule,
                           BlockState state,
                           BlockPos pos) {
        List<SpiralStairCtmGeometry.Fragment> fragments =
                SpiralStairCtmGeometry.split(quad.lightFace(), quad.vertices());
        if (fragments.isEmpty()) {
            throw new IllegalStateException("Synapheia could not split " + modelId
                    + " face " + quad.lightFace() + ".");
        }
        List<Sprite> sprites = SynapheiaService.sprites(snapshot, rule);
        if (sprites == null || sprites.size() != 36) {
            throw new IllegalStateException("Synapheia repeat sprites are unavailable for " + rule.resourceId() + ".");
        }

        int emitted = 0;
        for (SpiralStairCtmGeometry.Fragment fragment : fragments) {
            List<SpiralStairCtmGeometry.CellVertex> vertices = fragment.vertices();
            int offsetX = SpiralStairCtmGeometry.offsetX(quad.lightFace(), fragment);
            int offsetY = SpiralStairCtmGeometry.offsetY(quad.lightFace(), fragment);
            int offsetZ = SpiralStairCtmGeometry.offsetZ(quad.lightFace(), fragment);
            int tileIndex = ErydonCtmService.repeatTileIndex(
                    pos.getX() + offsetX, pos.getY() + offsetY, pos.getZ() + offsetZ,
                    quad.lightFace());
            Direction cullFace = cullFaceForOffset(quad.cullFace(), offsetX, offsetY, offsetZ);

            if (vertices.size() == 3) {
                emitRepeatPrimitive(emitter, quad, cullFace,
                        padTriangle(vertices.get(0), vertices.get(1), vertices.get(2)),
                        sprites.get(tileIndex));
                emitted++;
            } else if (vertices.size() == 4) {
                emitRepeatPrimitive(emitter, quad, cullFace, vertices, sprites.get(tileIndex));
                emitted++;
            } else {
                SpiralStairCtmGeometry.CellVertex first = vertices.get(0);
                for (int index = 1; index < vertices.size() - 1; index++) {
                    emitRepeatPrimitive(emitter, quad, cullFace,
                            padTriangle(first, vertices.get(index), vertices.get(index + 1)),
                            sprites.get(tileIndex));
                    emitted++;
                }
            }
        }
        SynapheiaMetrics.event("stage_invoked", SynapheiaMode.SYNAPHEIA, snapshot.generation(), fields(
                "stage", "repeat", "model_identifier", modelId.toString(),
                "block_id", blockId.toString(), "rule_id", rule.id(),
                "block_pos", List.of(pos.getX(), pos.getY(), pos.getZ()),
                "emitted_surface_count", emitted, "blockstate", state.toString()
        ));
        return emitted;
    }

    static <T> List<T> padTriangle(T first, T second, T third) {
        // Indium may rotate a quad to use the opposite AO diagonal. Repeating the first
        // vertex keeps the first three vertices non-degenerate in either orientation,
        // which Iris requires when deriving the PBR tangent.
        return List.of(first, second, third, first);
    }

    private static void emitRepeatPrimitive(QuadEmitter emitter,
                                            CapturedQuad source,
                                            Direction cullFace,
                                            List<SpiralStairCtmGeometry.CellVertex> vertices,
                                            Sprite sprite) {
        emitter.material(source.material());
        emitter.colorIndex(source.colorIndex());
        emitter.tag(source.tag());
        emitter.cullFace(cullFace);
        emitter.nominalFace(source.nominalFace());
        for (int index = 0; index < 4; index++) {
            SpiralStairCtmGeometry.CellVertex cellVertex = vertices.get(index);
            SpiralStairCtmGeometry.Vertex vertex = cellVertex.vertex();
            emitter.pos(index, vertex.x(), vertex.y(), vertex.z());
            emitter.uv(index, SpiralStairCtmGeometry.u(source.lightFace(), cellVertex),
                    SpiralStairCtmGeometry.v(source.lightFace(), cellVertex));
            emitter.color(index, vertex.color());
            emitter.lightmap(index, vertex.lightmap());
            if (vertex.hasNormal()) {
                emitter.normal(index, vertex.normalX(), vertex.normalY(), vertex.normalZ());
            }
        }
        emitter.spriteBake(sprite, MutableQuadView.BAKE_NORMALIZED);
        emitter.emit();
    }

    private static void emitOriginal(QuadEmitter emitter, CapturedQuad quad) {
        emitter.material(quad.material());
        emitter.colorIndex(quad.colorIndex());
        emitter.tag(quad.tag());
        emitter.cullFace(quad.cullFace());
        emitter.nominalFace(quad.nominalFace());
        for (int index = 0; index < 4; index++) {
            SpiralStairCtmGeometry.Vertex vertex = quad.vertices().get(index);
            emitter.pos(index, vertex.x(), vertex.y(), vertex.z());
            emitter.uv(index, vertex.sourceU(), vertex.sourceV());
            emitter.color(index, vertex.color());
            emitter.lightmap(index, vertex.lightmap());
            if (vertex.hasNormal()) {
                emitter.normal(index, vertex.normalX(), vertex.normalY(), vertex.normalZ());
            }
        }
        emitter.emit();
    }

    private void emitOverlay(QuadEmitter emitter,
                             BlockRenderView view,
                             BlockState state,
                             BlockPos pos,
                             Direction face,
                             SynapheiaManifest.Rule rule) {
        List<Sprite> sprites = SynapheiaService.sprites(snapshot, rule);
        if (sprites == null || sprites.size() != 47) {
            throw new IllegalStateException("Synapheia overlay sprites are unavailable for " + rule.resourceId() + ".");
        }
        int mask = connectionMask(view, state, pos, face, rule.innerSeams());
        int tileIndex = connectedTileIndex(mask);
        RenderMaterial material = overlayMaterial(state.getLuminance() == 0);
        emitter.square(face, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F);
        emitter.color(-1, -1, -1, -1);
        emitter.colorIndex(-1);
        emitter.uvUnitSquare();
        emitter.spriteBake(sprites.get(tileIndex), MutableQuadView.BAKE_NORMALIZED);
        emitter.material(material);
        emitter.emit();

        SynapheiaMetrics.event("tile_selected", SynapheiaMode.SYNAPHEIA, snapshot.generation(), fields(
                "stage", "overlay_ctm", "rule_id", rule.id(), "tile_index", tileIndex,
                "connection_mask", mask, "face_direction", face.getName(),
                "block_pos", List.of(pos.getX(), pos.getY(), pos.getZ())
        ));
    }

    private static int connectionMask(BlockRenderView view,
                                      BlockState state,
                                      BlockPos pos,
                                      Direction face,
                                      boolean innerSeams) {
        Direction[] directions = faceDirections(face);
        int mask = 0;
        for (int index = 0; index < 4; index++) {
            if (connects(view, state, pos.offset(directions[index]), face, innerSeams)) {
                mask |= 1 << (index * 2);
            }
        }
        for (int index = 0; index < 4; index++) {
            int next = (index + 1) & 3;
            int firstBit = 1 << (index * 2);
            int nextBit = 1 << (next * 2);
            if ((mask & firstBit) != 0 && (mask & nextBit) != 0
                    && connects(view, state, pos.offset(directions[index]).offset(directions[next]),
                    face, innerSeams)) {
                mask |= 1 << (index * 2 + 1);
            }
        }
        return mask;
    }

    private static boolean connects(BlockRenderView view,
                                    BlockState state,
                                    BlockPos target,
                                    Direction face,
                                    boolean innerSeams) {
        if (view.getBlockState(target).getBlock() != state.getBlock()) {
            return false;
        }
        return !innerSeams || view.getBlockState(target.offset(face)).getBlock() != state.getBlock();
    }

    private static Direction[] faceDirections(Direction face) {
        Direction vertical = face == Direction.UP ? Direction.NORTH
                : face == Direction.DOWN ? Direction.SOUTH : Direction.UP;
        Direction horizontal = face.getDirection() == Direction.AxisDirection.NEGATIVE
                ? vertical.rotateClockwise(face.getAxis())
                : vertical.rotateCounterclockwise(face.getAxis());
        return new Direction[]{horizontal, vertical.getOpposite(), horizontal.getOpposite(), vertical};
    }

    private static boolean isUnitSquare(CapturedQuad quad) {
        if (quad.lightFace() == null) {
            return false;
        }
        for (SpiralStairCtmGeometry.Vertex vertex : quad.vertices()) {
            float first;
            float second;
            switch (quad.lightFace().getAxis()) {
                case X -> { first = vertex.y(); second = vertex.z(); }
                case Y -> { first = vertex.x(); second = vertex.z(); }
                case Z -> { first = vertex.y(); second = vertex.x(); }
                default -> throw new IllegalStateException("Unexpected face axis.");
            }
            if (!unitBoundary(first) || !unitBoundary(second)) {
                return false;
            }
        }
        return true;
    }

    private static boolean unitBoundary(float value) {
        return Math.abs(value) < UNIT_EPSILON || Math.abs(value - 1.0F) < UNIT_EPSILON;
    }

    private static SpriteFinder spriteFinder() {
        SpriteFinder current = spriteFinder;
        if (current == null) {
            current = SpriteFinder.get(MinecraftClient.getInstance().getBakedModelManager()
                    .getAtlas(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE));
            spriteFinder = current;
        }
        return current;
    }

    private static RenderMaterial overlayMaterial(boolean ambientOcclusion) {
        RenderMaterial current = ambientOcclusion ? translucentAoMaterial : translucentNoAoMaterial;
        if (current != null) {
            return current;
        }
        Renderer renderer = RendererAccess.INSTANCE.getRenderer();
        if (renderer == null) {
            throw new IllegalStateException("Synapheia requires the Fabric renderer API.");
        }
        synchronized (MATERIAL_LOCK) {
            current = ambientOcclusion ? translucentAoMaterial : translucentNoAoMaterial;
            if (current == null) {
                current = renderer.materialFinder().clear()
                        .blendMode(BlendMode.TRANSLUCENT)
                        .ambientOcclusion(ambientOcclusion ? TriState.TRUE : TriState.FALSE)
                        .find();
                if (ambientOcclusion) {
                    translucentAoMaterial = current;
                } else {
                    translucentNoAoMaterial = current;
                }
            }
        }
        return current;
    }

    private static Map<String, Object> fields(Object... entries) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            fields.put((String) entries[index], entries[index + 1]);
        }
        return fields;
    }

    private record CapturedQuad(Direction lightFace,
                                Direction nominalFace,
                                Direction cullFace,
                                RenderMaterial material,
                                int colorIndex,
                                int tag,
                                Identifier sourceSprite,
                                List<SpiralStairCtmGeometry.Vertex> vertices) {
    }

    private record OverlayKey(String ruleId, Direction face) {
    }
}
