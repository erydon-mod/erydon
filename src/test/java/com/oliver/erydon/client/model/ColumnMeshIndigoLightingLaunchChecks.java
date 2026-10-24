package com.oliver.erydon.client.model;

import com.oliver.erydon.client.pom.ColumnMeshNormalTransport;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.api.util.TriState;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.fabricmc.fabric.impl.client.indigo.renderer.aocalc.AoCalculator;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.EncodingFormat;
import net.fabricmc.fabric.impl.client.indigo.renderer.mesh.MutableQuadViewImpl;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.AbstractBlockRenderContext;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.BlockRenderInfo;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.LightType;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Actual Fabric-woven Indigo renderQuad -> flat shade -> buffer output, without a client/window. */
public final class ColumnMeshIndigoLightingLaunchChecks {
    private static final int AUTHORED=0x009000B0;
    private static final Method RENDER=method();
    private static final sun.misc.Unsafe UNSAFE=unsafe();
    private ColumnMeshIndigoLightingLaunchChecks() { }
    private enum FieldKind { AFFINE, FULL_SKY, DARK }

    public static void run() throws Exception {
        int cases=0,vertices=0,changed=0,foreign=0,colors=0,emissive=0;
        int[] tags={ColumnMeshNormalTransport.tag(),ColumnMeshNormalTransport.tag(Direction.DOWN),
                ColumnMeshNormalTransport.tag(Direction.UP),ColumnMeshNormalTransport.tag(Direction.NORTH),
                ColumnMeshNormalTransport.tag(Direction.SOUTH),ColumnMeshNormalTransport.tag(Direction.WEST),
                ColumnMeshNormalTransport.tag(Direction.EAST)};
        for(FieldKind field:FieldKind.values())for(BlockPos origin:new BlockPos[]{BlockPos.ORIGIN,new BlockPos(-17,-2,-17),new BlockPos(15,0,15)})
            for(boolean directional:new boolean[]{false,true}) {
                Fixture fixture=new Fixture(field,origin,directional);
                OutputContext context=context(fixture);
                for(int cell=0;cell<2;cell++) {
                    BlockPos owner=origin.east(cell);
                    for(int tag:tags)for(int authored:new int[]{0,AUTHORED}) {
                        ProbeQuad input=quad(cell,tag,authored,false);
                        ProbeQuad nativeResult=render(context,input,owner,false);
                        ProbeQuad chosen=render(context,input,owner,true);
                        for(int v=0;v<4;v++) {
                            double x=cell+input.x(v),y=input.y(v),z=input.z(v);
                            int expected=max(authored,expected(field,x,y,z));
                            require(delta(chosen.lightmap(v),expected)<=1,"Actual woven Indigo light differs from closed-form field: "
                                    +field+" origin="+origin+" cell="+cell+" tag="+Integer.toHexString(tag)+" vertex="+v
                                    +" actual="+Integer.toHexString(chosen.lightmap(v))+" expected="+Integer.toHexString(expected));
                            require(chosen.color(v)==nativeResult.color(v),"Indigo column light correction changed native diffuse color");
                            if(directional)require(chosen.color(v)==0xFFCCCCCC,"Native directional SOUTH diffuse field was lost");
                            else require(chosen.color(v)==-1,"Neutral diffuse field acquired a color/AO change");
                            parity(input,chosen,v);
                            if(chosen.lightmap(v)!=nativeResult.lightmap(v))changed++;
                            vertices++;if(directional)colors++;
                        }
                        cases++;
                    }
                    for(int tag:new int[]{0,0x0DAE0000,0x0EC10007,0x0EC10101}) {
                        ProbeQuad input=quad(cell,tag,AUTHORED,false);
                        ProbeQuad a=render(context,input,owner,false),b=render(context,input,owner,true);
                        for(int v=0;v<4;v++) {
                            require(a.lightmap(v)==b.lightmap(v)&&a.color(v)==b.color(v),"Woven Indigo changed a foreign tag");
                            parity(input,b,v);foreign++;
                        }
                    }
                    ProbeQuad lit=render(context,quad(cell,tags[4],AUTHORED,true),owner,true);
                    for(int v=0;v<4;v++){require(lit.lightmap(v)==0x00F000F0,"Woven Indigo changed emissive fullbright");emissive++;}
                }
                // These are identical world vertices emitted by independently owned native cells.
                ProbeQuad left=render(context,quad(0,tags[4],0,false),origin,true);
                ProbeQuad right=render(context,quad(1,tags[4],0,false),origin.east(),true);
                require(delta(left.lightmap(2),right.lightmap(1))<=1&&delta(left.lightmap(3),right.lightmap(0))<=1,
                        "Actual Indigo retained an owner-cell light seam at a shared world vertex");
                require(left.color(2)==right.color(1)&&left.color(3)==right.color(0),
                        "Actual Indigo introduced a native diffuse color seam across cells");
            }
        scopeRestoration();
        require(changed>100&&vertices>1000&&foreign>100&&colors>100&&emissive>100,"Indigo lighting fixture did not exercise its woven path");
        require(!ColumnMeshLighting.hasScope(),"Indigo lighting fixture leaked an emission scope");
        System.out.println("ERYDON_COLUMN_INDIGO_LIGHTING_OK cases="+cases+" vertices="+vertices+" changedFromFlat="+changed
                +" foreignChecks="+foreign+" nativeDirectionalColors="+colors+" emissiveChecks="+emissive
                +" actualWovenRenderQuad=true closedFormFields=affine/fullsky/dark authoredMax=preserved negativeAndChunkEdges=true nestedAndFailureCleanup=true");
    }

