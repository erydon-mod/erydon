package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.client.pom.ErydonCuPomRuntimeState;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Supplier;

/** Applies all active ERYDON repeat and connected-overlay rules to emitted world quads. */
final class SynapheiaRepeatBakedModel extends ForwardingBakedModel {
    private static final Object MATERIAL_LOCK = new Object();
    private static final float UNIT_EPSILON = 0.0001F;
    private static final Offset[][] FACE_TANGENT_OFFSETS = createFaceTangentOffsets();
    private static final Set<String> WARNED_CROSS_CELL_SOURCE_OVERLAYS =
            ConcurrentHashMap.newKeySet();

    private static volatile SpriteFinder spriteFinder;
    private static final AtomicReferenceArray<RenderMaterial> OVERLAY_MATERIALS =
            new AtomicReferenceArray<>(SynapheiaManifest.OverlayLayer.values().length * 2);

    private final ModelIdentifier modelId;
    private final Identifier blockId;
    private final SynapheiaBlockPlan plan;
    private final SynapheiaService.Snapshot snapshot;
    private final boolean projectedRepeatGeometry;
    private final Identifier overlaySourceSpriteOverride;

    SynapheiaRepeatBakedModel(BakedModel wrapped,
                             ModelIdentifier modelId,
                             SynapheiaBlockPlan plan,
                             SynapheiaService.Snapshot snapshot) {
        this.wrapped = wrapped;
        this.modelId = modelId;
        this.blockId = plan.blockId();
        this.plan = plan;
        this.snapshot = snapshot;
        this.projectedRepeatGeometry = usesProjectedRepeatGeometry(this.blockId);
        this.overlaySourceSpriteOverride = ErydonSlopeModelClassifier.isHandledSlopeId(this.blockId)
                ? wrapped.getParticleSprite().getContents().getId()
                : null;
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

        boolean metricsEnabled = SynapheiaMetrics.enabled();
        long startedNanos = metricsEnabled ? System.nanoTime() : 0L;
        boolean geometryPomFallback = projectedRepeatGeometry
                && ErydonCuPomRuntimeState.requiresGeometryFallback();
        RenderCallState renderCall = new RenderCallState(plan.hasOverlay(), metricsEnabled);
        context.pushTransform(quad -> {
            renderCall.recordInputSurface();
            Identifier sourceSprite = spriteFinder().find(quad).getContents().getId();
            Identifier overlaySourceSprite = resolveOverlaySourceSprite(
                    blockId, sourceSprite, overlaySourceSpriteOverride);
            Direction face = quad.lightFace();
            if (renderCall.tracksOverlays()) {
                if (plan.hasSourceShapedOverlay()) {
                    observeOverlays(renderCall, quad, plan.overlayRules(face, overlaySourceSprite));
                } else if (isUnitSquare(quad)) {
                    renderCall.observeUnitOverlays(face, plan.overlayRules(face, overlaySourceSprite));
                }
            }

            SynapheiaManifest.Rule repeatRule = projectedRepeatGeometry
                    ? plan.repeatRuleForProjectedGeometry(face)
                    : plan.repeatRule(face, sourceSprite);
            SynapheiaCellGeometry.Cell cell = repeatRule == null || geometryPomFallback
                    ? null : SynapheiaCellGeometry.singleCell(face, quad);
            RepeatDisposition disposition = repeatDisposition(repeatRule, cell);
            if (disposition == RepeatDisposition.STREAM_UNCHANGED) {
                renderCall.recordStreamedSurface();
            } else if (disposition == RepeatDisposition.STREAM_SINGLE_CELL) {
                applySingleCellRepeat(quad, repeatRule, state, pos, cell);
                renderCall.recordStreamedSurface();
            } else {
                renderCall.captureCrossCell(capture(quad, repeatRule));
            }
            return disposition.streamsOriginal();
        });
        try {
            wrapped.emitBlockQuads(view, state, pos, randomSupplier, context);
        } finally {
            context.popTransform();
        }

        QuadEmitter emitter = context.getEmitter();
        int emitted = renderCall.streamedSurfaceCount();
        SynapheiaNeighbourCache neighbourCache = createNeighbourCache(
                renderCall.overlayCount() > 0, view, pos);
        Map<OverlayKey, OverlayTile> selectedOverlays = renderCall.overlayCount() == 0
                ? Map.of() : new LinkedHashMap<>(6);
        for (CapturedQuad quad : renderCall.crossCellQuads()) {
            emitted += emitRepeat(emitter, quad, quad.repeatRule(), state, pos, geometryPomFallback);
        }
        for (Map.Entry<OverlayKey, SynapheiaManifest.Rule> entry : renderCall.overlayEntries()) {
            emitOverlay(emitter, neighbourCache, selectedOverlays, state, pos,
                    entry.getKey().face(), entry.getValue());
            emitted++;
        }
        for (SourceOverlay overlay : renderCall.sourceOverlays()) {
            emitSourceOverlay(emitter, neighbourCache, selectedOverlays, state, pos, overlay);
            emitted++;
        }

        if (metricsEnabled) {
            SynapheiaMetrics.event("chunk_rebuild_phase", SynapheiaMode.SYNAPHEIA,
                    snapshot.generation(), fields(
                            "phase", "synapheia_ctm", "state", "sample",
                            "duration_ns", System.nanoTime() - startedNanos,
                            "input_surface_count", renderCall.inputSurfaceCount(),
                            "emitted_surface_count", emitted,
                            "overlay_surface_count", renderCall.overlayCount(),
                            "model_identifier", modelId.toString(), "block_id", blockId.toString()
                    ));
        }
    }

