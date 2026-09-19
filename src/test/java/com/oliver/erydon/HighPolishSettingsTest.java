package com.oliver.erydon;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;
import static com.oliver.erydon.HighPolishSettings.*;

class HighPolishSettingsTest {
    @Test
    void defaultsAreOffAndOldSingleSwitchMigratesWithoutLosingCoverage() {
        var properties = new Properties();
        assertEquals(defaults(), read(properties));
        properties.setProperty(PREFIX + "enabled", "true");
        var migrated = read(properties);
        for (String material : MATERIALS) {
            for (Finish finish : Finish.values()) assertTrue(migrated.enables(material, finish));
        }
        assertTrue(migrated.glazingEnabled());
        assertTrue(migrated.twoWayEnabled());
        assertFalse(migrated.enables("kelastrion", Finish.PLAIN));
    }

    @Test
    void stoneAndPatternChoicesRoundTripAndDoNotMutateTheActiveSnapshot() {
        var active = defaults().withEnabled(true);
        var changed = active.withStone("glacium", new Stone(false, Choice.ON, Choice.INHERIT, Choice.OFF))
                .withGlass(false, true);
        assertTrue(active.enables("glacium", Finish.PLAIN));
        assertFalse(changed.enables("glacium", Finish.PLAIN));
        assertTrue(changed.enables("glacium", Finish.HERRINGBONE));
        assertFalse(changed.enables("glacium", Finish.WEAVE));
        assertFalse(changed.enables("glacium", Finish.INLAYS));
        assertTrue(changed.enables("portorium", Finish.PLAIN));
        assertFalse(changed.glazingEnabled());
        assertTrue(changed.twoWayEnabled());
        var properties = new Properties();
        changed.write(properties);
        assertEquals(changed, read(properties));
        assertThrows(UnsupportedOperationException.class, () -> active.stones().clear());
    }

    @Test
    void masterOffOverridesEverythingButPreservesSavedChoices() {
        var selected = defaults().withEnabled(true)
                .withStone("glacium", new Stone(true, Choice.ON, Choice.ON, Choice.ON));
        var off = selected.withEnabled(false);
        for (String material : MATERIALS) {
            for (Finish finish : Finish.values()) assertFalse(off.enables(material, finish));
        }
        assertFalse(off.glazingEnabled());
        assertFalse(off.twoWayEnabled());
        assertEquals(selected, off.withEnabled(true));
    }

    @Test
    void malformedValuesFallBackSafelyAndIndependentGlassChoicesArePreserved() {
        var properties = new Properties();
        properties.setProperty(PREFIX + "enabled", "broken");
        properties.setProperty(PREFIX + "stone.glacium.weave", "broken");
        assertEquals(defaults(), read(properties));
        var glazingOnly = defaults().withEnabled(true).withGlass(true, false);
        assertTrue(glazingOnly.glazingEnabled());
        assertFalse(glazingOnly.twoWayEnabled());
        var properties2 = new Properties();
        glazingOnly.write(properties2);
        assertEquals(glazingOnly, read(properties2));
        assertEquals(defaults(), new HighPolishSettings(false, true, true, Map.of("unknown", Stone.DEFAULT)));
    }
}
