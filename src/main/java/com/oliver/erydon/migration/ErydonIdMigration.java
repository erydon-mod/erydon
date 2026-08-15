package com.oliver.erydon.migration;

import com.oliver.erydon.Erydon;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Approved ERYDON 2.0 registry-ID migration and permanent compatibility aliases.
 */
public final class ErydonIdMigration {
    private static final String MANIFEST_RESOURCE = "/data/erydon/id_migration.tsv";
    private static final String MANIFEST_HEADER = String.join("\t",
            "source_row", "mode", "old_path", "canonical_path",
            "old_display_name", "canonical_display_name", "design_style",
            "publication_status", "reason", "review_status");
    private static final int EXPECTED_ENTRIES = 1775;
    private static final int EXPECTED_ALIASES = 1586;
    private static final int EXPECTED_DIRECT_RENAMES = 189;
    private static final int EXPECTED_QUATREFOIL_ALIASES = 48;

    public enum Mode {
        PERMANENT_ALIAS,
        DIRECT_RENAME
    }

    public record Entry(int sourceRow,
                        Mode mode,
                        Identifier oldId,
                        Identifier canonicalId,
                        String oldDisplayName,
                        String canonicalDisplayName,
                        String designStyle,
                        String publicationStatus,
                        String reason,
                        String reviewStatus) {
        public boolean permanentAlias() {
            return mode == Mode.PERMANENT_ALIAS;
        }

        public List<String> searchTerms() {
            LinkedHashSet<String> terms = new LinkedHashSet<>();
            if (permanentAlias()) {
                addIdTerms(terms, oldId, oldDisplayName);
            }
            addIdTerms(terms, canonicalId, canonicalDisplayName);

            String vocabulary = (oldId.getPath() + " " + canonicalId.getPath() + " "
                    + oldDisplayName + " " + canonicalDisplayName).toLowerCase(Locale.ROOT);
            if (vocabulary.contains("guilloche") || vocabulary.contains("byzantine")) {
                Collections.addAll(terms, "guilloche", "guilloche motif", "byzantine", "byzantine style");
            }
            if (vocabulary.contains("quatrefoil")) {
                Collections.addAll(terms, "quatrefoil", "quatrefoil motif");
            }
            if (vocabulary.contains("trim")) {
                Collections.addAll(terms, "trim", "trim inlay");
            }
            if (vocabulary.contains("rose") || vocabulary.contains("rosette")) {
                Collections.addAll(terms, "rose", "rosette", "rosette inlay");
            }
            if (canonicalDisplayName.toLowerCase(Locale.ROOT).contains("inlay")) {
                terms.add("inlay");
            }
            return List.copyOf(terms);
        }

        private static void addIdTerms(Set<String> terms, Identifier id, String displayName) {
            terms.add(id.toString());
            terms.add(id.getPath());
            terms.add(id.getPath().replace('_', ' '));
            if (displayName != null && !displayName.isBlank()) {
                terms.add(displayName);
            }
        }
    }

    private record Manifest(List<Entry> entries,
                            List<Entry> aliases,
                            List<Entry> directRenames,
                            Map<String, Entry> byOldPath,
                            Map<String, Entry> byCanonicalPath,
                            Map<Identifier, Entry> byCanonicalId) {
    }

    private static final Manifest MANIFEST = loadManifest();
    private static boolean aliasesRegistered;

    private ErydonIdMigration() {
    }

    public static List<Entry> entries() {
        return MANIFEST.entries();
    }

    public static List<Entry> aliases() {
        return MANIFEST.aliases();
    }

    public static List<Entry> directRenames() {
        return MANIFEST.directRenames();
    }

    /** Returns the approved canonical registry path, or the unchanged input. */
    public static String canonicalPath(String path) {
        Entry entry = MANIFEST.byOldPath().get(path);
        return entry == null ? path : entry.canonicalId().getPath();
    }

