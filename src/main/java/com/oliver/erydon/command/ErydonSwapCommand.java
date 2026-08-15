package com.oliver.erydon.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.oliver.erydon.Erydon;
import com.oliver.erydon.block.ClusterRebuildableBlock;
import com.oliver.erydon.state.ClusterManualLockState;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class ErydonSwapCommand {

    private static final int MAX_RADIUS = 32;
    private static final long MAX_BOX_VOLUME = 524_288L;
    private static final Map<RegistryKey<World>, LastSwapUndo> LAST_UNDO_BY_WORLD = new HashMap<>();

    private static final SimpleCommandExceptionType SAME_FAMILY =
            new SimpleCommandExceptionType(Text.literal("Source and target families must be different."));
    private static final SimpleCommandExceptionType MIXED_FAMILY_GROUP =
            new SimpleCommandExceptionType(Text.literal("Family groups can only be swapped to another family group, for example aganite_family -> borealis_family."));
    private static final SimpleCommandExceptionType ALL_ERYDON_BLOCKS_TARGET =
            new SimpleCommandExceptionType(Text.literal("all_erydon_blocks can only be used as the source family."));
    private static final SimpleCommandExceptionType NO_SWAP_TO_UNDO =
            new SimpleCommandExceptionType(Text.literal("There is no swap to undo in this world."));
    private static final DynamicCommandExceptionType NO_MATCHING_BLOCKS =
            new DynamicCommandExceptionType(family ->
                    Text.literal("No ERYDON blocks in family '" + family + "' were found in the selected area."));
    private static final DynamicCommandExceptionType INVALID_FAMILY =
            new DynamicCommandExceptionType(family ->
                    Text.literal("Invalid ERYDON family '" + family + "'. Use a canonical family such as aganite, aganite_family, aganite_aged, or borealis_rusticated."));
    private static final DynamicCommandExceptionType INVALID_NAMESPACE =
            new DynamicCommandExceptionType(family ->
                    Text.literal("Only ERYDON families are supported here: '" + family + "'."));
    private static final Dynamic2CommandExceptionType BOX_TOO_LARGE =
            new Dynamic2CommandExceptionType((actual, limit) ->
                    Text.literal("Selected volume is " + actual + " blocks, which exceeds the limit of " + limit + "."));

    private ErydonSwapCommand() {
    }

    public static LiteralArgumentBuilder<ServerCommandSource> createCommand() {
        return literal("swap")
                .then(literal("undolast")
                        .executes(ctx -> executeUndoLast(ctx.getSource())))
                .then(literal("chunk")
                        .then(argument("from_family", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(ErydonSwapFamilyDatabase.sourceKeys(), builder))
                                .then(argument("to_family", StringArgumentType.word())
                                        .suggests((context, builder) -> CommandSource.suggestMatching(targetSuggestions(context), builder))
                                        .executes(ctx -> executeChunk(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "from_family"),
                                                StringArgumentType.getString(ctx, "to_family"))))))
                .then(literal("radius")
                        .then(argument("from_family", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(ErydonSwapFamilyDatabase.sourceKeys(), builder))
                                .then(argument("to_family", StringArgumentType.word())
                                        .suggests((context, builder) -> CommandSource.suggestMatching(targetSuggestions(context), builder))
                                        .then(argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS))
                                                .executes(ctx -> executeRadius(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "from_family"),
                                                        StringArgumentType.getString(ctx, "to_family"),
                                                        IntegerArgumentType.getInteger(ctx, "radius")))))))
                .then(literal("box")
                        .then(argument("from_family", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(ErydonSwapFamilyDatabase.sourceKeys(), builder))
                                .then(argument("to_family", StringArgumentType.word())
                                        .suggests((context, builder) -> CommandSource.suggestMatching(targetSuggestions(context), builder))
                                        .then(argument("from", BlockPosArgumentType.blockPos())
                                                .then(argument("to", BlockPosArgumentType.blockPos())
                                                        .executes(ctx -> executeBox(
                                                                ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "from_family"),
                                                                StringArgumentType.getString(ctx, "to_family"),
                                                                BlockPosArgumentType.getBlockPos(ctx, "from"),
                                                                BlockPosArgumentType.getBlockPos(ctx, "to"))))))));
    }

    private static int executeChunk(ServerCommandSource source, String rawFromFamily, String rawToFamily) throws CommandSyntaxException {
        FamilyPair families = resolveFamilies(rawFromFamily, rawToFamily);
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
        SwapOutcome outcome = swapInBox(world, box, families.fromFamily(), families.toFamily());
        sendSummary(source, "chunk " + chunkPos.x + "," + chunkPos.z, families, outcome);
        return outcome.replacedBlocks();
    }

    private static int executeRadius(ServerCommandSource source, String rawFromFamily, String rawToFamily, int radius)
            throws CommandSyntaxException {
        FamilyPair families = resolveFamilies(rawFromFamily, rawToFamily);
        BlockPos origin = BlockPos.ofFloored(source.getPosition());
        Box box = new Box(
                origin.getX() - radius,
                origin.getY() - radius,
                origin.getZ() - radius,
                origin.getX() + radius,
                origin.getY() + radius,
                origin.getZ() + radius
        );
        SwapOutcome outcome = swapInBox(source.getWorld(), box, families.fromFamily(), families.toFamily());
        sendSummary(source, "radius " + radius + " around " + formatPos(origin), families, outcome);
        return outcome.replacedBlocks();
    }

    private static int executeBox(ServerCommandSource source, String rawFromFamily, String rawToFamily, BlockPos first, BlockPos second)
            throws CommandSyntaxException {
        FamilyPair families = resolveFamilies(rawFromFamily, rawToFamily);
        Box box = Box.fromCorners(first, second);
        SwapOutcome outcome = swapInBox(source.getWorld(), box, families.fromFamily(), families.toFamily());
        sendSummary(source, "box " + formatPos(box.minPos()) + " to " + formatPos(box.maxPos()), families, outcome);
        return outcome.replacedBlocks();
    }

    private static int executeUndoLast(ServerCommandSource source) throws CommandSyntaxException {
        ServerWorld world = source.getWorld();
        LastSwapUndo undo = LAST_UNDO_BY_WORLD.remove(world.getRegistryKey());
        if (undo == null || undo.entries().isEmpty()) {
            throw NO_SWAP_TO_UNDO.create();
        }

        UndoOutcome outcome = undoSwap(world, undo);
        sendUndoSummary(source, outcome);
        return outcome.restoredBlocks();
    }

    private static FamilyPair resolveFamilies(String rawFromFamily, String rawToFamily) throws CommandSyntaxException {
        ErydonSwapFamilyDatabase.FamilySpec fromFamily = resolveFamily(rawFromFamily);
        ErydonSwapFamilyDatabase.FamilySpec toFamily = resolveFamily(rawToFamily);

        if (toFamily.isAllErydonBlocks()) {
            throw ALL_ERYDON_BLOCKS_TARGET.create();
        }
        if (fromFamily.canonicalKey().equals(toFamily.canonicalKey())) {
            throw SAME_FAMILY.create();
        }
        if (!fromFamily.isAllErydonBlocks() && fromFamily.isMaterialGroup() != toFamily.isMaterialGroup()) {
            throw MIXED_FAMILY_GROUP.create();
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

    private static String normalizeFamilyKey(String rawFamily) throws CommandSyntaxException {
        String normalized = rawFamily.toLowerCase(Locale.ROOT).trim();
        if (normalized.isEmpty()) {
            return normalized;
        }

        int namespaceSeparator = normalized.indexOf(':');
        if (namespaceSeparator >= 0) {
            String namespace = normalized.substring(0, namespaceSeparator);
            if (!Erydon.MOD_ID.equals(namespace)) {
                throw INVALID_NAMESPACE.create(rawFamily);
            }
            normalized = normalized.substring(namespaceSeparator + 1);
        }

        while (normalized.startsWith("_")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("_")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        if (normalized.isEmpty() || normalized.contains(" ")) {
            throw INVALID_FAMILY.create(rawFamily);
        }

        return normalized;
    }

    private static Iterable<String> targetSuggestions(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        return ErydonSwapFamilyDatabase.targetKeysForSource(normalizeFamilyKey(StringArgumentType.getString(context, "from_family")));
    }

    private static SwapOutcome swapInBox(ServerWorld world, Box requestedBox,
                                         ErydonSwapFamilyDatabase.FamilySpec fromFamily,
                                         ErydonSwapFamilyDatabase.FamilySpec toFamily)
            throws CommandSyntaxException {
        Box box = requestedBox.clampY(world);
        if (box.isEmpty()) {
            throw NO_MATCHING_BLOCKS.create(fromFamily.canonicalKey());
        }
        ensureVolumeWithinLimit(box.volume());

        List<Replacement> replacements = new ArrayList<>();
        Set<BlockPos> clusterSeeds = new LinkedHashSet<>();
        Set<BlockPos> preservedManualLocks = new LinkedHashSet<>();
        Map<String, Optional<Block>> counterpartCache = new HashMap<>();
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        int matchingBlocks = 0;
        int missingCounterparts = 0;

        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    mutable.set(x, y, z);
                    BlockState sourceState = world.getBlockState(mutable);
                    Identifier sourceId = Registries.BLOCK.getId(sourceState.getBlock());
                    Optional<ErydonSwapFamilyDatabase.FamilyMatch> match = ErydonSwapFamilyDatabase.match(sourceId, fromFamily, toFamily);
                    if (match.isEmpty()) {
                        continue;
                    }

                    matchingBlocks++;
                    String targetPath = match.get().targetPath(toFamily);
                    Block targetBlock = resolveTargetBlock(targetPath, counterpartCache);
                    if (targetBlock == null) {
                        missingCounterparts++;
                        continue;
                    }

                    BlockState targetState = copySharedProperties(sourceState, targetBlock);
                    BlockPos pos = mutable.toImmutable();
                    replacements.add(new Replacement(
                            pos,
                            targetState,
                            sourceState,
                            createBlockEntityNbt(world, pos)
                    ));
                    if (hasManualLock(world, pos)) {
                        preservedManualLocks.add(pos);
                    }
                    if (sourceState.getBlock() instanceof ClusterRebuildableBlock || targetBlock instanceof ClusterRebuildableBlock) {
                        clusterSeeds.add(pos);
                    }
                }
            }
        }

        if (matchingBlocks == 0) {
            throw NO_MATCHING_BLOCKS.create(fromFamily.canonicalKey());
        }

        int replacedBlocks = 0;
        List<UndoEntry> undoEntries = new ArrayList<>();
        StabilizationOutcome stabilization;
        try (ClusterManualLockState.SwapPreservation ignored =
                     ClusterManualLockState.beginSwapPreservation(preservedManualLocks)) {
            for (Replacement replacement : replacements) {
                if (world.setBlockState(replacement.pos(), replacement.state(), Block.NOTIFY_ALL)) {
                    undoEntries.add(new UndoEntry(
                            replacement.pos(),
                            replacement.previousState(),
                            replacement.previousBlockEntityNbt()
                    ));
                    replacedBlocks++;
                }
            }

            stabilization = stabilize(world, replacements, clusterSeeds);
        }

        if (!undoEntries.isEmpty()) {
            LAST_UNDO_BY_WORLD.put(world.getRegistryKey(), new LastSwapUndo(List.copyOf(undoEntries)));
        }

        return new SwapOutcome(matchingBlocks, replacedBlocks, missingCounterparts,
                stabilization.recalculatedClusters(), stabilization.recalculatedBlocks(),
                stabilization.skippedClusters(), stabilization.skippedBlocks());
    }

    private static void sendSummary(ServerCommandSource source, String scope, FamilyPair families, SwapOutcome outcome) {
        StringBuilder message = new StringBuilder();
        message.append("Swapped ")
                .append(outcome.replacedBlocks())
                .append(" ERYDON block")
                .append(outcome.replacedBlocks() == 1 ? "" : "s")
                .append(" in ")
                .append(scope)
                .append(" (")
                .append(families.fromFamily().canonicalKey())
                .append(" -> ")
                .append(families.toFamily().canonicalKey());

        if (outcome.missingCounterparts() > 0) {
            message.append(", ")
                    .append(outcome.missingCounterparts())
                    .append(" matching block")
                    .append(outcome.missingCounterparts() == 1 ? "" : "s")
                    .append(" had no counterpart");
        }

        if (outcome.recalculatedClusters() > 0) {
            message.append(", rebuilt ")
                    .append(outcome.recalculatedClusters())
                    .append(" cluster")
                    .append(outcome.recalculatedClusters() == 1 ? "" : "s")
                    .append(" / ")
                    .append(outcome.recalculatedBlocks())
                    .append(" block")
                    .append(outcome.recalculatedBlocks() == 1 ? "" : "s");
        }

        if (outcome.skippedClusters() > 0) {
            message.append(", kept ")
                    .append(outcome.skippedClusters())
                    .append(" manual-locked cluster")
                    .append(outcome.skippedClusters() == 1 ? "" : "s")
                    .append(" / ")
                    .append(outcome.skippedBlocks())
                    .append(" block")
                    .append(outcome.skippedBlocks() == 1 ? "" : "s");
        }

        message.append(").");
        source.sendFeedback(() -> Text.literal(message.toString()), false);
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

        if (outcome.recalculatedClusters() > 0) {
            message.append(", rebuilt ")
                    .append(outcome.recalculatedClusters())
                    .append(" cluster")
                    .append(outcome.recalculatedClusters() == 1 ? "" : "s")
                    .append(" / ")
                    .append(outcome.recalculatedBlocks())
                    .append(" block")
                    .append(outcome.recalculatedBlocks() == 1 ? "" : "s");
        }

        if (outcome.skippedClusters() > 0) {
            message.append(", kept ")
                    .append(outcome.skippedClusters())
                    .append(" manual-locked cluster")
                    .append(outcome.skippedClusters() == 1 ? "" : "s")
                    .append(" / ")
                    .append(outcome.skippedBlocks())
                    .append(" block")
                    .append(outcome.skippedBlocks() == 1 ? "" : "s");
        }

        message.append(".");
        source.sendFeedback(() -> Text.literal(message.toString()), false);
    }

    private static UndoOutcome undoSwap(ServerWorld world, LastSwapUndo undo) {
        Set<BlockPos> clusterSeeds = new LinkedHashSet<>();
        Set<BlockPos> preservedManualLocks = new LinkedHashSet<>();

        for (UndoEntry entry : undo.entries()) {
            BlockState currentState = world.getBlockState(entry.pos());
            if (currentState.getBlock() instanceof ClusterRebuildableBlock
                    || entry.state().getBlock() instanceof ClusterRebuildableBlock) {
                clusterSeeds.add(entry.pos());
            }
            if (hasManualLock(world, entry.pos())) {
                preservedManualLocks.add(entry.pos());
            }
        }

        int restoredBlocks = 0;
        StabilizationOutcome stabilization;
        try (ClusterManualLockState.SwapPreservation ignored =
                     ClusterManualLockState.beginSwapPreservation(preservedManualLocks)) {
            for (UndoEntry entry : undo.entries()) {
                if (world.setBlockState(entry.pos(), entry.state(), Block.NOTIFY_ALL)) {
                    restoredBlocks++;
                }
                restoreBlockEntityNbt(world, entry.pos(), entry.blockEntityNbt());
            }

            stabilization = stabilizeUndo(world, undo.entries(), clusterSeeds);
        }

        return new UndoOutcome(
                restoredBlocks,
                undo.entries().size(),
                stabilization.recalculatedClusters(),
                stabilization.recalculatedBlocks(),
                stabilization.skippedClusters(),
                stabilization.skippedBlocks()
        );
    }

    private static void ensureVolumeWithinLimit(long volume) throws CommandSyntaxException {
        if (volume > MAX_BOX_VOLUME) {
            throw BOX_TOO_LARGE.create(volume, MAX_BOX_VOLUME);
        }
    }

    private static Block resolveTargetBlock(String targetPath, Map<String, Optional<Block>> counterpartCache) {
        Optional<Block> cached = counterpartCache.get(targetPath);
        if (cached != null) {
            return cached.orElse(null);
        }

        Identifier targetId = new Identifier(Erydon.MOD_ID, targetPath);
        Optional<Block> resolved = Registries.BLOCK.containsId(targetId)
                ? Optional.of(Registries.BLOCK.get(targetId))
                : Optional.empty();
        counterpartCache.put(targetPath, resolved);
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
        return blockEntity == null ? null : blockEntity.createNbt().copy();
    }

    private static void restoreBlockEntityNbt(ServerWorld world, BlockPos pos, NbtCompound nbt) {
        if (nbt == null) {
            return;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity == null) {
            return;
        }

        blockEntity.readNbt(nbt.copy());
        blockEntity.markDirty();
        BlockState state = world.getBlockState(pos);
        world.updateListeners(pos, state, state, Block.NOTIFY_LISTENERS);
    }

    private static StabilizationOutcome stabilize(ServerWorld world, List<Replacement> replacements, Set<BlockPos> clusterSeeds) {
        List<BlockPos> changedPositions = new ArrayList<>(replacements.size());
        for (Replacement replacement : replacements) {
            changedPositions.add(replacement.pos());
        }
        return stabilizePositions(world, changedPositions, clusterSeeds);
    }

    private static StabilizationOutcome stabilizeUndo(ServerWorld world, List<UndoEntry> undoEntries, Set<BlockPos> clusterSeeds) {
        List<BlockPos> changedPositions = new ArrayList<>(undoEntries.size());
        for (UndoEntry entry : undoEntries) {
            changedPositions.add(entry.pos());
        }
        return stabilizePositions(world, changedPositions, clusterSeeds);
    }

    private static StabilizationOutcome stabilizePositions(ServerWorld world, Iterable<BlockPos> changedPositions,
                                                           Set<BlockPos> clusterSeeds) {
        for (BlockPos pos : changedPositions) {
            BlockState currentState = world.getBlockState(pos);
            world.updateNeighborsAlways(pos, currentState.getBlock());
        }

        int recalculatedClusters = 0;
        int recalculatedBlocks = 0;
        int skippedClusters = 0;
        int skippedBlocks = 0;
        Set<BlockPos> processed = new HashSet<>();

        for (BlockPos seed : clusterSeeds) {
            if (processed.contains(seed)) {
                continue;
            }

            BlockState currentState = world.getBlockState(seed);
            if (!(currentState.getBlock() instanceof ClusterRebuildableBlock rebuildable)) {
                continue;
            }

            ClusterRebuildableBlock.ClusterRecalcResult result = rebuildable.recalcCluster(world, seed);
            if (result.positions().isEmpty()) {
                continue;
            }

            processed.addAll(result.positions());
            if (result.recalculated()) {
                recalculatedClusters++;
                recalculatedBlocks += result.positions().size();
            } else {
                skippedClusters++;
                skippedBlocks += result.positions().size();
            }
        }

        return new StabilizationOutcome(recalculatedClusters, recalculatedBlocks, skippedClusters, skippedBlocks);
    }

    private static boolean hasManualLock(ServerWorld world, BlockPos pos) {
        return ClusterManualLockState.isLocked(world, ClusterManualLockState.COLUMN_SCOPE, pos)
                || ClusterManualLockState.isLocked(world, ClusterManualLockState.SURROUND_SCOPE, pos)
                || ClusterManualLockState.isLocked(world, ClusterManualLockState.WINDOW_ARCH_SCOPE, pos)
                || ClusterManualLockState.isLocked(world, ClusterManualLockState.WINDOW_FRENCH_GEORGIAN_SCOPE, pos);
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private record FamilyPair(ErydonSwapFamilyDatabase.FamilySpec fromFamily,
                              ErydonSwapFamilyDatabase.FamilySpec toFamily) {
    }

    private record Replacement(BlockPos pos, BlockState state,
                               BlockState previousState, NbtCompound previousBlockEntityNbt) {
    }

    private record UndoEntry(BlockPos pos, BlockState state, NbtCompound blockEntityNbt) {
    }

    private record LastSwapUndo(List<UndoEntry> entries) {
    }

    private record StabilizationOutcome(int recalculatedClusters, int recalculatedBlocks,
                                        int skippedClusters, int skippedBlocks) {
    }

    private record SwapOutcome(int matchingBlocks, int replacedBlocks, int missingCounterparts,
                               int recalculatedClusters, int recalculatedBlocks,
                               int skippedClusters, int skippedBlocks) {
    }

    private record UndoOutcome(int restoredBlocks, int totalBlocks,
                               int recalculatedClusters, int recalculatedBlocks,
                               int skippedClusters, int skippedBlocks) {
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
            return width * height * depth;
        }

        private BlockPos minPos() {
            return new BlockPos(minX, minY, minZ);
        }

        private BlockPos maxPos() {
            return new BlockPos(maxX, maxY, maxZ);
        }
    }
}
