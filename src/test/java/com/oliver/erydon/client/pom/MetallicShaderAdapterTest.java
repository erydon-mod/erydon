package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetallicShaderAdapterTest {
    @Test void disabledUnknownAndMissingSourcesCannotPartiallyEnableMetalRendering() {
        String vertex = "unchanged vertex";
        String fragment = "unchanged fragment";
        for (String program : MetallicShaderAdapter.PROGRAMS) {
            var disabled = MetallicShaderAdapter.adapt(program, vertex, fragment, false);
            assertFalse(disabled.changed());
            assertSame(vertex, disabled.vertex());
            assertSame(fragment, disabled.fragment());
            var unsupported = MetallicShaderAdapter.adapt(program, vertex, fragment, true);
            assertFalse(unsupported.changed(), program);
            assertEquals("UNSUPPORTED_SOURCE", unsupported.status(), program);
            assertSame(vertex, unsupported.vertex());
            assertSame(fragment, unsupported.fragment());
            var missing = MetallicShaderAdapter.adapt(program, vertex, null, true);
            assertFalse(missing.changed());
            assertSame(vertex, missing.vertex());
            assertNull(missing.fragment());
        }
        var other = MetallicShaderAdapter.adapt("gbuffers_entities", vertex, fragment, true);
        assertFalse(other.changed());
        assertEquals("OTHER_PROGRAM", other.status());
        assertSame(vertex, other.vertex());
        assertSame(fragment, other.fragment());
    }
}
