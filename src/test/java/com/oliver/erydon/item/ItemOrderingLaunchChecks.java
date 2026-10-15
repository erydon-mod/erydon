package com.oliver.erydon.item;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

/** Exact pre-change order oracle; optionally benchmarks the complete registered inventory. */
public final class ItemOrderingLaunchChecks implements PreLaunchEntrypoint {
    private static volatile List<Item> benchmarkResult;

    @Override
    public void onPreLaunch() {
        try {
            SharedConstants.createGameVersion();
            Bootstrap.initialize();
            com.oliver.erydon.ModBlocks.registerModBlocks();
            run();
            com.oliver.erydon.compat.rei.ReiRegistrationLaunchChecks.run();
            benchmark();
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    public static void run() {
        long[] comparisons = {0};
        List<Item> expected = baseline(comparisons);
        List<Item> actual = ErydonItemOrdering.orderedBlockItems();
        require(!actual.isEmpty(), "Item-ordering probe has no registered ERYDON items");
        sameOrder(expected, actual);
        // orderedBlockItems remains an ordered stream with the same comparison relation.
        // Java's stable sorted operation preserves encounter order when keys compare equal.
        for (int index = 1; index < actual.size(); index++) {
            require(ErydonItemOrdering.sortKey(actual.get(index - 1))
                            .compareTo(ErydonItemOrdering.sortKey(actual.get(index))) <= 0,
                    "Item sort key order changed");
        }
        sameOrder(actual, ErydonItemOrdering.orderedBlockItems());
        System.out.println("ERYDON_ITEM_ORDERING_OK items=" + actual.size()
                + " baselineKeyEvaluations=" + comparisons[0] * 2
                + " optimizedKeyEvaluations=" + actual.size()
                + " exactIdentityOrder=true stableSort=true");
    }

    private static List<Item> baseline(long[] comparisons) {
        return StreamSupport.stream(Registries.BLOCK.spliterator(), false)
                .filter(block -> "erydon".equals(Registries.BLOCK.getId(block).getNamespace()))
                .map(Block::asItem)
                .filter(item -> item != Items.AIR)
                .sorted((left, right) -> {
                    if (comparisons != null) comparisons[0]++;
                    return ErydonItemOrdering.sortKey(left).compareTo(ErydonItemOrdering.sortKey(right));
                }).toList();
    }

    private static void benchmark() {
        Supplier<List<Item>> old = () -> baseline(null);
        Supplier<List<Item>> current = ErydonItemOrdering::orderedBlockItems;
        for (int warmup = 0; warmup < 2; warmup++) {
            sameOrder(old.get(), current.get());
        }
        long[] before = new long[3];
        long[] after = new long[3];
        for (int round = 0; round < before.length; round++) {
            if ((round & 1) == 0) {
                before[round] = timed(old);
                after[round] = timed(current);
            } else {
                after[round] = timed(current);
                before[round] = timed(old);
            }
        }
        System.out.printf(Locale.ROOT,
                "ERYDON_ITEM_ORDERING_BENCHMARK items=%d baselineMedianMs=%.3f optimizedMedianMs=%.3f"
                        + " baselineNanos=%s optimizedNanos=%s%n",
                benchmarkResult.size(), median(before) / 1_000_000.0, median(after) / 1_000_000.0,
                Arrays.toString(before), Arrays.toString(after));
    }

    private static long timed(Supplier<List<Item>> operation) {
        long start = System.nanoTime();
        List<Item> result = operation.get();
        long elapsed = System.nanoTime() - start;
        benchmarkResult = result;
        return elapsed;
    }

    private static long median(long[] samples) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static void sameOrder(List<Item> expected, List<Item> actual) {
        require(expected.size() == actual.size(), "Item ordering changed item count");
        for (int index = 0; index < expected.size(); index++) {
            require(expected.get(index) == actual.get(index), "Item identity/order changed at " + index);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
