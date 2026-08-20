package com.oliver.erydon.client.pom;

import java.util.ArrayList;
import java.util.List;

/** Validates stitched sprite facts before encoding the active repeat-CTM families. */
public final class ErydonCuPomLookupPlan {
    public record SpritePhase(
            int atlasWidth,
            int atlasHeight,
            int spriteWidth,
            int spriteHeight,
            int centreX,
            int centreY
    ) {
        public SpritePhase {
            if (atlasWidth <= 0 || atlasHeight <= 0 || spriteWidth <= 0 || spriteHeight <= 0) {
                throw new IllegalArgumentException("Sprite and atlas dimensions must be positive");
            }
        }
    }

    public record SpriteFamily(String name, List<SpritePhase> phases) {
        public SpriteFamily {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("A CTM family name is required");
            }
            phases = List.copyOf(phases);
        }
    }

    public static ErydonCuPomLookupLayout.Encoded buildFamily(List<SpritePhase> phases) {
        return buildFamilies(List.of(new SpriteFamily("test-family", phases)));
    }

    public static ErydonCuPomLookupLayout.Encoded buildFamilies(List<SpriteFamily> families) {
        if (families == null || families.isEmpty()) {
            throw new IllegalArgumentException("At least one complete CTM family is required");
        }
        if (families.size() > ErydonCuPomLookupLayout.MAX_FAMILIES) {
            throw new IllegalArgumentException("Too many CTM families: " + families.size());
        }

        SpritePhase atlasReference = requireCompleteFamily(families.get(0)).get(0);
        List<List<ErydonCuPomLookupLayout.SpriteCentre>> centresByFamily =
                new ArrayList<>(families.size());
        for (SpriteFamily family : families) {
            List<SpritePhase> phases = requireCompleteFamily(family);
            SpritePhase familyReference = phases.get(0);
            List<ErydonCuPomLookupLayout.SpriteCentre> centres = new ArrayList<>(phases.size());
            for (int phase = 0; phase < phases.size(); phase++) {
                SpritePhase sprite = phases.get(phase);
                if (sprite.atlasWidth() != atlasReference.atlasWidth()
                        || sprite.atlasHeight() != atlasReference.atlasHeight()) {
                    throw new IllegalArgumentException(
                            family.name() + " phase " + phase + " reports a different atlas size");
                }
                if (sprite.spriteWidth() != familyReference.spriteWidth()
                        || sprite.spriteHeight() != familyReference.spriteHeight()) {
                    throw new IllegalArgumentException(
                            family.name() + " phase " + phase + " reports a different sprite size");
                }
                centres.add(new ErydonCuPomLookupLayout.SpriteCentre(sprite.centreX(), sprite.centreY()));
            }
            centresByFamily.add(List.copyOf(centres));
        }
        return ErydonCuPomLookupLayout.encode(
                atlasReference.atlasWidth(), atlasReference.atlasHeight(), centresByFamily);
    }

    private static List<SpritePhase> requireCompleteFamily(SpriteFamily family) {
        if (family == null) {
            throw new IllegalArgumentException("CTM family must not be null");
        }
        if (family.phases().size() != ErydonCuPomLookupLayout.PHASES_PER_FAMILY) {
            throw new IllegalArgumentException(
                    family.name() + " requires exactly " + ErydonCuPomLookupLayout.PHASES_PER_FAMILY
                            + " stitched phases");
        }
        return family.phases();
    }

    private ErydonCuPomLookupPlan() {
    }
}
