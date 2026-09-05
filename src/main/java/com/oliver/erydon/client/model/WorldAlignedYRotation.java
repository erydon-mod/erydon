package com.oliver.erydon.client.model;

import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.texture.Sprite;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.math.Direction;

/**
 * Shared runtime Y rotation for CTM-enabled component models. Geometry, face
 * metadata and horizontal UVs are rotated together; vanilla blockstate uvlock
 * cannot see component rotations performed after baking.
 */
final class WorldAlignedYRotation implements RenderContext.QuadTransform {
    private final int turns;
    private final boolean clearCullFace;
    private final boolean diagonal;
    private SpriteFinder spriteFinder;

    private WorldAlignedYRotation(int turns, boolean clearCullFace, boolean diagonal) {
        this.turns = turns;
        this.clearCullFace = clearCullFace;
        this.diagonal = diagonal;
    }

    static void emit(RenderContext context, BakedModel model, int degrees) {
        emit(context, model, degrees, false);
    }

    static void emit(RenderContext context, BakedModel model, int degrees, boolean clearCullFace) {
        if (model == null) {
            return;
        }
        boolean diagonal = degrees == 45;
        if (!diagonal && degrees % 90 != 0) {
            throw new IllegalArgumentException("Expected a quarter turn or a 45 degree column rotation");
        }
        int turns = Math.floorMod(degrees / 90, 4);
        if (turns == 0 && !clearCullFace && !diagonal) {
            SharedGeometryChildModel.emit(context, model);
            return;
        }

        context.pushTransform(new WorldAlignedYRotation(turns, clearCullFace || diagonal, diagonal));
        try {
            SharedGeometryChildModel.emit(context, model);
        } finally {
            context.popTransform();
        }
    }

    @Override
    public boolean transform(MutableQuadView quad) {
        Direction sourceFace = quad.lightFace();
        for (int vertex = 0; vertex < 4; vertex++) {
            float x = quad.x(vertex);
            float y = quad.y(vertex);
            float z = quad.z(vertex);
            quad.pos(vertex, rotateX(x, z), y, rotateZ(x, z));

            if (quad.hasNormal(vertex)) {
                float normalX = quad.normalX(vertex);
                float normalY = quad.normalY(vertex);
                float normalZ = quad.normalZ(vertex);
                quad.normal(vertex, rotateNormalX(normalX, normalZ), normalY, rotateNormalZ(normalX, normalZ));
            }
        }

        quad.cullFace(clearCullFace ? null : rotateFace(quad.cullFace()));
        quad.nominalFace(rotateFace(quad.nominalFace()));
        if (sourceFace != null && sourceFace.getAxis() == Direction.Axis.Y) {
            if (diagonal) {
                HorizontalUvLock.projectFlatHorizontal(quad, findSprite(quad), sourceFace);
            } else {
                HorizontalUvLock.apply(quad, findSprite(quad), sourceFace, turns);
            }
        }
        return true;
    }

    private Sprite findSprite(MutableQuadView quad) {
        if (spriteFinder == null) {
            spriteFinder = SpriteFinder.get(MinecraftClient.getInstance()
                    .getBakedModelManager()
                    .getAtlas(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE));
        }
        return spriteFinder.find(quad);
    }

    private float rotateX(float x, float z) {
        if (diagonal) return 0.5F + diagonalX(x - 0.5F, z - 0.5F);
        return switch (turns) {
            case 1 -> 1.0F - z;
            case 2 -> 1.0F - x;
            case 3 -> z;
            default -> x;
        };
    }

    private float rotateZ(float x, float z) {
        if (diagonal) return 0.5F + diagonalZ(x - 0.5F, z - 0.5F);
        return switch (turns) {
            case 1 -> x;
            case 2 -> 1.0F - z;
            case 3 -> 1.0F - x;
            default -> z;
        };
    }

    private float rotateNormalX(float x, float z) {
        if (diagonal) return diagonalX(x, z);
        return switch (turns) {
            case 1 -> -z;
            case 2 -> -x;
            case 3 -> z;
            default -> x;
        };
    }

    private float rotateNormalZ(float x, float z) {
        if (diagonal) return diagonalZ(x, z);
        return switch (turns) {
            case 1 -> x;
            case 2 -> -z;
            case 3 -> -x;
            default -> z;
        };
    }

    private Direction rotateFace(Direction face) {
        if (face == null || face.getAxis() == Direction.Axis.Y) {
            return face;
        }

        // A diagonal side has no axis-aligned nominal or culling face.
        if (diagonal) return null;

        Direction rotated = face;
        for (int i = 0; i < turns; i++) {
            rotated = rotated.rotateYClockwise();
        }
        return rotated;
    }

    static float diagonalX(float x, float z) {
        return (x - z) * 0.70710677F;
    }

    static float diagonalZ(float x, float z) {
        return (x + z) * 0.70710677F;
    }
}
