package com.oliver.erydon.client.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oliver.erydon.Erydon;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.Baker;
import net.minecraft.client.render.model.json.JsonUnbakedModel;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Function;

/** One prepared geometry/material cache per model reload, shared by every large-column state. */
public final class DoubleColumnMeshBaker {
    static final List<String> SUFFIXES=List.of("pillar","base","base_narrow","capital","capital_narrow","capital_guilloche");
    private final Map<String,DoubleColumnMeshGeometry.Template> templates;
    private final Map<String,MaterialMeshes> materials=new LinkedHashMap<>();

    private DoubleColumnMeshBaker(Map<String,DoubleColumnMeshGeometry.Template> templates) {
        this.templates=Map.copyOf(templates);
    }

    public static CompletableFuture<DoubleColumnMeshBaker> load(ResourceManager resources, Executor executor) {
        return CompletableFuture.supplyAsync(()-> {
            Map<String,DoubleColumnMeshGeometry.Template> templates=new LinkedHashMap<>();
            for(String suffix:SUFFIXES) {
                Identifier meshId=new Identifier(Erydon.MOD_ID,"meshes/large_columns/"+suffix+".json");
                Identifier sourceId=new Identifier(Erydon.MOD_ID,"models/block/column/circular/column_circular_"+suffix+".json");
                try {
                    JsonObject data;
                    try(var reader=resources.getResource(meshId).orElseThrow(()->new IOException("Missing "+meshId)).getReader()) {
                        data=JsonParser.parseReader(reader).getAsJsonObject();
                    }
                    byte[] master;
                    try(var input=resources.getResource(sourceId).orElseThrow(()->new IOException("Missing "+sourceId)).getInputStream()) {
                        master=input.readAllBytes();
                    }
                    // Git's platform EOL conversion must not disable the mesh
                    // on a clean Linux clone of these Windows-authored models.
                    byte[] normalized=new String(master,StandardCharsets.UTF_8).replace("\r\n","\n").replace('\r','\n').getBytes(StandardCharsets.UTF_8);
                    String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized));
                    if(!actual.equals(data.get("source_sha256").getAsString())) {
                        // Third-party packs can deliberately replace the old
                        // circular masters. Preserve that authored custom shape
                        // through the existing path instead of mixing old/new.
                        Erydon.LOGGER.warn("Large column mesh {} does not match overridden master {}; retaining authored model rendering",meshId,sourceId);
                        continue;
                    }
                    templates.put(suffix,DoubleColumnMeshGeometry.parse(data));
                } catch(IOException|NoSuchAlgorithmException|RuntimeException failure) {
                    throw new IllegalStateException("Cannot prepare large circular column "+meshId,failure);
                }
            }
            return new DoubleColumnMeshBaker(templates);
        },executor);
    }

    public BakedModel wrap(BakedModel wrapped,String blockPath,Baker modelBaker,
                           Function<SpriteIdentifier,Sprite> textureGetter) {
        Renderer renderer=RendererAccess.INSTANCE.getRenderer();
        if(renderer==null || templates.size()!=SUFFIXES.size()) return new DoubleCircularColumnBakedModel(wrapped);
        MaterialMeshes material;
        synchronized(materials) {
            material=materials.get(blockPath);
            if(material==null) {
                String sourcePath=blockPath.substring(0,blockPath.length()-"_double".length());
                Map<String,Style> styles=new LinkedHashMap<>();
                for(String suffix:SUFFIXES) {
                    Identifier id=ColumnBakedModel.modelId(sourcePath,suffix);
                    if(!(modelBaker.getOrLoadModel(id) instanceof JsonUnbakedModel source)) {
                        Erydon.LOGGER.warn("Large column material model {} uses a custom loader; retaining authored model rendering",id);
                        return new DoubleCircularColumnBakedModel(wrapped);
                    }
                    Identifier masterId=new Identifier(Erydon.MOD_ID,"block/column/circular/column_circular_"+suffix);
                    if(!(modelBaker.getOrLoadModel(masterId) instanceof JsonUnbakedModel master)
                            || source.getElements()!=master.getElements()) {
                        Erydon.LOGGER.warn("Large column material model {} changes its inherited geometry; retaining authored model rendering",id);
                        return new DoubleCircularColumnBakedModel(wrapped);
                    }
                    Sprite sprite=textureGetter.apply(source.resolveSprite("#stone"));
                    // Finish all geometry work while loading models, never on
                    // a chunk rebuild or an Axiom preview emission.
                    templates.get(suffix).cell(renderer,false,0,0,0);
                    if("pillar".equals(suffix)) templates.get(suffix).cell(renderer,true,0,0,0);
                    styles.put(suffix,new Style(templates.get(suffix),sprite,renderer));
                }
                material=new MaterialMeshes(Map.copyOf(styles));
                materials.put(blockPath,material);
            }
        }
        return new DoubleCircularColumnBakedModel(wrapped,material);
    }

    static final class MaterialMeshes {
        private final Map<String,Style> styles;
        MaterialMeshes(Map<String,Style> styles) { this.styles=styles; }
        void emit(String suffix,boolean shaft,int layer,int x,int z,RenderContext context) {
            Style style=styles.get(suffix);
            context.pushTransform(style.transform);
            try { style.cell(shaft,layer,x,z).mesh().outputTo(context.getEmitter()); }
            finally { context.popTransform(); }
        }
        List<BakedQuad> baked(String suffix,boolean shaft,int layer,int x,int z) {
            return styles.get(suffix).baked(shaft,layer,x,z);
        }
    }
    private static final class Style {
        final DoubleColumnMeshGeometry.Template geometry;
        final Sprite sprite;
        final Renderer renderer;
        final RenderContext.QuadTransform transform;
        private final AtomicReferenceArray<List<BakedQuad>> baked;
        Style(DoubleColumnMeshGeometry.Template geometry,Sprite sprite,Renderer renderer) {
            this.geometry=geometry;this.sprite=sprite;this.renderer=renderer;
            this.transform=quad->{quad.spriteBake(sprite,MutableQuadView.BAKE_NORMALIZED);return true;};
            this.baked=new AtomicReferenceArray<>(12);
        }
        DoubleColumnMeshGeometry.Cell cell(boolean shaft,int layer,int x,int z) {
            return geometry.cell(renderer,shaft,layer,x,z);
        }
        List<BakedQuad> baked(boolean shaft,int layer,int x,int z) {
            int key=(shaft?8:layer*4)+x*2+z;
            List<BakedQuad> result=baked.get(key);
            if(result==null) synchronized(baked) {
                result=baked.get(key);
                if(result==null) {result=cell(shaft,layer,x,z).baked(sprite);baked.set(key,result);}
            }
            return result;
        }
    }
}
