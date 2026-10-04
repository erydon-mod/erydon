package com.oliver.erydon.client.model;

import com.google.gson.JsonParser;
import com.oliver.erydon.block.CopingBlock;
import com.oliver.erydon.block.CopingConnections;
import com.oliver.erydon.block.CopingHorizontalFit;
import net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer;
import net.fabricmc.fabric.impl.client.indigo.renderer.helper.NormalHelper;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Exercises the actual Fabric mesh encoder/decoder, which discards nominal-face hints. */
public final class CopingMeshProjectionLaunchChecks {
    public static void run() throws Exception {
        int[] checked={0},changedNormals={0},smoothNormals={0};
        Identifier id=new Identifier("erydon","glacium_coping_georgian");
        for(var profile:CopingBlock.Surface.values()) {
            if(profile.aligned()) continue;
            String resource="assets/erydon/authoring_models/block/coping/georgian/coping_georgian_"+profile.asString()+".json";
            var stream=CopingMeshProjectionLaunchChecks.class.getClassLoader().getResourceAsStream(resource);
            if(stream==null) throw new AssertionError("Missing coping profile "+resource);
            com.google.gson.JsonObject json;
            try(var reader=new InputStreamReader(stream,StandardCharsets.UTF_8)) {
                json=JsonParser.parseReader(reader).getAsJsonObject();
            }
            var geometry=CopingGeometry.parse(json,id,profile);
            for(var other:CopingBlock.Surface.values()) {
                if(other.aligned()) continue;
                var joins=CopingConnections.Joins.samePlane(other,CopingConnections.WEST|CopingConnections.EAST);
                for(var surface:geometry.joined(profile,joins)) {
                    if(surface.vertices().size()!=4) continue;
                    var builder=IndigoRenderer.INSTANCE.meshBuilder();
                    var emitter=builder.getEmitter();
                    CopingGeometry.positions(emitter,surface,false,0,1,2,3);
                    for(int i=0;i<4;i++) {
                        emitter.uv(i,0,0).color(i,-1).lightmap(i,0);
                    }
                    emitter.emit();
                    builder.build().forEach(quad -> {
                        if(quad.nominalFace()!=surface.face()) changedNormals[0]++;
                        for(int vertex=0;vertex<4;vertex++) {
                            boolean expected=!surface.normals().isEmpty();
                            if(quad.hasNormal(vertex)!=expected) throw new AssertionError("Mesh replay changed coping normals");
                            if(expected) {
                                var normal=surface.normals().get(vertex);
                                int packed=NormalHelper.packNormal(normal.x(),normal.y(),normal.z());
                                if(Math.abs(quad.normalX(vertex)-NormalHelper.unpackNormalX(packed))>.000001
                                        || Math.abs(quad.normalY(vertex)-NormalHelper.unpackNormalY(packed))>.000001
                                        || Math.abs(quad.normalZ(vertex)-NormalHelper.unpackNormalZ(packed))>.000001)
                                    throw new AssertionError("Mesh replay lost smooth coping normals");
                                smoothNormals[0]++;
                            }
                        }
                        for(int turns=0;turns<4;turns++) {
                            Direction expected=surface.face();
                            if(expected.getAxis()!=Direction.Axis.Y)
                                for(int turn=0;turn<turns;turn++) expected=expected.rotateYClockwise();
                            int tag=CopingTexturePlane.rotate(quad.tag(),turns);
                            Direction projection=SynapheiaRepeatBakedModel.repeatProjectionFace(id,quad.lightFace(),quad.nominalFace(),tag);
                            if(projection!=expected) throw new AssertionError("Mesh replay lost coping texture plane: "+profile+" -> "+other);
                            checked[0]++;
                        }
                    });
                }
            }
        }
        if(changedNormals[0]==0) throw new AssertionError("Fixture did not exercise discarded nominal faces");
        if(smoothNormals[0]==0) throw new AssertionError("Fixture did not exercise smooth profile normals");
        horizontalFits(id);
        System.out.println("ERYDON_COPING_MESH_PHASE_OK: "+checked[0]+" replay/facing cases, "+changedNormals[0]+" lighting-plane changes retained authored CTM planes, "+smoothNormals[0]+" smooth vertex normals survived replay");
    }
    private static void horizontalFits(Identifier id) throws Exception {
        var stream=CopingMeshProjectionLaunchChecks.class.getClassLoader().getResourceAsStream(
                "assets/erydon/authoring_models/block/coping/georgian/coping_georgian_flat.json");
        if(stream==null) throw new AssertionError("Missing edited flat parent");
        CopingGeometry flat;
        try(var reader=new InputStreamReader(stream,StandardCharsets.UTF_8)) {
            flat=CopingGeometry.parse(JsonParser.parseReader(reader).getAsJsonObject(),id,CopingBlock.Surface.FLAT);
        }
        int[] count={0};
        for(var fit:CopingHorizontalFit.values()) for(Direction facing:Direction.Type.HORIZONTAL) {
            var pose=fit.pose(facing); var transform=new CopingHorizontalTransform(pose);
            for(var surface:flat.fitted(fit).joined(CopingBlock.Surface.FLAT,0)) {
                if(surface.vertices().size()!=4) continue;
                var builder=IndigoRenderer.INSTANCE.meshBuilder(); var emitter=builder.getEmitter();
                CopingGeometry.positions(emitter,surface,false,0,1,2,3);
                for(int vertex=0;vertex<4;vertex++) emitter.uv(vertex,surface.vertices().get(vertex).u(),surface.vertices().get(vertex).v()).color(vertex,-1).lightmap(vertex,0);
                transform.transform(emitter); emitter.emit();
                builder.build().forEach(quad -> {
                    Direction projection=SynapheiaRepeatBakedModel.repeatProjectionFace(id,quad.lightFace(),quad.nominalFace(),quad.tag());
                    if(projection!=transform.direction(surface.face())) throw new AssertionError("Aligned coping lost its placed texture plane");
                    for(int vertex=0;vertex<4;vertex++) {
                        var original=surface.vertices().get(vertex);
                        if(Math.abs(quad.x(vertex)-transform.x(original.x(),original.z()))>.000001
                                || Math.abs(quad.z(vertex)-transform.z(original.x(),original.z()))>.000001
                                || Math.abs(quad.y(vertex)-original.y())>.000001)
                            throw new AssertionError("Placed aligned mesh differs from state-only Axiom transform");
                        if(Math.abs(quad.u(vertex)-original.u())>.000001 || Math.abs(quad.v(vertex)-original.v())>.000001)
                            throw new AssertionError("Aligned coping shifted an individual UV instead of using world CTM phase");
                        if(!surface.normals().isEmpty() && Math.abs(quad.normalY(vertex)-surface.normals().get(vertex).y())>.000001)
                            throw new AssertionError("Yaw pitched a horizontal coping normal");
                    }
                    count[0]++;
                });
            }
        }
        System.out.println("ERYDON_COPING_HORIZONTAL_MESH_OK: "+count[0]+" editable fit/facing replay cases preserve normals, authored planes, UVs and Axiom positions");
    }
}
