package com.oliver.erydon.compat.worldedit;

import com.sk89q.jnbt.CompoundTag;
import com.sk89q.jnbt.StringTag;
import com.sk89q.worldedit.extent.transform.BlockTransformExtent;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.registry.state.BooleanProperty;
import com.sk89q.worldedit.registry.state.DirectionalProperty;
import com.sk89q.worldedit.registry.state.EnumProperty;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.util.concurrency.LazyReference;
import com.sk89q.worldedit.world.block.BlockType;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Actual optional transform hook, including the NBT-bearing clipboard representation. */
public final class CorniceAssemblyWorldEditLaunchChecks {
    private CorniceAssemblyWorldEditLaunchChecks() { }

    public static void run() throws ReflectiveOperationException {
        DirectionalProperty facing = new DirectionalProperty("facing", List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST));
        BooleanProperty mirrored = new BooleanProperty("mirrored", List.of(false, true));
        BooleanProperty waterlogged = new BooleanProperty("waterlogged", List.of(false, true));
        EnumProperty light = new EnumProperty("light", List.of("off", "low", "bright"));
        var fixture = WorldEditStateFixtures.create("erydon:cornice_assembly", List.of(facing, mirrored, waterlogged, light));
        Map<String, Property<?>> properties = new LinkedHashMap<>();
        for (Property<?> property : fixture.properties()) properties.put(property.getName(), property);
        Field propertyCache = BlockType.class.getDeclaredField("properties");
        propertyCache.setAccessible(true);
        propertyCache.set(fixture.type(), LazyReference.from(() -> Map.copyOf(properties)));
        for (var state : fixture.states().values()) WorldEditStateFixtures.populate(state, fixture.states());
        int cases = 0;
        for (var state : fixture.states().values()) for (int quarter = 0; quarter < 4; quarter++) {
            for (boolean reflect : new boolean[]{false, true}) {
                AffineTransform transform = new AffineTransform().translate(17, 3, -9).rotateY(quarter * 90)
                        .scale(reflect ? -1 : 1, 1, 1);
                var transformed = BlockTransformExtent.transform(state, transform);
                Vector3 direction = transform.apply(state.getState(facing).toVector()).subtract(transform.apply(Vector3.ZERO));
                Direction expected = Direction.findClosest(direction, Direction.Flag.CARDINAL);
                if (transformed.getState(facing) != expected
                        || transformed.getState(mirrored) != (state.getState(mirrored) ^ reflect)
                        || transformed.getState(waterlogged) != state.getState(waterlogged)
                        || !transformed.getState(light).equals(state.getState(light))) {
                    throw new AssertionError("Assembly WorldEdit transform failed: " + state + ", quarter=" + quarter + ", reflect=" + reflect);
                }
                CompoundTag payload = new CompoundTag(Map.of("id", new StringTag("erydon:cornice_assembly"),
                        "Payload", new StringTag("cornices-and-cover-retained")));
                var full = BlockTransformExtent.transform(state.toBaseBlock(payload), transform);
                if (!full.toImmutableState().equals(transformed) || full.getNbtData() != payload) {
                    throw new AssertionError("Assembly clipboard transform discarded its component NBT");
                }
                cases++;
            }
        }
        System.out.println("ERYDON_CORNICE_ASSEMBLY_WORLDEDIT_OK: states=" + fixture.states().size() + ", transforms=" + cases);
    }
}
