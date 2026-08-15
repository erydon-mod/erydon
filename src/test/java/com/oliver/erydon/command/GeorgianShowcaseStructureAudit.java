package com.oliver.erydon.command;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class GeorgianShowcaseStructureAudit {
    private static final int EXPECTED_BLOCKS = 1_561;
    private static final int EXPECTED_PALETTE_STATES = 57;
    private static final int[] EXPECTED_SIZE = {47, 7, 20};

    private static final Map<String, Integer> EXPECTED_BLOCK_COUNTS = Map.ofEntries(
            Map.entry("erydon:glacium_block", 1_231),
            Map.entry("erydon:glacium_slope", 130),
            Map.entry("erydon:glacium_ceiling_coffered_georgian_white_small", 60),
            Map.entry("erydon:glacium_ceiling_coffered_georgian_black_small", 60),
            Map.entry("erydon:glacium_cornice_georgian", 31),
            Map.entry("erydon:glacium_surround_georgian", 16),
            Map.entry("erydon:glacium_window_french_georgian", 9),
            Map.entry("erydon:glacium_alcove_georgian", 9),
            Map.entry("erydon:glacium_wall_georgian", 8),
            Map.entry("minecraft:oak_sign", 7)
    );

    private static final Map<String, Integer> EXPECTED_STATE_COUNTS = Map.ofEntries(
            Map.entry("erydon:glacium_block", 1),
            Map.entry("erydon:glacium_slope", 8),
            Map.entry("erydon:glacium_ceiling_coffered_georgian_white_small", 6),
            Map.entry("erydon:glacium_ceiling_coffered_georgian_black_small", 6),
            Map.entry("erydon:glacium_cornice_georgian", 3),
            Map.entry("erydon:glacium_surround_georgian", 8),
            Map.entry("erydon:glacium_window_french_georgian", 9),
            Map.entry("erydon:glacium_alcove_georgian", 9),
            Map.entry("erydon:glacium_wall_georgian", 6),
            Map.entry("minecraft:oak_sign", 1)
    );

    private GeorgianShowcaseStructureAudit() {
    }

    public static void main(String[] args) throws Exception {
        Path structurePath = args.length == 0
                ? Path.of("src/main/resources/data/erydon/structures/showcase/blocktypes/georgian.nbt")
                : Path.of(args[0]);
        NbtCompound root;
        try (InputStream input = Files.newInputStream(structurePath)) {
            root = NbtIo.readCompressed(input);
        }

        verifySize(root.getList("size", NbtElement.INT_TYPE));
        NbtList palette = root.getList("palette", NbtElement.COMPOUND_TYPE);
        require(palette.size() == EXPECTED_PALETTE_STATES,
                "Expected " + EXPECTED_PALETTE_STATES + " palette states, found " + palette.size());

        Map<String, Integer> stateCounts = new HashMap<>();
        for (int index = 0; index < palette.size(); index++) {
            String name = palette.getCompound(index).getString("Name");
            require(!name.equals("minecraft:air") && !name.equals("minecraft:cave_air")
                            && !name.equals("minecraft:void_air"),
                    "Palette contains air state " + name);
            stateCounts.merge(name, 1, Integer::sum);
        }
        require(stateCounts.equals(EXPECTED_STATE_COUNTS),
                "Unexpected state counts: " + stateCounts);

        NbtList blocks = root.getList("blocks", NbtElement.COMPOUND_TYPE);
        require(blocks.size() == EXPECTED_BLOCKS,
                "Expected " + EXPECTED_BLOCKS + " blocks, found " + blocks.size());
        Map<String, Integer> blockCounts = new HashMap<>();
        Set<String> positions = new HashSet<>();
        for (int index = 0; index < blocks.size(); index++) {
            NbtCompound block = blocks.getCompound(index);
            int stateIndex = block.getInt("state");
            require(stateIndex >= 0 && stateIndex < palette.size(),
                    "Block " + index + " uses invalid palette state " + stateIndex);

            NbtList pos = block.getList("pos", NbtElement.INT_TYPE);
            require(pos.size() == 3, "Block " + index + " has invalid position data");
            int x = pos.getInt(0);
            int y = pos.getInt(1);
            int z = pos.getInt(2);
            require(x >= 0 && x < EXPECTED_SIZE[0]
                            && y >= 0 && y < EXPECTED_SIZE[1]
                            && z >= 0 && z < EXPECTED_SIZE[2],
                    "Block " + index + " is out of bounds at " + x + "," + y + "," + z);
            require(positions.add(x + "," + y + "," + z),
                    "Duplicate block at " + x + "," + y + "," + z);
            require(!block.contains("nbt"), "Structure should not embed language-specific block entity NBT");

            String name = palette.getCompound(stateIndex).getString("Name");
            blockCounts.merge(name, 1, Integer::sum);
        }
        require(blockCounts.equals(EXPECTED_BLOCK_COUNTS),
                "Unexpected block counts: " + blockCounts);
        require(root.getList("entities", NbtElement.COMPOUND_TYPE).isEmpty(),
                "Showcase structure must not contain entities");

        assertPropertyValues(palette, "erydon:glacium_slope", "shape",
                Set.of("straight", "outer_left", "outer_right"));
        assertPropertyValues(palette, "erydon:glacium_slope", "facing",
                Set.of("north", "south", "east", "west"));
        assertPropertyValues(palette, "erydon:glacium_cornice_georgian", "shape",
                Set.of("straight", "inner_corner"));
        assertPropertyValues(palette, "erydon:glacium_alcove_georgian", "part",
                Set.of("base", "middle", "top"));
        assertPropertyValues(palette, "erydon:glacium_alcove_georgian", "span",
                Set.of("left", "right", "single"));
        assertPropertyValues(palette, "erydon:glacium_surround_georgian", "section", Set.of(
                "corbel_mantel_stub_lh", "corbel_mantel_stub_rh", "empty", "hearth",
                "mantel", "plinth_hearth_stub_lh", "plinth_hearth_stub_rh", "shaft"));
        assertPropertyValues(palette, "erydon:glacium_window_french_georgian", "piece", Set.of(
                "lower_multi_lh", "lower_multi_mid", "lower_multi_rh",
                "upper_multi_lh", "upper_multi_mid", "upper_multi_rh"));
        assertPropertyValues(palette, "erydon:glacium_ceiling_coffered_georgian_white_small", "unused",
                Set.of("u00", "u01", "u04", "u08", "u09", "u0c"));
        assertPropertyValues(palette, "erydon:glacium_ceiling_coffered_georgian_black_small", "unused",
                Set.of("u00", "u01", "u02", "u03", "u04", "u06"));
        assertPropertyValues(palette, "minecraft:oak_sign", "rotation", Set.of("8"));
        assertPropertyValues(palette, "minecraft:oak_sign", "waterlogged", Set.of("false"));

        for (String diagonal : Set.of("north_east", "north_west", "south_east", "south_west")) {
            assertPropertyValues(palette, "erydon:glacium_wall_georgian", diagonal,
                    Set.of("false", "true"));
        }

        System.out.println("Georgian showcase structure verification passed: size=47x7x20 blocks=1561 states=57 signs=7");
    }

    private static void verifySize(NbtList size) {
        require(size.size() == EXPECTED_SIZE.length, "Structure size must contain three values");
        for (int axis = 0; axis < EXPECTED_SIZE.length; axis++) {
            require(size.getInt(axis) == EXPECTED_SIZE[axis],
                    "Unexpected structure size at axis " + axis + ": " + size.getInt(axis));
        }
    }

    private static void assertPropertyValues(NbtList palette,
                                             String blockName,
                                             String propertyName,
                                             Set<String> expected) {
        Set<String> actual = new HashSet<>();
        for (int index = 0; index < palette.size(); index++) {
            NbtCompound state = palette.getCompound(index);
            if (state.getString("Name").equals(blockName)) {
                NbtCompound properties = state.getCompound("Properties");
                if (properties.contains(propertyName, NbtElement.STRING_TYPE)) {
                    actual.add(properties.getString(propertyName));
                }
            }
        }
        require(actual.equals(expected),
                blockName + " property " + propertyName + " expected " + expected + " but found " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
