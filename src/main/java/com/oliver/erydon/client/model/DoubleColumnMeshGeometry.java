package com.oliver.erydon.client.model;

import com.google.gson.JsonObject;
import com.oliver.erydon.client.pom.ColumnMeshNormalTransport;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MeshBuilder;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/** Reload-time geometry only. No world queries, clipping, or allocation while emitting a cell. */
final class DoubleColumnMeshGeometry {
    private static final double AREA_EPSILON = 1.e-24;
    private DoubleColumnMeshGeometry() { }

    record Vertex(double x, double y, double z, double nx, double ny, double nz) {
        double coordinate(int axis) { return axis == 0 ? x : axis == 1 ? y : z; }
        Vertex interpolate(Vertex other, double t, int axis, double plane) {
            // The clipped axis is exactly the requested cell plane. Recomputing
            // it by interpolation can produce a negative float at a zero edge.
            return new Vertex(axis == 0 ? plane : x + (other.x-x)*t,
                    axis == 1 ? plane : y + (other.y-y)*t,
                    axis == 2 ? plane : z + (other.z-z)*t,
                    nx+(other.nx-nx)*t, ny+(other.ny-ny)*t, nz+(other.nz-nz)*t);
        }
    }
    record Surface(List<Vertex> vertices) { }
    record Face(List<Vertex> vertices, Direction direction, Direction lightingHint) { }

    static final class Template {
        final List<Surface> surfaces;
        private volatile Cell[] doubled;
        private volatile Cell[] shaft;
        Template(List<Surface> surfaces) { this.surfaces = List.copyOf(surfaces); }

        Cell cell(Renderer renderer, boolean isShaft, int layer, int x, int z) {
            Cell[] selected=isShaft?shaft:doubled;
            if(selected==null) synchronized(this) {
                selected=isShaft?shaft:doubled;
                if(selected==null) {
                    selected=cells(renderer,isShaft);
                    if(isShaft) shaft=selected; else doubled=selected;
                }
            }
            return selected[(isShaft ? 0 : layer)*4 + x*2 + z];
        }

        List<Face> faces(boolean isShaft, int layer, int partX, int partZ) {
            return faces(isShaft,layer,partX,partZ,null);
        }

        private List<Face> faces(boolean isShaft,int layer,int partX,int partZ,Direction[] projections) {
            List<Face> result = new ArrayList<>();
            for (int sourceIndex=0;sourceIndex<surfaces.size();sourceIndex++) {
                Surface surface=surfaces.get(sourceIndex);
                List<Vertex> vertices = new ArrayList<>(surface.vertices.size());
                for (Vertex source : surface.vertices) {
                    double sy = isShaft ? 1 : 2;
                    double nx=source.nx/2, ny=source.ny/sy, nz=source.nz/2;
                    double norm=Math.sqrt(nx*nx+ny*ny+nz*nz);
                    vertices.add(new Vertex(source.x/8, source.y*sy/16, source.z/8,
                            nx/norm,ny/norm,nz/norm));
                }
                // A real face on an internal ownership plane belongs to only
                // one cell. Clipping never manufactures a cross-cell cap.
                if (onPlane(vertices,0,1) && partX==0 || onPlane(vertices,2,1) && partZ==0
                        || !isShaft && onPlane(vertices,1,1) && layer==1) continue;
                vertices=clip(vertices,0,partX,true);
                vertices=clip(vertices,0,partX+1,false);
                vertices=clip(vertices,2,partZ,true);
                vertices=clip(vertices,2,partZ+1,false);
                if (!isShaft) {
                    vertices=clip(vertices,1,layer,true);
                    vertices=clip(vertices,1,layer+1,false);
                }
                if (!hasArea(vertices)) continue;
                List<Vertex> local = new ArrayList<>(vertices.size());
                for (Vertex v:vertices) {
                    double normal=Math.sqrt(v.nx*v.nx+v.ny*v.ny+v.nz*v.nz);
                    local.add(new Vertex((float)(v.x-partX),(float)(v.y-(isShaft?0:layer)),(float)(v.z-partZ),
                            v.nx/normal,v.ny/normal,v.nz/normal));
                }
                local=compact(local);
                if(!hasArea(local)) continue;
                Direction direction=geometricDirection(local);
                for (int first=1;first+1<local.size();first+=2) {
                    List<Vertex> fragment=first+2<local.size()
                            ? List.of(local.get(0),local.get(first),local.get(first+1),local.get(first+2))
                            : List.of(local.get(0),local.get(first),local.get(first+1));
                    fragment=compact(new ArrayList<>(fragment));
                    if (hasArea(fragment)) result.add(new Face(List.copyOf(fragment),
                            projections==null?direction:projections[sourceIndex],direction));
                }
            }
            return List.copyOf(result);
        }

