package com.oliver.erydon.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.oliver.erydon.Erydon;
import com.oliver.erydon.migration.ErydonIdMigration;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class ErydonShowcaseCommand {

    private static final int PAD_SIZE = 6;
    private static final int CELL_STRIDE = PAD_SIZE + 1;
    private static final int SECTION_GAP_ROWS = 1;
    private static final long CONFIRMATION_WINDOW_MILLIS = 300_000L;
    private static final SuggestionProvider<ServerCommandSource> NO_SUGGESTIONS =
            (context, builder) -> builder.buildFuture();

    private static final Map<String, Integer> VARIANT_ORDER = createVariantOrder();
    private static final Map<String, Integer> GLAZING_ORDER = createGlazingOrder();
    private static final Map<UUID, PendingShowcase> PENDING_SHOWCASES = new HashMap<>();

    private static final SimpleCommandExceptionType NO_TEXTURE_BLOCKS =
            new SimpleCommandExceptionType(Text.literal("No ERYDON texture blocks were found for the showcase."));
    private static final SimpleCommandExceptionType NO_GEORGIAN_BLOCK_TYPES =
            new SimpleCommandExceptionType(Text.literal("The Georgian showcase blocks are not available in the registry."));
    private static final SimpleCommandExceptionType NO_PENDING_SHOWCASE =
            new SimpleCommandExceptionType(Text.literal("No matching showcase placement is pending. Run the showcase command again."));
    private static final SimpleCommandExceptionType SHOWCASE_CONFIRMATION_EXPIRED =
            new SimpleCommandExceptionType(Text.literal("Showcase confirmation expired. Run the showcase command again."));
    private static final SimpleCommandExceptionType SHOWCASE_WRONG_WORLD =
            new SimpleCommandExceptionType(Text.literal("Confirm the showcase in the same world where it was prepared."));
    private static final SimpleCommandExceptionType SHOWCASE_WRONG_CODE =
            new SimpleCommandExceptionType(Text.literal("Incorrect confirmation code. Use the code from the warning, or run the showcase command again."));

    private ErydonShowcaseCommand() {
    }

    public static LiteralArgumentBuilder<ServerCommandSource> createCommand() {
        return literal("showcase")
                .then(literal("texture")
                        .executes(ctx -> prepareTextureShowcase(ctx.getSource()))
                        .then(argument("confirmation_code", IntegerArgumentType.integer(10, 99))
                                .suggests(NO_SUGGESTIONS)
                                .executes(ctx -> confirmShowcase(
                                        ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "confirmation_code"),
                                        ShowcaseType.TEXTURE))))
                .then(literal("blocktypes")
                        .then(literal("georgian")
                                .executes(ctx -> prepareGeorgianShowcase(ctx.getSource()))
                                .then(argument("confirmation_code", IntegerArgumentType.integer(10, 99))
                                        .suggests(NO_SUGGESTIONS)
                                        .executes(ctx -> confirmShowcase(
                                                ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "confirmation_code"),
                                                ShowcaseType.BLOCKTYPES_GEORGIAN)))));
    }

    private static int prepareTextureShowcase(ServerCommandSource source) throws CommandSyntaxException {
        pruneExpiredPendingShowcases();

        TextureShowcasePlan plan = buildTexturePlan();
        if (plan.totalPads() == 0) {
            throw NO_TEXTURE_BLOCKS.create();
        }

        String details = "Footprint: "
                + plan.width() + "x" + plan.depth()
                + " blocks, " + plan.familyColumns() + " columns x " + plan.textureRows() + " variant rows"
                + (plan.includesGlazing() ? " plus glazing." : ".");
        String note = "Empty white cells mean that variant does not exist for that material.";
        return prepareShowcase(source, ShowcaseType.TEXTURE, plan, details, note);
    }

    private static int prepareGeorgianShowcase(ServerCommandSource source) throws CommandSyntaxException {
        pruneExpiredPendingShowcases();

        ShowcasePlacementPlan plan = ErydonBlockTypeShowcase.buildGeorgianPlan();
        if (plan == null || plan.displayCount() == 0) {
            throw NO_GEORGIAN_BLOCK_TYPES.create();
        }

        String details = "Footprint: " + plan.width() + "x" + plan.height() + "x" + plan.depth()
                + " blocks, with " + plan.displayCount() + " assembled Georgian block types.";
        String note = "Glacium keeps the material consistent so the shapes are easy to compare; use /erydon showcase texture for material variants.";
        return prepareShowcase(source, ShowcaseType.BLOCKTYPES_GEORGIAN, plan, details, note);
    }

    private static int prepareShowcase(ServerCommandSource source,
                                       ShowcaseType type,
                                       ShowcasePlacementPlan plan,
                                       String detailsMessage,
                                       String noteMessage) throws CommandSyntaxException {
        pruneExpiredPendingShowcases();

        int confirmationCode = ThreadLocalRandom.current().nextInt(10, 100);
        ServerPlayerEntity player = source.getPlayerOrThrow();
        BlockPos outerMin = BlockPos.ofFloored(source.getPosition()).add(1, 0, 1);
        int replacedBlocks = countOccupiedBlocksInFootprint(source.getWorld(), outerMin, plan);
        PENDING_SHOWCASES.put(player.getUuid(), new PendingShowcase(
                type,
                source.getWorld().getRegistryKey(),
                outerMin,
                plan,
                confirmationCode,
                System.currentTimeMillis()
        ));

        BlockPos outerMax = outerMin.add(plan.width() - 1, plan.height() - 1, plan.depth() - 1);
        String command = type.commandBase() + " " + confirmationCode;
        Text header = Text.literal(type.readyMessage())
                .formatted(Formatting.GOLD);
        Text details = Text.literal(detailsMessage).formatted(Formatting.YELLOW);
        Text bounds = Text.literal("Area: " + formatPos(outerMin) + " to " + formatPos(outerMax)
                + ". Existing non-air blocks replaced in this volume: " + replacedBlocks + ".")
                .formatted(Formatting.YELLOW);
        Text note = Text.literal(noteMessage).formatted(Formatting.YELLOW);
        Text confirm = Text.literal("To place within 5 minutes: " + command)
                .formatted(Formatting.GREEN)
                .styled(style -> style
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Text.literal("Click to insert the exact confirmation command."))));

        source.sendFeedback(() -> header, false);
        source.sendFeedback(() -> details, false);
        source.sendFeedback(() -> bounds, false);
        source.sendFeedback(() -> note, false);
        source.sendFeedback(() -> confirm, false);
        return plan.displayCount();
    }

    private static int confirmShowcase(ServerCommandSource source,
                                       int confirmationCode,
                                       ShowcaseType expectedType) throws CommandSyntaxException {
        pruneExpiredPendingShowcases();

        ServerPlayerEntity player = source.getPlayerOrThrow();
        PendingShowcase pending = PENDING_SHOWCASES.get(player.getUuid());
        if (pending == null || pending.type() != expectedType) {
            throw NO_PENDING_SHOWCASE.create();
        }
        if (isExpired(pending)) {
            PENDING_SHOWCASES.remove(player.getUuid());
            throw SHOWCASE_CONFIRMATION_EXPIRED.create();
        }
        if (!pending.worldKey().equals(source.getWorld().getRegistryKey())) {
            throw SHOWCASE_WRONG_WORLD.create();
        }
        if (pending.confirmationCode() != confirmationCode) {
            throw SHOWCASE_WRONG_CODE.create();
        }

        PENDING_SHOWCASES.remove(player.getUuid());
        clearShowcaseVolume(source.getWorld(), pending.outerMin(), pending.plan());
        pending.plan().place(source.getWorld(), pending.outerMin());

        BlockPos outerMax = pending.outerMin().add(
                pending.plan().width() - 1,
                pending.plan().height() - 1,
                pending.plan().depth() - 1);
        String message = "Placed " + expectedType.resultName() + " with "
                + pending.plan().displayCount() + " " + expectedType.resultUnits() + " from "
                + formatPos(pending.outerMin()) + " to " + formatPos(outerMax) + ".";
        source.sendFeedback(() -> Text.literal(message), false);
        return pending.plan().displayCount();
    }

    private static TextureShowcasePlan buildTexturePlan() {
        TreeSet<String> families = new TreeSet<>();
        Map<String, Map<String, ShowcaseEntry>> entriesByVariant = new TreeMap<>();
        List<ShowcaseEntry> glazingEntries = new ArrayList<>();

        for (Identifier id : Registries.BLOCK.getIds()) {
            if (!Erydon.MOD_ID.equals(id.getNamespace())) {
                continue;
            }

            String path = id.getPath();
            String resourcePath = ErydonIdMigration.legacyResourcePath(path);
            if (!isTextureBlockPath(resourcePath)) {
                continue;
            }

            Block block = Registries.BLOCK.get(id);
            ShowcaseEntry entry = new ShowcaseEntry(
                    path, block.getDefaultState(), normalizeVariant(resourcePath));
            if (resourcePath.startsWith("glazing_")) {
                glazingEntries.add(entry);
                continue;
            }

            String family = extractFamily(resourcePath);
            families.add(family);
            String partnerFamily = extractWeavePartnerFamily(resourcePath);
            if (partnerFamily != null) {
                families.add(partnerFamily);
            }

            placeEntryInVariantRow(entriesByVariant.computeIfAbsent(entry.variant(), key -> new HashMap<>()),
                    family, partnerFamily, entry);
        }

        List<ShowcaseRow> placedRows = new ArrayList<>();
        int gridRow = 0;
        int totalPads = 0;
        int familyColumns = families.size();
        int textureRows = 0;

        List<String> orderedFamilies = List.copyOf(families);
        List<Map.Entry<String, Map<String, ShowcaseEntry>>> orderedVariants = new ArrayList<>(entriesByVariant.entrySet());
        orderedVariants.sort(variantRowComparator(familyColumns));

        for (Map.Entry<String, Map<String, ShowcaseEntry>> variantEntry : orderedVariants) {
            Map<String, ShowcaseEntry> rowEntries = variantEntry.getValue();
            List<ShowcaseCell> cells = new ArrayList<>(familyColumns);
            for (int column = 0; column < orderedFamilies.size(); column++) {
                String family = orderedFamilies.get(column);
                ShowcaseEntry entry = rowEntries.get(family);
                if (entry != null) {
                    cells.add(new ShowcaseCell(column, entry));
                    totalPads++;
                }
            }
            placedRows.add(new ShowcaseRow(variantEntry.getKey(), gridRow, List.copyOf(cells)));
            gridRow++;
            textureRows++;
        }

        boolean includesGlazing = !glazingEntries.isEmpty();
        int totalRows = gridRow;

        if (includesGlazing) {
            List<ShowcaseEntry> sortedGlazing = new ArrayList<>(glazingEntries);
            sortedGlazing.sort(glazingVariantComparator());
            List<ShowcaseCell> glazingCells = new ArrayList<>(sortedGlazing.size());
            for (int column = 0; column < sortedGlazing.size(); column++) {
                glazingCells.add(new ShowcaseCell(column, sortedGlazing.get(column)));
            }
            gridRow += SECTION_GAP_ROWS;
            placedRows.add(new ShowcaseRow("glazing", gridRow, List.copyOf(glazingCells)));
            gridRow++;
            totalPads += sortedGlazing.size();
            familyColumns = Math.max(familyColumns, sortedGlazing.size());
            totalRows = gridRow;
        }

        if (placedRows.isEmpty() || familyColumns == 0 || totalRows == 0) {
            return new TextureShowcasePlan(List.of(), 0, 0, 0, false, 0, 0);
        }

        int width = (familyColumns * CELL_STRIDE) + 1;
        int depth = (totalRows * CELL_STRIDE) + 1;
        return new TextureShowcasePlan(List.copyOf(placedRows), width, depth, totalPads, includesGlazing, familyColumns, textureRows);
    }

    private static void placeTextureShowcase(ServerWorld world, BlockPos outerMin, TextureShowcasePlan plan) {
        BlockState borderState = Blocks.WHITE_WOOL.getDefaultState();
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        int minX = outerMin.getX();
        int minY = outerMin.getY();
        int minZ = outerMin.getZ();
        int maxX = minX + plan.width() - 1;
        int maxZ = minZ + plan.depth() - 1;

        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                mutable.set(x, minY, z);
                world.setBlockState(mutable, borderState, Block.NOTIFY_ALL);
            }
        }

        for (ShowcaseRow row : plan.rows()) {
            int startZ = minZ + 1 + (row.gridRow() * CELL_STRIDE);
            for (ShowcaseCell cell : row.cells()) {
                int startX = minX + 1 + (cell.column() * CELL_STRIDE);
                fillPad(world, mutable, startX, minY, startZ, cell.entry().state());
            }
        }
    }

    private static void fillPad(ServerWorld world, BlockPos.Mutable mutable, int startX, int y, int startZ, BlockState state) {
        for (int dz = 0; dz < PAD_SIZE; dz++) {
            for (int dx = 0; dx < PAD_SIZE; dx++) {
                mutable.set(startX + dx, y, startZ + dz);
                world.setBlockState(mutable, state, Block.NOTIFY_ALL);
            }
        }
    }

    private static void pruneExpiredPendingShowcases() {
        long now = System.currentTimeMillis();
        PENDING_SHOWCASES.entrySet().removeIf(entry -> now - entry.getValue().createdAtMillis() > CONFIRMATION_WINDOW_MILLIS);
    }

    private static boolean isExpired(PendingShowcase pending) {
        return System.currentTimeMillis() - pending.createdAtMillis() > CONFIRMATION_WINDOW_MILLIS;
    }

    private static boolean isTextureBlockPath(String path) {
        return path.endsWith("_block") || path.contains("_block_");
    }

    private static int countOccupiedBlocksInFootprint(ServerWorld world,
                                                      BlockPos outerMin,
                                                      ShowcasePlacementPlan plan) {
        int occupied = 0;
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        int minX = outerMin.getX();
        int minY = outerMin.getY();
        int minZ = outerMin.getZ();
        int maxX = minX + plan.width() - 1;
        int maxY = minY + plan.height() - 1;
        int maxZ = minZ + plan.depth() - 1;

        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    mutable.set(x, y, z);
                    if (!world.getBlockState(mutable).isAir()) {
                        occupied++;
                    }
                }
            }
        }

        return occupied;
    }

    private static void clearShowcaseVolume(ServerWorld world,
                                            BlockPos outerMin,
                                            ShowcasePlacementPlan plan) {
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        int maxX = outerMin.getX() + plan.width() - 1;
        int maxY = outerMin.getY() + plan.height() - 1;
        int maxZ = outerMin.getZ() + plan.depth() - 1;

        for (int y = outerMin.getY(); y <= maxY; y++) {
            for (int z = outerMin.getZ(); z <= maxZ; z++) {
                for (int x = outerMin.getX(); x <= maxX; x++) {
                    mutable.set(x, y, z);
                    world.setBlockState(mutable, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
    }

    private static String extractFamily(String path) {
        int firstSeparator = path.indexOf('_');
        return firstSeparator < 0 ? path : path.substring(0, firstSeparator);
    }

    private static String normalizeVariant(String path) {
        String family = extractFamily(path);
        if (family.length() + 1 >= path.length()) {
            return path;
        }
        String variant = path.substring(family.length() + 1);
        if (variant.endsWith("_weave_bronze_block")) {
            return "weave_bronze_block";
        }
        if (variant.endsWith("_weave_grout_block")) {
            return "weave_grout_block";
        }
        return variant;
    }

    private static String extractWeavePartnerFamily(String path) {
        String family = extractFamily(path);
        if (!path.endsWith("_weave_bronze_block") && !path.endsWith("_weave_grout_block")) {
            return null;
        }

        String withoutFamily = path.substring(family.length() + 1);
        int weaveIndex = withoutFamily.indexOf("_weave_");
        if (weaveIndex <= 0) {
            return null;
        }
        return withoutFamily.substring(0, weaveIndex);
    }

    private static void placeEntryInVariantRow(Map<String, ShowcaseEntry> rowEntries,
                                               String family,
                                               String partnerFamily,
                                               ShowcaseEntry entry) {
        if (!rowEntries.containsKey(family)) {
            rowEntries.put(family, entry);
            return;
        }
        if (partnerFamily != null && !rowEntries.containsKey(partnerFamily)) {
            rowEntries.put(partnerFamily, entry);
        }
    }

    private static Comparator<Map.Entry<String, Map<String, ShowcaseEntry>>> variantRowComparator(int familyCount) {
        return Comparator
                .comparingInt((Map.Entry<String, Map<String, ShowcaseEntry>> entry) -> entry.getValue().size() == familyCount ? 0 : 1)
                .thenComparingInt(entry -> variantRank(entry.getKey()))
                .thenComparing(Map.Entry::getKey);
    }

    private static Comparator<ShowcaseEntry> glazingVariantComparator() {
        return Comparator
                .comparingInt((ShowcaseEntry entry) -> GLAZING_ORDER.getOrDefault(entry.variant(), 100))
                .thenComparing(ShowcaseEntry::path);
    }

    private static int variantRank(String variant) {
        Integer exact = VARIANT_ORDER.get(variant);
        if (exact != null) {
            return exact;
        }
        if (variant.endsWith("_weave_bronze_block")) {
            return 6;
        }
        if (variant.endsWith("_weave_grout_block")) {
            return 7;
        }
        if (variant.endsWith("_block")) {
            return 50;
        }
        return 100;
    }

    private static Map<String, Integer> createVariantOrder() {
        Map<String, Integer> order = new HashMap<>();
        order.put("block", 0);
        order.put("rock_block", 1);
        order.put("block_aged", 2);
        order.put("ashlar_block", 3);
        order.put("herringbone_bronze_block", 4);
        order.put("herringbone_grout_block", 5);
        order.put("weave_bronze_block", 6);
        order.put("weave_grout_block", 7);
        order.put("diaphanes_block", 8);
        order.put("rusticated_block", 9);
        order.put("hewn_block", 10);
        order.put("block_bronzetrim", 11);
        order.put("block_silvertrim", 12);
        order.put("block_bronzeguilloche", 13);
        order.put("block_silverguilloche", 14);
        order.put("block_bronzequatrefoil", 15);
        order.put("block_silverquatrefoil", 16);
        order.put("block_bronzerose", 17);
        order.put("block_silverrose", 18);
        return Map.copyOf(order);
    }

    private static Map<String, Integer> createGlazingOrder() {
        Map<String, Integer> order = new HashMap<>();
        order.put("bronze_block", 0);
        order.put("silver_block", 1);
        order.put("crystal_block", 2);
        order.put("tinted_block", 3);
        return Map.copyOf(order);
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private record PendingShowcase(ShowcaseType type,
                                   RegistryKey<World> worldKey,
                                   BlockPos outerMin,
                                   ShowcasePlacementPlan plan,
                                   int confirmationCode,
                                   long createdAtMillis) {
    }

    private record TextureShowcasePlan(List<ShowcaseRow> rows,
                                       int width,
                                       int depth,
                                       int totalPads,
                                       boolean includesGlazing,
                                       int familyColumns,
                                       int textureRows) implements ShowcasePlacementPlan {
        @Override
        public int height() {
            return 1;
        }

        @Override
        public int displayCount() {
            return totalPads;
        }

        @Override
        public void place(ServerWorld world, BlockPos outerMin) {
            placeTextureShowcase(world, outerMin, this);
        }
    }

    private enum ShowcaseType {
        TEXTURE("/erydon showcase texture", "Texture showcase ready.", "texture showcase", "pads"),
        BLOCKTYPES_GEORGIAN(
                "/erydon showcase blocktypes georgian",
                "Georgian block-type showcase ready.",
                "Georgian block-type showcase",
                "block types"
        );

        private final String commandBase;
        private final String readyMessage;
        private final String resultName;
        private final String resultUnits;

        ShowcaseType(String commandBase, String readyMessage, String resultName, String resultUnits) {
            this.commandBase = commandBase;
            this.readyMessage = readyMessage;
            this.resultName = resultName;
            this.resultUnits = resultUnits;
        }

        private String commandBase() {
            return commandBase;
        }

        private String readyMessage() {
            return readyMessage;
        }

        private String resultName() {
            return resultName;
        }

        private String resultUnits() {
            return resultUnits;
        }
    }

    private record ShowcaseRow(String variant, int gridRow, List<ShowcaseCell> cells) {
    }

    private record ShowcaseCell(int column, ShowcaseEntry entry) {
    }

    private record ShowcaseEntry(String path, BlockState state, String variant) {
    }
}
