package com.oliver.erydon.client.model;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SynapheiaTileSequencePoolTest {
    @Test
    void equalOrderedSequencesShareOneImmutableList() {
        Identifier first = id("first");
        Identifier second = id("second");
        List<Identifier> mutable = new ArrayList<>(List.of(first, second));
        SynapheiaTileSequencePool pool = new SynapheiaTileSequencePool();

        List<Identifier> canonical = pool.intern(mutable);
        mutable.clear();
        List<Identifier> equal = pool.intern(List.of(first, second));
        List<Identifier> reversed = pool.intern(List.of(second, first));

        assertSame(canonical, equal);
        assertNotSame(canonical, reversed);
        assertEquals(2, pool.size());
        assertThrows(UnsupportedOperationException.class, () -> canonical.add(first));
        assertSame(canonical, new SynapheiaService.SpriteKey(4L, canonical).tiles());
    }

    @Test
    void spriteCacheIdentityIncludesGenerationAndTileOrder() {
        Identifier first = id("first");
        Identifier second = id("second");
        SynapheiaService.SpriteKey key = new SynapheiaService.SpriteKey(
                4L, List.of(first, second));

        assertEquals(key, new SynapheiaService.SpriteKey(
                4L, new ArrayList<>(List.of(first, second))));
        assertNotEquals(key, new SynapheiaService.SpriteKey(
                5L, List.of(first, second)));
        assertNotEquals(key, new SynapheiaService.SpriteKey(
                4L, List.of(second, first)));
    }

    private static Identifier id(String path) {
        return new Identifier("minecraft", "optifine/ctm/test/" + path);
    }
}