        private Cell[] cells(Renderer renderer, boolean isShaft) {
            Direction[] projections=sourceProjections(isShaft);
            Cell[] result=new Cell[isShaft?4:8];
            for (int layer=0;layer<(isShaft?1:2);layer++) for (int x=0;x<2;x++) for(int z=0;z<2;z++) {
                List<Face> faces=faces(isShaft,layer,x,z,projections);
                MeshBuilder builder=renderer.meshBuilder();
                QuadEmitter emitter=builder.getEmitter();
                var material=renderer.materialFinder().ambientOcclusion(TriState.FALSE).find();
                for(Face face:faces) {
                    for(int i=0;i<4;i++) {
                        // A-B-C-A keeps the encoder's first triangle nonzero
                        // in both NORMAL (A-B-C) and FLIP (B-C-A) order.
                        Vertex v=face.vertices.get(i%face.vertices.size());
                        emitter.pos(i,(float)v.x,(float)v.y,(float)v.z);
                        emitter.uv(i,u(face.direction,v),v(face.direction,v));
                        emitter.normal(i,(float)v.nx,(float)v.ny,(float)v.nz);
                        emitter.color(i,-1);
                        emitter.lightmap(i,0);
                    }
                    emitter.nominalFace(face.lightingHint).cullFace(null).material(material)
                            .tag(ColumnMeshNormalTransport.tag(face.direction)).emit();
                }
                result[layer*4+x*2+z]=new Cell(builder.build(),faces);
            }
            return result;
        }

        private Direction[] sourceProjections(boolean isShaft) {
            Direction[] result=new Direction[surfaces.size()];
            int index=0;
            for(Surface surface:surfaces) {
                if(surface.vertices.size()<3||surface.vertices.size()>4)
                    throw new IllegalArgumentException("Source texture projection needs a triangle or quad");
                List<Vertex> scaled=new ArrayList<>(surface.vertices.size());
                for(Vertex vertex:surface.vertices) scaled.add(new Vertex(vertex.x/8,
                        vertex.y*(isShaft?1:2)/16,vertex.z/8,vertex.nx,vertex.ny,vertex.nz));
                result[index++]=sourceDirection(scaled);
            }
            return result;
        }
    }

    record Cell(Mesh mesh, List<Face> faces) {
        List<BakedQuad> baked(Sprite sprite) {
            List<BakedQuad> result=new ArrayList<>(faces.size());
            for(Face face:faces) {
                int[] data=new int[32];
                for(int i=0;i<4;i++) {
                    Vertex vertex=face.vertices.get(i%face.vertices.size());
                    int at=i*8;
                    data[at]=Float.floatToRawIntBits((float)vertex.x);
                    data[at+1]=Float.floatToRawIntBits((float)vertex.y);
                    data[at+2]=Float.floatToRawIntBits((float)vertex.z);
                    data[at+3]=-1;
                    data[at+4]=Float.floatToRawIntBits(sprite.getFrameU(u(face.direction,vertex)*16));
                    data[at+5]=Float.floatToRawIntBits(sprite.getFrameV(v(face.direction,vertex)*16));
                    data[at+7]=(Math.round((float)vertex.nx*127)&255)
                            | (Math.round((float)vertex.ny*127)&255)<<8
                            | (Math.round((float)vertex.nz*127)&255)<<16;
                }
                result.add(new BakedQuad(data,-1,face.direction,sprite,true));
            }
            return List.copyOf(result);
        }
    }

    static Template parse(JsonObject document) {
        if (!"erydon_large_column_mesh_v1".equals(document.get("format").getAsString()))
            throw new IllegalArgumentException("Unknown large-column mesh format");
        List<Surface> surfaces=new ArrayList<>();
        for(var face:document.getAsJsonArray("faces")) {
            var corners=face.getAsJsonArray();
            if(corners.size()<3 || corners.size()>4) throw new IllegalArgumentException("Column mesh requires triangles or convex quads");
            List<Vertex> vertices=new ArrayList<>();
            for(var corner:corners) {
                var values=corner.getAsJsonArray();
                if(values.size()!=6) throw new IllegalArgumentException("Column mesh corner needs position and normal");
                double[] v=new double[6];
                for(int i=0;i<6;i++) {
                    v[i]=values.get(i).getAsDouble();
                    if(!Double.isFinite(v[i])) throw new IllegalArgumentException("Non-finite column mesh corner");
                }
                double n=v[3]*v[3]+v[4]*v[4]+v[5]*v[5];
                if(Math.abs(n-1)>1.e-6) throw new IllegalArgumentException("Non-unit column mesh normal");
                vertices.add(new Vertex(v[0],v[1],v[2],v[3],v[4],v[5]));
            }
            if(!hasArea(vertices)) throw new IllegalArgumentException("Zero-area column mesh face");
            surfaces.add(new Surface(List.copyOf(vertices)));
        }
        return new Template(surfaces);
    }

