package com.oliver.erydon.client.model;

import com.oliver.erydon.block.ArchRomanesqueBlock;
import com.oliver.erydon.block.WideArchLayout;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ArchComponentCacheTest {
    @Test
    void childrenResolveOnceWhileRandomDrivenQuadsRemainDelegated() {
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        BakedModel model = model(calls);
        var cache = new ArchRomanesqueBakedModel.ChildModels("aganite_arch_romanesque", id -> {
            resolutions.incrementAndGet();
            return model;
        });
        assertEquals(29, resolutions.get());
        assertEquals(0, calls.get());
        for (int pass = 0; pass < 100; pass++) {
            assertSame(model, cache.get("side_large_lower"));
            assertSame(model, cache.get("icon"));
            assertSame(model, cache.get("non_column_side_large_base"));
            assertSame(model, cache.get("non_column_corner_large_lower"));
        }
        assertEquals(29, resolutions.get());
        cache.get("corner_medium").getQuads(null, null, Random.create(1));
        cache.get("corner_medium").getQuads(null, null, Random.create(2));
        assertEquals(2, calls.get());
    }

    @Test
    void replacementCacheResolvesTheNewResourceGeneration() {
        BakedModel old = model(new AtomicInteger());
        BakedModel replacement = model(new AtomicInteger());
        var before = new ArchRomanesqueBakedModel.ChildModels("aganite_arch_romanesque", id -> old);
        var after = new ArchRomanesqueBakedModel.ChildModels("aganite_arch_romanesque", id -> replacement);
        assertSame(old, before.get("top_large"));
        assertSame(replacement, after.get("top_large"));
        assertNull(new ArchRomanesqueBakedModel.ChildModels(null, id -> replacement).get("top_large"));
    }

    @Test
    void otherStylesRetainTheirExistingChildSets() {
        for (String style : List.of("modern", "gothic")) {
            AtomicInteger count = new AtomicInteger();
            var cache = new ArchRomanesqueBakedModel.ChildModels("aganite_arch_" + style, id -> {
                count.incrementAndGet();
                return model(new AtomicInteger());
            });
            assertEquals(style.equals("modern") ? 10 : 9, count.get());
            assertNull(cache.get("side_large_lower"));
            assertNotNull(cache.get("side_large"));
            assertNull(cache.get("non_column_side_large_base"));
            assertNull(cache.get("non_column_corner_large_lower"));
        }
    }

    @Test
    void lowerCurveWallsUseTheirAuthoredVariantOnlyForRomanesque() {
        for (var corner : ArchRomanesqueBlock.Corner.values()) {
            for (var side : ArchRomanesqueBlock.Side.values()) {
                String suffix = ArchRomanesqueBakedModel.sideSuffix(side, corner, true);
                assertEquals(side == ArchRomanesqueBlock.Side.LARGE
                        && corner == ArchRomanesqueBlock.Corner.LARGE_LOWER,
                        "side_large_lower".equals(suffix));
                assertNotEquals("side_large_lower", ArchRomanesqueBakedModel.sideSuffix(side, corner, false));
            }
        }
    }

    @Test
    void capitalRowsKeepTheirOwnAuthoredColumnFacesOnlyForRomanesque() {
        for (var upper : ArchRomanesqueBlock.Upper.values()) {
            assertEquals(upper == ArchRomanesqueBlock.Upper.NONE ? "side_column" : "side_column_upper",
                    ArchRomanesqueBakedModel.columnSuffix(upper, true));
            assertEquals("side_column", ArchRomanesqueBakedModel.columnSuffix(upper, false));
        }
    }

    @Test
    void plainBodiesBasesAndCurvesUseTheirIndependentEditedProfiles() {
        var side = ArchRomanesqueBlock.Side.LARGE;
        assertEquals("non_column_side_small_crown", ArchRomanesqueBlock.Arrangement.SMALL_TOP
                .authoredSideSuffix(ArchRomanesqueBlock.Side.SMALL));
        assertEquals("non_column_side_medium_crown", ArchRomanesqueBlock.Arrangement.DOUBLE_TOP_L
                .authoredSideSuffix(ArchRomanesqueBlock.Side.MEDIUM));
        assertEquals("non_column_side_small", ArchRomanesqueBlock.Arrangement.SMALL_BODY
                .authoredSideSuffix(ArchRomanesqueBlock.Side.SMALL));
        assertEquals("non_column_side_medium", ArchRomanesqueBlock.Arrangement.DOUBLE_BODY_L
                .authoredSideSuffix(ArchRomanesqueBlock.Side.MEDIUM));
        assertEquals("side_medium", ArchRomanesqueBlock.Arrangement.DOUBLE_TOP_L_COLUMN
                .authoredSideSuffix(ArchRomanesqueBlock.Side.MEDIUM));
        assertEquals("non_column_side_large", ArchRomanesqueBlock.Arrangement.TRIPLE_BODY_L.authoredSideSuffix(side));
        assertEquals("non_column_side_large_base", ArchRomanesqueBlock.Arrangement.TRIPLE_BASE_R.authoredSideSuffix(side));
        assertEquals("non_column_side_large_lower", ArchRomanesqueBlock.Arrangement.TRIPLE_ROW2_L.authoredSideSuffix(side));
        assertEquals("non_column_side_large_crown", ArchRomanesqueBlock.Arrangement.TRIPLE_TOP_L.authoredSideSuffix(side));
        assertEquals("non_column_corner_large_lower", ArchRomanesqueBlock.Arrangement.TRIPLE_ROW2_L.authoredCornerSuffix());
        assertEquals("corner_large_lower", ArchRomanesqueBlock.Arrangement.TRIPLE_ROW2_L_COLUMN.authoredCornerSuffix());
        assertEquals("side_large_lower", ArchRomanesqueBlock.Arrangement.TRIPLE_ROW2_L_COLUMN.authoredSideSuffix(side));
        assertEquals("non_column_side_small_base", ArchRomanesqueBlock.Arrangement.SMALL_BODY_BASE
                .authoredSideSuffix(ArchRomanesqueBlock.Side.SMALL));
        assertEquals("non_column_side_medium_base", ArchRomanesqueBlock.Arrangement.DOUBLE_BASE_L
                .authoredSideSuffix(ArchRomanesqueBlock.Side.MEDIUM));
    }

    @Test
    void everyWideCrownOwnerRetainsItsColumnSourcesAndOriginalContinuation() {
        for (var arrangement : ArchRomanesqueBlock.Arrangement.values()) {
            if (!arrangement.isWide() || arrangement.wideBody() != null) continue;
            for (var component : WideArchLayout.components(arrangement)) {
                assertEquals(arrangement.isColumnStyle(), component.arrangement().isColumnStyle(),
                        arrangement + " lost its source style");
            }
        }
        for (var arrangement : List.of(
                ArchRomanesqueBlock.Arrangement.TRIPLE_COLUMN_CROWN_CONTINUATION_L,
                ArchRomanesqueBlock.Arrangement.TRIPLE_COLUMN_CROWN_CONTINUATION_R)) {
            assertEquals("side_large", arrangement.authoredSideSuffix(ArchRomanesqueBlock.Side.LARGE));
            assertNull(arrangement.authoredCornerSuffix());
        }
    }

    @Test
    void singleRowWideCrownsKeepAllThreeSourcesInTheirSelectedStyle() {
        assertEquals(List.of(
                        ArchRomanesqueBlock.Arrangement.TRIPLE_SINGLE_L,
                        ArchRomanesqueBlock.Arrangement.TOP_LARGE,
                        ArchRomanesqueBlock.Arrangement.TRIPLE_SINGLE_R),
                WideArchLayout.components(ArchRomanesqueBlock.Arrangement.WIDE_H1_Y0_C)
                        .stream().map(WideArchLayout.Component::arrangement).toList());
        assertEquals(List.of(
                        ArchRomanesqueBlock.Arrangement.TRIPLE_SINGLE_L_COLUMN,
                        ArchRomanesqueBlock.Arrangement.TOP_LARGE_COLUMN,
                        ArchRomanesqueBlock.Arrangement.TRIPLE_SINGLE_R_COLUMN),
                WideArchLayout.components(ArchRomanesqueBlock.Arrangement.WIDE_H1_Y0_C_COLUMN)
                        .stream().map(WideArchLayout.Component::arrangement).toList());
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
