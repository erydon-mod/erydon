package com.oliver.erydon.compat;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FamilyReleaseCompatibilityTest {
    @Test
    void matchingGenerationAcceptsIndependentProductVersions() {
        assertTrue(FamilyReleaseCompatibility.findGenerationMismatches(
                2,
                Map.of(
                        "erydon", "2.0.0",
                        "themelios", "2.0.0",
                        "daedalon", "1.0.0"
                ),
                Map.of(
                        "erydon", 2,
                        "themelios", 2,
                        "daedalon", 2
                )
        ).isEmpty());
    }

    @Test
    void differentGenerationIsRejectedRegardlessOfProductVersion() {
        assertEquals(
                java.util.List.of(
                        "themelios 2.0.0 (compatibility generation 1)"
                ),
                FamilyReleaseCompatibility.findGenerationMismatches(
                        2,
                        Map.of(
                                "erydon", "2.0.0",
                                "themelios", "2.0.0"
                        ),
                        Map.of(
                                "erydon", 2,
                                "themelios", 1
                        )
                )
        );
    }

    @Test
    void missingGenerationIsRejectedAsLegacyOrUnknown() {
        assertEquals(
                java.util.List.of(
                        "daedalon 0.9.0 (missing compatibility generation)"
                ),
                FamilyReleaseCompatibility.findGenerationMismatches(
                        2,
                        Map.of(
                                "erydon", "2.0.0",
                                "daedalon", "0.9.0"
                        ),
                        Map.of("erydon", 2)
                )
        );
    }
}
