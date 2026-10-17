package com.oliver.erydon.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.oliver.erydon.client.particle.BlockBreakParticleBudget;
import net.minecraft.block.BlockState;
import net.minecraft.client.particle.BlockDustParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Cap break debris only; mining hits and other namespaces retain the vanilla path. */
@Mixin(ParticleManager.class)
public abstract class BlockBreakParticlesMixin {
    @Shadow protected ClientWorld world;
    @Shadow public abstract void addParticle(Particle particle);

    @WrapOperation(method = "addBlockBreakParticles", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/shape/VoxelShape;forEachBox(Lnet/minecraft/util/shape/VoxelShapes$BoxConsumer;)V"))
    private void erydon$boundedBreakDebris(VoxelShape shape, VoxelShapes.BoxConsumer vanilla,
                                             Operation<Void> original, BlockPos pos, BlockState state) {
        if (!"erydon".equals(Registries.BLOCK.getId(state.getBlock()).getNamespace())) {
            original.call(shape, vanilla);
            return;
        }
        BlockBreakParticleBudget.emit(shape, (x, y, z, vx, vy, vz) ->
                addParticle(new BlockDustParticle(world, pos.getX() + x, pos.getY() + y, pos.getZ() + z,
                        vx, vy, vz, state, pos)));
    }
}
