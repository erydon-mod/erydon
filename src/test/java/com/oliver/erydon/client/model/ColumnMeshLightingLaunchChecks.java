package com.oliver.erydon.client.model;

import com.google.gson.JsonParser;
import com.oliver.erydon.block.ColumnBlock;
import com.oliver.erydon.block.DoubleCircularColumnBlock;
import link.infra.indium.renderer.IndiumRenderer;
import link.infra.indium.renderer.aocalc.AoCalculator;
import link.infra.indium.renderer.mesh.EncodingFormat;
import link.infra.indium.renderer.mesh.MutableQuadViewImpl;
import link.infra.indium.renderer.render.AbstractBlockRenderContext;
import link.infra.indium.renderer.render.BlockRenderInfo;
import me.jellysquid.mods.sodium.client.model.light.data.LightDataAccess;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.util.TriState;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.LightType;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Actual Indium light/color output at shared native mesh vertices; no Minecraft window. */
public final class ColumnMeshLightingLaunchChecks {
    private ColumnMeshLightingLaunchChecks() { }
    private record Sample(int light,int color,BlockPos owner) { }

    public static void run() throws Exception {
        int shared=0,flatSteps=0,smoothSteps=0,maxFlat=0,maxSmooth=0;
        int minimum=255,maximum=0;
        int[] uniformProblems={0,0,0,0};
        for(boolean uniform:new boolean[]{false,true})for(String suffix:DoubleColumnMeshBaker.SUFFIXES) {
            DoubleColumnMeshGeometry.Template template;
            try(var reader=new InputStreamReader(ColumnMeshLightingLaunchChecks.class.getResourceAsStream(
                    "/assets/erydon/meshes/large_columns/"+suffix+".json"),StandardCharsets.UTF_8)) {
                template=DoubleColumnMeshGeometry.parse(JsonParser.parseReader(reader).getAsJsonObject());
            }
            boolean shaft=suffix.equals("pillar");
            Fixture fixture=new Fixture(suffix,shaft,uniform);
            LightContext context=new LightContext(fixture.world,new Cache(fixture.world));
            Map<String,Sample> flatSeen=new HashMap<>(),smoothSeen=new HashMap<>();
            int[] counts={0,0,0,0,0,255,0,255,0};
            for(int y=0;y<(shaft?3:2);y++)for(int x=0;x<2;x++)for(int z=0;z<2;z++) {
                BlockPos owner=new BlockPos(x,y,z);
                var cell=template.cell(IndigoRenderer.INSTANCE,shaft,shaft?0:y,x,z);
                cell.mesh().forEach(source->{
                    require(source.material().ambientOcclusion()==TriState.FALSE,"Column lighting correction changed the production AO policy");
                    int blockData=context.cache.get(owner);
                    require(!LightDataAccess.unpackOP(blockData)&&!LightDataAccess.unpackFO(blockData)
                                    &&!LightDataAccess.unpackFC(blockData)&&Math.abs(LightDataAccess.unpackAO(blockData)-1)<.00001,
                            "Actual partial-column block data is opaque/full or self-occluding: "+suffix+" "+owner);
                    RealQuad flat=context.shade(source,owner,false),smooth=context.shade(source,owner,true);
                    for(int vertex=0;vertex<4;vertex++) {
                        if(uniform&&(flat.lightmap(vertex)!=0x00F00000||flat.color(vertex)!=-1))uniformProblems[0]++;
                        // Deliberately rejected candidate: native forced AO produces quantized-normal cell bands.
                        if(uniform&&(smooth.lightmap(vertex)!=0x00F00000||smooth.color(vertex)!=-1)) {
                            if(uniformProblems[2]++==0) {
                                RealQuad flatNoDiffuse=context.shade(source,owner,false,true),smoothNoDiffuse=context.shade(source,owner,true,true);
                                double normalLength=source.normalX(vertex)*source.normalX(vertex)+source.normalY(vertex)*source.normalY(vertex)+source.normalZ(vertex)*source.normalZ(vertex);
                                System.out.println("ERYDON_COLUMN_UNIFORM_DETAIL style="+suffix+" owner="+owner+" vertex="+vertex
                                        +" pos="+source.x(vertex)+","+source.y(vertex)+","+source.z(vertex)
                                        +" flatLight="+Integer.toHexString(flat.lightmap(vertex))+" smoothLight="+Integer.toHexString(smooth.lightmap(vertex))
                                        +" flatColor="+Integer.toHexString(flat.color(vertex))+" smoothColor="+Integer.toHexString(smooth.color(vertex))
                                        +" normal="+source.normalX(vertex)+","+source.normalY(vertex)+","+source.normalZ(vertex)+" normalLengthSquared="+normalLength
                                        +" flags="+flat.geometryFlags()+" lightFace="+flat.lightFace()+" nominal="+flat.nominalFace()+" shade="+flat.hasShade()
                                        +" flatNoDiffuse="+Integer.toHexString(flatNoDiffuse.lightmap(vertex))+"/"+Integer.toHexString(flatNoDiffuse.color(vertex))
                                        +" smoothNoDiffuse="+Integer.toHexString(smoothNoDiffuse.lightmap(vertex))+"/"+Integer.toHexString(smoothNoDiffuse.color(vertex)));
                            }
                        }
                        require(flat.normalX(vertex)==smooth.normalX(vertex)&&flat.normalY(vertex)==smooth.normalY(vertex)
                                        &&flat.normalZ(vertex)==smooth.normalZ(vertex),"Lighting altered transported corner normals");
                        float wx=owner.getX()+source.x(vertex),wy=owner.getY()+source.y(vertex),wz=owner.getZ()+source.z(vertex);
                        String key=Math.round(wx*1000000.)+":"+Math.round(wy*1000000.)+":"+Math.round(wz*1000000.)
                                +":"+Math.round(source.normalX(vertex)*127)+":"+Math.round(source.normalY(vertex)*127)+":"+Math.round(source.normalZ(vertex)*127);
                        Sample beforeFlat=flatSeen.putIfAbsent(key,new Sample(flat.lightmap(vertex),flat.color(vertex),owner));
                        Sample beforeSmooth=smoothSeen.putIfAbsent(key,new Sample(smooth.lightmap(vertex),smooth.color(vertex),owner));
                        int light=smooth.lightmap(vertex);
                        counts[5]=Math.min(counts[5],(light>>>16)&255);counts[6]=Math.max(counts[6],(light>>>16)&255);
                        int chosen=flat.lightmap(vertex);counts[7]=Math.min(counts[7],(chosen>>>16)&255);counts[8]=Math.max(counts[8],(chosen>>>16)&255);
                        if(beforeFlat!=null&&!beforeFlat.owner.equals(owner)) {
                            int a=difference(beforeFlat.light,flat.lightmap(vertex)),b=difference(beforeSmooth.light,smooth.lightmap(vertex));
                            counts[0]++;if(a>1)counts[1]++;if(b>1)counts[2]++;
                            counts[3]=Math.max(counts[3],a);counts[4]=Math.max(counts[4],b);
                        }
                    }
                });
            }
            if(!uniform){shared+=counts[0];flatSteps+=counts[1];smoothSteps+=counts[2];maxFlat=Math.max(maxFlat,counts[3]);maxSmooth=Math.max(maxSmooth,counts[4]);
                minimum=Math.min(minimum,counts[7]);maximum=Math.max(maximum,counts[8]);}
            else {uniformProblems[1]+=counts[1];uniformProblems[3]+=counts[2];}
            System.out.println("ERYDON_COLUMN_LIGHTING_SAMPLE uniform="+uniform+" style="+suffix+" shared="+counts[0]+" flatSteps="+counts[1]
                    +" smoothSteps="+counts[2]+" maxFlatChannelStep="+counts[3]+" maxSmoothChannelStep="+counts[4]
                    +" smoothSkyRange="+counts[5]+".."+counts[6]);
        }
        require(uniformProblems[0]==0&&uniformProblems[1]==0,"Uniform sky acquired light/color/AO changes: vertices="+uniformProblems[0]+" sharedSteps="+uniformProblems[1]);
        require(shared>100&&flatSteps>100&&maxFlat>=16,"Actual flat-light negative control failed to reproduce cell steps");
        require(uniformProblems[2]>0&&uniformProblems[3]>0,"Forced-AO negative control stopped exposing the pinned renderer quantization seam");
        require(maximum-minimum>=16,"Chosen production lighting lost its real sky/block-light variation");
        continuousWorldFields();
        System.out.println("ERYDON_COLUMN_LIGHTING_OK shared="+shared+" variableFieldFlatStepsObserved="+flatSteps+" rejectedForcedAoVariableSteps="+smoothSteps
                +" chosenFlatUniformSteps="+uniformProblems[1]+" rejectedForcedAoUniformSteps="+uniformProblems[3]
                +" actualIndiumShadeQuad=true lightCache=actualBlockStates voxelOwnership=cell meshArchitecture=unchanged");
    }

