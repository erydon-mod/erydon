package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Calls the public placed renderer, including POM splitting and real mesh encoding. */
final class SlopeRenderCapture implements AutoCloseable {
    private final Field clientField;
    private final Object previousClient;
    private final Map<String, Sprite[]> spriteCache;
    private final Sprite[] previousSprites;
    private final Sprite sprite;

    @SuppressWarnings("unchecked")
    SlopeRenderCapture(Sprite sprite) throws ReflectiveOperationException {
        this.sprite=sprite;
        clientField=MinecraftClient.class.getDeclaredField("instance");
        clientField.setAccessible(true);
        previousClient=clientField.get(null);
        if(previousClient==null) {
            Field field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
            clientField.set(null,((sun.misc.Unsafe)field.get(null)).allocateInstance(MinecraftClient.class));
        }
        // The test launch has no atlas. Seed only the fixture material; restore
        // the previous entry and client afterward without loading a game window.
        Field cache=ErydonCtmService.class.getDeclaredField("repeatSpritesBySet"); cache.setAccessible(true);
        spriteCache=(Map<String, Sprite[]>)cache.get(ErydonCtmService.get(null));
        Sprite[] tiles=new Sprite[36]; Arrays.fill(tiles,sprite);
        previousSprites=spriteCache.put("glacium",tiles);
    }

    @SuppressWarnings("removal")
    List<float[][]> emit(BakedModel model,BlockState state) {
        var builder=IndigoRenderer.INSTANCE.meshBuilder();
        var emitter=builder.getEmitter();
        RenderContext context=(RenderContext)Proxy.newProxyInstance(RenderContext.class.getClassLoader(),
                new Class<?>[]{RenderContext.class},(proxy,method,args) -> {
                    if(method.getName().equals("getEmitter")) return emitter;
                    throw new AssertionError("Slope bypassed its native emitter: "+method.getName());
                });
        ((FabricBakedModel)model).emitBlockQuads(null,state,BlockPos.ORIGIN,() -> Random.create(0),context);
        List<float[][]> quads=new ArrayList<>();
        builder.build().forEach(quad -> {
            float[][] points=new float[4][3];
            for(int v=0;v<4;v++) {
                points[v]=new float[]{quad.x(v),quad.y(v),quad.z(v)};
                for(float coordinate:points[v]) if(!Float.isFinite(coordinate) || coordinate<-.001F || coordinate>1.001F)
                    throw new AssertionError("Placed slope vertex escaped its cell: "+state);
                if(!Float.isFinite(quad.u(v)) || !Float.isFinite(quad.v(v))
                        || quad.u(v)<sprite.getMinU()-.00001 || quad.u(v)>sprite.getMaxU()+.00001
                        || quad.v(v)<sprite.getMinV()-.00001 || quad.v(v)>sprite.getMaxV()+.00001)
                    throw new AssertionError("Placed slope UV escaped its atlas sprite: "+state);
            }
            quads.add(points);
        });
        if(quads.isEmpty()) throw new AssertionError("Placed slope emitted no geometry: "+state);
        return quads;
    }

    @Override public void close() {
        if(previousSprites==null) spriteCache.remove("glacium");
        else spriteCache.put("glacium",previousSprites);
        try { clientField.set(null,previousClient); }
        catch(IllegalAccessException failure) { throw new AssertionError(failure); }
    }
}
