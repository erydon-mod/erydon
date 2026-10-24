package com.oliver.erydon.client.model;

import com.google.gson.JsonParser;
import com.oliver.erydon.block.ColumnBlock;
import com.oliver.erydon.block.DoubleCircularColumnBlock;
import com.oliver.erydon.client.pom.ColumnMeshNormalTransport;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.EncodingFormat;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.MutableQuadViewImpl;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BakedQuadFactory;
import net.minecraft.client.render.model.Baker;
import net.minecraft.client.render.model.ModelRotation;
import net.minecraft.client.render.model.UnbakedModel;
import net.minecraft.client.render.model.json.JsonUnbakedModel;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourcePack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Real generated templates, native emitter, prepared family factory and Axiom views; no client/window. */
public final class DoubleColumnMeshLaunchChecks {
    private static volatile Object blackhole;
    private DoubleColumnMeshLaunchChecks() { }
    public static void run() throws Exception {
        if(RendererAccess.INSTANCE.getRenderer()==null) RendererAccess.INSTANCE.registerRenderer(IndigoRenderer.INSTANCE);
        ResourceManager resources=resources(Map.of());
        long started=System.nanoTime();
        DoubleColumnMeshBaker prepared=DoubleColumnMeshBaker.load(resources,Runnable::run).join();
        long preparedNanos=System.nanoTime()-started;
        DoubleCircularColumnBlock block=new DoubleCircularColumnBlock(AbstractBlock.Settings.create());
        int states=0,quads=0,shared=0;
        int[] encoderTriangles={0};
        Map<String,DoubleColumnMeshGeometry.Template> templates=new HashMap<>();
        for(String suffix:DoubleColumnMeshBaker.SUFFIXES) {
            try(var reader=new InputStreamReader(required("/assets/erydon/meshes/large_columns/"+suffix+".json"),StandardCharsets.UTF_8)) {
                templates.put(suffix,DoubleColumnMeshGeometry.parse(JsonParser.parseReader(reader).getAsJsonObject()));
            }
            for(boolean shaft:new boolean[]{false,true}) {
                if(shaft&&!suffix.equals("pillar"))continue;
                for(int layer=0;layer<(shaft?1:2);layer++)for(int x=0;x<2;x++)for(int z=0;z<2;z++)
                    for(var face:templates.get(suffix).faces(shaft,layer,x,z))for(var v:face.vertices())
                        require(Math.abs(v.nx()*v.nx()+v.ny()*v.ny()+v.nz()*v.nz()-1)<1.e-12,
                                "Unpacked interpolated mesh normal is not strictly unit length");
            }
        }
        for(int resolution:new int[]{16,32,64}) {
            DoubleColumnMeshBaker atlasPrepared=DoubleColumnMeshBaker.load(resources,Runnable::run).join();
            try(var contents=new SpriteContents(new Identifier("erydon","large_column_probe_"+resolution),
                    new SpriteDimensions(resolution,resolution),new NativeImage(resolution,resolution,false),AnimationResourceMetadata.EMPTY)) {
                Sprite sprite=new TestSprite(contents,resolution);
                Sources sources=new Sources();
                Baker baker=baker(sources);
                BakedModel metadata=metadata(sprite);
                BakedModel model=atlasPrepared.wrap(metadata,"aganite_column_circular_double",baker,id->sprite);
                require(model instanceof DoubleCircularColumnBakedModel,"Prepared wrapper changed family");
                require(!model.useAmbientOcclusion(),"Column frame correction changed the existing model AO policy");
                var meshField=DoubleCircularColumnBakedModel.class.getDeclaredField("meshes");meshField.setAccessible(true);
                require(meshField.get(model)!=null,"Prepared wrapper silently fell back to old cuboids");
                for(var section:DoubleCircularColumnBlock.Section.values()) for(var base:ColumnBlock.BaseStyle.values())
                    for(var capital:ColumnBlock.CapitalStyle.values()) for(int x=0;x<2;x++) for(int z=0;z<2;z++) {
                        BlockState state=block.getDefaultState().with(DoubleCircularColumnBlock.SECTION,section)
                                .with(DoubleCircularColumnBlock.BASE,base).with(DoubleCircularColumnBlock.CAPITAL,capital)
                                .with(DoubleCircularColumnBlock.X,x).with(DoubleCircularColumnBlock.Z,z);
                        List<BakedQuad> baked=model.getQuads(state,null,Random.create(0));
                        require(baked==model.getQuads(state,null,Random.create(1)),"Axiom quad list was rebuilt during a query");
                        for(Direction side:Direction.values()) require(model.getQuads(state,side,Random.create(0)).isEmpty(),"Mesh added a second cull bucket");
                        Mesh emitted=emit(model,state);
                        int[] index={0};
                        emitted.forEach(quad->{
                            require(index[0]<baked.size(),"Native/Axiom mesh counts differ");
                            BakedQuad counterpart=baked.get(index[0]++);
                            int[] data=counterpart.getVertexData();
                            if(samePosition(quad,0,3)||samePosition(quad,2,3)) {
                                require(triangleNormalSquared(quad,0,1,2)>0,"NORMAL encoder order has a degenerate first triangle");
                                require(triangleNormalSquared(quad,1,2,3)>0,"FLIP encoder order has a degenerate first triangle");
                                encoderTriangles[0]++;
                            }
                            require(ColumnMeshNormalTransport.isColumn(quad.tag()),"Column normal tag lost before CTM");
                            Direction encoded=ColumnMeshNormalTransport.projectionFace(quad.tag());
                            Direction projection=SynapheiaRepeatBakedModel.repeatProjectionFace(
                                    new Identifier("erydon","aganite_column_circular_double"),quad.lightFace(),quad.nominalFace(),quad.tag());
                            require(encoded!=null&&projection==encoded&&projection==counterpart.getFace(),"Native/Axiom/CTM source texture plane differs");
                            require(quad.cullFace()==null,"An internal ownership wall became a cull face");
                            for(int i=0;i<4;i++) {
                                require(quad.hasNormal(i),"Real mesh lacks a vertex normal");
                                double length=quad.normalX(i)*quad.normalX(i)+quad.normalY(i)*quad.normalY(i)+quad.normalZ(i)*quad.normalZ(i);
                                // Indigo stores each component as a signed byte.
                                // Each decoded component differs by <1/127;
                                // |length^2-1| <=2*sqrt(3)/127+3/(127^2).
                                double packedError=2*Math.sqrt(3)/127+3./(127*127);
                                require(Math.abs(length-1)<=packedError,"Packed mesh normal exceeds its signed-byte quantization bound");
                                float[] nativeNormal={quad.normalX(i),quad.normalY(i),quad.normalZ(i)};
                                for(int axis=0;axis<3;axis++)require(Math.abs(nativeNormal[axis]-(byte)(data[i*8+7]>>(axis*8))/127F)<=1F/127+.000001F,
                                        "Native/Axiom normals differ beyond one signed-byte quantization step");
                                float[] position={quad.x(i),quad.y(i),quad.z(i)};
                                for(int axis=0;axis<3;axis++) {
                                    require(position[axis]>=0 && position[axis]<=1,"Mesh escaped its exact block cell");
                                    require(Float.floatToRawIntBits(position[axis])==data[i*8+axis],"Native/Axiom geometry differs");
                                }
                                require(quad.u(i)>=sprite.getMinU() && quad.u(i)<=sprite.getMaxU()
                                        && quad.v(i)>=sprite.getMinV() && quad.v(i)<=sprite.getMaxV(),"Mesh UV escaped its nonzero atlas sprite");
                                require(Math.abs(quad.u(i)-Float.intBitsToFloat(data[i*8+4]))<.000001
                                        &&Math.abs(quad.v(i)-Float.intBitsToFloat(data[i*8+5]))<.000001,"Native/Axiom UV phase differs");
                                float expectedU=sprite.getFrameU(projectU(projection,position)*16);
                                float expectedV=sprite.getFrameV(projectV(projection,position)*16);
                                if(!(Math.abs(quad.u(i)-expectedU)<.000001 &&Math.abs(quad.v(i)-expectedV)<.000001)) require(false,
                                        "Real mesh changed world-cell texture phase: state="+state+" suffix="+diagnosticSuffix(state)
                                                +" layer="+(section==DoubleCircularColumnBlock.Section.BASE_UPPER||section==DoubleCircularColumnBlock.Section.CAPITAL_UPPER?1:0)
                                                +" atlas="+resolution+" quad="+(index[0]-1)+" vertex="+i
                                                +" actual="+quad.u(i)+","+quad.v(i)+" expected="+expectedU+","+expectedV
                                                +" "+diagnosticQuad(quad,counterpart,sprite));
                            }
                        });
                        require(index[0]==baked.size(),"Axiom lost real native shell polygons");
                        states++;quads+=baked.size();
                    }
                factory(atlasPrepared,metadata,baker,sprite);
                customGeometryOverride(atlasPrepared,metadata,sprite);
                if(resolution==16) {
                    benchmark(model,block,sources,sprite);
                    DoubleColumnNativeEmissionBenchmark.run(templates,sprite);
                }
                for(String suffix:DoubleColumnMeshBaker.SUFFIXES) {
                    var template=templates.get(suffix);
                    var first=template.cell(RendererAccess.INSTANCE.getRenderer(),false,0,0,0);
                    for(int i=0;i<100;i++) require(first==template.cell(RendererAccess.INSTANCE.getRenderer(),false,0,0,0),"Immutable mesh cache missed");
                    shared++;
                }
            }
        }
        crossCellProjectionContinuity();
        ColumnMeshTangentLaunchChecks.run();
        ColumnMeshLightingLaunchChecks.run();
        ColumnMeshIndigoLightingLaunchChecks.run();
        malformedMeshNegativeControl();
        require(encoderTriangles[0]>100,"Actual emitted mesh did not exercise both encoder triangle orders");
        com.oliver.erydon.client.pom.ColumnMeshNormalLaunchChecks.run();
        System.out.println("ERYDON_LARGE_COLUMN_MESH_OK states="+states+" nativeAxiomQuads="+quads+" sharedTemplates="+shared
                +" atlas=16/32/64 exactCellBounds=true phase=world factory=prepared overrides=preserved encoderTriangleCases="+encoderTriangles[0]+" preparation_ns="+preparedNanos);
    }

