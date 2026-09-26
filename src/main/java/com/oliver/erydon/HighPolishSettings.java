package com.oliver.erydon;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Properties;

/** Immutable client preferences. The renderer captures one snapshot at startup. */
public record HighPolishSettings(boolean enabled, boolean glazing, boolean twoWay,
                                 Map<String, Stone> stones) {
    public static final String PREFIX = "erydon.high_polish.";
    public static final List<String> MATERIALS = List.of(
            "aganite", "aterzon", "borealis", "brectite", "calacattum", "chalstrom",
            "chrysonyx", "etruscus", "gelastrum", "glacium", "hesperion", "imperium",
            "kelastrion", "kylorion", "latmion", "laurentium", "mielonyx", "nerium", "noxoplis", "porphyros",
            "portorium", "psamatheon", "rosinium", "sanguenite", "selenephos", "solistra", "striatus");

    public HighPolishSettings {
        Map<String, Stone> sanitized = new HashMap<>();
        for (String material : MATERIALS) sanitized.put(material, stones.getOrDefault(material, Stone.DEFAULT));
        stones = Map.copyOf(sanitized);
    }

    public static HighPolishSettings defaults() {
        return new HighPolishSettings(false, true, true, Map.of());
    }

    public HighPolishSettings withEnabled(boolean value) {
        return new HighPolishSettings(value, glazing, twoWay, stones);
    }

    public HighPolishSettings withGlass(boolean glazing, boolean twoWay) {
        return new HighPolishSettings(enabled, glazing, twoWay, stones);
    }

    public HighPolishSettings withStone(String material, Stone stone) {
        Map<String, Stone> updated = new HashMap<>(stones);
        updated.put(material, stone);
        return new HighPolishSettings(enabled, glazing, twoWay, updated);
    }

    public boolean enables(String material, Finish finish) {
        return enabled && stones.containsKey(material) && level(material, finish) != Level.HONED;
    }

    public Level level(String material, Finish finish) {
        return enabled && stones.containsKey(material) ? stones.get(material).level(finish) : Level.HONED;
    }

    /** An explicit all-stones preset also resets pattern exceptions to Inherit. */
    public HighPolishSettings withAllStones(Level level) {
        Map<String, Stone> updated = new HashMap<>();
        for (String material : MATERIALS) updated.put(material, new Stone(level, Choice.INHERIT, Choice.INHERIT, Choice.INHERIT));
        return new HighPolishSettings(enabled, glazing, twoWay, updated);
    }

    public boolean glazingEnabled() { return enabled && glazing; }
    public boolean twoWayEnabled() { return enabled && twoWay; }

    public static HighPolishSettings read(Properties properties) {
        Map<String, Stone> stones = new HashMap<>();
        for (String material : MATERIALS) {
            String key = PREFIX + "stone." + material;
            stones.put(material, new Stone(Level.read(properties.getProperty(key)),
                    Choice.read(properties.getProperty(key + ".herringbone")),
                    Choice.read(properties.getProperty(key + ".weave")),
                    Choice.read(properties.getProperty(key + ".inlays"))));
        }
        return new HighPolishSettings(readBoolean(properties, PREFIX + "enabled", false),
                readBoolean(properties, PREFIX + "glazing", true),
                readBoolean(properties, PREFIX + "two_way", true), stones);
    }

    public void write(Properties properties) {
        properties.setProperty(PREFIX + "enabled", Boolean.toString(enabled));
        properties.setProperty(PREFIX + "glazing", Boolean.toString(glazing));
        properties.setProperty(PREFIX + "two_way", Boolean.toString(twoWay));
        for (String material : MATERIALS) {
            String key = PREFIX + "stone." + material;
            Stone stone = stones.get(material);
            properties.setProperty(key, stone.base.key());
            properties.setProperty(key + ".herringbone", stone.herringbone.key());
            properties.setProperty(key + ".weave", stone.weave.key());
            properties.setProperty(key + ".inlays", stone.inlays.key());
        }
    }

    private static boolean readBoolean(Properties properties, String key, boolean fallback) {
        String raw = properties.getProperty(key, "").trim().toLowerCase(Locale.ROOT);
        return switch (raw) {
            case "true", "yes", "1", "on" -> true;
            case "false", "no", "0", "off" -> false;
            default -> fallback;
        };
    }

    public enum Finish { PLAIN, HERRINGBONE, WEAVE, INLAYS }

    public enum Level {
        HONED, POLISHED, MIRROR;
        public String key() { return name().toLowerCase(Locale.ROOT); }
        public Level next() { return values()[(ordinal() + 1) % values().length]; }
        static Level read(String value) {
            if (value == null) return MIRROR; // Preserve the original enabled-by-default stone preference.
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "honed", "false", "no", "0", "off" -> HONED;
                case "polished" -> POLISHED;
                default -> MIRROR; // Includes the old true/on preference.
            };
        }
    }

    public enum Choice {
        INHERIT, HONED, POLISHED, MIRROR;
        public String key() { return name().toLowerCase(Locale.ROOT); }
        public Choice next() { return values()[(ordinal() + 1) % values().length]; }
        public Level resolve(Level base) { return this == INHERIT ? base : Level.valueOf(name()); }
        static Choice read(String value) {
            if (value == null) return INHERIT;
            if ("on".equalsIgnoreCase(value.trim())) return MIRROR;
            if ("off".equalsIgnoreCase(value.trim())) return HONED;
            try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { return INHERIT; }
        }
    }

    public record Stone(Level base, Choice herringbone, Choice weave, Choice inlays) {
        public static final Stone DEFAULT = new Stone(Level.MIRROR, Choice.INHERIT, Choice.INHERIT, Choice.INHERIT);
        public Level level(Finish finish) {
            return switch (finish) {
                case PLAIN -> base;
                case HERRINGBONE -> herringbone.resolve(base);
                case WEAVE -> weave.resolve(base);
                case INLAYS -> inlays.resolve(base);
            };
        }
    }
}
