package com.oliver.erydon.mixin.client;

import com.oliver.erydon.block.ShallowSlopeBlock;
import com.oliver.erydon.block.SlopeBlock;
import com.oliver.erydon.block.SlopeSteepBlock;
import com.oliver.erydon.block.SlopeVerticalBlock;
import com.oliver.erydon.block.SlopeVerticalShallowBroadBlock;
import com.oliver.erydon.block.SlopeVerticalShallowNarrowBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.FluidRenderer;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(FluidRenderer.class)
abstract class FluidRendererMixin {

    @Shadow
    private float calculateFluidHeight(
            BlockRenderView world,
            Fluid fluid,
            float currentHeight,
            float horizontalHeightA,
            float horizontalHeightB,
            BlockPos diagonalPos
    ) {
        throw new AssertionError();
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/block/FluidRenderer;calculateFluidHeight(Lnet/minecraft/world/BlockRenderView;Lnet/minecraft/fluid/Fluid;FFFLnet/minecraft/util/math/BlockPos;)F",
                    ordinal = 0
            )
    )
    private float erydon$flattenNorthEastCorner(
            FluidRenderer instance,
            BlockRenderView world,
            Fluid fluid,
            float currentHeight,
            float horizontalHeightA,
            float horizontalHeightB,
            BlockPos diagonalPos,
            BlockRenderView renderView,
            BlockPos pos,
            VertexConsumer vertexConsumer,
            BlockState blockState,
            FluidState fluidState
    ) {
        return this.erydon$getSlopeHeightOrVanilla(world, fluid, currentHeight, horizontalHeightA, horizontalHeightB, diagonalPos, blockState, fluidState);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/block/FluidRenderer;calculateFluidHeight(Lnet/minecraft/world/BlockRenderView;Lnet/minecraft/fluid/Fluid;FFFLnet/minecraft/util/math/BlockPos;)F",
                    ordinal = 1
            )
    )
    private float erydon$flattenNorthWestCorner(
            FluidRenderer instance,
            BlockRenderView world,
            Fluid fluid,
            float currentHeight,
            float horizontalHeightA,
            float horizontalHeightB,
            BlockPos diagonalPos,
            BlockRenderView renderView,
            BlockPos pos,
            VertexConsumer vertexConsumer,
            BlockState blockState,
            FluidState fluidState
    ) {
        return this.erydon$getSlopeHeightOrVanilla(world, fluid, currentHeight, horizontalHeightA, horizontalHeightB, diagonalPos, blockState, fluidState);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/block/FluidRenderer;calculateFluidHeight(Lnet/minecraft/world/BlockRenderView;Lnet/minecraft/fluid/Fluid;FFFLnet/minecraft/util/math/BlockPos;)F",
                    ordinal = 2
            )
    )
    private float erydon$flattenSouthEastCorner(
            FluidRenderer instance,
            BlockRenderView world,
            Fluid fluid,
            float currentHeight,
            float horizontalHeightA,
            float horizontalHeightB,
            BlockPos diagonalPos,
            BlockRenderView renderView,
            BlockPos pos,
            VertexConsumer vertexConsumer,
            BlockState blockState,
            FluidState fluidState
    ) {
        return this.erydon$getSlopeHeightOrVanilla(world, fluid, currentHeight, horizontalHeightA, horizontalHeightB, diagonalPos, blockState, fluidState);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/block/FluidRenderer;calculateFluidHeight(Lnet/minecraft/world/BlockRenderView;Lnet/minecraft/fluid/Fluid;FFFLnet/minecraft/util/math/BlockPos;)F",
                    ordinal = 3
            )
    )
    private float erydon$flattenSouthWestCorner(
            FluidRenderer instance,
            BlockRenderView world,
            Fluid fluid,
            float currentHeight,
            float horizontalHeightA,
            float horizontalHeightB,
            BlockPos diagonalPos,
            BlockRenderView renderView,
            BlockPos pos,
            VertexConsumer vertexConsumer,
            BlockState blockState,
            FluidState fluidState
    ) {
        return this.erydon$getSlopeHeightOrVanilla(world, fluid, currentHeight, horizontalHeightA, horizontalHeightB, diagonalPos, blockState, fluidState);
    }

    private float erydon$getSlopeHeightOrVanilla(
            BlockRenderView world,
            Fluid fluid,
            float currentHeight,
            float horizontalHeightA,
            float horizontalHeightB,
            BlockPos diagonalPos,
            BlockState blockState,
            FluidState fluidState
    ) {
        if (fluid == Fluids.WATER && this.erydon$isSlopeWaterSurface(blockState, fluidState)) {
            return fluidState.getHeight();
        }

        return this.calculateFluidHeight(world, fluid, currentHeight, horizontalHeightA, horizontalHeightB, diagonalPos);
    }

    private boolean erydon$isSlopeWaterSurface(BlockState blockState, FluidState fluidState) {
        if (fluidState.getFluid() != Fluids.WATER) {
            return false;
        }

        return blockState.getBlock() instanceof SlopeBlock
                || blockState.getBlock() instanceof ShallowSlopeBlock
                || blockState.getBlock() instanceof SlopeSteepBlock
                || blockState.getBlock() instanceof SlopeVerticalBlock
                || blockState.getBlock() instanceof SlopeVerticalShallowBroadBlock
                || blockState.getBlock() instanceof SlopeVerticalShallowNarrowBlock;
    }
}
