package com.oliver.erydon.command;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.migration.ErydonIdMigration;
import com.oliver.erydon.util.ErydonIdNaming;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

final class ErydonSwapFamilyDatabase {

    public static final String ALL_ERYDON_BLOCKS_KEY = "all_erydon_blocks";
    static final String DAEDALON_MOD_ID = "daedalon";
    static final String THEMELIOS_MOD_ID = "themelios";

    private static final Set<String> SUPPORTED_NAMESPACES = Set.of(
            Erydon.MOD_ID,
            DAEDALON_MOD_ID,
            THEMELIOS_MOD_ID
    );
    private static final String DAEDALON_SPARTAN_PREFIX = "statue_spartan_promachos_";
    private static final String DAEDALON_SPARTAN_CANONICAL_FORM = "_spartan_promachos_statue";
    private static final List<String> BASE_MATERIALS = List.of(
            "aganite",
            "aterzon",
            "borealis",
            "brectite",
            "calacattum",
            "chalstrom",
            "chrysonyx",
            "etruscus",
            "gelastrum",
            "glacium",
            "hesperion",
            "imperium",
            "kylorion",
            "laurentium",
            "mielonyx",
            "nerium",
            "noxoplis",
            "porphyros",
            "portorium",
            "rosinium",
            "sanguenite",
            "selenephos",
            "solistra",
            "striatus"
    );

    private static final List<String> BASE_ONLY_MATERIALS = List.of(
            "kelastrion",
            "kelastrion_aged",
            "kelastrion_ashlar",
            "kelastrion_rusticated",
            "kelastrion_rock",
            "latmion",
            "latmion_aged",
            "latmion_ashlar",
            "latmion_rusticated",
            "latmion_rock",
            "psamatheon",
            "psamatheon_aged",
            "psamatheon_ashlar",
            "psamatheon_rusticated",
            "psamatheon_rock",
            "kelastrion_hewn",
            "latmion_hewn",
            "psamatheon_hewn",
            "kelastrion_herringbone_bronze",
            "kelastrion_herringbone_grout",
            "latmion_herringbone_bronze",
            "latmion_herringbone_grout",
            "psamatheon_herringbone_bronze",
            "psamatheon_herringbone_grout"
    );

    private static final List<String> EXTRA_GROUP_MATERIALS = List.of(
            "kelastrion",
            "latmion",
            "psamatheon"
    );

    private static final List<String> STANDARD_PREFIX_VARIANTS = List.of(
            "",
            "_rock",
            "_ashlar",
            "_herringbone_bronze",
            "_herringbone_grout",
            "_rusticated",
            "_hewn"
    );

    private static final Map<String, List<String>> EXTRA_PREFIX_VARIANTS = Map.ofEntries(
            Map.entry("borealis", List.of("_diaphanes")),
            Map.entry("calacattum", List.of("_portorium_weave_bronze", "_portorium_weave_grout")),
            Map.entry("chalstrom", List.of("_calacattum_weave_bronze", "_calacattum_weave_grout")),
            Map.entry("chrysonyx", List.of("_glacium_weave_bronze", "_glacium_weave_grout")),
            Map.entry("gelastrum", List.of("_diaphanes", "_etruscus_weave_bronze", "_etruscus_weave_grout")),
            Map.entry("glacium", List.of("_nerium_weave_bronze", "_nerium_weave_grout")),
            Map.entry("hesperion", List.of("_glacium_weave_bronze", "_glacium_weave_grout")),
            Map.entry("kylorion", List.of("_glacium_weave_bronze", "_glacium_weave_grout")),
            Map.entry("laurentium", List.of("_calacattum_weave_bronze", "_calacattum_weave_grout")),
            Map.entry("mielonyx", List.of("_diaphanes", "_imperium_weave_bronze", "_imperium_weave_grout")),
            Map.entry("rosinium", List.of("_sanguenite_weave_bronze", "_sanguenite_weave_grout")),
            Map.entry("selenephos", List.of("_diaphanes")),
            Map.entry("solistra", List.of("_etruscus_weave_bronze", "_etruscus_weave_grout")),
            Map.entry("striatus", List.of("_nerium_weave_bronze", "_nerium_weave_grout"))
    );