    private record PhaseSample(Direction face,double u,double v,String cell) { }
    private static void crossCellProjectionContinuity() {
        int shared=0;
        int[] rendererTies={0};
        for(double tilt:new double[]{0,-.00001,.00001})for(int turns=0;turns<4;turns++)for(boolean reflected:new boolean[]{false,true}) {
            double[][] points={{.3,.1,1.2},{.3,1.9,1.2},{1.4,1.9,.1+tilt},{1.4,.1,.1+tilt}};
            List<DoubleColumnMeshGeometry.Vertex> corners=new ArrayList<>();
            for(double[] point:points) {
                double x=point[0],z=point[2];
                if(reflected)x=2-x;
                for(int turn=0;turn<turns;turn++){double previous=x;x=2-z;z=previous;}
                corners.add(new DoubleColumnMeshGeometry.Vertex(x*8,point[1]*8,z*8,0,1,0));
            }
            if(reflected)java.util.Collections.reverse(corners);
            var geometry=new DoubleColumnMeshGeometry.Template(List.of(new DoubleColumnMeshGeometry.Surface(List.copyOf(corners))));
            Map<String,PhaseSample> seen=new HashMap<>();
            for(int layer=0;layer<2;layer++)for(int x=0;x<2;x++)for(int z=0;z<2;z++) {
                final int cellX=x,cellY=layer,cellZ=z;
                final int turnCase=turns;final boolean reflectedCase=reflected;
                int[] matches={0};
                geometry.cell(RendererAccess.INSTANCE.getRenderer(),false,layer,x,z).mesh().forEach(quad->{
                    Direction encoded=ColumnMeshNormalTransport.projectionFace(quad.tag());
                    Direction projection=SynapheiaRepeatBakedModel.repeatProjectionFace(
                            new Identifier("erydon","aganite_column_circular_double"),quad.lightFace(),quad.nominalFace(),quad.tag());
                    require(encoded!=null&&projection==encoded,"Cross-cell CTM lost its stable source plane");
                    if(projection!=quad.lightFace())rendererTies[0]++;
                    for(int vertex=0;vertex<4;vertex++) {
                        float[] world={cellX+quad.x(vertex),cellY+quad.y(vertex),cellZ+quad.z(vertex)};
                        String key=Math.round(world[0]*1000000.)+":"+Math.round(world[1]*1000000.)+":"+Math.round(world[2]*1000000.);
                        PhaseSample current=new PhaseSample(projection,projectU(projection,world),projectV(projection,world),cellX+","+cellY+","+cellZ);
                        PhaseSample previous=seen.putIfAbsent(key,current);
                        if(previous!=null&&!previous.cell.equals(current.cell)) {
                            require(Math.abs(previous.u-current.u)<.000002&&Math.abs(previous.v-current.v)<.000002,
                                    "A continuous near45-degree plane changed world phase across cells: tilt="+tilt+" turns="+turnCase
                                            +" reflected="+reflectedCase+" point="+key+" first="+previous+" second="+current);
                            matches[0]++;
                        }
                    }
                });
                shared+=matches[0];
            }
        }
        require(shared>40,"Near45-degree fixtures did not cross actual cell boundaries");
        require(rendererTies[0]>0,"Cross-cell controls did not exercise differing renderer light faces");
        System.out.println("ERYDON_LARGE_COLUMN_CROSS_CELL_PHASE_OK sharedVertices="+shared+" rendererTieDifferences="+rendererTies[0]+" rotations=4 reflections=2 angleCases=3");
    }

