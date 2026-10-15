package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.math.Direction;

/** Reflects an assembled arch quad, including winding, normals, and face metadata. */
final class ArchHorizontalReflection implements RenderContext.QuadTransform {
    private static final ArchHorizontalReflection X = new ArchHorizontalReflection(Direction.Axis.X);
    private static final ArchHorizontalReflection Z = new ArchHorizontalReflection(Direction.Axis.Z);
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private final Direction.Axis axis;
    private final boolean worldUv;
    private SpriteFinder spriteFinder;

    private ArchHorizontalReflection(Direction.Axis axis) {
        this(axis, false);
    }

    private ArchHorizontalReflection(Direction.Axis axis, boolean worldUv) {
        this.axis = axis;
        this.worldUv = worldUv;
    }

    static ArchHorizontalReflection forState(Direction facing, boolean centre) {
        boolean reflectX = (facing.getAxis() == Direction.Axis.X) != centre;
        return reflectX ? X : Z;
    }

    /** A wrapper-local depth mirror keeps an authored left profile's front/back placement. */
    static ArchHorizontalReflection forDepthMirror(Direction facing) {
        return new ArchHorizontalReflection(facing.getAxis(), true);
    }

    Direction face(Direction source) {
        if (source == null || source.getAxis() != axis) {
            return source;
        }
        return source.getOpposite();
    }

    @Override
    public boolean transform(MutableQuadView quad) {
        Scratch scratch = SCRATCH.get();
        Direction cullFace = quad.cullFace();
        Direction nominalFace = quad.nominalFace();
        Direction lightFace = quad.lightFace();
        Sprite sprite = worldUv ? findSprite(quad) : null;

        for (int i = 0; i < 4; i++) {
            scratch.x[i] = quad.x(i);
            scratch.y[i] = quad.y(i);
            scratch.z[i] = quad.z(i);
            scratch.u[i] = quad.u(i);
            scratch.v[i] = quad.v(i);
            scratch.colors[i] = quad.color(i);
            scratch.lightmaps[i] = quad.lightmap(i);
            scratch.normals[i] = quad.hasNormal(i);
            if (scratch.normals[i]) {
                scratch.nx[i] = quad.normalX(i);
                scratch.ny[i] = quad.normalY(i);
                scratch.nz[i] = quad.normalZ(i);
            }
        }

        for (int i = 0; i < 4; i++) {
            int source = (4 - i) & 3;
            quad.pos(i, axis == Direction.Axis.X ? 1.0F - scratch.x[source] : scratch.x[source],
                    scratch.y[source], axis == Direction.Axis.Z ? 1.0F - scratch.z[source] : scratch.z[source]);
            float u = scratch.u[source];
            float v = scratch.v[source];
            if (sprite != null) {
                if (flipU(lightFace)) u = reflectedU(sprite, u);
                else v = reflectedV(sprite, v);
            }
            quad.uv(i, u, v);
            quad.color(i, scratch.colors[source]);
            quad.lightmap(i, scratch.lightmaps[source]);
            if (scratch.normals[source]) {
                quad.normal(i, axis == Direction.Axis.X ? -scratch.nx[source] : scratch.nx[source],
                        scratch.ny[source], axis == Direction.Axis.Z ? -scratch.nz[source] : scratch.nz[source]);
            }
        }

        // cullFace also sets nominalFace, so restore the latter afterwards.
        quad.cullFace(face(cullFace));
        quad.nominalFace(face(nominalFace));
        return true;
    }

    BakedQuad reflect(BakedQuad quad) {
        int[] old = quad.getVertexData();
        int[] data = old.clone();
        for (int i = 0; i < 4; i++) {
            int from = ((4 - i) & 3) * 8;
            int to = i * 8;
            System.arraycopy(old, from, data, to, 8);
            int position = to + (axis == Direction.Axis.X ? 0 : 2);
            data[position] = Float.floatToRawIntBits(1.0F - Float.intBitsToFloat(data[position]));
            int normalShift = axis == Direction.Axis.X ? 0 : 16;
            int normal = data[to + 7];
            int component = (byte) (normal >> normalShift);
            data[to + 7] = (normal & ~(0xFF << normalShift))
                    | ((-component & 0xFF) << normalShift);
            if (worldUv && quad.getSprite() != null) {
                int uvIndex = to + (flipU(quad.getFace()) ? 4 : 5);
                float value = Float.intBitsToFloat(data[uvIndex]);
                data[uvIndex] = Float.floatToRawIntBits(flipU(quad.getFace())
                        ? reflectedU(quad.getSprite(), value) : reflectedV(quad.getSprite(), value));
            }
        }
        return new BakedQuad(data, quad.getColorIndex(), face(quad.getFace()),
                quad.getSprite(), quad.hasShade());
    }

    private boolean flipU(Direction face) {
        return face == null || face.getAxis() != Direction.Axis.Y || axis == Direction.Axis.X;
    }

    private Sprite findSprite(MutableQuadView quad) {
        if (spriteFinder == null) {
            spriteFinder = SpriteFinder.get(MinecraftClient.getInstance().getBakedModelManager()
                    .getAtlas(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE));
        }
        return spriteFinder.find(quad);
    }

    private static float reflectedU(Sprite sprite, float value) {
        return sprite.getMinU() + sprite.getMaxU() - value;
    }

    private static float reflectedV(Sprite sprite, float value) {
        return sprite.getMinV() + sprite.getMaxV() - value;
    }

    private static final class Scratch {
        private final float[] x = new float[4];
        private final float[] y = new float[4];
        private final float[] z = new float[4];
        private final float[] u = new float[4];
        private final float[] v = new float[4];
        private final float[] nx = new float[4];
        private final float[] ny = new float[4];
        private final float[] nz = new float[4];
        private final int[] colors = new int[4];
        private final int[] lightmaps = new int[4];
        private final boolean[] normals = new boolean[4];
    }
}
