package com.oliver.erydon.client;

import com.oliver.erydon.client.texturealias.FamilyTextureAliasCoordinator;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Replaces the old command advert with one concise resource-pack notice on
 * each connection. Family leader election prevents duplicate family notices.
 */
public final class CollectionResourcePackNotice {
    private static final String MODRINTH_URL = "https://modrinth.com/user/ERYDON";
    private static final String CURSEFORGE_URL =
            "https://www.curseforge.com/members/erydon/projects";

    private CollectionResourcePackNotice() {
    }

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(() -> showIfNeeded(client)));
    }

    private static void showIfNeeded(MinecraftClient client) {
        if (!FamilyTextureAliasCoordinator.isLeader()
                || client.player == null) {
            return;
        }

        MutableText modrinth = link(
                MODRINTH_URL,
                "message.erydon_family.resource_packs.modrinth",
                "message.erydon_family.resource_packs.open_modrinth"
        );
        MutableText curseForge = link(
                CURSEFORGE_URL,
                "message.erydon_family.resource_packs.curseforge",
                "message.erydon_family.resource_packs.open_curseforge"
        );
        MutableText message = Text.literal("[ERYDON] ")
                .formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.translatable(
                        "message.erydon_family.resource_packs.notice",
                        modrinth,
                        curseForge
                ).formatted(Formatting.YELLOW));
        client.player.sendMessage(message, false);
    }

    private static MutableText link(
            String url,
            String labelKey,
            String hoverKey
    ) {
        return Text.translatable(labelKey)
                .formatted(Formatting.AQUA, Formatting.UNDERLINE)
                .styled(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                        .withHoverEvent(new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT,
                                Text.translatable(hoverKey)
                        )));
    }
}
