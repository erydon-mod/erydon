package com.oliver.erydon.block;

import net.minecraft.block.AbstractBlock;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

public class ModernSurroundBlock extends HandedSurroundBlock {

    private static final VoxelShape SHAPE_CORBEL = makeModernCorbelShape();
    private static final VoxelShape SHAPE_CORBEL_LH = makeModernCorbelLhShape();
    private static final VoxelShape SHAPE_CORBEL_RH = makeModernCorbelRhShape();
    private static final VoxelShape SHAPE_HEARTH = makeModernHearthShape();
    private static final VoxelShape SHAPE_HEARTH_STUB_LH = makeModernHearthStubLhShape();
    private static final VoxelShape SHAPE_HEARTH_STUB_RH = makeModernHearthStubRhShape();
    private static final VoxelShape SHAPE_MANTEL = makeModernMantelShape();
    private static final VoxelShape SHAPE_MANTEL_STUB_LH = makeModernMantelStubLhShape();
    private static final VoxelShape SHAPE_MANTEL_STUB_RH = makeModernMantelStubRhShape();
    private static final VoxelShape SHAPE_PLINTH = makeModernPlinthShape();
    private static final VoxelShape SHAPE_PLINTH_LH = makeModernPlinthLhShape();
    private static final VoxelShape SHAPE_PLINTH_RH = makeModernPlinthRhShape();
    private static final VoxelShape SHAPE_SHAFT = makeModernShaftShape();
    private static final VoxelShape SHAPE_SHAFT_LH = makeModernShaftLhShape();
    private static final VoxelShape SHAPE_SHAFT_RH = makeModernShaftRhShape();
    private static final VoxelShape SHAPE_SHORT_JAMB = makeModernShortJambShape();
    private static final VoxelShape SHAPE_SHORT_JAMB_LH = makeModernShortJambLhShape();
    private static final VoxelShape SHAPE_SHORT_JAMB_RH = makeModernShortJambRhShape();
    private static final VoxelShape SHAPE_SHORT_MANTEL = makeModernShortMantelShape();
    private static final VoxelShape SHAPE_SHORT_MANTEL_STUB_LH = makeModernShortMantelStubLhShape();
    private static final VoxelShape SHAPE_SHORT_MANTEL_STUB_RH = makeModernShortMantelStubRhShape();

    public ModernSurroundBlock(AbstractBlock.Settings settings) {
        super(settings);
    }

    @Override
    protected VoxelShape getBaseShapeForPiece(PieceType piece) {
        return switch (piece) {
            case NONE -> VoxelShapes.empty();

            case CORBEL -> SHAPE_CORBEL;
            case CORBEL_LH -> SHAPE_CORBEL_LH;
            case CORBEL_RH -> SHAPE_CORBEL_RH;

            case MANTEL -> SHAPE_MANTEL;
            case MANTEL_STUB_LH -> SHAPE_MANTEL_STUB_LH;
            case MANTEL_STUB_RH -> SHAPE_MANTEL_STUB_RH;

            case SHAFT -> SHAPE_SHAFT;
            case SHAFT_LH -> SHAPE_SHAFT_LH;
            case SHAFT_RH -> SHAPE_SHAFT_RH;

            case PLINTH -> SHAPE_PLINTH;
            case PLINTH_LH -> SHAPE_PLINTH_LH;
            case PLINTH_RH -> SHAPE_PLINTH_RH;

            case SHORT_JAMB -> SHAPE_SHORT_JAMB;
            case SHORT_JAMB_LH -> SHAPE_SHORT_JAMB_LH;
            case SHORT_JAMB_RH -> SHAPE_SHORT_JAMB_RH;

            case SHORT_MANTEL -> SHAPE_SHORT_MANTEL;
            case SHORT_MANTEL_STUB_LH -> SHAPE_SHORT_MANTEL_STUB_LH;
            case SHORT_MANTEL_STUB_RH -> SHAPE_SHORT_MANTEL_STUB_RH;

            case HEARTH -> SHAPE_HEARTH;
            case HEARTH_STUB_LH -> SHAPE_HEARTH_STUB_LH;
            case HEARTH_STUB_RH -> SHAPE_HEARTH_STUB_RH;
        };
    }

