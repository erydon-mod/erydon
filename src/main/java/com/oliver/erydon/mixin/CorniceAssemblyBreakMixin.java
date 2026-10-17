package com.oliver.erydon.mixin;

import com.oliver.erydon.block.CorniceAssembly;
import com.oliver.erydon.block.CorniceAssemblyBlock;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Remove one aimed-at paid component only after vanilla and Fabric's cancellable BEFORE gates. */
@Mixin(ServerPlayerInteractionManager.class)
public abstract class CorniceAssemblyBreakMixin {
    @Shadow protected ServerWorld world;
    @Shadow @Final protected ServerPlayerEntity player;

    @Inject(method = "tryBreakBlock", at = @At("HEAD"), cancellable = true)
    private void erydon$checkAssemblyPermission(BlockPos pos, CallbackInfoReturnable<Boolean> callback) {
        if (world.getBlockState(pos).getBlock() instanceof CorniceAssemblyBlock && !world.canPlayerModifyAt(player, pos)) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "tryBreakBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/world/ServerWorld;removeBlock(Lnet/minecraft/util/math/BlockPos;Z)Z"), cancellable = true)
    private void erydon$breakAssemblyPart(BlockPos pos, CallbackInfoReturnable<Boolean> callback) {
        if (!(world.getBlockState(pos).getBlock() instanceof CorniceAssemblyBlock)) return;
        if (!world.canPlayerModifyAt(player, pos)) { callback.setReturnValue(false); return; }
        Vec3d start = player.getEyePos();
        Vec3d end = start.add(player.getRotationVec(1).multiply(6));
        int index = CorniceAssembly.partIndexAt(world, pos, start, end);
        if (index < 0) { callback.setReturnValue(false); return; }
        BlockState part = CorniceAssembly.contents(world, pos).parts().get(index);
        ItemStack tool = player.getMainHandStack();
        if (!tool.getItem().canMine(part, world, pos, player)) { callback.setReturnValue(false); return; }
        ItemStack originalTool = tool.copy();
        boolean harvest = player.canHarvest(part);
        if (CorniceAssembly.removePart(world, pos, index) == null) { callback.setReturnValue(false); return; }
        // Pinned Fabric 0.6.2 invokes AFTER immediately before vanilla's
        // onBroken call. That original call is skipped by this partial removal.
        PlayerBlockBreakEvents.AFTER.invoker().afterBlockBreak(world, player, pos, part, null);
        part.getBlock().onBroken(world, pos, part);
        if (!player.isCreative()) {
            tool.postMine(world, part, pos, player);
            if (harvest) part.getBlock().afterBreak(world, player, pos, part, null, originalTool);
        }
        callback.setReturnValue(true);
    }
}
