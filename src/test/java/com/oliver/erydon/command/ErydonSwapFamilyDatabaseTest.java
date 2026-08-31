package com.oliver.erydon.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.oliver.erydon.Erydon;
import com.oliver.erydon.migration.ErydonIdMigration;
import com.oliver.erydon.util.ErydonIdNaming;
import net.minecraft.block.Block;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErydonSwapFamilyDatabaseTest {
    private static final Set<String> EXPECTED_MATERIAL_GROUPS = Set.of(
            "aganite_family",
            "aterzon_family",
            "borealis_family",
            "brectite_family",
            "calacattum_family",
            "chalstrom_family",
            "chrysonyx_family",
            "etruscus_family",
            "gelastrum_family",
            "glacium_family",
            "hesperion_family",
            "imperium_family",
            "kelastrion_family",
            "kylorion_family",
            "latmion_family",
            "laurentium_family",
            "mielonyx_family",
            "nerium_family",
            "noxoplis_family",
            "porphyros_family",
            "portorium_family",
            "psamatheon_family",
            "rosinium_family",
            "sanguenite_family",
            "selenephos_family",
            "solistra_family",
            "striatus_family"
    );

    @Test
    void catalogsEveryMaterialGroupIncludingChalstrom() {
        Set<String> materialGroups = ErydonSwapFamilyDatabase.canonicalKeys().stream()
                .filter(key -> key.endsWith("_family"))
                .collect(Collectors.toSet());

        assertEquals(EXPECTED_MATERIAL_GROUPS, materialGroups);
        assertTrue(ErydonSwapFamilyDatabase.findFamily("chalstrom_family").isPresent());
    }

    @Test
    void everyMaterialGroupIsBackedByCurrentCanonicalBlockstates() throws IOException {
        Set<String> blockPaths = blockstatePaths();
        NavigableSet<String> availableSources = ErydonSwapFamilyDatabase.sourceKeys(blockPaths);

        assertTrue(availableSources.containsAll(EXPECTED_MATERIAL_GROUPS));
        assertTrue(availableSources.contains("chalstrom_family"));
    }

    @Test
    void materialGroupTargetsOnlyOfferCompleteLiveCounterparts() throws IOException {
        Set<String> blockPaths = blockstatePaths();

        for (String source : EXPECTED_MATERIAL_GROUPS) {
            NavigableSet<String> targets =
                    ErydonSwapFamilyDatabase.targetKeysForSource(source, blockPaths);
            assertFalse(targets.isEmpty(), source);
            assertTrue(targets.stream().allMatch(key -> key.endsWith("_family")), source);
            assertFalse(targets.contains(source), source);
        }

        NavigableSet<String> chalstromTargets =
                ErydonSwapFamilyDatabase.targetKeysForSource("chalstrom_family", blockPaths);
        assertEquals(23, chalstromTargets.size());
        assertTrue(chalstromTargets.stream().allMatch(key -> key.endsWith("_family")));
        assertTrue(chalstromTargets.contains("aganite_family"));
        assertFalse(chalstromTargets.contains("chalstrom_family"));
        assertFalse(chalstromTargets.contains("kelastrion_family"));
        assertFalse(chalstromTargets.contains("latmion_family"));
        assertFalse(chalstromTargets.contains("psamatheon_family"));

        assertEquals(Set.of("gelastrum_family", "mielonyx_family", "selenephos_family"),
                ErydonSwapFamilyDatabase.targetKeysForSource("borealis_family", blockPaths));
    }

    @Test
    void sourceOverlayShapesSwapAcrossCompleteMaterialFamilies() {
        ErydonSwapFamilyDatabase.FamilySpec target =
                ErydonSwapFamilyDatabase.findFamily("aganite_family").orElseThrow();
        for (String path : Set.of(
                "nerium_trim_bronze_layer_multiface",
                "nerium_trim_silver_slope_vertical_shallow_narrow",
                "nerium_guilloche_bronze_stairs_shallow_top",
                "nerium_quatrefoil_silver_slope_steep_lower",
                "nerium_rosette_bronze_stairs")) {
            ErydonSwapFamilyDatabase.FamilyMatch match = ErydonSwapFamilyDatabase
                    .match(new Identifier(Erydon.MOD_ID, path))
                    .orElseThrow(() -> new AssertionError("Overlay shape was not matched: " + path));
            assertEquals(path.replaceFirst("^nerium_", "aganite_"), match.targetPath(target), path);
        }
    }

    @Test
    void agedFamiliesRoundTripEveryCanonicalAgedBlockstate() throws IOException {
        Set<String> agedPaths = blockstatePaths().stream()
                .filter(ErydonIdNaming::isAged)
                .collect(Collectors.toCollection(TreeSet::new));

        assertTrue(agedPaths.size() > 1_000);
        for (String path : agedPaths) {
            ErydonSwapFamilyDatabase.FamilyMatch match = ErydonSwapFamilyDatabase
                    .match(new Identifier(Erydon.MOD_ID, path))
                    .orElseThrow(() -> new AssertionError("Aged block was not matched: " + path));
            assertTrue(match.family().canonicalKey().endsWith("_aged"), path);
            assertEquals(path, match.targetPath(match.family()), path);
            assertFalse(path.endsWith("_aged"), path);
        }
    }

    @Test
    void publishedSuffixAgedAliasesResolveToCanonicalTargetIds() {
        ErydonSwapFamilyDatabase.FamilySpec source =
                ErydonSwapFamilyDatabase.findFamily("aganite_aged").orElseThrow();
        ErydonSwapFamilyDatabase.FamilySpec target =
                ErydonSwapFamilyDatabase.findFamily("chalstrom_aged").orElseThrow();

        String oldBlockPath = "aganite_block_aged";
        assertEquals("aganite_aged_block", ErydonIdMigration.canonicalPath(oldBlockPath));
        assertEquals("chalstrom_aged_block", ErydonSwapFamilyDatabase
                .match(new Identifier(Erydon.MOD_ID, oldBlockPath), source)
                .orElseThrow()
                .targetPath(target));

        String oldByzantinePath = "aganite_cornice_guilloche_aged";
        assertEquals("aganite_aged_cornice_byzantine", ErydonIdMigration.canonicalPath(oldByzantinePath));
        assertEquals("chalstrom_aged_cornice_byzantine", ErydonSwapFamilyDatabase
                .match(new Identifier(Erydon.MOD_ID, oldByzantinePath), source)
                .orElseThrow()
                .targetPath(target));
    }

    @Test
    void friendlyCommandNamesRoundTripWithoutUnderscores() throws Exception {
        assertEquals("Chalstrom Family", ErydonSwapFamilyDatabase.displayName("chalstrom_family"));
        assertEquals("\"Chalstrom Family\"", ErydonSwapFamilyDatabase.commandSuggestion("chalstrom_family"));
        assertEquals("chalstrom_family", ErydonSwapCommand.normalizeFamilyKey("Chalstrom Family"));
        assertEquals("chalstrom_family", ErydonSwapCommand.normalizeFamilyKey("\"Chalstrom Family\""));
        assertEquals("chalstrom_family", ErydonSwapCommand.normalizeFamilyKey("chalstrom-family"));
        assertEquals("chalstrom_family", ErydonSwapCommand.normalizeFamilyKey("erydon:chalstrom_family"));

        assertCommandParses("swap chunk \"Chalstrom Family\" \"Aganite Family\"");
        assertCommandParses("swap chunk chalstrom_family aganite_family");
    }

    @Test
    void materialSwapKeepsForcedArchitecturalStatesInsteadOfRebuildingThem() {
        ErydonSwapFamilyDatabase.FamilySpec source =
                ErydonSwapFamilyDatabase.findFamily("aganite_family").orElseThrow();
        ErydonSwapFamilyDatabase.FamilySpec target =
                ErydonSwapFamilyDatabase.findFamily("etruscus_family").orElseThrow();

        assertEquals("etruscus_cornice_georgian", ErydonSwapFamilyDatabase
                .match(new Identifier(Erydon.MOD_ID, "aganite_cornice_georgian"), source, target)
                .orElseThrow()
                .targetPath(target));
        assertEquals("etruscus_ceiling_coffered_georgian_black_small", ErydonSwapFamilyDatabase
                .match(new Identifier(Erydon.MOD_ID, "aganite_ceiling_coffered_georgian_black_small"), source, target)
                .orElseThrow()
                .targetPath(target));
        assertEquals(Block.NOTIFY_LISTENERS | Block.FORCE_STATE, ErydonSwapCommand.materialSwapFlags());
    }

    @Test
    void materialFamiliesSwapCompanionBlocksInsideTheirOwnNamespaces() {
        ErydonSwapFamilyDatabase.FamilySpec source =
                ErydonSwapFamilyDatabase.findFamily("aganite_family").orElseThrow();
        ErydonSwapFamilyDatabase.FamilySpec target =
                ErydonSwapFamilyDatabase.findFamily("etruscus_family").orElseThrow();

        assertEquals(
                new Identifier(ErydonSwapFamilyDatabase.DAEDALON_MOD_ID, "etruscus_athena_statue"),
                ErydonSwapFamilyDatabase.match(
                                new Identifier(ErydonSwapFamilyDatabase.DAEDALON_MOD_ID, "aganite_athena_statue"),
                                source,
                                target)
                        .orElseThrow()
                        .targetId(target));
        assertEquals(
                new Identifier(ErydonSwapFamilyDatabase.THEMELIOS_MOD_ID, "etruscus_stairs"),
                ErydonSwapFamilyDatabase.match(
                                new Identifier(ErydonSwapFamilyDatabase.THEMELIOS_MOD_ID, "aganite_stairs"),
                                source,
                                target)
                        .orElseThrow()
                        .targetId(target));
    }

    @Test
    void companionAgedIdsAndDaedalonSpartanOrderingRoundTrip() {
        ErydonSwapFamilyDatabase.FamilySpec source =
                ErydonSwapFamilyDatabase.findFamily("aganite_aged").orElseThrow();
        ErydonSwapFamilyDatabase.FamilySpec target =
                ErydonSwapFamilyDatabase.findFamily("etruscus_aged").orElseThrow();

        assertEquals(
                new Identifier(ErydonSwapFamilyDatabase.THEMELIOS_MOD_ID, "etruscus_aged_cylinder_small"),
                ErydonSwapFamilyDatabase.match(
                                new Identifier(
                                        ErydonSwapFamilyDatabase.THEMELIOS_MOD_ID,
                                        "aganite_aged_cylinder_small"),
                                source,
                                target)
                        .orElseThrow()
                        .targetId(target));
        assertEquals(
                new Identifier(
                        ErydonSwapFamilyDatabase.DAEDALON_MOD_ID,
                        "statue_spartan_promachos_etruscus_aged"),
                ErydonSwapFamilyDatabase.match(
                                new Identifier(
                                        ErydonSwapFamilyDatabase.DAEDALON_MOD_ID,
                                        "statue_spartan_promachos_aganite_aged"),
                                source,
                                target)
                        .orElseThrow()
                        .targetId(target));

        ErydonSwapFamilyDatabase.FamilySpec allErydon = ErydonSwapFamilyDatabase
                .findFamily(ErydonSwapFamilyDatabase.ALL_ERYDON_BLOCKS_KEY)
                .orElseThrow();
        assertTrue(ErydonSwapFamilyDatabase.match(
                new Identifier(ErydonSwapFamilyDatabase.DAEDALON_MOD_ID, "aganite_aged_athena_statue"),
                allErydon,
                target).isEmpty());
    }

    @Test
    void targetAvailabilityRequiresCompanionCounterpartsThatAreActuallyInstalled() {
        Set<Identifier> completeRegistry = Set.of(
                new Identifier(Erydon.MOD_ID, "aganite_block"),
                new Identifier(Erydon.MOD_ID, "etruscus_block"),
                new Identifier(ErydonSwapFamilyDatabase.DAEDALON_MOD_ID, "aganite_athena_statue"),
                new Identifier(ErydonSwapFamilyDatabase.DAEDALON_MOD_ID, "etruscus_athena_statue"),
                new Identifier(
                        ErydonSwapFamilyDatabase.DAEDALON_MOD_ID,
                        "statue_spartan_promachos_aganite_aged"),
                new Identifier(
                        ErydonSwapFamilyDatabase.DAEDALON_MOD_ID,
                        "statue_spartan_promachos_etruscus_aged"),
                new Identifier(ErydonSwapFamilyDatabase.THEMELIOS_MOD_ID, "aganite_aged_cylinder_small"),
                new Identifier(ErydonSwapFamilyDatabase.THEMELIOS_MOD_ID, "etruscus_aged_cylinder_small"),
                new Identifier("minecraft", "stone")
        );

        assertEquals(
                Set.of("etruscus_family"),
                ErydonSwapFamilyDatabase.targetKeysForSourceIds("aganite_family", completeRegistry));

        Set<Identifier> incompleteRegistry = new TreeSet<>(completeRegistry);
        incompleteRegistry.remove(new Identifier(
                ErydonSwapFamilyDatabase.DAEDALON_MOD_ID,
                "statue_spartan_promachos_etruscus_aged"));
        assertFalse(ErydonSwapFamilyDatabase
                .targetKeysForSourceIds("aganite_family", incompleteRegistry)
                .contains("etruscus_family"));
    }

    private static void assertCommandParses(String command) {
        CommandDispatcher<ServerCommandSource> dispatcher = new CommandDispatcher<>();
        dispatcher.register(ErydonSwapCommand.createCommand());
        ParseResults<ServerCommandSource> parsed = dispatcher.parse(command, null);
        assertFalse(parsed.getReader().canRead(), command + " left unread input: " + parsed.getReader().getRemaining());
        assertTrue(parsed.getExceptions().isEmpty(), command + " failed: " + parsed.getExceptions());
    }

    private static Set<String> blockstatePaths() throws IOException {
        Path root = Path.of("src", "main", "resources", "assets", Erydon.MOD_ID, "blockstates");
        try (Stream<Path> files = Files.list(root)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> path.getFileName().toString())
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .collect(Collectors.toUnmodifiableSet());
        }
    }
}