    static RepeatDisposition repeatDisposition(SynapheiaManifest.Rule repeatRule,
                                                SynapheiaCellGeometry.Cell cell) {
        if (repeatRule == null) {
            return RepeatDisposition.STREAM_UNCHANGED;
        }
        return cell == null
                ? RepeatDisposition.CAPTURE_CROSS_CELL
                : RepeatDisposition.STREAM_SINGLE_CELL;
    }

    static boolean usesProjectedRepeatGeometry(Identifier blockId) {
        if (blockId == null || !Erydon.MOD_ID.equals(blockId.getNamespace())) {
            return false;
        }
        String path = blockId.getPath();
        return path.endsWith("_stairs_spiral_large")
                || path.endsWith("_stairs_spiral_large_aged");
    }

    static Identifier resolveOverlaySourceSprite(Identifier blockId,
                                                 Identifier emittedSprite,
                                                 Identifier authoredParticleSprite) {
        return ErydonSlopeModelClassifier.isHandledSlopeId(blockId)
                && authoredParticleSprite != null
                ? authoredParticleSprite
                : emittedSprite;
    }

    static void clearCaches() {
        spriteFinder = null;
        for (int index = 0; index < OVERLAY_MATERIALS.length(); index++) {
            OVERLAY_MATERIALS.set(index, null);
        }
        WARNED_CROSS_CELL_SOURCE_OVERLAYS.clear();
    }

    private void observeOverlays(RenderCallState renderCall,
                                 MutableQuadView quad,
                                 List<SynapheiaManifest.Rule> matchingRules) {
        if (matchingRules.isEmpty()) {
            return;
        }
        boolean unitSquareChecked = false;
        boolean unitSquare = false;
        boolean cellChecked = false;
        SynapheiaCellGeometry.Cell cell = null;
        CapturedQuad source = null;
        for (SynapheiaManifest.Rule rule : matchingRules) {
            if (rule.overlayShape() == SynapheiaManifest.OverlayShape.UNIT_FACE) {
                if (!unitSquareChecked) {
                    unitSquare = isUnitSquare(quad);
                    unitSquareChecked = true;
                }
                if (unitSquare) {
                    renderCall.observeUnitOverlay(quad.lightFace(), rule);
                }
                continue;
            }

            if (!cellChecked) {
                cell = SynapheiaCellGeometry.singleCell(quad.lightFace(), quad);
                cellChecked = true;
            }
            if (cell == null) {
                warnCrossCellSourceOverlay(rule, quad.lightFace());
                continue;
            }
            if (source == null) {
                source = capture(quad, null);
            }
            renderCall.captureSourceOverlay(new SourceOverlay(source, cell, rule));
        }
    }