    private static final Map<String, FamilySpec> FAMILIES_BY_KEY = buildFamilies();
    private static final FamilySpec ALL_ERYDON_BLOCKS = FamilySpec.allErydonBlocks();
    private static final List<FamilySpec> MATCH_ORDER = FAMILIES_BY_KEY.values().stream()
            .filter(family -> !family.isMaterialGroup())
            .sorted(Comparator
                    .comparingInt(FamilySpec::matchPriority)
                    .reversed()
                    .thenComparing(FamilySpec::canonicalKey))
            .toList();
    private static final List<FamilySpec> MATERIAL_GROUP_MATCH_ORDER = FAMILIES_BY_KEY.values().stream()
            .filter(FamilySpec::isMaterialGroup)
            .sorted(Comparator
                    .comparingInt(FamilySpec::matchPriority)
                    .reversed()
                    .thenComparing(FamilySpec::canonicalKey))
            .toList();
    private static final NavigableSet<String> CANONICAL_KEYS = new TreeSet<>(FAMILIES_BY_KEY.keySet());
    private static final NavigableSet<String> MATERIAL_GROUP_KEYS = keysMatching(FamilySpec::isMaterialGroup);
    private static final NavigableSet<String> TEXTURE_FAMILY_KEYS = keysMatching(family -> !family.isMaterialGroup());

    private ErydonSwapFamilyDatabase() {
    }

    public static Optional<FamilySpec> findFamily(String canonicalKey) {
        if (ALL_ERYDON_BLOCKS_KEY.equals(canonicalKey) || "all_erydon".equals(canonicalKey) || "all".equals(canonicalKey)) {
            return Optional.of(ALL_ERYDON_BLOCKS);
        }
        return Optional.ofNullable(FAMILIES_BY_KEY.get(canonicalKey));
    }

    public static NavigableSet<String> canonicalKeys() {
        return CANONICAL_KEYS;
    }

    public static NavigableSet<String> sourceKeys() {
        return LiveAvailability.SOURCE_KEYS;
    }

    public static NavigableSet<String> targetKeysForSource(String canonicalSourceKey) {
        return targetKeysForSource(canonicalSourceKey, LiveAvailability.INDEX);
    }

    static NavigableSet<String> sourceKeys(Set<String> registeredBlockPaths) {
        return sourceKeys(AvailabilityIndex.fromErydonPaths(registeredBlockPaths));
    }

    static NavigableSet<String> sourceKeysForIds(Set<Identifier> registeredBlockIds) {
        return sourceKeys(AvailabilityIndex.fromIds(registeredBlockIds));
    }

    private static NavigableSet<String> sourceKeys(AvailabilityIndex index) {
        NavigableSet<String> keys = new TreeSet<>();
        for (FamilySpec family : FAMILIES_BY_KEY.values()) {
            if (!index.formsFor(family).isEmpty()) {
                keys.add(family.canonicalKey());
            }
        }
        keys.add(ALL_ERYDON_BLOCKS_KEY);
        return Collections.unmodifiableNavigableSet(keys);
    }

    static NavigableSet<String> targetKeysForSource(String canonicalSourceKey, Set<String> registeredBlockPaths) {
        return targetKeysForSource(canonicalSourceKey, AvailabilityIndex.fromErydonPaths(registeredBlockPaths));
    }

    static NavigableSet<String> targetKeysForSourceIds(
            String canonicalSourceKey, Set<Identifier> registeredBlockIds) {
        return targetKeysForSource(canonicalSourceKey, AvailabilityIndex.fromIds(registeredBlockIds));
    }

    private static NavigableSet<String> targetKeysForSource(
            String canonicalSourceKey, AvailabilityIndex index) {
        Optional<FamilySpec> source = findFamily(canonicalSourceKey);
        if (source.isEmpty() || source.get().isAllErydonBlocks()) {
            NavigableSet<String> available = new TreeSet<>(sourceKeys(index));
            available.remove(ALL_ERYDON_BLOCKS_KEY);
            return Collections.unmodifiableNavigableSet(available);
        }

        Set<AvailableForm> sourceForms = index.formsFor(source.get());
        NavigableSet<String> candidates = source.get().isMaterialGroup()
                ? MATERIAL_GROUP_KEYS
                : TEXTURE_FAMILY_KEYS;
        NavigableSet<String> available = new TreeSet<>();
        for (String candidateKey : candidates) {
            if (candidateKey.equals(source.get().canonicalKey())) {
                continue;
            }
            FamilySpec candidate = FAMILIES_BY_KEY.get(candidateKey);
            if (!sourceForms.isEmpty() && sourceForms.stream()
                    .map(form -> form.targetId(candidate))
                    .allMatch(index.registeredIds()::contains)) {
                available.add(candidateKey);
            }
        }
        return Collections.unmodifiableNavigableSet(available);
    }

    public static Optional<FamilyMatch> match(Identifier blockId) {
        if (!isSupportedNamespace(blockId.getNamespace())) {
            return Optional.empty();
        }
        return match(blockId.getNamespace(), canonicalPath(blockId));
    }

