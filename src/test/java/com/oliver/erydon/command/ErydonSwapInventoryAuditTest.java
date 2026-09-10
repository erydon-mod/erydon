package com.oliver.erydon.command;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Exhaustive ID coverage, with companion snapshots so CI needs no private checkout. */
class ErydonSwapInventoryAuditTest {
    private static final Path REPORTS = Path.of("build", "reports", "erydon-swap");

    @Test
    void everyMaterialBlockAndOverlayHasAnExactRoundTrip() throws IOException {
        Set<Identifier> ids = inventory();
        List<String> rows = new ArrayList<>(List.of("block_id\tfinish_source\tmaterial_source\tplain_counterpart\taged_counterpart"));
        List<String> excluded = new ArrayList<>();
        for (Identifier id : new TreeSet<>(ids)) {
            var possible = ErydonSwapFamilyDatabase.match(id);
            if (possible.isEmpty()) {
                assertTrue(id.getNamespace().equals("erydon") && isNonMaterialErydonBlock(id.getPath()), id.toString());
                excluded.add(id.toString());
                continue;
            }
            var match = possible.orElseThrow();
            assertEquals(id, match.targetId(match.family()), "Finish round trip: " + id);
            String material = match.family().canonicalKey().split("_")[0];
            var group = ErydonSwapFamilyDatabase.findFamily(material + "_family").orElseThrow();
            assertEquals(id, match.targetId(group), "Material round trip: " + id);
            assertEquals(id, ErydonSwapFamilyDatabase.match(id, group).orElseThrow().targetId(group));
            var all = ErydonSwapFamilyDatabase.findFamily("all_family_blocks").orElseThrow();
            assertEquals(id, ErydonSwapFamilyDatabase.match(id, all).orElseThrow().targetId(group));

            var plain = ErydonSwapFamilyDatabase.findFamily(material).orElseThrow();
            Identifier plainId = match.targetId(plain);
            if (ids.contains(plainId)) {
                assertEquals(id, ErydonSwapFamilyDatabase.match(plainId).orElseThrow().targetId(match.family()));
            }
            var aged = ErydonSwapFamilyDatabase.findFamily(material + "_aged");
            Identifier agedId = aged.map(match::targetId).orElse(null);
            if (agedId != null && ids.contains(agedId)) {
                assertEquals(id, ErydonSwapFamilyDatabase.match(agedId).orElseThrow().targetId(match.family()));
            }
            rows.add(id + "\t" + ErydonSwapFamilyDatabase.displayName(match.family().canonicalKey())
                    + "\t" + ErydonSwapFamilyDatabase.displayName(group.canonicalKey())
                    + "\t" + (ids.contains(plainId) ? plainId : "unavailable")
                    + "\t" + (agedId != null && ids.contains(agedId) ? agedId : "unavailable"));
        }
        assertEquals(43, excluded.size(), "Review non-material blocks when the inventory changes");
        Files.createDirectories(REPORTS);
        Files.write(REPORTS.resolve("blocks.tsv"), rows);
        Files.write(REPORTS.resolve("non-material-blocks.txt"), excluded);
    }

