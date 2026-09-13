package com.oliver.erydon.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.oliver.erydon.block.WindowArchBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keep vanilla debris, but do not multiply it by the arch window's fine collision detail. */
@Mixin(ParticleManager.class)
public abstract class WindowArchBreakParticlesMixin {
    @WrapOperation(method = "addBlockBreakParticles", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/block/BlockState;getOutlineShape(Lnet/minecraft/world/BlockView;Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/util/shape/VoxelShape;"))
    private VoxelShape erydon$boundedArchWindowDebris(BlockState state, BlockView world, BlockPos pos,
                                                    Operation<VoxelShape> original) {
        if (state.getBlock() instanceof WindowArchBlock window) {
            return window.getBreakParticleShape(state);
        }
        return original.call(state, world, pos);
    }
}
