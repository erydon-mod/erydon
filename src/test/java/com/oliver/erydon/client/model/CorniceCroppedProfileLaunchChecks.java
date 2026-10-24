package com.oliver.erydon.client.model;

import com.google.gson.JsonParser;
import com.oliver.erydon.block.CorniceBlock;
import com.oliver.erydon.block.CorniceConnections;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BakedQuadFactory;
import net.minecraft.client.render.model.ModelRotation;
import net.minecraft.client.render.model.json.JsonUnbakedModel;
import net.minecraft.client.render.model.json.ModelElement;
import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Independent authored-surface/UV and element-volume oracles for the scale-preserving short profiles. */
public final class CorniceCroppedProfileLaunchChecks {
    private CorniceCroppedProfileLaunchChecks() { }
    private static int cases,surfaceSamples,capSamples;

    public static void run() throws Exception {
        for(String style:List.of("modern","georgian","byzantine","gothic")) {
            String templateStyle=style.equals("byzantine")?"guilloche":style;
            Identifier id=new Identifier("erydon","block/cornice/"+templateStyle+"/cornice_"+templateStyle+"_straight");
            JsonUnbakedModel model;
            try(var reader=new InputStreamReader(CorniceCroppedProfileLaunchChecks.class.getClassLoader()
                    .getResourceAsStream("assets/erydon/models/"+id.getPath()+".json"),StandardCharsets.UTF_8)) {
                var json=JsonParser.parseReader(reader).getAsJsonObject();
            var textures=new com.google.gson.JsonObject();textures.addProperty("stone","erydon:cornice_probe");json.add("textures",textures);
            model=JsonUnbakedModel.deserialize(json.toString());
            }
            for(int resolution:new int[]{16,32,64})try(var contents=new SpriteContents(new Identifier("erydon","crop_probe"),
                    new SpriteDimensions(resolution,resolution),new NativeImage(resolution,resolution,false),AnimationResourceMetadata.EMPTY)) {
                Sprite sprite=new ProbeSprite(contents,resolution);
                List<BakedQuad> source=new ArrayList<>();BakedQuadFactory factory=new BakedQuadFactory();
                for(var element:model.getElements())for(var face:element.faces.entrySet())source.add(factory.bake(element.from,element.to,
                        face.getValue(),sprite,face.getKey(),ModelRotation.X0_Y0,element.rotation,element.shade,id));
                List<BakedQuad> volumes=CorniceProfileVolumes.bake(model,ignored->sprite,id);
                int[][] before=source.stream().map(q->q.getVertexData().clone()).toArray(int[][]::new);
                CorniceBlock block=(CorniceBlock)Registries.BLOCK.get(new Identifier("erydon","glacium_cornice_"+style));
                for(var shape:CorniceBlock.CorniceShape.values())if(shape.isHalfSupport())for(Direction facing:Direction.Type.HORIZONTAL) {
                    BlockState state=block.getDefaultState().with(CorniceBlock.SHAPE,shape).with(CorniceBlock.FACING,facing);
                    var segment=CorniceConnections.nativeComponents(state).get(0);
                    if(!segment.usesProfileCrop())continue;
                    List<BakedQuad> result=CorniceAngledGeometry.fit(source,volumes,segment);
                    require(!result.isEmpty(),"Cropping removed the entire "+style+" profile");
                    sourceSurfaces(source,result,segment,sprite,style);
                    for(BakedQuad q:result)if(onCap(q,segment))capUvs(q,sprite);
                    if(resolution==16&&facing==Direction.NORTH&&(shape==CorniceBlock.CorniceShape.HALF_EDGE_LOW
                            ||shape==CorniceBlock.CorniceShape.HALF_EDGE_HIGH))caps(model,result,segment,style);
                    cases++;
                }
                for(int i=0;i<source.size();i++)require(java.util.Arrays.equals(before[i],source.get(i).getVertexData()),"Cropping mutated the shared authored master");
            }
        }
        require(cases==192&&surfaceSamples>10000&&capSamples>10000,"Incomplete actual-profile crop/closure matrix");
        System.out.println("ERYDON_CORNICE_CROPPED_PROFILES_OK cases="+cases+" authoredSurfaceUvSamples="+surfaceSamples
                +" independentElementVolumeCapSamples="+capSamples+" styles=4 facings=4 atlas=16/32/64 ornamentScale=1");
    }

