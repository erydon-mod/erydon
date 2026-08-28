package com.oliver.erydon.client.model;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynapheiaBlockPlanTest {
    @Test
    void compiledPlansMatchTheOrderedSnapshotLookups() {
        Identifier block = id("erydon", "aganite_aged_block");
        Identifier matchedSource = id("erydon", "block/aganite_aged_block");
        Identifier genericSource = id("erydon", "block/aganite_aged_trim");
        SynapheiaManifest.Rule matchedRepeat = rule(
                "matched-repeat", SynapheiaManifest.Method.REPEAT, block,
                Set.of(Direction.UP), Set.of(matchedSource), 36);
        SynapheiaManifest.Rule broadRepeat = rule(
                "broad-repeat", SynapheiaManifest.Method.REPEAT, block,
                Set.of(Direction.values()), Set.of(), 36);
        SynapheiaManifest.Rule overlay = rule(
                "overlay", SynapheiaManifest.Method.OVERLAY_CTM, block,
                Set.of(Direction.UP), Set.of(matchedSource), 47);

        SynapheiaService.Snapshot snapshot = SynapheiaService.publish(
                new SynapheiaManifest.Prepared(
                        List.of(matchedRepeat, broadRepeat, overlay), "test", 1, 2, 1, 1L));
        SynapheiaBlockPlan plan = snapshot.planFor(block);

        assertNotNull(plan);
        assertTrue(plan.hasRepeat());
        assertTrue(plan.hasOverlay());
        assertSame(snapshot.repeatRuleFor(block, Direction.UP, matchedSource),
                plan.repeatRule(Direction.UP, matchedSource));
        assertSame(snapshot.repeatRuleFor(block, Direction.NORTH, genericSource),
                plan.repeatRule(Direction.NORTH, genericSource));
        assertNull(plan.repeatRule(Direction.UP, matchedRepeat.tiles().get(0)));
        assertEquals(snapshot.overlayRulesFor(block, Direction.UP, matchedSource),
                plan.overlayRules(Direction.UP, matchedSource));
        assertEquals(snapshot.overlayRulesFor(block, Direction.NORTH, matchedSource),
                plan.overlayRules(Direction.NORTH, matchedSource));
        assertSame(snapshot.repeatRuleForProjectedGeometry(block, Direction.UP),
                plan.repeatRuleForProjectedGeometry(Direction.UP));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.plansByBlock().put(id("minecraft", "stone"), plan));
    }

    @Test
    void singleRepeatPlanMatchesTheCommonDirectPath() {
        Identifier block = id("erydon", "aganite_block");
        Identifier source = id("erydon", "block/aganite_block");
        SynapheiaManifest.Rule repeat = rule(
                "single-repeat", SynapheiaManifest.Method.REPEAT, block,
                Set.of(Direction.values()), Set.of(), 36);
        SynapheiaService.Snapshot snapshot = SynapheiaService.publish(
                new SynapheiaManifest.Prepared(List.of(repeat), "test", 1, 1, 0, 1L));
        SynapheiaBlockPlan plan = snapshot.planFor(block);

        assertNotNull(plan);
        assertSame(snapshot.repeatRuleFor(block, Direction.SOUTH, source),
                plan.repeatRule(Direction.SOUTH, source));
        assertNull(plan.repeatRule(Direction.SOUTH, repeat.tiles().get(12)));
        assertFalse(plan.hasOverlay());
    }

    private static SynapheiaManifest.Rule rule(String name,
                                                SynapheiaManifest.Method method,
                                                Identifier block,
                                                Set<Direction> faces,
                                                Set<Identifier> matchTiles,
                                                int tileCount) {
        List<Identifier> tiles = IntStream.range(0, tileCount)
                .mapToObj(index -> id("minecraft", "optifine/ctm/" + name + "/" + index))
                .toList();
        return new SynapheiaManifest.Rule(
                id("minecraft", "optifine/ctm/" + name + "/rule.properties"),
                "test", method, tiles, faces, Set.of(block), matchTiles, true, 10);
    }

    private static Identifier id(String namespace, String path) {
        return new Identifier(namespace, path);
    }
}
