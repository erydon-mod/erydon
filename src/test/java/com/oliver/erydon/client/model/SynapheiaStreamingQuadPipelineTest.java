package com.oliver.erydon.client.model;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynapheiaStreamingQuadPipelineTest {
    @Test
    void unmatchedQuadStreamsWithoutCaptureOrReplay() {
        SynapheiaRepeatBakedModel.RepeatDisposition disposition =
                SynapheiaRepeatBakedModel.repeatDisposition(null, null);

        assertEquals(SynapheiaRepeatBakedModel.RepeatDisposition.STREAM_UNCHANGED, disposition);
        assertTrue(disposition.streamsOriginal());
    }

    @Test
    void singleCellRepeatStreamsAfterInPlaceMutation() {
        SynapheiaRepeatBakedModel.RepeatDisposition disposition =
                SynapheiaRepeatBakedModel.repeatDisposition(
                        rule("repeat", SynapheiaManifest.Method.REPEAT, 36),
                        new SynapheiaCellGeometry.Cell(0, 0));

        assertEquals(SynapheiaRepeatBakedModel.RepeatDisposition.STREAM_SINGLE_CELL, disposition);
        assertTrue(disposition.streamsOriginal());
    }

    @Test
    void crossCellRepeatIsTheOnlySuppressedPath() {
        SynapheiaRepeatBakedModel.RepeatDisposition disposition =
                SynapheiaRepeatBakedModel.repeatDisposition(
                        rule("repeat", SynapheiaManifest.Method.REPEAT, 36), null);

        assertEquals(SynapheiaRepeatBakedModel.RepeatDisposition.CAPTURE_CROSS_CELL, disposition);
        assertFalse(disposition.streamsOriginal());
    }

    @Test
    void overlayBookkeepingExistsOnlyAfterAnOverlayCapablePlanMatches() {
        SynapheiaManifest.Rule overlay =
                rule("overlay", SynapheiaManifest.Method.OVERLAY_CTM, 47);
        SynapheiaRepeatBakedModel.RenderCallState repeatOnly =
                new SynapheiaRepeatBakedModel.RenderCallState(false, false);
        repeatOnly.observeOverlays(Direction.UP, List.of(overlay));
        assertFalse(repeatOnly.hasOverlayBookkeeping());

        SynapheiaRepeatBakedModel.RenderCallState overlayCapable =
                new SynapheiaRepeatBakedModel.RenderCallState(true, false);
        assertFalse(overlayCapable.hasOverlayBookkeeping());
        overlayCapable.observeOverlays(Direction.UP, List.of(overlay, overlay));
        assertTrue(overlayCapable.hasOverlayBookkeeping());
        assertEquals(1, overlayCapable.overlayCount());
    }

    private static SynapheiaManifest.Rule rule(String name,
                                                SynapheiaManifest.Method method,
                                                int tileCount) {
        Identifier block = new Identifier("erydon", "aganite_block");
        List<Identifier> tiles = IntStream.range(0, tileCount)
                .mapToObj(index -> new Identifier(
                        "minecraft", "optifine/ctm/" + name + "/" + index))
                .toList();
        return new SynapheiaManifest.Rule(
                new Identifier("minecraft", "optifine/ctm/" + name + "/rule.properties"),
                "test", method, tiles, Set.of(Direction.values()), Set.of(block),
                Set.of(), true, 10);
    }
}
