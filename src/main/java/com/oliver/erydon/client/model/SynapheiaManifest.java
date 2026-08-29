package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/** Builds Synapheia's immutable rule manifest from the active CTM resource stack. */
final class SynapheiaManifest {
    private static final List<String> ROOTS = List.of("optifine/ctm", "mcpatcher/ctm");
    private static final Set<Direction> ALL_FACES = Set.copyOf(EnumSet.allOf(Direction.class));
    private static final Comparator<Rule> RULE_ORDER = Comparator
            .comparingInt(Rule::priority).reversed()
            .thenComparing((Rule rule) -> rule.matchTiles().isEmpty())
            .thenComparing(rule -> rule.resourceId().toString());

    private SynapheiaManifest() {
    }

    static Prepared load(ResourceManager manager) {
        Map<Identifier, Resource> resources = new TreeMap<>(Comparator.comparing(Identifier::toString));
        for (String root : ROOTS) {
            resources.putAll(manager.findResources(root, id -> id.getPath().endsWith(".properties")));
        }

        List<Rule> rules = new ArrayList<>();
        Set<String> sourcePacks = new LinkedHashSet<>();
        SynapheiaTileSequencePool tileSequences = new SynapheiaTileSequencePool();
        int bytesRead = 0;
        int legacyTranslucentOverlayRules = 0;
        boolean metricsEnabled = SynapheiaMetrics.enabled();
        for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
            Identifier resourceId = entry.getKey();
            Resource resource = entry.getValue();
            byte[] bytes = read(resourceId, resource);
            bytesRead += bytes.length;
            Properties properties = loadProperties(resourceId, bytes);
            Set<Identifier> blocks = parseBlocks(properties.getProperty("matchBlocks"));
            if (blocks.isEmpty()) {
                continue;
            }

            Rule parsedRule = parseRule(resourceId, resource.getResourcePackName(), properties, blocks);
            if (parsedRule.method() == Method.OVERLAY_CTM && usesLegacyTranslucentLayer(properties)) {
                legacyTranslucentOverlayRules++;
            }
            Rule rule = parsedRule.withTiles(tileSequences.intern(parsedRule.tiles()));
            rules.add(rule);
            sourcePacks.add(resource.getResourcePackName());
            if (metricsEnabled) {
                SynapheiaMetrics.event("ctm_rule_parsed", SynapheiaMode.SYNAPHEIA, 0L, fields(
                        "rule_id", rule.id(), "method", rule.method().propertyValue(),
                        "status", "accepted", "block_count", rule.blocks().size()
                ));
            }
        }

