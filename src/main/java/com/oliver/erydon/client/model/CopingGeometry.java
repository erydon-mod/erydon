package com.oliver.erydon.client.model;

import com.google.gson.JsonObject;
import com.oliver.erydon.block.CopingBlock;
import com.oliver.erydon.block.CopingConnections;
import com.oliver.erydon.block.CopingHorizontalFit;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/** Parses editable 1.21.11 geometry once per reload; joins trim the overlapping ends. */
public final class CopingGeometry {
    record Vertex(float x, float y, float z, float u, float v) {
        Vertex blend(Vertex b, float t) {
            return new Vertex(x+(b.x-x)*t, y+(b.y-y)*t, z+(b.z-z)*t, u+(b.u-u)*t, v+(b.v-v)*t);
        }
    }
    record Normal(float x, float y, float z) { }
    record Surface(List<Vertex> vertices, int edge, Direction face, boolean bandEnd, List<Normal> normals) { }
    private final List<Surface> surfaces;
    private final List<Vertex> bandVertices;
    private CopingGeometry(List<Surface> surfaces, List<Vertex> bandVertices) {
        this.surfaces = List.copyOf(surfaces); this.bandVertices = List.copyOf(bandVertices);
    }

    /** Resize the flat core; translate end bevels and preserve authored overhang distances. */
    CopingGeometry fitted(CopingHorizontalFit fit) {
        if(fit==CopingHorizontalFit.AXIS) return this;
        List<Surface> result=new ArrayList<>();
        for(Surface surface:surfaces) result.add(new Surface(surface.vertices.stream()
                .map(v -> fitted(v,fit,surface.edge)).toList(),surface.edge,surface.face,surface.bandEnd,surface.normals));
        return new CopingGeometry(result,bandVertices.stream().map(v -> fitted(v,fit,0)).toList());
    }

    private static Vertex fitted(Vertex v,CopingHorizontalFit fit,int edge) {
        float x=(float)((edge&CopingConnections.WEST)!=0 ? v.x+(1-fit.runLength)/2
                : (edge&CopingConnections.EAST)!=0 ? v.x+(fit.runLength-1)/2 : fitCoordinate(v.x,fit.runLength));
        float z=(float)((edge&CopingConnections.NORTH)!=0 ? v.z+(1-fit.width)/2
                : (edge&CopingConnections.SOUTH)!=0 ? v.z+(fit.width-1)/2 : fitCoordinate(v.z,fit.width));
        return new Vertex(x,v.y,z,v.u,v.v);
    }

    static double fitCoordinate(double coordinate,double coreSize) {
        if(coordinate<0) return .5-coreSize/2+coordinate;
        if(coordinate>1) return .5+coreSize/2+coordinate-1;
        return .5+(coordinate-.5)*coreSize;
    }

    static CopingGeometry parse(JsonObject model, Identifier id, CopingBlock.Surface profile) {
        var elements = model.getAsJsonArray("elements");
        if (elements == null || elements.isEmpty()) throw new IllegalArgumentException("Empty coping model: " + id);
        List<Surface> result = new ArrayList<>();
        List<Vertex> bandVertices = new ArrayList<>();
        for (int elementIndex = 0; elementIndex < elements.size(); elementIndex++) {
            JsonObject element = elements.get(elementIndex).getAsJsonObject();
            String name = element.has("name") ? element.get("name").getAsString() : "";
            int edge = switch (name) {
                case "edge_west" -> CopingConnections.WEST;
                case "edge_east" -> CopingConnections.EAST;
                case "edge_north" -> CopingConnections.NORTH;
                case "edge_south" -> CopingConnections.SOUTH;
                default -> 0;
            };
            for (var face : element.getAsJsonObject("faces").entrySet()) {
                // Preserve original group indices and use the established raw parser for every rotation.
                JsonObject singleFace = model.deepCopy();
                var singleElements = singleFace.getAsJsonArray("elements");
                for (var other : singleElements) other.getAsJsonObject().add("faces", new JsonObject());
                singleElements.get(elementIndex).getAsJsonObject().getAsJsonObject("faces").add(face.getKey(), face.getValue());
                var snapshot = ErydonRawModelLoadingPlugin.surfaceSnapshots("coping_georgian", id, singleFace).get(0);
                JsonObject faceData = face.getValue().getAsJsonObject();
                int turns = faceData.has("rotation") ? Math.floorMod(faceData.get("rotation").getAsInt()/90, 4) : 0;
                List<Vertex> vertices = new ArrayList<>(4);
                for (int v = 0; v < 4; v++) {
                    float[] p = snapshot.vertexPositions()[v];
                    float[] uv = snapshot.sourceUvs()[(v+turns)%4];
                    vertices.add(new Vertex(p[0], p[1], p[2], uv[0], uv[1]));
                }
                boolean bandEnd = ((edge == CopingConnections.WEST || edge == CopingConnections.EAST)
                        && (face.getKey().equals("north") || face.getKey().equals("south")))
                        || ((edge == CopingConnections.NORTH || edge == CopingConnections.SOUTH)
                        && (face.getKey().equals("east") || face.getKey().equals("west")));
                if (name.equals("upper_band")) bandVertices.addAll(vertices);
                // Keep the authored texture plane as the profile pitches or its
                // mitre changes slope. Dominant lighting normals can flip at 45 degrees.
                Direction direction=Direction.byName(face.getKey());
                boolean profilePlane=direction.getAxis()==Direction.Axis.Y && unrotated(element);
                List<Normal> normals=profilePlane ? vertices.stream()
                        .map(v -> profileNormal(profile,CopingConnections.Joins.samePlane(profile,0),direction,run(v,profile))).toList() : List.of();
                result.add(new Surface(List.copyOf(vertices), edge, direction,bandEnd,normals));
            }
        }
        return new CopingGeometry(result,bandVertices);
    }

