package com.oliver.erydon.client.model;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.EmptyBlockView;

import java.lang.reflect.Proxy;
import java.util.List;

/** Compare the rendered surface with both interaction shapes under real Fabric bootstrap. */
public final class SlopeShapeLaunchChecks {
    public static int run() {
        int configurations=0;
        try(SpriteContents contents=new SpriteContents(new Identifier("erydon","shape_probe"),
                new SpriteDimensions(16,16),new NativeImage(16,16,false),AnimationResourceMetadata.EMPTY)) {
            Sprite sprite=new TestSprite(contents);
            BakedModel wrapped=(BakedModel)Proxy.newProxyInstance(BakedModel.class.getClassLoader(),new Class<?>[]{BakedModel.class},
                    (proxy,method,args) -> switch(method.getName()) {
                        case "getParticleSprite" -> sprite;
                        case "getTransformation" -> ModelTransformation.NONE;
                        case "getOverrides" -> ModelOverrideList.EMPTY;
                        case "getQuads" -> List.of();
                        default -> method.getReturnType()==boolean.class ? false : null;
                    });
            for(String form:new String[]{"slope","slope_shallow_lower","slope_shallow_upper","slope_steep_lower","slope_steep_upper"}) {
                Identifier id=new Identifier("erydon","glacium_"+form);
                Block block=Registries.BLOCK.get(id);
                var family=ErydonSlopeModelClassifier.familyForId(id);
                BakedModel model=form.equals("slope") ? new SlopeBakedModel(wrapped,family)
                        : form.startsWith("slope_shallow") ? new ShallowSlopeBakedModel(wrapped,family)
                        : new SlopeSteepBakedModel(wrapped,family);
                for(BlockState state:block.getStateManager().getStates()) {
                    if (state.get(Properties.WATERLOGGED)) continue;
                    List<BakedQuad> quads=model.getQuads(state,null,Random.create(0));
                    var outline=block.getOutlineShape(state,EmptyBlockView.INSTANCE,BlockPos.ORIGIN,ShapeContext.absent());
                    var collision=block.getCollisionShape(state,EmptyBlockView.INSTANCE,BlockPos.ORIGIN,ShapeContext.absent());
                    require(outline==collision,"Outline/collision differ: "+state);
                    List<Box> boxes=collision.getBoundingBoxes();
                    for(int ix=0;ix<32;ix++) for(int iz=0;iz<32;iz++) {
                        double x=(ix+.5)/32,z=(iz+.5)/32;
                        double[] rendered={Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY};
                        for(BakedQuad quad:quads) {
                            intersect(quad,0,1,2,x,z,rendered); intersect(quad,0,2,3,x,z,rendered);
                        }
                        if (!Double.isFinite(rendered[0])) continue;
                        boolean top=state.get(Properties.BLOCK_HALF)==BlockHalf.TOP;
                        double min=top ? 1 : 0,max=min;
                        boolean found=false;
                        for(Box box:boxes) if(x>box.minX && x<box.maxX && z>box.minZ && z<box.maxZ) {
                            if (!found) { min=box.minY; max=box.maxY; found=true; }
                            else { min=Math.min(min,box.minY); max=Math.max(max,box.maxY); }
                        }
                        // Existing interaction shapes approximate smooth planes with steps:
                        // shallow rises 1/64 per slice, standard/steep at most 1/16.
                        double tolerance=form.startsWith("slope_shallow") ? .017 : .064;
                        require(Math.abs(rendered[0]-min)<=tolerance && Math.abs(rendered[1]-max)<=tolerance,
                                "Shape/render mismatch: "+state+" at "+x+","+z+" render="+rendered[0]+".."+rendered[1]+" shape="+min+".."+max);
                    }
                    configurations++;
                }
            }
        }
        System.out.println("ERYDON_SLOPE_SHAPES_OK: "+configurations+" standard/shallow/steep states, 1024 surface samples per state");
        return configurations;
    }
    private static void intersect(BakedQuad quad,int a,int b,int c,double x,double z,double[] bounds) {
        int[] data=quad.getVertexData();
        double ax=value(data,a,0),az=value(data,a,2),bx=value(data,b,0),bz=value(data,b,2),cx=value(data,c,0),cz=value(data,c,2);
        double denominator=(bz-cz)*(ax-cx)+(cx-bx)*(az-cz);
        if(Math.abs(denominator)<1e-10) return;
        double u=((bz-cz)*(x-cx)+(cx-bx)*(z-cz))/denominator;
        double v=((cz-az)*(x-cx)+(ax-cx)*(z-cz))/denominator,w=1-u-v;
        if(u<-.00001 || v<-.00001 || w<-.00001) return;
        double y=u*value(data,a,1)+v*value(data,b,1)+w*value(data,c,1);
        bounds[0]=Math.min(bounds[0],y); bounds[1]=Math.max(bounds[1],y);
    }
    private static float value(int[] data,int vertex,int axis) { return Float.intBitsToFloat(data[vertex*8+axis]); }
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    private static final class TestSprite extends Sprite {
        TestSprite(SpriteContents contents) { super(new Identifier("erydon","probe_atlas"),contents,32,32,0,0); }
    }
}
