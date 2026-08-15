package com.oliver.erydon.client.model;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.migration.ErydonIdMigration;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

final class ErydonModelFamilyIndex {
    private static volatile ErydonModelFamilyIndex instance;

    private final List<String> columns;
    private final List<String> cornices;
    private final List<String> cofferedCeilings;
    private final List<String> layerVertical;
    private final List<String> surrounds;
    private final List<String> archWindows;
    private final List<String> frenchGeorgianWindows;
    private final List<String> romanesqueArches;
    private final List<String> modernArches;
    private final List<String> gothicArches;
    private final List<String> alcoves;

    private ErydonModelFamilyIndex(List<String> columns,
                                   List<String> cornices,
                                   List<String> cofferedCeilings,
                                   List<String> layerVertical,
                                   List<String> surrounds,
                                   List<String> archWindows,
                                   List<String> frenchGeorgianWindows,
                                   List<String> romanesqueArches,
                                   List<String> modernArches,
                                   List<String> gothicArches,
                                   List<String> alcoves) {
        this.columns = List.copyOf(columns);
        this.cornices = List.copyOf(cornices);
        this.cofferedCeilings = List.copyOf(cofferedCeilings);
        this.layerVertical = List.copyOf(layerVertical);
        this.surrounds = List.copyOf(surrounds);
        this.archWindows = List.copyOf(archWindows);
        this.frenchGeorgianWindows = List.copyOf(frenchGeorgianWindows);
        this.romanesqueArches = List.copyOf(romanesqueArches);
        this.modernArches = List.copyOf(modernArches);
        this.gothicArches = List.copyOf(gothicArches);
        this.alcoves = List.copyOf(alcoves);
    }

    static ErydonModelFamilyIndex get() {
        ErydonModelFamilyIndex local = instance;
        if (local != null) {
            return local;
        }

        synchronized (ErydonModelFamilyIndex.class) {
            if (instance == null) {
                instance = build();
            }
            return instance;
        }
    }

    List<String> columns() {
        return columns;
    }

    List<String> cornices() {
        return cornices;
    }

    List<String> cofferedCeilings() {
        return cofferedCeilings;
    }

    List<String> layerVertical() {
        return layerVertical;
    }

    List<String> surrounds() {
        return surrounds;
    }

    List<String> archWindows() {
        return archWindows;
    }

    List<String> frenchGeorgianWindows() {
        return frenchGeorgianWindows;
    }

    List<String> romanesqueArches() {
        return romanesqueArches;
    }

    List<String> modernArches() {
        return modernArches;
    }

    List<String> gothicArches() {
        return gothicArches;
    }

    List<String> alcoves() {
        return alcoves;
    }

    static boolean isColumnBlock(String path) {
        path = ErydonIdMigration.legacyResourcePath(path);
        return path.contains("column_circular")
                || path.contains("column_gothic")
                || path.contains("column_square");
    }

    static boolean isCorniceBlock(String path) {
        path = ErydonIdMigration.legacyResourcePath(path);
        return path.contains("cornice_gothic")
                || path.contains("cornice_georgian")
                || path.contains("cornice_guilloche")
                || path.contains("cornice_modern");
    }

    static boolean isCofferedCeilingBlock(String path) {
        path = ErydonIdMigration.legacyResourcePath(path);
        return path.contains("ceiling_coffered_georgian_")
                || path.contains("ceiling_coffered_guilloche_")
                || path.contains("ceiling_coffered_modern_");
    }

    static boolean isLayerVerticalBlock(String path) {
        return LayerVerticalBakedModel.isLayerVerticalBlock(ErydonIdMigration.legacyResourcePath(path));
    }

    static boolean isSurroundBlock(String path) {
        path = ErydonIdMigration.legacyResourcePath(path);
        return path.contains("_surround_georgian")
                || path.contains("_surround_guilloche")
                || path.contains("_surround_gothic_ornate")
                || path.contains("_surround_modern");
    }

    static boolean isWindowArchBlock(String path) {
        return ErydonIdMigration.legacyResourcePath(path).contains("_window_arch");
    }

    static boolean isWindowFrenchGeorgianBlock(String path) {
        return ErydonIdMigration.legacyResourcePath(path).contains("_window_french_georgian");
    }

    static boolean isArchRomanesqueBlock(String path) {
        return ErydonIdMigration.legacyResourcePath(path).contains("_arch_romanesque");
    }

    static boolean isArchModernBlock(String path) {
        return ErydonIdMigration.legacyResourcePath(path).contains("_arch_modern");
    }

    static boolean isArchGothicBlock(String path) {
        return ErydonIdMigration.legacyResourcePath(path).contains("_arch_gothic");
    }

    static boolean isGeorgianAlcoveBlock(String path) {
        return ErydonIdMigration.legacyResourcePath(path).contains("_alcove_georgian");
    }

    static boolean isGothicAlcoveBlock(String path) {
        return ErydonIdMigration.legacyResourcePath(path).contains("_alcove_gothic");
    }

    static boolean isAlcoveBlock(String path) {
        return isGeorgianAlcoveBlock(path) || isGothicAlcoveBlock(path);
    }

    private static ErydonModelFamilyIndex build() {
        List<String> columns = new ArrayList<>();
        List<String> cornices = new ArrayList<>();
        List<String> cofferedCeilings = new ArrayList<>();
        List<String> layerVertical = new ArrayList<>();
        List<String> surrounds = new ArrayList<>();
        List<String> archWindows = new ArrayList<>();
        List<String> frenchGeorgianWindows = new ArrayList<>();
        List<String> romanesqueArches = new ArrayList<>();
        List<String> modernArches = new ArrayList<>();
        List<String> gothicArches = new ArrayList<>();
        List<String> alcoves = new ArrayList<>();

        for (Identifier id : Registries.BLOCK.getIds()) {
            if (!Erydon.MOD_ID.equals(id.getNamespace())) {
                continue;
            }

            String path = id.getPath();
            if (isColumnBlock(path)) {
                columns.add(path);
            }
            if (isCorniceBlock(path)) {
                cornices.add(path);
            }
            if (isCofferedCeilingBlock(path)) {
                cofferedCeilings.add(path);
            }
            if (isLayerVerticalBlock(path)) {
                layerVertical.add(path);
            }
            if (isSurroundBlock(path)) {
                surrounds.add(path);
            }
            if (isWindowArchBlock(path)) {
                archWindows.add(path);
            }
            if (isWindowFrenchGeorgianBlock(path)) {
                frenchGeorgianWindows.add(path);
            }
            if (isArchRomanesqueBlock(path)) {
                romanesqueArches.add(path);
            }
            if (isArchModernBlock(path)) {
                modernArches.add(path);
            }
            if (isArchGothicBlock(path)) {
                gothicArches.add(path);
            }
            if (isAlcoveBlock(path)) {
                alcoves.add(path);
            }
        }

        return new ErydonModelFamilyIndex(
                columns,
                cornices,
                cofferedCeilings,
                layerVertical,
                surrounds,
                archWindows,
                frenchGeorgianWindows,
                romanesqueArches,
                modernArches,
                gothicArches,
                alcoves
        );
    }
}
