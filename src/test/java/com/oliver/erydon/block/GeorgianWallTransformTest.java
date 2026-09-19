package com.oliver.erydon.block;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the production mappings without bootstrapping Minecraft in the plain JUnit runner. */
class GeorgianWallTransformTest {
    private static final String[] NAMES = {"NORTH_EAST", "SOUTH_EAST", "SOUTH_WEST", "NORTH_WEST"};
    private static final int[][] CORNERS = {{1, -1}, {1, 1}, {-1, 1}, {-1, -1}};
    private static final Path SOURCE = Path.of("src/main/java/com/oliver/erydon/block/DiagonalWallBlock.java");

    @Test
    void everyDiagonalCombinationFollowsCoordinateRotationAndMirroring() throws IOException {
        String source = Files.readString(SOURCE);
        for (String operation : new String[]{"CLOCKWISE_90", "CLOCKWISE_180", "COUNTERCLOCKWISE_90",
                "LEFT_RIGHT", "FRONT_BACK"}) {
            int[] mapping = mapping(source, operation);
            for (int mask = 0; mask < 16; mask++) {
                int expected = 0;
                for (int i = 0; i < 4; i++) {
                    if ((mask & (1 << i)) != 0) expected |= 1 << transformedCorner(i, operation);
                }
                assertEquals(expected, apply(mask, mapping), operation + " mask=" + mask);
                int restored = mask;
                int repetitions = operation.endsWith("90") ? 4 : 2;
                for (int i = 0; i < repetitions; i++) restored = apply(restored, mapping);
                assertEquals(mask, restored, "Transform must round-trip: " + operation);
            }
        }
    }

    @Test
    void transformsOnlyDelegateVanillaConnectionsAndPermuteDiagonalProperties() throws IOException {
        String source = Files.readString(SOURCE);
        for (String method : new String[]{"rotate", "mirror"}) {
            int start = source.indexOf("public BlockState " + method + "(");
            int end = source.indexOf("\n    @Override", start);
            assertTrue(start >= 0 && end > start);
            String body = source.substring(source.indexOf('{', start) + 1, end).trim();
            // A strict grammar guards against adding world reads, shape work, allocations,
            // extra property writes, or helper calls to this action-only code.
            String argument = method.equals("rotate") ? "rotation" : "mirror";
            String result = method.equals("rotate") ? "rotated" : "mirrored";
            String remainder = body.replace("BlockState " + result + " = super." + method
                    + "(state, " + argument + ");", "")
                    .replace("return switch (" + argument + ")", "")
                    .replaceAll("case [A-Z_0-9]+ -> " + result, "")
                    .replaceAll("\\.with\\((NORTH_EAST|SOUTH_EAST|SOUTH_WEST|NORTH_WEST), state\\.get\\((NORTH_EAST|SOUTH_EAST|SOUTH_WEST|NORTH_WEST)\\)\\)", "")
                    .replaceAll("[\\s{};]", "");
            assertEquals("", remainder);
            assertTrue(body.contains("super." + method + "(state, " + argument + ")"));
            assertTrue(body.contains("case NONE -> " + result + ";"));
        }
    }

    private static int[] mapping(String source, String operation) {
        var branch = Pattern.compile("case " + operation + " -> [a-z]+(.*?);", Pattern.DOTALL).matcher(source);
        assertTrue(branch.find(), "Missing transform " + operation);
        var assignments = Pattern.compile("\\.with\\(([A-Z_]+), state\\.get\\(([A-Z_]+)\\)\\)").matcher(branch.group(1));
        Map<String, String> pairs = new HashMap<>();
        while (assignments.find()) pairs.put(assignments.group(1), assignments.group(2));
        assertEquals(4, pairs.size());
        int[] mapping = new int[4];
        for (int target = 0; target < 4; target++) {
            mapping[target] = -1;
            for (int origin = 0; origin < 4; origin++) {
                if (NAMES[origin].equals(pairs.get(NAMES[target]))) mapping[target] = origin;
            }
            assertTrue(mapping[target] >= 0);
        }
        return mapping;
    }

    private static int apply(int mask, int[] mapping) {
        int result = 0;
        for (int i = 0; i < 4; i++) if ((mask & (1 << mapping[i])) != 0) result |= 1 << i;
        return result;
    }

    private static int transformedCorner(int index, String operation) {
        int x = CORNERS[index][0];
        int z = CORNERS[index][1];
        int turns = switch (operation) {
            case "CLOCKWISE_90" -> 1;
            case "CLOCKWISE_180" -> 2;
            case "COUNTERCLOCKWISE_90" -> 3;
            default -> 0;
        };
        for (int turn = 0; turn < turns; turn++) {
            int oldX = x;
            x = -z;
            z = oldX;
        }
        if (operation.equals("LEFT_RIGHT")) z = -z;
        if (operation.equals("FRONT_BACK")) x = -x;
        for (int i = 0; i < CORNERS.length; i++) {
            if (CORNERS[i][0] == x && CORNERS[i][1] == z) return i;
        }
        throw new AssertionError("Unmapped corner");
    }
}
