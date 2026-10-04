package com.oliver.erydon.client.model;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.nio.file.Path;
import javax.imageio.ImageIO;

import static com.oliver.erydon.client.model.SynapheiaSlopeConnections.*;
import static org.junit.jupiter.api.Assertions.*;

class SynapheiaDiagonalTrimTest {
    @AfterEach void clear() { SynapheiaDiagonalTrim.clear(); }

    private static Point point(Direction face, float s, float y) {
        float boundary = face.getDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
        return face.getAxis() == Direction.Axis.X ? new Point(boundary, y, s) : new Point(s, y, boundary);
    }

    private static Surface triangle(Direction face, float height, boolean upsideDown) {
        return new Surface(face, point(face, 0, upsideDown ? 1 : 0),
                point(face, 1, upsideDown ? 1 : 0), point(face, 0, upsideDown ? 1 - height : height));
    }

    private static int exposed(Direction face, Surface surface) {
        return SynapheiaDiagonalTrim.exposedEdges(face, surface, (x, y, z, edge) -> false);
    }

    @Test void diagonalOnlyDrawsOnStandardAndShallowTrimSides() {
        var trim = rule("trim");
        for (String suffix : List.of("slope", "slope_shallow_lower", "slope_shallow_upper")) {
            assertTrue(SynapheiaDiagonalTrim.eligible(new Identifier("erydon", "glacium_trim_bronze_" + suffix), trim, Direction.NORTH));
            assertFalse(SynapheiaDiagonalTrim.eligible(new Identifier("erydon", "glacium_trim_bronze_" + suffix), trim, Direction.UP));
        }
        assertFalse(SynapheiaDiagonalTrim.eligible(new Identifier("erydon", "glacium_trim_bronze_slope_steep_lower"), trim, Direction.NORTH));
        assertFalse(SynapheiaDiagonalTrim.eligible(new Identifier("erydon", "glacium_guilloche_bronze_slope"), trim, Direction.NORTH));
        assertFalse(SynapheiaDiagonalTrim.eligible(new Identifier("erydon", "glacium_trim_bronze_slope"), rule("guilloche"), Direction.NORTH));
    }

    @Test void triangleRibbonsStayInsideThePhysicalFaceAndTheSpriteForEveryFacingAndHalf() {
        for (Direction face : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
            for (float height : List.of(.5F, 1F)) for (boolean inverted : List.of(false, true)) {
                Surface side = triangle(face, height, inverted);
                int edges = exposed(face, side);
                assertEquals(1, Integer.bitCount(edges));
                var fragments = SynapheiaDiagonalTrim.fragments(face, side, edges);
                assertFalse(fragments.isEmpty());
                for (var primitive : fragments) {
                    assertEquals(4, primitive.size());
                    assertOutwardWinding(face, primitive);
                    float midU = 0, midV = 0;
                    for (var vertex : primitive) { midU += vertex.u() / 4; midV += vertex.v() / 4; }
                    float radiusU = Math.abs(primitive.get(0).u() - midU);
                    float radiusV = Math.abs(primitive.get(0).v() - midV);
                    for (var vertex : primitive) {
                        float s = face.getAxis() == Direction.Axis.X ? vertex.z() : vertex.x();
                        float y = inverted ? 1 - vertex.y() : vertex.y();
                        assertTrue(s >= -.00001F && s <= 1.00001F && y >= -.00001F);
                        assertTrue(y <= height * (1 - s) + .00001F, "Ribbon must not fill the missing half of a ramp");
                        assertTrue(vertex.u() >= -.00001F && vertex.u() <= 1.00001F);
                        assertTrue(vertex.v() >= -.00001F && vertex.v() <= SynapheiaDiagonalTrim.RIBBON_WIDTH + .00001F);
                        assertEquals(radiusU, Math.abs(vertex.u() - midU), .00001F, "Iris ghost keeps rectangular POM bounds");
                        assertEquals(radiusV, Math.abs(vertex.v() - midV), .00001F, "Iris ghost keeps rectangular POM bounds");
                    }
                }
            }
        }
    }