    private void warnCrossCellSourceOverlay(SynapheiaManifest.Rule rule, Direction face) {
        String key = modelId + "|" + rule.id() + "|" + face;
        if (WARNED_CROSS_CELL_SOURCE_OVERLAYS.add(key)) {
            Erydon.LOGGER.warn("[{}] Synapheia skipped source-shaped overlay {} on {} face {} "
                            + "because the source quad crosses world texture cells.",
                    Erydon.MOD_ID, rule.resourceId(), modelId, face);
        }
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

    private static CapturedQuad capture(MutableQuadView quad,
                                        SynapheiaManifest.Rule repeatRule) {
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
                quad.material(), quad.colorIndex(), quad.tag(), List.copyOf(vertices), repeatRule);
    }

    private void applySingleCellRepeat(MutableQuadView quad,
                                       SynapheiaManifest.Rule rule,
                                       BlockState state,
                                       BlockPos pos,
                                       SynapheiaCellGeometry.Cell cell) {
        List<Sprite> sprites = SynapheiaService.sprites(snapshot, rule);
        if (sprites == null || sprites.size() != 36) {
            throw new IllegalStateException("Synapheia repeat sprites are unavailable for "
                    + rule.resourceId() + ".");
        }
        Direction face = quad.lightFace();
        int offsetX = cell.offsetX(face);
        int offsetY = cell.offsetY(face);
        int offsetZ = cell.offsetZ(face);
        int tileIndex = ErydonCtmService.repeatTileIndex(
                pos.getX() + offsetX, pos.getY() + offsetY, pos.getZ() + offsetZ, face);
        for (int vertex = 0; vertex < 4; vertex++) {
            quad.uv(vertex, SynapheiaCellGeometry.u(face, quad, vertex, cell),
                    SynapheiaCellGeometry.v(face, quad, vertex, cell));
        }
        quad.cullFace(cullFaceForOffset(quad.cullFace(), offsetX, offsetY, offsetZ));
        quad.spriteBake(sprites.get(tileIndex), MutableQuadView.BAKE_NORMALIZED);

        if (SynapheiaMetrics.enabled()) {
            SynapheiaMetrics.event("stage_invoked", SynapheiaMode.SYNAPHEIA,
                    snapshot.generation(), fields(
                            "stage", "repeat", "model_identifier", modelId.toString(),
                            "block_id", blockId.toString(), "rule_id", rule.id(),
                            "block_pos", List.of(pos.getX(), pos.getY(), pos.getZ()),
                            "emitted_surface_count", 1, "blockstate", state.toString()
                    ));
        }
    }

    private int emitRepeat(QuadEmitter emitter,
                           CapturedQuad quad,
                           SynapheiaManifest.Rule rule,
                           BlockState state,
                           BlockPos pos,
                           boolean geometryPomFallback) {
        List<SpiralStairCtmGeometry.Fragment> fragments =
                SpiralStairCtmGeometry.split(quad.lightFace(), quad.vertices());
        if (fragments.isEmpty()) {
            if (!SpiralStairCtmGeometry.hasProjectedArea(quad.lightFace(), quad.vertices())) {
                return 0;
            }
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

            for (List<SpiralStairCtmGeometry.CellVertex> primitive
                    : repeatPrimitives(vertices, geometryPomFallback)) {
                List<SpiralStairCtmGeometry.CellVertex> emittedVertices = primitive.size() == 3
                        ? (geometryPomFallback
                        ? padPomSafeTriangle(primitive.get(0), primitive.get(1), primitive.get(2))
                        : padTriangle(primitive.get(0), primitive.get(1), primitive.get(2)))
                        : primitive;
                emitRepeatPrimitive(emitter, quad, cullFace, emittedVertices, sprites.get(tileIndex));
                emitted++;
            }
        }
        if (SynapheiaMetrics.enabled()) {
            SynapheiaMetrics.event("stage_invoked", SynapheiaMode.SYNAPHEIA, snapshot.generation(), fields(
                    "stage", "repeat", "model_identifier", modelId.toString(),
                    "block_id", blockId.toString(), "rule_id", rule.id(),
                    "block_pos", List.of(pos.getX(), pos.getY(), pos.getZ()),
                    "emitted_surface_count", emitted, "blockstate", state.toString()
            ));
        }
        return emitted;
    }

