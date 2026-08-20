package com.oliver.erydon.client.pom;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ErydonCuPomFamilyDiscoveryTest {
    @Test
    void findsDistinctErydonRepeatFamiliesAndIgnoresOtherRules() {
        Map<Identifier, byte[]> rules = new LinkedHashMap<>();
        rules.put(id("optifine/ctm/glacium_rock/a.properties"), bytes(repeat("erydon:glacium_rock")));
        rules.put(id("optifine/ctm/glacium_rock/b.properties"), bytes(repeat("erydon:glacium_rock_slope")));
        rules.put(id("optifine/ctm/aganite_rock/a.properties"), bytes(repeat("erydon:aganite_rock")));
        rules.put(id("optifine/ctm/vanilla/a.properties"), bytes(repeat("minecraft:stone")));
        rules.put(id("optifine/ctm/overlay/a.properties"), bytes("method=overlay_ctm\nmatchBlocks=erydon:glacium\n"));

        var families = ErydonCuPomFamilyDiscovery.discover(rules);

        assertEquals(2, families.size());
        assertEquals("minecraft:optifine/ctm/aganite_rock", families.get(0).name());
        assertEquals(36, families.get(0).phases().size());
        assertEquals(id("optifine/ctm/aganite_rock/0"), families.get(0).phases().get(0));
        assertEquals(id("optifine/ctm/aganite_rock/35"), families.get(0).phases().get(35));
    }

    @Test
    void rejectsMalformedErydonRepeatRules() {
        String invalid = repeat("erydon:glacium_rock").replace("width=6", "width=5");
        Map<Identifier, byte[]> rules = Map.of(
                id("optifine/ctm/glacium_rock/a.properties"), bytes(invalid));

        assertThrows(IllegalArgumentException.class, () -> ErydonCuPomFamilyDiscovery.discover(rules));
    }

    private static String repeat(String matchBlock) {
        return "method=repeat\nwidth=6\nheight=6\nmatchBlocks=" + matchBlock + "\ntiles=0-35\n";
    }

    private static Identifier id(String path) {
        return new Identifier("minecraft", path);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }
}