    @Test void shallowUpperTrapezoidsKeepTheirDiagonalInsideTheOutline() {
        for (Direction face : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
            for (boolean inverted : List.of(false, true)) {
                Surface side = inverted
                        ? new Surface(face, point(face, 0, 0), point(face, 1, .5F), point(face, 1, 1), point(face, 0, 1))
                        : new Surface(face, point(face, 0, 0), point(face, 1, 0), point(face, 1, .5F), point(face, 0, 1));
                int edges = exposed(face, side);
                assertEquals(1, Integer.bitCount(edges));
                var fragments = SynapheiaDiagonalTrim.fragments(face, side, edges);
                assertFalse(fragments.isEmpty());
                assertTrue(fragments.size() <= 6);
                for (var primitive : fragments) {
                    assertOutwardWinding(face, primitive);
                    for (var vertex : primitive) {
                        float s = face.getAxis() == Direction.Axis.X ? vertex.z() : vertex.x();
                        float y = inverted ? 1 - vertex.y() : vertex.y();
                        assertTrue(s >= -.00001F && s <= 1.00001F && y >= -.00001F);
                        assertTrue(y <= 1 - .5F * s + .00001F);
                    }
                }
            }
        }
    }

    @Test void mergedSideGetsOneSmallBorderIndependentOfItsInternalSourceStrips() {
        Direction face = Direction.NORTH;
        Surface whole = triangle(face, 1, false);
        int edges = exposed(face, whole);
        var fragments = SynapheiaDiagonalTrim.fragments(face, whole, edges);
        float width = SynapheiaDiagonalTrim.RIBBON_WIDTH;
        assertEquals(width * Math.sqrt(2) - width * width, visibleArea(fragments), .00002F);
        assertTrue(fragments.size() <= 6, "A shared diagonal needs only a handful of primitives");
        List<Point> stripPoints = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            float s0 = index / 16F, s1 = (index + 1) / 16F;
            stripPoints.addAll(List.of(point(face, s0, 0), point(face, s1, 0),
                    point(face, s1, 1 - s1), point(face, s0, 1 - s0)));
        }
        Surface merged = hull(face, stripPoints);
        var mergedFragments = SynapheiaDiagonalTrim.fragments(face, merged, exposed(face, merged));
        assertEquals(visibleArea(fragments), visibleArea(mergedFragments), .00002F);
        assertEquals(fragments.size(), mergedFragments.size());
    }

    @Test void joinedPhysicalDiagonalOmitsItsRibbonAndGapsRetainIt() {
        Direction face = Direction.NORTH;
        Surface whole = triangle(face, 1, false);
        Surface acrossPlane = triangle(Direction.SOUTH, 1, false);
        assertEquals(0, SynapheiaDiagonalTrim.exposedEdges(face, whole, (x, y, z, edge) -> {
            assertEquals(0, x); assertEquals(0, y); assertEquals(-1, z);
            return acrossPlane.meets(edge, x, y, z);
        }));
        Surface gap = new Surface(Direction.SOUTH, new Point(0, .25F, 1), new Point(1, .25F, 1), new Point(0, 1.25F, 1));
        assertEquals(1, Integer.bitCount(SynapheiaDiagonalTrim.exposedEdges(face, whole,
                (x, y, z, edge) -> gap.meets(edge, x, y, z))));
        assertTrue(SynapheiaDiagonalTrim.fragments(face, whole, 0).isEmpty());
    }

    @Test void matchingSourceGeometryReusesItsCachedPrimitivesUntilReload() {
        Surface side = triangle(Direction.NORTH, 1, false);
        int edges = exposed(Direction.NORTH, side);
        var first = SynapheiaDiagonalTrim.fragments(Direction.NORTH, side, edges);
        for (int index = 0; index < 1000; index++) {
            assertSame(first, SynapheiaDiagonalTrim.fragments(Direction.NORTH, side, edges));
        }
        assertEquals(1, SynapheiaDiagonalTrim.cachedFragmentCount());
        SynapheiaDiagonalTrim.clear();
        assertEquals(0, SynapheiaDiagonalTrim.cachedFragmentCount());
        assertNotSame(first, SynapheiaDiagonalTrim.fragments(Direction.NORTH, side, edges));
    }

    @Test void raisedDiagonalContinuesThroughCubeCornerWithoutASquareInnerCorner() {
        float stripe=7.5F/64F;
        for(Direction face:List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST))
            for(boolean inverted:List.of(false,true)) {
                Surface cube=cubeFace(face),ramp=triangle(face,1,inverted);
                int x=face.getAxis()==Direction.Axis.Z?1:0,z=face.getAxis()==Direction.Axis.X?1:0;
                var scene=List.of(new SynapheiaDiagonalTrim.PlacedSurface(cube,0,0,0),
                        new SynapheiaDiagonalTrim.PlacedSurface(ramp,0,inverted?-1:1,0),
                        new SynapheiaDiagonalTrim.PlacedSurface(ramp,x,0,z));
                var primitives=SynapheiaDiagonalTrim.compose(face,cube,scene);
                assertTrue(primitives.size()<=48);
                int cornerSamples=0;
                for(var p:stripeSamples(primitives,stripe)) {
                    float s=face.getAxis()==Direction.Axis.X?p.z():p.x(),y=inverted?1-p.y():p.y();
                    if(s>.6F && y>.6F) {
                        cornerSamples++;
                        assertEquals(2-Math.sqrt(2)*stripe,s+y,.0001F,
                                "The inset diagonal must cross the cube corner without a square CTM L");
                    }
                }
                assertTrue(cornerSamples>=2,"A Y+1 join needs the diagonal's missing inset section inside the cube");
                assertSame(primitives,SynapheiaDiagonalTrim.compose(face,cube,scene));
            }
    }

    @Test void acuteEndpointsMeetTheAxisStripesAtTheirInsetMitre() {
        float stripe=7.5F/64F;
        for(float height:List.of(.5F,1F)) {
            Surface side=triangle(Direction.NORTH,height,false);
            var pieces=SynapheiaDiagonalTrim.compose(Direction.NORTH,side,
                    List.of(new SynapheiaDiagonalTrim.PlacedSurface(side,0,0,0)));
            assertTrue(pieces.size()<=48);
            float normalLength=(float)Math.sqrt(1+height*height);
            float bottomJunction=1-stripe*(1+normalLength)/height;
            int bottomSamples=0;
            for(var p:stripeSamples(pieces,stripe)) {
                if(Math.abs(p.y()-stripe)<.0001F) {
                    bottomSamples++;
                    assertTrue(p.x()<=bottomJunction+.0001F,"The bottom stripe must stop at the diagonal mitre");
                }
                assertTrue(p.x()>=stripe-.0001F && p.y()>=stripe-.0001F,
                        "The diagonal cannot overrun the inset vertical or bottom border");
            }
            assertTrue(bottomSamples>=2);
        }
    }

    @Test void everyOpaquePixelRowMitresPastTheRealRampsRenderSafetySliver() throws Exception {
        // Native ramp sides are four-point trapezoids, not ideal triangles: their
        // 0.001-high end exists solely to keep POM geometry non-degenerate.
        for(String metal:List.of("bronze","silver")) {
            var image=ImageIO.read(Path.of("src/main/resources/assets/minecraft/textures/optifine/ctm/overlay/trim",
                    metal,"14.png").toFile());
            List<Integer> opaqueRows=new ArrayList<>();
            for(int row=0;row<image.getHeight();row++) {
                if((image.getRGB(image.getWidth()/2,row)>>>24)!=0) opaqueRows.add(row);
            }
            assertEquals(List.of(6,7,8),opaqueRows,"Tests must follow the actual visible sprite inset, not the transparent ribbon bounds");
            for(Direction face:List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST))
                for(float height:List.of(.5F,1F)) for(boolean inverted:List.of(false,true))
                    for(boolean mirrored:List.of(false,true)) {
                        Surface realSide=renderSafetySide(face,height,inverted,mirrored);
                        var pieces=SynapheiaDiagonalTrim.compose(face,realSide,
                                List.of(new SynapheiaDiagonalTrim.PlacedSurface(realSide,0,0,0)));
                        assertTrue(pieces.size()<=48);
                        for(int row:opaqueRows) {
                            float stripe=(row+.5F)/image.getHeight();
                            float normalLength=(float)Math.sqrt(1+height*height);
                            float bottomJunction=1-stripe*(1+normalLength)/height;
                            int sampled=0;
                            for(var p:stripeSamples(pieces,stripe)) {
                                sampled++;
                                float s=face.getAxis()==Direction.Axis.X?p.z():p.x();
                                if(mirrored) s=1-s;
                                float y=inverted?1-p.y():p.y();
                                assertTrue(s>=stripe-.0001F && y>=stripe-.0001F,
                                        "An opaque diagonal pixel must not run beyond the inset axis junction");
                                if(Math.abs(y-stripe)<.0001F)
                                    assertTrue(s<=bottomJunction+.0001F,"The acute gold endpoint joins the inset horizontal stroke");
                            }
                            assertTrue(sampled>=6,"All three perimeter strokes need visible pixel samples");
                        }
                    }
        }
    }

    private static Surface renderSafetySide(Direction face,float height,boolean inverted,boolean mirrored) {
        Point[] points={point(face,0,0),point(face,1,0),point(face,1,.001F),point(face,0,height)};
        for(int index=0;index<points.length;index++) {
            Point p=points[index];
            float s=face.getAxis()==Direction.Axis.X?p.z():p.x();
            points[index]=point(face,mirrored?1-s:s,inverted?1-p.y():p.y());
        }
        return new Surface(face,points);
    }

    @Test void physicalCompositionPreservesTheConnectedInteriorTileAndAtlasBounds() {
        assertEquals(26,SynapheiaRepeatBakedModel.connectedTileIndex(255));
        for(Direction face:List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST)) {
            Surface side=triangle(face,1,false);
            var pieces=SynapheiaDiagonalTrim.compose(face,side,
                    List.of(new SynapheiaDiagonalTrim.PlacedSurface(side,0,0,0)));
            for(var quad:pieces) {
                assertOutwardWinding(face,quad);
                for(var p:quad) {
                    assertTrue(p.u()>=-.0001F && p.u()<=1.0001F);
                    assertTrue(p.v()>=-.0001F && p.v()<=SynapheiaDiagonalTrim.RIBBON_WIDTH+.0001F);
                }
            }
        }
    }

    @Test void gradientPairsRemainWithinTheirRealFacesAndKeepBoundedPrimitives() {
        float[][] profiles={{1,0},{1,.5F},{.5F,0}};
        for(Direction face:List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST))
            for(boolean inverted:List.of(false,true)) for(var first:profiles) for(var second:profiles) {
                float delta=first[1]-second[0];
                if(Math.abs(delta-Math.round(delta))>.00001F) continue;
                int dy=Math.round(delta)*(inverted?-1:1);
                Surface a=profile(face,first[0],first[1],inverted),b=profile(face,second[0],second[1],inverted);
                int dx=face.getAxis()==Direction.Axis.Z?1:0,dz=face.getAxis()==Direction.Axis.X?1:0;
                var scene=List.of(new SynapheiaDiagonalTrim.PlacedSurface(a,0,0,0),
                        new SynapheiaDiagonalTrim.PlacedSurface(b,dx,dy,dz));
                var pieces=SynapheiaDiagonalTrim.compose(face,a,scene);
                assertTrue(pieces.size()<=48);
                for(var quad:pieces) {
                    assertOutwardWinding(face,quad);
                    for(var p:quad) {
                        float s=face.getAxis()==Direction.Axis.X?p.z():p.x(),y=inverted?1-p.y():p.y();
                        assertTrue(s>=-.0001F && s<=1.0001F && y>=-.0001F);
                        assertTrue(y<=first[0]+(first[1]-first[0])*s+.0001F);
                        assertTrue(p.u()>=-.0001F && p.u()<=1.0001F && p.v()>=-.0001F
                                && p.v()<=SynapheiaDiagonalTrim.RIBBON_WIDTH+.0001F);
                    }
                }
            }
    }

    @Test void detachedDiagonalDoesNotActivateCubeCompositionAndOccludedFacesDoNotConnect() {
        Direction face=Direction.NORTH;
        Surface cube=cubeFace(face),slope=triangle(face,1,false);
        assertFalse(SynapheiaDiagonalTrim.affects(face,cube,new SynapheiaDiagonalTrim.PlacedSurface(slope,1,1,0)),
                "A far diagonal does not affect a cube merely because its face touches one corner");
        assertTrue(SynapheiaDiagonalTrim.affects(face,cube,new SynapheiaDiagonalTrim.PlacedSurface(slope,0,1,0)));
        assertTrue(SynapheiaDiagonalTrim.affects(face,cube,new SynapheiaDiagonalTrim.PlacedSurface(slope,1,0,0)));
        Identifier id=new Identifier("erydon","glacium_trim_bronze_block"),air=new Identifier("minecraft","air");
        var base=rule("trim");
        var seamRule=new SynapheiaManifest.Rule(base.resourceId(),base.sourcePack(),base.method(),base.tiles(),base.faces(),
                Set.of(id),Set.of(),base.overlayShape(),base.overlayConnection(),true,20);
        assertTrue(SynapheiaRepeatBakedModel.overlayNeighbourVisible(seamRule,id,id,air));
        assertFalse(SynapheiaRepeatBakedModel.overlayNeighbourVisible(seamRule,id,id,id),
                "An occluded neighbouring side cannot erase the visible face's perimeter");
        assertFalse(SynapheiaRepeatBakedModel.overlayNeighbourVisible(seamRule,id,air,air));
    }

    @Test void variedNeighbourhoodPlansEvictAtTheRetentionLimitAndResetOnReload() {
        SynapheiaDiagonalTrim.clear();
        List<Surface> shapes=new ArrayList<>();
        List<List<List<SynapheiaDiagonalTrim.Vertex>>> retained=new ArrayList<>();
        for(int index=0;index<SynapheiaDiagonalTrim.MAX_COMPOSITIONS+16;index++) {
            Surface side=triangle(Direction.NORTH,1,false);
            shapes.add(side);
            retained.add(SynapheiaDiagonalTrim.compose(Direction.NORTH,side,
                    List.of(new SynapheiaDiagonalTrim.PlacedSurface(side,0,0,0))));
        }
        assertTrue(SynapheiaDiagonalTrim.cachedCompositionCount()<=SynapheiaDiagonalTrim.MAX_COMPOSITIONS);
        boolean evicted=false;
        for(int index=0;index<shapes.size();index++) {
            Surface side=shapes.get(index);
            var result=SynapheiaDiagonalTrim.compose(Direction.NORTH,side,
                    List.of(new SynapheiaDiagonalTrim.PlacedSurface(side,0,0,0)));
            if(result!=retained.get(index)) { evicted=true;break; }
        }
        assertTrue(evicted,"The cap must evict retained geometry rather than only report a bounded number");
        SynapheiaDiagonalTrim.clear();
        assertEquals(0,SynapheiaDiagonalTrim.cachedCompositionCount());
    }

    private static Surface profile(Direction face,float high,float low,boolean inverted) {
        if(low==0) return triangle(face,high,inverted);
        return inverted?new Surface(face,point(face,0,1-high),point(face,1,1-low),point(face,1,1),point(face,0,1))
                :new Surface(face,point(face,0,0),point(face,1,0),point(face,1,low),point(face,0,high));
    }

    private static List<SynapheiaDiagonalTrim.Vertex> stripeSamples(List<List<SynapheiaDiagonalTrim.Vertex>> pieces,float stripe) {
        List<SynapheiaDiagonalTrim.Vertex> result=new ArrayList<>();
        for(var quad:pieces) {
            List<SynapheiaDiagonalTrim.Vertex> visible=new ArrayList<>();
            for(var p:quad) if(visible.stream().noneMatch(q -> Math.abs(q.x()-p.x())<.00001F
                    && Math.abs(q.y()-p.y())<.00001F && Math.abs(q.z()-p.z())<.00001F)) visible.add(p);
            for(int index=0;index<visible.size();index++) {
                var a=visible.get(index);var b=visible.get((index+1)%visible.size());
                if(Math.min(a.v(),b.v())>stripe || Math.max(a.v(),b.v())<stripe || Math.abs(b.v()-a.v())<.000001F) continue;
                float t=(stripe-a.v())/(b.v()-a.v());
                result.add(new SynapheiaDiagonalTrim.Vertex(a.x()+(b.x()-a.x())*t,a.y()+(b.y()-a.y())*t,
                        a.z()+(b.z()-a.z())*t,a.u()+(b.u()-a.u())*t,stripe));
            }
        }
        return result;
    }

    private static float visibleArea(List<List<SynapheiaDiagonalTrim.Vertex>> primitives) {
        float area = 0;
        for (var primitive : primitives) {
            float twiceArea = 0;
            for (int index = 0; index < primitive.size(); index++) {
                var a = primitive.get(index); var b = primitive.get((index + 1) % primitive.size());
                twiceArea += a.x() * b.y() - b.x() * a.y();
            }
            area += Math.abs(twiceArea) * .5F;
        }
        return area;
    }

    private static void assertOutwardWinding(Direction face, List<SynapheiaDiagonalTrim.Vertex> primitive) {
        var a = primitive.get(0); var b = primitive.get(1); var c = primitive.get(2);
        float x1 = b.x() - a.x(), y1 = b.y() - a.y(), z1 = b.z() - a.z();
        float x2 = c.x() - a.x(), y2 = c.y() - a.y(), z2 = c.z() - a.z();
        float dot = face.getOffsetX() * (y1 * z2 - z1 * y2)
                + face.getOffsetY() * (z1 * x2 - x1 * z2)
                + face.getOffsetZ() * (x1 * y2 - y1 * x2);
        assertTrue(dot > 0, "Border quads must face outwards");
    }

    private static SynapheiaManifest.Rule rule(String motif) {
        var tiles = IntStream.range(0, 47).mapToObj(index -> new Identifier("minecraft",
                "textures/optifine/ctm/overlay/" + motif + "/bronze/" + index)).toList();
        return new SynapheiaManifest.Rule(new Identifier("erydon", "diagonal_test"), "fixture",
                SynapheiaManifest.Method.OVERLAY_CTM, tiles, Set.of(Direction.values()), Set.of(), Set.of(),
                SynapheiaManifest.OverlayShape.SOURCE, SynapheiaManifest.OverlayConnection.RULE, false, 20);
    }
}
