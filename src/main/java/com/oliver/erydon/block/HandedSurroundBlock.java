package com.oliver.erydon.block;

import net.minecraft.block.AbstractBlock;

public class HandedSurroundBlock extends SurroundBlock {

    public HandedSurroundBlock(AbstractBlock.Settings settings) {
        super(settings);
    }

    @Override
    protected PieceType mapOuterForPosition(PieceType baseOuter, int x, int y, int width, int height) {
        // Width == 1 is a decorative pillar: use the centred (unhanded) pieces.
        if (width <= 1) {
            return baseOuter;
        }

        boolean leftEdge  = (x == 0);
        boolean rightEdge = (x == width - 1);

        // Only edges get handedness.
        if (!leftEdge && !rightEdge) {
            return baseOuter;
        }

        return switch (baseOuter) {
            case CORBEL     -> leftEdge ? PieceType.CORBEL_LH     : PieceType.CORBEL_RH;
            case SHAFT      -> leftEdge ? PieceType.SHAFT_LH      : PieceType.SHAFT_RH;
            case PLINTH     -> leftEdge ? PieceType.PLINTH_LH     : PieceType.PLINTH_RH;
            case SHORT_JAMB -> leftEdge ? PieceType.SHORT_JAMB_LH : PieceType.SHORT_JAMB_RH;
            default -> baseOuter;
        };
    }


    // Debug-stick MANUAL cycling list for handed-edge styles
    private static final Section[] MANUAL_SECTIONS_HANDED = new Section[] {
            Section.EMPTY,

            // Single-piece variants (handed where needed)
            Section.CORBEL_LH,
            Section.CORBEL_RH,
            Section.PLINTH_LH,
            Section.PLINTH_RH,
            Section.SHAFT_LH,
            Section.SHAFT_RH,

            Section.MANTEL,
            Section.HEARTH,

            // Edge combos
            Section.CORBEL_LH_MANTEL_STUB_LH,
            Section.CORBEL_RH_MANTEL_STUB_RH,
            Section.PLINTH_LH_HEARTH_STUB_LH,
            Section.PLINTH_RH_HEARTH_STUB_RH,

            // Short (height==1) variants
            Section.SHORT_JAMB_LH,
            Section.SHORT_JAMB_RH,
            Section.SHORT_MANTEL,
            Section.SHORT_JAMB_LH_SHORT_MANTEL_STUB_LH,
            Section.SHORT_JAMB_RH_SHORT_MANTEL_STUB_RH
    };

    @Override
    protected Section[] getManualSections() {
        return MANUAL_SECTIONS_HANDED;
    }
}
