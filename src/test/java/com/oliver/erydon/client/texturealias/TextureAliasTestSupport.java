package com.oliver.erydon.client.texturealias;

import net.fabricmc.fabric.api.resource.ModResourcePack;
import net.fabricmc.fabric.impl.resource.loader.GroupResourcePack;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.metadata.ResourceMetadataReader;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TextureAliasTestSupport {
    static final String NAMESPACE = "erydon";
    static final String ALIAS_PATH = "textures/block/pilot_alias.png";
    static final Identifier ALIAS = id(NAMESPACE, ALIAS_PATH);

    private TextureAliasTestSupport() {
    }

    static AliasFixture singleAliasPack(String name, String contents) {
        FakePack pack = new FakePack(name);
        return populateSingleAlias(pack, NAMESPACE, ALIAS_PATH, contents);
    }

    static AliasFixture populateSingleAlias(
            FakePack pack,
            String namespace,
            String aliasPath,
            String contents
    ) {
        Identifier alias = id(namespace, aliasPath);
        byte[] canonicalBytes = bytes(contents);
        Identifier blob = blobId(namespace, canonicalBytes);
        pack.putBytes(blob, canonicalBytes);
        addManifest(
                pack,
                namespace,
                linkedAliases(alias, blob),
                false
        );
        return new AliasFixture(pack, alias, blob);
    }

    static void addManifest(
            FakePack pack,
            String namespace,
            Map<Identifier, Identifier> aliases,
            boolean mcmetaPresent
    ) {
        addManifest(pack, namespace, aliases, mcmetaPresent, "test");
    }

    static void addManifest(
            FakePack pack,
            String namespace,
            Map<Identifier, Identifier> aliases,
            boolean mcmetaPresent,
            String tier
    ) {
        pack.put(
                id(namespace, TextureAliasManifest.MANIFEST_PATH),
                manifest(namespace, aliases, mcmetaPresent, tier)
        );
    }

    static LinkedHashMap<Identifier, Identifier> linkedAliases(
            Identifier firstAlias,
            Identifier firstTarget
    ) {
        LinkedHashMap<Identifier, Identifier> aliases = new LinkedHashMap<>();
        aliases.put(firstAlias, firstTarget);
        return aliases;
    }

    static Identifier blobId(String namespace, byte[] contents) {
        return id(namespace, "texture_blobs/" + sha256(contents) + ".png");
    }

    static Identifier id(String namespace, String path) {
        return new Identifier(namespace, path);
    }

    static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String manifest(
            String namespace,
            Map<Identifier, Identifier> aliases,
            boolean mcmetaPresent,
            String tier
    ) {
        StringBuilder json = new StringBuilder(
                "{\"schema_version\":1,\"format\":\"erydon-texture-aliases\","
                        + "\"namespace\":\"" + namespace + "\",\"tier\":\"" + tier
                        + "\",\"aliases\":["
        );
        boolean first = true;
        Set<Identifier> blobs = new LinkedHashSet<>();
        for (Map.Entry<Identifier, Identifier> entry : aliases.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            Identifier alias = entry.getKey();
            Identifier target = entry.getValue();
            String fileSha = hashFromBlobPath(target.getPath());
            String fullTarget = target.getNamespace().equals(namespace)
                    ? "assets/" + namespace + "/" + target.getPath()
                    : target.toString();
            String logicalPath = alias.getPath();
            if (logicalPath.startsWith("textures/")) {
                logicalPath = logicalPath.substring("textures/".length());
            }
            logicalPath = logicalPath.substring(0, logicalPath.length() - ".png".length());
            json.append("{\"file_sha256\":\"").append(fileSha)
                    .append("\",\"logical_id\":\"")
                    .append(alias.getNamespace()).append(':').append(logicalPath)
                    .append("\",\"mcmeta_present\":").append(mcmetaPresent)
                    .append(",\"mcmeta_sha256\":null,\"path\":\"").append(alias.getPath())
                    .append("\",\"role\":\"albedo\",\"source\":\"test\",\"target\":\"")
                    .append(fullTarget)
                    .append("\",\"tier\":\"").append(tier).append("\"}");
            blobs.add(target);
        }

        json.append("],\"blobs\":[");
        first = true;
        for (Identifier blob : blobs) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"file_sha256\":\"").append(hashFromBlobPath(blob.getPath()))
                    .append("\",\"target\":\"assets/").append(namespace).append('/')
                    .append(blob.getPath()).append("\"}");
        }
        return json.append("]}").toString();
    }

    private static String hashFromBlobPath(String path) {
        String prefix = "texture_blobs/";
        String suffix = ".png";
        if (path.startsWith(prefix) && path.endsWith(suffix)) {
            return path.substring(prefix.length(), path.length() - suffix.length());
        }
        return "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    }

    record AliasFixture(FakePack pack, Identifier alias, Identifier blob) {
    }

    static class FakePack implements ResourcePack {
        private final String name;
        private final Map<Identifier, byte[]> resources = new LinkedHashMap<>();
        private final Map<Identifier, Integer> supplierReads = new LinkedHashMap<>();
        private int closeCount;

        FakePack(String name) {
            this.name = name;
        }

        void put(Identifier id, String value) {
            putBytes(id, bytes(value));
        }

        void putBytes(Identifier id, byte[] value) {
            resources.put(id, value.clone());
        }

        void resetSupplierReads() {
            supplierReads.clear();
        }

        int supplierReads(Identifier id) {
            return supplierReads.getOrDefault(id, 0);
        }

        int closeCount() {
            return closeCount;
        }

        @Override
        public InputSupplier<InputStream> openRoot(String... segments) {
            return null;
        }

        @Override
        public InputSupplier<InputStream> open(ResourceType type, Identifier id) {
            if (type != ResourceType.CLIENT_RESOURCES) {
                return null;
            }
            byte[] value = resources.get(id);
            if (value == null) {
                return null;
            }
            return () -> {
                supplierReads.merge(id, 1, Integer::sum);
                return new ByteArrayInputStream(value);
            };
        }

        @Override
        public void findResources(
                ResourceType type,
                String namespace,
                String prefix,
                ResultConsumer consumer
        ) {
            if (type != ResourceType.CLIENT_RESOURCES) {
                return;
            }
            resources.entrySet().stream()
                    .filter(entry -> entry.getKey().getNamespace().equals(namespace))
                    .filter(entry -> matchesPrefix(entry.getKey().getPath(), prefix))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> consumer.accept(
                            entry.getKey(),
                            () -> {
                                supplierReads.merge(entry.getKey(), 1, Integer::sum);
                                return new ByteArrayInputStream(entry.getValue());
                            }
                    ));
        }

        @Override
        public Set<String> getNamespaces(ResourceType type) {
            if (type != ResourceType.CLIENT_RESOURCES) {
                return Set.of();
            }
            Set<String> namespaces = new LinkedHashSet<>();
            resources.keySet().forEach(id -> namespaces.add(id.getNamespace()));
            return namespaces;
        }

        @Override
        public <T> T parseMetadata(ResourceMetadataReader<T> metaReader) {
            return null;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    static final class FakeModPack extends FakePack implements ModResourcePack {
        FakeModPack(String name) {
            super(name);
        }

        @Override
        public ModMetadata getFabricModMetadata() {
            return null;
        }
    }

    static final class NullStreamPack extends FakePack {
        private final Set<String> namespaces;
        private int openCount;

        NullStreamPack(String name, Set<String> namespaces) {
            super(name);
            this.namespaces = Set.copyOf(namespaces);
        }

        int openCount() {
            return openCount;
        }

        @Override
        public InputSupplier<InputStream> open(ResourceType type, Identifier id) {
            if (type != ResourceType.CLIENT_RESOURCES) {
                return null;
            }
            openCount++;
            return () -> null;
        }

        @Override
        public Set<String> getNamespaces(ResourceType type) {
            return type == ResourceType.CLIENT_RESOURCES ? namespaces : Set.of();
        }
    }

    static final class FakeGroupPack extends GroupResourcePack {
        FakeGroupPack(ResourcePack... packs) {
            super(ResourceType.CLIENT_RESOURCES, new ArrayList<>(List.of(packs)));
        }

        @Override
        public InputSupplier<InputStream> openRoot(String... segments) {
            return null;
        }

        @Override
        public <T> T parseMetadata(ResourceMetadataReader<T> metaReader) {
            return null;
        }

        @Override
        public String getName() {
            return "fake-group";
        }
    }

    private static boolean matchesPrefix(String path, String prefix) {
        return prefix.isEmpty() || path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
