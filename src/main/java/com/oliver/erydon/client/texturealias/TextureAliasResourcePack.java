package com.oliver.erydon.client.texturealias;

import net.fabricmc.fabric.api.resource.ModResourcePack;
import net.fabricmc.fabric.impl.resource.loader.GroupResourcePack;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.metadata.ResourceMetadataReader;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class TextureAliasResourcePack implements ResourcePack {
    private static final Logger LOGGER = LoggerFactory.getLogger("Erydon/TextureAliases");
    private static final String DISABLE_PROPERTY = "erydon.texture_aliases.disable";
    private static final AtomicBoolean DISABLE_WARNING_LOGGED = new AtomicBoolean();
    public static final List<String> FAMILY_NAMESPACES =
            List.of("daedalon", "erydon", "minecraft", "themelios");

    private final ResourcePack delegate;
    private final String namespace;
    private final Map<String, String> aliases;

    protected TextureAliasResourcePack(ResourcePack delegate, String namespace, TextureAliasManifest manifest)
            throws IOException {
        this.delegate = delegate;
        this.namespace = namespace;
        this.aliases = manifest.aliases();

        Map<String, String> expectedHashesByTarget = new LinkedHashMap<>();
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            String expectedSha = manifest.fileSha256(alias.getKey());
            String previousSha = expectedHashesByTarget.putIfAbsent(alias.getValue(), expectedSha);
            if (previousSha != null && !previousSha.equals(expectedSha)) {
                throw new IOException("Conflicting hashes for texture alias target in pack '"
                        + delegate.getName() + "': " + alias.getValue());
            }
        }
        for (Map.Entry<String, String> target : expectedHashesByTarget.entrySet()) {
            Identifier targetId = new Identifier(namespace, target.getKey());
            InputSupplier<InputStream> supplier = delegate.open(ResourceType.CLIENT_RESOURCES, targetId);
            if (supplier == null) {
                throw new IOException("Dangling texture alias target in pack '" + delegate.getName()
                        + "': " + target.getKey());
            }
            byte[] bytes;
            try (InputStream input = supplier.get()) {
                bytes = input.readAllBytes();
            }
            String actualSha = sha256(bytes);
            if (!actualSha.equals(target.getValue())) {
                throw new IOException("Texture alias blob hash differs from manifest in pack '"
                        + delegate.getName() + "': " + target.getKey()
                        + " (" + actualSha + " != " + target.getValue() + ")");
            }
        }
    }

    public static ResourcePack wrap(ResourcePack pack, String namespace) {
        if (Boolean.getBoolean(DISABLE_PROPERTY)) {
            if (DISABLE_WARNING_LOGGED.compareAndSet(false, true)) {
                LOGGER.warn("Texture aliases are disabled by -D{}=true", DISABLE_PROPERTY);
            }
            return pack;
        }

        if (pack instanceof GroupResourcePack) {
            return pack;
        }
        if (findHandler(pack, namespace) != null) {
            return pack;
        }
        if (!pack.getNamespaces(ResourceType.CLIENT_RESOURCES).contains(namespace)) {
            return pack;
        }

        try {
            TextureAliasManifest manifest = TextureAliasManifest.load(pack, namespace);
            if (manifest == null || manifest.aliases().isEmpty()) {
                return pack;
            }
            TextureAliasResourcePack aliasPack = new TextureAliasResourcePack(pack, namespace, manifest);
            LOGGER.info("Loaded {} exact texture aliases for namespace {} from pack '{}'",
                    manifest.aliases().size(), namespace, pack.getName());
            return aliasPack;
        } catch (IOException | RuntimeException exception) {
            throw closeAndWrapFailure(pack, namespace, exception);
        }
    }

    public static ResourcePack wrapFamilyNamespaces(ResourcePack pack) {
        ResourcePack wrapped = pack;
        for (String namespace : FAMILY_NAMESPACES) {
            wrapped = wrap(wrapped, namespace);
        }
        return wrapped;
    }

    public static List<ResourcePack> wrapAll(List<ResourcePack> packs, String namespace) {
        List<ResourcePack> wrapped = new ArrayList<>(packs.size());
        int currentIndex = -1;
        try {
            for (int index = 0; index < packs.size(); index++) {
                currentIndex = index;
                wrapped.add(wrap(packs.get(index), namespace));
            }
            return List.copyOf(wrapped);
        } catch (RuntimeException exception) {
            for (int index = 0; index < packs.size(); index++) {
                if (index == currentIndex) {
                    continue;
                }
                try {
                    packs.get(index).close();
                } catch (RuntimeException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            }
            throw exception;
        }
    }

    public static List<ModResourcePack> wrapAllModPacks(
            List<ModResourcePack> packs,
            String namespace
    ) {
        List<ModResourcePack> wrapped = new ArrayList<>(packs.size());
        try {
            for (ModResourcePack pack : packs) {
                wrapped.add(wrapModPack(pack, namespace));
            }
            return List.copyOf(wrapped);
        } catch (RuntimeException exception) {
            for (ModResourcePack pack : packs) {
                try {
                    pack.close();
                } catch (RuntimeException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            }
            throw exception;
        }
    }

    public static List<ModResourcePack> wrapAllFamilyModPacks(
            List<ModResourcePack> packs
    ) {
        List<ModResourcePack> wrapped = packs;
        for (String namespace : FAMILY_NAMESPACES) {
            wrapped = wrapAllModPacks(wrapped, namespace);
        }
        return wrapped;
    }

    private static ModResourcePack wrapModPack(ModResourcePack pack, String namespace) {
        if (Boolean.getBoolean(DISABLE_PROPERTY)) {
            return pack;
        }
        if (findHandler(pack, namespace) != null) {
            return pack;
        }

        try {
            TextureAliasManifest manifest = TextureAliasManifest.load(pack, namespace);
            if (manifest == null || manifest.aliases().isEmpty()) {
                return pack;
            }
            ModAliasResourcePack aliasPack =
                    new ModAliasResourcePack(pack, namespace, manifest);
            LOGGER.info("Loaded {} exact texture aliases for namespace {} from Fabric mod pack '{}'",
                    manifest.aliases().size(), namespace, pack.getName());
            return aliasPack;
        } catch (IOException | RuntimeException exception) {
            throw wrapFailure(pack, namespace, exception);
        }
    }

    static TextureAliasResourcePack findHandler(ResourcePack pack, String namespace) {
        ResourcePack current = pack;
        while (current instanceof TextureAliasResourcePack aliasPack) {
            if (aliasPack.handlesNamespace(namespace)) {
                return aliasPack;
            }
            current = aliasPack.delegate;
        }
        return null;
    }

    boolean handlesNamespace(String candidateNamespace) {
        return namespace.equals(candidateNamespace);
    }

    InputSupplier<InputStream> openCanonical(String aliasPath) {
        String targetPath = aliases.get(aliasPath);
        if (targetPath == null) {
            return null;
        }
        return delegate.open(
                ResourceType.CLIENT_RESOURCES,
                new Identifier(namespace, targetPath)
        );
    }

    boolean hasPhysical(Identifier id) {
        return delegate.open(ResourceType.CLIENT_RESOURCES, id) != null;
    }

    String expectedFileSha256(String aliasPath) {
        String target = aliases.get(aliasPath);
        if (target == null || !target.startsWith("texture_blobs/")
                || !target.endsWith(".png")) {
            return null;
        }
        return target.substring("texture_blobs/".length(), target.length() - ".png".length());
    }

    @Override
    public InputSupplier<InputStream> openRoot(String... segments) {
        return delegate.openRoot(segments);
    }

    @Override
    public InputSupplier<InputStream> open(ResourceType type, Identifier id) {
        InputSupplier<InputStream> physical = delegate.open(type, id);
        if (physical != null || type != ResourceType.CLIENT_RESOURCES || !namespace.equals(id.getNamespace())) {
            return physical;
        }

        String targetPath = aliases.get(id.getPath());
        if (targetPath == null) {
            return null;
        }
        return delegate.open(type, new Identifier(namespace, targetPath));
    }

    @Override
    public void findResources(ResourceType type, String requestedNamespace, String prefix, ResultConsumer consumer) {
        if (type != ResourceType.CLIENT_RESOURCES || !namespace.equals(requestedNamespace)) {
            delegate.findResources(type, requestedNamespace, prefix, consumer);
            return;
        }

        Set<Identifier> physicalIds = new HashSet<>();
        delegate.findResources(type, requestedNamespace, prefix, (id, supplier) -> {
            physicalIds.add(id);
            consumer.accept(id, supplier);
        });

        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            Identifier aliasId = new Identifier(namespace, alias.getKey());
            if (!matchesPrefix(aliasId.getPath(), prefix)
                    || physicalIds.contains(aliasId)
                    || delegate.open(type, aliasId) != null) {
                continue;
            }

            InputSupplier<InputStream> target = delegate.open(
                    ResourceType.CLIENT_RESOURCES,
                    new Identifier(namespace, alias.getValue())
            );
            if (target == null) {
                throw new IllegalStateException("Texture alias target disappeared from pack '" + delegate.getName()
                        + "': " + alias.getKey() + " -> " + alias.getValue());
            }
            consumer.accept(aliasId, target);
        }
    }

    @Override
    public Set<String> getNamespaces(ResourceType type) {
        return delegate.getNamespaces(type);
    }

    @Override
    public <T> T parseMetadata(ResourceMetadataReader<T> metaReader) throws IOException {
        return delegate.parseMetadata(metaReader);
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public boolean isAlwaysStable() {
        return delegate.isAlwaysStable();
    }

    @Override
    public void close() {
        delegate.close();
    }

    private static boolean matchesPrefix(String path, String prefix) {
        return prefix.isEmpty() || path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static IllegalStateException closeAndWrapFailure(
            ResourcePack pack,
            String namespace,
            Exception cause
    ) {
        IllegalStateException failure = wrapFailure(pack, namespace, cause);
        try {
            pack.close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
        return failure;
    }

    private static IllegalStateException wrapFailure(
            ResourcePack pack,
            String namespace,
            Exception cause
    ) {
        return new IllegalStateException(
                "Invalid texture alias manifest for namespace " + namespace
                        + " in pack '" + pack.getName() + "'",
                cause
        );
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class ModAliasResourcePack
            extends TextureAliasResourcePack
            implements ModResourcePack {
        private final ModResourcePack modDelegate;

        private ModAliasResourcePack(
                ModResourcePack delegate,
                String namespace,
                TextureAliasManifest manifest
        ) throws IOException {
            super(delegate, namespace, manifest);
            this.modDelegate = delegate;
        }

        @Override
        public ModMetadata getFabricModMetadata() {
            return modDelegate.getFabricModMetadata();
        }
    }
}