    public static Optional<FamilyMatch> match(Identifier blockId, FamilySpec requestedFamily, FamilySpec targetFamily) {
        if (!isSupportedNamespace(blockId.getNamespace())
                || (requestedFamily.isAllErydonBlocks() && !Erydon.MOD_ID.equals(blockId.getNamespace()))) {
            return Optional.empty();
        }

        String namespace = blockId.getNamespace();
        String canonicalPath = canonicalPath(blockId);
        if (requestedFamily.isAllErydonBlocks()) {
            if (!targetFamily.isMaterialGroup()) {
                return match(namespace, canonicalPath);
            }

            Optional<FamilyMatch> materialGroupMatch = matchMaterialGroup(namespace, canonicalPath);
            return materialGroupMatch.isPresent() ? materialGroupMatch : match(namespace, canonicalPath);
        }

        return match(namespace, canonicalPath, requestedFamily);
    }

    public static Optional<FamilyMatch> match(Identifier blockId, FamilySpec requestedFamily) {
        if (!isSupportedNamespace(blockId.getNamespace())
                || (requestedFamily.isAllErydonBlocks() && !Erydon.MOD_ID.equals(blockId.getNamespace()))) {
            return Optional.empty();
        }

        return match(blockId.getNamespace(), canonicalPath(blockId), requestedFamily);
    }

    private static Optional<FamilyMatch> match(
            String namespace, String canonicalPath, FamilySpec requestedFamily) {
        if (requestedFamily.isMaterialGroup()) {
            return requestedFamily.extractForm(canonicalPath)
                    .map(form -> new FamilyMatch(requestedFamily, namespace, form));
        }

        Optional<FamilyMatch> match = match(namespace, canonicalPath);
        if (match.isEmpty() || !match.get().family().canonicalKey().equals(requestedFamily.canonicalKey())) {
            return Optional.empty();
        }
        return match;
    }

    private static Optional<FamilyMatch> match(String namespace, String path) {
        for (FamilySpec family : MATCH_ORDER) {
            Optional<String> form = family.extractForm(path);
            if (form.isPresent()) {
                return Optional.of(new FamilyMatch(family, namespace, form.get()));
            }
        }
        return Optional.empty();
    }

    private static Optional<FamilyMatch> matchMaterialGroup(String namespace, String path) {
        for (FamilySpec family : MATERIAL_GROUP_MATCH_ORDER) {
            Optional<String> form = family.extractForm(path);
            if (form.isPresent()) {
                return Optional.of(new FamilyMatch(family, namespace, form.get()));
            }
        }
        return Optional.empty();
    }

    private static Map<String, FamilySpec> buildFamilies() {
        Map<String, FamilySpec> families = new LinkedHashMap<>();
        for (String base : BASE_MATERIALS) {
            registerMaterialGroup(families, base);
            registerAged(families, base);
            for (String variant : STANDARD_PREFIX_VARIANTS) {
                registerPrefix(families, base + variant);
            }

            List<String> extras = EXTRA_PREFIX_VARIANTS.get(base);
            if (extras == null) {
                continue;
            }
            for (String extra : extras) {
                registerPrefix(families, base + extra);
            }
        }
        for (String baseOnly : BASE_ONLY_MATERIALS) {
            if (baseOnly.endsWith("_aged")) {
                registerAged(families, baseOnly.substring(0, baseOnly.length() - "_aged".length()));
            } else {
                registerPrefix(families, baseOnly);
            }
        }
        for (String groupMaterial : EXTRA_GROUP_MATERIALS) {
            registerMaterialGroup(families, groupMaterial);
        }
        return Map.copyOf(families);
    }

    private static NavigableSet<String> keysMatching(java.util.function.Predicate<FamilySpec> predicate) {
        NavigableSet<String> keys = new TreeSet<>();
        for (FamilySpec family : FAMILIES_BY_KEY.values()) {
            if (predicate.test(family)) {
                keys.add(family.canonicalKey());
            }
        }
        return keys;
    }

    static boolean isSupportedNamespace(String namespace) {
        return SUPPORTED_NAMESPACES.contains(namespace);
    }

    private static String canonicalPath(Identifier id) {
        String path = id.getPath();
        if (Erydon.MOD_ID.equals(id.getNamespace())) {
            return ErydonIdMigration.canonicalPath(path);
        }
        if (DAEDALON_MOD_ID.equals(id.getNamespace())) {
            return canonicalDaedalonPath(path);
        }

        // Themelios registry aliases resolve legacy IDs to their canonical block
        // object before the command asks the registry for its identifier.
        return path;
    }

