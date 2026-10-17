package com.oliver.erydon.client.pom;

import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.block.BlockState;

/** Transient state identity for the already-verified synchronous Indium/Iris terrain encoder. */
public final class CornicePartMaterialTransport {
    private static final ThreadLocal<BlockState> CURRENT = new ThreadLocal<>();
    private CornicePartMaterialTransport() { }

    public interface SourceContext { RenderContext originalContext(); }

    public static RenderContext unwrap(RenderContext context) {
        return context instanceof SourceContext part ? part.originalContext() : context;
    }

    public static boolean supportsContext(RenderContext context) {
        context = unwrap(context);
        return context != null && context.getClass().getName().equals("link.infra.indium.renderer.render.TerrainRenderContext")
                && InlaySubstrateTransport.rendererSupported();
    }

    public static BlockState push(BlockState part) {
        BlockState previous = CURRENT.get();
        CURRENT.set(part);
        return previous;
    }

    public static void restore(BlockState previous) {
        // Retain the thread-local slot, so sibling draws do not allocate a new
        // map entry. A null value holds no part or atlas data between draws.
        CURRENT.set(previous);
    }

    public static BlockState current() { return CURRENT.get(); }
}
