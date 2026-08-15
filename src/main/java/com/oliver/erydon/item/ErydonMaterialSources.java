package com.oliver.erydon.item;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class ErydonMaterialSources {
    private static final List<MaterialSource> MATERIAL_SOURCES = List.of(
            material("aganite", "Agate gemstone"),
            material("aterzon", "Panda Marble"),
            material("borealis", "Labradorite gemstone"),
            material("brectite", "Breche de Vendome marble"),
            material("calacattum", "Calacatta Oro marble"),
            material("chalstrom", "Tempest Gold marble"),
            material("chrysonyx", "Black and gold onyx"),
            material("etruscus", "Dark green Etruscan marble"),
            material("gelastrum", "Azul Macaubas quartzite"),
            material("glacium", "Statuario marble"),
            material("hesperion", "Sodalite-blue stone"),
            material("imperium", "Emperador Dark marble"),
            material("kylorion", "Rainforest marble"),
            material("kelastrion", "Belgian Blue limestone"),
            material("latmion", "Limestone"),
            material("laurentium", "Noir Saint Laurent marble"),
            material("mielonyx", "Honey onyx"),
            material("nerium", "Nero Marquina marble"),
            material("noxoplis", "Black opal"),
            material("porphyros", "Rosso Orobico marble"),
            material("psamatheon", "Sandstone"),
            material("portorium", "Portoro marble"),
            material("rosinium", "Rosa Portugalo marble"),
            material("sanguenite", "Rosso Levanto marble"),
            material("selenephos", "Pink Alabaster"),
            material("solistra", "Giallo Siena marble"),
            material("striatus", "Striped travertine")
    );

    private ErydonMaterialSources() {
    }

    public static List<String> materialPrefixes() {
        return MATERIAL_SOURCES.stream()
                .map(MaterialSource::prefix)
                .toList();
    }

    public static String findSourceName(String path) {
        List<MaterialSource> materialSources = findAll(path);
        if (materialSources.isEmpty()) {
            return null;
        }
        return joinSourceNames(materialSources, " / ");
    }

    public static List<String> findSearchTerms(String path) {
        List<MaterialSource> materialSources = findAll(path);
        if (materialSources.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> searchTerms = new LinkedHashSet<>();
        for (MaterialSource materialSource : materialSources) {
            searchTerms.addAll(materialSource.searchTerms());
        }

        if (materialSources.size() > 1) {
            searchTerms.add(joinSourceNames(materialSources, " / "));
            searchTerms.add(joinSourceNames(materialSources, " and "));
        }

        return List.copyOf(searchTerms);
    }

    private static List<MaterialSource> findAll(String path) {
        List<MaterialSource> weaveSources = findWeaveSources(path);
        if (!weaveSources.isEmpty()) {
            return weaveSources;
        }

        MaterialSource materialSource = findByPath(path);
        return materialSource == null ? List.of() : List.of(materialSource);
    }

    private static List<MaterialSource> findWeaveSources(String path) {
        if (!path.contains("_weave_")) {
            return List.of();
        }

        String[] prefixes = path.substring(0, path.indexOf("_weave_")).split("_");
        if (prefixes.length < 2) {
            return List.of();
        }

        ArrayList<MaterialSource> materialSources = new ArrayList<>(2);
        addIfPresent(materialSources, findByPrefix(prefixes[0]));
        addIfPresent(materialSources, findByPrefix(prefixes[1]));
        return List.copyOf(materialSources);
    }

    private static void addIfPresent(List<MaterialSource> materialSources, MaterialSource materialSource) {
        if (materialSource != null && !materialSources.contains(materialSource)) {
            materialSources.add(materialSource);
        }
    }

    private static MaterialSource findByPath(String path) {
        for (MaterialSource materialSource : MATERIAL_SOURCES) {
            if (materialSource.matches(path)) {
                return materialSource;
            }
        }
        return null;
    }

    private static MaterialSource findByPrefix(String prefix) {
        for (MaterialSource materialSource : MATERIAL_SOURCES) {
            if (materialSource.prefix().equals(prefix)) {
                return materialSource;
            }
        }
        return null;
    }

    private static String joinSourceNames(List<MaterialSource> materialSources, String separator) {
        StringBuilder builder = new StringBuilder();
        for (MaterialSource materialSource : materialSources) {
            if (builder.length() > 0) {
                builder.append(separator);
            }
            builder.append(materialSource.sourceName());
        }
        return builder.toString();
    }

    private static MaterialSource material(String prefix, String sourceName, String... searchAliases) {
        ArrayList<String> searchTerms = new ArrayList<>(1 + searchAliases.length);
        searchTerms.add(sourceName);
        searchTerms.addAll(List.of(searchAliases));
        return new MaterialSource(prefix, sourceName, List.copyOf(searchTerms));
    }

    private record MaterialSource(String prefix, String sourceName, List<String> searchTerms) {
        private boolean matches(String path) {
            return path.equals(prefix)
                    || path.startsWith(prefix + "_");
        }
    }
}
