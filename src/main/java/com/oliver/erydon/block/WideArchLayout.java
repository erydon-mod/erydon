package com.oliver.erydon.block;

import java.util.List;

import static com.oliver.erydon.block.ArchRomanesqueBlock.Arrangement;

/** Geometry-only layout shared by the client mesh and server collision cache. */
public final class WideArchLayout {
    private WideArchLayout() { }

    public record Component(Arrangement arrangement, int x, int row) { }

    public static List<Component> components(Arrangement target) {
        if (target.wideBody() != null) {
            Arrangement body = target.wideBody();
            boolean right = body.sideR() != ArchRomanesqueBlock.Side.NONE || body.columnR() || body.plinthR();
            return List.of(new Component(body, right ? 2 : 0, 0));
        }
        if (target.wideRows() == 1) {
            return List.of(new Component(Arrangement.TRIPLE_SINGLE_L, 0, 0),
                    new Component(Arrangement.TOP_LARGE, 1, 0),
                    new Component(Arrangement.TRIPLE_SINGLE_R, 2, 0));
        }
        return List.of(new Component(Arrangement.TRIPLE_TOP_L, 0, 0),
                new Component(Arrangement.TOP_LARGE, 1, 0),
                new Component(Arrangement.TRIPLE_TOP_R, 2, 0),
                new Component(Arrangement.TRIPLE_ROW2_L, 0, 1),
                new Component(Arrangement.TRIPLE_ROW2_R, 2, 1),
                // Continue the frame to the bottom of the final partially occupied row.
                new Component(Arrangement.TRIPLE_BODY_L, 0, 2),
                new Component(Arrangement.TRIPLE_BODY_R, 2, 2));
    }

    public static double scaleX(int width) { return width / 3.0; }

    public static double scaleY(Arrangement target, int width) {
        if (target.wideBody() != null || target.wideRows() == 1) return 1;
        return Math.min(width / 3.0, target.wideRows() / 2.0);
    }

    public static double x(double x, Component component, Arrangement target, int width) {
        return (x + component.x()) * scaleX(width) - target.wideX(width);
    }

    public static double y(double y, Component component, Arrangement target, int width) {
        return 1 + (y - component.row() - 1) * scaleY(target, width) + target.wideRow();
    }
}
