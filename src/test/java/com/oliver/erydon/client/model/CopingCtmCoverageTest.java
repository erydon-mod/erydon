package com.oliver.erydon.client.model;

import com.google.gson.JsonParser;
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
        assertEquals(135,ids.size());
        for(var element:ids) {
            Identifier block=new Identifier(element.getAsString());
            String prefix=block.getPath().replace("_coping_georgian","");
            Path directory=root.resolve("assets/minecraft/optifine/ctm/"+prefix);
            Path file=directory.resolve("coping.properties");
            byte[] bytes=Files.readAllBytes(file);
            assertFalse(bytes.length>=3 && (bytes[0]&255)==239 && (bytes[1]&255)==187 && (bytes[2]&255)==191);
            Properties props=new Properties();
            try(var input=Files.newInputStream(file)) { props.load(input); }
            assertEquals(Set.of(block),SynapheiaManifest.parseBlocks(props.getProperty("matchBlocks")));
            var rule=SynapheiaManifest.parseRule(new Identifier("minecraft","optifine/ctm/"+prefix+"/coping.properties"),
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
        var snapshot=SynapheiaService.publish(new SynapheiaManifest.Prepared(List.copyOf(active),"native, older-pack",0,270,0,0));
        for(var element:ids) {
            Identifier id=new Identifier(element.getAsString());
            assertNotNull(snapshot.planFor(id));
            assertTrue(snapshot.planFor(id).hasRepeat());
            for(var surface:com.oliver.erydon.block.CopingBlock.Surface.values())
                for(var facing:net.minecraft.util.math.Direction.Type.HORIZONTAL)
                    assertTrue(SynapheiaModelLoadingPlugin.ownsCtmModel(new ModelIdentifier(id,"facing="+facing.asString()+",surface="+surface.asString()+",offset=false,waterlogged=false")));
            assertFalse(SynapheiaModelLoadingPlugin.ownsCtmModel(new ModelIdentifier(id,"inventory")));
        }
    }
}
