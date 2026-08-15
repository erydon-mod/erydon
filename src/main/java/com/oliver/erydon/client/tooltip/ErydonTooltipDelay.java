package com.oliver.erydon.client.tooltip;

import com.oliver.erydon.ErydonConfig;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.util.List;

public final class ErydonTooltipDelay {
    private static final long HOVER_CONTINUITY_MS = 250L;

    private static Identifier lastHoveredItemId;
    private static long lastHoverTimeMs;
    private static long hoverStartTimeMs;

    private ErydonTooltipDelay() {
    }

    public static List<Text> getTooltipLines(ItemStack stack) {
        if (!ErydonConfig.tooltipsEnabled()) {
            return List.of();
        }
        updateHoverState(stack);
        if (!hasDelayElapsed()) {
            return List.of();
        }
        return ErydonTooltipRegistry.getTooltipLines(stack);
    }

    private static void updateHoverState(ItemStack stack) {
        Identifier itemId = Registries.ITEM.getId(stack.getItem());
        if (itemId == null) {
            return;
        }

        long now = Util.getMeasuringTimeMs();
        boolean hoverExpired = now - lastHoverTimeMs > HOVER_CONTINUITY_MS;
        if (!itemId.equals(lastHoveredItemId) || hoverExpired) {
            lastHoveredItemId = itemId;
            hoverStartTimeMs = now;
        }
        lastHoverTimeMs = now;
    }

    private static boolean hasDelayElapsed() {
        return Util.getMeasuringTimeMs() - hoverStartTimeMs >= ErydonConfig.tooltipDelayMs();
    }
}
