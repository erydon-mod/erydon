package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.util.math.Direction;

/** Reflects an assembled arch quad, including winding, normals, and face metadata. */
final class ArchHorizontalReflection implements RenderContext.QuadTransform {
    private static final ArchHorizontalReflection X = new ArchHorizontalReflection(Direction.Axis.X);
    private static final ArchHorizontalReflection Z = new ArchHorizontalReflection(Direction.Axis.Z);
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private final Direction.Axis axis;

    private ArchHorizontalReflection(Direction.Axis axis) {
        this.axis = axis;
    }

    static ArchHorizontalReflection forState(Direction facing, boolean centre) {
        boolean reflectX = (facing.getAxis() == Direction.Axis.X) != centre;
        return reflectX ? X : Z;
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
            quad.uv(i, scratch.u[source], scratch.v[source]);
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
        }
        return new BakedQuad(data, quad.getColorIndex(), face(quad.getFace()),
                quad.getSprite(), quad.hasShade());
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