    private static int difference(int a,int b) {return Math.max(Math.abs((a&255)-(b&255)),Math.abs(((a>>>16)&255)-((b>>>16)&255)));}

    private enum Field { STEP, AFFINE, FULL_SKY, DARK, TORCH_ROOF, EMISSIVE_STATE }

    /** Closed-form fields are independent of the production interpolator's grid/weight implementation. */
    private static int expected(Field field,double x,double y,double z) {
        double sky=switch(field) {case AFFINE->7.5+x+y-z;case FULL_SKY,EMISSIVE_STATE->15;default->0;};
        double block=switch(field) {case AFFINE->5+x-y;case TORCH_ROOF->14-Math.abs(x+.5)-Math.abs(y-1.5)-Math.abs(z-1.5);case EMISSIVE_STATE->15;default->0;};
        return (int)Math.round(sky*16)<<16 | (int)Math.round(block*16);
    }

    private static void continuousWorldFields() throws Exception {
        int cases=0,vertices=0,shared=0,oldSeams=0,maxStep=0,foreignChecks=0,torchVertices=0;
        BlockPos[] origins={BlockPos.ORIGIN,new BlockPos(-2,-2,-2),new BlockPos(15,0,15),new BlockPos(-17,-2,-17)};
        for(Field field:new Field[]{Field.AFFINE,Field.FULL_SKY,Field.DARK,Field.TORCH_ROOF,Field.EMISSIVE_STATE})
            for(String suffix:DoubleColumnMeshBaker.SUFFIXES) {
                boolean pillar=suffix.equals("pillar");
                if((field==Field.DARK||field==Field.TORCH_ROOF||field==Field.EMISSIVE_STATE)&&!pillar)continue;
                int originCount=field==Field.FULL_SKY||field==Field.EMISSIVE_STATE||!pillar?1:origins.length;
                for(int originIndex=0;originIndex<originCount;originIndex++) {
                    BlockPos origin=origins[originIndex];
                    DoubleColumnMeshGeometry.Template template;
                    try(var reader=new InputStreamReader(ColumnMeshLightingLaunchChecks.class.getResourceAsStream(
                            "/assets/erydon/meshes/large_columns/"+suffix+".json"),StandardCharsets.UTF_8)) {
                        template=DoubleColumnMeshGeometry.parse(JsonParser.parseReader(reader).getAsJsonObject());
                    }
                    Fixture fixture=new Fixture(suffix,pillar,field,origin);
                    LightContext context=new LightContext(fixture.world,new Cache(fixture.world));
                    Map<String,Sample> seen=new HashMap<>(),oldSeen=new HashMap<>();
                    int[] counts={0,0,0,0,0,0,0};
                    for(int y=0;y<(pillar?3:2);y++)for(int x=0;x<2;x++)for(int z=0;z<2;z++) {
                        BlockPos owner=origin.add(x,y,z);
                        require(!ColumnMeshLighting.hasScope(),"Prior cell retained its lighting scope");
                        int depth=ColumnMeshLighting.push(fixture.world,owner);
                        try {
                        template.cell(IndigoRenderer.INSTANCE,pillar,pillar?0:y,x,z).mesh().forEach(source->{
                            RealQuad old=context.shade(source,owner,false);
                            int sampled=ColumnMeshLighting.sampleCount();
                            RealQuad foreign=context.foreign(source,owner);
                            require(ColumnMeshLighting.sampleCount()==sampled,"Foreign tag sampled the continuous light field");
                            RealQuad chosen=context.chosen(source,owner);
                            RealQuad authored=field==Field.DARK?context.chosen(source,owner,0x00C00090,false):null;
                            RealQuad emissive=field==Field.DARK?context.chosen(source,owner,0,true):null;
                            for(int v=0;v<4;v++) {
                                double wx=owner.getX()+(double)source.x(v),wy=owner.getY()+(double)source.y(v),wz=owner.getZ()+(double)source.z(v);
                                int wanted=expected(field,wx-origin.getX(),wy-origin.getY(),wz-origin.getZ());
                                require(difference(chosen.lightmap(v),wanted)<=1,"Actual post-render world light differs from closed-form field: "
                                        +field+" style="+suffix+" owner="+owner+" vertex="+v+" world="+wx+","+wy+","+wz
                                        +" actual="+Integer.toHexString(chosen.lightmap(v))+" expected="+Integer.toHexString(wanted));
                                require(chosen.color(v)==-1,"Continuous light correction added color/AO under oldLighting=false");
                                require(foreign.lightmap(v)==old.lightmap(v)&&foreign.color(v)==old.color(v),"Column correction modified a foreign-tag draw");
                                for(int vertex=0;vertex<4;vertex++)require(chosen.x(vertex)==source.x(vertex)&&chosen.y(vertex)==source.y(vertex)
                                                &&chosen.z(vertex)==source.z(vertex)&&chosen.u(vertex)==source.u(vertex)&&chosen.v(vertex)==source.v(vertex)
                                                &&chosen.normalX(vertex)==old.normalX(vertex)&&chosen.normalY(vertex)==old.normalY(vertex)
                                                &&chosen.normalZ(vertex)==old.normalZ(vertex),"World-light correction changed mesh/UV/normal fields");
                                String key=Math.round(wx*1000000.)+":"+Math.round(wy*1000000.)+":"+Math.round(wz*1000000.);
                                Sample previous=seen.putIfAbsent(key,new Sample(chosen.lightmap(v),chosen.color(v),owner));
                                Sample oldPrevious=oldSeen.putIfAbsent(key,new Sample(old.lightmap(v),old.color(v),owner));
                                if(previous!=null&&!previous.owner.equals(owner)) {
                                    int step=difference(previous.light,chosen.lightmap(v));
                                    require(step<=1,"Actual continuous light has a shared-cell/chunk seam "+field+" "+origin+" "+key);
                                    counts[1]++;counts[3]=Math.max(counts[3],step);
                                    if(difference(oldPrevious.light,old.lightmap(v))>1)counts[2]++;
                                }
                                if(field==Field.TORCH_ROOF) {
                                    require((chosen.lightmap(v)>>>16)==0&&(chosen.lightmap(v)&255)>0,"Roofed torch field became full-bright or lost local light");
                                    counts[5]++;
                                }
                                if(field==Field.DARK)require(chosen.lightmap(v)==0,"Dark world was made emissive");
                                if(authored!=null)require(authored.lightmap(v)==0x00C00090,"Custom authored vertex light was overwritten");
                                if(emissive!=null)require(emissive.lightmap(v)==0x00F000F0,"Emissive material lost native full brightness");
                                counts[0]++;counts[4]++;
                            }
                        });
                        require(ColumnMeshLighting.sampleCount()<=27,"Lighting emission exceeded its 3x3x3 sampling budget");
                        } finally { ColumnMeshLighting.restore(depth); }
                        require(!ColumnMeshLighting.hasScope(),"Completed cell retained lighting scope");
                    }
                    require(counts[1]>100,"Continuous fixture missed actual shared owner vertices");
                    vertices+=counts[0];shared+=counts[1];oldSeams+=counts[2];maxStep=Math.max(maxStep,counts[3]);foreignChecks+=counts[4];torchVertices+=counts[5];cases++;
                    System.out.println("ERYDON_COLUMN_CONTINUOUS_LIGHT_SAMPLE field="+field+" style="+suffix+" origin="+origin
                            +" vertices="+counts[0]+" shared="+counts[1]+" oldFlatSeams="+counts[2]+" maxChosenStep="+counts[3]);
                }
            }
        require(cases==24&&vertices>100000&&shared>1000&&oldSeams>100&&torchVertices>1000,"Incomplete continuous-light fixture coverage "+cases);
        scopeFailureAndNesting();
        System.out.println("ERYDON_COLUMN_CONTINUOUS_LIGHT_OK cases="+cases+" vertices="+vertices+" shared="+shared+" rejectedFlatSeams="+oldSeams
                    +" maxStep="+maxStep+" foreignTagChecks="+foreignChecks+" torchUnderRoof="+torchVertices+" negativeAndChunkEdges=true closedFormExpected=true actualPostShade=true emissive+authored=preserved maxCellSamples=27");
    }