        if (rules.isEmpty()) {
            throw new IllegalStateException("No active CTM rules target ERYDON blocks.");
        }
        rules.sort(RULE_ORDER);
        long repeatCount = rules.stream().filter(rule -> rule.method() == Method.REPEAT).count();
        long overlayCount = rules.stream().filter(rule -> rule.method() == Method.OVERLAY_CTM).count();
        if (repeatCount == 0) {
            throw new IllegalStateException("The active resource stack contains no ERYDON repeat rules.");
        }
        if (legacyTranslucentOverlayRules > 0) {
            Erydon.LOGGER.info("[{}] Synapheia mapped {} legacy layer=translucent overlay rules to cutout_mipped.",
                    Erydon.MOD_ID, legacyTranslucentOverlayRules);
        }
        return new Prepared(List.copyOf(rules), String.join(", ", sourcePacks), bytesRead,
                Math.toIntExact(repeatCount), Math.toIntExact(overlayCount), 0L);
    }

    static Rule parseRule(Identifier resourceId,
                          String sourcePack,
                          Properties properties,
                          Set<Identifier> blocks) {
        Method method;
        try {
            method = Method.fromProperty(require(properties, "method", resourceId));
        } catch (IllegalStateException exception) {
            throw invalid(resourceId, exception.getMessage());
        }
        int expectedTiles = method == Method.REPEAT ? 36 : 47;
        List<Identifier> tiles = parseTextureIds(require(properties, "tiles", resourceId), resourceId);
        if (tiles.size() != expectedTiles) {
            throw invalid(resourceId, method.propertyValue() + " requires " + expectedTiles
                    + " tiles, found " + tiles.size());
        }

        if (method == Method.REPEAT) {
            int width = parseInt(properties.getProperty("width", "0"), "width", resourceId);
            int height = parseInt(properties.getProperty("height", "0"), "height", resourceId);
            if (width != 6 || height != 6) {
                throw invalid(resourceId, "repeat rules must be 6x6");
            }
        } else {
            String layer = properties.getProperty("layer", "cutout_mipped").trim();
            if (!"cutout_mipped".equalsIgnoreCase(layer) && !"translucent".equalsIgnoreCase(layer)) {
                throw invalid(resourceId, "overlay_ctm layer must be cutout_mipped");
            }
        }

        String connect = properties.getProperty("connect", "block").trim();
        if (!"block".equalsIgnoreCase(connect)) {
            throw invalid(resourceId, "only connect=block is supported, found " + connect);
        }
        boolean innerSeams = Boolean.parseBoolean(properties.getProperty("innerSeams", "false").trim());
        Set<Direction> faces = parseFaces(properties.getProperty("faces", "all"), resourceId);
        Set<Identifier> matchTiles = Set.copyOf(parseTextureIds(
                properties.getProperty("matchTiles", ""), resourceId));
        int priority = parseInt(properties.getProperty("priority", "0"), "priority", resourceId);

        return new Rule(resourceId, sourcePack, method, List.copyOf(tiles), faces,
                Set.copyOf(blocks), matchTiles, innerSeams, priority);
    }

    static Set<Identifier> parseBlocks(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        Set<Identifier> blocks = new LinkedHashSet<>();
        for (String raw : value.trim().split("\\s+")) {
            String token = raw.trim();
            if (token.isEmpty() || "\\".equals(token) || token.startsWith("!")) {
                continue;
            }
            int stateStart = token.indexOf('[');
            if (stateStart >= 0) {
                token = token.substring(0, stateStart);
            }
            Identifier id = token.indexOf(':') >= 0
                    ? Identifier.tryParse(token)
                    : Identifier.tryParse("minecraft:" + token);
            if (id != null && Erydon.MOD_ID.equals(id.getNamespace())) {
                blocks.add(id);
            }
        }
        return Set.copyOf(blocks);
    }

    private static byte[] read(Identifier id, Resource resource) {
        try (var input = resource.getInputStream()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read CTM rule " + id + ".", exception);
        }
    }

    private static Properties loadProperties(Identifier id, byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            throw invalid(id, "UTF-8 BOM is not permitted");
        }
        Properties properties = new Properties();
        try (var input = new ByteArrayInputStream(bytes)) {
            properties.load(input);
            return properties;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to parse CTM rule " + id + ".", exception);
        }
    }

    private static Set<Direction> parseFaces(String value, Identifier resourceId) {
        EnumSet<Direction> faces = EnumSet.noneOf(Direction.class);
        for (String token : value.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            switch (token) {
                case "", "\\" -> { }
                case "all" -> faces.addAll(ALL_FACES);
                case "sides" -> faces.addAll(List.of(Direction.NORTH, Direction.SOUTH,
                        Direction.WEST, Direction.EAST));
                case "top", "up" -> faces.add(Direction.UP);
                case "bottom", "down" -> faces.add(Direction.DOWN);
                case "north" -> faces.add(Direction.NORTH);
                case "south" -> faces.add(Direction.SOUTH);
                case "west" -> faces.add(Direction.WEST);
                case "east" -> faces.add(Direction.EAST);
                default -> throw invalid(resourceId, "unsupported face " + token);
            }
        }
        if (faces.isEmpty()) {
            throw invalid(resourceId, "faces must not be empty");
        }
        return Set.copyOf(faces);
    }

    private static List<Identifier> parseTextureIds(String value, Identifier resourceId) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<Identifier> result = new ArrayList<>();
        for (String token : value.trim().split("\\s+")) {
            if (token.isEmpty() || "\\".equals(token) || "<skip>".equals(token)) {
                continue;
            }
            int dash = token.indexOf('-');
            if (dash > 0 && token.substring(0, dash).chars().allMatch(Character::isDigit)
                    && token.substring(dash + 1).chars().allMatch(Character::isDigit)) {
                int first = Integer.parseInt(token.substring(0, dash));
                int last = Integer.parseInt(token.substring(dash + 1));
                if (last < first) {
                    throw invalid(resourceId, "descending tile range " + token);
                }
                for (int index = first; index <= last; index++) {
                    result.add(resolveTextureId(Integer.toString(index), resourceId));
                }
            } else {
                result.add(resolveTextureId(token, resourceId));
            }
        }
        return List.copyOf(result);
    }

    private static boolean usesLegacyTranslucentLayer(Properties properties) {
        return "translucent".equalsIgnoreCase(properties.getProperty("layer", "").trim());
    }

    private static Identifier resolveTextureId(String token, Identifier resourceId) {
        String namespace = resourceId.getNamespace();
        String path = token.replace('\\', '/');
        int namespaceSeparator = path.indexOf(':');
        if (namespaceSeparator >= 0) {
            namespace = path.substring(0, namespaceSeparator);
            path = path.substring(namespaceSeparator + 1);
        }
        if (path.startsWith("textures/")) {
            path = path.substring("textures/".length());
        } else if (path.startsWith("./")) {
            path = parent(resourceId.getPath()) + "/" + path.substring(2);
        } else if (path.indexOf('/') < 0) {
            path = parent(resourceId.getPath()) + "/" + path;
        }
        if (path.endsWith(".png")) {
            path = path.substring(0, path.length() - 4);
        }
        Identifier id = Identifier.tryParse(namespace + ":" + path);
        if (id == null) {
            throw invalid(resourceId, "invalid texture identifier " + token);
        }
        return id;
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String require(Properties properties, String key, Identifier resourceId) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw invalid(resourceId, "missing " + key);
        }
        return value.trim();
    }

    private static int parseInt(String value, String label, Identifier resourceId) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw invalid(resourceId, label + " must be an integer");
        }
    }

    private static IllegalStateException invalid(Identifier resourceId, String reason) {
        return new IllegalStateException("Unsupported ERYDON CTM rule " + resourceId + ": " + reason + ".");
    }

    private static Map<String, Object> fields(Object... entries) {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            fields.put((String) entries[index], entries[index + 1]);
        }
        return fields;
    }

    enum Method {
        REPEAT("repeat"),
        OVERLAY_CTM("overlay_ctm");

        private final String propertyValue;

        Method(String propertyValue) {
            this.propertyValue = propertyValue;
        }

        static Method fromProperty(String value) {
            for (Method method : values()) {
                if (method.propertyValue.equalsIgnoreCase(value.trim())) {
                    return method;
                }
            }
            throw new IllegalStateException("unsupported method " + value);
        }

        String propertyValue() {
            return propertyValue;
        }
    }

    record Prepared(List<Rule> rules,
                    String sourcePacks,
                    int bytes,
                    int repeatRuleCount,
                    int overlayRuleCount,
                    long durationNanos) {
        Prepared withDuration(long durationNanos) {
            return new Prepared(rules, sourcePacks, bytes, repeatRuleCount, overlayRuleCount, durationNanos);
        }
    }

    record Rule(Identifier resourceId,
                String sourcePack,
                Method method,
                List<Identifier> tiles,
                Set<Direction> faces,
                Set<Identifier> blocks,
                Set<Identifier> matchTiles,
                boolean innerSeams,
                int priority) {
        String id() {
            return resourceId + "@" + sourcePack;
        }

        Rule withTiles(List<Identifier> replacementTiles) {
            return new Rule(resourceId, sourcePack, method, replacementTiles, faces,
                    blocks, matchTiles, innerSeams, priority);
        }

        boolean matches(Direction face, Identifier sourceSprite) {
            return face != null && faces.contains(face)
                    && (matchTiles.isEmpty() || matchTiles.contains(sourceSprite));
        }
    }
}
