package com.oliver.erydon.client.texturealias;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** Run the complete resource-manager regression suite with indexed enumeration. */
class TextureAliasIndexedResourcePackTest extends TextureAliasResourcePackTest {
    private static String previous;

    @BeforeAll
    static void enableIndex() {
        previous = System.getProperty("erydon.perf.alias_index");
        System.setProperty("erydon.perf.alias_index", "true");
    }

    @AfterAll
    static void restoreIndexFlag() {
        if (previous == null) System.clearProperty("erydon.perf.alias_index");
        else System.setProperty("erydon.perf.alias_index", previous);
    }
}
