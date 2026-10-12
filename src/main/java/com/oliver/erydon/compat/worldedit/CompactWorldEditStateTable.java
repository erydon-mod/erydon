package com.oliver.erydon.compat.worldedit;

import com.google.common.collect.ForwardingTable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Table;
import com.sk89q.worldedit.registry.state.Property;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * WorldEdit's complete Cartesian state layouts need one shared lookup, rather
 * than a nested immutable map for every state's neighbouring transitions.
 * Explicit collection/view access still returns the original immutable Table
 * contract; the normal BlockState.with() path allocates nothing.
 */
public final class CompactWorldEditStateTable<S> extends ForwardingTable<Property<?>, Object, S> {
    private final Layout<S> layout;
    private final int stateIndex;

    private CompactWorldEditStateTable(Layout<S> layout, int stateIndex) {
        this.layout = layout;
        this.stateIndex = stateIndex;
    }

    /** One cache entry per canonical BlockType; neither layouts nor source maps are retained by the cache. */
    public static final class Factory<S> {
        private final Map<Object, CacheEntry<S>> layouts = new WeakHashMap<>();

        /** Returns null when the original populate algorithm must handle an unsupported layout. */
        public synchronized Table<Property<?>, Object, S> create(
                Object blockType, Map<Map<Property<?>, Object>, S> stateMap,
                Map<Property<?>, Object> currentValues) {
            if (blockType == null || !(stateMap instanceof ImmutableMap) || currentValues == null) {
                return null;
            }
            CacheEntry<S> cached = layouts.get(blockType);
            Layout<S> layout;
            if (cached != null && cached.source.get() == stateMap) {
                if (cached.unsupported) {
                    return null;
                }
                layout = cached.layout.get();
            } else {
                layout = null;
            }
            if (layout == null) {
                layout = Layout.create(stateMap, currentValues);
                layouts.put(blockType, new CacheEntry<>(stateMap, layout));
                if (layout == null) {
                    return null;
                }
            }
            int index = layout.indexOf(currentValues, true);
            if (index < 0) {
                return null;
            }
            return layout.transitionCount == 0 ? ImmutableTable.of()
                    : new CompactWorldEditStateTable<>(layout, index);
        }
    }

    private static final class CacheEntry<S> {
        private final WeakReference<Map<Map<Property<?>, Object>, S>> source;
        private final WeakReference<Layout<S>> layout;
        private final boolean unsupported;

        private CacheEntry(Map<Map<Property<?>, Object>, S> source, Layout<S> layout) {
            this.source = new WeakReference<>(source);
            this.layout = new WeakReference<>(layout);
            this.unsupported = layout == null;
        }
    }

    private static final class Layout<S> {
        private final Property<?>[] properties;
        private final Object[][] values;
        private final ImmutableMap<Property<?>, Integer> propertyIndices;
        private final ImmutableMap<Object, Integer>[] valueIndices;
        private final int[] strides;
        private final Object[] states;
        private final int transitionCount;

        private Layout(Property<?>[] properties, Object[][] values,
                       ImmutableMap<Property<?>, Integer> propertyIndices,
                       ImmutableMap<Object, Integer>[] valueIndices, int[] strides,
                       Object[] states, int transitionCount) {
            this.properties = properties;
            this.values = values;
            this.propertyIndices = propertyIndices;
            this.valueIndices = valueIndices;
            this.strides = strides;
            this.states = states;
            this.transitionCount = transitionCount;
        }

