package com.oliver.erydon.block;

import net.minecraft.util.math.Box;
import net.minecraft.util.shape.ArrayVoxelShape;
import net.minecraft.util.shape.BitSetVoxelSet;
import net.minecraft.util.shape.VoxelSet;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/** Builds one exact coordinate grid instead of repeatedly merging authored slices. */
final class AlcoveVoxelShapes {
    private AlcoveVoxelShapes() { }

    static VoxelShape fromBoxes(List<double[]> boxes) {
        if (boxes.isEmpty()) return VoxelShapes.empty();
        double[][] coordinates = new double[3][];
        for (int axis = 0; axis < 3; axis++) {
            TreeSet<Double> values = new TreeSet<>();
            for (double[] box : boxes) {
                values.add(box[axis]);
                values.add(box[axis + 3]);
            }
            coordinates[axis] = values.stream().mapToDouble(Double::doubleValue).toArray();
        }
        BitSetVoxelSet cells = new BitSetVoxelSet(coordinates[0].length - 1,
                coordinates[1].length - 1, coordinates[2].length - 1);
        for (double[] box : boxes) {
            int[] first = new int[3], last = new int[3];
            for (int axis = 0; axis < 3; axis++) {
                first[axis] = Arrays.binarySearch(coordinates[axis], box[axis]);
                last[axis] = Arrays.binarySearch(coordinates[axis], box[axis + 3]);
            }
            for (int x = first[0]; x < last[0]; x++) {
                for (int y = first[1]; y < last[1]; y++) {
                    for (int z = first[2]; z < last[2]; z++) cells.set(x, y, z);
                }
            }
        }
        return new AuthoredShape(cells, coordinates[0], coordinates[1], coordinates[2]);
    }

    private static final class AuthoredShape extends ArrayVoxelShape {
        private final List<Box> boxes;

        private AuthoredShape(VoxelSet cells, double[] x, double[] y, double[] z) {
            super(cells, x, y, z);
            boxes = List.copyOf(super.getBoundingBoxes());
        }

        @Override public List<Box> getBoundingBoxes() {
            return boxes;
        }
    }
}