    private static void scopeFailureAndNesting() throws Exception {
        Fixture fixture=new Fixture("pillar",true,Field.DARK,BlockPos.ORIGIN);
        LightContext context=new LightContext(fixture.world,new Cache(fixture.world));
        DoubleColumnMeshGeometry.Template template;
        try(var reader=new InputStreamReader(ColumnMeshLightingLaunchChecks.class.getResourceAsStream(
                "/assets/erydon/meshes/large_columns/pillar.json"),StandardCharsets.UTF_8)) {
            template=DoubleColumnMeshGeometry.parse(JsonParser.parseReader(reader).getAsJsonObject());
        }
        boolean[] checked={false};
        template.cell(IndigoRenderer.INSTANCE,true,0,0,0).mesh().forEach(source->{
            if(checked[0])return;checked[0]=true;
            context.shade(source,BlockPos.ORIGIN,false); // Prime only the renderer's native owner/offset cache.
            int outer=ColumnMeshLighting.push(fixture.world,BlockPos.ORIGIN);
            try {
                int reads=fixture.lightReads;
                context.foreign(source,BlockPos.ORIGIN);
                require(ColumnMeshLighting.sampleCount()==0&&fixture.lightReads==reads,"Foreign tag touched the world sampler");
                int inner=ColumnMeshLighting.push(fixture.world,new BlockPos(1,0,0));
                try {context.chosen(source,new BlockPos(1,0,0));}
                finally {ColumnMeshLighting.restore(inner);}
                require(ColumnMeshLighting.hasScope()&&ColumnMeshLighting.sampleCount()==0,"Nested draw corrupted the parent cell cache");
                fixture.failLightReads=true;
                boolean failed=false;
                try {context.chosen(source,BlockPos.ORIGIN);}
                catch(LightProbeFailure expected){failed=true;}
                require(failed,"World-light exception negative control was not exercised");
            } finally {fixture.failLightReads=false;ColumnMeshLighting.restore(outer);}
            require(!ColumnMeshLighting.hasScope(),"Failed lighting draw leaked its scope");
            int recovered=ColumnMeshLighting.push(fixture.world,BlockPos.ORIGIN);
            try {
                RealQuad result=context.chosen(source,BlockPos.ORIGIN);
                for(int vertex=0;vertex<4;vertex++)require(result.lightmap(vertex)==0,"Failed draw contaminated the next cell's light");
                require(ColumnMeshLighting.sampleCount()>0&&ColumnMeshLighting.sampleCount()<=27,"Recovered draw did not resample its own world");
            } finally {ColumnMeshLighting.restore(recovered);}
            require(!ColumnMeshLighting.hasScope(),"Recovered draw leaked its scope");
        });
        require(checked[0],"Missing real mesh quad for scoped-light failure controls");
    }