    List<Surface> joined(CopingBlock.Surface profile, int mask) {
        return joined(profile,CopingConnections.Joins.samePlane(profile,mask));
    }

    List<Surface> joined(CopingBlock.Surface profile,CopingConnections.Joins joins) {
        if(!joins.horizontal().isEmpty()) return joinedHorizontal(profile,joins);
        int mask=joins.mask();
        List<Surface> result = new ArrayList<>();
        float bandBottom = (float)bandVertices.stream().mapToDouble(v -> normalHeight(v,profile)).min().orElse(0);
        for (Surface source : surfaces) {
            if ((source.edge & mask) != 0) continue;
            List<Vertex> vertices = source.vertices;
            // Bevel end caps share a plane with the band's sides. Keep only their exposed lower part.
            if (source.bandEnd && !bandVertices.isEmpty()) vertices = clipBandEnd(vertices,profile,bandBottom);
            if ((mask & CopingConnections.WEST) != 0) vertices = clipRun(vertices,profile,(float)joins.westCut(),true);
            if ((mask & CopingConnections.EAST) != 0) vertices = clipRun(vertices,profile,(float)joins.eastCut(),false);
            if ((mask & CopingConnections.NORTH) != 0) vertices = clip(vertices, 2, 0, true);
            if ((mask & CopingConnections.SOUTH) != 0) vertices = clip(vertices, 2, 1, false);
            if (vertices.size()<3) continue;
            if (((mask&CopingConnections.WEST)!=0 && vertices.stream().allMatch(v -> Math.abs(run(v,profile)-joins.westCut())<.00001))
                    || ((mask&CopingConnections.EAST)!=0 && vertices.stream().allMatch(v -> Math.abs(run(v,profile)-joins.eastCut())<.00001))) continue;
            List<List<Vertex>> pieces=new ArrayList<>(); pieces.add(vertices);
            if ((mask&CopingConnections.WEST)!=0 && joins.westGradient()!=profile.gradient()) splitRun(pieces,profile,.2F);
            if ((mask&CopingConnections.EAST)!=0 && joins.eastGradient()!=profile.gradient()) splitRun(pieces,profile,.8F);
            for (List<Vertex> piece:pieces) if (piece.size()>=3) {
                List<Vertex> joined=piece.stream().map(v -> mitre(v,profile,joins)).toList();
                // Keep lighting continuous at the stub's tessellation boundary.
                // Named rotated bevels retain their own sharp geometric normals.
                List<Normal> normals=source.normals.isEmpty() ? List.of() : piece.stream()
                        .map(v -> profileNormal(profile,joins,source.face,run(v,profile))).toList();
                result.add(new Surface(joined,0,source.face,source.bandEnd,normals));
            }
        }
        return result;
    }