    static List<List<SpiralStairCtmGeometry.CellVertex>> repeatPrimitives(
            List<SpiralStairCtmGeometry.CellVertex> vertices,
            boolean geometryPomFallback) {
        if (geometryPomFallback) {
            // Unknown POM pipelines retain the proven geometry-only correction.
            // The supported CU path resolves exact sprite bounds in its vertex
            // shader and therefore does not need this per-surface expansion.
            return ArchRepeatCtmRenderer.pomSafePrimitives(vertices);
        }
        // The authored main spiral is 118 bounded surfaces versus 284 with the
        // geometry-only POM decomposition. Keep the normal path to one quad for
        // triangles/quads and an n-2 fan for larger clipped polygons.
        if (vertices.size() <= 4) {
            return List.of(List.copyOf(vertices));
        }
        List<List<SpiralStairCtmGeometry.CellVertex>> result = new ArrayList<>();
        SpiralStairCtmGeometry.CellVertex first = vertices.get(0);
        for (int index = 1; index < vertices.size() - 1; index++) {
            result.add(List.of(first, vertices.get(index), vertices.get(index + 1)));
        }
        return List.copyOf(result);
    }

    static <T> List<T> padTriangle(T first, T second, T third) {
        // Indium may rotate a quad to use the opposite AO diagonal. Repeating the first
        // vertex keeps the first three vertices non-degenerate in either orientation,
        // which Iris requires when deriving the PBR tangent.
        return List.of(first, second, third, first);
    }

    static List<SpiralStairCtmGeometry.CellVertex> padPomSafeTriangle(
            SpiralStairCtmGeometry.CellVertex first,
            SpiralStairCtmGeometry.CellVertex second,
            SpiralStairCtmGeometry.CellVertex third) {
        return List.of(first, second, third,
                ArchRepeatCtmRenderer.pomSafeTriangleGhost(first, second, third));
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

    private void emitOverlay(QuadEmitter emitter,
                             SynapheiaNeighbourCache neighbourCache,
                             Map<OverlayKey, OverlayTile> selectedOverlays,
                             BlockState state,
                             BlockPos pos,
                             Direction face,
                             SynapheiaManifest.Rule rule) {
        OverlayTile overlay = selectOverlay(neighbourCache, selectedOverlays, state, face, rule);
        emitter.square(face, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F);
        emitter.color(-1, -1, -1, -1);
        emitter.colorIndex(-1);
        assignOverlayUvs(emitter, overlay.sprite());
        emitter.material(overlay.material());
        emitter.emit();
        recordOverlaySelection(pos, face, rule, overlay);
    }

    private void emitSourceOverlay(QuadEmitter emitter,
                                   SynapheiaNeighbourCache neighbourCache,
                                   Map<OverlayKey, OverlayTile> selectedOverlays,
                                   BlockState state,
                                   BlockPos pos,
                                   SourceOverlay sourceOverlay) {
        CapturedQuad source = sourceOverlay.source();
        Direction face = source.lightFace();
        OverlayTile overlay = selectOverlay(
                neighbourCache, selectedOverlays, state, face, sourceOverlay.rule());
        emitter.material(overlay.material());
        emitter.colorIndex(-1);
        emitter.tag(source.tag());
        emitter.cullFace(source.cullFace());
        emitter.nominalFace(source.nominalFace());
        for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++) {
            SpiralStairCtmGeometry.Vertex vertex = source.vertices().get(vertexIndex);
            emitter.pos(vertexIndex, vertex.x(), vertex.y(), vertex.z());
            emitter.uv(vertexIndex,
                    SynapheiaCellGeometry.u(face, vertex, sourceOverlay.cell()),
                    SynapheiaCellGeometry.v(face, vertex, sourceOverlay.cell()));
            emitter.color(vertexIndex, -1);
            emitter.lightmap(vertexIndex, vertex.lightmap());
            if (vertex.hasNormal()) {
                emitter.normal(vertexIndex, vertex.normalX(), vertex.normalY(), vertex.normalZ());
            }
        }
        emitter.spriteBake(overlay.sprite(), MutableQuadView.BAKE_NORMALIZED);
        emitter.emit();
        recordOverlaySelection(pos, face, sourceOverlay.rule(), overlay);
    }

