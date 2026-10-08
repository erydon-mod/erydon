package com.oliver.erydon.state;

import com.google.common.collect.ForwardingTable;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Table;
import net.minecraft.state.property.Property;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shares canonical destinations instead of allocating a dense matrix for every arch state. */
public final class ArchStateTransitions<S> extends ForwardingTable<Property<?>, Comparable<?>, S> {
    private static final ThreadLocal<Factory<?>> FACTORY = new ThreadLocal<>();
    private final Index<S> index;
    private final int state;

    private ArchStateTransitions(Index<S> index, int state) {
        this.index = index;
        this.state = state;
    }

    @SuppressWarnings("unchecked")
    public static <S> Table<Property<?>, Comparable<?>, S> create(
            Map<Map<Property<?>, Comparable<?>>, S> states, Map<Property<?>, Comparable<?>> entries) {
        Factory<S> factory = (Factory<S>) FACTORY.get();
        if (factory == null || factory.source.get() != states) {
            factory = new Factory<>(new WeakReference<>(states), new Index<>(states, entries));
            FACTORY.set(factory);
        }
        return new ArchStateTransitions<>(factory.index, factory.index.encode(entries));
    }

    @Override public S get(Object row, Object column) {
        for (int i = 0; i < index.properties.size(); i++) {
            if (!index.properties.get(i).equals(row)) continue;
            int next = index.values.get(i).indexOf(column);
            if (next < 0) return null;
            int current = state / index.strides[i] % index.values.get(i).size();
            return next == current ? null : index.destination(state + (next-current) * index.strides[i]);
        }
        return null;
    }

    // Vanilla only calls get(). Preserve the complete read-only Table contract for integrations.
    @Override protected Table<Property<?>, Comparable<?>, S> delegate() {
        ImmutableTable.Builder<Property<?>, Comparable<?>, S> builder = ImmutableTable.builder();
        for (int i = 0; i < index.properties.size(); i++) {
            Property<?> property = index.properties.get(i);
            for (Comparable<?> value : index.values.get(i)) {
                S target = get(property, value);
                if (target != null) builder.put(property, value, target);
            }
        }
        return builder.build();
    }

    private record Factory<S>(WeakReference<Map<Map<Property<?>, Comparable<?>>, S>> source, Index<S> index) { }

    private static final class Index<S> {
        final List<Property<?>> properties;
        final List<List<? extends Comparable<?>>> values = new ArrayList<>();
        final int[] strides;
        final Object[] states;

        Index(Map<Map<Property<?>, Comparable<?>>, S> source, Map<Property<?>, Comparable<?>> sample) {
            properties = List.copyOf(sample.keySet());
            strides = new int[properties.size()];
            int size = 1;
            for (int i = 0; i < properties.size(); i++) {
                strides[i] = size;
                List<? extends Comparable<?>> choices = List.copyOf(properties.get(i).getValues());
                values.add(choices);
                size = Math.multiplyExact(size, choices.size());
            }
            states = new Object[size];
            source.forEach((entries, target) -> states[encode(entries)] = target);
        }

        int encode(Map<Property<?>, Comparable<?>> entries) {
            int result = 0;
            for (int i = 0; i < properties.size(); i++) {
                int value = values.get(i).indexOf(entries.get(properties.get(i)));
                if (value < 0) throw new IllegalArgumentException("Invalid arch state");
                result += value * strides[i];
            }
            return result;
        }

        @SuppressWarnings("unchecked") S destination(int encoded) { return (S) states[encoded]; }
    }
}
