package com.oliver.erydon.client.model;

import com.oliver.erydon.block.GlazingShallowSlopeBlock;
import com.oliver.erydon.block.GlazingSlopeBlock;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;

import java.util.function.Supplier;

/** Exercises only the current glazing families, without opening a game window or a saved world. */
public final class GlazingSlopeReviewLaunchProbe implements PreLaunchEntrypoint {
    @Override public void onPreLaunch() {
        try {
            SharedConstants.createGameVersion();
            Bootstrap.initialize();
            runChecks();
            System.out.println("ERYDON_GLAZING_SLOPE_REVIEW_OK");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    /** Also runs inside the ordinary check task, after its existing vanilla bootstrap. */
    public static void runChecks() throws Exception {
        for (String finish : new String[]{"tinted", "silver", "crystal", "bronze"}) {
            fixture("glazing_framed_" + finish + "_slope", GlazingSlopeBlock.class, BlockHalf.BOTTOM,
                    () -> new GlazingSlopeBlock(AbstractBlock.Settings.copy(Blocks.GLASS)));
            for (var variant : GlazingShallowSlopeBlock.Variant.values()) {
                fixture("glazing_framed_" + finish + "_shallow_slope_" + variant.name().toLowerCase(java.util.Locale.ROOT),
                        GlazingShallowSlopeBlock.class, variant == GlazingShallowSlopeBlock.Variant.UPPER ? BlockHalf.TOP : BlockHalf.BOTTOM,
                        () -> new GlazingShallowSlopeBlock(AbstractBlock.Settings.copy(Blocks.GLASS), variant));
            }
        }
        com.oliver.erydon.migration.GlazingVerticalDiagonalMigrationLaunchChecks.run();
        GlazingSlopeMeshLaunchChecks.run();
    }

    private static void fixture(String path, Class<? extends Block> expectedType, BlockHalf expectedHalf,
                                Supplier<Block> factory) {
        Identifier id = new Identifier("erydon", path);
        Block block;
        if (Registries.BLOCK.containsId(id)) {
            block = Registries.BLOCK.get(id);
            if (!expectedType.isInstance(block) || block.getDefaultState().get(Properties.BLOCK_HALF) != expectedHalf) {
                throw new AssertionError("Canonical glazing fixture has the wrong block type/registered section: " + id);
            }
        } else {
            block = factory.get();
            Registry.register(Registries.BLOCK, id, block);
        }
        if (Registries.ITEM.containsId(id)) {
            if (!(Registries.ITEM.get(id) instanceof BlockItem item) || item.getBlock() != block) {
                throw new AssertionError("Canonical glazing fixture has an unrelated item: " + id);
            }
        } else {
            Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings()));
        }
    }
}