    private OverlayTile selectOverlay(SynapheiaNeighbourCache neighbourCache,
                                      Map<OverlayKey, OverlayTile> selectedOverlays,
                                      BlockState state,
                                      Direction face,
                                      SynapheiaManifest.Rule rule) {
        OverlayKey key = new OverlayKey(rule.id(), face);
        OverlayTile selected = selectedOverlays.get(key);
        if (selected != null) {
            return selected;
        }
        List<Sprite> sprites = SynapheiaService.sprites(snapshot, rule);
        if (sprites == null || sprites.size() != 47) {
            throw new IllegalStateException("Synapheia overlay sprites are unavailable for " + rule.resourceId() + ".");
        }
        if (neighbourCache == null) {
            throw new IllegalStateException("Synapheia overlay neighbour cache is unavailable.");
        }
        int mask = connectionMask(neighbourCache, state, face, rule);
        int tileIndex = connectedTileIndex(mask);
        RenderMaterial material = overlayMaterial(
                state.getLuminance() == 0, rule.overlayLayer());
        selected = new OverlayTile(sprites.get(tileIndex), material, tileIndex, mask);
        selectedOverlays.put(key, selected);
        return selected;
    }

    private void recordOverlaySelection(BlockPos pos,
                                        Direction face,
                                        SynapheiaManifest.Rule rule,
                                        OverlayTile overlay) {
        if (SynapheiaMetrics.enabled()) {
            SynapheiaMetrics.event("tile_selected", SynapheiaMode.SYNAPHEIA, snapshot.generation(), fields(
                    "stage", "overlay_ctm", "rule_id", rule.id(), "tile_index", overlay.tileIndex(),
                    "connection_mask", overlay.connectionMask(), "face_direction", face.getName(),
                    "block_pos", List.of(pos.getX(), pos.getY(), pos.getZ())
            ));
        }
    }

    static int connectionMask(SynapheiaNeighbourCache neighbourCache,
                              BlockState state,
                              Direction face,
                              SynapheiaManifest.Rule rule) {
        Offset[] directions = FACE_TANGENT_OFFSETS[face.ordinal()];
        Identifier sourceBlockId = Registries.BLOCK.getId(state.getBlock());
        int mask = 0;
        for (int index = 0; index < 4; index++) {
            Offset direction = directions[index];
            if (connects(neighbourCache, sourceBlockId,
                    direction.x(), direction.y(), direction.z(), face, rule)) {
                mask |= 1 << (index * 2);
            }
        }
        for (int index = 0; index < 4; index++) {
            int next = (index + 1) & 3;
            int firstBit = 1 << (index * 2);
            int nextBit = 1 << (next * 2);
            Offset first = directions[index];
            Offset second = directions[next];
            if ((mask & firstBit) != 0 && (mask & nextBit) != 0 && connects(
                    neighbourCache, sourceBlockId,
                    first.x() + second.x(), first.y() + second.y(), first.z() + second.z(),
                    face, rule)) {
                mask |= 1 << (index * 2 + 1);
            }
        }
        return mask;
    }

    private static boolean connects(SynapheiaNeighbourCache neighbourCache,
                                    Identifier sourceBlockId,
                                    int dx,
                                    int dy,
                                    int dz,
                                    Direction face,
                                    SynapheiaManifest.Rule rule) {
        Identifier neighbourBlockId = Registries.BLOCK.getId(
                neighbourCache.get(dx, dy, dz).getBlock());
        if (!overlayBlocksConnect(rule, sourceBlockId, neighbourBlockId)) {
            return false;
        }
        if (!rule.innerSeams()) {
            return true;
        }
        Identifier seamBlockId = Registries.BLOCK.getId(neighbourCache.get(
                dx + face.getOffsetX(), dy + face.getOffsetY(), dz + face.getOffsetZ()
        ).getBlock());
        return !overlayBlocksConnect(rule, sourceBlockId, seamBlockId);
    }

