package com.oliver.erydon.client.model;

import com.oliver.erydon.block.PitchedSlopeCornerLaunchProbe;
import com.oliver.erydon.block.ShallowSlopeBlock;
import com.oliver.erydon.block.SlopeBlock;
import com.oliver.erydon.block.SlopeSteepBlock;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/** Runs slope-only checks without loading a world or another task's fixtures. */
public final class SlopeCornerReviewLaunchProbe implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        try {
            SharedConstants.createGameVersion();
            Bootstrap.initialize();
            PitchedSlopeCornerLaunchProbe.run();
            fixture("glacium_slope", new SlopeBlock(Block.Settings.copy(Blocks.STONE)));
            fixture("glacium_slope_shallow_lower", new ShallowSlopeBlock(Block.Settings.copy(Blocks.STONE), ShallowSlopeBlock.Variant.LOWER));
            fixture("glacium_slope_shallow_upper", new ShallowSlopeBlock(Block.Settings.copy(Blocks.STONE), ShallowSlopeBlock.Variant.UPPER));
            fixture("glacium_slope_steep_lower", new SlopeSteepBlock(Block.Settings.copy(Blocks.STONE), SlopeSteepBlock.Variant.LOWER));
            fixture("glacium_slope_steep_upper", new SlopeSteepBlock(Block.Settings.copy(Blocks.STONE), SlopeSteepBlock.Variant.UPPER));
            SlopeShapeLaunchChecks.run();
            VerticalSlopeGeometryLaunchChecks.run();
            System.out.println("ERYDON_SLOPE_CORNER_REVIEW_OK");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void fixture(String path, Block block) {
        Registry.register(Registries.BLOCK, new Identifier("erydon", path), block);
        block.getStateManager().getStates().forEach(BlockState::initShapeCache);
    }
}
