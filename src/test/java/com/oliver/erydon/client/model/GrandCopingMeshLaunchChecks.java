package com.oliver.erydon.client.model;

import com.oliver.erydon.block.CopingBlock;
import com.oliver.erydon.block.CopingDesign;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.EncodingFormat;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.MutableQuadViewImpl;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.resource.DirectoryResourcePack;
import net.minecraft.resource.LifecycledResourceManagerImpl;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The production loader, placed renderer and real Indigo mesh decoder retain Grand's triangular caps. */
public final class GrandCopingMeshLaunchChecks {
    private record Quad(float[][] points,Direction plane,Direction light) { }

    public static void run(CopingBlock grand) throws Exception {
        if(RendererAccess.INSTANCE.getRenderer()==null) RendererAccess.INSTANCE.registerRenderer(IndigoRenderer.INSTANCE);
        String anchor="assets/erydon/authoring_models/block/coping/georgian_grand/coping_georgian_grand_flat.json";
        Path root=Path.of(GrandCopingMeshLaunchChecks.class.getClassLoader().getResource(anchor).toURI());
        for(int i=0;i<Path.of(anchor).getNameCount();i++) root=root.getParent();
        try(var resources=new LifecycledResourceManagerImpl(ResourceType.CLIENT_RESOURCES,List.of(new DirectoryResourcePack("erydon",root,true)));
            var contents=new SpriteContents(new Identifier("erydon","block/glacium_block"),new SpriteDimensions(16,16),
                    new NativeImage(16,16,false),AnimationResourceMetadata.EMPTY)) {
            var designs=CopingModelLoadingPlugin.load(resources,Runnable::run).join();
            require(designs.size()==2 && designs.get(CopingDesign.GEORGIAN_GRAND).size()==CopingBlock.Surface.values().length,
                    "The production coping loader omitted a design or support profile");
            Sprite sprite=new Sprite(new Identifier("erydon","grand_coping_probe_atlas"),contents,64,64,16,32) {};
            BakedModel particle=(BakedModel) Proxy.newProxyInstance(BakedModel.class.getClassLoader(),new Class<?>[]{BakedModel.class},
                    (proxy,method,args) -> {
                        if(method.getName().equals("getParticleSprite")) return sprite;
                        if(method.getReturnType()==boolean.class) return true;
                        throw new AssertionError("Unexpected source model query "+method);
                    });
            var renderer=new CopingBakedModel(particle,designs.get(CopingDesign.GEORGIAN_GRAND));
            Identifier id=new Identifier("erydon","glacium_coping_georgian_grand");
            int cases=0,triangles=0,upTriangles=0,projectionWitnesses=0;
            for(var profile:CopingBlock.Surface.values()) for(Direction facing:Direction.Type.HORIZONTAL) {
                BlockState state=grand.getDefaultState().with(CopingBlock.SURFACE,profile).with(CopingBlock.FACING,facing);
                List<Quad> emitted=emit(renderer,state,Map.of(BlockPos.ORIGIN,state),sprite);
                require(!emitted.isEmpty(),"A Grand support profile disappeared: "+profile+" "+facing);
                var fallback=renderer.getQuads(state,null,Random.create(0));
                require(emitted.size()==fallback.size(),"Fabric and Axiom lost different Grand faces");
                int capTriangles=0,upCaps=0;
                for(int q=0;q<emitted.size();q++) {
                    var quad=emitted.get(q); int[] data=fallback.get(q).getVertexData();
                    for(int vertex=0;vertex<4;vertex++) for(int axis=0;axis<3;axis++)
                        require(Math.abs(quad.points[vertex][axis]-Float.intBitsToFloat(data[vertex*8+axis]))<.000003,
                                "Grand Fabric/Axiom positions diverged: "+profile+" "+facing);
                    if(unique(quad.points)==3) {
                        require(area(quad.points)>.000000001,"A cap triangle collapsed during mesh replay");
                        // The ordered vertex comparison above also preserves winding;
                        // a renderer reversal cannot pass as the same triangle.
                        capTriangles++;
                        if(quad.plane==Direction.UP) upCaps++;
                    }
                    require(quad.plane!=null,"Grand lost its authored texture plane tag");
                    require(SynapheiaRepeatBakedModel.repeatProjectionFace(id,quad.light,null,CopingTexturePlane.tag(quad.plane))==quad.plane,
                            "CTM projected a Grand cap using its slanted lighting face");
                    if(quad.light!=quad.plane) projectionWitnesses++;
                }
                require(capTriangles>=32,"Real mesh emission lost an authored triangular corner: "+profile+" "+facing+" count="+capTriangles);
                require(upCaps>0,"Grand's UP-projected triangular corners lost their CTM plane: "+profile+" "+facing);
                triangles+=capTriangles; upTriangles+=upCaps; cases++;
            }
            for(Direction facing:Direction.Type.HORIZONTAL) {
                BlockState state=grand.getDefaultState().with(CopingBlock.FACING,facing);
                Map<BlockPos,BlockState> cluster=new HashMap<>(); cluster.put(BlockPos.ORIGIN,state);
                for(Direction direction:Direction.Type.HORIZONTAL) cluster.put(BlockPos.ORIGIN.offset(direction),state);
                List<Quad> joined=emit(renderer,state,cluster,sprite);
                require(!joined.isEmpty(),"Joining all Grand edges removed the cap body");
                require(joined.stream().noneMatch(q -> unique(q.points)==3),"A fully concealed free-corner cap remained in a joined cluster");
                cases++;
            }
            require(projectionWitnesses>0,"CTM plane proof never exercised a slanted face");
            System.out.println("ERYDON_GRAND_COPING_MESH_OK cases="+cases+" retainedTriangles="+triangles
                    +" upProjectedTriangles="+upTriangles+" slantedTexturePlaneWitnesses="+projectionWitnesses
                    +" FabricAxiomOrderedPositions=3e-6 winding=preserved allFacings=true productionLoader=bothDesigns");
        }
    }

