package com.oliver.erydon.mixin;

import com.oliver.erydon.block.CopingRaycast;
import com.oliver.erydon.block.CorniceRaycast;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.block.BlockState;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockView.class)
public interface CopingRaycastMixin {
    @ModifyReturnValue(method="raycastBlock",at=@At("RETURN"))
    default BlockHitResult erydon$targetCoping(BlockHitResult original,Vec3d start,Vec3d end,BlockPos pos,VoxelShape shape,BlockState state) {
        BlockHitResult cap = CopingRaycast.closest((BlockView)this,start,end,pos,state,original);
        return CorniceRaycast.closest((BlockView)this,start,end,pos,state,cap);
    }
}
