package com.oliver.erydon.block;

public final class WallLightBlock extends LightBlock {

    public WallLightBlock(Settings settings) {
        super(settings, Layout.WALL);
    }

    @Override
    protected boolean usesCompactWallStateSchema() {
        return true;
    }
}
