package com.oliver.erydon.mixin.client;

import com.oliver.erydon.block.CorniceAssemblyBlock;
import com.oliver.erydon.block.CorniceAssembly;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The authoritative part update keeps the other local pieces visible while the server processes mining. */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class CorniceAssemblyClientBreakMixin {
    @Inject(method = "breakBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/block/Block;onBreak(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/entity/player/PlayerEntity;)V"), cancellable = true)
    private void erydon$keepUnbrokenParts(BlockPos pos, CallbackInfoReturnable<Boolean> callback) {
        var client = MinecraftClient.getInstance();
        var world = client.world;
        if (world == null || client.player == null || !(world.getBlockState(pos).getBlock() instanceof CorniceAssemblyBlock)) return;
        var start = client.player.getEyePos();
        int index = CorniceAssembly.partIndexAt(world, pos, start, start.add(client.player.getRotationVec(1).multiply(6)));
        if (index < 0) { callback.setReturnValue(false); return; }
        var part = CorniceAssembly.contents(world, pos).parts().get(index);
        if (!client.player.getMainHandStack().getItem().canMine(part, world, pos, client.player)) {
            callback.setReturnValue(false);
            return;
        }
        // Vanilla onBreak supplies this player's one local sound/debris event;
        // the server sends the matching event only to the other players.
        part.getBlock().onBreak(world, pos, part, client.player);
        callback.setReturnValue(true);
    }
}
