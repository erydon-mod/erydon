package com.oliver.erydon.client.pom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ErydonCuPomAdapterConfigTest {
    @Test
    void defaultsToAutoAndSupportsOneSwitchRollback() {
        assertEquals(ComplementaryUnboundDev5SourceTransformer.Mode.AUTO,
                ErydonCuPomAdapterConfig.select(null, false).mode());
        assertEquals(ComplementaryUnboundDev5SourceTransformer.Mode.OFF,
                ErydonCuPomAdapterConfig.select("off", false).mode());
    }

    @Test
    void forceIsDevelopmentOnly() {
        var development = ErydonCuPomAdapterConfig.select("force", true);
        assertEquals(ComplementaryUnboundDev5SourceTransformer.Mode.FORCE, development.mode());
        assertNull(development.warning());

        var production = ErydonCuPomAdapterConfig.select("force", false);
        assertEquals(ComplementaryUnboundDev5SourceTransformer.Mode.AUTO, production.mode());
        assertNotNull(production.warning());
    }
}
