package com.oliver.erydon.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.oliver.erydon.block.CorniceBlock;
import com.oliver.erydon.block.CorniceAssembly;
import com.oliver.erydon.block.CorniceSlopePlacement;
import com.oliver.erydon.block.SlopeVerticalBlock;
import com.oliver.erydon.block.SlopeVerticalShallowBroadBlock;
import com.oliver.erydon.block.SlopeVerticalShallowNarrowBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockCollisionSpliterator;
import net.minecraft.world.BlockView;
import net.minecraft.world.CollisionView;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.AbstractMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/** Discover attached overhangs through slope cells vanilla already visits; never expand every collision query. */
@Mixin(BlockCollisionSpliterator.class)
public abstract class CorniceCollisionIteratorMixin<T> {
    @Shadow @Final private Box box;
    @Shadow @Final private VoxelShape boxShape;
    @Shadow @Final private CollisionView world;
    @Shadow @Final private ShapeContext context;
    @Shadow @Final private boolean forEntity;
    @Shadow @Final private BiFunction<BlockPos.Mutable, VoxelShape, T> resultFunction;
    @Unique private ArrayDeque<Map.Entry<BlockPos, BlockState>> erydon$corniceOwners;
    @Unique private Set<Long> erydon$queuedCornices;
    @Unique private BlockPos.Mutable erydon$extraPos;

    @WrapOperation(method = "computeNext", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/block/BlockState;isOf(Lnet/minecraft/block/Block;)Z"))
    private boolean erydon$admitCorniceCorners(BlockState state, Block block, Operation<Boolean> original) {
        return original.call(state, block) || block == Blocks.MOVING_PISTON && CorniceAssembly.isOwner(state);
    }

    @WrapOperation(method = "computeNext", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/BlockView;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"))
    private BlockState erydon$discoverAttachedOwners(BlockView chunk, BlockPos supportPos, Operation<BlockState> original) {
        BlockState support = original.call(chunk, supportPos);
        Block block = support.getBlock();
        if (!CorniceSlopePlacement.candidate(support)) return support;
        if (supportPos.getY() + 1 <= box.minY || supportPos.getY() + .25 >= box.maxY) return support;
        // Lighting and other asynchronous ServerWorld readers must not request additional chunks.
        if (world instanceof ServerWorld server && !server.getServer().isOnThread()) return support;
        for (Direction outward : Direction.Type.HORIZONTAL) {
            if (CorniceSlopePlacement.shapeFor(support, outward) == null) continue;
            BlockPos owner = supportPos.offset(outward);
            if (erydon$insideVanillaRange(owner)) continue;
            BlockView ownerChunk = world.getChunkAsView(ChunkSectionPos.getSectionCoord(owner.getX()),
                    ChunkSectionPos.getSectionCoord(owner.getZ()));
            if (ownerChunk == null) continue;
            BlockState state = ownerChunk.getBlockState(owner);
            if (!CorniceAssembly.isOwner(state) || forEntity && !state.shouldSuffocate(ownerChunk, owner)) continue;
            boolean attached = false;
            for (BlockState part : CorniceAssembly.contents(ownerChunk, owner).cornices()) {
                if (part.get(CorniceBlock.SHAPE).isAngled()
                        && CorniceSlopePlacement.supportPos(part, owner).equals(supportPos)
                        && CorniceSlopePlacement.matches(support, part)) attached = true;
            }
            if (!attached) continue;
            if (erydon$corniceOwners == null) {
                erydon$corniceOwners = new ArrayDeque<>();
                erydon$queuedCornices = new HashSet<>();
                erydon$extraPos = new BlockPos.Mutable();
            }
            if (erydon$queuedCornices.add(owner.asLong()))
                erydon$corniceOwners.add(new AbstractMap.SimpleImmutableEntry<>(owner, state));
        }
        return support;
    }

    @Inject(method = "computeNext", at = @At("HEAD"), cancellable = true)
    private void erydon$emitQueuedOwners(CallbackInfoReturnable<T> callback) {
        T extra = erydon$nextOwner();
        if (extra != null) callback.setReturnValue(extra);
    }

    @WrapOperation(method = "computeNext", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/BlockCollisionSpliterator;endOfData()Ljava/lang/Object;"))
    private T erydon$ownersBeforeEnd(BlockCollisionSpliterator<T> iterator, Operation<T> original) {
        // Calling vanilla endOfData first would mark AbstractIterator DONE and hide a queued result.
        T extra = erydon$nextOwner();
        return extra != null ? extra : original.call(iterator);
    }

    @Unique private boolean erydon$insideVanillaRange(BlockPos owner) {
        return owner.getX() >= MathHelper.floor(box.minX - 1e-7) - 1
                && owner.getX() <= MathHelper.floor(box.maxX + 1e-7) + 1
                && owner.getY() >= MathHelper.floor(box.minY - 1e-7) - 1
                && owner.getY() <= MathHelper.floor(box.maxY + 1e-7) + 1
                && owner.getZ() >= MathHelper.floor(box.minZ - 1e-7) - 1
                && owner.getZ() <= MathHelper.floor(box.maxZ + 1e-7) + 1;
    }

    @Unique private T erydon$nextOwner() {
        if (erydon$corniceOwners == null) return null;
        while (!erydon$corniceOwners.isEmpty()) {
            var extra = erydon$corniceOwners.removeFirst();
            BlockPos owner = extra.getKey();
            VoxelShape shape = extra.getValue().getCollisionShape(world, owner, context)
                    .offset(owner.getX(), owner.getY(), owner.getZ());
            if (!shape.isEmpty() && VoxelShapes.matchesAnywhere(shape, boxShape, BooleanBiFunction.AND)) {
                erydon$extraPos.set(owner.getX(), owner.getY(), owner.getZ());
                return resultFunction.apply(erydon$extraPos, shape);
            }
        }
        return null;
    }
}
