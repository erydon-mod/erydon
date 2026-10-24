package com.oliver.erydon.client.model;

import com.oliver.erydon.block.CorniceConnections;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/** Cached rigid half profiles. End planes cut ornaments instead of squeezing their spacing. */
final class CorniceCroppedGeometry {
    private static final double EPS = 1.e-7;
    private CorniceCroppedGeometry() { }

    static List<BakedQuad> fit(List<BakedQuad> source, List<BakedQuad> volumes, CorniceConnections.Segment segment) {
        List<BakedQuad> result = new ArrayList<>();
        List<Edge> startEdges = new ArrayList<>(), endEdges = new ArrayList<>();
        double phase = segment.sourcePhase();
        // A supported half arm can extend at most .75 physical blocks at a mitre.
        // Adjacent unit profiles preserve the authored ornament phase at tile boundaries.
        for (int tile = -2; tile <= 2; tile++) for (BakedQuad quad : source) {
            List<Vertex> polygon = vertices(quad, tile - phase);
            // Unit-tile end faces are internal to the repeated profile. The actual
            // unjoined crop ends are closed below from their real slice contours.
            if (onSourceEnd(quad)) continue;
            polygon = clip(polygon, segment, true);
            polygon = clip(polygon, segment, false);
            if (polygon.size() < 3) continue;
            boolean atStart = onCut(polygon, segment, true), atEnd = onCut(polygon, segment, false);
            if (atStart || atEnd) continue;
            emit(result, quad, polygon, segment);
        }
        // Authored models omit hidden faces. Closed vanilla-baked element volumes
        // supply their actual slice boundaries without adding those hidden faces to the render mesh.
        for (int tile = -2; tile <= 2; tile++) for (BakedQuad quad : volumes) {
            List<Vertex> polygon = clip(clip(vertices(quad, tile - phase), segment, true), segment, false);
            if (polygon.size() < 3 || onCut(polygon, segment, true) || onCut(polygon, segment, false)) continue;
            if (!segment.joinedStart()) edges(startEdges, polygon, segment, true, quad);
            if (!segment.joinedEnd()) edges(endEdges, polygon, segment, false, quad);
        }
        if (!segment.joinedStart()) close(result, startEdges, segment, true);
        if (!segment.joinedEnd()) close(result, endEdges, segment, false);
        return List.copyOf(result);
    }

