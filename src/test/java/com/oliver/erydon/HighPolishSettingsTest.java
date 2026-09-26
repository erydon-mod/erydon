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
            for (Finish finish : Finish.values()) assertEquals(Level.MIRROR, migrated.level(material, finish));
        }
        assertTrue(migrated.glazingEnabled());
        assertTrue(migrated.twoWayEnabled());
        assertTrue(migrated.enables("kelastrion", Finish.PLAIN));
    }

    @Test
    void stoneAndPatternChoicesRoundTripAndDoNotMutateTheActiveSnapshot() {
        var active = defaults().withEnabled(true);
        var changed = active.withStone("glacium", new Stone(Level.HONED, Choice.POLISHED, Choice.INHERIT, Choice.MIRROR))
                .withGlass(false, true);
        assertTrue(active.enables("glacium", Finish.PLAIN));
        assertFalse(changed.enables("glacium", Finish.PLAIN));
        assertTrue(changed.enables("glacium", Finish.HERRINGBONE));
        assertFalse(changed.enables("glacium", Finish.WEAVE));
        assertEquals(Level.POLISHED, changed.level("glacium", Finish.HERRINGBONE));
        assertEquals(Level.MIRROR, changed.level("glacium", Finish.INLAYS));
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
                .withStone("glacium", new Stone(Level.POLISHED, Choice.MIRROR, Choice.HONED, Choice.INHERIT));
        var off = selected.withEnabled(false);
        for (String material : MATERIALS) {
            for (Finish finish : Finish.values()) {
                assertFalse(off.enables(material, finish));
                assertEquals(Level.HONED, off.level(material, finish));
            }
        }
        assertFalse(off.glazingEnabled());
        assertFalse(off.twoWayEnabled());
        assertEquals(selected, off.withEnabled(true));
    }

    @Test
    void oldPerStoneBooleansAndPatternOverridesMigrateToTheEquivalentFinish() {
        var properties = new Properties();
        properties.setProperty(PREFIX + "enabled", "true");
        properties.setProperty(PREFIX + "stone.glacium", "false");
        properties.setProperty(PREFIX + "stone.glacium.herringbone", "on");
        properties.setProperty(PREFIX + "stone.glacium.inlays", "off");
        properties.setProperty(PREFIX + "stone.latmion", "true");
        var migrated = read(properties);
        assertEquals(Level.HONED, migrated.level("glacium", Finish.PLAIN));
        assertEquals(Level.MIRROR, migrated.level("glacium", Finish.HERRINGBONE));
        assertEquals(Level.HONED, migrated.level("glacium", Finish.INLAYS));
        assertEquals(Level.HONED, migrated.level("glacium", Finish.WEAVE));
        assertEquals(Level.MIRROR, migrated.level("latmion", Finish.PLAIN));
        migrated.write(properties);
        assertEquals("honed", properties.getProperty(PREFIX + "stone.glacium"));
        assertEquals("mirror", properties.getProperty(PREFIX + "stone.glacium.herringbone"));
        assertEquals(migrated, read(properties));
    }

    @Test
    void allStonePresetsResetPatternExceptionsButPreserveGlassAndTheMasterSwitch() {
        var mixed = defaults().withGlass(false, true)
                .withStone("latmion", new Stone(Level.HONED, Choice.MIRROR, Choice.POLISHED, Choice.HONED));
        for (Level level : Level.values()) {
            var changed = mixed.withAllStones(level);
            assertFalse(changed.enabled());
            assertFalse(changed.glazing());
            assertTrue(changed.twoWay());
            for (String material : MATERIALS) {
                assertEquals(new Stone(level, Choice.INHERIT, Choice.INHERIT, Choice.INHERIT), changed.stones().get(material));
                for (Finish finish : Finish.values()) assertEquals(level, changed.withEnabled(true).level(material, finish));
            }
        }
        assertEquals(Choice.MIRROR, mixed.stones().get("latmion").herringbone());
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
