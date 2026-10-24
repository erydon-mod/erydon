package com.oliver.erydon.client.model;

import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BakedQuadFactory;
import net.minecraft.client.render.model.ModelRotation;
import net.minecraft.client.render.model.json.JsonUnbakedModel;
import net.minecraft.client.render.model.json.ModelElementFace;
import net.minecraft.client.render.model.json.ModelElementTexture;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Bake-only closed authored element volumes used exclusively to close a new cut end. */
final class CorniceProfileVolumes {
    private CorniceProfileVolumes() { }

    static List<BakedQuad> bake(JsonUnbakedModel source, Function<SpriteIdentifier, Sprite> textures, Identifier id) {
        BakedQuadFactory factory=new BakedQuadFactory();
        List<BakedQuad> result=new ArrayList<>();
        for(var element:source.getElements()) {
            if(element.faces.isEmpty())continue;
            ModelElementFace material=element.faces.values().iterator().next();
            for(Direction direction:Direction.values()) {
                ModelElementFace face=element.faces.get(direction);
                if(face==null)face=new ModelElementFace(null,material.tintIndex,material.textureId,
                        new ModelElementTexture(new float[]{0,0,16,16},0));
                result.add(factory.bake(element.from,element.to,face,textures.apply(source.resolveSprite(face.textureId)),
                        direction,ModelRotation.X0_Y0,element.rotation,element.shade,id));
            }
        }
        return List.copyOf(result);
    }
}
