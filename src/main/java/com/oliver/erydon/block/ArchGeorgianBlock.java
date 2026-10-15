package com.oliver.erydon.block;

import com.google.gson.JsonParser;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Georgian pilasters repeat below a one- or two-row authored crown. */
public final class ArchGeorgianBlock extends ArchRomanesqueBlock {
    private record ShapeKey(Arrangement arrangement, int width, Direction facing) { }
    private static final Map<ShapeKey, VoxelShape> SHAPES = new ConcurrentHashMap<>();
    private static final class Authored {
        static final Map<String, List<double[]>> BOXES = loadBoxes();
    }

    public ArchGeorgianBlock(Settings settings) { super(settings); }

    @Override protected boolean supportsColumnStyle() { return false; }

    @Override protected Direction getClusterRightDirection(Direction facing) {
        return facing.rotateYCounterclockwise();
    }

    @Override public ActionResult onUse(BlockState state, World world, BlockPos pos,
                                      PlayerEntity player, Hand hand, BlockHitResult hit) {
        return ActionResult.PASS;
    }

    @Override protected Arrangement computeArrangement(int width, int height, int x, int y, StyleSet style) {
        if (width < 1 || height < 1) return Arrangement.SMALL_TOP;
        int crownRows = Math.min(height, (width + 2) / 3);
        if (y < crownRows) {
            if (width == 1) return Arrangement.SMALL_TOP;
            if (width == 2) return x == 0 ? Arrangement.DOUBLE_TOP_L : Arrangement.DOUBLE_TOP_R;
            if (width == 3) return x == 1 ? Arrangement.TOP_LARGE
                    : x == 0 ? Arrangement.TRIPLE_TOP_L : Arrangement.TRIPLE_TOP_R;
            int edge = Math.min(x, width - 1 - x);
            String role = width % 2 == 1 && x == width / 2 ? "C" : (x < width / 2 ? "L" : "R") + edge;
            return Arrangement.valueOf("WIDE_H" + crownRows + "_Y" + y + "_" + role);
        }
        if (x != 0 && x != width - 1) return Arrangement.TRIPLE_VOID;
        boolean base = y == height - 1;
        if (width == 1) return base ? Arrangement.SMALL_BASE : Arrangement.SMALL_BODY;
        String prefix = width == 2 ? "DOUBLE_" : width == 3 ? "TRIPLE_" : "WIDE_";
        return Arrangement.valueOf(prefix + "COLUMN_" + (base ? "BASE_" : "SHAFT_") + (x == 0 ? "L" : "R"));
    }

    @Override public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        var key = new ShapeKey(state.get(ARRANGEMENT), state.get(WIDTH), state.get(FACING));
        return SHAPES.computeIfAbsent(key, ArchGeorgianBlock::shape);
    }

    @Override public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        var shape = getCollisionShape(state, world, pos, context);
        return shape.isEmpty() ? VoxelShapes.fullCube() : shape;
    }

    @Override protected boolean isVoidState(BlockState state) {
        return getCollisionShape(state, net.minecraft.world.EmptyBlockView.INSTANCE,
                BlockPos.ORIGIN, ShapeContext.absent()).isEmpty();
    }

    private static VoxelShape shape(ShapeKey key) {
        var boxes = new ArrayList<double[]>();
        int turns = switch (key.facing) { case NORTH -> 2; case EAST -> 3; case WEST -> 1; default -> 0; };
        for (String suffix : GeorgianArchLayout.components(key.arrangement, key.width)) {
            var source = Authored.BOXES.get(suffix);
            if (source == null) throw new IllegalStateException("Missing Georgian arch shape " + suffix);
            for (double[] authored : source) {
                double[] box = authored;
                for (int turn = 0; turn < turns; turn++) {
                    box = new double[]{1-box[5], box[1], box[0], 1-box[2], box[4], box[3]};
                }
                boxes.add(box);
            }
        }
        return AlcoveVoxelShapes.fromBoxes(boxes);
    }

    private static Map<String, List<double[]>> loadBoxes() {
        String path = "assets/erydon/interaction_shapes/arch_georgian.json";
        var stream = ArchGeorgianBlock.class.getClassLoader().getResourceAsStream(path);
        if (stream == null) throw new IllegalStateException("Missing " + path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var document = JsonParser.parseReader(reader).getAsJsonObject();
            if (document.get("schema").getAsInt() != 1 || document.get("units_per_block").getAsInt() != 16) {
                throw new IllegalStateException("Unsupported Georgian arch shape contract");
            }
            var result = new HashMap<String, List<double[]>>();
            for (var entry : document.getAsJsonObject("components").entrySet()) {
                var boxes = new ArrayList<double[]>();
                for (var encoded : entry.getValue().getAsJsonArray()) {
                    var values = encoded.getAsJsonArray();
                    if (values.size() != 6) throw new IllegalStateException("Invalid Georgian shape box");
                    double[] box = new double[6];
                    for (int i = 0; i < 6; i++) {
                        box[i] = values.get(i).getAsDouble() / 16;
                        if (!Double.isFinite(box[i])) throw new IllegalStateException("Non-finite Georgian shape box");
                    }
                    if (box[0] >= box[3] || box[1] >= box[4] || box[2] >= box[5]) {
                        throw new IllegalStateException("Empty Georgian shape box");
                    }
                    boxes.add(box);
                }
                result.put(entry.getKey(), List.copyOf(boxes));
            }
            return Map.copyOf(result);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot read Georgian arch shapes", exception);
        }
    }
}