    /** Derives a canonical slice/post ID from either a legacy or canonical slab ID. */
    public static String canonicalSlitherPath(String slabPath, String variant) {
        String canonicalSlabPath = canonicalPath(slabPath);
        if (!canonicalSlabPath.endsWith("_slab")) {
            throw new IllegalArgumentException("Expected canonical slab path, got: " + canonicalSlabPath);
        }
        if (!variant.equals("slice_vertical")
                && !variant.equals("slice_horizontal")
                && !variant.equals("post")) {
            throw new IllegalArgumentException("Unexpected slither variant: " + variant);
        }
        return canonicalSlabPath.substring(0, canonicalSlabPath.length() - "_slab".length())
                + "_" + variant;
    }

    /**
     * Returns the stable internal resource path for a canonical block ID.
     * Published resources retain their old model/texture names; unpublished
     * direct renames already use canonical resource names.
     */
    public static String legacyResourcePath(String canonicalPath) {
        Entry entry = MANIFEST.byCanonicalPath().get(canonicalPath);
        return entry != null && entry.permanentAlias()
                ? entry.oldId().getPath()
                : canonicalPath;
    }

    public static Entry findByCanonicalId(Identifier canonicalId) {
        return MANIFEST.byCanonicalId().get(canonicalId);
    }

    public static List<String> searchTermsForCanonicalPath(String canonicalPath) {
        Entry entry = MANIFEST.byCanonicalPath().get(canonicalPath);
        return entry == null ? List.of() : entry.searchTerms();
    }

    public static synchronized void registerAliases() {
        if (aliasesRegistered) {
            verifyLiveRegistry();
            return;
        }

        for (Entry entry : MANIFEST.aliases()) {
            requireCanonicalRegistration(entry);
            if (Registries.BLOCK.containsId(entry.oldId()) || Registries.ITEM.containsId(entry.oldId())) {
                throw new IllegalStateException("Old ID is registered instead of aliased: " + entry.oldId());
            }
            Registries.BLOCK.addAlias(entry.oldId(), entry.canonicalId());
            Registries.ITEM.addAlias(entry.oldId(), entry.canonicalId());
        }
        aliasesRegistered = true;
        verifyLiveRegistry();
        Erydon.LOGGER.info("[id-migration] Registered {} permanent block/item alias pairs; "
                        + "verified {} unpublished direct renames, including {} Quatrefoil aliases.",
                MANIFEST.aliases().size(), MANIFEST.directRenames().size(),
                MANIFEST.aliases().stream().filter(entry -> entry.oldId().getPath().contains("quatrefoil")).count());
    }

    public static void verifyLiveRegistry() {
        for (Entry entry : MANIFEST.aliases()) {
            Block canonicalBlock = Registries.BLOCK.get(entry.canonicalId());
            Item canonicalItem = Registries.ITEM.get(entry.canonicalId());
            if (Registries.BLOCK.get(entry.oldId()) != canonicalBlock) {
                throw new IllegalStateException("Block alias did not resolve: " + entry.oldId());
            }
            if (Registries.ITEM.get(entry.oldId()) != canonicalItem) {
                throw new IllegalStateException("Item alias did not resolve: " + entry.oldId());
            }
            if (!entry.canonicalId().equals(Registries.BLOCK.getId(canonicalBlock))) {
                throw new IllegalStateException("Block alias replaced canonical ID: " + entry.canonicalId());
            }
            if (!entry.canonicalId().equals(Registries.ITEM.getId(canonicalItem))) {
                throw new IllegalStateException("Item alias replaced canonical ID: " + entry.canonicalId());
            }
            if (Registries.BLOCK.getIds().contains(entry.oldId())
                    || Registries.ITEM.getIds().contains(entry.oldId())) {
                throw new IllegalStateException("Alias leaked into canonical registry IDs: " + entry.oldId());
            }
        }

        for (Entry entry : MANIFEST.directRenames()) {
            requireCanonicalRegistration(entry);
            if (Registries.BLOCK.containsId(entry.oldId()) || Registries.ITEM.containsId(entry.oldId())) {
                throw new IllegalStateException("Unpublished direct rename has a legacy registration: "
                        + entry.oldId());
            }
        }
    }

