package com.oliver.erydon.client.tooltip;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;

public final class ErydonTooltipClient {
    private ErydonTooltipClient() {
    }

    public static void init() {
        ItemTooltipCallback.EVENT.register((stack, context, lines) ->
                lines.addAll(ErydonTooltipDelay.getTooltipLines(stack)));
    }
}
