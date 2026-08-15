package com.oliver.erydon.state;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Stores per-position manual cluster data outside blockstate schema so
 * non-visual state does not multiply baked model states.
 */
public final class ClusterManualLockState extends PersistentState {

    public static final String COLUMN_SCOPE = "column";
    public static final String ROMANESQUE_ARCH_SCOPE = "romanesque_arch";
    public static final String SURROUND_SCOPE = "surround";
    public static final String WINDOW_ARCH_SCOPE = "window_arch";
    public static final String WINDOW_FRENCH_GEORGIAN_SCOPE = "window_french_georgian";
    public static final String ALCOVE_SCOPE = "alcove";
    public static final String GEORGIAN_WALL_SCOPE = "georgian_wall";

    private static final String DATA_KEY = "erydon_cluster_manual_locks";
    private static final String LOCKS_TAG = "locks";
    private static final String INT_VALUES_TAG = "int_values";
    private static final String VALUE_POSITIONS_TAG = "positions";
    private static final String VALUE_VALUES_TAG = "values";
    private static final ThreadLocal<Map<Long, Integer>> PRESERVED_SWAP_POSITIONS =
            ThreadLocal.withInitial(HashMap::new);

    private final Map<String, Set<Long>> lockedPositionsByScope = new HashMap<>();
    private final Map<String, Map<Long, Integer>> intValuesByScope = new HashMap<>();

