package com.oliver.erydon.client.model;

import net.minecraft.util.math.Direction;

/**
 * Projects slope vertices onto the same world-facing texture planes used by
 * standard block faces. The repeat-CTM sprite supplies the world cell; these
 * coordinates preserve the phase inside that cell after model rotation.
 */
final class SlopeWorldUv {
    private SlopeWorldUv() {
    }

    static float projectedU(FixedSlopeRotation transform,
                            Direction nominalFace,
                            Direction finalFace,
                            float x,
                            float y,
                            float z,
                            float authoredU,
                            float authoredV) {
        float sourceX = sourceX(nominalFace, x, authoredU);
        float sourceY = sourceY(nominalFace, y);
        float sourceZ = sourceZ(nominalFace, z, authoredV);
        float textureS = finalFace == Direction.EAST || finalFace == Direction.WEST
                ? transform.positionZ(sourceX, sourceY, sourceZ)
                : transform.positionX(sourceX, sourceY, sourceZ);
        float normalized = finalFace == Direction.NORTH || finalFace == Direction.EAST
                ? 1.0F - textureS
                : textureS;
        return textureUnits(normalized);
    }

    static float projectedV(FixedSlopeRotation transform,
                            Direction nominalFace,
                            Direction finalFace,
                            float x,
                            float y,
                            float z,
                            float authoredU,
                            float authoredV) {
        float sourceX = sourceX(nominalFace, x, authoredU);
        float sourceY = sourceY(nominalFace, y);
        float sourceZ = sourceZ(nominalFace, z, authoredV);
        float textureT = finalFace == Direction.UP || finalFace == Direction.DOWN
                ? transform.positionZ(sourceX, sourceY, sourceZ)
                : transform.positionY(sourceX, sourceY, sourceZ);
        float normalized = finalFace == Direction.UP ? textureT : 1.0F - textureT;
        return textureUnits(normalized);
    }

    private static float sourceX(Direction nominalFace, float x, float authoredU) {
        return nominalFace == Direction.UP ? authoredU / 16.0F : x;
    }

    private static float sourceY(Direction nominalFace, float y) {
        return nominalFace == Direction.UP ? 0.5F : y;
    }

    private static float sourceZ(Direction nominalFace, float z, float authoredV) {
        return nominalFace == Direction.UP ? authoredV / 16.0F : z;
    }

    private static float textureUnits(float normalized) {
        if (Math.abs(normalized) <= 0.000001F) {
            return 0.0F;
        }
        if (Math.abs(normalized - 1.0F) <= 0.000001F) {
            return 16.0F;
        }
        return normalized * 16.0F;
    }
}
