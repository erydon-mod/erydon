package com.oliver.erydon.block;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Edit the actual part under the cursor, retaining the normal debug-stick controls. */
public final class CorniceAssemblyDebugControls {
    private CorniceAssemblyDebugControls() { }

    public static boolean use(PlayerEntity player, World world, BlockPos pos, boolean update, ItemStack stack) {
        if (!player.isCreativeLevelTwoOp()) return false;
        var contents = CorniceAssembly.contents(world, pos);
        if (contents.parts().isEmpty()) return false;
        var start = player.getEyePos();
        int index = CorniceAssembly.partIndexAt(world, pos, start, start.add(player.getRotationVec(1).multiply(8)));
        if (index < 0 || index >= contents.parts().size()) return false;
        BlockState part = contents.parts().get(index);
        List<Property<?>> properties = part.getProperties().stream()
                .filter(property -> !(part.getBlock() instanceof CoverBlock) || property != CoverBlock.EXT)
                .sorted(Comparator.comparing(Property::getName)).toList();
        if (properties.isEmpty()) return false;
        String id = Registries.BLOCK.getId(part.getBlock()).toString();
        var remembered = stack.getOrCreateSubNbt("DebugProperty");
        String selectedName = remembered.getString(id);
        int selectedIndex = 0;
        for (int i = 0; i < properties.size(); i++) {
            if (properties.get(i).getName().equals(selectedName)) selectedIndex = i;
        }
        if (!update) selectedIndex = Math.floorMod(selectedIndex + (player.shouldCancelInteraction() ? -1 : 1), properties.size());
        Property<?> selected = properties.get(selectedIndex);
        remembered.putString(id, selected.getName());
        BlockState displayed = part;
        if (update) {
            BlockState next = cycle(part, selected, player.shouldCancelInteraction());
            if (CorniceAssembly.replacePart(world, pos, index, next)) {
                CoverBlock.refreshSharedCover(world, pos);
                displayed = next;
            }
        }
        player.sendMessage(Text.translatable(update ? "item.minecraft.debug_stick.update" : "item.minecraft.debug_stick.select",
                selected.getName(), value(displayed, selected)), true);
        return true;
    }

    private static <T extends Comparable<T>> BlockState cycle(BlockState state, Property<T> property, boolean backwards) {
        List<T> values = new ArrayList<>(property.getValues());
        int index = values.indexOf(state.get(property));
        return state.with(property, values.get(Math.floorMod(index + (backwards ? -1 : 1), values.size())));
    }

    private static <T extends Comparable<T>> String value(BlockState state, Property<T> property) {
        return property.name(state.get(property));
    }
}
