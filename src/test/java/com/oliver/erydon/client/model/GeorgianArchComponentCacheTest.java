package com.oliver.erydon.client.model;

import com.oliver.erydon.block.ArchRomanesqueBlock;
import com.oliver.erydon.block.GeorgianArchLayout;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GeorgianArchComponentCacheTest {
    @Test void allWidthsUseTheirAuthoredCrownsAndFixedBodyDetails() {
        var referenced = new HashSet<String>();
        for (int width = 1; width <= 6; width++) {
            for (var arrangement : ArchRomanesqueBlock.Arrangement.values()) {
                var selected = GeorgianArchLayout.components(arrangement, width);
                referenced.addAll(selected);
                assertTrue(GeorgianArchLayout.modelSuffixes().containsAll(selected));
                assertSame(selected, GeorgianArchLayout.components(arrangement, width));
                assertEquals(selected, GeorgianArchLayout.components(arrangement, width));
            }
        }
        assertEquals(56, GeorgianArchLayout.modelSuffixes().size());
        assertEquals(55, referenced.size()); // Icon is selected only for inventory.
        assertEquals(List.of("base_left", "base_right"),
                GeorgianArchLayout.components(ArchRomanesqueBlock.Arrangement.SMALL_BASE, 1));
        assertEquals(List.of("width6_short_top_column0"),
                GeorgianArchLayout.components(ArchRomanesqueBlock.Arrangement.WIDE_H1_Y0_L0, 6));
        assertEquals(List.of("width6_top_row1_column5"),
                GeorgianArchLayout.components(ArchRomanesqueBlock.Arrangement.WIDE_H2_Y1_R0, 6));
        assertTrue(GeorgianArchLayout.components(ArchRomanesqueBlock.Arrangement.TRIPLE_VOID, 6).isEmpty());
    }

    @Test void resolvingChildrenDoesNotCacheRandomDrivenQuadResults() {
        var resolutions = new AtomicInteger();
        var calls = new AtomicInteger();
        var child = model(calls);
        var cache = new ArchGeorgianBakedModel.ComponentModels("aganite_aged_arch_georgian", id -> {
            assertTrue(id.getPath().startsWith("block/arch/georgian/aganite_aged_arch_georgian_"));
            resolutions.incrementAndGet(); return child;
        });
        assertEquals(56, resolutions.get());
        assertEquals(0, calls.get());
        for (int pass = 0; pass < 100; pass++) {
            assertSame(cache.forState(ArchRomanesqueBlock.Arrangement.SMALL_TOP, 1),
                    cache.forState(ArchRomanesqueBlock.Arrangement.SMALL_TOP, 1));
        }
        cache.forState(ArchRomanesqueBlock.Arrangement.SMALL_TOP, 1).get(0).getQuads(null, null, Random.create(1));
        cache.forState(ArchRomanesqueBlock.Arrangement.SMALL_TOP, 1).get(0).getQuads(null, null, Random.create(2));
        assertEquals(56, resolutions.get()); assertEquals(2, calls.get());
        var replacement = model(new AtomicInteger());
        var reloaded = new ArchGeorgianBakedModel.ComponentModels("aganite_aged_arch_georgian", id -> replacement);
        assertSame(child, cache.forState(ArchRomanesqueBlock.Arrangement.SMALL_TOP, 1).get(0));
        assertSame(replacement, reloaded.forState(ArchRomanesqueBlock.Arrangement.SMALL_TOP, 1).get(0));
    }

    private static BakedModel model(AtomicInteger calls) {
        return new BakedModel() {
            @Override public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
                calls.incrementAndGet();
                return List.of();
            }
            @Override public boolean useAmbientOcclusion() { return false; }
            @Override public boolean hasDepth() { return false; }
            @Override public boolean isSideLit() { return false; }
            @Override public boolean isBuiltin() { return false; }
            @Override public Sprite getParticleSprite() { return null; }
            @Override public ModelTransformation getTransformation() { return null; }
            @Override public ModelOverrideList getOverrides() { return null; }
        };
    }
}
