package com.oliver.erydon.command;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.util.ErydonIdNaming;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeSet;

final class ErydonSwapFamilyDatabase {

    public static final String ALL_ERYDON_BLOCKS_KEY = "all_erydon_blocks";

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
    private static final NavigableSet<String> SOURCE_KEYS = buildSourceKeys();
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
        return SOURCE_KEYS;
    }

    public static NavigableSet<String> targetKeysForSource(String canonicalSourceKey) {
        Optional<FamilySpec> source = findFamily(canonicalSourceKey);
        if (source.isEmpty() || source.get().isAllErydonBlocks()) {
            return CANONICAL_KEYS;
        }
        return source.get().isMaterialGroup() ? MATERIAL_GROUP_KEYS : TEXTURE_FAMILY_KEYS;
    }

    public static Optional<FamilyMatch> match(Identifier blockId) {
        if (!Erydon.MOD_ID.equals(blockId.getNamespace())) {
            return Optional.empty();
        }
        return match(blockId.getPath());
    }

    public static Optional<FamilyMatch> match(Identifier blockId, FamilySpec requestedFamily, FamilySpec targetFamily) {
        if (!Erydon.MOD_ID.equals(blockId.getNamespace())) {
            return Optional.empty();
        }

        if (requestedFamily.isAllErydonBlocks()) {
            if (!targetFamily.isMaterialGroup()) {
                return match(blockId.getPath());
            }

            Optional<FamilyMatch> materialGroupMatch = matchMaterialGroup(blockId.getPath());
            return materialGroupMatch.isPresent() ? materialGroupMatch : match(blockId.getPath());
        }

        return match(blockId, requestedFamily);
    }

    public static Optional<FamilyMatch> match(Identifier blockId, FamilySpec requestedFamily) {
        if (!Erydon.MOD_ID.equals(blockId.getNamespace())) {
            return Optional.empty();
        }

        if (requestedFamily.isMaterialGroup()) {
            return requestedFamily.extractForm(blockId.getPath())
                    .map(form -> new FamilyMatch(requestedFamily, form));
        }

        Optional<FamilyMatch> match = match(blockId.getPath());
        if (match.isEmpty() || !match.get().family().canonicalKey().equals(requestedFamily.canonicalKey())) {
            return Optional.empty();
        }
        return match;
    }

    private static Optional<FamilyMatch> match(String path) {
        for (FamilySpec family : MATCH_ORDER) {
            Optional<String> form = family.extractForm(path);
            if (form.isPresent()) {
                return Optional.of(new FamilyMatch(family, form.get()));
            }
        }
        return Optional.empty();
    }

    private static Optional<FamilyMatch> matchMaterialGroup(String path) {
        for (FamilySpec family : MATERIAL_GROUP_MATCH_ORDER) {
            Optional<String> form = family.extractForm(path);
            if (form.isPresent()) {
                return Optional.of(new FamilyMatch(family, form.get()));
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

    private static NavigableSet<String> buildSourceKeys() {
        NavigableSet<String> keys = new TreeSet<>(CANONICAL_KEYS);
        keys.add(ALL_ERYDON_BLOCKS_KEY);
        return keys;
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

    private static void registerPrefix(Map<String, FamilySpec> families, String canonicalKey) {
        families.put(canonicalKey, FamilySpec.prefix(canonicalKey));
    }

    private static void registerAged(Map<String, FamilySpec> families, String baseMaterial) {
        families.put(baseMaterial + "_aged", FamilySpec.aged(baseMaterial));
    }

    private static void registerMaterialGroup(Map<String, FamilySpec> families, String baseMaterial) {
        families.put(baseMaterial + "_family", FamilySpec.materialGroup(baseMaterial));
    }

    public record FamilyMatch(FamilySpec family, String form) {
        public String targetPath(FamilySpec targetFamily) {
            return targetFamily.buildPath(form);
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
                case AGED -> buildAgedPath(form);
                case MATERIAL_GROUP -> wireStem + form;
                case ALL_ERYDON_BLOCKS -> throw new IllegalStateException("all_erydon_blocks cannot be used as a target family");
            };
        }

        private String buildAgedPath(String form) {
            String canonicalPath = wireStem + "_aged" + form;
            if (Registries.BLOCK.containsId(new Identifier(Erydon.MOD_ID, canonicalPath))) {
                return canonicalPath;
            }
            return wireStem + form + "_aged";
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
}
