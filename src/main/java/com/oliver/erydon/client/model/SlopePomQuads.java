package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.minecraft.client.texture.Sprite;

import java.util.ArrayList;
import java.util.List;

/** Applies the existing rectangular POM bounds correction after slope UV projection. */
final class SlopePomQuads {
    private SlopePomQuads() {
    }

    static void emit(QuadEmitter emitter, Sprite sprite) {
        // Ordinary rectangular faces and already-corrected slope tops allocate nothing.
        if (hasStableBounds(emitter)) {
            emitter.spriteBake(sprite, MutableQuadView.BAKE_ROTATE_NONE);
            emitter.emit();
            return;
        }

        var material = emitter.material();
        var nominalFace = emitter.nominalFace();
        var cullFace = emitter.cullFace();
        int colorIndex = emitter.colorIndex();
        int tag = emitter.tag();
        List<SpiralStairCtmGeometry.CellVertex> source = new ArrayList<>(4);
        for (int index = 0; index < 4; index++) {
            var vertex = new SpiralStairCtmGeometry.Vertex(
                    emitter.x(index), emitter.y(index), emitter.z(index),
                    emitter.color(index), emitter.lightmap(index), emitter.hasNormal(index),
                    emitter.hasNormal(index) ? emitter.normalX(index) : 0,
                    emitter.hasNormal(index) ? emitter.normalY(index) : 0,
                    emitter.hasNormal(index) ? emitter.normalZ(index) : 0);
            // Remove a triangle's duplicate position before decomposing its visible polygon.
            if (source.stream().noneMatch(v -> v.vertex().x() == vertex.x()
                    && v.vertex().y() == vertex.y() && v.vertex().z() == vertex.z())) {
                source.add(new SpiralStairCtmGeometry.CellVertex(
                        vertex, emitter.u(index) / 16.0F, emitter.v(index) / 16.0F));
            }
        }
        for (var primitive : ArchRepeatCtmRenderer.pomSafePrimitives(source)) {
            emitter.material(material);
            emitter.nominalFace(nominalFace);
            emitter.cullFace(cullFace);
            emitter.colorIndex(colorIndex);
            emitter.tag(tag);
            for (int index = 0; index < 4; index++) {
                var cell = index < primitive.size() ? primitive.get(index)
                        : ArchRepeatCtmRenderer.pomSafeTriangleGhost(
                                primitive.get(0), primitive.get(1), primitive.get(2));
                var vertex = cell.vertex();
                emitter.pos(index, vertex.x(), vertex.y(), vertex.z());
                emitter.uv(index, cell.localS() * 16.0F, cell.localT() * 16.0F);
                emitter.color(index, vertex.color());
                emitter.lightmap(index, vertex.lightmap());
                if (vertex.hasNormal()) {
                    emitter.normal(index, vertex.normalX(), vertex.normalY(), vertex.normalZ());
                }
            }
            emitter.spriteBake(sprite, MutableQuadView.BAKE_ROTATE_NONE);
            emitter.emit();
        }
    }

    private static boolean hasStableBounds(QuadView quad) {
        float midU = (quad.u(0) + quad.u(1) + quad.u(2) + quad.u(3)) * 0.25F;
        float midV = (quad.v(0) + quad.v(1) + quad.v(2) + quad.v(3)) * 0.25F;
        float radiusU = Math.abs(quad.u(0) - midU);
        float radiusV = Math.abs(quad.v(0) - midV);
        for (int index = 1; index < 4; index++) {
            if (Math.abs(Math.abs(quad.u(index) - midU) - radiusU) > 0.00001F
                    || Math.abs(Math.abs(quad.v(index) - midV) - radiusV) > 0.00001F) {
                return false;
            }
        }
        return true;
    }
}
