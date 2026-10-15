package com.oliver.erydon.block;

import java.util.List;

/** Immutable authored dimensions shared by coping placement, joins and rendering. */
public enum CopingDesign {
    GEORGIAN("coping_georgian", "georgian", 3.6 / 16, .125,
            List.of(new Bevel(.03125, .0671261556037529, .038041381118820716))),
    GEORGIAN_GRAND("coping_georgian_grand", "georgian_grand", 8.0 / 16, 2.7 / 16,
            List.of(new Bevel(.03125, .0671261556037529, .038041381118820716),
                    new Bevel(.125, .1608767806037529, -.05570924388117937)));

    /** Extents relative to the block boundary; an outer tier can have a negative inner reach. */
    public record Bevel(double runOverhang, double outerOverhang, double innerReach) { }

    private static final CopingDesign[] DESIGNS = values();

    private final String idSuffix;
    private final String blockSuffix;
    private final String authoringDirectory;
    private final double thickness;
    private final double overhang;
    private final List<Bevel> bevels;

    CopingDesign(String idSuffix, String authoringDirectory, double thickness, double overhang, List<Bevel> bevels) {
        this.idSuffix = idSuffix;
        this.blockSuffix = "_" + idSuffix;
        this.authoringDirectory = authoringDirectory;
        this.thickness = thickness;
        this.overhang = overhang;
        this.bevels = List.copyOf(bevels);
    }

    public String idSuffix() { return idSuffix; }
    public String authoringDirectory() { return authoringDirectory; }
    public double thickness() { return thickness; }
    public double overhang() { return overhang; }
    public List<Bevel> bevels() { return bevels; }

    public static double maxThickness() { return GEORGIAN_GRAND.thickness; }

    public static CopingDesign fromBlockPath(String path) {
        if (path == null) return null;
        for (CopingDesign design : DESIGNS) if (path.endsWith(design.blockSuffix)) return design;
        return null;
    }
}
