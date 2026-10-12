package com.oliver.erydon.mixin.client;

import com.oliver.erydon.block.CorniceBlock;
import com.oliver.erydon.client.CorniceClientRedraw;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla skips its render listener when a received owner state already matches the chunk. */
@Mixin(ClientWorld.class)
public abstract class CorniceRedrawMixin {
    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void erydon$redrawLiveCornice(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        if (!(state.getBlock() instanceof CorniceBlock)) return;
        ClientWorld world = (ClientWorld) (Object) this;
        CorniceClientRedraw.afterPacket(world, pos, state,
                () -> world.updateListeners(pos, state, state, Block.NOTIFY_LISTENERS));
    }
}
