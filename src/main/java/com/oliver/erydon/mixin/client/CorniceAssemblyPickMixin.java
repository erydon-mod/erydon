package com.oliver.erydon.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.oliver.erydon.block.CorniceAssembly;
import com.oliver.erydon.block.CorniceAssemblyBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MinecraftClient.class)
public abstract class CorniceAssemblyPickMixin {
    @WrapOperation(method = "doItemPick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/block/Block;getPickStack(Lnet/minecraft/world/BlockView;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)Lnet/minecraft/item/ItemStack;"))
    private ItemStack erydon$pickOriginalPart(Block block, BlockView world, BlockPos pos, BlockState state,
                                              Operation<ItemStack> original) {
        if (!(block instanceof CorniceAssemblyBlock)) return original.call(block, world, pos, state);
        MinecraftClient client = (MinecraftClient) (Object) this;
        if (client.player == null) return original.call(block, world, pos, state);
        Vec3d start = client.player.getEyePos();
        int index = CorniceAssembly.partIndexAt(world, pos, start, start.add(client.player.getRotationVec(1).multiply(6)));
        return index < 0 ? ItemStack.EMPTY : CorniceAssembly.stackFor(CorniceAssembly.contents(world, pos).parts().get(index));
    }

    @WrapOperation(method = "doItemPick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/world/ClientWorld;getBlockEntity(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/entity/BlockEntity;"))
    private BlockEntity erydon$avoidCarrierNbtOnPartItem(ClientWorld world, BlockPos pos, Operation<BlockEntity> original) {
        return world.getBlockState(pos).getBlock() instanceof CorniceAssemblyBlock ? null : original.call(world, pos);
    }
}
