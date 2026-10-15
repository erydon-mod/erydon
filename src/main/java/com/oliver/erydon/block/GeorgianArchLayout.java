package com.oliver.erydon.block;

import java.util.ArrayList;
import java.util.List;

/** Shared component selection for rendered and targetable Georgian arch cells. */
public final class GeorgianArchLayout {
    private static final List<String> SUFFIXES = createSuffixes();
    private static final List<List<List<String>>> SELECTIONS = createSelections();

    private GeorgianArchLayout() { }

    public static List<String> modelSuffixes() { return SUFFIXES; }

    public static List<String> components(ArchRomanesqueBlock.Arrangement arrangement, int width) {
        return SELECTIONS.get(Math.max(1, Math.min(6, width)) - 1).get(arrangement.ordinal());
    }

    private static List<String> createSuffixes() {
        var suffixes = new ArrayList<String>(56);
        suffixes.addAll(List.of("base_left", "base_right", "middle_left", "middle_right"));
        for (int width = 1; width <= 6; width++) {
            for (int row = 0; row < (width + 2) / 3; row++) {
                for (int column = 0; column < width; column++) {
                    suffixes.add("width" + width + "_top_row" + row + "_column" + column);
                }
            }
            if (width > 3) for (int column = 0; column < width; column++) {
                suffixes.add("width" + width + "_short_top_column" + column);
            }
        }
        suffixes.add("icon");
        return List.copyOf(suffixes);
    }

    private static List<List<List<String>>> createSelections() {
        var widths = new ArrayList<List<List<String>>>(6);
        for (int width = 1; width <= 6; width++) {
            var states = new ArrayList<List<String>>();
            for (var arrangement : ArchRomanesqueBlock.Arrangement.values()) states.add(select(arrangement, width));
            widths.add(List.copyOf(states));
        }
        return List.copyOf(widths);
    }

    private static List<String> select(ArchRomanesqueBlock.Arrangement arrangement, int width) {
        if (arrangement.isVoid()) return List.of();
        if (arrangement.isWide() && arrangement.wideBody() == null) {
            int column = arrangement.wideX(width);
            if (width <= 3 || column < 0 || column >= width || arrangement.wideRow() > 1) return List.of();
            String suffix = arrangement.wideRows() == 1
                    ? "width" + width + "_short_top_column" + column
                    : "width" + width + "_top_row" + arrangement.wideRow() + "_column" + column;
            return List.of(suffix);
        }
        var body = arrangement.wideBody() == null ? arrangement : arrangement.wideBody();
        boolean small = body == ArchRomanesqueBlock.Arrangement.SMALL_TOP
                || body == ArchRomanesqueBlock.Arrangement.SMALL_BODY
                || body == ArchRomanesqueBlock.Arrangement.SMALL_BASE;
        if (!arrangement.isWide() && (body.corner() != ArchRomanesqueBlock.Corner.NONE || body.hasTopLarge())) {
            int crownWidth = small ? 1 : body.name().startsWith("DOUBLE_") ? 2 : 3;
            int column = small ? 0 : body.hasTopLarge() ? 1 : body.cornerFlip() ? crownWidth - 1 : 0;
            return List.of("width" + crownWidth + "_top_row0_column" + column);
        }
        if (arrangement.isWide() && arrangement.wideX(width) != 0 && arrangement.wideX(width) != width - 1) {
            return List.of();
        }
        String part = body.plinthL() || body.plinthR() ? "base_" : "middle_";
        if (small) return List.of(part + "left", part + "right");
        boolean right = body.sideR() != ArchRomanesqueBlock.Side.NONE || body.columnR() || body.plinthR();
        return List.of(part + (right ? "right" : "left"));
    }
}
