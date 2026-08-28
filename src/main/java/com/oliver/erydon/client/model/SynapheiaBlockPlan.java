package com.oliver.erydon.client.model;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Immutable render-time rule plan compiled once for one covered block. */
final class SynapheiaBlockPlan {
    private final Identifier blockId;
    private final List<SynapheiaManifest.Rule> repeatRules;
    private final List<SynapheiaManifest.Rule> overlayRules;
    private final Set<Identifier> repeatOutputTiles;
    private final SynapheiaManifest.Rule singleRepeatRule;

    private SynapheiaBlockPlan(Identifier blockId,
                               List<SynapheiaManifest.Rule> repeatRules,
                               List<SynapheiaManifest.Rule> overlayRules,
                               Set<Identifier> repeatOutputTiles) {
        this.blockId = blockId;
        this.repeatRules = List.copyOf(repeatRules);
        this.overlayRules = List.copyOf(overlayRules);
        this.repeatOutputTiles = Set.copyOf(repeatOutputTiles);
        this.singleRepeatRule = repeatRules.size() == 1 ? repeatRules.get(0) : null;
    }

    static SynapheiaBlockPlan compile(Identifier blockId,
                                      List<SynapheiaManifest.Rule> orderedRules) {
        List<SynapheiaManifest.Rule> repeats = new ArrayList<>();
        List<SynapheiaManifest.Rule> overlays = new ArrayList<>();
        Set<Identifier> repeatOutputs = new LinkedHashSet<>();
        for (SynapheiaManifest.Rule rule : orderedRules) {
            if (rule.method() == SynapheiaManifest.Method.REPEAT) {
                repeats.add(rule);
                repeatOutputs.addAll(rule.tiles());
            } else if (rule.method() == SynapheiaManifest.Method.OVERLAY_CTM) {
                overlays.add(rule);
            }
        }
        return new SynapheiaBlockPlan(blockId, repeats, overlays, repeatOutputs);
    }

    Identifier blockId() {
        return blockId;
    }

    boolean hasRepeat() {
        return !repeatRules.isEmpty();
    }

    boolean hasOverlay() {
        return !overlayRules.isEmpty();
    }

    SynapheiaManifest.Rule repeatRule(Direction face, Identifier sourceSprite) {
        if (sourceSprite != null && repeatOutputTiles.contains(sourceSprite)) {
            return null;
        }
        if (singleRepeatRule != null) {
            return singleRepeatRule.matches(face, sourceSprite) ? singleRepeatRule : null;
        }
        for (SynapheiaManifest.Rule rule : repeatRules) {
            if (rule.matches(face, sourceSprite)) {
                return rule;
            }
        }
        return null;
    }

    SynapheiaManifest.Rule repeatRuleForProjectedGeometry(Direction face) {
        for (SynapheiaManifest.Rule rule : repeatRules) {
            if (rule.matchTiles().isEmpty() && rule.matches(face, null)) {
                return rule;
            }
        }
        return null;
    }

    List<SynapheiaManifest.Rule> overlayRules(Direction face, Identifier sourceSprite) {
        if (overlayRules.isEmpty()) {
            return List.of();
        }
        if (overlayRules.size() == 1) {
            SynapheiaManifest.Rule rule = overlayRules.get(0);
            return rule.matches(face, sourceSprite) ? overlayRules : List.of();
        }
        List<SynapheiaManifest.Rule> matches = null;
        for (SynapheiaManifest.Rule rule : overlayRules) {
            if (rule.matches(face, sourceSprite)) {
                if (matches == null) {
                    matches = new ArrayList<>(2);
                }
                matches.add(rule);
            }
        }
        return matches == null ? List.of() : List.copyOf(matches);
    }
}