    private static String diagnosticSuffix(BlockState state) {
        return switch(state.get(DoubleCircularColumnBlock.SECTION)) {
            case SHAFT->"pillar";
            case BASE_LOWER,BASE_UPPER->state.get(DoubleCircularColumnBlock.BASE)==ColumnBlock.BaseStyle.NARROW?"base_narrow":"base";
            case CAPITAL_LOWER,CAPITAL_UPPER->switch(state.get(DoubleCircularColumnBlock.CAPITAL)) {
                case NONE->"pillar";case NARROW->"capital_narrow";case GUILLOCHE->"capital_guilloche";case GEORGIAN->"capital";
            };
        };
    }
    private static String diagnosticQuad(QuadView quad,BakedQuad source,Sprite sprite) {
        StringBuilder result=new StringBuilder("nominal=").append(quad.nominalFace()).append(" light=").append(quad.lightFace())
                .append(" bakedProjectionFace=").append(source.getFace()).append(" faceNormal=").append(quad.faceNormal())
                .append(" sprite=[").append(sprite.getMinU()).append(',').append(sprite.getMaxU()).append(';')
                .append(sprite.getMinV()).append(',').append(sprite.getMaxV()).append("] vertices=");
        for(int i=0;i<4;i++)result.append(i).append(':').append(quad.x(i)).append(',').append(quad.y(i)).append(',').append(quad.z(i))
                .append(" uv=").append(quad.u(i)).append(',').append(quad.v(i)).append(" normal=")
                .append(quad.normalX(i)).append(',').append(quad.normalY(i)).append(',').append(quad.normalZ(i)).append(';');
        return result.toString();
    }