    private static final class LightProbeFailure extends RuntimeException { }

    private static final class Fixture {
        final BlockRenderView world;
        int lightReads;
        boolean failLightReads;
        Fixture(String suffix,boolean shaft,boolean uniform) {
            this(suffix,shaft,uniform?Field.FULL_SKY:Field.STEP,BlockPos.ORIGIN);
        }
        Fixture(String suffix,boolean shaft,Field field,BlockPos origin) {
            var settings=AbstractBlock.Settings.copy(Blocks.STONE).nonOpaque();
            if(field==Field.EMISSIVE_STATE)settings.emissiveLighting((state,view,pos)->true);
            DoubleCircularColumnBlock block=new DoubleCircularColumnBlock(settings);
            world=(BlockRenderView)Proxy.newProxyInstance(BlockRenderView.class.getClassLoader(),new Class<?>[]{BlockRenderView.class},
                    (proxy,method,args)->switch(method.getName()) {
                        case "getBlockState" -> {
                            BlockPos pos=(BlockPos)args[0];
                            pos=pos.subtract(origin);
                            if(field==Field.TORCH_ROOF&&pos.getY()>=4)yield Blocks.STONE.getDefaultState();
                            if(field==Field.TORCH_ROOF&&pos.equals(new BlockPos(-1,1,1)))yield Blocks.TORCH.getDefaultState();
                            if(pos.getX()<0||pos.getX()>1||pos.getZ()<0||pos.getZ()>1||pos.getY()<0||pos.getY()>2)yield Blocks.AIR.getDefaultState();
                            var section=shaft?DoubleCircularColumnBlock.Section.SHAFT:suffix.startsWith("base")
                                    ?pos.getY()==0?DoubleCircularColumnBlock.Section.BASE_LOWER:DoubleCircularColumnBlock.Section.BASE_UPPER
                                    :pos.getY()==0?DoubleCircularColumnBlock.Section.CAPITAL_LOWER:DoubleCircularColumnBlock.Section.CAPITAL_UPPER;
                            yield block.getDefaultState().with(DoubleCircularColumnBlock.X,pos.getX()).with(DoubleCircularColumnBlock.Z,pos.getZ())
                                    .with(DoubleCircularColumnBlock.SECTION,section)
                                    .with(DoubleCircularColumnBlock.BASE,suffix.equals("base_narrow")?ColumnBlock.BaseStyle.NARROW:ColumnBlock.BaseStyle.FULL)
                                    .with(DoubleCircularColumnBlock.CAPITAL,suffix.equals("capital_guilloche")?ColumnBlock.CapitalStyle.GUILLOCHE:
                                            suffix.equals("capital_narrow")?ColumnBlock.CapitalStyle.NARROW:ColumnBlock.CapitalStyle.GEORGIAN);
                        }
                        case "getBrightness","getLightLevel" -> {
                            if(args[0] instanceof Direction)yield 1F; // Active shader oldLighting=false; no diffuse-axis hypothesis.
                            lightReads++;
                            if(failLightReads)throw new LightProbeFailure();
                            BlockPos p=(BlockPos)args[1];
                            p=p.subtract(origin);
                            int level=switch(field) {
                                case FULL_SKY->args[0]==LightType.SKY?15:0;
                                case DARK,EMISSIVE_STATE->0;
                                case AFFINE->args[0]==LightType.SKY?8+p.getX()+p.getY()-p.getZ():5+p.getX()-p.getY();
                                case TORCH_ROOF->args[0]==LightType.SKY?0:14-Math.abs(p.getX()+1)-Math.abs(p.getY()-1)-Math.abs(p.getZ()-1);
                                case STEP->args[0]==LightType.SKY?11+p.getY()-p.getZ()+(p.getX()>0?2:0):4+p.getX()+p.getY();
                            };
                            yield Math.max(0,Math.min(15,level));
                        }
                        case "getFluidState" -> Fluids.EMPTY.getDefaultState();
                        case "getBlockEntity","getLightingProvider" -> null;
                        case "getColor" -> 0xFFFFFF;
                        case "getHeight" -> 256;
                        case "getBottomY" -> -64;
                        default -> throw new AssertionError("Unexpected light fixture access "+method.getName());
                    });
        }
    }

