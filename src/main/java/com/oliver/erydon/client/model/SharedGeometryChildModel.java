package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.render.model.BakedModel;

/** Marker for child models that must emit directly through an already-wrapped family context. */
interface SharedGeometryChildModel extends FabricBakedModel {
    void emitSharedGeometry(RenderContext context);

    static void emit(RenderContext context, BakedModel model) {
        if (model instanceof SharedGeometryChildModel shared) {
            shared.emitSharedGeometry(context);
        } else {
            context.fallbackConsumer().accept(model);
        }
    }
}