    @Test
    void everySuggestedDestinationHasARealCounterpartAndPartialDestinationsRemainVisible() throws IOException {
        Set<Identifier> ids = inventory();
        var index = ErydonSwapFamilyDatabase.AvailabilityIndex.fromIds(ids);
        List<String> rows = new ArrayList<>(List.of("source\ttarget\tmapped_forms\tmissing_forms\talready_target_forms"));
        List<String> families = new ArrayList<>(List.of("family\terydon\tdaedalon\tthemelios"));
        for (String key : ErydonSwapFamilyDatabase.canonicalKeys()) {
            var source = ErydonSwapFamilyDatabase.findFamily(key).orElseThrow();
            var forms = index.formsFor(source);
            if (forms.isEmpty()) {
                continue;
            }
            families.add(ErydonSwapFamilyDatabase.displayName(key) + "\t"
                    + forms.stream().filter(f -> f.namespace().equals("erydon")).count() + "\t"
                    + forms.stream().filter(f -> f.namespace().equals("daedalon")).count() + "\t"
                    + forms.stream().filter(f -> f.namespace().equals("themelios")).count());
            var suggestions = ErydonSwapFamilyDatabase.targetKeysForSource(key, index);
            for (String targetKey : ErydonSwapFamilyDatabase.canonicalKeys()) {
                if (key.equals(targetKey)) {
                    continue;
                }
                var target = ErydonSwapFamilyDatabase.findFamily(targetKey).orElseThrow();
                int mapped = 0;
                int missing = 0;
                int unchanged = 0;
                for (var form : forms) {
                    Identifier targetId = form.targetId(target);
                    Identifier original = form.targetId(source);
                    if (targetId.equals(original)) {
                        unchanged++;
                    } else if (ids.contains(targetId)) {
                        mapped++;
                        var reverse = ErydonSwapFamilyDatabase.match(targetId, target).orElseThrow();
                        // Switching to a specific finish can intentionally collapse several source finishes.
                        if (!source.isMaterialGroup() && !target.isMaterialGroup()) {
                            assertEquals(original, reverse.targetId(source), original + " -> " + targetId);
                        }
                    } else {
                        missing++;
                    }
                }
                assertEquals(mapped > 0, suggestions.contains(targetKey), key + " -> " + targetKey);
                if (mapped > 0) {
                    rows.add(ErydonSwapFamilyDatabase.displayName(key) + "\t" + ErydonSwapFamilyDatabase.displayName(targetKey)
                            + "\t" + mapped + "\t" + missing + "\t" + unchanged);
                }
            }
        }
        Files.createDirectories(REPORTS);
        Files.write(REPORTS.resolve("destinations.tsv"), rows);
        Files.write(REPORTS.resolve("families.tsv"), families);
    }

    @Test
    void companionSnapshotMatchesEverySuppliedLiveCheckout() throws IOException {
        Set<Identifier> fixture = companionIds();
        assertEquals(4425, fixture.stream().filter(id -> id.getNamespace().equals("daedalon")).count());
        assertEquals(2940, fixture.stream().filter(id -> id.getNamespace().equals("themelios")).count());
        for (String namespace : List.of("daedalon", "themelios")) {
            String root = System.getenv("ERYDON_SWAP_" + namespace.toUpperCase(java.util.Locale.ROOT) + "_ROOT");
            if (root != null && !root.isBlank()) {
                assertLiveMatches(fixture, namespace, Path.of(root));
            }
        }
        String modernRoot = System.getenv("ERYDON_SWAP_THEMELIOS_MODERN_ROOT");
        if (modernRoot != null && !modernRoot.isBlank()) {
            assertLiveMatches(fixture, "themelios", Path.of(modernRoot));
        }
    }

    private static void assertLiveMatches(Set<Identifier> fixture, String namespace, Path root) throws IOException {
        Set<Identifier> actual = blockstates(root, namespace);
        actual.remove(new Identifier(namespace, "fountain_basin_part"));
        actual.remove(new Identifier(namespace, "monopteros_part"));
        assertEquals(fixture.stream().filter(id -> id.getNamespace().equals(namespace)).collect(Collectors.toSet()), actual);
    }

    private static Set<Identifier> inventory() throws IOException {
        Set<Identifier> ids = companionIds();
        ids.addAll(blockstates(Path.of("."), "erydon"));
        return ids;
    }

    private static Set<Identifier> companionIds() throws IOException {
        Set<Identifier> ids = new HashSet<>();
        for (String line : Files.readAllLines(Path.of("src/test/resources/swap/companion-blocks.tsv"))) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\t");
            for (String material : fields[2].split(",")) {
                assertTrue(ids.add(new Identifier(fields[0], fields[1].replace("{material}", material))), line);
            }
        }
        return ids;
    }

    private static Set<Identifier> blockstates(Path root, String namespace) throws IOException {
        try (var paths = Files.list(root.resolve("src/main/resources/assets/" + namespace + "/blockstates"))) {
            return paths.filter(p -> p.toString().endsWith(".json"))
                    .map(p -> new Identifier(namespace, p.getFileName().toString().replaceFirst("\\.json$", "")))
                    .collect(Collectors.toSet());
        }
    }

    private static boolean isNonMaterialErydonBlock(String path) {
        return path.startsWith("glazing_") || path.startsWith("cover_")
                || path.startsWith("ceiling_coffered_") || path.equals("light_pendant_halo");
    }
}
