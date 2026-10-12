package com.oliver.erydon.compat.worldedit;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Table;
import com.sk89q.worldedit.registry.state.BooleanProperty;
import com.sk89q.worldedit.registry.state.EnumProperty;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.world.block.BlockState;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class CompactWorldEditStateTableTest {
    private static volatile Object allocationSink;

    @Test
    void everyTransitionMatchesOriginalAndWithPreservesCanonicalIdentity() throws Exception {
        for (var fixture : WorldEditStateFixtures.representative()) {
            var factory = new CompactWorldEditStateTable.Factory<BlockState>();
            for (BlockState state : fixture.states().values()) {
                var expected = WorldEditStateFixtures.original(fixture.states(), state);
                var actual = factory.create(fixture.type(), fixture.states(), state.getStates());
                assertInstanceOf(CompactWorldEditStateTable.class, actual);
                assertEquals(expected.size(), actual.size());
                WorldEditStateFixtures.attach(state, actual);
                for (Property<?> property : fixture.properties()) {
                    // A distinct property with the same name must use WorldEdit's name-based equality.
                    Property<?> equivalent = new EnumProperty(property.getName(), List.of("unrelated"));
                    for (Object value : property.getValues()) {
                        assertSame(expected.get(property, value), actual.get(property, value));
                        assertSame(expected.get(property, value), actual.get(equivalent, value));
                        Object target = expected.get(property, value);
                        assertSame(target == null ? state : target, state.with(cast(property), value));
                        if (value instanceof String text) {
                            assertSame(expected.get(property, new String(text)), actual.get(property, new String(text)));
                        }
                    }
                    assertNull(actual.get(property, new Object()));
                    assertNull(actual.get(property, null));
                }
                assertNull(actual.get(null, null));
                Property<?> absent = new BooleanProperty("absent", List.of(false, true));
                assertNull(actual.get(absent, true));
                assertSame(state, state.with(cast(absent), true));
            }
        }
    }

    @Test
    void immutableTableViewsOrderingEqualityAndMissingValuesMatch() throws Exception {
        for (var fixture : WorldEditStateFixtures.representative()) {
            var factory = new CompactWorldEditStateTable.Factory<BlockState>();
            var state = fixture.states().values().iterator().next();
            var expected = WorldEditStateFixtures.original(fixture.states(), state);
            var actual = factory.create(fixture.type(), fixture.states(), state.getStates());
            assertEquals(expected, actual);
            assertEquals(actual, expected);
            assertEquals(expected.hashCode(), actual.hashCode());
            assertEquals(expected.toString(), actual.toString());
            assertEquals(expected.rowMap(), actual.rowMap());
            assertEquals(expected.columnMap(), actual.columnMap());
            assertEquals(new ArrayList<>(expected.rowKeySet()), new ArrayList<>(actual.rowKeySet()));
            assertEquals(new ArrayList<>(expected.columnKeySet()), new ArrayList<>(actual.columnKeySet()));
            assertEquals(new ArrayList<>(expected.cellSet()), new ArrayList<>(actual.cellSet()));
            assertEquals(new ArrayList<>(expected.values()), new ArrayList<>(actual.values()));
            for (Property<?> property : fixture.properties()) {
                assertEquals(expected.row(property), actual.row(property));
                assertEquals(expected.containsRow(property), actual.containsRow(property));
                for (Object value : property.getValues()) {
                    assertEquals(expected.column(value), actual.column(value));
                    assertEquals(expected.containsColumn(value), actual.containsColumn(value));
                    assertEquals(expected.contains(property, value), actual.contains(property, value));
                }
            }
            for (BlockState value : fixture.states().values()) {
                assertEquals(expected.containsValue(value), actual.containsValue(value));
            }
            assertFalse(actual.containsRow(null));
            assertFalse(actual.containsColumn(null));
            assertFalse(actual.containsValue(null));
            assertFalse(actual.containsRow(new BooleanProperty("absent", List.of(false, true))));
            assertFalse(actual.containsColumn(new Object()));
            assertTrue(actual.row(new BooleanProperty("absent", List.of(false, true))).isEmpty());
            assertTrue(actual.column(new Object()).isEmpty());
            assertThrows(NullPointerException.class, () -> actual.row(null));
            assertThrows(NullPointerException.class, () -> actual.column(null));
            Property<?> row = actual.rowKeySet().iterator().next();
            Object column = actual.columnKeySet().iterator().next();
            assertThrows(UnsupportedOperationException.class, actual::clear);
            assertThrows(UnsupportedOperationException.class, () -> actual.put(row, column, state));
            assertThrows(UnsupportedOperationException.class, () -> actual.remove(null, null));
            assertThrows(UnsupportedOperationException.class, () -> actual.putAll(ImmutableTable.of()));
            assertThrows(UnsupportedOperationException.class, () -> actual.row(row).put(column, state));
            assertThrows(UnsupportedOperationException.class, () -> actual.column(column).put(row, state));
            assertThrows(UnsupportedOperationException.class, () -> actual.rowMap().get(row).clear());
            assertThrows(UnsupportedOperationException.class, () -> actual.columnMap().get(column).clear());
            assertThrows(UnsupportedOperationException.class, () -> actual.rowMap().clear());
            assertThrows(UnsupportedOperationException.class, () -> actual.columnMap().clear());
            assertThrows(UnsupportedOperationException.class, () -> actual.cellSet().clear());
            assertThrows(UnsupportedOperationException.class, () -> actual.values().clear());
            assertThrows(UnsupportedOperationException.class, () -> actual.rowKeySet().clear());
            assertThrows(UnsupportedOperationException.class, () -> actual.columnKeySet().clear());
            var iterator = actual.cellSet().iterator();
            iterator.next();
            assertThrows(UnsupportedOperationException.class, iterator::remove);
        }
    }

    @Test
    void unsupportedLayoutsFallBackAndTrivialLayoutsStayEmpty() throws Exception {
        var fixture = WorldEditStateFixtures.representative().get(0);
        var state = fixture.states().values().iterator().next();
        var factory = new CompactWorldEditStateTable.Factory<BlockState>();
        assertNull(factory.create(fixture.type(), new LinkedHashMap<>(fixture.states()), state.getStates()));
        assertNull(factory.create(fixture.type(), WorldEditStateFixtures.sparse(fixture), state.getStates()));
        assertNull(factory.create(fixture.type(), ImmutableMap.of(), state.getStates()));
        var wrongOrder = new LinkedHashMap<Property<?>, Object>();
        var reversed = new ArrayList<>(state.getStates().entrySet());
        java.util.Collections.reverse(reversed);
        reversed.forEach(entry -> wrongOrder.put(entry.getKey(), entry.getValue()));
        assertNotNull(factory.create(fixture.type(), fixture.states(), state.getStates()));
        assertNull(factory.create(fixture.type(), fixture.states(), wrongOrder));
        Property<?> property = fixture.properties().get(0);
        WorldEditStateFixtures.setState(state, property, new String((String) state.getState(cast(property))));
        assertSame(state, WorldEditStateFixtures.original(fixture.states(), state)
                .get(property, property.getValues().get(0)));
        assertNull(factory.create(fixture.type(), fixture.states(), state.getStates()));
        for (var trivial : List.of(
                WorldEditStateFixtures.create("erydon:empty_probe", List.of()),
                WorldEditStateFixtures.create("erydon:single_probe", List.of(new EnumProperty("part", List.of("only")))))) {
            BlockState only = trivial.states().values().iterator().next();
            var table = factory.create(trivial.type(), trivial.states(), only.getStates());
            assertEquals(ImmutableTable.of(), table);
            assertTrue(table.isEmpty());
            assertEquals(0, table.size());
        }
    }

    @Test
    void oneLayoutIsSharedAndReplacedSourceDoesNotReuseOldCanonicalStates() throws Exception {
        var fixture = WorldEditStateFixtures.representative().get(1);
        var factory = new CompactWorldEditStateTable.Factory<BlockState>();
        Field layout = CompactWorldEditStateTable.class.getDeclaredField("layout");
        layout.setAccessible(true);
        Object first = null;
        for (var state : fixture.states().values()) {
            var table = factory.create(fixture.type(), fixture.states(), state.getStates());
            if (first == null) first = layout.get(table);
            assertSame(first, layout.get(table));
        }
        var replacement = WorldEditStateFixtures.create(fixture.type().getId(), fixture.properties());
        var state = replacement.states().values().iterator().next();
        var table = factory.create(fixture.type(), replacement.states(), state.getStates());
        assertNotSame(first, layout.get(table));
        assertEquals(WorldEditStateFixtures.original(replacement.states(), state), table);
    }

    @Test
    void boundedConstructionAllocationComparison() throws Exception {
        var management = ManagementFactory.getThreadMXBean();
        assumeTrue(management instanceof ThreadMXBean);
        var bean = (ThreadMXBean) management;
        assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        for (var fixture : WorldEditStateFixtures.representative()) {
            for (int warmup = 0; warmup < 12; warmup++) {
                construct(fixture, false);
                construct(fixture, true);
            }
            long original = allocated(bean, fixture, false);
            long compact = allocated(bean, fixture, true);
            System.out.printf("ERYDON_WORLDEDIT_TABLE_ALLOCATION: %s, states=%d, original=%d bytes, compact=%d bytes%n",
                    fixture.type().getId(), fixture.states().size(), original, compact);
            assertTrue(compact < original, "Compact full-layout construction must allocate less than original populate");
        }
    }

    private static long allocated(ThreadMXBean bean, WorldEditStateFixtures.Fixture fixture, boolean compact) {
        long id = Thread.currentThread().getId();
        long before = bean.getThreadAllocatedBytes(id);
        for (int repeat = 0; repeat < 4; repeat++) construct(fixture, compact);
        return (bean.getThreadAllocatedBytes(id) - before) / 4;
    }

    private static void construct(WorldEditStateFixtures.Fixture fixture, boolean compact) {
        var factory = new CompactWorldEditStateTable.Factory<BlockState>();
        var tables = new ArrayList<Table<Property<?>, Object, BlockState>>(fixture.states().size());
        for (BlockState state : fixture.states().values()) {
            tables.add(compact ? factory.create(fixture.type(), fixture.states(), state.getStates())
                    : WorldEditStateFixtures.original(fixture.states(), state));
        }
        allocationSink = tables;
    }

    @SuppressWarnings("unchecked")
    private static Property<Object> cast(Property<?> property) { return (Property<Object>) property; }
}