    private static boolean onPlane(List<Vertex> vs,int axis,double value) {
        return vs.stream().allMatch(v->Math.abs(v.coordinate(axis)-value)<1.e-10);
    }
    private static List<Vertex> compact(List<Vertex> input) {
        List<Vertex> result=new ArrayList<>();
        for(Vertex v:input) {
            if(result.isEmpty()||!samePosition(result.get(result.size()-1),v)) result.add(v);
        }
        if(result.size()>1&&samePosition(result.get(0),result.get(result.size()-1))) result.remove(result.size()-1);
        boolean changed=true;
        while(changed&&result.size()>3) {
            changed=false;
            for(int i=0;i<result.size();i++) {
                Vertex a=result.get((i+result.size()-1)%result.size()),b=result.get(i),c=result.get((i+1)%result.size());
                double x=(b.y-a.y)*(c.z-a.z)-(b.z-a.z)*(c.y-a.y);
                double y=(b.z-a.z)*(c.x-a.x)-(b.x-a.x)*(c.z-a.z);
                double z=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);
                if(x==0&&y==0&&z==0) {result.remove(i);changed=true;break;}
            }
        }
        // Any surviving four-corner face is convex. Rotate it so the first
        // triangle has maximal area in the actual emitted float coordinates.
        if(result.size()==4) {
            int best=0;double maximum=-1;
            for(int i=0;i<4;i++) {
                Vertex a=result.get(i),b=result.get((i+1)%4),c=result.get((i+2)%4);
                double x=(b.y-a.y)*(c.z-a.z)-(b.z-a.z)*(c.y-a.y);
                double y=(b.z-a.z)*(c.x-a.x)-(b.x-a.x)*(c.z-a.z);
                double z=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);
                double area=x*x+y*y+z*z;
                if(area>maximum) {best=i;maximum=area;}
            }
            if(best!=0) {List<Vertex> rotated=new ArrayList<>();for(int i=0;i<4;i++)rotated.add(result.get((best+i)%4));result=rotated;}
        }
        return result;
    }
    private static boolean samePosition(Vertex a,Vertex b) {return a.x==b.x&&a.y==b.y&&a.z==b.z;}
    private static List<Vertex> clip(List<Vertex> input,int axis,double plane,boolean lower) {
        if(input.isEmpty()) return input;
        List<Vertex> output=new ArrayList<>();
        Vertex previous=input.get(input.size()-1);
        double a=(previous.coordinate(axis)-plane)*(lower?1:-1);
        for(Vertex vertex:input) {
            double b=(vertex.coordinate(axis)-plane)*(lower?1:-1);
            if((a>=0)!=(b>=0)) output.add(previous.interpolate(vertex,a/(a-b),axis,plane));
            if(b>=0) output.add(vertex);
            previous=vertex;a=b;
        }
        return output;
    }
    private static boolean hasArea(List<Vertex> vertices) {
        if(vertices.size()<3) return false;
        Vertex a=vertices.get(0);
        for(int i=1;i+1<vertices.size();i++) {
            Vertex b=vertices.get(i),c=vertices.get(i+1);
            double x=(b.y-a.y)*(c.z-a.z)-(b.z-a.z)*(c.y-a.y);
            double y=(b.z-a.z)*(c.x-a.x)-(b.x-a.x)*(c.z-a.z);
            double z=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);
            if(x*x+y*y+z*z>AREA_EPSILON) return true;
        }
        return false;
    }
    private static Direction geometricDirection(List<Vertex> vertices) {
        Vertex a=vertices.get(0);
        for(int i=1;i+1<vertices.size();i++) {
            Vertex b=vertices.get(i),c=vertices.get(i+1);
            float x=(float)((b.y-a.y)*(c.z-a.z)-(b.z-a.z)*(c.y-a.y));
            float y=(float)((b.z-a.z)*(c.x-a.x)-(b.x-a.x)*(c.z-a.z));
            float z=(float)((b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x));
            if(x*x+y*y+z*z>AREA_EPSILON) return Direction.getFacing(x,y,z);
        }
        throw new IllegalArgumentException("No geometric column face normal");
    }
    private static Direction sourceDirection(List<Vertex> vertices) {
        // Texture projection is metadata, not a local renderer light face.
        // Normalise the strongest original double-precision triangle before
        // choosing its canonical direction; globally float-collapsed slivers
        // must not erase a valid source plane or change its cells' UV phase.
        Vertex a=vertices.get(0);
        double maximum=0,nx=0,ny=0,nz=0;
        for(int i=1;i+1<vertices.size();i++) {
            Vertex b=vertices.get(i),c=vertices.get(i+1);
            double x=(b.y-a.y)*(c.z-a.z)-(b.z-a.z)*(c.y-a.y);
            double y=(b.z-a.z)*(c.x-a.x)-(b.x-a.x)*(c.z-a.z);
            double z=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);
            double area=x*x+y*y+z*z;
            if(area>maximum){maximum=area;nx=x;ny=y;nz=z;}
        }
        if(maximum<=0||!Double.isFinite(maximum))throw new IllegalArgumentException("No finite source texture plane");
        double length=Math.sqrt(maximum);
        return Direction.getFacing((float)(nx/length),(float)(ny/length),(float)(nz/length));
    }
    static float u(Direction face,Vertex v) {
        return (float)switch(face) { case NORTH->1-v.x; case SOUTH,UP,DOWN->v.x;case WEST->v.z;case EAST->1-v.z; };
    }
    static float v(Direction face,Vertex v) {
        return (float)switch(face) { case UP->v.z;case DOWN->1-v.z;default->1-v.y; };
    }
}