    static boolean overlayBlocksConnect(SynapheiaManifest.Rule rule,
                                        Identifier sourceBlockId,
                                        Identifier neighbourBlockId) {
        if (sourceBlockId == null || neighbourBlockId == null) {
            return false;
        }
        return rule.overlayConnection() == SynapheiaManifest.OverlayConnection.RULE
                ? rule.blocks().contains(neighbourBlockId)
                : sourceBlockId.equals(neighbourBlockId);
    }

    static SynapheiaNeighbourCache createNeighbourCache(boolean overlayCapable,
                                                        BlockRenderView view,
                                                        BlockPos pos) {
        return overlayCapable ? new SynapheiaNeighbourCache(view, pos) : null;
    }

    static Offset tangentOffset(Direction face, int index) {
        return FACE_TANGENT_OFFSETS[face.ordinal()][index];
    }

    private static Offset[][] createFaceTangentOffsets() {
        Direction[] faces = Direction.values();
        Offset[][] offsets = new Offset[faces.length][4];
        for (Direction face : faces) {
            Direction vertical = face == Direction.UP ? Direction.NORTH
                    : face == Direction.DOWN ? Direction.SOUTH : Direction.UP;
            Direction horizontal = face.getDirection() == Direction.AxisDirection.NEGATIVE
                    ? vertical.rotateClockwise(face.getAxis())
                    : vertical.rotateCounterclockwise(face.getAxis());
            offsets[face.ordinal()] = new Offset[]{
                    Offset.from(horizontal), Offset.from(vertical.getOpposite()),
                    Offset.from(horizontal.getOpposite()), Offset.from(vertical)
            };
        }
        return offsets;
    }

