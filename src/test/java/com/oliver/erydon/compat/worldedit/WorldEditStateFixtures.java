package com.oliver.erydon.compat.worldedit;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Table;
import com.sk89q.worldedit.registry.state.BooleanProperty;
import com.sk89q.worldedit.registry.state.EnumProperty;
import com.sk89q.worldedit.registry.state.IntegerProperty;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Real WorldEdit states, without querying its platform or generating Minecraft registries. */
public final class WorldEditStateFixtures {
    private static final Constructor<BlockState> CONSTRUCTOR;
    private static final Method SET_STATE;
    private static final Method POPULATE;
    private static final Field STATES;

    static {
        try {
            CONSTRUCTOR = BlockState.class.getDeclaredConstructor(BlockType.class);
            SET_STATE = BlockState.class.getDeclaredMethod("setState", Property.class, Object.class);
            POPULATE = BlockState.class.getDeclaredMethod("populate", Map.class);
            STATES = BlockState.class.getDeclaredField("states");
            CONSTRUCTOR.setAccessible(true);
            SET_STATE.setAccessible(true);
            POPULATE.setAccessible(true);
            STATES.setAccessible(true);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    public record Fixture(BlockType type, List<Property<?>> properties,
                          ImmutableMap<Map<Property<?>, Object>, BlockState> states) { }

    public static List<Fixture> representative() throws ReflectiveOperationException {
        return List.of(
                create("minecraft:compact_probe_stairs", List.of(
                        new EnumProperty("facing", List.of("north", "south", "west", "east")),
                        new EnumProperty("half", List.of("bottom", "top")),
                        new EnumProperty("shape", List.of("straight", "inner_left", "inner_right", "outer_left", "outer_right")),
                        new BooleanProperty("waterlogged", List.of(false, true)))),
                create("erydon:compact_probe_arch", List.of(
                        new EnumProperty("style", List.of("roman", "gothic", "modern")),
                        new IntegerProperty("part", List.of(0, 1, 2, 3)),
                        new EnumProperty("facing", List.of("north", "south", "west", "east")),
                        new BooleanProperty("waterlogged", List.of(false, true)))));
    }

    public static Fixture create(String id, List<Property<?>> properties) throws ReflectiveOperationException {
        BlockType type = new BlockType(id);
        ImmutableMap.Builder<Map<Property<?>, Object>, BlockState> states = ImmutableMap.builder();
        addStates(type, properties, 0, new LinkedHashMap<>(), states);
        return new Fixture(type, properties, states.build());
    }

    private static void addStates(BlockType type, List<Property<?>> properties, int property,
                                  Map<Property<?>, Object> values,
                                  ImmutableMap.Builder<Map<Property<?>, Object>, BlockState> states)
            throws ReflectiveOperationException {
        if (property == properties.size()) {
            BlockState state = CONSTRUCTOR.newInstance(type);
            for (var entry : values.entrySet()) {
                SET_STATE.invoke(state, entry.getKey(), entry.getValue());
            }
            Map<Property<?>, Object> sorted = new TreeMap<>(Comparator.comparing(Property::getName));
            sorted.putAll(values);
            states.put(ImmutableMap.copyOf(sorted), state);
            return;
        }
        Property<?> key = properties.get(property);
        for (Object value : key.getValues()) {
            values.put(key, value);
            addStates(type, properties, property + 1, values, states);
        }
    }

    /** The original 7.2.15 populate algorithm, including its identity-based current-value exclusion. */
    public static Table<Property<?>, Object, BlockState> original(
            Map<Map<Property<?>, Object>, BlockState> states, BlockState current) {
        ImmutableTable.Builder<Property<?>, Object, BlockState> result = ImmutableTable.builder();
        Map<Property<?>, Object> currentValues = current.getStates();
        for (var entry : currentValues.entrySet()) {
            for (Object value : entry.getKey().getValues()) {
                if (value != entry.getValue()) {
                    ImmutableMap.Builder<Property<?>, Object> changed = ImmutableMap.builder();
                    for (var existing : currentValues.entrySet()) {
                        changed.put(existing.getKey(), existing.getKey().equals(entry.getKey())
                                ? value : existing.getValue());
                    }
                    BlockState target = states.get(changed.build());
                    if (target != null) {
                        result.put(entry.getKey(), value, target);
                    }
                }
            }
        }
        return result.build();
    }

    public static void populate(BlockState state, Map<Map<Property<?>, Object>, BlockState> states)
            throws ReflectiveOperationException {
        POPULATE.invoke(state, states);
    }

    @SuppressWarnings("unchecked")
    public static Table<Property<?>, Object, BlockState> table(BlockState state)
            throws ReflectiveOperationException {
        return (Table<Property<?>, Object, BlockState>) STATES.get(state);
    }

    public static void attach(BlockState state, Table<Property<?>, Object, BlockState> table)
            throws ReflectiveOperationException {
        STATES.set(state, table);
    }

    public static void setState(BlockState state, Property<?> property, Object value)
            throws ReflectiveOperationException {
        SET_STATE.invoke(state, property, value);
    }

    public static Map<Map<Property<?>, Object>, BlockState> sparse(Fixture fixture) {
        var entries = new ArrayList<>(fixture.states.entrySet());
        ImmutableMap.Builder<Map<Property<?>, Object>, BlockState> result = ImmutableMap.builder();
        for (int i = 0; i < entries.size() - 1; i++) {
            result.put(entries.get(i));
        }
        return result.build();
    }
}