    private List<Surface> joinedHorizontal(CopingBlock.Surface profile,CopingConnections.Joins joins) {
        List<Surface> result=new ArrayList<>();
        int horizontalMask=joins.horizontal().stream().mapToInt(CopingConnections.HorizontalEnd::edge).reduce(0,(a,b)->a|b);
        int legacyMask=joins.mask()&~horizontalMask;
        int legacyKey=joins.key()&~(((horizontalMask&CopingConnections.WEST)!=0 ? 7 : 0)
                |((horizontalMask&CopingConnections.EAST)!=0 ? 56 : 0)
                |((horizontalMask&CopingConnections.NORTH)!=0 ? 64 : 0)|((horizontalMask&CopingConnections.SOUTH)!=0 ? 128 : 0));
        var legacyJoins=new CopingConnections.Joins(legacyKey);
        float bandBottom=(float)bandVertices.stream().mapToDouble(v -> v.y).min().orElse(0);
        for(Surface source:surfaces) {
            if((source.edge&joins.mask())!=0) continue;
            List<Vertex> polygon=source.bandEnd ? clipBandEnd(source.vertices,profile,bandBottom) : source.vertices;
            if((legacyMask&CopingConnections.WEST)!=0) polygon=clip(polygon,0,0,true);
            if((legacyMask&CopingConnections.EAST)!=0) polygon=clip(polygon,0,1,false);
            if((legacyMask&CopingConnections.NORTH)!=0) polygon=clip(polygon,2,0,true);
            if((legacyMask&CopingConnections.SOUTH)!=0) polygon=clip(polygon,2,1,false);
            List<List<Vertex>> pitchedPieces=new ArrayList<>(); pitchedPieces.add(polygon);
            if((legacyMask&CopingConnections.WEST)!=0 && joins.westGradient()!=0) splitRun(pitchedPieces,profile,.2F);
            if((legacyMask&CopingConnections.EAST)!=0 && joins.eastGradient()!=0) splitRun(pitchedPieces,profile,.8F);
            for(List<Vertex> pitchedPiece:pitchedPieces) {
                polygon=pitchedPiece.stream().map(v -> mitre(v,profile,legacyJoins)).toList();
                // Extend the longitudinal body only where needed, then clip all
                // ports together. Clipping prevents a second port moving the first seam.
                polygon=polygon.stream().map(v -> {
                    var point=CopingConnections.expand(v.x,v.z,joins.horizontal());
                    return new Vertex((float)point.x(),v.y,(float)point.z(),v.u,v.v);
                }).toList();
                if(polygon.size()<3) continue;
                // Flat end faces are internal after a join; side bevels remain exposed.
                if(joins.horizontal().stream().anyMatch(end -> polygonOnEnd(source.vertices,end))) continue;
                List<List<Vertex>> pieces=new ArrayList<>(); pieces.add(polygon);
                for(var end:joins.horizontal()) {
                    if(branch(source.edge,end)==0) {
                        splitLateral(pieces,end,-end.halfWidth());
                        splitLateral(pieces,end,end.halfWidth());
                    }
                    splitRunCoordinate(pieces,end,0);
                }
                for(List<Vertex> piece:pieces) if(piece.size()>=3) {
                    List<Vertex> warped=piece;
                    for(var end:joins.horizontal()) {
                        int branch=branch(source.edge,end);
                        warped=clipPlane(warped,v -> end.distance(v.x,v.z,branch),0,end.sign()<0);
                    }
                    if(warped.size()<3) continue;
                    List<Normal> normals=source.normals.isEmpty() ? List.of() : warped.stream()
                            .map(v -> profileNormal(profile,legacyJoins,source.face,legacyOriginalRun(v,legacyJoins))).toList();
                    // Keep authored bevel ownership for geometric seam diagnostics.
                    // Emission uses only vertices, face and normals.
                    result.add(new Surface(warped,source.edge,source.face,source.bandEnd,normals));
                }
            }
        }
        return result;
    }
    private static double legacyOriginalRun(Vertex v,CopingConnections.Joins legacy) {
        double run=v.x;
        if((legacy.mask()&CopingConnections.WEST)!=0 && run<.2) {
            double k=v.y*Math.tan(Math.atan(legacy.westGradient())/2);
            return (run-k)/(1-k/.2);
        }
        if((legacy.mask()&CopingConnections.EAST)!=0 && run>.8) {
            double k=v.y*Math.tan(Math.atan(legacy.eastGradient())/2);
            return (run+4*k)/(1+k/.2);
        }
        return run;
    }

