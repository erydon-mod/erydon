package com.oliver.erydon.client.model;

import net.minecraft.util.Identifier;

import java.util.AbstractList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;

/** Reload-local canonical pool for ordered CTM tile identifier sequences. */
final class SynapheiaTileSequencePool {
    private final Map<List<Identifier>, List<Identifier>> sequences = new HashMap<>();

    List<Identifier> intern(List<Identifier> input) {
        List<Identifier> immutable = List.copyOf(input);
        List<Identifier> existing = sequences.get(immutable);
        if (existing != null) {
            return existing;
        }
        List<Identifier> canonical = new InternedSequence(immutable);
        sequences.put(canonical, canonical);
        return canonical;
    }

    int size() {
        return sequences.size();
    }

    static List<Identifier> stableCacheKey(List<Identifier> input) {
        return input instanceof InternedSequence ? input : List.copyOf(input);
    }

    /** Immutable list with the ordered-content hash calculated once at reload. */
    private static final class InternedSequence extends AbstractList<Identifier>
            implements RandomAccess {
        private final List<Identifier> values;
        private final int hashCode;

        private InternedSequence(List<Identifier> values) {
            this.values = values;
            this.hashCode = values.hashCode();
        }

        @Override
        public Identifier get(int index) {
            return values.get(index);
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
