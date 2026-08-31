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
    private final CompiledRules rules;

    private SynapheiaBlockPlan(Identifier blockId,
                               CompiledRules rules) {
        this.blockId = blockId;
        this.rules = rules;
    }

    static SynapheiaBlockPlan compile(Identifier blockId,
                                      List<SynapheiaManifest.Rule> orderedRules) {
        return create(blockId, compileRules(orderedRules));
    }

    static SynapheiaBlockPlan create(Identifier blockId, CompiledRules rules) {
        return new SynapheiaBlockPlan(blockId, rules);
    }

    static CompiledRules compileRules(List<SynapheiaManifest.Rule> orderedRules) {
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
        return new CompiledRules(repeats, overlays, repeatOutputs);
    }

    Identifier blockId() {
        return blockId;
    }

    boolean hasRepeat() {
        return !rules.repeatRules.isEmpty();
    }

    boolean hasOverlay() {
        return !rules.overlayRules.isEmpty();
    }

    boolean hasSourceShapedOverlay() {
        return rules.sourceShapedOverlays;
    }

    boolean sharesRuleDataWith(SynapheiaBlockPlan other) {
        return other != null && rules == other.rules;
    }

    SynapheiaManifest.Rule repeatRule(Direction face, Identifier sourceSprite) {
        if (sourceSprite != null && rules.repeatOutputTiles.contains(sourceSprite)) {
            return null;
        }
        if (rules.singleRepeatRule != null) {
            return rules.singleRepeatRule.matches(face, sourceSprite)
                    ? rules.singleRepeatRule : null;
        }
        for (SynapheiaManifest.Rule rule : rules.repeatRules) {
            if (rule.matches(face, sourceSprite)) {
                return rule;
            }
        }
        return null;
    }

    SynapheiaManifest.Rule repeatRuleForProjectedGeometry(Direction face) {
        for (SynapheiaManifest.Rule rule : rules.repeatRules) {
            if (rule.matchTiles().isEmpty() && rule.matches(face, null)) {
                return rule;
            }
        }
        return null;
    }

    List<SynapheiaManifest.Rule> overlayRules(Direction face, Identifier sourceSprite) {
        if (rules.overlayRules.isEmpty()) {
            return List.of();
        }
        if (rules.overlayRules.size() == 1) {
            SynapheiaManifest.Rule rule = rules.overlayRules.get(0);
            return rule.matches(face, sourceSprite) ? rules.overlayRules : List.of();
        }
        List<SynapheiaManifest.Rule> matches = null;
        for (SynapheiaManifest.Rule rule : rules.overlayRules) {
            if (rule.matches(face, sourceSprite)) {
                if (matches == null) {
                    matches = new ArrayList<>(2);
                }
                matches.add(rule);
            }
        }
        return matches == null ? List.of() : List.copyOf(matches);
    }

    static final class CompiledRules {
        private final List<SynapheiaManifest.Rule> repeatRules;
        private final List<SynapheiaManifest.Rule> overlayRules;
        private final Set<Identifier> repeatOutputTiles;
        private final SynapheiaManifest.Rule singleRepeatRule;
        private final boolean sourceShapedOverlays;

        private CompiledRules(List<SynapheiaManifest.Rule> repeatRules,
                              List<SynapheiaManifest.Rule> overlayRules,
                              Set<Identifier> repeatOutputTiles) {
            this.repeatRules = List.copyOf(repeatRules);
            this.overlayRules = List.copyOf(overlayRules);
            this.repeatOutputTiles = Set.copyOf(repeatOutputTiles);
            this.singleRepeatRule = repeatRules.size() == 1 ? repeatRules.get(0) : null;
            this.sourceShapedOverlays = overlayRules.stream().anyMatch(
                    rule -> rule.overlayShape() == SynapheiaManifest.OverlayShape.SOURCE);
        }
    }
}
