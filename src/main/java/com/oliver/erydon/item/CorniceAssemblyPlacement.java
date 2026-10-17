package com.oliver.erydon.item;

import com.oliver.erydon.block.CorniceAssembly;
import com.oliver.erydon.block.CorniceAssemblyBlock;
import com.oliver.erydon.block.CorniceBlock;
import com.oliver.erydon.block.CoverBlock;
import net.minecraft.advancement.criterion.Criteria;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.state.property.Property;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.event.GameEvent;

/** Vanilla placement side effects for adding a paid part to an occupied cell. */
final class CorniceAssemblyPlacement {
    private CorniceAssemblyPlacement() { }

    static boolean sharedHost(BlockState state) {
        return state.getBlock() instanceof CorniceBlock || state.getBlock() instanceof CoverBlock
                || state.getBlock() instanceof CorniceAssemblyBlock;
    }

    static BlockState stackState(BlockState state, ItemStack stack) {
        var tag = stack.getSubNbt("BlockStateTag");
        if (tag == null || state == null) return state;
        for (String name : tag.getKeys()) {
            Property<?> property = state.getBlock().getStateManager().getProperty(name);
            if (property != null) state = propertyValue(state, property, tag.getString(name));
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState propertyValue(BlockState state, Property<T> property, String value) {
        return property.parse(value).map(parsed -> state.with(property, parsed)).orElse(state);
    }

    static ActionResult add(ItemPlacementContext context, BlockState proposed) {
        var world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        var player = context.getPlayer();
        BlockState part = stackState(proposed, context.getStack());
        if (part == null || context.getStack().isEmpty() || world.isOutOfHeightLimit(pos)
                || !world.getWorldBorder().contains(pos)
                || player != null && (!world.canPlayerModifyAt(player, pos)
                || !player.canPlaceOn(pos, context.getSide(), context.getStack()))
                || !part.canPlaceAt(world, pos) || !CorniceAssembly.canAdd(world, pos, part)
                || !world.canPlace(part, pos, player == null ? ShapeContext.absent() : ShapeContext.of(player))) {
            return ActionResult.FAIL;
        }
        if (world.isClient) return ActionResult.SUCCESS;
        if (!CorniceAssembly.add(world, pos, part)) return ActionResult.FAIL;
        if (player instanceof ServerPlayerEntity serverPlayer) {
            Criteria.PLACED_BLOCK.trigger(serverPlayer, pos, context.getStack());
        }
        var sound = part.getSoundGroup();
        world.playSound(null, pos, sound.getPlaceSound(), SoundCategory.BLOCKS,
                (sound.getVolume() + 1) / 2, sound.getPitch() * .8F);
        world.emitGameEvent(GameEvent.BLOCK_PLACE, pos, GameEvent.Emitter.of(player, part));
        if (player == null || !player.getAbilities().creativeMode) context.getStack().decrement(1);
        return ActionResult.CONSUME;
    }
}
