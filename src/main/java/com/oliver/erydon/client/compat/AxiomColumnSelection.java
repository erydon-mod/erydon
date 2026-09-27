package com.oliver.erydon.client.compat;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.block.DoubleCircularColumnBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;

/** Adds incomplete column sections to Axiom's selection without replacing its corners. */
public final class AxiomColumnSelection {
    private AxiomColumnSelection() {}

    /** Returns the unexpanded restore point only when extra cells were installed. */
    public static Object complete(Object selectionState, BlockView world) {
        Bridge bridge = Holder.BRIDGE;
        if (bridge == null) return null;
        try {
            Object original = bridge.getRestore.invoke(selectionState);
            if (original == null) return null;
            BlockPos from = (BlockPos) bridge.from.invoke(original);
            BlockPos to = (BlockPos) bridge.to.invoke(original);
            Object positions = bridge.set.invoke(original);
            Set<BlockPos> sparse = new HashSet<>();
            if (positions != null) {
                Object visitor = Proxy.newProxyInstance(bridge.visitor.getClassLoader(),
                        new Class<?>[]{bridge.visitor}, (proxy, method, args) -> {
                            if (method.getName().equals("accept")) {
                                sparse.add(new BlockPos((int) args[0], (int) args[1], (int) args[2]));
                                return null;
                            }
                            return switch (method.getName()) {
                                case "toString" -> "ERYDON column selection visitor";
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> throw new UnsupportedOperationException(method.getName());
                            };
                        });
                bridge.forEach.invoke(positions, visitor);
            }
            Set<BlockPos> extras = ColumnSelectionCompletion.extraCells(from, to, sparse,
                    pos -> DoubleCircularColumnBlock.selectionCells(world, pos));
            if (extras.isEmpty()) return null;

            // getSelectionRestore owns a copy; keep it intact for changing corners and undo.
            Object expanded = positions == null ? bridge.positions.newInstance() : bridge.copy.invoke(positions);
            for (BlockPos pos : extras) bridge.add.invoke(expanded, pos.getX(), pos.getY(), pos.getZ());
            bridge.restoreFrom.invoke(selectionState, bridge.restore.newInstance(from, to, expanded));
            return original;
        } catch (ReflectiveOperationException exception) {
            Erydon.LOGGER.warn("Unable to complete double-column sections in Axiom's selection", exception);
            return null;
        }
    }

    public static void restore(Object selectionState, Object original) {
        Bridge bridge = Holder.BRIDGE;
        if (bridge == null) return;
        try {
            bridge.restoreFrom.invoke(selectionState, original);
        } catch (ReflectiveOperationException exception) {
            Erydon.LOGGER.warn("Unable to restore Axiom's column selection corners", exception);
        }
    }

    public static Object copyRestore(Object original) {
        Bridge bridge = Holder.BRIDGE;
        if (bridge == null) return original;
        try {
            Object positions = bridge.set.invoke(original);
            return bridge.restore.newInstance(bridge.from.invoke(original), bridge.to.invoke(original),
                    positions == null ? null : bridge.copy.invoke(positions));
        } catch (ReflectiveOperationException exception) {
            Erydon.LOGGER.warn("Unable to copy Axiom's column selection restore point", exception);
            return original;
        }
    }

    private static final class Holder {
        private static final Bridge BRIDGE = resolve();

        private static Bridge resolve() {
            try {
                Class<?> positions = Class.forName("com.moulberry.axiom.collections.PositionSet");
                Class<?> restore = Class.forName("com.moulberry.axiom.buildertools.BuilderToolSelectionState$Restore");
                Class<?> selection = Class.forName("com.moulberry.axiom.buildertools.BuilderToolSelectionState");
                Class<?> visitor = Class.forName("com.moulberry.axiomclientapi.funcinterfaces.TriIntConsumer");
                return new Bridge(positions.getConstructor(),
                        positions.getMethod("add", int.class, int.class, int.class),
                        positions.getMethod("copy"), positions.getMethod("forEach", visitor), visitor,
                        restore.getConstructor(BlockPos.class, BlockPos.class, positions),
                        selection.getMethod("getSelectionRestore"), selection.getMethod("restoreFrom", restore),
                        restore.getMethod("pos1"), restore.getMethod("pos2"), restore.getMethod("set"));
            } catch (ReflectiveOperationException exception) {
                Erydon.LOGGER.warn("This Axiom version does not expose the supported builder selection API", exception);
                return null;
            }
        }
    }

    private record Bridge(Constructor<?> positions, Method add, Method copy, Method forEach, Class<?> visitor,
                          Constructor<?> restore, Method getRestore, Method restoreFrom,
                          Method from, Method to, Method set) {}
}