    private static void sourceSurfaces(List<BakedQuad> source,List<BakedQuad> output,CorniceConnections.Segment segment,Sprite sprite,String style) {
        for(BakedQuad q:output)for(int vertex=0;vertex<4;vertex++) {
            double[] p=point(q,vertex);double along=along(p,segment),depth=depth(p,segment);
            require(along>=-3.e-6&&along<=segment.run().length()+3.e-6,"Cropped profile escaped its real half-length bounds");
            require(value(q,vertex,4)>=sprite.getMinU()&&value(q,vertex,4)<=sprite.getMaxU()
                    &&value(q,vertex,5)>=sprite.getMinV()&&value(q,vertex,5)<=sprite.getMaxV(),"New crop UV escaped its nonzero atlas slot");
            if(onCap(q,segment))continue;
            boolean found=false;
            for(int tile=-2;tile<=2&&!found;tile++) {
                double[] authored={along+segment.sourcePhase()-tile,p[1],depth};
                for(BakedQuad original:source)if(covers(original,authored,value(q,vertex,4),value(q,vertex,5),true)){found=true;break;}
            }
            require(found,"Cropped "+style+" surface/UV is not a rigid copy of an actual authored face; ornament was deformed");surfaceSamples++;
        }
        // Reverse coverage proves that cropping did not silently discard an ornament.
        for(BakedQuad original:source)for(int tile=-1;tile<=1;tile++) {
            double[] authored=new double[3];double u=0,v=0;
            for(int i=0;i<4;i++){for(int axis=0;axis<3;axis++)authored[axis]+=value(original,i,axis)/4;u+=value(original,i,4)/4;v+=value(original,i,5)/4;}
            double physical=authored[0]+tile-segment.sourcePhase();
            if(physical<.00001||physical>segment.run().length()-.00001)continue;
            // Unit end faces are internal to the repeated source, rather than exterior surfaces.
            if(Math.abs(authored[0])<1.e-7||Math.abs(authored[0]-1)<1.e-7)continue;
            var placed=segment.profilePoint(physical,authored[2]);double[] p={placed.x(),authored[1],placed.z()};
            boolean found=false;for(BakedQuad q:output)if(covers(q,p,u,v,true)){found=true;break;}
            require(found,"A retained actual "+style+" ornament surface vanished at native scale");surfaceSamples++;
        }
    }

    private static void capUvs(BakedQuad quad,Sprite sprite) {
        Direction face=quad.getFace();int axis=face.getAxis()==Direction.Axis.X?2:0;double centre=0,centreY=0;
        for(int i=0;i<4;i++){centre+=value(quad,i,axis)/4;centreY+=value(quad,i,1)/4;}
        int cell=(int)Math.floor(centre),row=(int)Math.floor(centreY);
        for(int i=0;i<4;i++){
            double local=value(quad,i,axis)-cell,u=face==Direction.NORTH||face==Direction.EAST?1-local:local,v=1-(value(quad,i,1)-row);
            require(Math.abs(value(quad,i,4)-sprite.getFrameU(u*16))<.000001&&Math.abs(value(quad,i,5)-sprite.getFrameV(v*16))<.000001,
                    "New cap changed its physical world-plane UV scale or phase");
        }
    }

    private static void caps(JsonUnbakedModel model,List<BakedQuad> quads,CorniceConnections.Segment segment,String style) {
        for(boolean end:new boolean[]{false,true}) {
            double boundary=end?segment.run().length():0;
            List<BakedQuad> cap=quads.stream().filter(q->onCap(q,segment)&&Math.abs(along(point(q,0),segment)-boundary)<1.e-6).toList();
            require(!cap.isEmpty(),"Actual "+style+" short profile has an open cropped end");
            for(int iy=0;iy<103;iy++)for(int iz=0;iz<97;iz++) {
                double y=(4+(iy+.317)*12/103.)/16,z=(-.4+(iz+.619)*8.8/97.)/16;
                int expected=volume(model,(boundary+segment.sourcePhase())*16+(end?-.0002:.0002),y*16,z*16);
                if(expected==0)continue; // Exclude only mathematically near-boundary samples from the independent rotation oracle.
                var p=segment.profilePoint(boundary,z);double[] actual={p.x(),y,p.z()};
                int count=0;for(BakedQuad q:cap)if(covers(q,actual,0,0,false))count++;
                require((count>0)==(expected>0),"Actual "+style+" crop cap fills an authored hole or misses solid material at phase="
                        +segment.sourcePhase()+" end="+end+" y="+y+" depth="+z+" expected="+expected+" capCount="+count);
                require(count<=1,"Actual "+style+" crop cap contains overlapping coplanar pieces");capSamples++;
            }
        }
    }

