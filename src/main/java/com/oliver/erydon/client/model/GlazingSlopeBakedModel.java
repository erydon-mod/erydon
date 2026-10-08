package com.oliver.erydon.client.model;

import com.oliver.erydon.block.GlazingShallowSlopeBlock;
import com.oliver.erydon.block.GlazingSlopeBlock;
import com.oliver.erydon.block.GlazingSlopeGeometry;
import com.oliver.erydon.block.GlazingSlopeGeometry.Profile;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BlockHalf;
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

/** The same smooth panes and lead frame feed both Fabric rendering and Axiom previews. */
public final class GlazingSlopeBakedModel implements BakedModel, FabricBakedModel {
    private record Key(Direction facing, String shape, BlockHalf half) { }
    private record Geometry(Mesh mesh, List<BakedQuad> quads) { }

    private final BakedModel wrapped;
    private final Profile profile;
    private final GlazingSlopeGeometry.Template template;
    private final Sprite glass;
    private final Sprite lead;
    private final Map<Key, Geometry> geometry = new ConcurrentHashMap<>();

    public GlazingSlopeBakedModel(BakedModel wrapped, Profile profile, Sprite glass, Sprite lead) {
        this(wrapped, profile, null, glass, lead);
    }

    GlazingSlopeBakedModel(BakedModel wrapped, Profile profile, GlazingSlopeGeometry.Template template,
                         Sprite glass, Sprite lead) {
        this.wrapped = wrapped;
        this.profile = profile;
        this.template = template;
        this.glass = glass;
        this.lead = lead;
    }

    private Geometry geometry(BlockState state) {
        Direction facing = state == null ? Direction.SOUTH : state.get(GlazingSlopeBlock.FACING);
        BlockHalf half = state == null || profile != Profile.STANDARD
                ? BlockHalf.BOTTOM : state.get(GlazingSlopeBlock.HALF);
        String shape = state == null ? "straight" : profile == Profile.STANDARD
                ? state.get(GlazingSlopeBlock.SHAPE).asString()
                : state.get(GlazingShallowSlopeBlock.SHAPE).asString();
        return geometry.computeIfAbsent(new Key(facing, shape, half), this::build);
    }

    private Geometry build(Key key) {
        List<GlazingSlopeGeometry.Face> faces = template == null
                ? GlazingSlopeGeometry.faces(profile, key.facing, key.shape, key.half)
                : GlazingSlopeGeometry.faces(template, key.facing, key.shape, key.half, profile == Profile.STANDARD);
        var builder = RendererAccess.INSTANCE.getRenderer().meshBuilder();
        QuadEmitter emitter = builder.getEmitter();
        for (var face : faces) {
            int count = face.vertices().size();
            if (count == 4) emit(emitter, face, 0, 1, 2, 3);
            else for (int i = 1; i < count - 1; i++) emit(emitter, face, 0, i, i + 1, i + 1);
        }
        Mesh mesh = builder.build();
        List<BakedQuad> quads = new ArrayList<>();
        mesh.forEach(quad -> quads.add(quad.toBakedQuad(quad.tag() == 1 ? lead : glass)));
        return new Geometry(mesh, List.copyOf(quads));
    }

    private void emit(QuadEmitter emitter, GlazingSlopeGeometry.Face face, int... indices) {
        var a = face.vertices().get(indices[0]);
        var b = face.vertices().get(indices[1]);
        var c = face.vertices().get(indices[2]);
        double nx = (b.y()-a.y())*(c.z()-a.z()) - (b.z()-a.z())*(c.y()-a.y());
        double ny = (b.z()-a.z())*(c.x()-a.x()) - (b.x()-a.x())*(c.z()-a.z());
        double nz = (b.x()-a.x())*(c.y()-a.y()) - (b.y()-a.y())*(c.x()-a.x());
        double length = Math.sqrt(nx*nx + ny*ny + nz*nz);
        if (length < 1.0e-10) return;
        nx /= length; ny /= length; nz /= length;
        boolean frame = "lead".equals(face.texture());
        Sprite sprite = frame ? lead : glass;
        emitter.cullFace(null).nominalFace(Direction.getFacing(nx, ny, nz)).tag(frame ? 1 : 0);
        for (int i = 0; i < 4; i++) {
            var vertex = face.vertices().get(indices[i]);
            emitter.pos(i, (float) vertex.x(), (float) vertex.y(), (float) vertex.z());
            emitter.normal(i, (float) nx, (float) ny, (float) nz);
            emitter.sprite(i, 0, sprite.getFrameU(vertex.u()), sprite.getFrameV(vertex.v()));
            emitter.spriteColor(i, 0, -1).lightmap(i, 0);
        }
        emitter.emit();
    }

    @Override public void emitBlockQuads(BlockRenderView world, BlockState state, BlockPos pos,
                                         Supplier<Random> random, RenderContext context) {
        geometry(state).mesh.outputTo(context.getEmitter());
    }
    @Override public void emitItemQuads(ItemStack stack, Supplier<Random> random, RenderContext context) {
        geometry(null).mesh.outputTo(context.getEmitter());
    }
    @Override public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
        return face == null ? geometry(state).quads : List.of();
    }
    @Override public boolean isVanillaAdapter() { return false; }
    @Override public boolean useAmbientOcclusion() { return wrapped.useAmbientOcclusion(); }
    @Override public boolean hasDepth() { return true; }
    @Override public boolean isSideLit() { return wrapped.isSideLit(); }
    @Override public boolean isBuiltin() { return false; }
    @Override public Sprite getParticleSprite() { return glass; }
    @Override public ModelTransformation getTransformation() { return wrapped.getTransformation(); }
    @Override public ModelOverrideList getOverrides() { return wrapped.getOverrides(); }
}
