package com.oliver.erydon.block;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeorgianWallChunkLoadingSafetyTest {
    @Test
    void dynamicShapeResolutionStaysOffWorkersAndUsesOnlyNonBlockingChunkLookups() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/oliver/erydon/block/GeorgianWallSlopeResolver.java"
        ));

        int workerGuard = source.indexOf("!serverWorld.getServer().isOnThread()");
        int firstDynamicRead = source.indexOf("Part supportPart = partForSupport");
        assertTrue(workerGuard >= 0, "Server-world resolution must be rejected off the server thread.");
        assertTrue(
                workerGuard < firstDynamicRead,
                "The worker guard must run before any neighbouring state or shape is queried."
        );
        assertTrue(source.contains("serverWorld.getChunkManager().getWorldChunk("));
        assertTrue(source.contains("chunk.getBlockState(pos)"));
        assertTrue(source.contains("Blocks.VOID_AIR.getDefaultState()"));
        int airGuard = source.indexOf("if (support.isAir())");
        int outlineShapeRead = source.indexOf("support.getOutlineShape(");
        assertTrue(airGuard >= 0 && airGuard < outlineShapeRead,
                "Unavailable support must stop before third-party shape hooks can query the world.");
        assertFalse(
                source.contains("isChunkLoaded(pos)"),
                "The broad loaded check can be true before a full chunk is available and must not guard this read."
        );
        assertEquals(
                1,
                occurrences(source, "world.getBlockState("),
                "All dynamic wall-shape reads must go through loadedState so chunk lighting cannot self-deadlock."
        );
    }

    private static int occurrences(String source, String target) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(target, offset)) >= 0) {
            count++;
            offset += target.length();
        }
        return count;
    }
}