    private static List<Vertex> vertices(BakedQuad source, double shift) {
        int[] data = source.getVertexData();
        List<Vertex> result = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int[] vertex = new int[8]; System.arraycopy(data, i * 8, vertex, 0, 8);
            vertex[0] = Float.floatToRawIntBits((float)(Float.intBitsToFloat(vertex[0]) + shift));
            append(result, new Vertex(vertex));
        }
        if (result.size() > 1 && same(result.get(0), result.get(result.size()-1))) result.remove(result.size()-1);
        return result;
    }

    private static boolean onSourceEnd(BakedQuad source) {
        int[] data = source.getVertexData();
        boolean start = true, end = true;
        for (int i=0;i<4;i++) {
            double x=Float.intBitsToFloat(data[i*8]);
            start &= Math.abs(x) < EPS; end &= Math.abs(x-1) < EPS;
        }
        return start || end;
    }

    private static double distance(Vertex v, CorniceConnections.Segment segment, boolean start) {
        var cut = start ? segment.start() : segment.end();
        double distance = v.value(0) - segment.run().length() * cut.at(v.value(2));
        return start ? distance : -distance;
    }

    private static boolean onCut(List<Vertex> polygon, CorniceConnections.Segment segment, boolean start) {
        for (Vertex v : polygon) if (Math.abs(distance(v, segment, start)) > EPS) return false;
        return true;
    }

    private static List<Vertex> clip(List<Vertex> input, CorniceConnections.Segment segment, boolean start) {
        if (input.isEmpty()) return input;
        List<Vertex> result = new ArrayList<>();
        Vertex a=input.get(input.size()-1); double da=distance(a, segment, start);
        for (Vertex b:input) {
            double db=distance(b, segment, start);
            if ((da >= 0) != (db >= 0)) {
                Vertex intersection=a.interpolate(b, da/(da-db));
                int[] data=intersection.data.clone();
                data[0]=Float.floatToRawIntBits((float)(segment.run().length()
                        *(start?segment.start():segment.end()).at(intersection.value(2))));
                append(result,new Vertex(data));
            }
            if (db >= 0) append(result,b);
            a=b; da=db;
        }
        if(result.size()>1&&same(result.get(0),result.get(result.size()-1))) result.remove(result.size()-1);
        return result;
    }

    private static void edges(List<Edge> output,List<Vertex> polygon,CorniceConnections.Segment segment,
                              boolean start,BakedQuad source) {
        Vertex a=polygon.get(polygon.size()-1);
        for(Vertex b:polygon) {
            if(!same(a,b)&&Math.abs(distance(a,segment,start))<EPS&&Math.abs(distance(b,segment,start))<EPS)
                output.add(new Edge(a,b,source));
            a=b;
        }
    }

    /** Exact planar union of oriented slice boundaries; internal edges cancel and holes stay empty. */
    private static void close(List<BakedQuad> output,List<Edge> edges,CorniceConnections.Segment segment,boolean start) {
        List<Double> boundaries=new ArrayList<>();
        for(Edge edge:edges) {boundaries.add(edge.a.value(1));boundaries.add(edge.b.value(1));}
        // Split where slice edges exchange order as well as at every authored corner.
        for(int i=0;i<edges.size();i++)for(int j=i+1;j<edges.size();j++) {
            Edge a=edges.get(i),b=edges.get(j);
            double ay=a.b.value(1)-a.a.value(1),az=a.b.value(2)-a.a.value(2);
            double by=b.b.value(1)-b.a.value(1),bz=b.b.value(2)-b.a.value(2);
            double cross=ay*bz-az*by;
            if(Math.abs(cross)<1.e-15) continue;
            double dy=b.a.value(1)-a.a.value(1),dz=b.a.value(2)-a.a.value(2);
            double t=(dy*bz-dz*by)/cross,u=(dy*az-dz*ay)/cross;
            if(t>0&&t<1&&u>0&&u<1)boundaries.add(a.a.value(1)+ay*t);
        }
        boundaries.sort(Double::compare);
        List<Double> rows=new ArrayList<>();
        for(double y:boundaries)if(rows.isEmpty()||y-rows.get(rows.size()-1)>1.e-9)rows.add(y);
        for(int row=0;row+1<rows.size();row++) {
            double low=rows.get(row),high=rows.get(row+1),middle=(low+high)*.5;
            if(high-low<1.e-9)continue;
            List<Crossing> crossings=new ArrayList<>();
            for(Edge edge:edges) {
                double a=edge.a.value(1),b=edge.b.value(1);
                if(middle<=Math.min(a,b)||middle>=Math.max(a,b))continue;
                Vertex v=atHeight(edge,middle);
                crossings.add(new Crossing(v.value(2),b>a?1:-1,edge));
            }
            crossings.sort(java.util.Comparator.comparingDouble(Crossing::z));
            List<Interval> intervals=new ArrayList<>();
            int winding=0;Edge left=null;
            for(int first=0;first<crossings.size();) {
                int last=first+1,change=crossings.get(first).delta;
                while(last<crossings.size()&&Math.abs(crossings.get(last).z-crossings.get(first).z)<EPS)
                    change+=crossings.get(last++).delta;
                int next=winding+change;
                if(winding==0&&next!=0)left=crossings.get(first).edge;
                else if(winding!=0&&next==0&&left!=null)intervals.add(new Interval(left,crossings.get(first).edge));
                winding=next;first=last;
            }
            // Missing authored faces do not define a closed volume: retain their open boundary.
            if(winding!=0)continue;
            for(Interval interval:intervals) {
                List<Vertex> polygon=new ArrayList<>(List.of(atHeight(interval.left,low),atHeight(interval.right,low),
                        atHeight(interval.right,high),atHeight(interval.left,high)));
                if(!start)java.util.Collections.reverse(polygon);
                emitCap(output,interval.left.source,polygon,segment,start);
            }
        }
    }

    private static Vertex atHeight(Edge edge,double y) {
        Vertex v=edge.a.interpolate(edge.b,(y-edge.a.value(1))/(edge.b.value(1)-edge.a.value(1)));
        int[] data=v.data.clone();data[1]=Float.floatToRawIntBits((float)y);return new Vertex(data);
    }
    private record Crossing(double z,int delta,Edge edge) { }
    private record Interval(Edge left,Edge right) { }

    private static List<Vertex> placed(List<Vertex> polygon,CorniceConnections.Segment segment) {
        List<Vertex> result=new ArrayList<>(polygon.size());
        for(Vertex vertex:polygon) {
            int[] data=vertex.data.clone();var point=segment.profilePoint(vertex.value(0),vertex.value(2));
            data[0]=Float.floatToRawIntBits((float)point.x());data[2]=Float.floatToRawIntBits((float)point.z());result.add(new Vertex(data));
        }
        return result;
    }

    private static void emitCap(List<BakedQuad> output,BakedQuad source,List<Vertex> polygon,CorniceConnections.Segment segment,boolean start) {
        polygon=placed(polygon,segment);
        Direction face=Direction.getFacing((float)(segment.run().tangentX()*(start?-1:1)),0,
                (float)(segment.run().tangentZ()*(start?-1:1)));
        int axis=face.getAxis()==Direction.Axis.X?2:0;
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY,minY=min,maxY=max;
        for(Vertex v:polygon){min=Math.min(min,v.value(axis));max=Math.max(max,v.value(axis));minY=Math.min(minY,v.value(1));maxY=Math.max(maxY,v.value(1));}
        // Split at real world texture-cell planes. No UV clamp/quad shift is used to hide an atlas overrun.
        for(int cell=(int)Math.floor(min);cell<=(int)Math.floor(max-1.e-9);cell++)
            for(int row=(int)Math.floor(minY);row<=(int)Math.floor(maxY-1.e-9);row++) {
                List<Vertex> part=axisClip(axisClip(axisClip(axisClip(polygon,axis,cell,true),axis,cell+1,false),1,row,true),1,row+1,false);
                if(part.size()<3)continue;
                List<Vertex> mapped=new ArrayList<>(part.size());
                for(Vertex v:part) {
                    int[] data=v.data.clone();double local=v.value(axis)-cell;
                    double u=face==Direction.NORTH||face==Direction.EAST?1-local:local,uv=1-(v.value(1)-row);
                    data[4]=Float.floatToRawIntBits(source.getSprite()==null?(float)u:source.getSprite().getFrameU(u*16));
                    data[5]=Float.floatToRawIntBits(source.getSprite()==null?(float)uv:source.getSprite().getFrameV(uv*16));mapped.add(new Vertex(data));
                }
                emitMapped(output,source,mapped,face);
            }
    }

    private static List<Vertex> axisClip(List<Vertex> polygon,int axis,double plane,boolean lower) {
        if(polygon.isEmpty())return polygon;
        List<Vertex> result=new ArrayList<>();Vertex a=polygon.get(polygon.size()-1);double da=(a.value(axis)-plane)*(lower?1:-1);
        for(Vertex b:polygon) {
            double db=(b.value(axis)-plane)*(lower?1:-1);
            if((da>=0)!=(db>=0)) {Vertex v=a.interpolate(b,da/(da-db));int[] data=v.data.clone();data[axis]=Float.floatToRawIntBits((float)plane);append(result,new Vertex(data));}
            if(db>=0)append(result,b);a=b;da=db;
        }
        if(result.size()>1&&same(result.get(0),result.get(result.size()-1)))result.remove(result.size()-1);
        return result;
    }

    private static void emit(List<BakedQuad> output,BakedQuad source,List<Vertex> polygon,CorniceConnections.Segment segment) {
        Direction original=source.getFace();
        Direction projection=Direction.getFacing((float)(segment.run().tangentX()*original.getOffsetX()+segment.run().normalX()*original.getOffsetZ()),
                original.getOffsetY(),(float)(segment.run().tangentZ()*original.getOffsetX()+segment.run().normalZ()*original.getOffsetZ()));
        emitMapped(output,source,placed(polygon,segment),projection);
    }

    private static void emitMapped(List<BakedQuad> output,BakedQuad source,List<Vertex> polygon,Direction projection) {
        List<Vertex> compact=new ArrayList<>();for(Vertex vertex:polygon)append(compact,vertex);
        if(compact.size()>1&&same(compact.get(0),compact.get(compact.size()-1)))compact.remove(compact.size()-1);
        if(compact.size()<3)return;
        if(compact.size()==4) {
            int best=0;double maximum=-1;
            for(int i=0;i<4;i++){double area=areaSquared(compact.get(i),compact.get((i+1)%4),compact.get((i+2)%4));if(area>maximum){maximum=area;best=i;}}
            if(best!=0){List<Vertex> rotated=new ArrayList<>();for(int i=0;i<4;i++)rotated.add(compact.get((best+i)%4));compact=rotated;}
        }
        for(int i=1;i+1<compact.size();i++) {
            Vertex[] vertices={compact.get(0),compact.get(i),compact.get(i+1),compact.get(0)};
            if(compact.size()==4) {vertices=new Vertex[]{compact.get(0),compact.get(1),compact.get(2),compact.get(3)};i=compact.size();}
            int[] data=new int[32];
            for(int vertex=0;vertex<4;vertex++) System.arraycopy(vertices[vertex].data,0,data,vertex*8,8);
            double ax=value(data,1,0)-value(data,0,0),ay=value(data,1,1)-value(data,0,1),az=value(data,1,2)-value(data,0,2);
            double bx=value(data,2,0)-value(data,0,0),by=value(data,2,1)-value(data,0,1),bz=value(data,2,2)-value(data,0,2);
            double nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx,length=Math.sqrt(nx*nx+ny*ny+nz*nz);
            if(length<1.e-12) continue;
            nx/=length;ny/=length;nz/=length;
            int packed=(Math.round((float)nx*127)&255)|((Math.round((float)ny*127)&255)<<8)|((Math.round((float)nz*127)&255)<<16);
            for(int vertex=0;vertex<4;vertex++) data[vertex*8+7]=(data[vertex*8+7]&0xFF000000)|packed;
            // Rigid cardinal placement keeps the original texture plane even when
            // float rounding on a tiny clipped triangle changes a45-degree lighting tie.
            output.add(new BakedQuad(data,source.getColorIndex(),projection,source.getSprite(),source.hasShade()));
        }
    }

    private static double areaSquared(Vertex a,Vertex b,Vertex c) {
        double ax=b.value(0)-a.value(0),ay=b.value(1)-a.value(1),az=b.value(2)-a.value(2);
        double bx=c.value(0)-a.value(0),by=c.value(1)-a.value(1),bz=c.value(2)-a.value(2);
        double x=ay*bz-az*by,y=az*bx-ax*bz,z=ax*by-ay*bx;return x*x+y*y+z*z;
    }
    private static double value(int[] data,int vertex,int slot) {return Float.intBitsToFloat(data[vertex*8+slot]);}
    private static void append(List<Vertex> vertices,Vertex v) {if(vertices.isEmpty()||!same(vertices.get(vertices.size()-1),v)) vertices.add(v);}
    private static boolean same(Vertex a,Vertex b) {
        return Math.abs(a.value(0)-b.value(0))<EPS&&Math.abs(a.value(1)-b.value(1))<EPS&&Math.abs(a.value(2)-b.value(2))<EPS;
    }
    private record Edge(Vertex a,Vertex b,BakedQuad source) { }
    private record Vertex(int[] data) {
        double value(int slot) {return Float.intBitsToFloat(data[slot]);}
        Vertex interpolate(Vertex other,double t) {
            int[] result=data.clone();
            for(int slot:new int[]{0,1,2,4,5}) result[slot]=Float.floatToRawIntBits((float)(value(slot)+(other.value(slot)-value(slot))*t));
            return new Vertex(result);
        }
    }
}
