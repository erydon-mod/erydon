package com.oliver.erydon.compat.worldedit;

import com.sk89q.worldedit.registry.state.Property;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/** Proves the optional hook on actual transformed WorldEdit states before opening a game window. */
public final class WorldEditCompactStateLaunchProbe implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        try {
            boolean enabled = Boolean.parseBoolean(System.getProperty(
                    "erydon.perf.worldedit_compact_transitions", "true"));
            int cases = 0;
            for (var fixture : WorldEditStateFixtures.representative()) {
                for (var state : fixture.states().values()) {
                    var expected = WorldEditStateFixtures.original(fixture.states(), state);
                    WorldEditStateFixtures.populate(state, fixture.states());
                    var actual = WorldEditStateFixtures.table(state);
                    if ((actual instanceof CompactWorldEditStateTable<?>) != enabled
                            || !expected.equals(actual)) {
                        throw new AssertionError("WorldEdit populate hook/transition mismatch");
                    }
                    for (Property<?> property : fixture.properties()) {
                        for (Object value : property.getValues()) {
                            Object target = expected.get(property, value);
                            if (state.with(cast(property), value) != (target == null ? state : target)) {
                                throw new AssertionError("WorldEdit with() changed canonical target");
                            }
                            cases++;
                        }
                    }
                }
                var sparse = WorldEditStateFixtures.sparse(fixture);
                // Choose a state unaffected by the removed final combination, so original populate
                // can verify fallback without logging a deliberate missing-state warning.
                var state = sparse.values().iterator().next();
                var expected = WorldEditStateFixtures.original(sparse, state);
                WorldEditStateFixtures.populate(state, sparse);
                var actual = WorldEditStateFixtures.table(state);
                if (actual instanceof CompactWorldEditStateTable<?> || !expected.equals(actual)) {
                    throw new AssertionError("Unsupported sparse layout did not use original populate");
                }
            }
            System.out.println("ERYDON_WORLDEDIT_COMPACT_OK: enabled=" + enabled + ", transitions=" + cases);
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    @SuppressWarnings("unchecked")
    private static Property<Object> cast(Property<?> property) { return (Property<Object>) property; }
}
