package com.oliver.erydon.client.model;

import com.oliver.erydon.block.CopingBlock;
import com.oliver.erydon.block.CopingConnections;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.texture.Sprite;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

final class CopingBakedModel implements BakedModel, FabricBakedModel {
    private final BakedModel wrapped;
    private final Map<CopingBlock.Surface,CopingGeometry> geometry;
    private record MeshKey(CopingBlock.Surface surface,CopingConnections.Joins joins,boolean offset) { }
    private final LoadingCache<MeshKey,Mesh> meshes;
    private final Map<Integer,CopingHorizontalTransform> fits=new ConcurrentHashMap<>();
    CopingBakedModel(BakedModel wrapped,Map<CopingBlock.Surface,CopingGeometry> geometry) {
        this.wrapped=wrapped; this.geometry=geometry;
        meshes=CacheBuilder.newBuilder().maximumSize(1024).build(new CacheLoader<>() {
            @Override public Mesh load(MeshKey key) {
                return geometry.get(key.surface).mesh(getParticleSprite(),key.surface,key.joins,key.offset);
            }
        });
    }
    private Mesh mesh(CopingBlock.Surface surface,CopingConnections.Joins joins,boolean offset) {
        return meshes.getUnchecked(new MeshKey(surface,joins,offset && !surface.aligned()));
    }
    @Override public boolean isVanillaAdapter() { return false; }
    @Override public void emitBlockQuads(BlockRenderView view,BlockState state,BlockPos pos,Supplier<Random> random,RenderContext context) {
        CopingBlock.Surface surface=state.get(CopingBlock.SURFACE);
        Direction facing=CopingConnections.facing(view,state,pos);
        Mesh source=mesh(surface,CopingConnections.resolve(view,state,pos,facing),state.get(CopingBlock.OFFSET));
        if(surface.aligned()) {
            context.pushTransform(fit(surface,facing));
            try { source.outputTo(context.getEmitter()); } finally { context.popTransform(); }
            return;
        }
        int degrees=degrees(facing);
        context.pushTransform(quad -> {
            quad.tag(CopingTexturePlane.rotate(quad.tag(),degrees/90));
            return true;
        });
        try {
            WorldAlignedYRotation.emit(context,new MeshChild(source),degrees,true);
        } finally {
            context.popTransform();
        }
    }
    @Override public void emitItemQuads(ItemStack stack,Supplier<Random> random,RenderContext context) {
        mesh(CopingBlock.Surface.FLAT,new CopingConnections.Joins(0),false).outputTo(context.getEmitter());
    }
    @Override public List<BakedQuad> getQuads(BlockState state,Direction face,Random random) {
        if (face!=null) return List.of();
        CopingBlock.Surface surface=state==null ? CopingBlock.Surface.FLAT : state.get(CopingBlock.SURFACE);
        int turns=state==null ? 0 : degrees(state.get(CopingBlock.FACING))/90;
        List<BakedQuad> quads=new ArrayList<>();
        mesh(surface,new CopingConnections.Joins(0),state!=null && state.get(CopingBlock.OFFSET)).forEach(quad -> {
            BakedQuad baked=quad.toBakedQuad(getParticleSprite());
            if(surface.aligned() && state!=null) {
                CopingHorizontalTransform fit=fit(surface,state.get(CopingBlock.FACING));
                int[] data=baked.getVertexData().clone();
                for(int vertex=0;vertex<4;vertex++) {
                    int i=vertex*8;
                    float x=Float.intBitsToFloat(data[i]),z=Float.intBitsToFloat(data[i+2]);
                    data[i]=Float.floatToRawIntBits(fit.x(x,z)); data[i+2]=Float.floatToRawIntBits(fit.z(x,z));
                    data[i+7]=fit.normal(data[i+7]);
                }
                quads.add(HorizontalUvLock.projectFlatHorizontal(new BakedQuad(data,baked.getColorIndex(),
                        fit.direction(baked.getFace()),baked.getSprite(),baked.hasShade())));
                return;
            }
            if (turns==0) { quads.add(baked); return; }
            int[] data=baked.getVertexData().clone();
            for (int v=0;v<4;v++) {
                int offset=v*8; float x=Float.intBitsToFloat(data[offset]),z=Float.intBitsToFloat(data[offset+2]);
                for (int t=0;t<turns;t++) { float next=1-z; z=x; x=next; }
                data[offset]=Float.floatToRawIntBits(x); data[offset+2]=Float.floatToRawIntBits(z);
                data[offset+7]=rotatePackedNormal(data[offset+7],turns);
            }
            HorizontalUvLock.apply(data,baked.getSprite(),baked.getFace(),turns);
            Direction direction=baked.getFace();
            if (direction.getAxis().isHorizontal()) for(int t=0;t<turns;t++) direction=direction.rotateYClockwise();
            quads.add(new BakedQuad(data,baked.getColorIndex(),direction,baked.getSprite(),baked.hasShade()));
        });
        return List.copyOf(quads);
    }
    private CopingHorizontalTransform fit(CopingBlock.Surface surface,Direction facing) {
        return fits.computeIfAbsent(surface.ordinal()*4+facing.getHorizontal(),
                ignored -> new CopingHorizontalTransform(surface.fit.pose(facing)));
    }
    static int rotatePackedNormal(int packed,int turns) {
        int x=(byte)packed,z=(byte)(packed>>>16);
        for(int turn=0;turn<Math.floorMod(turns,4);turn++) { int next=-z; z=x; x=next; }
        // Preserve the Y component and the shader's fourth byte without requantizing.
        return (packed&0xFF00FF00)|(x&0xFF)|((z&0xFF)<<16);
    }
    private static int degrees(Direction facing) {
        return switch(facing) { case SOUTH -> 90; case WEST -> 180; case NORTH -> 270; default -> 0; };
    }
    private final class MeshChild implements BakedModel,SharedGeometryChildModel {
        private final Mesh mesh;
        MeshChild(Mesh mesh) { this.mesh=mesh; }
        @Override public void emitSharedGeometry(RenderContext context) { mesh.outputTo(context.getEmitter()); }
        @Override public boolean isVanillaAdapter() { return false; }
        @Override public List<BakedQuad> getQuads(BlockState state,Direction face,Random random) { return List.of(); }
        @Override public boolean useAmbientOcclusion() { return true; }
        @Override public boolean hasDepth() { return true; }
        @Override public boolean isSideLit() { return true; }
        @Override public boolean isBuiltin() { return false; }
        @Override public Sprite getParticleSprite() { return CopingBakedModel.this.getParticleSprite(); }
        @Override public ModelTransformation getTransformation() { return wrapped.getTransformation(); }
        @Override public ModelOverrideList getOverrides() { return wrapped.getOverrides(); }
    }
    @Override public boolean useAmbientOcclusion() { return true; }
    @Override public boolean hasDepth() { return true; }
    @Override public boolean isSideLit() { return true; }
    @Override public boolean isBuiltin() { return false; }
    @Override public Sprite getParticleSprite() { return wrapped.getParticleSprite(); }
    @Override public ModelTransformation getTransformation() { return wrapped.getTransformation(); }
    @Override public ModelOverrideList getOverrides() { return wrapped.getOverrides(); }
}