    /** Independent inverse rotation/rescale containment of the actual authored cuboids, including repeated neighbours. */
    private static int volume(JsonUnbakedModel model,double x,double y,double z) {
        boolean boundary=false;
        for(int tile=-2;tile<=2;tile++)for(ModelElement element:model.getElements()) {
            double px=x-tile*16,py=y,pz=z;
            if(element.rotation!=null) {
                var r=element.rotation;double ox=r.origin().x*16,oy=r.origin().y*16,oz=r.origin().z*16;
                px-=ox;py-=oy;pz-=oz;double angle=Math.toRadians(r.angle()),c=Math.cos(angle),s=Math.sin(angle);
                if(r.rescale()){if(r.axis()!=Direction.Axis.X)px*=c;if(r.axis()!=Direction.Axis.Y)py*=c;if(r.axis()!=Direction.Axis.Z)pz*=c;}
                double a=px,b=py,d=pz;
                switch(r.axis()){case X->{py=b*c+d*s;pz=d*c-b*s;}case Y->{px=a*c-d*s;pz=d*c+a*s;}case Z->{px=a*c+b*s;py=b*c-a*s;}}
                px+=ox;py+=oy;pz+=oz;
            }
            double[] p={px,py,pz},low={element.from.x,element.from.y,element.from.z},high={element.to.x,element.to.y,element.to.z};
            boolean inside=true,near=false;
            for(int axis=0;axis<3;axis++){inside&=p[axis]>low[axis]&&p[axis]<high[axis];near|=Math.abs(p[axis]-low[axis])<.0001||Math.abs(p[axis]-high[axis])<.0001;}
            if(inside&&!near)return 1;
            if(inside||near&&p[0]>=low[0]-.0001&&p[0]<=high[0]+.0001&&p[1]>=low[1]-.0001&&p[1]<=high[1]+.0001&&p[2]>=low[2]-.0001&&p[2]<=high[2]+.0001)boundary=true;
        }
        return boundary?0:-1;
    }

    private static boolean onCap(BakedQuad q,CorniceConnections.Segment segment) {
        double first=along(point(q,0),segment);
        if(Math.abs(first)>2.e-6&&Math.abs(first-segment.run().length())>2.e-6)return false;
        for(int i=1;i<4;i++)if(Math.abs(along(point(q,i),segment)-first)>2.e-6)return false;return true;
    }
    private static double along(double[] p,CorniceConnections.Segment s){return(p[0]-s.run().a().x())*s.run().tangentX()+(p[2]-s.run().a().z())*s.run().tangentZ();}
    private static double depth(double[] p,CorniceConnections.Segment s){return(p[0]-s.run().a().x())*s.run().normalX()+(p[2]-s.run().a().z())*s.run().normalZ();}
    private static boolean covers(BakedQuad q,double[] p,double u,double v,boolean uv){return triangle(q,0,1,2,p,u,v,uv)||triangle(q,0,2,3,p,u,v,uv);}
    private static boolean triangle(BakedQuad q,int a,int b,int c,double[] p,double u,double v,boolean uv) {
        double[] origin=point(q,a),ab=new double[3],ac=new double[3],ap=new double[3];
        for(int i=0;i<3;i++){ab[i]=value(q,b,i)-origin[i];ac[i]=value(q,c,i)-origin[i];ap[i]=p[i]-origin[i];}
        double aa=dot(ab,ab),bb=dot(ac,ac),cc=dot(ab,ac),d=aa*bb-cc*cc;if(d<1.e-20)return false;
        double t=(dot(ap,ab)*bb-dot(ap,ac)*cc)/d,w=(dot(ap,ac)*aa-dot(ap,ab)*cc)/d;
        double tolerance=uv?.00002:1.e-9;
        if(t<-tolerance||w<-tolerance||t+w>1+tolerance)return false;
        double error=0;for(int i=0;i<3;i++){double e=ap[i]-t*ab[i]-w*ac[i];error+=e*e;}if(error>1.e-11)return false;
        return !uv||Math.abs(u-value(q,a,4)-t*(value(q,b,4)-value(q,a,4))-w*(value(q,c,4)-value(q,a,4)))<.00001
                &&Math.abs(v-value(q,a,5)-t*(value(q,b,5)-value(q,a,5))-w*(value(q,c,5)-value(q,a,5)))<.00001;
    }
    private static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
    private static double[] point(BakedQuad q,int i){return new double[]{value(q,i,0),value(q,i,1),value(q,i,2)};}
    private static float value(BakedQuad q,int i,int slot){return Float.intBitsToFloat(q.getVertexData()[i*8+slot]);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static final class ProbeSprite extends Sprite {
        ProbeSprite(SpriteContents contents,int size){super(new Identifier("erydon","crop_probe_atlas"),contents,size*4,size*4,size,size*2);}
        @Override public float getAnimationFrameDelta(){return 0;}
    }
}
