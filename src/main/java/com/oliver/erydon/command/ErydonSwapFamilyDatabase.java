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
    static final String ALL_FAMILY_BLOCKS_KEY = "all_family_blocks";
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
    private static final Map<String, FamilySpec> ALL_SOURCES = Map.of(
            ALL_ERYDON_BLOCKS_KEY, FamilySpec.allBlocks(ALL_ERYDON_BLOCKS_KEY, Erydon.MOD_ID),
            ALL_FAMILY_BLOCKS_KEY, FamilySpec.allBlocks(ALL_FAMILY_BLOCKS_KEY, ""),
            "all_daedalon_blocks", FamilySpec.allBlocks("all_daedalon_blocks", DAEDALON_MOD_ID),
            "all_themelios_blocks", FamilySpec.allBlocks("all_themelios_blocks", THEMELIOS_MOD_ID));
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
    private static final NavigableSet<String> CANONICAL_KEYS =
            Collections.unmodifiableNavigableSet(new TreeSet<>(FAMILIES_BY_KEY.keySet()));

    private ErydonSwapFamilyDatabase() {
    }

    public static Optional<FamilySpec> findFamily(String canonicalKey) {
        if (ALL_ERYDON_BLOCKS_KEY.equals(canonicalKey) || "all_erydon".equals(canonicalKey) || "all".equals(canonicalKey)) {
            return Optional.of(ALL_SOURCES.get(ALL_ERYDON_BLOCKS_KEY));
        }
        if (ALL_SOURCES.containsKey(canonicalKey)) {
            return Optional.of(ALL_SOURCES.get(canonicalKey));
        }
        for (String suffix : List.of("_polished", "_honed", "_base")) {
            if (canonicalKey.endsWith(suffix)) {
                String material = canonicalKey.substring(0, canonicalKey.length() - suffix.length());
                if (BASE_MATERIALS.contains(material) || EXTRA_GROUP_MATERIALS.contains(material)) {
                    return Optional.ofNullable(FAMILIES_BY_KEY.get(material));
                }
            }
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
        String key = findFamily(canonicalSourceKey).map(FamilySpec::canonicalKey).orElse(canonicalSourceKey);
        return LiveAvailability.TARGET_KEYS.computeIfAbsent(key,
                source -> targetKeysForSource(source, LiveAvailability.INDEX));
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
        for (FamilySpec scope : ALL_SOURCES.values()) {
            if (scope.isAllErydonBlocks() || !index.formsFor(scope).isEmpty()) {
                keys.add(scope.canonicalKey());
            }
        }
        return Collections.unmodifiableNavigableSet(keys);
    }

    static NavigableSet<String> targetKeysForSource(String canonicalSourceKey, Set<String> registeredBlockPaths) {
        return targetKeysForSource(canonicalSourceKey, AvailabilityIndex.fromErydonPaths(registeredBlockPaths));
    }

    static NavigableSet<String> targetKeysForSourceIds(
            String canonicalSourceKey, Set<Identifier> registeredBlockIds) {
        return targetKeysForSource(canonicalSourceKey, AvailabilityIndex.fromIds(registeredBlockIds));
    }

    static NavigableSet<String> targetKeysForSource(
            String canonicalSourceKey, AvailabilityIndex index) {
        Optional<FamilySpec> source = findFamily(canonicalSourceKey);
        if (source.isEmpty()) {
            return Collections.emptyNavigableSet();
        }
        Set<FamilyMatch> sourceForms = index.formsFor(source.get());
        NavigableSet<String> available = new TreeSet<>();
        for (FamilySpec candidate : FAMILIES_BY_KEY.values()) {
            if (candidate.canonicalKey().equals(source.get().canonicalKey())) {
                continue;
            }
            // Suggestions describe possible conversions, not complete catalogue parity.
            // Execution uses the same mapping and leaves missing counterparts untouched.
            if (sourceForms.stream().anyMatch(form -> {
                Identifier target = form.targetId(candidate);
                return !target.equals(form.targetId(form.family())) && index.registeredIds().contains(target);
            })) {
                available.add(candidate.canonicalKey());
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
        return match(blockId, requestedFamily);
    }

    public static Optional<FamilyMatch> match(Identifier blockId, FamilySpec requestedFamily) {
        if (!isSupportedNamespace(blockId.getNamespace()) || !requestedFamily.acceptsNamespace(blockId.getNamespace())) {
            return Optional.empty();
        }
        String path = canonicalPath(blockId);
        if (requestedFamily.isAllBlocks()) {
            return match(blockId.getNamespace(), path);
        }
        return match(blockId.getNamespace(), path, requestedFamily);
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
        // Inlays are finishes of their own, not part of the plain polished/honed set.
        for (String material : java.util.stream.Stream.concat(BASE_MATERIALS.stream(), EXTRA_GROUP_MATERIALS.stream()).toList()) {
            for (String motif : List.of("trim", "guilloche", "quatrefoil", "rosette")) {
                for (String metal : List.of("bronze", "silver")) {
                    registerPrefix(families, material + "_" + motif + "_" + metal);
                }
            }
        }
        registerPrefix(families, "bronze");
        registerMaterialGroup(families, "bronze");
        return Map.copyOf(families);
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
        if (!BASE_MATERIALS.contains(material) && !EXTRA_GROUP_MATERIALS.contains(material)) {
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
        if (!BASE_MATERIALS.contains(material) && !EXTRA_GROUP_MATERIALS.contains(material)) {
            return canonicalPath;
        }
        return DAEDALON_SPARTAN_PREFIX + material + (aged ? "_aged" : "");
    }

    static String displayName(String canonicalKey) {
        if (BASE_MATERIALS.contains(canonicalKey)) {
            canonicalKey += "_polished";
        } else if (EXTRA_GROUP_MATERIALS.contains(canonicalKey)) {
            canonicalKey += "_honed";
        }
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

    public record FamilyMatch(FamilySpec family, String namespace, String form,
                              String finishForm, String materialForm) {
        private FamilyMatch(FamilySpec family, String namespace, String form) {
            this(family, namespace, form,
                    family.isMaterialGroup() ? extractForm(family.buildPath(form), MATCH_ORDER) : form,
                    family.isMaterialGroup() ? form : extractForm(family.buildPath(form), MATERIAL_GROUP_MATCH_ORDER));
        }

        private static String extractForm(String path, List<FamilySpec> order) {
            for (FamilySpec spec : order) {
                Optional<String> extracted = spec.extractForm(path);
                if (extracted.isPresent()) {
                    return extracted.get();
                }
            }
            throw new IllegalArgumentException("Unmapped material path: " + path);
        }

        public Identifier targetId(FamilySpec targetFamily) {
            String targetForm = targetFamily.isMaterialGroup() ? materialForm : finishForm;
            String canonicalTargetPath = targetFamily.buildPath(targetForm);
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

        private static FamilySpec allBlocks(String key, String namespace) {
            return new FamilySpec(key, MatchMode.ALL_ERYDON_BLOCKS, namespace);
        }

        public String canonicalKey() {
            return canonicalKey;
        }

        public boolean isAllErydonBlocks() {
            return ALL_ERYDON_BLOCKS_KEY.equals(canonicalKey);
        }

        public boolean isAllBlocks() {
            return mode == MatchMode.ALL_ERYDON_BLOCKS;
        }

        private boolean acceptsNamespace(String namespace) {
            return !isAllBlocks() || wireStem.isEmpty() || wireStem.equals(namespace);
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
        private static final Map<String, NavigableSet<String>> TARGET_KEYS = new java.util.concurrent.ConcurrentHashMap<>();

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

    record AvailabilityIndex(Set<Identifier> registeredIds,
                                     Map<String, Set<FamilyMatch>> formsByFamily) {
        private static AvailabilityIndex fromErydonPaths(Set<String> registeredBlockPaths) {
            Set<Identifier> ids = new LinkedHashSet<>();
            for (String path : registeredBlockPaths) {
                ids.add(new Identifier(Erydon.MOD_ID, path));
            }
            return fromIds(ids);
        }

        static AvailabilityIndex fromIds(Set<Identifier> registeredBlockIds) {
            Set<Identifier> canonicalIds = new LinkedHashSet<>();
            for (Identifier id : registeredBlockIds) {
                if (!isSupportedNamespace(id.getNamespace())) {
                    continue;
                }
                String canonicalPath = canonicalPath(id);
                canonicalIds.add(new Identifier(
                        id.getNamespace(), registeredPath(id.getNamespace(), canonicalPath)));
            }

            Map<String, Set<FamilyMatch>> mutableForms = new LinkedHashMap<>();
            for (Identifier id : canonicalIds) {
                String namespace = id.getNamespace();
                String path = canonicalPath(id);
                match(namespace, path).ifPresent(match -> {
                    mutableForms.computeIfAbsent(match.family().canonicalKey(), ignored -> new LinkedHashSet<>()).add(match);
                    for (FamilySpec scope : ALL_SOURCES.values()) {
                        if (scope.acceptsNamespace(namespace)) {
                            mutableForms.computeIfAbsent(scope.canonicalKey(), ignored -> new LinkedHashSet<>()).add(match);
                        }
                    }
                });
                matchMaterialGroup(namespace, path).ifPresent(match -> mutableForms
                        .computeIfAbsent(match.family().canonicalKey(), ignored -> new LinkedHashSet<>())
                        .add(match));
            }

            Map<String, Set<FamilyMatch>> immutableForms = new LinkedHashMap<>();
            for (Map.Entry<String, Set<FamilyMatch>> entry : mutableForms.entrySet()) {
                immutableForms.put(entry.getKey(), Set.copyOf(entry.getValue()));
            }
            return new AvailabilityIndex(Set.copyOf(canonicalIds), Map.copyOf(immutableForms));
        }

        Set<FamilyMatch> formsFor(FamilySpec family) {
            return formsByFamily.getOrDefault(family.canonicalKey(), Set.of());
        }
    }
}
