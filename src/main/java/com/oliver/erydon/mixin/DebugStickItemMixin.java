package com.oliver.erydon.mixin;

import com.oliver.erydon.block.AlcoveBlock;
import com.oliver.erydon.block.ArchRomanesqueBlock;
import com.oliver.erydon.block.CeilingBlock;
import com.oliver.erydon.block.ChimneyBlock;
import com.oliver.erydon.block.CoverBlock;
import com.oliver.erydon.block.DoubleCircularColumnBlock;
import com.oliver.erydon.block.LightBlock;
import com.oliver.erydon.block.LightPendantBlock;
import com.oliver.erydon.block.StairsSpiralLargeBlock;
import com.oliver.erydon.block.SurroundBlock;
import com.oliver.erydon.block.WindowArchBlock;
import com.oliver.erydon.block.WindowFrenchGeorgianBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.DebugStickItem;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;
import java.util.List;

@Mixin(DebugStickItem.class)
public abstract class DebugStickItemMixin {
    @Redirect(method = "use", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/state/StateManager;getProperties()Ljava/util/Collection;"))
    private Collection<Property<?>> erydon$visibleProperties(StateManager<?, ?> manager,
            PlayerEntity player, BlockState state, WorldAccess world, BlockPos pos, boolean update, ItemStack stack) {
        Block block = state.getBlock();
        List<Property<?>> visible = manager.getProperties().stream()
                .filter(property -> !erydon$isHidden(block, property))
                .toList();
        return visible;
    }

    @Redirect(method = "use", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/state/StateManager;getProperty(Ljava/lang/String;)Lnet/minecraft/state/property/Property;"))
    private Property<?> erydon$visibleSelectedProperty(StateManager<?, ?> manager, String name,
            PlayerEntity player, BlockState state, WorldAccess world, BlockPos pos, boolean update, ItemStack stack) {
        Property<?> property = manager.getProperty(name);
        return property != null && erydon$isHidden(state.getBlock(), property) ? null : property;
    }

    private static boolean erydon$isHidden(Block block, Property<?> property) {
        if (block instanceof CeilingBlock) return property == CeilingBlock.UNUSED;
        if (block instanceof ArchRomanesqueBlock) {
            return property == ArchRomanesqueBlock.ARRANGEMENT || property == ArchRomanesqueBlock.REFLECTED;
        }
        if (block instanceof AlcoveBlock) return property == AlcoveBlock.PART;
        if (block instanceof CoverBlock) return property == CoverBlock.EXT;
        if (block instanceof LightBlock) return property == LightBlock.PART;
        if (block instanceof LightPendantBlock) return property == LightPendantBlock.PART;
        if (block instanceof ChimneyBlock) return property == ChimneyBlock.PART;
        if (block instanceof StairsSpiralLargeBlock) return property == StairsSpiralLargeBlock.PART;
        if (block instanceof DoubleCircularColumnBlock) {
            return property == DoubleCircularColumnBlock.X || property == DoubleCircularColumnBlock.Z;
        }
        if (block instanceof SurroundBlock) return property == SurroundBlock.SECTION;
        if (block instanceof WindowArchBlock) {
            return property == WindowArchBlock.PIECE || property == WindowArchBlock.SILL;
        }
        if (block instanceof WindowFrenchGeorgianBlock) {
            return property == WindowFrenchGeorgianBlock.PIECE
                    || property == WindowFrenchGeorgianBlock.SILL
                    || property == WindowFrenchGeorgianBlock.HINGE
                    || property == WindowFrenchGeorgianBlock.CORNER;
        }
        return false;
    }
}
