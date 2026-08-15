package com.oliver.erydon.mixin.compat.worldedit;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableTable;
import com.oliver.erydon.Erydon;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.util.Identifier;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

@Pseudo
@Mixin(value = BlockState.class, remap = false)
public abstract class WorldEditBlockStateMixin {
    private static final int ERYDON_MAX_FULL_WORLD_EDIT_STATES =
            Integer.getInteger("erydon.worldedit.max_full_states", 128);
    private static final AtomicInteger ERYDON_SLIMMED_BLOCK_TYPES = new AtomicInteger();

    @Inject(method = "generateStateMap", at = @At("HEAD"), cancellable = true, require = 0)
    private static void erydon$useSlimStateMapForLargeErydonBlocks(
            BlockType blockType,
            CallbackInfoReturnable<Map<Map<Property<?>, Object>, BlockState>> cir
    ) {
        if (!blockType.getId().startsWith(Erydon.MOD_ID + ":")) {
            return;
        }

        long stateCount = stateCount(blockType);
        if (stateCount <= ERYDON_MAX_FULL_WORLD_EDIT_STATES) {
            return;
        }

        Map<Property<?>, Object> defaultValues = defaultValues(blockType);
        BlockState defaultState = WorldEditBlockStateAccessor.erydon$create(blockType);
        WorldEditBlockStateAccessor accessor = (WorldEditBlockStateAccessor) (Object) defaultState;
        for (Map.Entry<Property<?>, Object> entry : defaultValues.entrySet()) {
            accessor.erydon$setState(entry.getKey(), entry.getValue());
        }
        accessor.erydon$setStates(ImmutableTable.of());

        int slimmed = ERYDON_SLIMMED_BLOCK_TYPES.incrementAndGet();
        if (slimmed <= 8 || slimmed % 250 == 0) {
            Erydon.LOGGER.info(
                    "[{}] Using slim WorldEdit state map for {} ({} states).",
                    Erydon.MOD_ID,
                    blockType.getId(),
                    stateCount
            );
        }

        cir.setReturnValue(ImmutableMap.of(ImmutableMap.copyOf(defaultValues), defaultState));
    }

    private static long stateCount(BlockType blockType) {
        long count = 1L;
        for (Property<?> property : blockType.getProperties()) {
            count *= Math.max(1, property.getValues().size());
            if (count > ERYDON_MAX_FULL_WORLD_EDIT_STATES) {
                return count;
            }
        }
        return count;
    }

    private static Map<Property<?>, Object> defaultValues(BlockType blockType) {
        Map<Property<?>, Object> values = new TreeMap<>(Comparator.comparing(Property::getName));
        Block block = Registries.BLOCK.get(new Identifier(blockType.getId()));
        net.minecraft.block.BlockState defaultState = block.getDefaultState();

        for (Map.Entry<net.minecraft.state.property.Property<?>, Comparable<?>> entry : defaultState.getEntries().entrySet()) {
            Property<?> property = blockType.getProperty(entry.getKey().getName());
            values.put(property, worldEditValue(entry.getKey(), entry.getValue()));
        }
        return values;
    }

    private static Object worldEditValue(net.minecraft.state.property.Property<?> property, Comparable<?> value) {
        if (property instanceof DirectionProperty) {
            return FabricAdapter.adaptEnumFacing((Direction) value);
        }
        if (property instanceof EnumProperty<?> && value instanceof StringIdentifiable named) {
            return named.asString();
        }
        return value;
    }
}
