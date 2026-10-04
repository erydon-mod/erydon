package com.oliver.erydon.client.model;

import com.google.gson.JsonParser;
import com.oliver.erydon.block.CopingBlock;
import com.oliver.erydon.block.CopingConnections;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.minecraft.block.BlockState;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Test-only snapshots from the same editable raw geometry and fitted emitter as placed copings. */
public final class CopingFinialMeshContact {
    private static final Map<CopingBlock.Surface,CopingGeometry> GEOMETRY = new EnumMap<>(CopingBlock.Surface.class);
    private static final Map<BlockState,TopPlane> PLANES = new HashMap<>();
    private static final double EPSILON = .00001;

    public record Point(double x,double y,double z) { }
    public record TopPlane(List<Point> vertices) {
        public double height(double x,double z) {
            Point a=vertices.get(0),b=vertices.get(1),c=vertices.get(2);
            double ax=b.x-a.x,ay=b.y-a.y,az=b.z-a.z;
            double bx=c.x-a.x,by=c.y-a.y,bz=c.z-a.z;
            double nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx;
            if(Math.abs(ny)<EPSILON) throw new AssertionError("Coping top has no mounting plane");
            return a.y-(nx*(x-a.x)+nz*(z-a.z))/ny;
        }
        public boolean contains(double x,double z) {
            double sign=0;
            for(int i=0;i<vertices.size();i++) {
                Point a=vertices.get(i),b=vertices.get((i+1)%vertices.size());
                double cross=(b.x-a.x)*(z-a.z)-(b.z-a.z)*(x-a.x);
                if(Math.abs(cross)<EPSILON) continue;
                if(sign!=0 && Math.signum(cross)!=sign) return false;
                sign=Math.signum(cross);
            }
            return true;
        }
    }

    public static TopPlane top(BlockState state) {
        return PLANES.computeIfAbsent(state,CopingFinialMeshContact::placedTop);
    }

    private static TopPlane placedTop(BlockState state) {
        CopingBlock.Surface profile=state.get(CopingBlock.SURFACE);
        CopingGeometry geometry=GEOMETRY.computeIfAbsent(profile,CopingFinialMeshContact::readGeometry);
        var surfaces=geometry.joined(profile,new CopingConnections.Joins(0));
        CopingGeometry.Surface top=surfaces.stream().filter(surface -> surface.face()==Direction.UP
                && !surface.normals().isEmpty()).max(java.util.Comparator.comparingDouble(surface ->
                surface.vertices().stream().mapToDouble(vertex -> CopingGeometry.normalHeight(vertex,profile)).average()
                        .orElseThrow())).orElseThrow(() -> new AssertionError("Raw coping lost its top face"));
        if(top.vertices().size()!=4) throw new AssertionError("Free-standing coping top was not a quad");
        var emitter=RendererAccess.INSTANCE.getRenderer().meshBuilder().getEmitter();
        CopingGeometry.positions(emitter,top,state.get(CopingBlock.OFFSET) && !profile.aligned(),0,1,2,3);
        // The production aligned transform also gives the exact ordinary quarter-turn position transform.
        new CopingHorizontalTransform(profile.fit.pose(state.get(CopingBlock.FACING))).transform(emitter);
        List<Point> points=new ArrayList<>(4);
        for(int i=0;i<4;i++) points.add(new Point(emitter.x(i),emitter.y(i)-1,emitter.z(i)));
        TopPlane plane=new TopPlane(List.copyOf(points));
        for(Point point:points) if(Math.abs(plane.height(point.x,point.z)-point.y)>EPSILON)
            throw new AssertionError("Authored coping top is not coplanar: "+profile);
        return plane;
    }

    private static CopingGeometry readGeometry(CopingBlock.Surface profile) {
        CopingBlock.Surface authored=profile.authoringProfile();
        String path="/assets/erydon/authoring_models/block/coping/georgian/coping_georgian_"+authored.asString()+".json";
        try(var stream=CopingFinialMeshContact.class.getResourceAsStream(path)) {
            if(stream==null) throw new AssertionError("Missing approved coping geometry "+path);
            try(var reader=new InputStreamReader(stream,StandardCharsets.UTF_8)) {
                return CopingGeometry.parse(JsonParser.parseReader(reader).getAsJsonObject(),
                        new Identifier("erydon","glacium_coping_georgian"),authored).fitted(profile.fit);
            }
        } catch(java.io.IOException exception) { throw new IllegalStateException("Cannot read raw coping geometry",exception); }
    }
}