    private static void benchmark(BakedModel mesh,DoubleCircularColumnBlock block,Sources sources,Sprite sprite) {
        BakedQuadFactory factory=new BakedQuadFactory();Random random=Random.create(0);
        for(String suffix:DoubleColumnMeshBaker.SUFFIXES) {
            JsonUnbakedModel master=sources.resolve(new Identifier("erydon","block/column/circular/column_circular_"+suffix));
            List<BakedQuad> child=new ArrayList<>();
            for(var element:master.getElements())for(var face:element.faces.entrySet())
                child.add(factory.bake(element.from,element.to,face.getValue(),sprite,face.getKey(),ModelRotation.X0_Y0,
                        element.rotation,element.shade,new Identifier("erydon","column_mesh_benchmark")));
            boolean shaft=suffix.equals("pillar");
            List<BlockState> states=new ArrayList<>();
            for(int layer=0;layer<(shaft?1:2);layer++)for(int x=0;x<2;x++)for(int z=0;z<2;z++) {
                var section=shaft?DoubleCircularColumnBlock.Section.SHAFT:suffix.startsWith("base")
                        ? layer==0?DoubleCircularColumnBlock.Section.BASE_LOWER:DoubleCircularColumnBlock.Section.BASE_UPPER
                        :layer==0?DoubleCircularColumnBlock.Section.CAPITAL_LOWER:DoubleCircularColumnBlock.Section.CAPITAL_UPPER;
                var base=suffix.equals("base_narrow")?ColumnBlock.BaseStyle.NARROW:ColumnBlock.BaseStyle.FULL;
                var capital=suffix.equals("capital_guilloche")?ColumnBlock.CapitalStyle.GUILLOCHE
                        :suffix.equals("capital_narrow")?ColumnBlock.CapitalStyle.NARROW:ColumnBlock.CapitalStyle.GEORGIAN;
                states.add(block.getDefaultState().with(DoubleCircularColumnBlock.SECTION,section)
                        .with(DoubleCircularColumnBlock.X,x).with(DoubleCircularColumnBlock.Z,z)
                        .with(DoubleCircularColumnBlock.BASE,base).with(DoubleCircularColumnBlock.CAPITAL,capital));
            }
            List<BakedQuad> old=legacy(child,shaft);List<BakedQuad> current=new ArrayList<>();
            for(BlockState state:states)current.addAll(mesh.getQuads(state,null,random));
            if(suffix.equals("capital_guilloche")) {
                require(gpuTriangles(current)<=gpuTriangles(old)*1.05,
                        "Byzantine mesh exceeded its measured five-percent GPU triangle budget: "+gpuTriangles(current)+" vs "+gpuTriangles(old));
                require(current.size()<=old.size()*1.08,
                        "Byzantine mesh exceeded its measured eight-percent quad budget: "+current.size()+" vs "+old.size());
            }
            for(int i=0;i<2;i++)blackhole=legacy(child,shaft);
            long oldNanos=0,cachedNanos=0;
            for(int trial=0;trial<3;trial++) {
                long start=System.nanoTime();blackhole=legacy(child,shaft);oldNanos+=System.nanoTime()-start;
                start=System.nanoTime();
                for(int iteration=0;iteration<5000;iteration++)for(BlockState state:states)blackhole=mesh.getQuads(state,null,random);
                cachedNanos+=(System.nanoTime()-start)/5000;
            }
            System.out.println("ERYDON_LARGE_COLUMN_MESH_METRIC style="+suffix+" legacyCellQuads="+old.size()
                    +" meshCellQuads="+current.size()+" legacyTriangles="+gpuTriangles(old)+" meshTriangles="+gpuTriangles(current)
                    +" legacySurfaceArea="+surfaceArea(old)+" meshSurfaceArea="+surfaceArea(current)
                    +" legacyAxiomGeometryNs="+(oldNanos/3)+" cachedAxiomViewsNs="+(cachedNanos/3)
                    +" runtimeMeshClipOperations=0 frameRateMeasured=false");
        }
    }
    private static List<BakedQuad> legacy(List<BakedQuad> child,boolean shaft) {
        List<BakedQuad> result=new ArrayList<>();
        for(int y=0;y<(shaft?1:2);y++)for(int x=0;x<2;x++)for(int z=0;z<2;z++)for(var face:child)
            result.addAll(DoubleColumnSlice.bake(face,y,x,z,shaft));
        return result;
    }
    private static int gpuTriangles(List<BakedQuad> quads) {
        int count=0;
        for(var quad:quads) {
            int[] d=quad.getVertexData();
            if(triangleNormalSquared(d,0,1,2)>0)count++;
            if(triangleNormalSquared(d,2,3,0)>0)count++;
        }
        return count;
    }
    private static boolean samePosition(QuadView quad,int a,int b) {
        return quad.x(a)==quad.x(b)&&quad.y(a)==quad.y(b)&&quad.z(a)==quad.z(b);
    }
    private static float triangleNormalSquared(QuadView quad,int a,int b,int c) {
        float x1=quad.x(b)-quad.x(a),y1=quad.y(b)-quad.y(a),z1=quad.z(b)-quad.z(a);
        float x2=quad.x(c)-quad.x(a),y2=quad.y(c)-quad.y(a),z2=quad.z(c)-quad.z(a);
        float x=y1*z2-z1*y2,y=z1*x2-x1*z2,z=x1*y2-y1*x2;
        return x*x+y*y+z*z;
    }
    private static double triangleNormalSquared(int[] data,int a,int b,int c) {
        double[] first=new double[3],second=new double[3];
        for(int axis=0;axis<3;axis++) {
            first[axis]=(double)Float.intBitsToFloat(data[b*8+axis])-Float.intBitsToFloat(data[a*8+axis]);
            second[axis]=(double)Float.intBitsToFloat(data[c*8+axis])-Float.intBitsToFloat(data[a*8+axis]);
        }
        double x=first[1]*second[2]-first[2]*second[1],y=first[2]*second[0]-first[0]*second[2],z=first[0]*second[1]-first[1]*second[0];
        return x*x+y*y+z*z;
    }
    private static double surfaceArea(List<BakedQuad> quads) {
        double result=0;
        for(var quad:quads) {
            int[] d=quad.getVertexData();
            for(int i=1;i<3;i++) {
                double[] a=new double[3],b=new double[3];
                for(int axis=0;axis<3;axis++) {
                    a[axis]=Float.intBitsToFloat(d[i*8+axis])-Float.intBitsToFloat(d[axis]);
                    b[axis]=Float.intBitsToFloat(d[(i+1)*8+axis])-Float.intBitsToFloat(d[axis]);
                }
                double x=a[1]*b[2]-a[2]*b[1],y=a[2]*b[0]-a[0]*b[2],z=a[0]*b[1]-a[1]*b[0];result+=Math.sqrt(x*x+y*y+z*z)/2;
            }
        }
        return result;
    }