    private static List<Quad> emit(CopingBakedModel renderer,BlockState state,Map<BlockPos,BlockState> blocks,Sprite sprite) throws Exception {
        var builder=IndigoRenderer.INSTANCE.meshBuilder(); var destination=builder.getEmitter();
        var transforms=new ArrayDeque<RenderContext.QuadTransform>();
        QuadEmitter emitter=new MutableQuadViewImpl() {
            { data=new int[EncodingFormat.TOTAL_STRIDE]; clear(); }
            @Override public void emitDirectly() {
                for(var transform:transforms) if(!transform.transform(this)) return;
                destination.copyFrom(this).emit();
            }
        };
        SpriteFinder finder=(SpriteFinder) Proxy.newProxyInstance(SpriteFinder.class.getClassLoader(),new Class<?>[]{SpriteFinder.class},
                (proxy,method,args) -> sprite);
        RenderContext context=(RenderContext) Proxy.newProxyInstance(RenderContext.class.getClassLoader(),new Class<?>[]{RenderContext.class},
                (proxy,method,args) -> switch(method.getName()) {
                    case "getEmitter" -> emitter;
                    case "pushTransform" -> {
                        var transform=(RenderContext.QuadTransform)args[0];
                        if(transform instanceof WorldAlignedYRotation) {
                            var field=WorldAlignedYRotation.class.getDeclaredField("spriteFinder"); field.setAccessible(true); field.set(transform,finder);
                        }
                        transforms.push(transform); yield null;
                    }
                    case "popTransform" -> { transforms.pop(); yield null; }
                    case "hasTransform" -> !transforms.isEmpty();
                    case "isFaceCulled" -> false;
                    default -> throw new AssertionError("Unexpected Grand render context query "+method);
                });
        BlockRenderView view=(BlockRenderView) Proxy.newProxyInstance(BlockRenderView.class.getClassLoader(),new Class<?>[]{BlockRenderView.class},
                (proxy,method,args) -> switch(method.getName()) {
                    case "getBlockState" -> blocks.getOrDefault(args[0],Blocks.AIR.getDefaultState());
                    case "getFluidState" -> blocks.getOrDefault(args[0],Blocks.AIR.getDefaultState()).getFluidState();
                    case "getBlockEntity" -> null;
                    default -> throw new AssertionError("Unexpected Grand neighbour query "+method);
                });
        renderer.emitBlockQuads(view,state,BlockPos.ORIGIN,() -> Random.create(0),context);
        require(transforms.isEmpty(),"Grand renderer leaked a transform");
        List<Quad> result=new ArrayList<>();
        builder.build().forEach(quad -> {
            float[][] points=new float[4][3];
            for(int vertex=0;vertex<4;vertex++) { points[vertex][0]=quad.x(vertex); points[vertex][1]=quad.y(vertex); points[vertex][2]=quad.z(vertex); }
            result.add(new Quad(points,CopingTexturePlane.face(quad.tag()),quad.lightFace()));
        });
        return result;
    }

    private static int unique(float[][] points) {
        List<float[]> found=new ArrayList<>();
        for(var point:points) if(found.stream().noneMatch(other -> Math.abs(other[0]-point[0])<.0000001
                && Math.abs(other[1]-point[1])<.0000001 && Math.abs(other[2]-point[2])<.0000001)) found.add(point);
        return found.size();
    }
    private static double area(float[][] p) {
        double ax=p[1][0]-p[0][0],ay=p[1][1]-p[0][1],az=p[1][2]-p[0][2];
        double bx=p[2][0]-p[0][0],by=p[2][1]-p[0][1],bz=p[2][2]-p[0][2];
        double x=ay*bz-az*by,y=az*bx-ax*bz,z=ax*by-ay*bx;
        return Math.sqrt(x*x+y*y+z*z)/2;
    }
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