    public static ClusterManualLockState get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(
                ClusterManualLockState::fromNbt,
                ClusterManualLockState::new,
                DATA_KEY
        );
    }

    public static boolean isLocked(WorldAccess world, String scope, BlockPos pos) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return false;
        }
        return get(serverWorld).isLocked(scope, pos);
    }

    public static boolean setLocked(World world, String scope, BlockPos pos, boolean locked) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return locked;
        }

        ClusterManualLockState state = get(serverWorld);
        if (state.setLockedInternal(scope, pos, locked)) {
            state.markDirty();
        }
        return state.isLocked(scope, pos);
    }

    public static boolean toggleLocked(World world, String scope, BlockPos pos) {
        return setLocked(world, scope, pos, !isLocked(world, scope, pos));
    }

    public static void clear(WorldAccess world, String scope, BlockPos pos) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }

        ClusterManualLockState state = get(serverWorld);
        if (state.setLockedInternal(scope, pos, false)) {
            state.markDirty();
        }
    }

    public static int getInt(WorldAccess world, String scope, BlockPos pos) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return 0;
        }
        return get(serverWorld).getIntInternal(scope, pos);
    }

    public static int setInt(World world, String scope, BlockPos pos, int value) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return value;
        }

        ClusterManualLockState state = get(serverWorld);
        if (state.setIntInternal(scope, pos, value)) {
            state.markDirty();
        }
        return state.getIntInternal(scope, pos);
    }

    public static void clearInt(WorldAccess world, String scope, BlockPos pos) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }

        ClusterManualLockState state = get(serverWorld);
        if (state.setIntInternal(scope, pos, 0)) {
            state.markDirty();
        }
    }

    public static SwapPreservation beginSwapPreservation(Iterable<BlockPos> positions) {
        Map<Long, Integer> preserved = PRESERVED_SWAP_POSITIONS.get();
        List<Long> encodedPositions = new java.util.ArrayList<>();

        for (BlockPos pos : positions) {
            long encodedPos = pos.asLong();
            preserved.merge(encodedPos, 1, Integer::sum);
            encodedPositions.add(encodedPos);
        }

        return new SwapPreservation(encodedPositions);
    }

    public static boolean isPreservedForSwap(BlockPos pos) {
        return PRESERVED_SWAP_POSITIONS.get().containsKey(pos.asLong());
    }

    public static ClusterManualLockState fromNbt(NbtCompound nbt) {
        ClusterManualLockState state = new ClusterManualLockState();
        NbtCompound locks = nbt.getCompound(LOCKS_TAG);

        for (String scope : locks.getKeys()) {
            long[] encodedPositions = locks.getLongArray(scope);
            if (encodedPositions.length == 0) {
                continue;
            }

            Set<Long> positions = new HashSet<>(encodedPositions.length);
            for (long encodedPos : encodedPositions) {
                positions.add(encodedPos);
            }
            state.lockedPositionsByScope.put(scope, positions);
        }

        NbtCompound intValues = nbt.getCompound(INT_VALUES_TAG);
        for (String scope : intValues.getKeys()) {
            NbtCompound scopedValues = intValues.getCompound(scope);
            long[] encodedPositions = scopedValues.getLongArray(VALUE_POSITIONS_TAG);
            int[] values = scopedValues.getIntArray(VALUE_VALUES_TAG);
            int count = Math.min(encodedPositions.length, values.length);
            if (count == 0) {
                continue;
            }

            Map<Long, Integer> scoped = new HashMap<>(count);
            for (int i = 0; i < count; i++) {
                if (values[i] != 0) {
                    scoped.put(encodedPositions[i], values[i]);
                }
            }
            if (!scoped.isEmpty()) {
                state.intValuesByScope.put(scope, scoped);
            }
        }

        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtCompound locks = new NbtCompound();

        for (Map.Entry<String, Set<Long>> entry : new TreeMap<>(lockedPositionsByScope).entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }

            long[] encodedPositions = entry.getValue().stream()
                    .mapToLong(Long::longValue)
                    .sorted()
                    .toArray();
            locks.putLongArray(entry.getKey(), encodedPositions);
        }

        nbt.put(LOCKS_TAG, locks);

        NbtCompound intValues = new NbtCompound();
        for (Map.Entry<String, Map<Long, Integer>> entry : new TreeMap<>(intValuesByScope).entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }

            TreeMap<Long, Integer> sortedValues = new TreeMap<>(entry.getValue());
            long[] encodedPositions = new long[sortedValues.size()];
            int[] values = new int[sortedValues.size()];
            int index = 0;
            for (Map.Entry<Long, Integer> valueEntry : sortedValues.entrySet()) {
                encodedPositions[index] = valueEntry.getKey();
                values[index] = valueEntry.getValue();
                index++;
            }

            NbtCompound scopedValues = new NbtCompound();
            scopedValues.putLongArray(VALUE_POSITIONS_TAG, encodedPositions);
            scopedValues.putIntArray(VALUE_VALUES_TAG, values);
            intValues.put(entry.getKey(), scopedValues);
        }

        nbt.put(INT_VALUES_TAG, intValues);
        return nbt;
    }

    private boolean isLocked(String scope, BlockPos pos) {
        Set<Long> positions = lockedPositionsByScope.get(scope);
        return positions != null && positions.contains(pos.asLong());
    }

    private int getIntInternal(String scope, BlockPos pos) {
        Map<Long, Integer> values = intValuesByScope.get(scope);
        if (values == null) {
            return 0;
        }
        return values.getOrDefault(pos.asLong(), 0);
    }

    private boolean setLockedInternal(String scope, BlockPos pos, boolean locked) {
        Set<Long> positions = lockedPositionsByScope.computeIfAbsent(scope, ignored -> new HashSet<>());
        boolean changed;

        if (locked) {
            changed = positions.add(pos.asLong());
        } else {
            changed = positions.remove(pos.asLong());
            if (positions.isEmpty()) {
                lockedPositionsByScope.remove(scope);
            }
        }

        return changed;
    }

    private boolean setIntInternal(String scope, BlockPos pos, int value) {
        long encodedPos = pos.asLong();

        if (value == 0) {
            Map<Long, Integer> values = intValuesByScope.get(scope);
            if (values == null) {
                return false;
            }

            boolean changed = values.remove(encodedPos) != null;
            if (values.isEmpty()) {
                intValuesByScope.remove(scope);
            }
            return changed;
        }

        Map<Long, Integer> values = intValuesByScope.computeIfAbsent(scope, ignored -> new HashMap<>());
        Integer previous = values.put(encodedPos, value);
        return previous == null || previous != value;
    }

    public static final class SwapPreservation implements AutoCloseable {
        private final List<Long> encodedPositions;
        private boolean closed;

        private SwapPreservation(List<Long> encodedPositions) {
            this.encodedPositions = encodedPositions;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;

            Map<Long, Integer> preserved = PRESERVED_SWAP_POSITIONS.get();
            for (long encodedPos : encodedPositions) {
                Integer count = preserved.get(encodedPos);
                if (count == null) {
                    continue;
                }
                if (count <= 1) {
                    preserved.remove(encodedPos);
                } else {
                    preserved.put(encodedPos, count - 1);
                }
            }
            if (preserved.isEmpty()) {
                PRESERVED_SWAP_POSITIONS.remove();
            }
        }
    }
}