    private static void scopeRestoration() throws Exception {
        Fixture affine=new Fixture(FieldKind.AFFINE,BlockPos.ORIGIN,false),dark=new Fixture(FieldKind.DARK,BlockPos.ORIGIN,false);
        OutputContext a=context(affine),b=context(dark);
        ProbeQuad input=quad(0,ColumnMeshNormalTransport.tag(Direction.SOUTH),0,false);
        int previous=ColumnMeshLighting.push(affine.world,BlockPos.ORIGIN);
        try {
            ProbeQuad inner=render(b,input,BlockPos.ORIGIN,true);
            for(int v=0;v<4;v++)require(inner.lightmap(v)==0,"Nested Indigo scope borrowed outer light");
            require(ColumnMeshLighting.hasScope(),"Nested Indigo draw discarded the outer scope");
            ProbeQuad resumed=render(a,input,BlockPos.ORIGIN,false);
            for(int v=0;v<4;v++)require(delta(resumed.lightmap(v),expected(FieldKind.AFFINE,input.x(v),input.y(v),input.z(v)))<=1,
                    "Nested Indigo draw did not restore the outer world/light coordinates");
        } finally {ColumnMeshLighting.restore(previous);}
        BlockRenderView broken=(BlockRenderView)Proxy.newProxyInstance(BlockRenderView.class.getClassLoader(),new Class<?>[]{BlockRenderView.class},
                (p,m,args)->{
                    if((m.getName().equals("getLightLevel")||m.getName().equals("getBrightness"))
                            &&args[0] instanceof LightType&&!((BlockPos)args[1]).equals(BlockPos.ORIGIN))
                        throw new IllegalStateException("deliberate neighbour-light query failure");
                    try{return m.invoke(affine.world,args);}catch(InvocationTargetException failure){throw failure.getCause();}
                });
        boolean failed=false;
        try {render(context(new Fixture(broken,affine.state)),input,BlockPos.ORIGIN,true);}
        catch(AssertionError expected){failed=true;}
        require(failed&&!ColumnMeshLighting.hasScope(),"Indigo renderer failure retained a world/light scope");
        ProbeQuad nativeAfter=render(a,input,BlockPos.ORIGIN,false);
        require(nativeAfter.lightmap(0)==nativeAfter.lightmap(2),"A failed tagged draw altered the next native flat-light draw");
    }