    private static final class Cache extends LightDataAccess {
        private final Map<Long,Integer> values=new HashMap<>();
        Cache(BlockRenderView world){this.world=world;}
        @Override public int get(int x,int y,int z){return values.computeIfAbsent(BlockPos.asLong(x,y,z),ignored->compute(x,y,z));}
    }

    private static final class LightContext extends AbstractBlockRenderContext {
        private final LightDataAccess cache;
        LightContext(BlockRenderView world,LightDataAccess cache) throws Exception {
            this.cache=cache;
            var unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
            blockInfo=(BlockRenderInfo)((sun.misc.Unsafe)unsafeField.get(null)).allocateInstance(BlockRenderInfo.class);
            blockInfo.blockView=world;blockInfo.blockPos=BlockPos.ORIGIN;blockInfo.blockState=world.getBlockState(BlockPos.ORIGIN);
            aoCalc=new AoCalculator(blockInfo,cache);
        }
        RealQuad shade(QuadView source,BlockPos pos,boolean smooth) {return shade(source,pos,smooth,false);}
        RealQuad shade(QuadView source,BlockPos pos,boolean smooth,boolean disableDiffuse) {
            return shade(source,pos,smooth,disableDiffuse,0,0,false);
        }
        RealQuad chosen(QuadView source,BlockPos pos){return chosen(source,pos,0,false);}
        RealQuad chosen(QuadView source,BlockPos pos,int authored,boolean emissive){return shade(source,pos,false,false,source.tag(),authored,emissive);}
        RealQuad foreign(QuadView source,BlockPos pos){return shade(source,pos,false,false,0x0DAE0000,0,false);}
        private RealQuad shade(QuadView source,BlockPos pos,boolean smooth,boolean disableDiffuse,int tag,int authored,boolean emissive) {
            blockInfo.blockPos=pos;blockInfo.blockState=blockInfo.blockView.getBlockState(pos);aoCalc.clear();
            RealQuad quad=new RealQuad();
            quad.material(IndiumRenderer.INSTANCE.materialFinder().ambientOcclusion(smooth?TriState.TRUE:TriState.FALSE).disableDiffuse(disableDiffuse).emissive(emissive).find());
            quad.cullFace(source.cullFace()).nominalFace(source.nominalFace()).tag(tag);
            for(int vertex=0;vertex<4;vertex++) {
                quad.pos(vertex,source.x(vertex),source.y(vertex),source.z(vertex)).uv(vertex,source.u(vertex),source.v(vertex))
                        .normal(vertex,source.normalX(vertex),source.normalY(vertex),source.normalZ(vertex)).color(vertex,-1).lightmap(vertex,authored);
            }
            shadeQuad(quad,false,smooth,emissive);return quad;
        }
        @Override protected LightDataAccess getLightCache(){return cache;}
        @Override protected void bufferQuad(MutableQuadViewImpl quad,Material material){throw new AssertionError("Lighting fixture must stop before buffer output");}
    }

    private static final class RealQuad extends MutableQuadViewImpl {
        RealQuad(){data=new int[EncodingFormat.TOTAL_STRIDE];clear();}
        @Override public void emitDirectly(){throw new AssertionError("Unexpected lighting fixture emission");}
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