    private static String canonicalDaedalonPath(String path) {
        if (!path.startsWith(DAEDALON_SPARTAN_PREFIX)) {
            return path;
        }

        String material = path.substring(DAEDALON_SPARTAN_PREFIX.length());
        boolean aged = material.endsWith("_aged");
        if (aged) {
            material = material.substring(0, material.length() - "_aged".length());
        }
        if (!FAMILIES_BY_KEY.containsKey(material + "_family")) {
            return path;
        }
        return material + (aged ? "_aged" : "") + DAEDALON_SPARTAN_CANONICAL_FORM;
    }

    private static String registeredPath(String namespace, String canonicalPath) {
        if (!DAEDALON_MOD_ID.equals(namespace)
                || !canonicalPath.endsWith(DAEDALON_SPARTAN_CANONICAL_FORM)) {
            return canonicalPath;
        }

        String material = canonicalPath.substring(
                0, canonicalPath.length() - DAEDALON_SPARTAN_CANONICAL_FORM.length());
        boolean aged = material.endsWith("_aged");
        if (aged) {
            material = material.substring(0, material.length() - "_aged".length());
        }
        if (!FAMILIES_BY_KEY.containsKey(material + "_family")) {
            return canonicalPath;
        }
        return DAEDALON_SPARTAN_PREFIX + material + (aged ? "_aged" : "");
    }

    static String displayName(String canonicalKey) {
        StringBuilder display = new StringBuilder();
        for (String token : canonicalKey.split("_")) {
            if (!display.isEmpty()) {
                display.append(' ');
            }
            if ("erydon".equals(token)) {
                display.append("ERYDON");
            } else if (!token.isEmpty()) {
                display.append(Character.toUpperCase(token.charAt(0)))
                        .append(token.substring(1));
            }
        }
        return display.toString();
    }

    static String commandSuggestion(String canonicalKey) {
        String displayName = displayName(canonicalKey);
        return displayName.indexOf(' ') >= 0 ? '"' + displayName + '"' : displayName;
    }

    private static void registerPrefix(Map<String, FamilySpec> families, String canonicalKey) {
        families.put(canonicalKey, FamilySpec.prefix(canonicalKey));
    }

    private static void registerAged(Map<String, FamilySpec> families, String baseMaterial) {
        families.put(baseMaterial + "_aged", FamilySpec.aged(baseMaterial));
    }

    private static void registerMaterialGroup(Map<String, FamilySpec> families, String baseMaterial) {
        families.put(baseMaterial + "_family", FamilySpec.materialGroup(baseMaterial));
    }

    public record FamilyMatch(FamilySpec family, String namespace, String form) {
        public Identifier targetId(FamilySpec targetFamily) {
            String canonicalTargetPath = targetFamily.buildPath(form);
            return new Identifier(namespace, registeredPath(namespace, canonicalTargetPath));
        }

        public String targetPath(FamilySpec targetFamily) {
            return targetId(targetFamily).getPath();
        }
    }

    public static final class FamilySpec {
        private final String canonicalKey;
        private final MatchMode mode;
        private final String wireStem;

        private FamilySpec(String canonicalKey, MatchMode mode, String wireStem) {
            this.canonicalKey = canonicalKey;
            this.mode = mode;
            this.wireStem = wireStem;
        }

        public static FamilySpec prefix(String canonicalKey) {
            return new FamilySpec(canonicalKey, MatchMode.PREFIX, canonicalKey);
        }

        public static FamilySpec aged(String baseMaterial) {
            return new FamilySpec(baseMaterial + "_aged", MatchMode.AGED, baseMaterial);
        }

        public static FamilySpec materialGroup(String baseMaterial) {
            return new FamilySpec(baseMaterial + "_family", MatchMode.MATERIAL_GROUP, baseMaterial);
        }

        public static FamilySpec allErydonBlocks() {
            return new FamilySpec(ALL_ERYDON_BLOCKS_KEY, MatchMode.ALL_ERYDON_BLOCKS, "");
        }

        public String canonicalKey() {
            return canonicalKey;
        }

        public boolean isAllErydonBlocks() {
            return mode == MatchMode.ALL_ERYDON_BLOCKS;
        }

        public boolean isMaterialGroup() {
            return mode == MatchMode.MATERIAL_GROUP;
        }

        public int matchPriority() {
            return canonicalKey.length();
        }

        public String buildPath(String form) {
            return switch (mode) {
                case PREFIX -> wireStem + form;
                case AGED -> wireStem + "_aged" + form;
                case MATERIAL_GROUP -> wireStem + form;
                case ALL_ERYDON_BLOCKS -> throw new IllegalStateException("all_erydon_blocks cannot be used as a target family");
            };
        }

