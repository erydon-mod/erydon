package com.oliver.erydon.client.texturealias;

import net.fabricmc.fabric.api.resource.ModResourcePack;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.LifecycledResourceManagerImpl;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.oliver.erydon.client.texturealias.TextureAliasTestSupport.ALIAS;
import static com.oliver.erydon.client.texturealias.TextureAliasTestSupport.ALIAS_PATH;
import static com.oliver.erydon.client.texturealias.TextureAliasTestSupport.NAMESPACE;
import static com.oliver.erydon.client.texturealias.TextureAliasTestSupport.addManifest;
import static com.oliver.erydon.client.texturealias.TextureAliasTestSupport.blobId;
import static com.oliver.erydon.client.texturealias.TextureAliasTestSupport.bytes;
import static com.oliver.erydon.client.texturealias.TextureAliasTestSupport.id;
import static com.oliver.erydon.client.texturealias.TextureAliasTestSupport.linkedAliases;
import static com.oliver.erydon.client.texturealias.TextureAliasTestSupport.populateSingleAlias;
import static com.oliver.erydon.client.texturealias.TextureAliasTestSupport.singleAliasPack;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureAliasResourcePackTest {
    @Test
    void disablePropertyKeepsPhysicalPackUnwrapped() {
        TextureAliasTestSupport.AliasFixture fixture =
                singleAliasPack("physical-fallback", "canonical");
        String property = "erydon.texture_aliases.disable";
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "true");
            assertSame(
                    fixture.pack(),
                    TextureAliasResourcePack.wrap(fixture.pack(), NAMESPACE)
            );
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
            fixture.pack().close();
        }
        assertEquals(1, fixture.pack().closeCount());
    }

    @Test
    void resolvesAliasAndEnumeratesLogicalPath() throws Exception {
        TextureAliasTestSupport.AliasFixture fixture =
                singleAliasPack("base", "canonical");
        List<ResourcePack> wrapped =
                TextureAliasResourcePack.wrapAll(List.of(fixture.pack()), NAMESPACE);

        try (LifecycledResourceManagerImpl manager =
                     new LifecycledResourceManagerImpl(ResourceType.CLIENT_RESOURCES, wrapped)) {
            assertArrayEquals(bytes("canonical"), read(manager.getResource(ALIAS).orElseThrow()));
            Resource enumerated = manager.findResources("textures", ignored -> true).get(ALIAS);
            assertNotNull(enumerated);
            assertArrayEquals(bytes("canonical"), read(enumerated));
        }
    }

    @Test
    void resolvesAndEnumeratesOptifineAliasPath() throws Exception {
        String namespace = "minecraft";
        String aliasPath = "optifine/ctm/glacium_aged/0_n.png";
        Identifier alias = id(namespace, aliasPath);
        TextureAliasTestSupport.FakePack pack =
                new TextureAliasTestSupport.FakePack("optifine");
        populateSingleAlias(pack, namespace, aliasPath, "normal-bytes");
        ResourcePack wrapped = TextureAliasResourcePack.wrap(pack, namespace);

        try (LifecycledResourceManagerImpl manager =
                     new LifecycledResourceManagerImpl(
                             ResourceType.CLIENT_RESOURCES,
                             List.of(wrapped)
                     )) {
            assertArrayEquals(
                    bytes("normal-bytes"),
                    read(manager.getResource(alias).orElseThrow())
            );
            Resource enumerated = manager.findResources(
                    "optifine/ctm/glacium_aged",
                    ignored -> true
            ).get(alias);
            assertNotNull(enumerated);
            assertArrayEquals(bytes("normal-bytes"), read(enumerated));
        }
    }

    @Test
    void physicalFileInSamePackWinsForLookupAndEnumeration() throws Exception {
        TextureAliasTestSupport.AliasFixture fixture =
                singleAliasPack("base", "canonical");
        fixture.pack().put(ALIAS, "physical");
        ResourcePack wrapped = TextureAliasResourcePack.wrap(fixture.pack(), NAMESPACE);

        try (LifecycledResourceManagerImpl manager =
                     new LifecycledResourceManagerImpl(ResourceType.CLIENT_RESOURCES, List.of(wrapped))) {
            assertArrayEquals(bytes("physical"), read(manager.getResource(ALIAS).orElseThrow()));
            assertArrayEquals(
                    bytes("physical"),
                    read(manager.findResources("textures", ignored -> true).get(ALIAS))
            );
        }
    }

    @Test
    void higherPriorityPhysicalOverrideWinsForLookupAndEnumeration() throws Exception {
        TextureAliasTestSupport.AliasFixture fixture =
                singleAliasPack("base", "canonical");
        TextureAliasTestSupport.FakePack override =
                new TextureAliasTestSupport.FakePack("override");
        override.put(ALIAS, "override");

        List<ResourcePack> wrapped =
                TextureAliasResourcePack.wrapAll(List.of(fixture.pack(), override), NAMESPACE);
        try (LifecycledResourceManagerImpl manager =
                     new LifecycledResourceManagerImpl(ResourceType.CLIENT_RESOURCES, wrapped)) {
            assertWinner(manager.getResource(ALIAS).orElseThrow(), "override", "override");
            assertWinner(
                    manager.findResources("textures", ignored -> true).get(ALIAS),
                    "override",
                    "override"
            );
        }
    }

    @Test
    void higherPriorityAliasWinsOverLowerPhysicalForLookupAndEnumeration() throws Exception {
        TextureAliasTestSupport.FakePack lower =
                new TextureAliasTestSupport.FakePack("lower");
        lower.put(ALIAS, "lower-physical");
        TextureAliasTestSupport.AliasFixture higher =
                singleAliasPack("higher", "higher-canonical");

        List<ResourcePack> wrapped =
                TextureAliasResourcePack.wrapAll(List.of(lower, higher.pack()), NAMESPACE);
        try (LifecycledResourceManagerImpl manager =
                     new LifecycledResourceManagerImpl(ResourceType.CLIENT_RESOURCES, wrapped)) {
            assertWinner(
                    manager.getResource(ALIAS).orElseThrow(),
                    "higher-canonical",
                    "higher"
            );
            assertWinner(
                    manager.findResources("textures", ignored -> true).get(ALIAS),
                    "higher-canonical",
                    "higher"
            );
        }
    }

    @Test
    void wrappingTheSameNamespaceTwiceIsIdempotent() {
        TextureAliasTestSupport.AliasFixture fixture =
                singleAliasPack("base", "canonical");
        ResourcePack once = TextureAliasResourcePack.wrap(fixture.pack(), NAMESPACE);
        ResourcePack twice = TextureAliasResourcePack.wrap(once, NAMESPACE);
        assertSame(once, twice);
        once.close();
        assertEquals(1, fixture.pack().closeCount());
    }

    @Test
    void supportsMultipleNamespacesWithoutDuplicatingAnExistingHandler() throws Exception {
        TextureAliasTestSupport.FakePack base =
                new TextureAliasTestSupport.FakePack("multi-namespace");
        populateSingleAlias(base, NAMESPACE, ALIAS_PATH, "erydon-canonical");
        Identifier daedalonAlias = id("daedalon", ALIAS_PATH);
        populateSingleAlias(base, "daedalon", ALIAS_PATH, "daedalon-canonical");

        ResourcePack erydonWrapped = TextureAliasResourcePack.wrap(base, NAMESPACE);
        ResourcePack bothWrapped = TextureAliasResourcePack.wrap(erydonWrapped, "daedalon");
        ResourcePack wrappedAgain = TextureAliasResourcePack.wrap(bothWrapped, NAMESPACE);

        assertNotSame(erydonWrapped, bothWrapped);
        assertSame(bothWrapped, wrappedAgain);
        assertNotNull(TextureAliasResourcePack.findHandler(bothWrapped, NAMESPACE));
        assertNotNull(TextureAliasResourcePack.findHandler(bothWrapped, "daedalon"));
        assertArrayEquals(
                bytes("erydon-canonical"),
                read(bothWrapped.open(ResourceType.CLIENT_RESOURCES, ALIAS))
        );
        assertArrayEquals(
                bytes("daedalon-canonical"),
                read(bothWrapped.open(ResourceType.CLIENT_RESOURCES, daedalonAlias))
        );

        bothWrapped.close();
        assertEquals(1, base.closeCount());
    }

    @Test
    void familyWrapperCoversEveryUnifiedPackNamespace() {
        TextureAliasTestSupport.FakePack base =
                new TextureAliasTestSupport.FakePack("family");
        for (String namespace : TextureAliasResourcePack.FAMILY_NAMESPACES) {
            populateSingleAlias(
                    base,
                    namespace,
                    ALIAS_PATH,
                    namespace + "-canonical"
            );
        }

        ResourcePack wrapped =
                TextureAliasResourcePack.wrapFamilyNamespaces(base);

        for (String namespace : TextureAliasResourcePack.FAMILY_NAMESPACES) {
            assertNotNull(TextureAliasResourcePack.findHandler(wrapped, namespace));
        }
        wrapped.close();
        assertEquals(1, base.closeCount());
    }

    @Test
    void familyWrapperSkipsNotReadyVirtualPackOutsideFamilyNamespaces() {
        TextureAliasTestSupport.NullStreamPack pack =
                new TextureAliasTestSupport.NullStreamPack(
                        "PFM-Runtime-RP",
                        Set.of("pfm")
                );

        assertSame(pack, TextureAliasResourcePack.wrapFamilyNamespaces(pack));
        assertEquals(0, pack.openCount());
        pack.close();
        assertEquals(1, pack.closeCount());
    }

    @Test
    void nullManifestStreamIsTreatedAsMissing() {
        TextureAliasTestSupport.NullStreamPack pack =
                new TextureAliasTestSupport.NullStreamPack(
                        "not-ready",
                        Set.of(NAMESPACE)
                );

        assertSame(pack, TextureAliasResourcePack.wrap(pack, NAMESPACE));
        assertEquals(1, pack.openCount());
        pack.close();
        assertEquals(1, pack.closeCount());
    }

    @Test
    void findResourcesRequiresAPathSegmentBoundary() {
        TextureAliasTestSupport.FakePack base =
                new TextureAliasTestSupport.FakePack("prefix-boundary");
        byte[] canonical = bytes("canonical");
        Identifier blob = blobId(NAMESPACE, canonical);
        Identifier sibling = id(NAMESPACE, "textures/blockade/not-a-block.png");
        base.putBytes(blob, canonical);
        LinkedHashMap<Identifier, Identifier> aliases = linkedAliases(ALIAS, blob);
        aliases.put(sibling, blob);
        addManifest(base, NAMESPACE, aliases, false);
        ResourcePack wrapped = TextureAliasResourcePack.wrap(base, NAMESPACE);
        Map<Identifier, InputSupplier<InputStream>> found = new LinkedHashMap<>();

        wrapped.findResources(
                ResourceType.CLIENT_RESOURCES,
                NAMESPACE,
                "textures/block",
                found::put
        );

        assertEquals(Set.of(ALIAS), found.keySet());
        wrapped.close();
    }

    @Test
    void validatesASharedCanonicalBlobOnlyOnceWhileWrapping() {
        TextureAliasTestSupport.FakePack base =
                new TextureAliasTestSupport.FakePack("shared-blob");
        byte[] canonical = bytes("canonical");
        Identifier blob = blobId(NAMESPACE, canonical);
        Identifier secondAlias = id(NAMESPACE, "textures/gui/shared.png");
        base.putBytes(blob, canonical);
        LinkedHashMap<Identifier, Identifier> aliases = linkedAliases(ALIAS, blob);
        aliases.put(secondAlias, blob);
        addManifest(base, NAMESPACE, aliases, false);
        base.resetSupplierReads();

        ResourcePack wrapped = TextureAliasResourcePack.wrap(base, NAMESPACE);

        assertEquals(1, base.supplierReads(blob));
        wrapped.close();
        assertEquals(1, base.closeCount());
    }

    @Test
    void fabricGroupRemainsOutermostAndHigherChildAliasWins() throws Exception {
        TextureAliasTestSupport.FakeModPack lower =
                new TextureAliasTestSupport.FakeModPack("lower-mod");
        lower.put(ALIAS, "lower-physical");
        TextureAliasTestSupport.FakeModPack higher =
                new TextureAliasTestSupport.FakeModPack("higher-mod");
        populateSingleAlias(higher, NAMESPACE, ALIAS_PATH, "higher-canonical");

        List<ModResourcePack> children =
                TextureAliasResourcePack.wrapAllModPacks(List.of(lower, higher), NAMESPACE);
        TextureAliasTestSupport.FakeGroupPack group =
                new TextureAliasTestSupport.FakeGroupPack(children.toArray(ResourcePack[]::new));

        assertSame(group, TextureAliasResourcePack.wrap(group, NAMESPACE));
        try (LifecycledResourceManagerImpl manager =
                     new LifecycledResourceManagerImpl(ResourceType.CLIENT_RESOURCES, List.of(group))) {
            assertWinner(
                    manager.getResource(ALIAS).orElseThrow(),
                    "higher-canonical",
                    "fake-group"
            );
        }
        assertEquals(1, lower.closeCount());
        assertEquals(1, higher.closeCount());
    }

    @Test
    void fabricGroupHigherChildPhysicalWinsOverLowerAlias() throws Exception {
        TextureAliasTestSupport.FakeModPack lower =
                new TextureAliasTestSupport.FakeModPack("lower-mod");
        populateSingleAlias(lower, NAMESPACE, ALIAS_PATH, "lower-canonical");
        TextureAliasTestSupport.FakeModPack higher =
                new TextureAliasTestSupport.FakeModPack("higher-mod");
        higher.put(ALIAS, "higher-physical");

        List<ModResourcePack> children =
                TextureAliasResourcePack.wrapAllModPacks(List.of(lower, higher), NAMESPACE);
        TextureAliasTestSupport.FakeGroupPack group =
                new TextureAliasTestSupport.FakeGroupPack(children.toArray(ResourcePack[]::new));

        try (LifecycledResourceManagerImpl manager =
                     new LifecycledResourceManagerImpl(ResourceType.CLIENT_RESOURCES, List.of(group))) {
            assertWinner(
                    manager.getResource(ALIAS).orElseThrow(),
                    "higher-physical",
                    "fake-group"
            );
        }
        assertEquals(1, lower.closeCount());
        assertEquals(1, higher.closeCount());
    }

    @Test
    void managerClosesWrappedDelegateExactlyOnce() {
        TextureAliasTestSupport.AliasFixture fixture =
                singleAliasPack("close-once", "canonical");
        ResourcePack wrapped = TextureAliasResourcePack.wrap(fixture.pack(), NAMESPACE);

        try (LifecycledResourceManagerImpl ignored =
                     new LifecycledResourceManagerImpl(
                             ResourceType.CLIENT_RESOURCES,
                             List.of(wrapped)
                     )) {
            assertEquals(0, fixture.pack().closeCount());
        }

        assertEquals(1, fixture.pack().closeCount());
    }

    @Test
    void invalidBlobHashFailsAndClosesPack() {
        TextureAliasTestSupport.FakePack base =
                new TextureAliasTestSupport.FakePack("bad-hash");
        Identifier declaredBlob = blobId(NAMESPACE, bytes("declared-contents"));
        base.put(declaredBlob, "different-contents");
        addManifest(base, NAMESPACE, linkedAliases(ALIAS, declaredBlob), false);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> TextureAliasResourcePack.wrap(base, NAMESPACE)
        );

        assertTrue(exception.getCause().getMessage().contains("hash"));
        assertEquals(1, base.closeCount());
    }

    @Test
    void metadataBackedAliasIsExcludedAndClosesPack() {
        TextureAliasTestSupport.AliasFixture fixture =
                singleAliasPack("metadata", "canonical");
        addManifest(
                fixture.pack(),
                NAMESPACE,
                linkedAliases(fixture.alias(), fixture.blob()),
                true
        );

        assertThrows(
                IllegalStateException.class,
                () -> TextureAliasResourcePack.wrap(fixture.pack(), NAMESPACE)
        );
        assertEquals(1, fixture.pack().closeCount());
    }

    @Test
    void danglingTargetFailsWithDiagnosticAndClosesPack() {
        TextureAliasTestSupport.FakePack base =
                new TextureAliasTestSupport.FakePack("broken");
        Identifier missingBlob = blobId(NAMESPACE, bytes("missing"));
        addManifest(base, NAMESPACE, linkedAliases(ALIAS, missingBlob), false);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> TextureAliasResourcePack.wrap(base, NAMESPACE)
        );

        assertTrue(exception.getMessage().contains("broken"));
        assertTrue(exception.getMessage().contains(NAMESPACE));
        assertEquals(1, base.closeCount());
    }

    @Test
    void crossNamespaceTargetIsRejectedAndClosesPack() {
        TextureAliasTestSupport.FakePack base =
                new TextureAliasTestSupport.FakePack("cross-namespace");
        Identifier otherBlob = blobId("other", bytes("canonical"));
        base.putBytes(otherBlob, bytes("canonical"));
        addManifest(base, NAMESPACE, linkedAliases(ALIAS, otherBlob), false);

        assertThrows(
                IllegalStateException.class,
                () -> TextureAliasResourcePack.wrap(base, NAMESPACE)
        );
        assertEquals(1, base.closeCount());
    }

    @Test
    void failedModPackBatchClosesEveryOriginalPackExactlyOnce() {
        TextureAliasTestSupport.FakeModPack valid =
                new TextureAliasTestSupport.FakeModPack("valid");
        populateSingleAlias(valid, NAMESPACE, ALIAS_PATH, "canonical");
        TextureAliasTestSupport.FakeModPack broken =
                new TextureAliasTestSupport.FakeModPack("broken");
        Identifier missingBlob = blobId(NAMESPACE, bytes("missing"));
        addManifest(broken, NAMESPACE, linkedAliases(ALIAS, missingBlob), false);

        assertThrows(
                IllegalStateException.class,
                () -> TextureAliasResourcePack.wrapAllModPacks(
                        List.of(valid, broken),
                        NAMESPACE
                )
        );

        assertEquals(1, valid.closeCount());
        assertEquals(1, broken.closeCount());
    }

    private static void assertWinner(
            Resource resource,
            String expectedContents,
            String expectedPackName
    ) throws IOException {
        assertNotNull(resource);
        assertArrayEquals(bytes(expectedContents), read(resource));
        assertEquals(expectedPackName, resource.getResourcePackName());
    }

    private static byte[] read(Resource resource) throws IOException {
        try (InputStream input = resource.getInputStream()) {
            return input.readAllBytes();
        }
    }

    private static byte[] read(InputSupplier<InputStream> supplier) throws IOException {
        try (InputStream input = supplier.get()) {
            return input.readAllBytes();
        }
    }
}