    private static VoxelShape makeModernCorbelShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.25, 0.0, 0.0, 0.75, 0.5, 0.0625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.25, 0.375, 0.0625, 0.75, 0.5, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.25, 0.3125, 0.0625, 0.625, 0.375, 0.1875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.625, 0.0, 0.0625, 0.75, 0.375, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.25, 0.0, 0.0625, 0.375, 0.375, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.5625, 0.0, 0.0625, 0.625, 0.3125, 0.1875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.375, 0.0, 0.0625, 0.4375, 0.3125, 0.1875));
        return shape;
    }

    private static VoxelShape makeModernCorbelLhShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.5, 0.0, 0.0, 1.0, 0.5, 0.0625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.5, 0.375, 0.0625, 1.0, 0.5, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.75, 0.25, 0.0625, 1.0, 0.375, 0.1875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.5, 0.0, 0.0625, 0.625, 0.375, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.625, 0.0, 0.0625, 0.75, 0.375, 0.1875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.75, 0.0, 0.0625, 0.875, 0.125, 0.125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.75, 0.125, 0.0625, 1.0, 0.25, 0.125));
        return shape;
    }

    private static VoxelShape makeModernCorbelRhShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.0, 0.0, 0.5, 0.5, 0.0625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.375, 0.0625, 0.5, 0.5, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.25, 0.0625, 0.25, 0.375, 0.1875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.125, 0.0625, 0.25, 0.25, 0.125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.375, 0.0, 0.0625, 0.5, 0.375, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.25, 0.0, 0.0625, 0.375, 0.375, 0.1875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.125, 0.0, 0.0625, 0.25, 0.125, 0.125));
        return shape;
    }

    private static VoxelShape makeModernHearthShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.125, 0.0, 1.0, 0.1875, 0.3125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.001856875, 0.0625, 0.000473125, 1.001856875, 0.125, 0.3726325));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.0, 0.0, 1.0, 0.0625, 0.4375));
        return shape;
    }

    private static VoxelShape makeModernHearthStubLhShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.4375, 0.125, 0.0, 1.0, 0.1875, 0.3125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.371211875, 0.0625, 0.000473125, 0.998143125, 0.125, 0.3726325));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.3125, 0.0, 0.0, 1.0, 0.0625, 0.4375));
        return shape;
    }

    private static VoxelShape makeModernHearthStubRhShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.125, 0.0, 0.5625, 0.1875, 0.3125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.001856875, 0.0625, 0.000473125, 0.628788125, 0.125, 0.3726325));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.0, 0.0, 0.6875, 0.0625, 0.4375));
        return shape;
    }

    private static VoxelShape makeModernMantelShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.0, 0.0, 1.0, 0.5, 0.0625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.375, 0.0625, 1.0, 0.5, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.25, 0.0625, 1.0, 0.375, 0.1875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.125, 0.0625, 1.0, 0.25, 0.125));
        return shape;
    }

    private static VoxelShape makeModernMantelStubLhShape() {
        return VoxelShapes.empty();
    }

    private static VoxelShape makeModernMantelStubRhShape() {
        return VoxelShapes.empty();
    }

    private static VoxelShape makeModernPlinthShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.375, 0.125, 0.125, 0.4375, 1.0, 0.1875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.25, 0.125, 0.125, 0.375, 1.0, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.5625, 0.125, 0.125, 0.625, 1.0, 0.1875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.25, 0.125, 0.0, 0.75, 1.0, 0.125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.625, 0.125, 0.125, 0.75, 1.0, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.1875, 0.0625, 0.0, 0.8125, 0.125, 0.3125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.125, 0.0, 0.0, 0.875, 0.0625, 0.375));
        return shape;
    }

    private static VoxelShape makeModernPlinthLhShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.625, 0.0, 0.0625, 0.75, 1.0, 0.1875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.75, 0.0, 0.0625, 0.875, 1.0, 0.125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.5, 0.0, 0.0, 1.0, 1.0, 0.0625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.5, 0.0, 0.0625, 0.625, 1.0, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.4375, 0.0625, 0.0, 1.0, 0.125, 0.3125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.375, 0.0, 0.0, 1.0, 0.0625, 0.375));
        return shape;
    }

    private static VoxelShape makeModernPlinthRhShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.125, 0.0, 0.5, 1.0, 0.0625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.375, 0.125, 0.0625, 0.5, 1.0, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.25, 0.125, 0.0625, 0.375, 1.0, 0.1875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.125, 0.125, 0.0625, 0.25, 1.0, 0.125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.0625, 0.0, 0.5625, 0.125, 0.3125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.0, 0.0, 0.625, 0.0625, 0.375));
        return shape;
    }

    private static VoxelShape makeModernShaftShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.625, 0.0, 0.125, 0.75, 1.0, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.5625, 0.0, 0.125, 0.625, 1.0, 0.1875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.25, 0.0, 0.125, 0.375, 1.0, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.375, 0.0, 0.125, 0.4375, 1.0, 0.1875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.25, 0.0, 0.0, 0.75, 1.0, 0.125));
        return shape;
    }

    private static VoxelShape makeModernShaftLhShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.5, 0.0, 0.0, 1.0, 1.0, 0.0625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.5, 0.0, 0.0625, 0.625, 1.0, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.625, 0.0, 0.0625, 0.75, 1.0, 0.1875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.75, 0.0, 0.0625, 0.875, 1.0, 0.125));
        return shape;
    }

    private static VoxelShape makeModernShaftRhShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.0, 0.0, 0.5, 1.0, 0.0625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.375, 0.0, 0.0625, 0.5, 1.0, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.25, 0.0, 0.0625, 0.375, 1.0, 0.1875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.125, 0.0, 0.0625, 0.25, 1.0, 0.125));
        return shape;
    }

    private static VoxelShape makeModernShortJambShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.1875, 0.0625, 0.0, 0.8125, 0.125, 0.3125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.125, 0.0, 0.0, 0.875, 0.0625, 0.375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.25, 0.125, 0.0, 0.75, 1.0, 0.0625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.25, 0.875, 0.0625, 0.75, 1.0, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.625, 0.125, 0.0625, 0.75, 0.875, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.25, 0.8125, 0.0625, 0.625, 0.875, 0.1875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.5625, 0.125, 0.0625, 0.625, 0.8125, 0.1875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.25, 0.125, 0.0625, 0.375, 0.875, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.375, 0.125, 0.0625, 0.4375, 0.8125, 0.1875));
        return shape;
    }

    private static VoxelShape makeModernShortJambLhShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.5, 0.125, 0.0, 1.0, 1.0, 0.0625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.5, 0.875, 0.0625, 1.0, 1.0, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.4375, 0.0625, 0.0, 1.0, 0.125, 0.3125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.375, 0.0, 0.0, 1.0, 0.0625, 0.375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.75, 0.625, 0.0625, 1.0, 0.75, 0.125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.75, 0.125, 0.0625, 0.875, 0.625, 0.125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.625, 0.75, 0.0625, 1.0, 0.875, 0.1875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.5, 0.125, 0.0625, 0.625, 0.875, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.625, 0.125, 0.0625, 0.75, 0.75, 0.1875));
        return shape;
    }

    private static VoxelShape makeModernShortJambRhShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.75, 0.0625, 0.375, 0.875, 0.1875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.125, 0.0, 0.5, 1.0, 0.0625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.0625, 0.0, 0.5625, 0.125, 0.3125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.0, 0.0, 0.625, 0.0625, 0.375));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.375, 0.125, 0.0625, 0.5, 0.875, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.25, 0.125, 0.0625, 0.375, 0.75, 0.1875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.625, 0.0625, 0.25, 0.75, 0.125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.125, 0.125, 0.0625, 0.25, 0.625, 0.125));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.875, 0.0625, 0.5, 1.0, 0.25));
        return shape;
    }

    private static VoxelShape makeModernShortMantelShape() {
        VoxelShape shape = VoxelShapes.empty();
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.5, 0.0, 1.0, 1.0, 0.0625));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.875, 0.0625, 1.0, 1.0, 0.25));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.75, 0.0625, 1.0, 0.875, 0.1875));
        shape = VoxelShapes.union(shape, VoxelShapes.cuboid(0.0, 0.625, 0.0625, 1.0, 0.75, 0.125));
        return shape;
    }

    private static VoxelShape makeModernShortMantelStubLhShape() {
        return VoxelShapes.empty();
    }

    private static VoxelShape makeModernShortMantelStubRhShape() {
        return VoxelShapes.empty();
    }
}
