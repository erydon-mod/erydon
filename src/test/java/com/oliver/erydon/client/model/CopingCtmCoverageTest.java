package com.oliver.erydon.client.model;

import com.google.gson.JsonParser;
import com.oliver.erydon.block.CopingDesign;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CopingCtmCoverageTest {
    @Test void allCopingsKeepTheirOwnRepeatRuleWhenAPackReplacesTheMaterialRule() throws Exception {
        Path root=Path.of("src/main/resources");
        var ids=JsonParser.parseString(Files.readString(root.resolve("data/erydon/tags/blocks/coping.json")))
                .getAsJsonObject().getAsJsonArray("values");
        List<SynapheiaManifest.Rule> active=new ArrayList<>();
        assertEquals(270,ids.size());
        var byDesign=new java.util.EnumMap<CopingDesign,Integer>(CopingDesign.class);
        for(var element:ids) {
            Identifier block=new Identifier(element.getAsString());
            CopingDesign design=CopingDesign.fromBlockPath(block.getPath());
            assertNotNull(design,block.toString());
            byDesign.merge(design,1,Integer::sum);
            String prefix=block.getPath().substring(0,block.getPath().length()-design.idSuffix().length()-1);
            Path directory=root.resolve("assets/minecraft/optifine/ctm/"+prefix);
            String ruleFile=design==CopingDesign.GEORGIAN ? "coping.properties" : design.idSuffix()+".properties";
            Path file=directory.resolve(ruleFile);
            byte[] bytes=Files.readAllBytes(file);
            assertFalse(bytes.length>=3 && (bytes[0]&255)==239 && (bytes[1]&255)==187 && (bytes[2]&255)==191);
            Properties props=new Properties();
            try(var input=Files.newInputStream(file)) { props.load(input); }
            assertEquals(Set.of(block),SynapheiaManifest.parseBlocks(props.getProperty("matchBlocks")));
            var rule=SynapheiaManifest.parseRule(new Identifier("minecraft","optifine/ctm/"+prefix+"/"+ruleFile),
                    "native",props,Set.of(block));
            active.add(rule);
            assertEquals(36,rule.tiles().size());
            try(var paths=Files.list(directory)) {
                Path material=paths.filter(p -> p.getFileName().toString().startsWith("a_") && p.toString().endsWith("_base.properties"))
                        .findFirst().orElseThrow();
                Properties olderPack=new Properties();
                try(var input=Files.newInputStream(material)) { olderPack.load(input); }
                assertEquals(olderPack.getProperty("tiles"),props.getProperty("tiles"),"Use the pack's existing tile paths");
                // The active pack owns this path and explicitly contains only its older block.
                Set<Identifier> olderBlocks=Set.of(new Identifier("erydon",prefix+"_block"));
                active.add(SynapheiaManifest.parseRule(new Identifier("minecraft","optifine/ctm/"+prefix+"/a_base.properties"),
                        "older-pack",olderPack,olderBlocks));
            }
        }
        for(CopingDesign design:CopingDesign.values()) assertEquals(135,byDesign.get(design),design.toString());
        var snapshot=SynapheiaService.publish(new SynapheiaManifest.Prepared(List.copyOf(active),"native, older-pack",0,540,0,0));
        for(var element:ids) {
            Identifier id=new Identifier(element.getAsString());
            assertNotNull(snapshot.planFor(id));
            assertTrue(snapshot.planFor(id).hasRepeat());
            assertEquals(net.minecraft.util.math.Direction.UP,SynapheiaRepeatBakedModel.repeatProjectionFace(id,
                    net.minecraft.util.math.Direction.NORTH,net.minecraft.util.math.Direction.SOUTH,
                    CopingTexturePlane.tag(net.minecraft.util.math.Direction.UP)),"Stored texture plane was lost for "+id);
            for(var surface:com.oliver.erydon.block.CopingBlock.Surface.values())
                for(var facing:net.minecraft.util.math.Direction.Type.HORIZONTAL)
                    assertTrue(SynapheiaModelLoadingPlugin.ownsCtmModel(new ModelIdentifier(id,"facing="+facing.asString()+",surface="+surface.asString()+",offset=false,waterlogged=false")));
            assertFalse(SynapheiaModelLoadingPlugin.ownsCtmModel(new ModelIdentifier(id,"inventory")));
        }
    }
}