    private static boolean isUnitSquare(QuadView quad) {
        Direction face = quad.lightFace();
        if (face == null) {
            return false;
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            float first;
            float second;
            switch (face.getAxis()) {
                case X -> { first = quad.y(vertex); second = quad.z(vertex); }
                case Y -> { first = quad.x(vertex); second = quad.z(vertex); }
                case Z -> { first = quad.y(vertex); second = quad.x(vertex); }
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

    private static RenderMaterial overlayMaterial(boolean ambientOcclusion,
                                                  SynapheiaManifest.OverlayLayer layer) {
        int materialIndex = layer.ordinal() * 2 + (ambientOcclusion ? 1 : 0);
        RenderMaterial current = OVERLAY_MATERIALS.get(materialIndex);
        if (current != null) {
            return current;
        }
        Renderer renderer = RendererAccess.INSTANCE.getRenderer();
        if (renderer == null) {
            throw new IllegalStateException("Synapheia requires the Fabric renderer API.");
        }
        synchronized (MATERIAL_LOCK) {
            current = OVERLAY_MATERIALS.get(materialIndex);
            if (current == null) {
                current = renderer.materialFinder().clear()
                        .blendMode(overlayBlendMode(layer))
                        .ambientOcclusion(ambientOcclusion ? TriState.TRUE : TriState.FALSE)
                        .find();
                OVERLAY_MATERIALS.set(materialIndex, current);
            }
        }
        return current;
    }

    private static void assignOverlayUvs(MutableQuadView quad, Sprite sprite) {
        float delta = sprite.getAnimationFrameDelta();
        float centerU = (sprite.getMinU() + sprite.getMaxU()) * 0.5F;
        float centerV = (sprite.getMinV() + sprite.getMaxV()) * 0.5F;
        float minU = net.minecraft.util.math.MathHelper.lerp(delta, sprite.getMinU(), centerU);
        float maxU = net.minecraft.util.math.MathHelper.lerp(delta, sprite.getMaxU(), centerU);
        float minV = net.minecraft.util.math.MathHelper.lerp(delta, sprite.getMinV(), centerV);
        float maxV = net.minecraft.util.math.MathHelper.lerp(delta, sprite.getMaxV(), centerV);
        quad.uv(0, minU, minV);
        quad.uv(1, minU, maxV);
        quad.uv(2, maxU, maxV);
        quad.uv(3, maxU, minV);
    }

    static BlendMode overlayBlendMode(SynapheiaManifest.OverlayLayer layer) {
        return switch (layer) {
            case CUTOUT_MIPPED -> BlendMode.CUTOUT_MIPPED;
            case CUTOUT -> BlendMode.CUTOUT;
        };
    }

    private static Map<String, Object> fields(Object... entries) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            fields.put((String) entries[index], entries[index + 1]);
        }
        return fields;
    }

    enum RepeatDisposition {
        STREAM_UNCHANGED(true),
        STREAM_SINGLE_CELL(true),
        CAPTURE_CROSS_CELL(false);

        private final boolean streamsOriginal;

        RepeatDisposition(boolean streamsOriginal) {
            this.streamsOriginal = streamsOriginal;
        }

        boolean streamsOriginal() {
            return streamsOriginal;
        }
    }

    static final class RenderCallState {
        private final boolean overlayCapable;
        private final boolean metricsEnabled;
        private List<CapturedQuad> crossCellQuads;
        private Map<OverlayKey, SynapheiaManifest.Rule> overlays;
        private List<SourceOverlay> sourceOverlays;
        private int inputSurfaceCount;
        private int streamedSurfaceCount;

        RenderCallState(boolean overlayCapable, boolean metricsEnabled) {
            this.overlayCapable = overlayCapable;
            this.metricsEnabled = metricsEnabled;
        }

        boolean tracksOverlays() {
            return overlayCapable;
        }

        void observeUnitOverlay(Direction face, SynapheiaManifest.Rule rule) {
            if (!overlayCapable
                    || rule.overlayShape() != SynapheiaManifest.OverlayShape.UNIT_FACE) {
                return;
            }
            if (overlays == null) {
                overlays = new LinkedHashMap<>();
            }
            overlays.putIfAbsent(new OverlayKey(rule.id(), face), rule);
        }

        void observeUnitOverlays(Direction face, List<SynapheiaManifest.Rule> matchingRules) {
            for (SynapheiaManifest.Rule rule : matchingRules) {
                observeUnitOverlay(face, rule);
            }
        }

        void captureSourceOverlay(SourceOverlay overlay) {
            if (!overlayCapable) {
                return;
            }
            if (sourceOverlays == null) {
                sourceOverlays = new ArrayList<>(1);
            }
            sourceOverlays.add(overlay);
        }

        boolean hasOverlayBookkeeping() {
            return overlays != null || sourceOverlays != null;
        }

        int overlayCount() {
            return (overlays == null ? 0 : overlays.size())
                    + (sourceOverlays == null ? 0 : sourceOverlays.size());
        }

        private Iterable<Map.Entry<OverlayKey, SynapheiaManifest.Rule>> overlayEntries() {
            return overlays == null ? List.of() : overlays.entrySet();
        }

        private Iterable<SourceOverlay> sourceOverlays() {
            return sourceOverlays == null ? List.of() : sourceOverlays;
        }

        private void captureCrossCell(CapturedQuad quad) {
            if (crossCellQuads == null) {
                crossCellQuads = new ArrayList<>(1);
            }
            crossCellQuads.add(quad);
        }

        private Iterable<CapturedQuad> crossCellQuads() {
            return crossCellQuads == null ? List.of() : crossCellQuads;
        }

        private void recordInputSurface() {
            if (metricsEnabled) {
                inputSurfaceCount++;
            }
        }

        private void recordStreamedSurface() {
            if (metricsEnabled) {
                streamedSurfaceCount++;
            }
        }

        private int inputSurfaceCount() {
            return inputSurfaceCount;
        }

        private int streamedSurfaceCount() {
            return streamedSurfaceCount;
        }
    }

    private record CapturedQuad(Direction lightFace,
                                Direction nominalFace,
                                Direction cullFace,
                                RenderMaterial material,
                                int colorIndex,
                                int tag,
                                List<SpiralStairCtmGeometry.Vertex> vertices,
                                SynapheiaManifest.Rule repeatRule) {
    }

    private record OverlayKey(String ruleId, Direction face) {
    }

    private record SourceOverlay(CapturedQuad source,
                                 SynapheiaCellGeometry.Cell cell,
                                 SynapheiaManifest.Rule rule) {
    }

    private record OverlayTile(Sprite sprite,
                               RenderMaterial material,
                               int tileIndex,
                               int connectionMask) {
    }

    record Offset(int x, int y, int z) {
        private static Offset from(Direction direction) {
            return new Offset(direction.getOffsetX(), direction.getOffsetY(), direction.getOffsetZ());
        }
    }
}
