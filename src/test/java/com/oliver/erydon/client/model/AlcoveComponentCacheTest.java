package com.oliver.erydon.client.model;

import com.oliver.erydon.block.AlcoveBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AlcoveComponentCacheTest {
    @Test
    void georgianChildrenResolveOnceAndEveryStateReusesTheActualPackModels() {
        Map<Identifier, BakedModel> models = new HashMap<>();
        AtomicInteger resolutions = new AtomicInteger();
        var cache = new AlcoveBakedModel.ComponentModels("aganite_alcove_georgian", id -> {
            resolutions.incrementAndGet();
            return models.computeIfAbsent(id, key -> model(new AtomicInteger()));
        });
        assertEquals(26, resolutions.get());
        for (int pass = 0; pass < 10; pass++) {
            for (var part : AlcoveBlock.AlcovePart.values()) {
                for (var span : AlcoveBlock.AlcoveSpan.values()) {
                    List<BakedModel> selected = cache.forState(part, span);
                    assertSame(selected, cache.forState(part, span));
                    List<String> suffixes = AlcoveBakedModel.componentSuffixes(part, span, true);
                    assertEquals(suffixes.size(), selected.size());
                    for (int index = 0; index < selected.size(); index++) {
                        assertSame(models.get(AlcoveBakedModel.modelId("aganite_alcove_georgian", suffixes.get(index))),
                                selected.get(index));
                    }
                }
            }
        }
        assertEquals(26, resolutions.get(), "Rendering must not resolve child IDs repeatedly");
    }

    @Test
    void newReloadCacheUsesNewPackModelsWithoutRetainingThePreviousSelection() {
        BakedModel previous = model(new AtomicInteger());
        BakedModel replacement = model(new AtomicInteger());
        var before = new AlcoveBakedModel.ComponentModels("aganite_alcove_georgian", id -> previous);
        var after = new AlcoveBakedModel.ComponentModels("aganite_alcove_georgian", id -> replacement);
        var part = AlcoveBlock.AlcovePart.TOP;
        var span = AlcoveBlock.AlcoveSpan.TRIPLE_LEFT;
        assertNotSame(before.forState(part, span), after.forState(part, span));
        assertSame(previous, before.forState(part, span).get(0));
        assertSame(replacement, after.forState(part, span).get(0));
    }

    @Test
    void modelCacheDoesNotEvaluateOrCacheRandomDrivenQuads() {
        AtomicInteger quadCalls = new AtomicInteger();
        BakedModel child = model(quadCalls);
        var cache = new AlcoveBakedModel.ComponentModels("aganite_alcove_georgian", id -> child);
        assertEquals(0, quadCalls.get());
        BakedModel selected = cache.forState(AlcoveBlock.AlcovePart.TOP, AlcoveBlock.AlcoveSpan.SINGLE).get(0);
        selected.getQuads(null, null, Random.create(1));
        selected.getQuads(null, null, Random.create(2));
        assertEquals(2, quadCalls.get(), "Quad generation remains delegated on each call");
    }

    @Test
    void modernAndGothicResolveOnlyTheirExistingChildren() {
        for (String style : List.of("modern", "gothic")) {
            AtomicInteger resolutions = new AtomicInteger();
            BakedModel child = model(new AtomicInteger());
            var cache = new AlcoveBakedModel.ComponentModels("aganite_alcove_" + style, id -> {
                resolutions.incrementAndGet();
                return child;
            });
            assertEquals(14, resolutions.get());
            assertEquals(3, cache.forState(AlcoveBlock.AlcovePart.TOP, AlcoveBlock.AlcoveSpan.SINGLE).size());
            assertEquals(2, cache.forState(AlcoveBlock.AlcovePart.MIDDLE, AlcoveBlock.AlcoveSpan.SINGLE).size());
            assertEquals(14, resolutions.get());
        }
    }

    @Test
    void missingChildrenRemainSkippedAndResolvedSelectionsAreImmutable() {
        BakedModel child = model(new AtomicInteger());
        var cache = new AlcoveBakedModel.ComponentModels("aganite_alcove_georgian", id ->
                id.getPath().endsWith("_base_detail_left") ? null : child);
        var selected = cache.forState(AlcoveBlock.AlcovePart.BASE, AlcoveBlock.AlcoveSpan.SINGLE);
        assertEquals(3, selected.size());
        assertThrows(UnsupportedOperationException.class, () -> selected.add(child));
    }

    private static BakedModel model(AtomicInteger quadCalls) {
        // Java Proxy initializes all method parameter classes, including
        // BlockState's live registries. A concrete stub keeps this a unit test.
        return new BakedModel() {
            @Override
            public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
                quadCalls.incrementAndGet();
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