        private Optional<String> extractForm(String path) {
            return switch (mode) {
                case PREFIX -> extractPrefixForm(path);
                case AGED -> extractAgedForm(path);
                case MATERIAL_GROUP -> extractMaterialGroupForm(path);
                case ALL_ERYDON_BLOCKS -> Optional.empty();
            };
        }

        private Optional<String> extractPrefixForm(String path) {
            if (path.equals(wireStem)) {
                return Optional.of("");
            }
            if (path.startsWith(wireStem + "_")) {
                return Optional.of(path.substring(wireStem.length()));
            }
            return Optional.empty();
        }

        private Optional<String> extractAgedForm(String path) {
            if (!ErydonIdNaming.isAged(path)) {
                return Optional.empty();
            }

            String core = ErydonIdNaming.withoutAged(path);
            if (core.equals(wireStem)) {
                return Optional.of("");
            }
            if (core.startsWith(wireStem + "_")) {
                return Optional.of(core.substring(wireStem.length()));
            }
            return Optional.empty();
        }

        private Optional<String> extractMaterialGroupForm(String path) {
            if (path.contains("_weave_")) {
                return Optional.empty();
            }
            if (path.equals(wireStem)) {
                return Optional.of("");
            }
            if (path.startsWith(wireStem + "_")) {
                return Optional.of(path.substring(wireStem.length()));
            }
            return Optional.empty();
        }
    }

    private enum MatchMode {
        PREFIX,
        AGED,
        MATERIAL_GROUP,
        ALL_ERYDON_BLOCKS
    }

    private static final class LiveAvailability {
        private static final AvailabilityIndex INDEX = AvailabilityIndex.fromIds(registeredBlockIds());
        private static final NavigableSet<String> SOURCE_KEYS = sourceKeys(INDEX);

        private static Set<Identifier> registeredBlockIds() {
            Set<Identifier> ids = new LinkedHashSet<>();
            for (Identifier id : Registries.BLOCK.getIds()) {
                if (isSupportedNamespace(id.getNamespace())) {
                    ids.add(id);
                }
            }
            return Set.copyOf(ids);
        }
    }

    private record AvailableForm(String namespace, String form) {
        private Identifier targetId(FamilySpec family) {
            String canonicalTargetPath = family.buildPath(form);
            return new Identifier(namespace, registeredPath(namespace, canonicalTargetPath));
        }
    }

    private record AvailabilityIndex(Set<Identifier> registeredIds,
                                     Map<String, Set<AvailableForm>> formsByFamily) {
        private static AvailabilityIndex fromErydonPaths(Set<String> registeredBlockPaths) {
            Set<Identifier> ids = new LinkedHashSet<>();
            for (String path : registeredBlockPaths) {
                ids.add(new Identifier(Erydon.MOD_ID, path));
            }
            return fromIds(ids);
        }

        private static AvailabilityIndex fromIds(Set<Identifier> registeredBlockIds) {
            Set<Identifier> canonicalIds = new LinkedHashSet<>();
            for (Identifier id : registeredBlockIds) {
                if (!isSupportedNamespace(id.getNamespace())) {
                    continue;
                }
                String canonicalPath = canonicalPath(id);
                canonicalIds.add(new Identifier(
                        id.getNamespace(), registeredPath(id.getNamespace(), canonicalPath)));
            }

            Map<String, Set<AvailableForm>> mutableForms = new LinkedHashMap<>();
            for (Identifier id : canonicalIds) {
                String namespace = id.getNamespace();
                String path = canonicalPath(id);
                match(namespace, path).ifPresent(match -> mutableForms
                        .computeIfAbsent(match.family().canonicalKey(), ignored -> new LinkedHashSet<>())
                        .add(new AvailableForm(namespace, match.form())));
                matchMaterialGroup(namespace, path).ifPresent(match -> mutableForms
                        .computeIfAbsent(match.family().canonicalKey(), ignored -> new LinkedHashSet<>())
                        .add(new AvailableForm(namespace, match.form())));
            }

            Map<String, Set<AvailableForm>> immutableForms = new LinkedHashMap<>();
            for (Map.Entry<String, Set<AvailableForm>> entry : mutableForms.entrySet()) {
                immutableForms.put(entry.getKey(), Set.copyOf(entry.getValue()));
            }
            return new AvailabilityIndex(Set.copyOf(canonicalIds), Map.copyOf(immutableForms));
        }

        private Set<AvailableForm> formsFor(FamilySpec family) {
            return formsByFamily.getOrDefault(family.canonicalKey(), Set.of());
        }
    }
}
