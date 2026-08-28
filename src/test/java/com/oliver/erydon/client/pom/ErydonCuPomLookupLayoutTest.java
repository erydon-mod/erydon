package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ErydonCuPomLookupLayoutTest {
    @Test
    void encodesAndFindsExactBoundsForAllThirtySixPrototypePhases() {
        ErydonCuPomLookupLayout.Encoded encoded = encodeStandardFamily();

        assertEquals(4, ErydonCuPomLookupLayout.PROTOCOL_VERSION);
        assertEquals(1024, encoded.lookupWidth());
        assertEquals(1057, encoded.lookupHeight());
        assertEquals(1, encoded.familyCount());
        assertEquals(36, encoded.recordCount());
        assertEquals(ErydonCuPomLookupLayout.RUNTIME_BYTES, encoded.rgba().length);
        assertEquals(16_384, ErydonCuPomLookupLayout.MAX_RECORDS);
        assertEquals(455, ErydonCuPomLookupLayout.MAX_FAMILIES);
        byte[] rgba = encoded.rgba();
        for (int phase = 0; phase < 36; phase++) {
            int x = standardX(phase);
            int y = standardY(phase);
            assertEquals(phase, ErydonCuPomLookupLayout.findRecord(rgba, x, y));
            assertEquals(phase, ErydonCuPomLookupLayout.findRecord(rgba, x + 15, y + 15));
            assertEquals(
                    new ErydonCuPomLookupLayout.SpriteBounds(x, y, 16, 16),
                    ErydonCuPomLookupLayout.readBounds(rgba, phase));
        }
        assertEquals(-1, ErydonCuPomLookupLayout.findRecord(rgba, 16, 16));
        assertEquals(-1, ErydonCuPomLookupLayout.findRecord(rgba, -1, 0));
        assertEquals(-1, ErydonCuPomLookupLayout.findRecord(rgba, 16_384, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ErydonCuPomLookupLayout.readBounds(rgba, 36));
    }

    @Test
    void phaseOffsetsWrapCorrectlyFromMinusThirteenThroughPlusThirteen() {
        for (int phase = 0; phase < 36; phase++) {
            for (int deltaX = -13; deltaX <= 13; deltaX++) {
                for (int deltaY = -13; deltaY <= 13; deltaY++) {
                    int expected = Math.floorMod(phase / 6 + deltaY, 6) * 6
                            + Math.floorMod(phase % 6 + deltaX, 6);
                    assertEquals(expected, ErydonCuPomLookupLayout.targetRecord(phase, deltaX, deltaY));
                }
            }
        }
    }

    @Test
    void occupancyCoversEveryQuantumCellInsideLargerSprites() {
        List<ErydonCuPomLookupPlan.SpritePhase> phases = new ArrayList<>(36);
        for (int phase = 0; phase < 36; phase++) {
            phases.add(new ErydonCuPomLookupPlan.SpritePhase(
                    4096, 4096, phase * 48, 0, 32, 32));
        }
        byte[] rgba = ErydonCuPomLookupPlan.buildFamily(phases).rgba();

        for (int phase = 0; phase < 36; phase++) {
            int x = phase * 48;
            assertEquals(phase, ErydonCuPomLookupLayout.findRecord(rgba, x, 0));
            assertEquals(phase, ErydonCuPomLookupLayout.findRecord(rgba, x + 16, 0));
            assertEquals(phase, ErydonCuPomLookupLayout.findRecord(rgba, x, 16));
            assertEquals(phase, ErydonCuPomLookupLayout.findRecord(rgba, x + 31, 31));
            assertEquals(-1, ErydonCuPomLookupLayout.findRecord(rgba, x + 32, 0));
        }
    }

    @Test
    void rejectsIncompleteDuplicateMixedSizeAndAtlasMismatchInputs() {
        List<ErydonCuPomLookupPlan.SpritePhase> phases = standardPhases();
        assertThrows(IllegalArgumentException.class,
                () -> ErydonCuPomLookupPlan.buildFamily(phases.subList(0, 35)));

        List<ErydonCuPomLookupPlan.SpritePhase> duplicate = new ArrayList<>(phases);
        duplicate.set(35, duplicate.get(0));
        assertThrows(IllegalArgumentException.class, () -> ErydonCuPomLookupPlan.buildFamily(duplicate));

        List<ErydonCuPomLookupPlan.SpritePhase> mixedSize = new ArrayList<>(phases);
        ErydonCuPomLookupPlan.SpritePhase original = mixedSize.get(9);
        mixedSize.set(9, new ErydonCuPomLookupPlan.SpritePhase(
                original.atlasWidth(), original.atlasHeight(), original.spriteX(), original.spriteY(), 32, 16));
        assertThrows(IllegalArgumentException.class, () -> ErydonCuPomLookupPlan.buildFamily(mixedSize));

        List<ErydonCuPomLookupPlan.SpritePhase> atlasMismatch = new ArrayList<>(phases);
        original = atlasMismatch.get(12);
        atlasMismatch.set(12, new ErydonCuPomLookupPlan.SpritePhase(
                8192, original.atlasHeight(), original.spriteX(), original.spriteY(),
                original.spriteWidth(), original.spriteHeight()));
        assertThrows(IllegalArgumentException.class, () -> ErydonCuPomLookupPlan.buildFamily(atlasMismatch));
    }

    @Test
    void rejectsUnalignedOutOfAtlasAndOverlappingBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new ErydonCuPomLookupPlan.SpritePhase(4096, 4096, 1, 0, 16, 16));
        assertThrows(IllegalArgumentException.class,
                () -> new ErydonCuPomLookupPlan.SpritePhase(4096, 4096, 4096, 0, 16, 16));
        assertThrows(IllegalArgumentException.class,
                () -> new ErydonCuPomLookupPlan.SpritePhase(32768, 4096, 0, 0, 16, 16));

        List<ErydonCuPomLookupPlan.SpritePhase> overlapping = new ArrayList<>(36);
        for (int phase = 0; phase < 36; phase++) {
            overlapping.add(new ErydonCuPomLookupPlan.SpritePhase(
                    4096, 4096, phase * 48, 0, 32, 32));
        }
        overlapping.set(35, new ErydonCuPomLookupPlan.SpritePhase(4096, 4096, 16, 16, 32, 32));
        assertThrows(IllegalArgumentException.class, () -> ErydonCuPomLookupPlan.buildFamily(overlapping));
    }

    @Test
    void encodesEveryCurrentErydonRepeatFamilyWithinOneLookup() {
        int familyCount = 244;
        List<ErydonCuPomLookupPlan.SpriteFamily> families = new ArrayList<>(familyCount);
        for (int family = 0; family < familyCount; family++) {
            List<ErydonCuPomLookupPlan.SpritePhase> phases = new ArrayList<>(36);
            for (int phase = 0; phase < 36; phase++) {
                int record = family * 36 + phase;
                phases.add(new ErydonCuPomLookupPlan.SpritePhase(
                        16384, 16384,
                        (record % 1024) * 16,
                        (record / 1024) * 16,
                        16,
                        16));
            }
            families.add(new ErydonCuPomLookupPlan.SpriteFamily("family-" + family, phases));
        }

        ErydonCuPomLookupLayout.Encoded encoded = ErydonCuPomLookupPlan.buildFamilies(families);
        assertEquals(familyCount, encoded.familyCount());
        assertEquals(familyCount * 36, encoded.recordCount());
        byte[] rgba = encoded.rgba();
        for (int record = 0; record < encoded.recordCount(); record++) {
            assertEquals(record, ErydonCuPomLookupLayout.findRecord(
                    rgba,
                    (record % 1024) * 16 + 8,
                    (record / 1024) * 16 + 8));
        }
    }

    @Test
    void rejectsMoreThanFourHundredAndFiftyFiveCompleteFamilies() {
        ErydonCuPomLookupPlan.SpriteFamily family =
                new ErydonCuPomLookupPlan.SpriteFamily("family", standardPhases());
        assertThrows(IllegalArgumentException.class, () -> ErydonCuPomLookupPlan.buildFamilies(
                Collections.nCopies(ErydonCuPomLookupLayout.MAX_FAMILIES + 1, family)));
    }

    @Test
    void encodingIsDeterministicAndDefensivelyCopied() {
        ErydonCuPomLookupLayout.Encoded first = encodeStandardFamily();
        ErydonCuPomLookupLayout.Encoded second = encodeStandardFamily();
        assertArrayEquals(first.rgba(), second.rgba());
        assertNotSame(first.rgba(), first.rgba());

        byte[] callerCopy = first.rgba();
        Arrays.fill(callerCopy, (byte) 0);
        assertEquals(69, first.rgba()[0] & 0xFF);
    }

    private static ErydonCuPomLookupLayout.Encoded encodeStandardFamily() {
        return ErydonCuPomLookupPlan.buildFamily(standardPhases());
    }

    private static List<ErydonCuPomLookupPlan.SpritePhase> standardPhases() {
        List<ErydonCuPomLookupPlan.SpritePhase> phases = new ArrayList<>();
        for (int phase = 0; phase < 36; phase++) {
            phases.add(new ErydonCuPomLookupPlan.SpritePhase(
                    4096, 4096, standardX(phase), standardY(phase), 16, 16));
        }
        return phases;
    }

    private static int standardX(int phase) {
        return (phase % 12) * 32;
    }

    private static int standardY(int phase) {
        return (phase / 12) * 32;
    }
}
