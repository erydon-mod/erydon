package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ErydonCuPomLookupLayoutTest {
    @Test
    void encodesAndFindsAllThirtySixPrototypePhases() {
        ErydonCuPomLookupLayout.Encoded encoded = encodeStandardFamily();

        assertEquals(1, encoded.familyCount());
        assertEquals(36, encoded.recordCount());
        assertEquals(ErydonCuPomLookupLayout.RUNTIME_BYTES, encoded.rgba().length);
        for (int phase = 0; phase < 36; phase++) {
            assertEquals(phase, ErydonCuPomLookupLayout.findRecord(
                    encoded.rgba(), 32 + phase * 17, 64 + phase * 11));
        }
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
                original.atlasWidth(), original.atlasHeight(), 32, 16, original.centreX(), original.centreY()));
        assertThrows(IllegalArgumentException.class, () -> ErydonCuPomLookupPlan.buildFamily(mixedSize));

        List<ErydonCuPomLookupPlan.SpritePhase> atlasMismatch = new ArrayList<>(phases);
        original = atlasMismatch.get(12);
        atlasMismatch.set(12, new ErydonCuPomLookupPlan.SpritePhase(
                8192, original.atlasHeight(), original.spriteWidth(), original.spriteHeight(),
                original.centreX(), original.centreY()));
        assertThrows(IllegalArgumentException.class, () -> ErydonCuPomLookupPlan.buildFamily(atlasMismatch));
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
                        16384, 16384, 16, 16,
                        8 + (record % 512) * 16,
                        8 + (record / 512) * 16));
            }
            families.add(new ErydonCuPomLookupPlan.SpriteFamily("family-" + family, phases));
        }

        ErydonCuPomLookupLayout.Encoded encoded = ErydonCuPomLookupPlan.buildFamilies(families);
        assertEquals(familyCount, encoded.familyCount());
        assertEquals(familyCount * 36, encoded.recordCount());
        for (int record = 0; record < encoded.recordCount(); record++) {
            assertEquals(record, ErydonCuPomLookupLayout.findRecord(
                    encoded.rgba(),
                    8 + (record % 512) * 16,
                    8 + (record / 512) * 16));
        }
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
                    4096, 4096, 16, 16, 32 + phase * 17, 64 + phase * 11));
        }
        return phases;
    }
}
