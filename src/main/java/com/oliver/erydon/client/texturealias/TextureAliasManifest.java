package com.oliver.erydon.client.texturealias;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TextureAliasManifest {
    static final int SCHEMA_VERSION = 1;
    static final String FORMAT = "erydon-texture-aliases";
    static final String MANIFEST_PATH = "texture_aliases/v1.json";

    private final String tier;
    private final Map<String, String> aliases;
    private final Map<String, String> fileHashes;

    private TextureAliasManifest(
            String tier,
            Map<String, String> aliases,
            Map<String, String> fileHashes
    ) {
        this.tier = tier;
        this.aliases = Collections.unmodifiableMap(new LinkedHashMap<>(aliases));
        this.fileHashes = Collections.unmodifiableMap(new LinkedHashMap<>(fileHashes));
    }

    static TextureAliasManifest load(ResourcePack pack, String namespace) throws IOException {
        Identifier manifestId = new Identifier(namespace, MANIFEST_PATH);
        InputSupplier<InputStream> supplier = pack.open(ResourceType.CLIENT_RESOURCES, manifestId);
        if (supplier == null) {
            return null;
        }

        JsonObject root;
        try (InputStream input = supplier.get()) {
            if (input == null) {
                return null;
            }
            InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
            root = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Malformed JSON in " + manifestId, exception);
        }

        int schemaVersion = requiredInt(root, "schema_version", manifestId);
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IOException("Unsupported texture alias schema " + schemaVersion + " in " + manifestId);
        }
        String format = requiredString(root, "format", manifestId);
        if (!FORMAT.equals(format)) {
            throw new IOException("Unsupported texture alias format '" + format + "' in " + manifestId);
        }

        if (!namespace.equals(requiredString(root, "namespace", manifestId))) {
            throw new IOException("Texture alias namespace does not match " + namespace + " in " + manifestId);
        }
        String tier = requiredString(root, "tier", manifestId);

        JsonElement aliasesElement = root.get("aliases");
        if (aliasesElement == null || !aliasesElement.isJsonArray()) {
            throw new IOException("Missing array 'aliases' in " + manifestId);
        }

        Map<String, String> aliases = new LinkedHashMap<>();
        Map<String, String> fileHashes = new LinkedHashMap<>();
        String targetPrefix = "assets/" + namespace + "/texture_blobs/";
        String resourcePrefix = "assets/" + namespace + "/";
        for (JsonElement aliasElement : aliasesElement.getAsJsonArray()) {
            if (!aliasElement.isJsonObject()) {
                throw new IOException("Texture alias entry is not an object in " + manifestId);
            }
            JsonObject aliasObject = aliasElement.getAsJsonObject();
            String aliasPath = requiredString(aliasObject, "path", manifestId);
            String fullTargetPath = requiredString(aliasObject, "target", manifestId);
            String fileSha256 = requiredString(aliasObject, "file_sha256", manifestId);
            validatePath(namespace, aliasPath, "alias", manifestId);
            if (!fullTargetPath.startsWith(targetPrefix)) {
                throw new IOException("Alias target must remain inside namespace " + namespace
                        + " in " + manifestId + ": " + fullTargetPath);
            }
            String targetPath = fullTargetPath.substring(resourcePrefix.length());
            validatePath(namespace, targetPath, "target", manifestId);
            if (!targetPath.startsWith("texture_blobs/")) {
                throw new IOException("Alias target must be inside texture_blobs in " + manifestId + ": " + targetPath);
            }
            if (!fileSha256.matches("[0-9a-f]{64}")
                    || !fullTargetPath.equals(targetPrefix + fileSha256 + ".png")) {
                throw new IOException("Alias target does not match its SHA-256 in " + manifestId
                        + ": " + fullTargetPath);
            }
            String expectedLogicalId = logicalId(namespace, aliasPath);
            if (!expectedLogicalId.equals(requiredString(aliasObject, "logical_id", manifestId))) {
                throw new IOException("Logical texture ID does not match path in " + manifestId + ": " + aliasPath);
            }
            if (requiredBoolean(aliasObject, "mcmeta_present", manifestId)) {
                throw new IOException("Animated or metadata-backed aliases are not enabled in schema pilot 1: "
                        + aliasPath);
            }
            if (aliasPath.equals(targetPath)) {
                throw new IOException("Self-referencing alias in " + manifestId + ": " + aliasPath);
            }
            if (aliases.put(aliasPath, targetPath) != null) {
                throw new IOException("Duplicate texture alias path in " + manifestId + ": " + aliasPath);
            }
            fileHashes.put(aliasPath, fileSha256);
        }

        validateDeclaredBlobs(root, namespace, aliases, manifestId);
        validateNoCycles(aliases, manifestId);
        return new TextureAliasManifest(tier, aliases, fileHashes);
    }

    String tier() {
        return tier;
    }

    Map<String, String> aliases() {
        return aliases;
    }

    String fileSha256(String aliasPath) {
        return fileHashes.get(aliasPath);
    }

    private static void validateNoCycles(Map<String, String> aliases, Identifier manifestId) throws IOException {
        for (String start : aliases.keySet()) {
            List<String> chain = new ArrayList<>();
            String current = start;
            while (aliases.containsKey(current)) {
                if (chain.contains(current)) {
                    chain.add(current);
                    throw new IOException("Texture alias cycle in " + manifestId + ": " + String.join(" -> ", chain));
                }
                chain.add(current);
                current = aliases.get(current);
            }
        }
    }

    private static void validatePath(String namespace, String path, String label, Identifier manifestId)
            throws IOException {
        if (path.indexOf(':') >= 0 || path.startsWith("/") || path.contains("..") || path.indexOf('\\') >= 0) {
            throw new IOException("Invalid " + label + " path in " + manifestId + ": " + path);
        }
        try {
            new Identifier(namespace, path);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid " + label + " path in " + manifestId + ": " + path, exception);
        }
    }

    private static String logicalId(String namespace, String path) throws IOException {
        if ((!path.startsWith("textures/") && !path.startsWith("optifine/"))
                || !path.endsWith(".png")) {
            throw new IOException(
                    "Texture alias path must be a PNG under textures/ or optifine/: " + path
            );
        }
        String logicalPath = path.substring(0, path.length() - ".png".length());
        if (logicalPath.startsWith("textures/")) {
            logicalPath = logicalPath.substring("textures/".length());
        }
        return namespace + ":" + logicalPath;
    }

    private static void validateDeclaredBlobs(
            JsonObject root,
            String namespace,
            Map<String, String> aliases,
            Identifier manifestId
    ) throws IOException {
        JsonElement blobsElement = root.get("blobs");
        if (blobsElement == null || !blobsElement.isJsonArray()) {
            throw new IOException("Missing array 'blobs' in " + manifestId);
        }

        Set<String> expectedTargets = Set.copyOf(aliases.values());
        Set<String> declaredTargets = new java.util.HashSet<>();
        String prefix = "assets/" + namespace + "/";
        for (JsonElement blobElement : blobsElement.getAsJsonArray()) {
            if (!blobElement.isJsonObject()) {
                throw new IOException("Texture blob entry is not an object in " + manifestId);
            }
            JsonObject blob = blobElement.getAsJsonObject();
            String fullTarget = requiredString(blob, "target", manifestId);
            String fileSha256 = requiredString(blob, "file_sha256", manifestId);
            if (!fullTarget.startsWith(prefix)) {
                throw new IOException("Cross-namespace texture blob in " + manifestId + ": " + fullTarget);
            }
            String target = fullTarget.substring(prefix.length());
            if (!fileSha256.matches("[0-9a-f]{64}")
                    || !target.equals("texture_blobs/" + fileSha256 + ".png")) {
                throw new IOException("Texture blob target does not match its SHA-256 in " + manifestId
                        + ": " + fullTarget);
            }
            if (!declaredTargets.add(target)) {
                throw new IOException("Duplicate texture blob in " + manifestId + ": " + fullTarget);
            }
        }
        if (!declaredTargets.equals(expectedTargets)) {
            throw new IOException("Declared texture blobs do not exactly match alias targets in " + manifestId);
        }
    }

    private static int requiredInt(JsonObject object, String key, Identifier manifestId) throws IOException {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IOException("Missing integer '" + key + "' in " + manifestId);
        }
        return element.getAsInt();
    }

    private static String requiredString(JsonObject object, String key, Identifier manifestId) throws IOException {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IOException("Missing string '" + key + "' in " + manifestId);
        }
        return element.getAsString();
    }

    private static boolean requiredBoolean(JsonObject object, String key, Identifier manifestId) throws IOException {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IOException("Missing boolean '" + key + "' in " + manifestId);
        }
        return element.getAsBoolean();
    }
}
