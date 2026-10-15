package com.oliver.erydon.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oliver.erydon.block.CopingBlock;
import com.oliver.erydon.block.CopingConnections;
import com.oliver.erydon.block.CopingDesign;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Authored dimensions and independent corner-face witnesses for the additional coping design. */
class GrandCopingGeometryTest {
    private static final Path ROOT=Path.of("src/main/resources/assets/erydon/authoring_models/block/coping/georgian_grand");

    @Test void allSupportProfilesKeepTheHalfBlockThicknessAndHalfHeightTranslation() throws Exception {
        for(var profile:CopingBlock.Surface.values()) {
            var geometry=geometry(profile);
            var faces=geometry.joined(profile,0);
            assertFalse(faces.isEmpty(),profile.toString());
            assertEquals(.5,faces.stream().flatMap(s -> s.vertices().stream())
                    .mapToDouble(v -> CopingGeometry.normalHeight(v,profile)).max().orElseThrow(),.00002,profile.toString());
            for(var surface:faces) for(var vertex:surface.vertices()) {
                assertTrue(Float.isFinite(vertex.x()) && Float.isFinite(vertex.y()) && Float.isFinite(vertex.z()));
                assertTrue(Float.isFinite(vertex.u()) && Float.isFinite(vertex.v()));
            }
        }
        var full=geometry(CopingBlock.Surface.FLAT).joined(CopingBlock.Surface.FLAT,0);
        var half=geometry(CopingBlock.Surface.FLAT_HALF).joined(CopingBlock.Surface.FLAT_HALF,0);
        assertEquals(full.size(),half.size());
        for(int i=0;i<full.size();i++) for(int j=0;j<full.get(i).vertices().size();j++) {
            var a=full.get(i).vertices().get(j); var b=half.get(i).vertices().get(j);
            assertEquals(a.x(),b.x(),.000001); assertEquals(a.y()-.5,b.y(),.000001); assertEquals(a.z(),b.z(),.000001);
            assertEquals(a.u(),b.u()); assertEquals(a.v(),b.v());
        }
    }

    @Test void everyAuthoredTriangularCornerSurvivesUntilOneOfItsOwnEdgesJoins() throws Exception {
        var source=source(CopingBlock.Surface.FLAT);
        var geometry=geometry(CopingBlock.Surface.FLAT);
        int triangles=0;
        for(var item:source.getAsJsonArray("elements")) {
            var element=item.getAsJsonObject();
            if(!element.has("erydon_coping_corner")) continue;
            String name=element.get("name").getAsString();
            int edge=(name.contains("west") ? CopingConnections.WEST : 0)
                    |(name.contains("east") ? CopingConnections.EAST : 0)
                    |(name.contains("north") ? CopingConnections.NORTH : 0)
                    |(name.contains("south") ? CopingConnections.SOUTH : 0);
            var one=source.deepCopy(); var elements=new JsonArray(); elements.add(element.deepCopy()); one.add("elements",elements);
            var snapshots=ErydonRawModelLoadingPlugin.surfaceSnapshots("grand_corner_witness",new Identifier("erydon","grand_corner_witness"),one);
            assertEquals(1,snapshots.size());
            var points=snapshots.get(0).vertexPositions();
            assertEquals(3,java.util.Arrays.stream(points).map(p -> java.util.Arrays.toString(p)).distinct().count(),"A corner stopped being a genuine triangle");
            for(int mask=0;mask<16;mask++) {
                boolean present=geometry.joined(CopingBlock.Surface.FLAT,mask).stream().anyMatch(face -> sameVertices(face.vertices(),points));
                assertEquals((edge&mask)==0,present,"Corner ownership changed: "+name+" mask="+mask);
            }
            triangles++;
        }
        assertEquals(32,triangles,"Both tiers must retain all four closed corner profiles");
    }

    @Test void finiteJoinMetricsMatchBothAuthoredBevelTiersOnEverySide() throws Exception {
        var source=source(CopingBlock.Surface.FLAT);
        int checked=0;
        for(var item:source.getAsJsonArray("elements")) {
            var element=item.getAsJsonObject(); String name=element.get("name").getAsString();
            if(!name.startsWith("edge_") || element.has("erydon_coping_corner")) continue;
            var one=source.deepCopy(); var elements=new JsonArray(); elements.add(element.deepCopy()); one.add("elements",elements);
            boolean cross=name.equals("edge_north") || name.equals("edge_south");
            boolean lowSide=name.equals("edge_north") || name.equals("edge_west");
            double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY,runLow=Double.POSITIVE_INFINITY,runHigh=Double.NEGATIVE_INFINITY;
            for(var face:ErydonRawModelLoadingPlugin.surfaceSnapshots("grand_bevel_witness",new Identifier("erydon","grand_bevel_witness"),one))
                for(var point:face.vertexPositions()) {
                    double lateral=cross ? point[2] : point[0],run=cross ? point[0] : point[2];
                    double offset=lowSide ? lateral : 1-lateral;
                    low=Math.min(low,offset); high=Math.max(high,offset); runLow=Math.min(runLow,run); runHigh=Math.max(runHigh,run);
                }
            var expected=CopingDesign.GEORGIAN_GRAND.bevels().get(element.get("erydon_coping_band").getAsString().equals("upper_band") ? 0 : 1);
            assertEquals(expected.outerOverhang(),-low,.000001,name);
            assertEquals(expected.innerReach(),high,.000001,name);
            assertEquals(expected.runOverhang(),-runLow,.000001,name);
            assertEquals(expected.runOverhang(),runHigh-1,.000001,name);
            checked++;
        }
        assertEquals(8,checked);
    }

    private static boolean sameVertices(List<CopingGeometry.Vertex> actual,float[][] expected) {
        if(actual.size()!=expected.length) return false;
        for(int i=0;i<expected.length;i++) {
            var a=actual.get(i); var b=expected[i];
            if(Math.abs(a.x()-b[0])>.000001 || Math.abs(a.y()-b[1])>.000001 || Math.abs(a.z()-b[2])>.000001) return false;
        }
        return true;
    }
    private static JsonObject source(CopingBlock.Surface profile) throws Exception {
        return JsonParser.parseString(Files.readString(ROOT.resolve("coping_georgian_grand_"+profile.authoringProfile().asString()+".json"))).getAsJsonObject();
    }
    private static CopingGeometry geometry(CopingBlock.Surface profile) throws Exception {
        var authored=profile.authoringProfile();
        var geometry=CopingGeometry.parse(source(profile),new Identifier("erydon","grand_geometry_test_"+authored.asString()),authored);
        return profile.aligned() ? geometry.fitted(profile.fit) : geometry.translatedY((float)(profile.high-authored.high));
    }
}
