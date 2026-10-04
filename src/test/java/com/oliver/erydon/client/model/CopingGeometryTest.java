package com.oliver.erydon.client.model;

import com.google.gson.JsonParser;
import com.oliver.erydon.block.CopingBlock;
import com.oliver.erydon.block.CopingConnections;
import com.oliver.erydon.block.CopingHorizontalFit;
import com.oliver.erydon.block.CopingHorizontalMitre;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CopingGeometryTest {
    private static CopingBlock.Surface[] authoredProfiles() {
        return java.util.Arrays.stream(CopingBlock.Surface.values()).filter(p -> !p.aligned()).toArray(CopingBlock.Surface[]::new);
    }
    @Test void axiomFallbackRotatesPackedNormalsWithoutLosingShaderData() {
        for(int x:new int[]{-127,-89,0,89,127}) for(int z:new int[]{-127,-89,0,89,127}) {
            int packed=0xA5005D00|(x&255)|((z&255)<<16);
            int rotated=packed;
            for(int turns=0;turns<4;turns++) {
                assertEquals(rotated,CopingBakedModel.rotatePackedNormal(packed,turns));
                assertEquals(packed&0xFF00FF00,rotated&0xFF00FF00);
                assertEquals(-((byte)(rotated>>>16)),(byte)CopingBakedModel.rotatePackedNormal(rotated,1));
                rotated=CopingBakedModel.rotatePackedNormal(rotated,1);
            }
            assertEquals(packed,rotated);
        }
    }

    @Test void profileLightingIsContinuousAcrossStubSplitsAndSharedInclineJoins() {
        double[] gradients={0,0,.5,1,2,-.5,-1,-2};
        for(var profile:authoredProfiles()) for(int code=1;code<gradients.length;code++) {
            double other=gradients[code],angle=(Math.atan(profile.gradient())+Math.atan(other))/2;
            for(boolean west:new boolean[]{true,false}) for(Direction face:List.of(Direction.UP,Direction.DOWN)) {
                var joins=new CopingConnections.Joins(west ? code : code<<3);
                double sign=face==Direction.UP ? 1 : -1;
                var atJoin=CopingGeometry.profileNormal(profile,joins,face,west ? 0 : 1);
                assertEquals(sign*Math.sin(angle),atJoin.x(),.000001);
                assertEquals(sign*Math.cos(angle),atJoin.y(),.000001);
                double boundary=west ? .2 : .8;
                var before=CopingGeometry.profileNormal(profile,joins,face,boundary-.000001);
                var after=CopingGeometry.profileNormal(profile,joins,face,boundary+.000001);
                assertEquals(before.x(),after.x(),.000001,"Lighting must not step at the 20% stub");
                assertEquals(before.y(),after.y(),.000001);
                assertEquals(1,atJoin.x()*atJoin.x()+atJoin.y()*atJoin.y(),.000001);
                // Horizontal rotation preserves the same normal and shade at a shared vertex.
                for(int turns=0;turns<4;turns++) {
                    double x=turns%2==0 ? atJoin.x()*(turns==0 ? 1 : -1) : 0;
                    double z=turns%2==1 ? atJoin.x()*(turns==1 ? 1 : -1) : 0;
                    assertEquals(1,x*x+atJoin.y()*atJoin.y()+z*z,.000001);
                }
            }
        }
    }

    @Test void onlyProfileTopsAndUndersidesReceiveSmoothNormals() throws Exception {
        for(var profile:authoredProfiles()) {
            var surfaces=geometry(profile).joined(profile,new CopingConnections.Joins(1|(4<<3)));
            int smooth=0,sharp=0;
            for(var surface:surfaces) {
                if(surface.normals().isEmpty()) { sharp++; continue; }
                smooth++;
                assertEquals(Direction.Axis.Y,surface.face().getAxis());
                assertEquals(surface.vertices().size(),surface.normals().size());
                for(var normal:surface.normals()) {
                    assertEquals(1,normal.x()*normal.x()+normal.y()*normal.y()+normal.z()*normal.z(),.000001);
                    assertEquals(0,normal.z());
                }
            }
            assertTrue(smooth>0 && sharp>0,"Rotated bevels must retain sharp profile edges");
        }
    }

    @Test void editableProfilesRetainTheirAuthoredVerticesWithoutHiddenBevelStretching() throws Exception {
        for(var profile:authoredProfiles()) {
            var pitched=geometry(profile).joined(profile,0);
            var model=JsonParser.parseString(Files.readString(ROOT.resolve("coping_georgian_"+profile.asString()+".json"))).getAsJsonObject();
            var authored=ErydonRawModelLoadingPlugin.surfaceSnapshots("coping",new Identifier("erydon","coping"),model);
            for(var surface:pitched) if(!surface.bandEnd()) {
                assertTrue(authored.stream().anyMatch(snapshot -> {
                    float[][] points=snapshot.vertexPositions();
                    for(int i=0;i<4;i++) {
                        var v=surface.vertices().get(i);
                        if(Math.abs(v.x()-points[i][0])>.000001 || Math.abs(v.y()-points[i][1])>.000001
                                || Math.abs(v.z()-points[i][2])>.000001) return false;
                    }
                    return true;
                }),profile+" must render the saved Blockbench geometry directly");
            }
            double top=pitched.stream().flatMap(s -> s.vertices().stream()).mapToDouble(v -> CopingGeometry.normalHeight(v,profile)).max().orElseThrow();
            assertEquals(CopingBlock.THICKNESS,top,.00002,"All profiles must be 20% thicker");
        }
    }

    @Test void copingTexturePlanesDoNotFollowChangingLightingNormals() {
        var coping=new Identifier("erydon","glacium_coping_georgian");
        for(var lighting:Direction.values()) for(var authored:Direction.values())
            assertEquals(authored,SynapheiaRepeatBakedModel.repeatProjectionFace(coping,lighting,authored));
        var other=new Identifier("erydon","glacium_block");
        assertEquals(Direction.EAST,SynapheiaRepeatBakedModel.repeatProjectionFace(other,Direction.EAST,Direction.UP));
    }
    private static final Path ROOT=Path.of("src/main/resources/assets/erydon/authoring_models/block/coping/georgian");
    @Test void horizontalFitsPreserveTheEditedFlatThicknessUvsAndPhysicalBevelWidths() throws Exception {
        var flat=geometry(CopingBlock.Surface.FLAT);
        for(var fit:CopingHorizontalFit.values()) {
            var fitted=flat.fitted(fit).joined(CopingBlock.Surface.FLAT,0);
            var original=flat.joined(CopingBlock.Surface.FLAT,0);
            assertEquals(original.size(),fitted.size());
            for(int index=0;index<original.size();index++) {
                var a=original.get(index); var b=fitted.get(index);
                assertEquals(a.vertices().size(),b.vertices().size());
                for(int vertex=0;vertex<a.vertices().size();vertex++) {
                    assertEquals(a.vertices().get(vertex).y(),b.vertices().get(vertex).y());
                    assertEquals(a.vertices().get(vertex).u(),b.vertices().get(vertex).u());
                    assertEquals(a.vertices().get(vertex).v(),b.vertices().get(vertex).v());
                }
            }
            assertEquals(-.125,CopingGeometry.fitCoordinate(-.125,fit.width)-(.5-fit.width/2),.000001);
            assertEquals(.125,CopingGeometry.fitCoordinate(1.125,fit.width)-(.5+fit.width/2),.000001);
            assertEquals(CopingBlock.THICKNESS,fitted.stream().flatMap(s -> s.vertices().stream()).mapToDouble(CopingGeometry.Vertex::y).max().orElseThrow(),.00001);
        }
    }

    @Test void actualPairedWallPosesShareTheirVisibleTopAndBaseEnds() throws Exception {
        var flat=geometry(CopingBlock.Surface.FLAT);
        var sources=List.of(CopingHorizontalFit.DIAGONAL,CopingHorizontalFit.BROAD_RIGHT,CopingHorizontalFit.BROAD_WIDE_RIGHT,
                CopingHorizontalFit.BROAD_RIGHT,CopingHorizontalFit.NARROW_RIGHT,CopingHorizontalFit.NARROW_THIN_RIGHT,CopingHorizontalFit.DIAGONAL);
        var targets=List.of(CopingHorizontalFit.DIAGONAL,CopingHorizontalFit.BROAD_RIGHT,CopingHorizontalFit.BROAD_WIDE_RIGHT,
                CopingHorizontalFit.NARROW_RIGHT,CopingHorizontalFit.BROAD_RIGHT,CopingHorizontalFit.NARROW_THIN_RIGHT,CopingHorizontalFit.BROAD_RIGHT);
        for(int index=0;index<sources.size();index++) {
            var a=sources.get(index); var b=targets.get(index);
            var ap=a.pose(Direction.EAST); var bp=b.pose(Direction.WEST);
            int dx=index==1 || index==6 ? 0 : -1,dz=index==1 || index==6 ? -1 : 0;
            var source=new CopingHorizontalMitre.Rail(ap.centreX(),ap.centreZ(),ap.yawRadians(),a.runLength,a.width);
            var target=new CopingHorizontalMitre.Rail(dx+bp.centreX(),dz+bp.centreZ(),bp.yawRadians(),b.runLength,b.width);
            double along=(target.x()-source.x())*Math.cos(source.yaw())+(target.z()-source.z())*Math.sin(source.yaw());
            int ae=along<0 ? -1 : 1,be=ae;
            var mitre=CopingHorizontalMitre.solve(source,ae,target,be); assertNotNull(mitre);
            var first=new CopingConnections.HorizontalEnd(0,ae,a.runLength/2,a.width/2,mitre.sourceCuts());
            var second=new CopingConnections.HorizontalEnd(0,be,b.runLength/2,b.width/2,mitre.targetCuts());
            var one=flat.fitted(a).joined(CopingBlock.Surface.FLAT,new CopingConnections.Joins(ae<0 ? 1 : 8,0,1,List.of(first)));
            var two=flat.fitted(b).joined(CopingBlock.Surface.FLAT,new CopingConnections.Joins(be<0 ? 1 : 8,0,1,List.of(second)));
            var sourceSegments=horizontalBoundary(one,first,source).stream().filter(CopingGeometryTest::visibleHorizontalEnvelope).toList();
            var targetSegments=horizontalBoundary(two,second,target).stream().filter(CopingGeometryTest::visibleHorizontalEnvelope).toList();
            assertFalse(sourceSegments.isEmpty()); assertFalse(targetSegments.isEmpty());
            assertHorizontalBoundary(sourceSegments,targetSegments,a+" -> "+b);
            assertHorizontalBoundary(targetSegments,sourceSegments,b+" -> "+a);
        }
    }
    private static boolean visibleHorizontalEnvelope(List<double[]> segment) {
        return (Math.abs(segment.get(0)[1])<.00001 && Math.abs(segment.get(1)[1])<.00001)
                || (Math.abs(segment.get(0)[1]-CopingBlock.THICKNESS)<.00001 && Math.abs(segment.get(1)[1]-CopingBlock.THICKNESS)<.00001);
    }
    private static List<List<double[]>> horizontalBoundary(List<CopingGeometry.Surface> surfaces,
            CopingConnections.HorizontalEnd end,CopingHorizontalMitre.Rail rail) {
        java.util.ArrayList<List<double[]>> result=new java.util.ArrayList<>();
        for(var surface:surfaces) for(int i=0;i<surface.vertices().size();i++) {
            var a=surface.vertices().get(i); var b=surface.vertices().get((i+1)%surface.vertices().size());
            if(CopingHorizontalPlacedMeshLaunchChecks.sharedCut(surface,a,b,end))
                result.add(List.of(horizontalPoint(a,rail),horizontalPoint(b,rail)));
        }
        return result;
    }
    @Test void aFreePerimeterEdgeCannotBorrowDifferentExtrapolatedMitreBranches() {
        var cuts=List.of(new CopingHorizontalMitre.Cut(-1,-.44721359549995787),
                new CopingHorizontalMitre.Cut(-2D/3,-.22360679774997888),new CopingHorizontalMitre.Cut(-1,0));
        var end=new CopingConnections.HorizontalEnd(0,-1,3/(2*Math.sqrt(5)),3/(2*Math.sqrt(5)),cuts);
        var free=new CopingGeometry.Vertex(1.2020704F,.1875F,-.2020704F,0,0);
        var joined=new CopingGeometry.Vertex(.7548568F,.1875F,-.2020704F,0,0);
        var perimeter=new CopingGeometry.Surface(List.of(free,joined),0,Direction.NORTH,false,List.of());
        assertFalse(CopingHorizontalPlacedMeshLaunchChecks.sharedCut(perimeter,free,joined,end));
        var below=new CopingGeometry.Vertex(joined.x(),.0375F,joined.z(),0,0);
        assertTrue(CopingHorizontalPlacedMeshLaunchChecks.sharedCut(perimeter,joined,below,end));
    }
    @Test void nativeSideBevelMetricsMatchAllFourApprovedRawEdges() throws Exception {
        var model=JsonParser.parseString(Files.readString(ROOT.resolve("coping_georgian_flat.json"))).getAsJsonObject();
        int checked=0;
        for(var element:model.getAsJsonArray("elements")) {
            var authored=element.getAsJsonObject(); String name=authored.get("name").getAsString();
            if(!name.startsWith("edge_")) continue;
            var single=model.deepCopy(); var elements=new com.google.gson.JsonArray(); elements.add(authored.deepCopy()); single.add("elements",elements);
            boolean northSouth=name.equals("edge_north") || name.equals("edge_south");
            boolean lowEdge=name.equals("edge_north") || name.equals("edge_west");
            double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY,minRun=Double.POSITIVE_INFINITY,maxRun=Double.NEGATIVE_INFINITY;
            for(var surface:ErydonRawModelLoadingPlugin.surfaceSnapshots("coping",new Identifier("erydon","coping"),single))
                for(var vertex:surface.vertexPositions()) {
                    double lateral=northSouth ? vertex[2] : vertex[0],run=northSouth ? vertex[0] : vertex[2];
                    double offset=lowEdge ? lateral : 1-lateral;
                    low=Math.min(low,offset); high=Math.max(high,offset); minRun=Math.min(minRun,run); maxRun=Math.max(maxRun,run);
                }
            assertEquals(CopingConnections.SIDE_BEVEL_OUTER_OVERHANG,-low,.000001,name+" outward extent changed; update finite-span metrics");
            assertEquals(CopingConnections.SIDE_BEVEL_INNER_REACH,high,.000001,name+" inward extent changed; update finite-span metrics");
            assertEquals(CopingConnections.SIDE_BEVEL_RUN_OVERHANG,-minRun,.000001,name+" low run span changed");
            assertEquals(CopingConnections.SIDE_BEVEL_RUN_OVERHANG,maxRun-1,.000001,name+" high run span changed");
            checked++;
        }
        assertEquals(4,checked);
    }
    @Test void aFlatCornerWithTwoDiagonalPortsKeepsBothActualSeamsOnTheirSharedCuts() throws Exception {
        var flat=geometry(CopingBlock.Surface.FLAT);
        var source=new CopingHorizontalMitre.Rail(.5,.5,0,1,1);
        var broad=new CopingHorizontalMitre.Rail(1.1,.3,-Math.atan(2),3/Math.sqrt(5),3/Math.sqrt(5));
        var diagonal=new CopingHorizontalMitre.Rail(.25,1.25,-Math.PI/4,Math.sqrt(2),Math.sqrt(.5));
        var eastJoin=CopingHorizontalMitre.solve(source,1,broad,-1);
        var southJoin=CopingHorizontalMitre.solve(new CopingHorizontalMitre.Rail(.5,.5,Math.PI/2,1,1),1,diagonal,1);
        assertNotNull(eastJoin); assertNotNull(southJoin);
        var east=new CopingConnections.HorizontalEnd(0,1,.5,.5,eastJoin.sourceCuts());
        var south=new CopingConnections.HorizontalEnd(1,1,.5,.5,southJoin.sourceCuts());
        var joins=new CopingConnections.Joins(8|128,0,1,List.of(east,south));
        var actual=flat.joined(CopingBlock.Surface.FLAT,joins);
        assertFalse(actual.isEmpty()); int boundaries=0;
        for(var surface:actual) for(var v:surface.vertices()) {
            if(!surface.normals().isEmpty()) {
                assertTrue(east.distance(v.x(),v.z(),0)<.00003,"Second port moved the first actual cut");
                assertTrue(south.distance(v.x(),v.z(),0)<.00003,"First port moved the second actual cut");
            }
            assertTrue(v.x()>-2 && v.x()<3 && v.z()>-2 && v.z()<3,"Actual two-port outer corner escaped bounded picking");
            if(Math.abs(east.distance(v.x(),v.z(),0))<.00003 || Math.abs(south.distance(v.x(),v.z(),0))<.00003) boundaries++;
        }
        assertTrue(boundaries>0,"Fixture did not retain either sharp shared boundary");
    }
    @Test void aHorizontalJoinRetainsTheApprovedPitchMitreAndSmoothNormalsOnAnotherEnd() throws Exception {
        var flat=geometry(CopingBlock.Surface.FLAT);
        var source=new CopingHorizontalMitre.Rail(.5,.5,Math.PI/2,1,1);
        var target=new CopingHorizontalMitre.Rail(.25,1.25,-Math.PI/4,Math.sqrt(2),Math.sqrt(.5));
        var join=CopingHorizontalMitre.solve(source,1,target,1); assertNotNull(join);
        var end=new CopingConnections.HorizontalEnd(1,1,.5,.5,join.sourceCuts());
        var surfaces=flat.joined(CopingBlock.Surface.FLAT,new CopingConnections.Joins(3|128,0,1,List.of(end)));
        double expected=CopingBlock.THICKNESS*Math.tan(Math.PI/8); boolean found=false;
        for(var surface:surfaces) if(!surface.normals().isEmpty()) for(int vertex=0;vertex<surface.vertices().size();vertex++) {
            var v=surface.vertices().get(vertex);
            if(Math.abs(v.y()-CopingBlock.THICKNESS)<.00001 && Math.abs(v.x()-expected)<.00002) {
                assertEquals(Math.sin(Math.PI/8),surface.normals().get(vertex).x(),.00002);
                found=true;
            }
        }
        assertTrue(found,"Horizontal composition lost the approved flat-to-pitch mitre");
    }
    private static double[] horizontalPoint(CopingGeometry.Vertex v,CopingHorizontalMitre.Rail rail) {
        return new double[]{rail.x()+(v.x()-.5)*Math.cos(rail.yaw())-(v.z()-.5)*Math.sin(rail.yaw()),v.y(),
                rail.z()+(v.x()-.5)*Math.sin(rail.yaw())+(v.z()-.5)*Math.cos(rail.yaw())};
    }
    private static void assertHorizontalBoundary(List<List<double[]>> source,List<List<double[]>> target,String description) {
        for(var segment:source) for(double fraction:new double[]{0,.5,1}) {
            double[] p=new double[3]; for(int i=0;i<3;i++) p[i]=segment.get(0)[i]+fraction*(segment.get(1)[i]-segment.get(0)[i]);
            assertTrue(target.stream().anyMatch(candidate -> {
                double[] a=candidate.get(0),b=candidate.get(1); double length=0,dot=0;
                for(int i=0;i<3;i++) { length+=(b[i]-a[i])*(b[i]-a[i]); dot+=(p[i]-a[i])*(b[i]-a[i]); }
                double t=length==0 ? 0 : Math.max(0,Math.min(1,dot/length)),distance=0;
                for(int i=0;i<3;i++) distance+=Math.pow(p[i]-a[i]-t*(b[i]-a[i]),2);
                return distance<.000000004;
            }),description+" has unmatched visible bevel/base/top seam at "+java.util.Arrays.toString(p));
        }
    }
    @Test void everyEditableProfileParsesWithSafeAtlasUvsAndBothSteepPositions() throws Exception {
        for (var profile:authoredProfiles()) {
            var geometry=geometry(profile);
            var surfaces=geometry.joined(profile,0);
            assertTrue(surfaces.size()>=34 && surfaces.size()<=42,profile.asString());
            for (var surface:surfaces) for (var v:surface.vertices()) {
                assertTrue(Float.isFinite(v.x()) && Float.isFinite(v.y()) && Float.isFinite(v.z()));
                assertTrue(v.u()>=0 && v.u()<=16 && v.v()>=0 && v.v()<=16);
            }
            // The underside of the named base follows the support's exact plane.
            var model=JsonParser.parseString(Files.readString(ROOT.resolve("coping_georgian_"+profile.asString()+".json"))).getAsJsonObject();
            var elements=model.getAsJsonArray("elements");
            for (var element:elements) if (element.getAsJsonObject().get("name").getAsString().equals("base")) {
                var faces=element.getAsJsonObject().getAsJsonObject("faces");
                var bottom=faces.get("down");
                for (var e:elements) e.getAsJsonObject().add("faces",new com.google.gson.JsonObject());
                element.getAsJsonObject().getAsJsonObject("faces").add("down",bottom);
                var snapshot=ErydonRawModelLoadingPlugin.surfaceSnapshots("coping",new Identifier("erydon","coping"),model).get(0);
                for (float[] vertex:snapshot.vertexPositions()) {
                    assertEquals(profile.height(vertex[0]),vertex[1],.00001,profile.asString());
                }
                break;
            }
        }
    }
    @Test void continuousRunsTrimOverlapsAndHideEndBevelsWithoutCornerModels() throws Exception {
        for (var profile:authoredProfiles()) {
            var geometry=geometry(profile);
            for (int mask=0;mask<16;mask++) for (var surface:geometry.joined(profile,mask)) {
                assertEquals(0,surface.edge());
                for (var v:surface.vertices()) {
                    if ((mask&CopingConnections.WEST)!=0) assertTrue(CopingGeometry.run(v,profile)>=-.00001);
                    if ((mask&CopingConnections.EAST)!=0) assertTrue(CopingGeometry.run(v,profile)<=1.00001);
                    if ((mask&CopingConnections.NORTH)!=0) assertTrue(v.z()>=-.00001);
                    if ((mask&CopingConnections.SOUTH)!=0) assertTrue(v.z()<=1.00001);
                    assertTrue(v.u()>=0 && v.u()<=16 && v.v()>=0 && v.v()<=16);
                }
            }
            assertTrue(geometry.joined(profile,15).size()<geometry.joined(profile,0).size());
        }
    }
    @Test void joinsFollowWorldHeightsAcrossAllFourFacings() {
        for (Direction facing:Direction.Type.HORIZONTAL) {
            int x=facing.getOffsetX(),z=facing.getOffsetZ();
            assertEquals(CopingConnections.EAST,join(CopingBlock.Surface.FLAT,CopingBlock.Surface.FLAT,facing,x,0,z));
            assertEquals(CopingConnections.EAST,join(CopingBlock.Surface.SLOPE,CopingBlock.Surface.SLOPE,facing,x,-1,z));
            assertEquals(0,join(CopingBlock.Surface.SLOPE,CopingBlock.Surface.SLOPE,facing,x,0,z));
            assertEquals(CopingConnections.EAST,join(CopingBlock.Surface.SHALLOW_UPPER,CopingBlock.Surface.SHALLOW_LOWER,facing,x,0,z));
            assertEquals(CopingConnections.EAST,join(CopingBlock.Surface.SHALLOW_LOWER,CopingBlock.Surface.SHALLOW_UPPER,facing,x,-1,z));
            assertEquals(CopingConnections.EAST,join(CopingBlock.Surface.STEEP_LOWER,CopingBlock.Surface.STEEP_UPPER,facing,0,-1,0));
            assertEquals(CopingConnections.EAST,join(CopingBlock.Surface.STEEP_UPPER,CopingBlock.Surface.STEEP_LOWER,facing,x,-1,z));
        }
    }
    @Test void bevelEndCapsStopBelowTheBandWithoutChangingItsVisibleProfile() throws Exception {
        for (var profile:authoredProfiles()) {
            var surfaces=geometry(profile).joined(profile,0);
            long exposedCaps=surfaces.stream().filter(CopingGeometry.Surface::bandEnd).count();
            assertTrue(exposedCaps<=8,profile.asString());
            for (var surface:surfaces) if (surface.bandEnd()) for (var vertex:surface.vertices()) {
                assertTrue(CopingGeometry.normalHeight(vertex,profile)<=.6/16+.00001,profile.asString());
            }
        }
    }
    @Test void clippingInterpolatesUvsInsteadOfRestartingTheAuthoredFace() {
        var polygon=List.of(new CopingGeometry.Vertex(-.125f,0,0,0,0),new CopingGeometry.Vertex(1.125f,0,0,16,0),
                new CopingGeometry.Vertex(1.125f,0,1,16,16),new CopingGeometry.Vertex(-.125f,0,1,0,16));
        var clipped=CopingGeometry.clip(polygon,0,0,true);
        for (var v:clipped) if (v.x()==0) assertEquals(1.6f,v.u(),.00001);
    }

    @Test void everyInclineFormsACommonMitreAtBothEndsIncludingRidges() throws Exception {
        double[] gradients={0,0,.5,1,2,-.5,-1,-2};
        int checked=0;
        for(var profile:authoredProfiles()) {
            var geometry=geometry(profile);
            for(int code=1;code<gradients.length;code++) for(boolean west:new boolean[]{true,false}) {
                double other=gradients[code],boundary=west ? 0 : 1;
                var mask=CopingConnections.Joins.samePlane(profile,west ? CopingConnections.WEST : CopingConnections.EAST);
                var joins=new CopingConnections.Joins(west ? code : code<<3);
                int points=0;
                for(var surface:geometry.joined(profile,mask)) for(var original:surface.vertices()) {
                    if(Math.abs(CopingGeometry.run(original,profile)-boundary)>.00001) continue;
                    var vertex=CopingGeometry.mitre(original,profile,joins);
                    double endpoint=west ? profile.start : profile.end,height=west ? profile.high : profile.low;
                    double otherDistance=(vertex.y()-height+other*(vertex.x()-endpoint))/Math.sqrt(1+other*other);
                    assertEquals(CopingGeometry.normalHeight(vertex,profile),otherDistance,.00002,
                            profile+" -> "+other+" "+west);
                    assertEquals(original.u(),vertex.u()); assertEquals(original.v(),vertex.v());
                    points++;
                }
                assertTrue(points>0,"Missing joining profile boundary");
                for(var surface:geometry.joined(profile,joins)) for(var vertex:surface.vertices()) {
                    assertTrue(Float.isFinite(vertex.x()) && Float.isFinite(vertex.y()));
                    assertTrue(vertex.u()>=0 && vertex.u()<=16 && vertex.v()>=0 && vertex.v()<=16);
                }
                checked++;
            }
        }
        assertEquals(84,checked);
    }

    @Test void differentInclinesJoinOnlyWhenTheirSupportEndpointsMeet() {
        for(Direction facing:Direction.Type.HORIZONTAL) {
            int x=facing.getOffsetX(),z=facing.getOffsetZ();
            assertEquals(CopingConnections.EAST,join(CopingBlock.Surface.FLAT,CopingBlock.Surface.SHALLOW_UPPER,facing,x,0,z));
            assertEquals(CopingConnections.EAST,join(CopingBlock.Surface.SLOPE,CopingBlock.Surface.SHALLOW_UPPER,facing,x,-1,z));
            assertEquals(CopingConnections.WEST,join(CopingBlock.Surface.STEEP_LOWER,CopingBlock.Surface.SLOPE,facing,-x,1,-z));
            assertEquals(0,join(CopingBlock.Surface.FLAT,CopingBlock.Surface.SHALLOW_LOWER,facing,x,0,z));
        }
    }

    @Test void neighbouringModelsHaveTheSameCrossSectionAtTheSharedMitre() throws Exception {
        for(var a:authoredProfiles()) for(var b:authoredProfiles()) {
            var left=boundary(geometry(a),a,CopingConnections.EAST,b.gradient());
            var right=boundary(geometry(b),b,CopingConnections.WEST,a.gradient());
            assertSameCrossSection(left,a,right,b);
            assertSameCrossSection(right,b,left,a);
        }
    }
    private static void assertSameCrossSection(List<List<CopingGeometry.Vertex>> source,CopingBlock.Surface profile,
                                               List<List<CopingGeometry.Vertex>> target,CopingBlock.Surface other) {
        assertFalse(source.isEmpty()); assertFalse(target.isEmpty());
        for(var segment:source) for(var vertex:List.of(segment.get(0),segment.get(1),segment.get(0).blend(segment.get(1),.5F))) {
            double z=vertex.z(),height=CopingGeometry.normalHeight(vertex,profile);
            assertTrue(target.stream().anyMatch(candidate -> {
                var a=candidate.get(0); var b=candidate.get(1);
                double az=a.z(),ah=CopingGeometry.normalHeight(a,other);
                double dz=b.z()-az,dh=CopingGeometry.normalHeight(b,other)-ah,length=dz*dz+dh*dh;
                double t=length==0 ? 0 : Math.max(0,Math.min(1,((z-az)*dz+(height-ah)*dh)/length));
                return Math.hypot(z-az-t*dz,height-ah-t*dh)<.00002;
            }),"Different joining cross sections: "+profile+" -> "+other+" vertex "+vertex);
        }
    }
    private static List<List<CopingGeometry.Vertex>> boundary(CopingGeometry geometry,CopingBlock.Surface profile,int edge,double other) {
        int code=other==0 ? 1 : other==.5 ? 2 : other==1 ? 3 : 4;
        var joins=new CopingConnections.Joins(edge==CopingConnections.WEST ? code : code<<3);
        double end=edge==CopingConnections.WEST ? 0 : 1;
        // Clipping may add collinear vertices; compare the continuous profile,
        // including edge interiors, rather than requiring identical tessellation.
        return geometry.joined(profile,CopingConnections.Joins.samePlane(profile,edge)).stream()
                .map(surface -> surface.vertices().stream().filter(v -> Math.abs(CopingGeometry.run(v,profile)-end)<.00001)
                        .map(v -> CopingGeometry.mitre(v,profile,joins)).toList())
                .filter(segment -> segment.size()>=2)
                .map(CopingGeometryTest::longestSegment).toList();
    }
    private static List<CopingGeometry.Vertex> longestSegment(List<CopingGeometry.Vertex> points) {
        List<CopingGeometry.Vertex> result=List.of(points.get(0),points.get(1));
        double longest=-1;
        for(var a:points) for(var b:points) {
            double distance=Math.pow(a.x()-b.x(),2)+Math.pow(a.y()-b.y(),2)+Math.pow(a.z()-b.z(),2);
            if(distance>longest) { longest=distance; result=List.of(a,b); }
        }
        return result;
    }
    private static int join(CopingBlock.Surface a,CopingBlock.Surface b,Direction facing,int x,int y,int z) {
        return CopingConnections.joins(a,facing,b,facing,x,y,z);
    }
    private static CopingGeometry geometry(CopingBlock.Surface profile) throws Exception {
        String name="coping_georgian_"+profile.asString()+".json";
        return CopingGeometry.parse(JsonParser.parseString(Files.readString(ROOT.resolve(name))).getAsJsonObject(),new Identifier("erydon",name),profile);
    }
}