    private static boolean polygonOnEnd(List<Vertex> polygon,CopingConnections.HorizontalEnd end) {
        double coordinate=end.run(polygon.get(0).x,polygon.get(0).z);
        return end.sign()*coordinate>=end.halfLength()-.00001
                && polygon.stream().allMatch(v -> Math.abs(end.run(v.x,v.z)-coordinate)<.00001);
    }
    /** A named side bevel keeps its fixed signed offset, including its small inward lip. */
    static int branch(int edge,CopingConnections.HorizontalEnd end) {
        return CopingConnections.bevelBranch(edge,end);
    }
    private static void splitLateral(List<List<Vertex>> pieces,CopingConnections.HorizontalEnd end,double at) {
        splitCoordinate(pieces,v -> end.lateral(v.x,v.z),at);
    }
    private static void splitRunCoordinate(List<List<Vertex>> pieces,CopingConnections.HorizontalEnd end,double at) {
        splitCoordinate(pieces,v -> end.run(v.x,v.z),at);
    }
    private static void splitCoordinate(List<List<Vertex>> pieces,java.util.function.ToDoubleFunction<Vertex> coordinate,double at) {
        List<List<Vertex>> split=new ArrayList<>();
        for(List<Vertex> piece:pieces) {
            boolean below=piece.stream().anyMatch(v -> coordinate.applyAsDouble(v)<at-.00001);
            boolean above=piece.stream().anyMatch(v -> coordinate.applyAsDouble(v)>at+.00001);
            if(below && above) { split.add(clipPlane(piece,coordinate,at,false)); split.add(clipPlane(piece,coordinate,at,true)); }
            else split.add(piece);
        }
        pieces.clear(); pieces.addAll(split);
    }

    private static boolean unrotated(JsonObject element) {
        if(!element.has("rotation")) return true;
        JsonObject rotation=element.getAsJsonObject("rotation");
        for(String key:List.of("angle","x","y","z"))
            if(rotation.has(key) && Math.abs(rotation.get(key).getAsDouble())>.000001) return false;
        return true;
    }

    static Normal profileNormal(CopingBlock.Surface profile,CopingConnections.Joins joins,Direction face,double run) {
        double angle=Math.atan(profile.gradient());
        if((joins.mask()&CopingConnections.WEST)!=0)
            angle+=smoothWeight(1-run/.2)*(Math.atan(joins.westGradient())-Math.atan(profile.gradient()))/2;
        if((joins.mask()&CopingConnections.EAST)!=0)
            angle+=smoothWeight(1-(1-run)/.2)*(Math.atan(joins.eastGradient())-Math.atan(profile.gradient()))/2;
        double sign=face==Direction.DOWN ? -1 : 1;
        return new Normal((float)(sign*Math.sin(angle)),(float)(sign*Math.cos(angle)),0);
    }

    private static double smoothWeight(double value) {
        double t=Math.max(0,Math.min(1,value));
        return t*t*(3-2*t);
    }

    Mesh mesh(Sprite sprite, CopingBlock.Surface profile, CopingConnections.Joins joins, boolean offset) {
        var builder = RendererAccess.INSTANCE.getRenderer().meshBuilder();
        QuadEmitter emitter = builder.getEmitter();
        for (Surface surface : joined(profile, joins)) {
            List<Vertex> v = surface.vertices;
            if (v.size() == 4) emit(emitter,sprite,surface,offset,0,1,2,3);
            else for (int i=1; i<v.size()-1; i++) emit(emitter,sprite,surface,offset,0,i,i+1,i+1);
        }
        return builder.build();
    }

    /** Authored longitudinal coordinate before the profile group's pitch rotation. */
    static double run(Vertex v,CopingBlock.Surface profile) {
        double gradient=profile.gradient(),normalX=gradient/Math.sqrt(1+gradient*gradient);
        return (v.x-profile.start-normalX*normalHeight(v,profile))/(profile.end-profile.start);
    }

    static Vertex mitre(Vertex v,CopingBlock.Surface profile,CopingConnections.Joins joins) {
        double u=run(v,profile),gradient=profile.gradient();
        double shift=0;
        if ((joins.mask()&CopingConnections.WEST)!=0)
            shift+=Math.max(0,1-u/.2)*Math.tan((Math.atan(joins.westGradient())-Math.atan(gradient))/2);
        if ((joins.mask()&CopingConnections.EAST)!=0)
            shift+=Math.max(0,1-(1-u)/.2)*Math.tan((Math.atan(joins.eastGradient())-Math.atan(gradient))/2);
        shift*=normalHeight(v,profile)/Math.sqrt(1+gradient*gradient);
        return new Vertex((float)(v.x+shift),(float)(v.y-gradient*shift),v.z,v.u,v.v);
    }

