package com.oliver.erydon.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ErydonSwapOverlayTest {
    @Test
    void everyOverlayInTheSourceInventorySwapsBothWaysWithoutChangingMaterialMotifOrShape() throws Exception {
        Set<Identifier> ids = inventory();
        var bronzeToSilver = ErydonSwapCommand.resolveOverlayPlan("bronze", "silver");
        var silverToBronze = ErydonSwapCommand.resolveOverlayPlan("Silver", "Bronze");
        int overlays = 0;
        for (Identifier id : ids) {
            boolean bronze = id.getPath().matches(".+_(trim|guilloche|quatrefoil|rosette)_bronze_.+");
            boolean silver = id.getPath().matches(".+_(trim|guilloche|quatrefoil|rosette)_silver_.+");
            var forward = bronze ? bronzeToSilver : silverToBronze;
            var reverse = bronze ? silverToBronze : bronzeToSilver;
            if (!bronze && !silver) {
                assertTrue(bronzeToSilver.targetId().apply(id).isEmpty(), id.toString());
                assertTrue(silverToBronze.targetId().apply(id).isEmpty(), id.toString());
                continue;
            }
            overlays++;
            Identifier target = forward.targetId().apply(id).orElseThrow();
            assertEquals(new Identifier(id.getNamespace(), id.getPath().replace(
                    bronze ? "_bronze_" : "_silver_", bronze ? "_silver_" : "_bronze_")), target);
            assertTrue(ids.contains(target), "Missing counterpart: " + target);
            assertEquals(id, reverse.targetId().apply(target).orElseThrow());
            assertTrue(reverse.targetId().apply(id).isEmpty(), "Wrong source metal: " + id);
        }
        assertTrue(overlays >= 2688, "Expected all ERYDON and Themelios overlay shapes, found " + overlays);
    }

    @Test
    void unrelatedNamespacesAndSolidBronzeRemainOutsideTheSelection() throws Exception {
        var plan = ErydonSwapCommand.resolveOverlayPlan("bronze", "silver");
        for (Identifier id : Set.of(new Identifier("minecraft", "aganite_trim_bronze_block"),
                new Identifier("othermod", "aganite_rosette_bronze_block"),
                new Identifier("daedalon", "bronze_athena_statue"),
                new Identifier("erydon", "aganite_herringbone_bronze_block"),
                new Identifier("erydon", "chalstrom_calacattum_weave_bronze_block"))) {
            assertTrue(plan.targetId().apply(id).isEmpty(), id.toString());
        }
    }

    @Test
    void rejectsInvalidAndIdenticalMetals() {
        assertThrows(CommandSyntaxException.class, () -> ErydonSwapCommand.resolveOverlayPlan("gold", "silver"));
        assertThrows(CommandSyntaxException.class, () -> ErydonSwapCommand.resolveOverlayPlan("bronze", "gold"));
        assertThrows(CommandSyntaxException.class, () -> ErydonSwapCommand.resolveOverlayPlan("bronze", "Bronze"));
        assertThrows(CommandSyntaxException.class, () -> ErydonSwapCommand.resolveOverlayPlan("silver", "Silver"));
    }

    @Test
    void allScopesAndHelpParseAndKeepRadiusLimits() {
        var dispatcher = dispatcher();
        for (String command : Set.of("swap overlay", "swap overlay help",
                "swap overlay chunk bronze silver", "swap overlay chunk silver bronze",
                "swap overlay radius bronze silver 1", "swap overlay radius silver bronze 32",
                "swap overlay box bronze silver ~ ~ ~ ~15 ~15 ~15", "swap undolast")) {
            var parsed = dispatcher.parse(command, null);
            assertFalse(parsed.getReader().canRead(), command);
            assertTrue(parsed.getExceptions().isEmpty(), command);
            assertNotNull(parsed.getContext().getCommand(), command);
        }
        for (int radius : new int[]{0, 33}) {
            var parsed = dispatcher.parse("swap overlay radius bronze silver " + radius, null);
            assertFalse(parsed.getExceptions().isEmpty());
        }
    }

    @Test
    void suggestionsOfferOnlyTheOppositeMetal() throws Exception {
        var dispatcher = dispatcher();
        assertEquals(Set.of("bronze", "silver"), suggestions(dispatcher, "swap overlay chunk "));
        assertEquals(Set.of("silver"), suggestions(dispatcher, "swap overlay chunk bronze "));
        assertEquals(Set.of("bronze"), suggestions(dispatcher, "swap overlay chunk Silver "));
        assertEquals(Set.of("silver"), suggestions(dispatcher, "swap overlay radius bronze s"));
    }

    private static CommandDispatcher<ServerCommandSource> dispatcher() {
        var dispatcher = new CommandDispatcher<ServerCommandSource>();
        dispatcher.register(ErydonSwapCommand.createCommand());
        return dispatcher;
    }

    private static Set<String> suggestions(CommandDispatcher<ServerCommandSource> dispatcher, String input) throws Exception {
        Set<String> values = new HashSet<>();
        for (var suggestion : dispatcher.getCompletionSuggestions(dispatcher.parse(input, null)).get().getList()) {
            values.add(suggestion.getText());
        }
        return values;
    }

    private static Set<Identifier> inventory() throws IOException {
        Set<Identifier> ids = new HashSet<>();
        try (var files = Files.list(Path.of("src/main/resources/assets/erydon/blockstates"))) {
            files.filter(path -> path.toString().endsWith(".json"))
                    .forEach(path -> ids.add(new Identifier("erydon", path.getFileName().toString().replaceFirst("\\.json$", ""))));
        }
        for (String line : Files.readAllLines(Path.of("src/test/resources/swap/companion-blocks.tsv"))) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\t");
            for (String material : fields[2].split(",")) {
                ids.add(new Identifier(fields[0], fields[1].replace("{material}", material)));
            }
        }
        return ids;
    }
}
