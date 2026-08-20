package com.oliver.erydon.client.pom;

import com.oliver.erydon.Erydon;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/** Finds the distinct 6x6 repeat tile sets used by active ERYDON CTM rules. */
final class ErydonCuPomFamilyDiscovery {
    private static final List<String> ROOTS = List.of("optifine/ctm", "mcpatcher/ctm");

    record Family(String name, List<Identifier> phases) {
        Family {
            phases = List.copyOf(phases);
        }
    }

    static List<Family> discover(ResourceManager manager) {
        Map<Identifier, Resource> resources = new TreeMap<>((left, right) ->
                left.toString().compareTo(right.toString()));
        for (String root : ROOTS) {
            resources.putAll(manager.findResources(root, id -> id.getPath().endsWith(".properties")));
        }

        Map<Identifier, byte[]> bytesByRule = new LinkedHashMap<>();
        for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
            try (var input = entry.getValue().getInputStream()) {
                bytesByRule.put(entry.getKey(), input.readAllBytes());
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to read CTM rule " + entry.getKey(), exception);
            }
        }
        return discover(bytesByRule);
    }

    static List<Family> discover(Map<Identifier, byte[]> rules) {
        Map<String, Family> uniqueFamilies = new TreeMap<>();
        for (Map.Entry<Identifier, byte[]> entry : rules.entrySet()) {
            Family family = parseRule(entry.getKey(), entry.getValue());
            if (family == null) {
                continue;
            }
            String key = family.phases().stream()
                    .map(Identifier::toString)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElseThrow();
            uniqueFamilies.putIfAbsent(key, family);
        }
        if (uniqueFamilies.isEmpty()) {
            throw new IllegalArgumentException("No active 6x6 repeat CTM families target ERYDON blocks");
        }
        return List.copyOf(uniqueFamilies.values());
    }

    private static Family parseRule(Identifier resourceId, byte[] bytes) {
        Properties properties = loadProperties(resourceId, bytes);
        if (!"repeat".equalsIgnoreCase(properties.getProperty("method", "").trim())
                || !targetsErydon(properties.getProperty("matchBlocks"))) {
            return null;
        }

        int width = parseInt(properties.getProperty("width"), "width", resourceId);
        int height = parseInt(properties.getProperty("height"), "height", resourceId);
        if (width != 6 || height != 6) {
            throw invalid(resourceId, "repeat grid must be 6x6");
        }

        List<Identifier> phases = parseTextureIds(properties.getProperty("tiles"), resourceId);
        if (phases.size() != ErydonCuPomLookupLayout.PHASES_PER_FAMILY) {
            throw invalid(resourceId, "expected 36 repeat phases, found " + phases.size());
        }
        Set<Identifier> distinct = new LinkedHashSet<>(phases);
        if (distinct.size() != phases.size()) {
            throw invalid(resourceId, "repeat phases must use 36 distinct sprite identifiers");
        }
        return new Family(familyName(resourceId, phases), phases);
    }

    private static Properties loadProperties(Identifier resourceId, byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            throw invalid(resourceId, "UTF-8 BOM is not permitted");
        }
        Properties properties = new Properties();
        try (var input = new ByteArrayInputStream(bytes)) {
            properties.load(input);
            return properties;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to parse CTM rule " + resourceId, exception);
        }
    }

    private static boolean targetsErydon(String matchBlocks) {
        if (matchBlocks == null || matchBlocks.isBlank()) {
            return false;
        }
        for (String raw : matchBlocks.trim().split("\\s+")) {
            String token = raw.trim();
            if (token.isEmpty() || "\\".equals(token) || token.startsWith("!")) {
                continue;
            }
            int stateStart = token.indexOf('[');
            if (stateStart >= 0) {
                token = token.substring(0, stateStart);
            }
            if (token.startsWith(Erydon.MOD_ID + ":")) {
                return true;
            }
        }
        return false;
    }

    private static List<Identifier> parseTextureIds(String value, Identifier resourceId) {
        if (value == null || value.isBlank()) {
            throw invalid(resourceId, "missing tiles");
        }
        List<Identifier> result = new ArrayList<>();
        for (String raw : value.trim().split("\\s+")) {
            String token = raw.trim();
            if (token.isEmpty() || "\\".equals(token)) {
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
                for (int phase = first; phase <= last; phase++) {
                    result.add(resolveTextureId(Integer.toString(phase), resourceId));
                }
            } else {
                result.add(resolveTextureId(token, resourceId));
            }
        }
        return List.copyOf(result);
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

    private static int parseInt(String value, String label, Identifier resourceId) {
        if (value == null) {
            throw invalid(resourceId, "missing " + label);
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw invalid(resourceId, label + " must be an integer");
        }
    }

    private static String familyName(Identifier resourceId, List<Identifier> phases) {
        Identifier first = phases.get(0);
        String firstParent = parent(first.getPath());
        boolean commonParent = phases.stream().allMatch(id ->
                id.getNamespace().equals(first.getNamespace()) && parent(id.getPath()).equals(firstParent));
        return commonParent ? first.getNamespace() + ":" + firstParent : resourceId.toString();
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static IllegalArgumentException invalid(Identifier resourceId, String reason) {
        return new IllegalArgumentException("Invalid ERYDON CTM rule " + resourceId + ": " + reason);
    }

    private ErydonCuPomFamilyDiscovery() {
    }
}
