package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErydonCuPomRuntimeStateTest {
    @Test
    void transformedSourceIsNotTrustedBeforeCompilation() {
        assertEquals(ErydonCuPomRuntimeState.State.SOURCE_READY,
                ErydonCuPomRuntimeState.afterProgramStatus(
                        ErydonCuPomRuntimeState.State.PENDING, "TRANSFORMED"));
    }

    @Test
    void adapterFailureIsStickyForTheCurrentShaderLoad() {
        ErydonCuPomRuntimeState.State failed = ErydonCuPomRuntimeState.afterProgramStatus(
                ErydonCuPomRuntimeState.State.SOURCE_READY, "ANCHOR_MISMATCH_NO_CHANGE");
        assertEquals(ErydonCuPomRuntimeState.State.FAILED, failed);
        assertEquals(ErydonCuPomRuntimeState.State.FAILED,
                ErydonCuPomRuntimeState.afterProgramStatus(failed, "TRANSFORMED"));
        assertEquals(ErydonCuPomRuntimeState.State.FAILED,
                ErydonCuPomRuntimeState.afterProgramStatus(failed, "POM_NOT_COMPILED"));
    }

    @Test
    void aNewTransformedProgramRevokesAnOlderCompilationUntilItLinks() {
        assertEquals(ErydonCuPomRuntimeState.State.SOURCE_READY,
                ErydonCuPomRuntimeState.afterProgramStatus(
                        ErydonCuPomRuntimeState.State.EXACT_BOUNDS, "TRANSFORMED"));
        assertEquals(ErydonCuPomRuntimeState.State.SOURCE_READY,
                ErydonCuPomRuntimeState.beforeTerrainCompilation(
                        ErydonCuPomRuntimeState.State.EXACT_BOUNDS));
        assertEquals(ErydonCuPomRuntimeState.State.EXACT_BOUNDS,
                ErydonCuPomRuntimeState.afterTerrainCompilation(
                        ErydonCuPomRuntimeState.State.SOURCE_READY));
        assertEquals(ErydonCuPomRuntimeState.State.FAILED,
                ErydonCuPomRuntimeState.afterTerrainCompilation(
                        ErydonCuPomRuntimeState.State.FAILED));
    }
}
