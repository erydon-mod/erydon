package com.oliver.erydon.compat.worldedit;

import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.math.transform.Transform;
import com.sk89q.worldedit.registry.state.BooleanProperty;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.world.block.BlockStateHolder;

/** WorldEdit handles facing; the cell-local payload also needs its horizontal reflection basis. */
public final class CorniceAssemblyWorldEditTransform {
    private CorniceAssemblyWorldEditTransform() { }

    public static <B extends BlockStateHolder<B>> B apply(B original, B transformed, Transform transform) {
        if (!"erydon:cornice_assembly".equals(original.getBlockType().getId())) return transformed;
        Property<?> property = original.getBlockType().getProperty("mirrored");
        if (!(property instanceof BooleanProperty mirrored)) return transformed;
        Vector3 zero = transform.apply(Vector3.ZERO);
        Vector3 east = transform.apply(Vector3.at(1, 0, 0)).subtract(zero);
        Vector3 south = transform.apply(Vector3.at(0, 0, 1)).subtract(zero);
        Vector3 up = transform.apply(Vector3.at(0, 1, 0)).subtract(zero);
        // The saved architecture supports horizontal transforms. Do not turn a
        // vertical flip or tilt into an unrelated horizontal mirror.
        if (Math.abs(east.getY()) > 1e-6 || Math.abs(south.getY()) > 1e-6 || up.getY() < 1e-6
                || Math.abs(up.getX()) > 1e-6 || Math.abs(up.getZ()) > 1e-6) return transformed;
        double determinant = east.getX() * south.getZ() - south.getX() * east.getZ();
        return determinant < -1e-6 ? transformed.with(mirrored, !original.getState(mirrored)) : transformed;
    }
}