        @SuppressWarnings("unchecked")
        private static <S> Layout<S> create(Map<Map<Property<?>, Object>, S> source,
                                             Map<Property<?>, Object> currentValues) {
            if (source.isEmpty()) {
                return null;
            }
            Property<?>[] properties = currentValues.keySet().toArray(Property<?>[]::new);
            Object[][] values = new Object[properties.length][];
            ImmutableMap<Object, Integer>[] valueIndices = new ImmutableMap[properties.length];
            ImmutableMap.Builder<Property<?>, Integer> propertyIndices = ImmutableMap.builder();
            int[] strides = new int[properties.length];
            long count = 1;
            int transitionCount = 0;
            try {
                for (int p = 0; p < properties.length; p++) {
                    values[p] = properties[p].getValues().toArray();
                    if (values[p].length == 0) {
                        return null;
                    }
                    strides[p] = (int) count;
                    count *= values[p].length;
                    if (count > source.size()) {
                        return null;
                    }
                    propertyIndices.put(properties[p], p);
                    ImmutableMap.Builder<Object, Integer> indices = ImmutableMap.builder();
                    for (int v = 0; v < values[p].length; v++) {
                        indices.put(values[p][v], v);
                    }
                    valueIndices[p] = indices.build();
                    transitionCount = Math.addExact(transitionCount, values[p].length - 1);
                }
                if (count != source.size()) {
                    return null;
                }
                Layout<S> layout = new Layout<>(properties, values, propertyIndices.build(),
                        valueIndices, strides, new Object[(int) count], transitionCount);
                for (Map.Entry<Map<Property<?>, Object>, S> entry : source.entrySet()) {
                    int index = layout.indexOf(entry.getKey(), false);
                    if (index < 0 || entry.getValue() == null || layout.states[index] != null) {
                        return null;
                    }
                    layout.states[index] = entry.getValue();
                }
                return layout;
            } catch (IllegalArgumentException | NullPointerException | ArithmeticException unsupported) {
                return null;
            }
        }

        private int indexOf(Map<Property<?>, Object> stateValues, boolean requireRowOrder) {
            if (stateValues == null || stateValues.size() != properties.length) {
                return -1;
            }
            Iterator<Property<?>> keys = requireRowOrder ? stateValues.keySet().iterator() : null;
            int index = 0;
            for (int p = 0; p < properties.length; p++) {
                if (keys != null && keys.next() != properties[p]) {
                    return -1;
                }
                Object value = stateValues.get(properties[p]);
                Integer ordinal = valueIndices[p].get(value);
                // WorldEdit populate excludes the current value by identity, not equals.
                if (ordinal == null || values[p][ordinal] != value) {
                    return -1;
                }
                index += ordinal * strides[p];
            }
            return index;
        }
    }

    private int currentOrdinal(int property) {
        return stateIndex / layout.strides[property] % layout.values[property].length;
    }

    @SuppressWarnings("unchecked")
    private S transition(int property, int ordinal) {
        int current = currentOrdinal(property);
        return ordinal == current ? null
                : (S) layout.states[stateIndex + (ordinal - current) * layout.strides[property]];
    }

    @Override
    public S get(Object rowKey, Object columnKey) {
        Integer property = layout.propertyIndices.get(rowKey);
        if (property == null) {
            return null;
        }
        Integer ordinal = layout.valueIndices[property].get(columnKey);
        return ordinal == null ? null : transition(property, ordinal);
    }

    @Override public int size() { return layout.transitionCount; }
    @Override public boolean contains(Object row, Object column) { return get(row, column) != null; }
    @Override public boolean isEmpty() { return size() == 0; }

    @Override
    public boolean containsRow(Object rowKey) {
        Integer property = layout.propertyIndices.get(rowKey);
        return property != null && layout.values[property].length > 1;
    }

    @Override
    public boolean containsColumn(Object columnKey) {
        for (int p = 0; p < layout.properties.length; p++) {
            Integer ordinal = layout.valueIndices[p].get(columnKey);
            if (ordinal != null && ordinal != currentOrdinal(p)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean containsValue(Object value) {
        if (value == null) {
            return false;
        }
        for (int p = 0; p < layout.properties.length; p++) {
            for (int v = 0; v < layout.values[p].length; v++) {
                S state = transition(p, v);
                if (state != null && value.equals(state)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Rare API views are immutable snapshots and are never cached on a state. */
    @Override
    protected Table<Property<?>, Object, S> delegate() {
        ImmutableTable.Builder<Property<?>, Object, S> builder = ImmutableTable.builder();
        for (int p = 0; p < layout.properties.length; p++) {
            for (int v = 0; v < layout.values[p].length; v++) {
                S state = transition(p, v);
                if (state != null) {
                    builder.put(layout.properties[p], layout.values[p][v], state);
                }
            }
        }
        return builder.build();
    }

    @Override public S put(Property<?> row, Object column, S value) { throw new UnsupportedOperationException(); }
    @Override public void putAll(Table<? extends Property<?>, ?, ? extends S> table) { throw new UnsupportedOperationException(); }
    @Override public S remove(Object row, Object column) { throw new UnsupportedOperationException(); }
    @Override public void clear() { throw new UnsupportedOperationException(); }
}
