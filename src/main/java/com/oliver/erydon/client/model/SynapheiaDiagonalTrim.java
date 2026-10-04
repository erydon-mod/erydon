package com.oliver.erydon.client.model;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.oliver.erydon.client.pom.InlaySubstrateTransport;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import com.oliver.erydon.client.model.SynapheiaSlopeConnections.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Experimental Trim-only borders on the actual diagonal outline of a ramp side. */
final class SynapheiaDiagonalTrim {
    static final int EDGE_TILE = 14; // Three connected sides: only the top border remains.
    static final float RIBBON_WIDTH = 3.0F / 16.0F;
    private static final float EPS = 0.00001F;
    private static final float MIN_JOIN_EDGE = 1.0F / 256.0F;
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("erydon.debug.diagonalTrim", "true"));
    private static final ConcurrentHashMap<FragmentKey, List<List<Vertex>>> FRAGMENTS = new ConcurrentHashMap<>();
    static final int MAX_COMPOSITIONS=4096;
    // Guava's segmented cache has lock-free ordinary reads and a fixed retention limit;
    // varied surrounding slopes cannot accumulate plans for the lifetime of a world.
    private static final LoadingCache<CompositionKey,List<List<Vertex>>> COMPOSITIONS=CacheBuilder.newBuilder()
            .maximumSize(MAX_COMPOSITIONS).concurrencyLevel(4)
            .build(new CacheLoader<CompositionKey,List<List<Vertex>>>() {
                @Override public List<List<Vertex>> load(CompositionKey key) { return buildComposition(key); }
            });

    private SynapheiaDiagonalTrim() { }

    static boolean eligible(Identifier block, SynapheiaManifest.Rule rule, Direction face) {
        if (!supportsRule(block,rule,face)) return false;
        var family = ErydonSlopeModelClassifier.familyForId(block);
        return family == ErydonSlopeModelClassifier.Family.STANDARD
                || family == ErydonSlopeModelClassifier.Family.SHALLOW_LOWER
                || family == ErydonSlopeModelClassifier.Family.SHALLOW_UPPER;
    }

    static boolean supportsRule(Identifier block, SynapheiaManifest.Rule rule, Direction face) {
        if (!ENABLED || face.getAxis() == Direction.Axis.Y || rule.tiles().size() != 47
                || rule.method() != SynapheiaManifest.Method.OVERLAY_CTM
                || rule.overlayShape() != SynapheiaManifest.OverlayShape.SOURCE
                || !block.getPath().contains("_trim_")) return false;
        // Both the native resources and current Collection rules use these shared tiles.
        // Do not rotate any of the detailed motifs or a third-party replacement rule.
        String tile = rule.tiles().get(EDGE_TILE).getPath();
        return tile.endsWith("/overlay/trim/bronze/14") || tile.endsWith("/overlay/trim/silver/14");
    }

    static void clear() { FRAGMENTS.clear(); COMPOSITIONS.invalidateAll(); COMPOSITIONS.cleanUp(); }
    static int cachedFragmentCount() { return FRAGMENTS.size(); }
    static long cachedCompositionCount() { COMPOSITIONS.cleanUp(); return COMPOSITIONS.size(); }

    record PlacedSurface(SynapheiaSlopeConnections.Surface surface, int x, int y, int z) { }

    /** Compose the coplanar union locally, including the inset line crossing a raised cube corner. */
    static List<List<Vertex>> compose(Direction face, SynapheiaSlopeConnections.Surface target,
                                      List<PlacedSurface> neighbours) {
        return COMPOSITIONS.getUnchecked(new CompositionKey(face,target,List.copyOf(neighbours)));
    }

    /** Activate a cube only where an actual inset diagonal can reach its physical face. */
    static boolean affects(Direction face,SynapheiaSlopeConnections.Surface target,
                           PlacedSurface neighbour) {
        var points=borderPoints(face,neighbour.surface);
        float centreS=0,centreY=0;
        for(var p:points) { centreS+=s(face,p)+horizontalShift(face,neighbour); centreY+=p.y()+neighbour.y; }
        centreS/=points.length;centreY/=points.length;
        for(int index=0;index<points.length;index++) {
            Point a=shift(points[index],neighbour),b=shift(points[(index+1)%points.length],neighbour);
            float ds=s(face,b)-s(face,a),dy=b.y()-a.y();
            if(Math.abs(ds)<EPS || Math.abs(dy)<EPS) continue;
            float length=(float)Math.sqrt(ds*ds+dy*dy),ns=-dy/length,ny=ds/length;
            if(ns*(centreS-s(face,a))+ny*(centreY-a.y())<0) { ns=-ns;ny=-ny; }
            var band=clip(face,List.of(point(a,0,0),point(b,1,0),inset(face,b,ns,ny,1),inset(face,a,ns,ny,0)),outline(target));
            if(Math.abs(area(face,band))>EPS*EPS) return true;
        }
        return false;
    }

    private static List<List<Vertex>> buildComposition(CompositionKey key) {
        List<Segment> boundary = new ArrayList<>();
        var outlines=new java.util.IdentityHashMap<PlacedSurface,Point[]>();
        for(var placed:key.neighbours) outlines.put(placed,borderPoints(key.face,placed.surface));
        for (var placed : key.neighbours) {
            var points = outlines.get(placed);
            float centreS=0,centreY=0;
            for(var p:points) { centreS+=s(key.face,p)+horizontalShift(key.face,placed); centreY+=p.y()+placed.y; }
            centreS/=points.length; centreY/=points.length;
            for(int index=0;index<points.length;index++) {
                Point a=shift(points[index],placed),b=shift(points[(index+1)%points.length],placed);
                float ds=s(key.face,b)-s(key.face,a),dy=b.y()-a.y();
                float length=(float)Math.sqrt(ds*ds+dy*dy);
                if(length<EPS) continue;
                float ns=-dy/length,ny=ds/length;
                if(ns*(centreS-s(key.face,a))+ny*(centreY-a.y())<0) { ns=-ns; ny=-ny; }
                List<float[]> uncovered=new ArrayList<>(); uncovered.add(new float[]{0,1});
                var edge=new SynapheiaSlopeConnections.Edge(a,b);
                for(var other:key.neighbours) {
                    if(other==placed) continue;
                    var otherPoints=outlines.get(other);
                    for(int j=0;j<otherPoints.length;j++) {
                        Point c=shift(otherPoints[j],other),d=shift(otherPoints[(j+1)%otherPoints.length],other);
                        if(!edge.overlaps(new SynapheiaSlopeConnections.Edge(c,d))) continue;
                        float lengthSquared=length*length;
                        float lo=((s(key.face,c)-s(key.face,a))*ds+(c.y()-a.y())*dy)/lengthSquared;
                        float hi=((s(key.face,d)-s(key.face,a))*ds+(d.y()-a.y())*dy)/lengthSquared;
                        uncovered=subtract(uncovered,Math.min(lo,hi),Math.max(lo,hi));
                    }
                }
                for(var interval:uncovered) if(interval[1]-interval[0]>EPS)
                    boundary.add(new Segment(interpolate(a,b,interval[0]),interpolate(a,b,interval[1]),ns,ny));
            }
        }
        List<List<Vertex>> result=new ArrayList<>();
        List<Vertex> target=outline(key.target);
        for(var edge:boundary) {
            float ds=s(key.face,edge.b)-s(key.face,edge.a),dy=edge.b.y()-edge.a.y();
            float length=(float)Math.sqrt(ds*ds+dy*dy),extension=RIBBON_WIDTH*4;
            Point a=move(key.face,edge.a,-ds/length*extension,-dy/length*extension);
            Point b=move(key.face,edge.b,ds/length*extension,dy/length*extension);
            List<Vertex> ribbon=List.of(point(a,0,0),point(b,1,0),
                    inset(key.face,b,edge.ns,edge.ny,1),inset(key.face,a,edge.ns,edge.ny,0));
            for(int end=0;end<2;end++) {
                Point corner=end==0?edge.a:edge.b;
                float intoS=(end==0?ds:-ds)/length,intoY=(end==0?dy:-dy)/length;
                boolean mitred=false;
                for(var adjacent:boundary) {
                    if(adjacent==edge || !(adjacent.a.near(corner)||adjacent.b.near(corner))) continue;
                    float convex=adjacent.ns*intoS+adjacent.ny*intoY;
                    if(Math.abs(convex)<EPS) continue; // Straight continuation owns its own half.
                    float sign=convex>0?1:-1;
                    ribbon=clipDistance(ribbon,v -> sign*(distance(key.face,adjacent,v)-distance(key.face,edge,v)));
                    mitred=true;
                }
                if(!mitred) ribbon=clipDistance(ribbon,v ->
                        (s(key.face,v)-s(key.face,corner))*intoS+(v.y-corner.y())*intoY);
            }
            ribbon=deduplicate(clip(key.face,ribbon,target));
            if(ribbon.size()<3 || Math.abs(area(key.face,ribbon))<EPS*EPS) continue;
            float winding=(key.face.getAxis()==Direction.Axis.X?-1:1)
                    *(key.face.getDirection()==Direction.AxisDirection.POSITIVE?1:-1);
            if(area(key.face,ribbon)*winding<0) { var reverse=new ArrayList<>(ribbon); java.util.Collections.reverse(reverse); ribbon=reverse; }
            result.addAll(primitives(ribbon));
        }
        return List.copyOf(result);
    }

    /** The native ramp keeps a 0.001-high end for non-degenerate POM bounds. That
     * safety edge is not a separate architectural corner at the visible Trim inset. */
    private static Point[] borderPoints(Direction face,SynapheiaSlopeConnections.Surface surface) {
        Point[] original=surface.points;
        List<Point> cleaned=null;
        for(int index=0;index<original.length;index++) {
            Point a=original[index],b=original[(index+1)%original.length];
            float ds=s(face,b)-s(face,a),dy=b.y()-a.y();
            if(ds*ds+dy*dy>MIN_JOIN_EDGE*MIN_JOIN_EDGE || (!axisAligned(face,a,b))) continue;
            if(cleaned==null) cleaned=new ArrayList<>(List.of(original));
        }
        if(cleaned==null) return original;
        boolean changed;
        do {
            changed=false;
            for(int index=0;index<cleaned.size() && cleaned.size()>3;index++) {
                int nextIndex=(index+1)%cleaned.size();
                Point a=cleaned.get(index),b=cleaned.get(nextIndex);
                float ds=s(face,b)-s(face,a),dy=b.y()-a.y();
                if(ds*ds+dy*dy>MIN_JOIN_EDGE*MIN_JOIN_EDGE || !axisAligned(face,a,b)) continue;
                Point previous=cleaned.get((index+cleaned.size()-1)%cleaned.size());
                Point next=cleaned.get((index+2)%cleaned.size());
                boolean previousAxis=axisAligned(face,previous,a),nextAxis=axisAligned(face,b,next);
                if(previousAxis==nextAxis) continue;
                // Preserve the endpoint on the real horizontal/vertical boundary;
                // the diagonal mitres to that boundary instead of the subpixel step.
                cleaned.remove(previousAxis?nextIndex:index);
                changed=true;break;
            }
        } while(changed);
        return cleaned.toArray(Point[]::new);
    }

    private static boolean axisAligned(Direction face,Point a,Point b) {
        return Math.abs(s(face,a)-s(face,b))<EPS || Math.abs(a.y()-b.y())<EPS;
    }

    private static List<float[]> subtract(List<float[]> source,float lo,float hi) {
        List<float[]> result=new ArrayList<>();
        for(var interval:source) {
            if(hi<=interval[0]+EPS || lo>=interval[1]-EPS) { result.add(interval); continue; }
            if(lo>interval[0]+EPS) result.add(new float[]{interval[0],Math.min(lo,interval[1])});
            if(hi<interval[1]-EPS) result.add(new float[]{Math.max(hi,interval[0]),interval[1]});
        }
        return result;
    }

    private static List<Vertex> clipDistance(List<Vertex> source, java.util.function.ToDoubleFunction<Vertex> distance) {
        if(source.isEmpty()) return source;
        List<Vertex> result=new ArrayList<>();
        Vertex previous=source.get(source.size()-1); float pd=(float)distance.applyAsDouble(previous);
        for(var current:source) {
            float cd=(float)distance.applyAsDouble(current);
            if((pd>=-EPS)!=(cd>=-EPS)) result.add(previous.lerp(current,Math.max(0,Math.min(1,pd/(pd-cd)))));
            if(cd>=-EPS) result.add(current);
            previous=current;pd=cd;
        }
        return result;
    }

    private static float distance(Direction face,Segment edge,Vertex v) {
        return edge.ns*(s(face,v)-s(face,edge.a))+edge.ny*(v.y-edge.a.y());
    }
    private static float horizontalShift(Direction face,PlacedSurface p) { return face.getAxis()==Direction.Axis.X?p.z:p.x; }
    private static Point shift(Point p,PlacedSurface placed) { return p.shifted(placed.x,placed.y,placed.z); }
    private static Point interpolate(Point a,Point b,float t) { return new Point(a.x()+(b.x()-a.x())*t,a.y()+(b.y()-a.y())*t,a.z()+(b.z()-a.z())*t); }
    private static Point move(Direction face,Point p,float ds,float dy) { return new Point(p.x()+(face.getAxis()==Direction.Axis.Z?ds:0),p.y()+dy,p.z()+(face.getAxis()==Direction.Axis.X?ds:0)); }
    private record Segment(Point a,Point b,float ns,float ny) { }
    private record CompositionKey(Direction face,SynapheiaSlopeConnections.Surface target,List<PlacedSurface> neighbours) { }

    static void emit(QuadEmitter emitter, List<List<Vertex>> fragments,
                     List<SpiralStairCtmGeometry.Vertex> source, Direction face,
                     Direction cullFace, Direction nominalFace, int tag,
                     RenderMaterial material, Sprite sprite,int substrateRecord) {
        var lighting = source.get(0);
        // The source composite can already be 1/1024 above the stone. Keep this narrow
        // transparent ribbon just above it without replacing its world-phased interior.
        float offset = 2.0F / 1024.0F;
        for (var primitive : fragments) {
            emitter.material(material);
            emitter.cullFace(cullFace);
            emitter.nominalFace(nominalFace);
            emitter.colorIndex(-1);
            emitter.tag(tag);
            for (int index = 0; index < 4; index++) {
                var vertex = primitive.get(index);
                emitter.pos(index, vertex.x + face.getOffsetX() * offset,
                        vertex.y + face.getOffsetY() * offset, vertex.z + face.getOffsetZ() * offset);
                emitter.uv(index, vertex.u, vertex.v);
                emitter.color(index, -1);
                emitter.lightmap(index, lighting.lightmap());
                if (lighting.hasNormal()) emitter.normal(index, lighting.normalX(), lighting.normalY(), lighting.normalZ());
            }
            emitter.spriteBake(sprite, MutableQuadView.BAKE_NORMALIZED);
            InlaySubstrateTransport.emitRibbon(emitter,substrateRecord);
        }
    }

    /** Uses at most the already cached block immediately across the face's grid plane. */
    static int exposedEdges(Direction face, SynapheiaSlopeConnections.Surface surface,
                            SynapheiaSlopeConnections.Lookup lookup) {
        if (!surface.onBoundary(face) || surface.points.length > 30) return 0;
        int result = 0;
        for (int index = 0; index < surface.points.length; index++) {
            var a = surface.points[index];
            var b = surface.points[(index + 1) % surface.points.length];
            // A horizontal/vertical CTM edge is already drawn by the existing 47-tile path.
            if (Math.abs(s(face, a) - s(face, b)) < EPS || Math.abs(a.y() - b.y()) < EPS) continue;
            var edge = new SynapheiaSlopeConnections.Edge(a, b);
            if (!lookup.connects(face.getOffsetX(), face.getOffsetY(), face.getOffsetZ(), edge))
                result |= 1 << index;
        }
        return result;
    }

    /** The merged physical face gets one ribbon; its internal source strips add no borders. */
    static List<List<Vertex>> fragments(Direction face, SynapheiaSlopeConnections.Surface whole,
                                        int exposedEdges) {
        if (exposedEdges == 0) return List.of();
        var key = new FragmentKey(whole, face, exposedEdges);
        return FRAGMENTS.computeIfAbsent(key, SynapheiaDiagonalTrim::build);
    }

    private static List<List<Vertex>> build(FragmentKey key) {
        Direction face = key.face;
        List<Vertex> source = outline(key.surface);
        if (source.size() < 3) return List.of();
        float centreS = 0, centreT = 0;
        for (var point : key.surface.points) { centreS += s(face, point); centreT += point.y(); }
        centreS /= key.surface.points.length;
        centreT /= key.surface.points.length;
        List<List<Vertex>> result = new ArrayList<>();
        for (int index = 0; index < key.surface.points.length; index++) {
            if ((key.edges & (1 << index)) == 0) continue;
            var a = key.surface.points[index];
            var b = key.surface.points[(index + 1) % key.surface.points.length];
            float ds = s(face, b) - s(face, a), dt = b.y() - a.y();
            float length = (float) Math.sqrt(ds * ds + dt * dt);
            if (length < EPS) continue;
            float inwardS = -dt / length, inwardT = ds / length;
            if (inwardS * (centreS - s(face, a)) + inwardT * (centreT - a.y()) < 0) {
                inwardS = -inwardS; inwardT = -inwardT;
            }
            // Trim is uniform along the straight edge. Its normal maps follow this UV basis,
            // while the existing interior continues to use its original world projection.
            List<Vertex> ribbon = List.of(point(a, 0, 0), point(b, 1, 0),
                    inset(face, b, inwardS, inwardT, 1), inset(face, a, inwardS, inwardT, 0));
            ribbon = clip(face, ribbon, source);
            ribbon = deduplicate(ribbon);
            if (ribbon.size() < 3 || Math.abs(area(face, ribbon)) < EPS * EPS) continue;
            // A hull is sorted in the same 2-D order for both sides. Restore the actual
            // outward 3-D winding, including X-axis faces and upside-down ramps.
            float outwardWinding = (face.getAxis() == Direction.Axis.X ? -1 : 1)
                    * (face.getDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1);
            if (area(face, ribbon) * outwardWinding < 0) {
                List<Vertex> reversed = new ArrayList<>(ribbon);
                java.util.Collections.reverse(reversed);
                ribbon = reversed;
            }
            result.addAll(primitives(ribbon));
        }
        return List.copyOf(result);
    }

    private static List<Vertex> outline(SynapheiaSlopeConnections.Surface surface) {
        List<Vertex> result = new ArrayList<>(surface.points.length);
        for (var point : surface.points) result.add(point(point, 0, 0));
        return result;
    }

    private static List<List<Vertex>> primitives(List<Vertex> polygon) {
        List<SpiralStairCtmGeometry.CellVertex> projected = new ArrayList<>(polygon.size());
        for (var vertex : polygon) projected.add(new SpiralStairCtmGeometry.CellVertex(
                new SpiralStairCtmGeometry.Vertex(vertex.x, vertex.y, vertex.z, -1, 0, false, 0, 0, 0), vertex.u, vertex.v));
        List<List<Vertex>> result = new ArrayList<>();
        for (var primitive : ArchRepeatCtmRenderer.pomSafePrimitives(projected)) {
            List<Vertex> quad = new ArrayList<>(4);
            for (int index = 0; index < 4; index++) {
                var cell = index < primitive.size() ? primitive.get(index)
                        : ArchRepeatCtmRenderer.pomSafeTriangleGhost(primitive.get(0), primitive.get(1), primitive.get(2));
                var vertex = cell.vertex();
                quad.add(new Vertex(vertex.x(), vertex.y(), vertex.z(), cell.localS(), cell.localT()));
            }
            result.add(List.copyOf(quad));
        }
        return List.copyOf(result);
    }

    private static List<Vertex> clip(Direction face, List<Vertex> polygon, List<Vertex> boundary) {
        float winding = Math.signum(area(face, boundary));
        if (winding == 0) return List.of();
        for (int edge = 0; edge < boundary.size() && !polygon.isEmpty(); edge++) {
            Vertex a = boundary.get(edge), b = boundary.get((edge + 1) % boundary.size());
            List<Vertex> output = new ArrayList<>();
            Vertex previous = polygon.get(polygon.size() - 1);
            float previousDistance = turn(face, a, b, previous) * winding;
            for (Vertex current : polygon) {
                float distance = turn(face, a, b, current) * winding;
                if ((distance >= -EPS) != (previousDistance >= -EPS)) {
                    float amount = previousDistance / (previousDistance - distance);
                    output.add(previous.lerp(current, Math.max(0, Math.min(1, amount))));
                }
                if (distance >= -EPS) output.add(current);
                previous = current; previousDistance = distance;
            }
            polygon = output;
        }
        return polygon;
    }

    private static List<Vertex> deduplicate(List<Vertex> vertices) {
        List<Vertex> result = new ArrayList<>();
        for (var vertex : vertices) if (result.isEmpty() || !result.get(result.size() - 1).near(vertex)) result.add(vertex);
        if (result.size() > 1 && result.get(0).near(result.get(result.size() - 1))) result.remove(result.size() - 1);
        return result;
    }

    private static float area(Direction face, List<Vertex> vertices) {
        float area = 0;
        for (int index = 0; index < vertices.size(); index++) {
            Vertex a = vertices.get(index), b = vertices.get((index + 1) % vertices.size());
            area += s(face, a) * b.y - s(face, b) * a.y;
        }
        return area * .5F;
    }

    private static float turn(Direction face, Vertex a, Vertex b, Vertex c) {
        return (s(face, b) - s(face, a)) * (c.y - a.y) - (b.y - a.y) * (s(face, c) - s(face, a));
    }

    private static float s(Direction face, SynapheiaSlopeConnections.Point point) {
        return face.getAxis() == Direction.Axis.X ? point.z() : point.x();
    }
    private static float s(Direction face, Vertex point) { return face.getAxis() == Direction.Axis.X ? point.z : point.x; }
    private static Vertex point(SynapheiaSlopeConnections.Point p, float u, float v) {
        return new Vertex(p.x(), p.y(), p.z(), u, v);
    }
    private static Vertex inset(Direction face, SynapheiaSlopeConnections.Point p, float ds, float dt, float u) {
        return new Vertex(p.x() + (face.getAxis() == Direction.Axis.Z ? ds * RIBBON_WIDTH : 0),
                p.y() + dt * RIBBON_WIDTH,
                p.z() + (face.getAxis() == Direction.Axis.X ? ds * RIBBON_WIDTH : 0), u, RIBBON_WIDTH);
    }

    record Vertex(float x, float y, float z, float u, float v) {
        private Vertex lerp(Vertex other, float t) {
            return new Vertex(x + (other.x - x) * t, y + (other.y - y) * t,
                    z + (other.z - z) * t, u + (other.u - u) * t, v + (other.v - v) * t);
        }
        private boolean near(Vertex other) {
            return Math.abs(x - other.x) < EPS && Math.abs(y - other.y) < EPS && Math.abs(z - other.z) < EPS;
        }
    }

    private record FragmentKey(SynapheiaSlopeConnections.Surface surface, Direction face, int edges) { }
}
