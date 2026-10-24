package com.oliver.erydon.client.model;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.oliver.erydon.block.CorniceConnections;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.util.math.Direction;

import java.util.List;

/** One reload-scoped geometry budget shared by every cornice material and saved pose. */
final class CorniceFittedCache {
    static final int MAX_ESTIMATED_BYTES = 32 * 1024 * 1024;
    private record Key(String blockPath, List<CorniceConnections.Segment> segments, Direction outerFacing) { }
    private final Cache<Key, List<BakedQuad>> quads = CacheBuilder.newBuilder()
            .maximumWeight(MAX_ESTIMATED_BYTES)
            .weigher((Key key, List<BakedQuad> value) -> estimatedBytes(value)).build();

    List<BakedQuad> get(String blockPath, List<CorniceConnections.Segment> segments) {
        return quads.getIfPresent(new Key(blockPath, segments, null));
    }

    void put(String blockPath, List<CorniceConnections.Segment> segments, List<BakedQuad> value) {
        quads.put(new Key(blockPath, segments, null), value);
    }

    List<BakedQuad> getOuter(String blockPath, Direction facing) {
        return quads.getIfPresent(new Key(blockPath, List.of(), facing));
    }

    void putOuter(String blockPath, Direction facing, List<BakedQuad> value) {
        quads.put(new Key(blockPath, List.of(), facing), value);
    }

    // Includes arrays, quad objects, list references and a conservative key allowance.
    static int estimatedBytes(List<BakedQuad> value) {
        return 256 + value.size() * 224;
    }
}
