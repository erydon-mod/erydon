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
import net.minecraft.state.property.Property;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.EmptyBlockView;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Independent height-field proof of actual placed, preview and interaction geometry. */
public final class SlopeShapeLaunchChecks {
    private static final int GRID=32;
    // Native POM-safe tips and partial horizontal boundaries intentionally move by at most .001.
    private static final double MESH_TOLERANCE=.0016;
    private record Sampled(double[] low,double[] high) {}
    private record Geometry(Sampled placed,Sampled preview,Sampled physical) {}

    public static int run() {
        int configurations=0,transforms=0;
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
            try(SlopeRenderCapture capture=new SlopeRenderCapture(sprite)) {
                for(String form:new String[]{"slope","slope_shallow_lower","slope_shallow_upper","slope_steep_lower","slope_steep_upper"}) {
                    Identifier id=new Identifier("erydon","glacium_"+form);
                    Block block=Registries.BLOCK.get(id);
                    var family=ErydonSlopeModelClassifier.familyForId(id);
                    BakedModel model=form.equals("slope") ? new SlopeBakedModel(wrapped,family)
                            : form.startsWith("slope_shallow") ? new ShallowSlopeBakedModel(wrapped,family)
                            : new SlopeSteepBakedModel(wrapped,family);
                    Map<BlockState,Geometry> samples=new LinkedHashMap<>();
                    for(BlockState state:block.getStateManager().getStates()) {
                        boolean top=state.get(Properties.BLOCK_HALF)==BlockHalf.TOP;
                        List<float[][]> placed=capture.emit(model,state);
                        List<float[][]> preview=model.getQuads(state,null,Random.create(0)).stream()
                                .map(SlopeShapeLaunchChecks::points).toList();
                        require(!preview.isEmpty(),"Slope preview emitted no geometry: "+state);
                        var outline=block.getOutlineShape(state,EmptyBlockView.INSTANCE,BlockPos.ORIGIN,ShapeContext.absent());
                        var collision=block.getCollisionShape(state,EmptyBlockView.INSTANCE,BlockPos.ORIGIN,ShapeContext.absent());
                        require(outline==collision,"Outline/collision differ: "+state);
                        Geometry geometry=new Geometry(sample(placed,top),sample(preview,top),sampleBoxes(collision.getBoundingBoxes(),top));
                        for(int ix=0;ix<GRID;ix++) for(int iz=0;iz<GRID;iz++) {
                            int i=index(ix,iz);
                            double x=(ix+.5)/GRID,z=(iz+.5)/GRID,height=expectedHeight(form,state,x,z);
                            double low=top ? 1-height : 0,high=top ? 1 : height;
                            check(geometry.placed,i,low,high,MESH_TOLERANCE,"Placed renderer",state,x,z);
                            check(geometry.preview,i,low,high,MESH_TOLERANCE,"Axiom preview",state,x,z);
                            check(geometry.physical,i,low,high,shapeTolerance(form),"Outline/collision",state,x,z);
                            check(geometry.placed,i,geometry.preview.low[i],geometry.preview.high[i],MESH_TOLERANCE,
                                    "Placed/preview parity",state,x,z);
                        }
                        samples.put(state,geometry);
                        configurations++;
                    }
                    for(var entry:samples.entrySet()) {
                        BlockState state=entry.getKey(); Geometry geometry=entry.getValue();
                        Geometry dry=samples.get(state.with(Properties.WATERLOGGED,false));
                        compare(geometry,dry,0,BlockMirror.NONE,0,"Waterlogged geometry",state);
                        for(BlockRotation rotation:BlockRotation.values()) {
                            int turns=switch(rotation) { case NONE -> 0; case CLOCKWISE_90 -> 1; case CLOCKWISE_180 -> 2; case COUNTERCLOCKWISE_90 -> 3; };
                            compare(geometry,samples.get(state.rotate(rotation)),turns,BlockMirror.NONE,shapeTolerance(form),
                                    "Rotation "+rotation,state); transforms++;
                        }
                        for(BlockMirror mirror:BlockMirror.values()) {
                            compare(geometry,samples.get(state.mirror(mirror)),0,mirror,shapeTolerance(form),
                                    "Mirror "+mirror,state); transforms++;
                        }
                    }
                }
            } catch(ReflectiveOperationException failure) { throw new AssertionError("Unable to initialise placed-render fixture",failure); }
        }
        require(configurations==400,"Expected all 400 wet/dry pitched states, got "+configurations);
        System.out.println("ERYDON_SLOPE_SHAPES_OK: "+configurations+" wet/dry standard/shallow/steep states, 1024 independent height samples each; actual FRAPI/POM mesh, Axiom preview, outline/collision and "+transforms+" spatial transforms");
        return configurations;
    }

    /** Describes the requested corner directly; deliberately uses no shared orientation or mesh helper. */
    private static double expectedHeight(String form,BlockState state,double x,double z) {
        Direction facing=state.get(Properties.HORIZONTAL_FACING);
        String shape=shapeName(state);
        double own=straightHeight(form,progress(facing,x,z));
        if(shape.equals("straight")) return own;
        Direction other=shape.endsWith("left") ? facing.rotateYCounterclockwise() : facing.rotateYClockwise();
        double perpendicular=straightHeight(form,progress(other,x,z));
        return shape.startsWith("inner") ? Math.max(own,perpendicular) : Math.min(own,perpendicular);
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static String shapeName(BlockState state) {
        Property property=state.getBlock().getStateManager().getProperty("shape");
        return property.name(state.get(property));
    }
    private static double progress(Direction facing,double x,double z) {
        return switch(facing) { case EAST -> x; case SOUTH -> z; case WEST -> 1-x; case NORTH -> 1-z; default -> throw new AssertionError(facing); };
    }
    private static double straightHeight(String form,double run) {
        return switch(form) {
            case "slope" -> 1-run;
            case "slope_shallow_lower" -> .5*(1-run);
            case "slope_shallow_upper" -> .5+.5*(1-run);
            case "slope_steep_lower" -> Math.max(0,1-2*run);
            case "slope_steep_upper" -> Math.min(1,2-2*run);
            default -> throw new AssertionError(form);
        };
    }
    private static double shapeTolerance(String form) { return form.startsWith("slope_shallow") ? .017 : .064; }
    private static void compare(Geometry source,Geometry target,int turns,BlockMirror mirror,double physicalTolerance,String label,BlockState state) {
        require(target!=null,label+" returned an unregistered state: "+state);
        for(int ix=0;ix<GRID;ix++) for(int iz=0;iz<GRID;iz++) {
            int tx=ix,tz=iz;
            for(int t=0;t<turns;t++) { int oldX=tx; tx=GRID-1-tz; tz=oldX; }
            if(mirror==BlockMirror.FRONT_BACK) tx=GRID-1-tx;
            if(mirror==BlockMirror.LEFT_RIGHT) tz=GRID-1-tz;
            int i=index(ix,iz),j=index(tx,tz);
            double meshTolerance=physicalTolerance==0 ? 0 : MESH_TOLERANCE;
            check(target.placed,j,source.placed.low[i],source.placed.high[i],meshTolerance,label+" placed",state,tx,tz);
            check(target.preview,j,source.preview.low[i],source.preview.high[i],meshTolerance,label+" preview",state,tx,tz);
            check(target.physical,j,source.physical.low[i],source.physical.high[i],physicalTolerance,label+" physical",state,tx,tz);
        }
    }
    private static Sampled sample(List<float[][]> quads,boolean top) {
        double[] low=new double[GRID*GRID],high=new double[GRID*GRID];
        for(int ix=0;ix<GRID;ix++) for(int iz=0;iz<GRID;iz++) {
            double x=(ix+.5)/GRID,z=(iz+.5)/GRID;
            double[] bounds={Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY};
            for(float[][] quad:quads) { intersect(quad,0,1,2,x,z,bounds); intersect(quad,0,2,3,x,z,bounds); }
            int i=index(ix,iz);
            // Empty steep footprints are tested as zero volume rather than silently skipped.
            low[i]=Double.isFinite(bounds[0]) ? bounds[0] : top ? 1 : 0;
            high[i]=Double.isFinite(bounds[1]) ? bounds[1] : top ? 1 : 0;
        }
        return new Sampled(low,high);
    }
    private static Sampled sampleBoxes(List<Box> boxes,boolean top) {
        double[] low=new double[GRID*GRID],high=new double[GRID*GRID];
        for(int ix=0;ix<GRID;ix++) for(int iz=0;iz<GRID;iz++) {
            double x=(ix+.5)/GRID,z=(iz+.5)/GRID,min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
            for(Box box:boxes) if(x>box.minX && x<box.maxX && z>box.minZ && z<box.maxZ) {
                min=Math.min(min,box.minY); max=Math.max(max,box.maxY);
            }
            int i=index(ix,iz); low[i]=Double.isFinite(min) ? min : top ? 1 : 0; high[i]=Double.isFinite(max) ? max : top ? 1 : 0;
        }
        return new Sampled(low,high);
    }
    private static void check(Sampled sample,int i,double low,double high,double tolerance,String label,BlockState state,double x,double z) {
        require(Math.abs(sample.low[i]-low)<=tolerance && Math.abs(sample.high[i]-high)<=tolerance,
                label+" mismatch: "+state+" at "+x+","+z+" actual="+sample.low[i]+".."+sample.high[i]+" expected="+low+".."+high);
    }
    private static float[][] points(BakedQuad quad) {
        int[] data=quad.getVertexData(); int stride=data.length/4; float[][] points=new float[4][3];
        for(int vertex=0;vertex<4;vertex++) for(int axis=0;axis<3;axis++) points[vertex][axis]=Float.intBitsToFloat(data[vertex*stride+axis]);
        return points;
    }
    private static void intersect(float[][] quad,int a,int b,int c,double x,double z,double[] bounds) {
        double ax=quad[a][0],az=quad[a][2],bx=quad[b][0],bz=quad[b][2],cx=quad[c][0],cz=quad[c][2];
        double denominator=(bz-cz)*(ax-cx)+(cx-bx)*(az-cz);
        if(Math.abs(denominator)<1e-10) return;
        double u=((bz-cz)*(x-cx)+(cx-bx)*(z-cz))/denominator;
        double v=((cz-az)*(x-cx)+(ax-cx)*(z-cz))/denominator,w=1-u-v;
        if(u<-.00001 || v<-.00001 || w<-.00001) return;
        double y=u*quad[a][1]+v*quad[b][1]+w*quad[c][1];
        bounds[0]=Math.min(bounds[0],y); bounds[1]=Math.max(bounds[1],y);
    }
    private static int index(int x,int z) { return x*GRID+z; }
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    private static final class TestSprite extends Sprite {
        TestSprite(SpriteContents contents) { super(new Identifier("erydon","probe_atlas"),contents,32,32,0,0); }
    }
}