    private static List<Vertex> clipRun(List<Vertex> vertices,CopingBlock.Surface profile,float boundary,boolean greater) {
        return clipPlane(vertices,v -> run(v,profile),boundary,greater);
    }
    private static void splitRun(List<List<Vertex>> pieces,CopingBlock.Surface profile,float at) {
        List<List<Vertex>> split=new ArrayList<>();
        for(List<Vertex> piece:pieces) {
            boolean below=piece.stream().anyMatch(v -> run(v,profile)<at-.00001);
            boolean above=piece.stream().anyMatch(v -> run(v,profile)>at+.00001);
            if (below && above) { split.add(clipRun(piece,profile,at,false)); split.add(clipRun(piece,profile,at,true)); }
            else split.add(piece);
        }
        pieces.clear(); pieces.addAll(split);
    }
    private static List<Vertex> clipPlane(List<Vertex> input,java.util.function.ToDoubleFunction<Vertex> coordinate,double boundary,boolean greater) {
        if (input.isEmpty()) return input;
        List<Vertex> output=new ArrayList<>(); Vertex a=input.get(input.size()-1);
        double da=planeDistance(coordinate.applyAsDouble(a),boundary);
        for(Vertex b:input) {
            double db=planeDistance(coordinate.applyAsDouble(b),boundary);
            boolean insideA=greater ? da>=0 : da<=0,insideB=greater ? db>=0 : db<=0;
            if (insideA!=insideB) output.add(a.blend(b,(float)(da/(da-db))));
            if (insideB) output.add(b);
            a=b; da=db;
        }
        return output;
    }

    private static double planeDistance(double coordinate,double boundary) {
        double distance=coordinate-boundary;
        // Float rotations leave shared boundary vertices a few ULPs apart.
        // Treat those points as coplanar instead of cutting halfway up an end.
        return Math.abs(distance)<.000001 ? 0 : distance;
    }

    static void positions(QuadEmitter emitter,Surface surface,boolean offset,int... indices) {
        emitter.cullFace(null).nominalFace(surface.face).tag(CopingTexturePlane.tag(surface.face));
        for (int i=0; i<4; i++) {
            Vertex v=surface.vertices.get(indices[i]);
            emitter.pos(i,v.x-(offset ? 1 : 0),v.y,v.z);
            if(!surface.normals.isEmpty()) {
                Normal normal=surface.normals.get(indices[i]);
                emitter.normal(i,normal.x,normal.y,normal.z);
            }
        }
    }

    private static void emit(QuadEmitter emitter,Sprite sprite,Surface surface,boolean offset,int... indices) {
        positions(emitter,surface,offset,indices);
        for(int i=0;i<4;i++) {
            Vertex v=surface.vertices.get(indices[i]);
            emitter.sprite(i,0,sprite.getFrameU(v.u),sprite.getFrameV(v.v)).spriteColor(i,0,-1).lightmap(i,0);
        }
        emitter.emit();
    }

    static double normalHeight(Vertex vertex,CopingBlock.Surface profile) {
        return (vertex.y-profile.height(vertex.x))/Math.sqrt(1+profile.gradient()*profile.gradient());
    }
    private static List<Vertex> clipBandEnd(List<Vertex> input,CopingBlock.Surface profile,float boundary) {
        if (input.isEmpty()) return input;
        List<Vertex> output = new ArrayList<>();
        Vertex a=input.get(input.size()-1);
        double da=planeDistance(normalHeight(a,profile),boundary);
        for (Vertex b:input) {
            double db=planeDistance(normalHeight(b,profile),boundary);
            if ((da<=0)!=(db<=0)) output.add(a.blend(b,(float)(da/(da-db))));
            if (db<=0) output.add(b);
            a=b; da=db;
        }
        return output;
    }

    static List<Vertex> clip(List<Vertex> input, int axis, float boundary, boolean greater) {
        if (input.isEmpty()) return input;
        List<Vertex> output = new ArrayList<>();
        Vertex a=input.get(input.size()-1);
        float da=coordinate(a,axis)-boundary;
        for (Vertex b : input) {
            float db=coordinate(b,axis)-boundary;
            boolean insideA=greater ? da>=0 : da<=0, insideB=greater ? db>=0 : db<=0;
            if (insideA!=insideB) output.add(a.blend(b,da/(da-db)));
            if (insideB) output.add(b);
            a=b; da=db;
        }
        return output;
    }
    private static float coordinate(Vertex vertex,int axis) { return axis==0 ? vertex.x : vertex.z; }
}
