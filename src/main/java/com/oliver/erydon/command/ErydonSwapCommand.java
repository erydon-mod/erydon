package com.oliver.erydon.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.oliver.erydon.state.ClusterManualLockState;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class ErydonSwapCommand {

    private static final int MAX_RADIUS = 32;
    private static final long MAX_BOX_VOLUME = 524_288L;
    // Builders and Axiom can intentionally place unsupported architectural states.
    // A material swap must preserve those placements instead of re-running survival checks.
    private static final int MATERIAL_SWAP_FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
    private static final String SOURCE_ARGUMENT = "source";
    private static final String TARGET_ARGUMENT = "target";
    private static final Map<ServerWorld, LastSwapUndo> LAST_UNDO_BY_WORLD = new WeakHashMap<>();

    private static final SimpleCommandExceptionType SAME_FAMILY =
            new SimpleCommandExceptionType(Text.literal("Source and target families must be different."));
    private static final SimpleCommandExceptionType SAME_OVERLAY =
            new SimpleCommandExceptionType(Text.translatable("command.erydon.swap.overlay.same"));
    private static final DynamicCommandExceptionType INVALID_OVERLAY =
            new DynamicCommandExceptionType(metal -> Text.translatable("command.erydon.swap.overlay.invalid", metal));
    private static final SimpleCommandExceptionType ALL_ERYDON_BLOCKS_TARGET =
            new SimpleCommandExceptionType(Text.literal("All-block selectors can only be used as the source."));
    private static final SimpleCommandExceptionType NO_SWAP_TO_UNDO =
            new SimpleCommandExceptionType(Text.literal("There is no swap to undo in this world."));
    private static final DynamicCommandExceptionType NO_MATCHING_BLOCKS =
            new DynamicCommandExceptionType(family ->
                    Text.literal("No ERYDON-family blocks in family '")
                            .append(family instanceof Text text ? text : Text.literal(String.valueOf(family)))
                            .append("' were found in the selected area."));
    private static final DynamicCommandExceptionType INVALID_FAMILY =
            new DynamicCommandExceptionType(family ->
                    Text.literal("Invalid family '" + family + "'. Use a name such as Aganite, \"Aganite Family\", \"Aganite Aged\", or \"Borealis Rusticated\"."));
    private static final DynamicCommandExceptionType INVALID_NAMESPACE =
            new DynamicCommandExceptionType(family ->
                    Text.literal("Only ERYDON, Daedalon, and Themelios families are supported here: '" + family + "'."));
    private static final Dynamic2CommandExceptionType BOX_TOO_LARGE =
            new Dynamic2CommandExceptionType((actual, limit) ->
                    Text.literal("Selected volume is " + actual + " blocks, which exceeds the limit of " + limit + "."));

    private ErydonSwapCommand() {
    }

    public static LiteralArgumentBuilder<ServerCommandSource> createCommand() {
        return addScopes(literal("swap")
                .executes(ctx -> sendHelp(ctx.getSource()))
                .then(literal("help").executes(ctx -> sendHelp(ctx.getSource())))
                .then(literal("undolast")
                        .executes(ctx -> executeUndoLast(ctx.getSource())))
                .then(addScopes(literal("overlay")
                        .executes(ctx -> sendOverlayHelp(ctx.getSource()))
                        .then(literal("help").executes(ctx -> sendOverlayHelp(ctx.getSource()))), true)), false);
    }

    private static LiteralArgumentBuilder<ServerCommandSource> addScopes(
            LiteralArgumentBuilder<ServerCommandSource> root, boolean overlay) {
        return root
                .then(literal("chunk")
                        .then(argument(SOURCE_ARGUMENT, StringArgumentType.string())
                                .suggests((context, builder) -> suggestSources(builder, overlay))
                                .then(argument(TARGET_ARGUMENT, StringArgumentType.string())
                                        .suggests((context, builder) -> suggestTargets(context, builder, overlay))
                                        .executes(ctx -> executeChunk(
                                                ctx.getSource(),
                                                resolvePlan(ctx, overlay))))))
                .then(literal("radius")
                        .then(argument(SOURCE_ARGUMENT, StringArgumentType.string())
                                .suggests((context, builder) -> suggestSources(builder, overlay))
                                .then(argument(TARGET_ARGUMENT, StringArgumentType.string())
                                        .suggests((context, builder) -> suggestTargets(context, builder, overlay))
                                        .then(argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                                                .executes(ctx -> executeRadius(
                                                        ctx.getSource(),
                                                        resolvePlan(ctx, overlay),
                                                        IntegerArgumentType.getInteger(ctx, "radius")))))))
                .then(literal("box")
                        .then(argument(SOURCE_ARGUMENT, StringArgumentType.string())
                                .suggests((context, builder) -> suggestSources(builder, overlay))
                                .then(argument(TARGET_ARGUMENT, StringArgumentType.string())
                                        .suggests((context, builder) -> suggestTargets(context, builder, overlay))
                                        .then(argument("from", BlockPosArgumentType.blockPos())
                                                .then(argument("to", BlockPosArgumentType.blockPos())
                                                        .executes(ctx -> executeBox(
                                                                ctx.getSource(),
                                                                resolvePlan(ctx, overlay),
                                                                BlockPosArgumentType.getBlockPos(ctx, "from"),
                                                                BlockPosArgumentType.getBlockPos(ctx, "to"))))))));
    }

    private static int sendOverlayHelp(ServerCommandSource source) {
        source.sendFeedback(() -> Text.translatable("command.erydon.swap.overlay.help"), false);
        return 1;
    }

    private static SwapPlan resolvePlan(CommandContext<ServerCommandSource> context, boolean overlay)
            throws CommandSyntaxException {
        String from = StringArgumentType.getString(context, SOURCE_ARGUMENT);
        String to = StringArgumentType.getString(context, TARGET_ARGUMENT);
        if (overlay) {
            return resolveOverlayPlan(from, to);
        }
        FamilyPair families = resolveFamilies(from, to);
        return new SwapPlan(
                ErydonSwapFamilyDatabase.displayText(families.fromFamily().canonicalKey()),
                ErydonSwapFamilyDatabase.displayText(families.toFamily().canonicalKey()),
                id -> ErydonSwapFamilyDatabase.match(id, families.fromFamily(), families.toFamily())
                        .map(match -> match.targetId(families.toFamily())));
    }

    static SwapPlan resolveOverlayPlan(String from, String to) throws CommandSyntaxException {
        ErydonSwapOverlay source = ErydonSwapOverlay.parse(from).orElseThrow(() -> INVALID_OVERLAY.create(from));
        ErydonSwapOverlay target = ErydonSwapOverlay.parse(to).orElseThrow(() -> INVALID_OVERLAY.create(to));
        if (source == target) {
            throw SAME_OVERLAY.create();
        }
        return new SwapPlan(Text.translatable("command.erydon.swap.overlay." + source.key()),
                Text.translatable("command.erydon.swap.overlay." + target.key()),
                id -> source.targetId(id, target));
    }

    private static CompletableFuture<Suggestions> suggestSources(SuggestionsBuilder builder, boolean overlay) {
        return overlay ? suggestMetals(builder, Optional.empty())
                : suggestFamilies(ErydonSwapFamilyDatabase.sourceKeys(), builder);
    }

    private static CompletableFuture<Suggestions> suggestTargets(
            CommandContext<ServerCommandSource> context, SuggestionsBuilder builder, boolean overlay)
            throws CommandSyntaxException {
        return overlay ? suggestMetals(builder, ErydonSwapOverlay.parse(StringArgumentType.getString(context, SOURCE_ARGUMENT)))
                : suggestTargetFamilies(context, builder);
    }

    private static CompletableFuture<Suggestions> suggestMetals(
            SuggestionsBuilder builder, Optional<ErydonSwapOverlay> source) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (ErydonSwapOverlay metal : ErydonSwapOverlay.values()) {
            if (source.orElse(null) != metal && metal.key().startsWith(remaining)) {
                builder.suggest(metal.key());
            }
        }
        return builder.buildFuture();
    }

    private static int sendHelp(ServerCommandSource source) {
        source.sendFeedback(() -> Text.translatable("command.erydon.swap.help"), false);
        return 1;
    }

    private static int executeChunk(ServerCommandSource source, SwapPlan plan) throws CommandSyntaxException {
        ServerWorld world = source.getWorld();
        ChunkPos chunkPos = new ChunkPos(BlockPos.ofFloored(source.getPosition()));
        Box box = new Box(
                chunkPos.getStartX(),
                world.getBottomY(),
                chunkPos.getStartZ(),
                chunkPos.getEndX(),
                world.getTopY() - 1,
                chunkPos.getEndZ()
        );
        SwapOutcome outcome = swapInBox(world, box, plan);
        sendSummary(source, "chunk " + chunkPos.x + "," + chunkPos.z, plan, outcome);
        return outcome.replacedBlocks();
    }

    private static int executeRadius(ServerCommandSource source, SwapPlan plan, int radius)
            throws CommandSyntaxException {
        BlockPos origin = BlockPos.ofFloored(source.getPosition());
        Box box = new Box(
                origin.getX() - radius,
                origin.getY() - radius,
                origin.getZ() - radius,
                origin.getX() + radius,
                origin.getY() + radius,
                origin.getZ() + radius
        );
        SwapOutcome outcome = swapInBox(source.getWorld(), box, plan);
        sendSummary(source, "radius " + radius + " around " + formatPos(origin), plan, outcome);
        return outcome.replacedBlocks();
    }

    private static int executeBox(ServerCommandSource source, SwapPlan plan, BlockPos first, BlockPos second)
            throws CommandSyntaxException {
        Box box = Box.fromCorners(first, second);
        SwapOutcome outcome = swapInBox(source.getWorld(), box, plan);
        sendSummary(source, "box " + formatPos(box.minPos()) + " to " + formatPos(box.maxPos()), plan, outcome);
        return outcome.replacedBlocks();
    }

    private static int executeUndoLast(ServerCommandSource source) throws CommandSyntaxException {
        ServerWorld world = source.getWorld();
        LastSwapUndo undo = LAST_UNDO_BY_WORLD.get(world);
        if (undo == null || undo.entries().isEmpty()) {
            throw NO_SWAP_TO_UNDO.create();
        }

        UndoOutcome outcome = undoSwap(world, undo);
        LAST_UNDO_BY_WORLD.remove(world, undo);
        sendUndoSummary(source, outcome);
        return outcome.restoredBlocks();
    }

    static FamilyPair resolveFamilies(String rawFromFamily, String rawToFamily) throws CommandSyntaxException {
        ErydonSwapFamilyDatabase.FamilySpec fromFamily = resolveFamily(rawFromFamily);
        ErydonSwapFamilyDatabase.FamilySpec toFamily = resolveFamily(rawToFamily);

        if (toFamily.isAllBlocks()) {
            throw ALL_ERYDON_BLOCKS_TARGET.create();
        }
        if (fromFamily.canonicalKey().equals(toFamily.canonicalKey())) {
            throw SAME_FAMILY.create();
        }

        return new FamilyPair(fromFamily, toFamily);
    }

    private static ErydonSwapFamilyDatabase.FamilySpec resolveFamily(String rawFamily) throws CommandSyntaxException {
        String normalized = normalizeFamilyKey(rawFamily);
        if (normalized.isEmpty()) {
            throw INVALID_FAMILY.create(rawFamily);
        }

        return ErydonSwapFamilyDatabase.findFamily(normalized)
                .orElseThrow(() -> INVALID_FAMILY.create(rawFamily));
    }

    static String normalizeFamilyKey(String rawFamily) throws CommandSyntaxException {
        String normalized = rawFamily.toLowerCase(Locale.ROOT).trim();
        if (normalized.isEmpty()) {
            return normalized;
        }

        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }

        int namespaceSeparator = normalized.indexOf(':');
        if (namespaceSeparator >= 0) {
            String namespace = normalized.substring(0, namespaceSeparator);
            if (!ErydonSwapFamilyDatabase.isSupportedNamespace(namespace)) {
                throw INVALID_NAMESPACE.create(rawFamily);
            }
            normalized = normalized.substring(namespaceSeparator + 1);
        }

        normalized = normalized.replace('-', '_').replaceAll("\\s+", "_");
        while (normalized.contains("__")) {
            normalized = normalized.replace("__", "_");
        }

        while (normalized.startsWith("_")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("_")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        if (normalized.isEmpty() || !normalized.matches("[a-z0-9_]+")) {
            throw INVALID_FAMILY.create(rawFamily);
        }

        return normalized;
    }

    static int materialSwapFlags() {
        return MATERIAL_SWAP_FLAGS;
    }

    private static CompletableFuture<Suggestions> suggestTargetFamilies(
            CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) throws CommandSyntaxException {
        String sourceKey = normalizeFamilyKey(StringArgumentType.getString(context, SOURCE_ARGUMENT));
        return suggestFamilies(ErydonSwapFamilyDatabase.targetKeysForSource(sourceKey), builder);
    }

    private static CompletableFuture<Suggestions> suggestFamilies(
            Iterable<String> canonicalKeys, SuggestionsBuilder builder) {
        String remaining = normalizeSuggestionFragment(builder.getRemaining());
        for (String canonicalKey : canonicalKeys) {
            String displayKey = normalizeSuggestionFragment(ErydonSwapFamilyDatabase.displayName(canonicalKey));
            if (remaining.isEmpty() || canonicalKey.startsWith(remaining) || displayKey.startsWith(remaining)
                    || displayKey.replace("_honed", "_polished").startsWith(remaining)
                    || displayKey.replace("_polished", "_honed").startsWith(remaining)
                    || displayKey.replace("_polished", "_mirror").replace("_honed", "_mirror").startsWith(remaining)) {
                builder.suggest(ErydonSwapFamilyDatabase.commandSuggestion(canonicalKey));
            }
        }
        return builder.buildFuture();
    }

    private static String normalizeSuggestionFragment(String raw) {
        String normalized = raw.toLowerCase(Locale.ROOT).trim().replace("\"", "");
        int namespaceSeparator = normalized.indexOf(':');
        if (namespaceSeparator >= 0) {
            normalized = normalized.substring(namespaceSeparator + 1);
        }
        return normalized.replace('-', '_').replaceAll("\\s+", "_");
    }

    private static SwapOutcome swapInBox(ServerWorld world, Box requestedBox, SwapPlan plan)
            throws CommandSyntaxException {
        Box box = requestedBox.clampY(world);
        if (box.isEmpty()) {
            throw NO_MATCHING_BLOCKS.create(plan.fromLabel());
        }
        ensureVolumeWithinLimit(box.volume());

        List<Replacement> replacements = new ArrayList<>();
        Map<Identifier, Optional<Block>> counterpartCache = new HashMap<>();
        Map<Identifier, Optional<Identifier>> matchCache = new HashMap<>();
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        int matchingBlocks = 0;
        int missingCounterparts = 0;

        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    mutable.set(x, y, z);
                    BlockState sourceState = world.getBlockState(mutable);
                    Identifier sourceId = Registries.BLOCK.getId(sourceState.getBlock());
                    Optional<Identifier> match = matchCache.computeIfAbsent(sourceId, plan.targetId());
                    if (match.isEmpty()) {
                        continue;
                    }

                    matchingBlocks++;
                    Identifier targetId = match.get();
                    Block targetBlock = resolveTargetBlock(targetId, counterpartCache);
                    if (targetBlock == null) {
                        missingCounterparts++;
                        continue;
                    }

                    BlockState targetState = copySharedProperties(sourceState, targetBlock);
                    if (targetState.equals(sourceState)) {
                        continue;
                    }
                    ErydonSwapBlockEntitySupport.validateRefresh(targetState);
                    BlockPos pos = mutable.toImmutable();
                    NbtCompound previousNbt = createBlockEntityNbt(world, pos);
                    NbtCompound targetNbt = ErydonSwapBlockEntitySupport.swapComponents(previousNbt, componentId ->
                            plan.targetId().apply(componentId)
                                    .filter(id -> resolveTargetBlock(id, counterpartCache) != null)
                                    .orElse(componentId));
                    replacements.add(new Replacement(
                            pos,
                            targetState,
                            sourceState,
                            previousNbt,
                            targetNbt
                    ));
                }
            }
        }

        if (matchingBlocks == 0) {
            throw NO_MATCHING_BLOCKS.create(plan.fromLabel());
        }

        int replacedBlocks = 0;
        List<UndoEntry> undoEntries = new ArrayList<>();
        try (ClusterManualLockState.SwapPreservation ignored =
                     ClusterManualLockState.beginSwapPreservation(positionsOf(replacements))) {
            for (Replacement replacement : replacements) {
                if (world.setBlockState(replacement.pos(), replacement.state(), MATERIAL_SWAP_FLAGS)) {
                    try {
                        restoreBlockEntityNbt(world, replacement.pos(), replacement.targetBlockEntityNbt());
                    } finally {
                        // Keep undo available even if a companion callback fails after a world write.
                        undoEntries.add(new UndoEntry(
                                replacement.pos(),
                                replacement.previousState(),
                                replacement.previousBlockEntityNbt(),
                                replacement.state(),
                                createBlockEntityNbt(world, replacement.pos())
                        ));
                    }
                    replacedBlocks++;
                }
            }
        } finally {
            if (!undoEntries.isEmpty()) {
                LAST_UNDO_BY_WORLD.put(world, new LastSwapUndo(List.copyOf(undoEntries)));
            }
        }

        return new SwapOutcome(replacedBlocks, missingCounterparts);
    }

    private static void sendSummary(ServerCommandSource source, String scope, SwapPlan plan, SwapOutcome outcome) {
        StringBuilder message = new StringBuilder();
        message.append("Swapped ")
                .append(outcome.replacedBlocks())
                .append(" ERYDON-family block")
                .append(outcome.replacedBlocks() == 1 ? "" : "s")
                .append(" in ")
                .append(scope)
                .append(" (");
        Text prefix = Text.literal(message.toString()).append(plan.fromLabel()).append(" -> ").append(plan.toLabel());
        message.setLength(0);

        if (outcome.missingCounterparts() > 0) {
            message.append(", ")
                    .append(outcome.missingCounterparts())
                    .append(" matching block")
                    .append(outcome.missingCounterparts() == 1 ? "" : "s")
                    .append(" had no counterpart");
        }

        message.append(").");
        source.sendFeedback(() -> prefix.copy().append(message.toString()), false);
    }

    private static void sendUndoSummary(ServerCommandSource source, UndoOutcome outcome) {
        StringBuilder message = new StringBuilder();
        message.append("Undid last swap: restored ")
                .append(outcome.restoredBlocks())
                .append(" block")
                .append(outcome.restoredBlocks() == 1 ? "" : "s");

        if (outcome.restoredBlocks() != outcome.totalBlocks()) {
            message.append(" of ").append(outcome.totalBlocks());
        }

        message.append(".");
        source.sendFeedback(() -> Text.literal(message.toString()), false);
    }

    private static UndoOutcome undoSwap(ServerWorld world, LastSwapUndo undo) {
        int restoredBlocks = 0;
        try (ClusterManualLockState.SwapPreservation ignored =
                     ClusterManualLockState.beginSwapPreservation(undo.entries().stream()
                             .map(UndoEntry::pos)
                             .toList())) {
            for (UndoEntry entry : undo.entries()) {
                if (!world.getBlockState(entry.pos()).equals(entry.swappedState())
                        || !java.util.Objects.equals(createBlockEntityNbt(world, entry.pos()), entry.swappedBlockEntityNbt())) {
                    continue;
                }
                if (world.setBlockState(entry.pos(), entry.state(), MATERIAL_SWAP_FLAGS)) {
                    restoredBlocks++;
                }
                restoreBlockEntityNbt(world, entry.pos(), entry.blockEntityNbt());
            }
        }

        return new UndoOutcome(restoredBlocks, undo.entries().size());
    }

    private static void ensureVolumeWithinLimit(long volume) throws CommandSyntaxException {
        if (volume > MAX_BOX_VOLUME) {
            throw BOX_TOO_LARGE.create(volume, MAX_BOX_VOLUME);
        }
    }

    private static Block resolveTargetBlock(
            Identifier targetId, Map<Identifier, Optional<Block>> counterpartCache) {
        Optional<Block> cached = counterpartCache.get(targetId);
        if (cached != null) {
            return cached.orElse(null);
        }

        Optional<Block> resolved = Registries.BLOCK.containsId(targetId)
                ? Optional.of(Registries.BLOCK.get(targetId))
                : Optional.empty();
        counterpartCache.put(targetId, resolved);
        return resolved.orElse(null);
    }

    private static BlockState copySharedProperties(BlockState sourceState, Block targetBlock) {
        BlockState targetState = targetBlock.getDefaultState();
        for (Property<?> sourceProperty : sourceState.getProperties()) {
            Property<?> targetProperty = findProperty(targetState, sourceProperty.getName());
            if (targetProperty == null) {
                continue;
            }
            targetState = copyProperty(sourceState, targetState, sourceProperty, targetProperty);
        }
        return targetState;
    }

    private static Property<?> findProperty(BlockState state, String name) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) {
                return property;
            }
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState copyProperty(BlockState sourceState, BlockState targetState,
                                           Property sourceProperty, Property targetProperty) {
        Comparable value = sourceState.get(sourceProperty);
        Optional parsed = targetProperty.parse(sourceProperty.name(value));
        if (parsed.isEmpty()) {
            return targetState;
        }
        return targetState.with(targetProperty, (Comparable) parsed.get());
    }

    private static NbtCompound createBlockEntityNbt(ServerWorld world, BlockPos pos) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        return blockEntity == null ? null : blockEntity.createNbtWithId().copy();
    }

    private static void restoreBlockEntityNbt(ServerWorld world, BlockPos pos, NbtCompound nbt) {
        if (nbt == null) {
            return;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity == null || !nbt.getString("id").equals(
                String.valueOf(Registries.BLOCK_ENTITY_TYPE.getId(blockEntity.getType())))) {
            return;
        }

        blockEntity.readNbt(nbt.copy());
        blockEntity.markDirty();
        BlockState state = world.getBlockState(pos);
        ErydonSwapBlockEntitySupport.refreshAssembly(world, pos, state);
        world.updateListeners(pos, state, state, Block.NOTIFY_LISTENERS);
    }

    private static List<BlockPos> positionsOf(List<Replacement> replacements) {
        List<BlockPos> changedPositions = new ArrayList<>(replacements.size());
        for (Replacement replacement : replacements) {
            changedPositions.add(replacement.pos());
        }
        return changedPositions;
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    record FamilyPair(ErydonSwapFamilyDatabase.FamilySpec fromFamily,
                              ErydonSwapFamilyDatabase.FamilySpec toFamily) {
    }

    record SwapPlan(Text fromLabel, Text toLabel, Function<Identifier, Optional<Identifier>> targetId) {
    }

    private record Replacement(BlockPos pos, BlockState state,
                               BlockState previousState, NbtCompound previousBlockEntityNbt,
                               NbtCompound targetBlockEntityNbt) {
    }

    private record UndoEntry(BlockPos pos, BlockState state, NbtCompound blockEntityNbt,
                             BlockState swappedState, NbtCompound swappedBlockEntityNbt) {
    }

    private record LastSwapUndo(List<UndoEntry> entries) {
    }

    private record SwapOutcome(int replacedBlocks, int missingCounterparts) {
    }

    private record UndoOutcome(int restoredBlocks, int totalBlocks) {
    }

    private record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        private static Box fromCorners(BlockPos first, BlockPos second) {
            return new Box(
                    Math.min(first.getX(), second.getX()),
                    Math.min(first.getY(), second.getY()),
                    Math.min(first.getZ(), second.getZ()),
                    Math.max(first.getX(), second.getX()),
                    Math.max(first.getY(), second.getY()),
                    Math.max(first.getZ(), second.getZ())
            );
        }

        private Box clampY(ServerWorld world) {
            return new Box(
                    minX,
                    Math.max(minY, world.getBottomY()),
                    minZ,
                    maxX,
                    Math.min(maxY, world.getTopY() - 1),
                    maxZ
            );
        }

        private boolean isEmpty() {
            return minX > maxX || minY > maxY || minZ > maxZ;
        }

        private long volume() {
            if (isEmpty()) {
                return 0L;
            }
            long width = (long) maxX - minX + 1L;
            long height = (long) maxY - minY + 1L;
            long depth = (long) maxZ - minZ + 1L;
            try {
                return Math.multiplyExact(Math.multiplyExact(width, height), depth);
            } catch (ArithmeticException overflow) {
                return Long.MAX_VALUE;
            }
        }

        private BlockPos minPos() {
            return new BlockPos(minX, minY, minZ);
        }

        private BlockPos maxPos() {
            return new BlockPos(maxX, maxY, maxZ);
        }
    }
}