    private static void requireCanonicalRegistration(Entry entry) {
        List<String> missing = new ArrayList<>();
        if (!Registries.BLOCK.containsId(entry.canonicalId())) {
            missing.add("block");
        }
        if (!Registries.ITEM.containsId(entry.canonicalId())) {
            missing.add("item");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing canonical " + String.join(" and ", missing)
                    + " registration for " + entry.canonicalId() + " (sheet row " + entry.sourceRow() + ")");
        }
    }

    private static Manifest loadManifest() {
        List<Entry> entries = new ArrayList<>(EXPECTED_ENTRIES);
        try (InputStream input = ErydonIdMigration.class.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing ID migration manifest: " + MANIFEST_RESOURCE);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String header = reader.readLine();
                if (!MANIFEST_HEADER.equals(header)) {
                    throw new IllegalStateException("Unexpected ID migration manifest header: " + header);
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] fields = line.split("\t", -1);
                    if (fields.length != 10) {
                        throw new IllegalStateException("Malformed ID migration row: " + line);
                    }
                    entries.add(new Entry(
                            Integer.parseInt(fields[0]),
                            Mode.valueOf(fields[1]),
                            id(fields[2]),
                            id(fields[3]),
                            fields[4], fields[5], fields[6], fields[7], fields[8], fields[9]
                    ));
                }
            }
        } catch (IOException | NumberFormatException exception) {
            throw new IllegalStateException("Failed to load ID migration manifest", exception);
        }

        return validateAndIndex(entries);
    }

    private static Manifest validateAndIndex(List<Entry> sourceEntries) {
        if (sourceEntries.size() != EXPECTED_ENTRIES) {
            throw new IllegalStateException("Expected " + EXPECTED_ENTRIES + " migration entries, found "
                    + sourceEntries.size());
        }

        List<Entry> aliases = new ArrayList<>();
        List<Entry> directRenames = new ArrayList<>();
        Map<String, Entry> byOldPath = new LinkedHashMap<>();
        Map<String, Entry> byCanonicalPath = new LinkedHashMap<>();
        Map<Identifier, Entry> byCanonicalId = new LinkedHashMap<>();
        for (Entry entry : sourceEntries) {
            if (!Erydon.MOD_ID.equals(entry.oldId().getNamespace())
                    || !Erydon.MOD_ID.equals(entry.canonicalId().getNamespace())) {
                throw new IllegalStateException("Migration IDs must stay in the erydon namespace: " + entry);
            }
            if (entry.oldId().equals(entry.canonicalId())) {
                throw new IllegalStateException("Migration does not change ID: " + entry.oldId());
            }
            if (!"Approved".equals(entry.reviewStatus())) {
                throw new IllegalStateException("Migration is not approved: " + entry.oldId());
            }
            if (byOldPath.put(entry.oldId().getPath(), entry) != null) {
                throw new IllegalStateException("Duplicate old migration ID: " + entry.oldId());
            }
            if (byCanonicalPath.put(entry.canonicalId().getPath(), entry) != null
                    || byCanonicalId.put(entry.canonicalId(), entry) != null) {
                throw new IllegalStateException("Duplicate canonical migration ID: " + entry.canonicalId());
            }
            (entry.permanentAlias() ? aliases : directRenames).add(entry);
        }

        Set<String> chains = new LinkedHashSet<>(byOldPath.keySet());
        chains.retainAll(byCanonicalPath.keySet());
        if (!chains.isEmpty()) {
            throw new IllegalStateException("Alias chains are forbidden: " + chains);
        }
        if (aliases.size() != EXPECTED_ALIASES || directRenames.size() != EXPECTED_DIRECT_RENAMES) {
            throw new IllegalStateException("Unexpected migration mode counts: " + aliases.size()
                    + " aliases, " + directRenames.size() + " direct renames");
        }
        long quatrefoil = aliases.stream()
                .filter(entry -> entry.oldId().getPath().contains("quatrefoil"))
                .count();
        if (quatrefoil != EXPECTED_QUATREFOIL_ALIASES) {
            throw new IllegalStateException("Expected " + EXPECTED_QUATREFOIL_ALIASES
                    + " Quatrefoil aliases, found " + quatrefoil);
        }

        return new Manifest(
                List.copyOf(sourceEntries), List.copyOf(aliases), List.copyOf(directRenames),
                Map.copyOf(byOldPath), Map.copyOf(byCanonicalPath), Map.copyOf(byCanonicalId)
        );
    }

    private static Identifier id(String path) {
        return new Identifier(Erydon.MOD_ID, path);
    }
}
