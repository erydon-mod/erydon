package com.oliver.erydon.client.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ReloadSpriteHandlesTest {
    @Test
    void concurrentFirstUseSharesRepeatAndOverlaySequencesByRuleIdentity() throws Exception {
        Object firstRepeat = new String("repeat");
        Object secondRepeat = new String("repeat");
        Object overlay = new Object();
        List<String> repeatTiles = tiles(36);
        List<String> overlayTiles = tiles(47);
        AtomicInteger repeatLoads = new AtomicInteger();
        AtomicInteger overlayLoads = new AtomicInteger();
        List<String> repeatSprites = List.copyOf(repeatTiles);
        List<String> overlaySprites = List.copyOf(overlayTiles);
        var table = new ReloadSpriteHandles.Builder<Object, List<String>>()
                .bind(firstRepeat, repeatTiles, () -> {
                    repeatLoads.incrementAndGet();
                    return repeatSprites;
                })
                .bind(secondRepeat, new ArrayList<>(repeatTiles), () -> {
                    throw new AssertionError("Equal tile sequences must share a resolver");
                })
                .bind(overlay, overlayTiles, () -> {
                    overlayLoads.incrementAndGet();
                    return overlaySprites;
                })
                .build();
        assertEquals(0, repeatLoads.get(), "Sprite loading must wait until first use");

        var workers = Executors.newFixedThreadPool(8);
        try {
            List<Callable<List<String>>> requests = new ArrayList<>();
            for (int index = 0; index < 160; index++) {
                Object rule = index % 3 == 0 ? overlay : index % 2 == 0 ? firstRepeat : secondRepeat;
                requests.add(() -> table.get(rule));
            }
            var results = workers.invokeAll(requests);
            for (int index = 0; index < results.size(); index++) {
                List<String> actual = results.get(index).get(20, TimeUnit.SECONDS);
                assertSame(index % 3 == 0 ? overlaySprites : repeatSprites, actual);
            }
        } finally {
            workers.shutdownNow();
        }
        assertEquals(1, repeatLoads.get());
        assertEquals(1, overlayLoads.get());
        assertThrows(IllegalArgumentException.class, () -> table.get(new String("repeat")));
    }

    @Test
    void failedLoadRetriesAndEachReloadUsesNewSprites() {
        Object rule = new Object();
        AtomicInteger attempts = new AtomicInteger();
        Object firstAtlas = new Object();
        var first = new ReloadSpriteHandles.Builder<Object, Object>()
                .bind(rule, tiles(36), () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("Atlas not ready");
                    }
                    return firstAtlas;
                }).build();
        assertThrows(IllegalStateException.class, () -> first.get(rule));
        assertSame(firstAtlas, first.get(rule));
        assertEquals(2, attempts.get());

        for (int reload = 0; reload < 3; reload++) {
            Object newAtlas = new Object();
            var next = new ReloadSpriteHandles.Builder<Object, Object>()
                    .bind(rule, tiles(36), () -> newAtlas).build();
            assertSame(newAtlas, next.get(rule));
            assertNotSame(first.get(rule), next.get(rule));
        }
        assertThrows(IllegalArgumentException.class, () -> new ReloadSpriteHandles.Builder<Object, Object>()
                .bind(rule, tiles(36), Object::new).bind(rule, tiles(36), Object::new));
    }

    private static List<String> tiles(int count) {
        return IntStream.range(0, count).mapToObj(Integer::toString).toList();
    }
}