    private static void malformedMeshNegativeControl() {
        var bad=JsonParser.parseString("{\"format\":\"erydon_large_column_mesh_v1\",\"faces\":[[[0,0,0,0,0,0],[1,0,0,0,0,0],[0,0,1,0,0,0]]]}").getAsJsonObject();
        boolean caught=false;try{DoubleColumnMeshGeometry.parse(bad);}catch(IllegalArgumentException expected){caught=true;}
        require(caught,"Non-unit normal negative control was accepted");
    }
    private static void customGeometryOverride(DoubleColumnMeshBaker prepared,BakedModel metadata,Sprite sprite)throws Exception {
        Sources changed=new Sources();
        Identifier child=ColumnBakedModel.modelId("glacium_column_circular","base");
        JsonUnbakedModel override=JsonUnbakedModel.deserialize("{\"textures\":{\"stone\":\"erydon:block/glacium_block\"},\"elements\":[{\"from\":[0,0,0],\"to\":[8,16,16],\"faces\":{\"north\":{\"texture\":\"#stone\"}}}]}");
        changed.models.put(child,override);
        BakedModel model=prepared.wrap(metadata,"glacium_column_circular_double",baker(changed),id->sprite);
        var field=DoubleCircularColumnBakedModel.class.getDeclaredField("meshes");field.setAccessible(true);
        require(field.get(model)==null,"Material-specific custom geometry was silently replaced by the generic shell");
        BakedModel untouched=prepared.wrap(metadata,"borealis_column_circular_double",baker(new Sources()),id->sprite);
        require(field.get(untouched)!=null,"One material override disabled an untouched sibling's mesh");
        Sources customSources=new Sources();
        Identifier customId=ColumnBakedModel.modelId("selenephos_column_circular","base");
        UnbakedModel custom=(UnbakedModel)Proxy.newProxyInstance(UnbakedModel.class.getClassLoader(),new Class<?>[]{UnbakedModel.class},(p,m,a)->{
            throw new AssertionError("Custom unbaked model was inspected instead of preserving its existing path");
        });
        Baker customBaker=(Baker)Proxy.newProxyInstance(Baker.class.getClassLoader(),new Class<?>[]{Baker.class},(p,m,a)->{
            if(m.getName().equals("getOrLoadModel"))return customId.equals(a[0])?custom:customSources.resolve((Identifier)a[0]);
            throw new AssertionError(m.getName());
        });
        require(field.get(prepared.wrap(metadata,"selenephos_column_circular_double",customBaker,id->sprite))==null,
                "A non-JSON custom material loader was silently replaced by the generic mesh");
    }
    private static void factory(DoubleColumnMeshBaker prepared,BakedModel metadata,Baker baker,Sprite sprite) {
        Event<ModelModifier.AfterBake> event=EventFactory.createArrayBacked(ModelModifier.AfterBake.class,callbacks->(model,context)->{
            for(var callback:callbacks)model=callback.modifyModelAfterBake(model,context);return model;
        });
        ModelLoadingPlugin.Context load=(ModelLoadingPlugin.Context)Proxy.newProxyInstance(ModelLoadingPlugin.Context.class.getClassLoader(),new Class<?>[]{ModelLoadingPlugin.Context.class},(proxy,method,args)->switch(method.getName()){
            case "modifyModelAfterBake"->event;case "addModels"->null;default->throw new AssertionError(method.getName());
        });
        new ErydonFamilyModelLoadingPlugin(prepared).onInitializeModelLoader(load);
        Function<String,BakedModel> modify=variant->{
            ModelModifier.AfterBake.Context context=(ModelModifier.AfterBake.Context)Proxy.newProxyInstance(ModelModifier.AfterBake.Context.class.getClassLoader(),new Class<?>[]{ModelModifier.AfterBake.Context.class},(proxy,method,args)->switch(method.getName()){
                case "id"->new ModelIdentifier(new Identifier("erydon","aganite_column_circular_double"),variant);
                case "baker"->baker;case "textureGetter"->(Function<SpriteIdentifier,Sprite>)(id->sprite);
                default->throw new AssertionError("Unexpected prepared factory context "+method.getName());
            });
            return event.invoker().modifyModelAfterBake(metadata,context);
        };
        BakedModel first=modify.apply("part_x=0,part_z=0,section=shaft");
        require(first instanceof DoubleCircularColumnBakedModel,"Actual prepared family factory missed large columns");
        require(first==modify.apply("part_x=1,part_z=1,section=base_upper"),"Family factory made per-state mesh wrappers");
        require(modify.apply("inventory")==metadata,"Placed mesh replaced the existing item model");
    }
    private static Baker baker(Sources sources) {
        return(Baker)Proxy.newProxyInstance(Baker.class.getClassLoader(),new Class<?>[]{Baker.class},(proxy,method,args)->{
            if(method.getName().equals("getOrLoadModel"))return sources.resolve((Identifier)args[0]);
            throw new AssertionError("Mesh unexpectedly rebaked a vanilla child: "+method.getName());
        });
    }
    private static final class Sources {
        final Map<Identifier,JsonUnbakedModel> models=new HashMap<>();
        JsonUnbakedModel resolve(Identifier id) {
            JsonUnbakedModel found=models.get(id);if(found!=null)return found;
            try {
                JsonUnbakedModel model;
                if(id.getNamespace().equals("minecraft"))model=JsonUnbakedModel.deserialize("{}");
                else try(var reader=new InputStreamReader(required("/assets/"+id.getNamespace()+"/models/"+id.getPath()+".json"),StandardCharsets.UTF_8)) {model=JsonUnbakedModel.deserialize(reader);}
                model.id=id.toString();models.put(id,model);model.setParents(this::resolve);return model;
            }catch(Exception failure){throw new IllegalStateException(id.toString(),failure);}
        }
    }
    private static ResourceManager resources(Map<Identifier,byte[]> overrides) {
        ResourcePack pack=(ResourcePack)Proxy.newProxyInstance(ResourcePack.class.getClassLoader(),new Class<?>[]{ResourcePack.class},(p,m,a)->m.getName().equals("getName")?"production-classpath":null);
        return(ResourceManager)Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),new Class<?>[]{ResourceManager.class},(p,m,a)->{
            if(m.getName().equals("getResource")) {
                Identifier id=(Identifier)a[0];String path="/assets/"+id.getNamespace()+"/"+id.getPath();
                if(overrides.containsKey(id))return Optional.of(new Resource(pack,()->new ByteArrayInputStream(overrides.get(id))));
                if(DoubleColumnMeshLaunchChecks.class.getResource(path)==null)return Optional.empty();
                return Optional.of(new Resource(pack,()->required(path)));
            }
            throw new AssertionError("Unexpected mesh resource access "+m.getName());
        });
    }
    private static java.io.InputStream required(String path) {
        var input=DoubleColumnMeshLaunchChecks.class.getResourceAsStream(path);
        if(input==null)throw new IllegalStateException("Missing production resource "+path);return input;
    }
    private static BakedModel metadata(Sprite sprite) {
        return(BakedModel)Proxy.newProxyInstance(BakedModel.class.getClassLoader(),new Class<?>[]{BakedModel.class},(p,m,a)->switch(m.getName()){
            case "getParticleSprite"->sprite;case "getQuads"->List.of();case "hasDepth","isSideLit"->true;
            case "useAmbientOcclusion","isBuiltin","isVanillaAdapter"->false;default->null;
        });
    }
    private static Mesh emit(BakedModel model,BlockState state) {
        var builder=RendererAccess.INSTANCE.getRenderer().meshBuilder();QuadEmitter original=builder.getEmitter();
        List<RenderContext.QuadTransform> transforms=new ArrayList<>();
        QuadEmitter emitter=new CaptureEmitter(original,transforms);
        RenderContext context=(RenderContext)Proxy.newProxyInstance(RenderContext.class.getClassLoader(),new Class<?>[]{RenderContext.class},(proxy,method,args)->switch(method.getName()){
            case "getEmitter"->emitter;
            case "pushTransform"->{transforms.add((RenderContext.QuadTransform)args[0]);yield null;}
            case "popTransform"->{transforms.remove(transforms.size()-1);yield null;}
            case "hasTransform"->!transforms.isEmpty();
            default->throw new AssertionError("Mesh used legacy fallback/context "+method.getName());
        });
        model.emitBlockQuads(null,state,BlockPos.ORIGIN,()->Random.create(0),context);
        require(transforms.isEmpty(),"Material transform leaked beyond column emission");return builder.build();
    }
    private static final class CaptureEmitter extends MutableQuadViewImpl {
        private final QuadEmitter output;
        private final List<RenderContext.QuadTransform> transforms;
        CaptureEmitter(QuadEmitter output,List<RenderContext.QuadTransform> transforms) {
            this.output=output;this.transforms=transforms;data=new int[EncodingFormat.TOTAL_STRIDE];clear();
        }
        @Override public void emitDirectly() {
            for(int i=transforms.size()-1;i>=0;i--) require(transforms.get(i).transform(this),"Column transform dropped a real surface");
            output.copyFrom(this).emit();
        }
    }
    private static float projectU(Direction face,float[] p) {return switch(face){case NORTH->1-p[0];case SOUTH,UP,DOWN->p[0];case WEST->p[2];case EAST->1-p[2];};}
    private static float projectV(Direction face,float[] p) {return switch(face){case UP->p[2];case DOWN->1-p[2];default->1-p[1];};}
    private static final class TestSprite extends Sprite {
        TestSprite(SpriteContents contents,int resolution){super(new Identifier("erydon","large_column_atlas"),contents,resolution*8,resolution*8,resolution*2,resolution*3);}
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
