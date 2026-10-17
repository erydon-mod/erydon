package com.oliver.erydon.block;

import com.oliver.erydon.ErydonConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

/** Complete saved pendant-state matrix, including cached Minecraft luminance. */
public final class PendantLightingLaunchChecks {
    private PendantLightingLaunchChecks() { }

    public static void run() {
        var snapshot = ErydonConfig.serverSettings();
        boolean mirrored = ErydonConfig.hasServerSettingsMirror();
        boolean editable = ErydonConfig.canEditServerSettings();
        int checked = 0;
        try {
            for (int level : new int[]{0, 1, 7, 15}) {
                ErydonConfig.installServerSettingsMirror(snapshot.withPendantLightLevel(level), false);
                // Construct after selecting the level, as Minecraft caches luminance in each state.
                var pendant = new LightPendantBlock(Block.Settings.copy(Blocks.STONE).nonOpaque()
                        .luminance(LightPendantBlock::luminance));
                var halo = new LightPendantHaloBlock(Block.Settings.copy(Blocks.LIGHT)
                        .luminance(LightPendantHaloBlock::luminance));
                for (BlockState state : pendant.getStateManager().getStates()) {
                    var part = state.get(LightPendantBlock.PART);
                    boolean body = part == LightPendantBlock.Part.SINGLE || part == LightPendantBlock.Part.BOTTOM;
                    int expected = body && state.get(LightPendantBlock.LIT) ? level : 0;
                    require(LightPendantBlock.luminance(state) == expected, "Wrong pendant callback: " + state);
                    require(state.getLuminance() == expected, "Wrong cached pendant emission: " + state);
                    checked++;
                }
                for (BlockState state : halo.getStateManager().getStates()) {
                    int expected = state.get(LightPendantHaloBlock.LIT) ? level : 0;
                    require(state.getLuminance() == expected, "Large body halo changed: " + state);
                }
            }
        } finally {
            if (mirrored) ErydonConfig.installServerSettingsMirror(snapshot, editable);
            else ErydonConfig.clearServerSettingsMirror();
        }
        System.out.println("ERYDON_PENDANT_LIGHTING_OK: " + checked
                + " frame/body/part/lit/level states; TOP and ROD emit zero; SINGLE/BOTTOM and body halo preserve configured light");
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