    private static int expected(FieldKind field,double x,double y,double z) {
        return switch(field) {
            case AFFINE->(int)Math.round((7.5+x+y-z)*16)<<16|(int)Math.round((5+x-y)*16);
            case FULL_SKY->0x00F00000;
            case DARK->0;
        };
    }
    private static int max(int a,int b){return Math.max(a&65535,b&65535)|Math.max(a>>>16,b>>>16)<<16;}
    private static int delta(int a,int b){return Math.max(Math.abs((a&65535)-(b&65535)),Math.abs((a>>>16)-(b>>>16)));}
    private static ProbeQuad quad(int cell,int tag,int authored,boolean emissive) {
        ProbeQuad q=new ProbeQuad();float low=cell==0?.25F:0,high=cell==0?1:.75F;
        float[][] points={{low,.875F,.375F},{low,.125F,.375F},{high,.125F,.375F},{high,.875F,.375F}};
        for(int i=0;i<4;i++)q.pos(i,points[i][0],points[i][1],points[i][2]).uv(i,points[i][0],points[i][1])
                .normal(i,0,0,1).color(i,-1).lightmap(i,authored);
        q.material(IndigoRenderer.INSTANCE.materialFinder().ambientOcclusion(TriState.FALSE).emissive(emissive).find())
                .colorIndex(-1).cullFace(null).nominalFace(Direction.SOUTH).tag(tag);
        return q;
    }
    private static void parity(ProbeQuad a,ProbeQuad b,int v) {
        require(a.x(v)==b.x(v)&&a.y(v)==b.y(v)&&a.z(v)==b.z(v)&&a.u(v)==b.u(v)&&a.v(v)==b.v(v)
                &&a.normalX(v)==b.normalX(v)&&a.normalY(v)==b.normalY(v)&&a.normalZ(v)==b.normalZ(v)
                &&a.tag()==b.tag()&&a.material()==b.material(),"Indigo light correction changed geometry/UV/normal/tag/material");
    }
    private static ProbeQuad render(OutputContext context,ProbeQuad source,BlockPos owner,boolean scoped) throws Exception {
        context.info().blockPos=owner;context.output=null;
        ProbeQuad copy=new ProbeQuad();copy.copyFrom(source);
        int previous=scoped?ColumnMeshLighting.push(context.info().blockView,owner):-1;
        try { RENDER.invoke(context,copy,false); }
        catch(InvocationTargetException failure) {throw new AssertionError("Actual Indigo renderQuad failed",failure.getCause());}
        finally {if(scoped)ColumnMeshLighting.restore(previous);}
        require(context.output!=null,"Actual Indigo renderQuad never reached native buffer output");return context.output;
    }
    private record Fixture(BlockRenderView world,BlockState state) {
        Fixture(FieldKind field,BlockPos origin,boolean directional) {
            this(world(field,origin,directional),new com.oliver.erydon.block.DoubleCircularColumnBlock(
                    AbstractBlock.Settings.copy(Blocks.STONE).nonOpaque()).getDefaultState());
        }
        private static BlockRenderView world(FieldKind field,BlockPos origin,boolean directional) {
            return (BlockRenderView)Proxy.newProxyInstance(BlockRenderView.class.getClassLoader(),new Class<?>[]{BlockRenderView.class},
                    (p,m,args)->switch(m.getName()) {
                        case "getBrightness","getLightLevel" -> {
                            if(args[0] instanceof Direction d)yield !directional||!((boolean)args[1])?1F:switch(d){case UP->1F;case DOWN->.5F;case NORTH,SOUTH->.8F;default->.6F;};
                            LightType type=(LightType)args[0];BlockPos pos=((BlockPos)args[1]).subtract(origin);
                            int level=switch(field){case FULL_SKY->type==LightType.SKY?15:0;case DARK->0;
                                case AFFINE->type==LightType.SKY?8+pos.getX()+pos.getY()-pos.getZ():5+pos.getX()-pos.getY();};
                            yield Math.max(0,Math.min(15,level));
                        }
                        case "getBlockState"->Blocks.AIR.getDefaultState();
                        case "getHeight"->256;case "getBottomY"->-64;
                        case "getLightingProvider","getBlockEntity"->null;
                        default->throw new AssertionError("Unexpected Indigo light world access: "+m.getName());
                    });
        }
    }
    private static OutputContext context(Fixture fixture) throws Exception {
        // The real constructors require MinecraftClient's color registry. Neither colorization nor AO
        // is active here; retain the actual woven render/shade code and supply only its normal inputs.
        OutputContext context=(OutputContext)UNSAFE.allocateInstance(OutputContext.class);
        BlockRenderInfo info=(BlockRenderInfo)UNSAFE.allocateInstance(BlockRenderInfo.class);
        info.blockView=fixture.world;info.blockPos=BlockPos.ORIGIN;info.blockState=fixture.state;
        set(context,"blockInfo",info);set(context,"lightPos",new BlockPos.Mutable());
        set(context,"activeTransform",(RenderContext.QuadTransform)quad->true);
        return context;
    }
    private static void set(Object target,String name,Object value) throws Exception {
        for(Class<?> c=target.getClass();c!=null;c=c.getSuperclass())try {
            Field field=c.getDeclaredField(name);field.setAccessible(true);field.set(target,value);return;
        }catch(NoSuchFieldException ignored){ }
        throw new NoSuchFieldException(name);
    }
    private static Method method() {
        try {Method method=AbstractBlockRenderContext.class.getDeclaredMethod("renderQuad",MutableQuadViewImpl.class,boolean.class);method.setAccessible(true);return method;}
        catch(ReflectiveOperationException failure){throw new ExceptionInInitializerError(failure);}
    }
    private static sun.misc.Unsafe unsafe() {
        try {Field field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);return(sun.misc.Unsafe)field.get(null);}
        catch(ReflectiveOperationException failure){throw new ExceptionInInitializerError(failure);}
    }
    private static final class OutputContext extends AbstractBlockRenderContext {
        private ProbeQuad output;
        BlockRenderInfo info(){return blockInfo;}
        @Override protected AoCalculator createAoCalc(BlockRenderInfo info){return null;}
        @Override protected VertexConsumer getVertexConsumer(RenderLayer layer){return null;}
        @Override protected void bufferQuad(MutableQuadViewImpl quad,VertexConsumer ignored){output=new ProbeQuad();output.copyFrom(quad);}
    }
    private static final class ProbeQuad extends MutableQuadViewImpl {
        ProbeQuad(){data=new int[EncodingFormat.TOTAL_STRIDE];clear();}
        @Override public void emitDirectly(){throw new AssertionError("Unexpected Indigo fixture emission");}
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
