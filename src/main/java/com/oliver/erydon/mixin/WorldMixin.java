package com.oliver.erydon.mixin;

import com.oliver.erydon.block.DiagonalWallBlock;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public abstract class WorldMixin {
    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
            at = @At("RETURN"))
    private void erydon$refreshGeorgianWallDiagonalConnections(BlockPos pos,
                                                               BlockState state,
                                                               int flags,
                                                               int maxUpdateDepth,
                                                               CallbackInfoReturnable<Boolean> cir) {
        World world = (World) (Object) this;
        if (cir.getReturnValueZ()) {
            DiagonalWallBlock.refreshAroundChangedBlock(world, pos);
        }
    }
}
