package com.oliver.erydon.mixin;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.block.ClusterRebuildableBlock;
import com.oliver.erydon.block.LayerMultifaceBlock;
import com.oliver.erydon.util.BlockStateTagHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Inject(method = "placeFromNbt", at = @At("HEAD"), cancellable = true)
    private void erydon$preserveMultifacePlacement(BlockPos pos, World world, ItemStack stack,
                                                  BlockState state, CallbackInfoReturnable<BlockState> cir) {
        // Picked/Axiom palette stacks can carry faces and layers=1. Applying these
        // after placement would undo the surface/thickness selected by the click.
        if (state.getBlock() instanceof LayerMultifaceBlock) cir.setReturnValue(state);
    }

    @Inject(method = "postPlacement", at = @At("TAIL"))
    private void erydon$restoreCopiedBlockStateAfterPlacement(
            BlockPos pos,
            World world,
            @Nullable PlayerEntity player,
            ItemStack stack,
            BlockState state,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (world.isClient() || !cir.getReturnValueZ() || !BlockStateTagHelper.hasBlockStateTag(stack)) {
            return;
        }

        BlockState current = world.getBlockState(pos);
        // Multiface placement deliberately follows the clicked surface and supports stacking.
        if (current.getBlock() instanceof LayerMultifaceBlock) return;
        Identifier blockId = Registries.BLOCK.getId(current.getBlock());
        if (!Erydon.MOD_ID.equals(blockId.getNamespace())) {
            return;
        }

        BlockState restored = BlockStateTagHelper.applyBlockStateTag(current, stack);
        if (!restored.equals(current)) {
            world.setBlockState(pos, restored, Block.NOTIFY_ALL);
            current = restored;
        }

        if (current.getBlock() instanceof ClusterRebuildableBlock rebuildable) {
            rebuildable.recalcCluster(world, pos);
        }
    }
}
