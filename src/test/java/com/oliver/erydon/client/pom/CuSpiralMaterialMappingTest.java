package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuSpiralMaterialMappingTest {
    private static final Path BLOCKSTATES =
            Path.of("src/main/resources/assets/erydon/blockstates");
    private static final Path BLOCK_PROPERTIES =
            Path.of("src/main/resources/assets/erydon/shaders/block.properties");

    @Test
    void reservedMaterialContainsExactlyEveryLiveLargeSpiralBlock() throws IOException {
        Set<String> expected = new HashSet<>();
        try (var paths = Files.list(BLOCKSTATES)) {
            paths.filter(path -> path.getFileName().toString().endsWith("_stairs_spiral_large.json"))
                    .map(path -> path.getFileName().toString())
                    .map(name -> "erydon:" + name.substring(0, name.length() - ".json".length()))
                    .forEach(expected::add);
        }

        List<String> matchingLines = Files.readAllLines(BLOCK_PROPERTIES).stream()
                .filter(line -> line.startsWith("block."
                        + ComplementaryUnboundDev5SourceTransformer.SPIRAL_MATERIAL_ID + "="))
                .toList();
        assertEquals(1, matchingLines.size());
        String values = matchingLines.get(0).substring(matchingLines.get(0).indexOf('=') + 1).trim();
        Set<String> actual = Set.of(values.split("\\s+"));

        assertEquals(81, expected.size());
        assertEquals(expected, actual);
        assertTrue(actual.stream().noneMatch(token -> token.contains(":" + "waterlogged=")));
    }

    @Test
    void customMaterialNumbersAreUnique() throws IOException {
        Set<String> seen = new HashSet<>();
        for (String line : Files.readAllLines(BLOCK_PROPERTIES)) {
            if (!line.startsWith("block.")) {
                continue;
            }
            String key = line.substring(0, line.indexOf('='));
            assertTrue(seen.add(key), () -> "Duplicate custom shader material " + key);
        }
    }
}
