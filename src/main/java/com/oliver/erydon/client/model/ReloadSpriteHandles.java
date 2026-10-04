/*
 * MIT License
 * Copyright (c) 2026
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.oliver.erydon.client.model;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** One lazy sprite table per immutable resource-reload snapshot. */
final class ReloadSpriteHandles<K, V> {
    private final IdentityHashMap<K, Slot<V>> byRule;

    private ReloadSpriteHandles(IdentityHashMap<K, Slot<V>> byRule) {
        this.byRule = new IdentityHashMap<>(byRule);
    }

    V get(K rule) {
        Slot<V> slot = byRule.get(rule);
        if (slot == null) {
            throw new IllegalArgumentException("Rule does not belong to this sprite snapshot");
        }
        return slot.get();
    }

    static final class Builder<K, V> {
        private final IdentityHashMap<K, Slot<V>> byRule = new IdentityHashMap<>();
        private final Map<Object, Slot<V>> byTiles = new HashMap<>();

        Builder<K, V> bind(K rule, Object canonicalTiles, Supplier<? extends V> loader) {
            Objects.requireNonNull(rule);
            Objects.requireNonNull(canonicalTiles);
            Objects.requireNonNull(loader);
            if (byRule.containsKey(rule)) {
                throw new IllegalArgumentException("Duplicate rule identity");
            }
            Slot<V> slot = byTiles.computeIfAbsent(canonicalTiles, ignored -> new Slot<>(loader));
            byRule.put(rule, slot);
            return this;
        }

        ReloadSpriteHandles<K, V> build() {
            return new ReloadSpriteHandles<>(byRule);
        }
    }

    private static final class Slot<V> {
        private final Supplier<? extends V> loader;
        private volatile V value;

        private Slot(Supplier<? extends V> loader) {
            this.loader = loader;
        }

        V get() {
            V result = value;
            if (result != null) {
                return result;
            }
            synchronized (this) {
                result = value;
                if (result == null) {
                    result = Objects.requireNonNull(loader.get(), "Sprite resolver returned null");
                    value = result;
                }
                return result;
            }
        }
    }
}
