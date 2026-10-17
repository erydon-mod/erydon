package com.oliver.erydon.block.entity;

import com.oliver.erydon.block.CorniceAssembly;
import com.oliver.erydon.block.CorniceBlock;
import com.oliver.erydon.block.CoverBlock;
import net.fabricmc.fabric.api.rendering.data.v1.RenderAttachmentBlockEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;

/** No ticker or per-frame renderer: chunks consume the immutable contents attachment. */
public final class CorniceAssemblyBlockEntity extends BlockEntity implements RenderAttachmentBlockEntity {
    private volatile CorniceAssembly.Contents contents = CorniceAssembly.EMPTY;
    private volatile Snapshot snapshot;
    private record Snapshot(CorniceAssembly.Contents local, BlockState basis, CorniceAssembly.Contents world) { }

    public CorniceAssemblyBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.corniceAssembly(), pos, state);
    }

    public CorniceAssembly.Contents contents() { return contents; }

    public CorniceAssembly.Contents worldContents(BlockState state) {
        CorniceAssembly.Contents local = contents;
        Snapshot cached = snapshot;
        if (cached != null && cached.local == local && cached.basis == state) return cached.world;
        CorniceAssembly.Contents result = CorniceAssembly.toWorld(local, state);
        snapshot = new Snapshot(local, state, result);
        return result;
    }

    public void setContents(CorniceAssembly.Contents worldBasis) {
        CorniceAssembly.Contents replacement = CorniceAssembly.toLocal(worldBasis, getCachedState());
        if (replacement.equals(contents)) return;
        contents = replacement;
        markDirty();
        if (world != null) world.updateListeners(pos, getCachedState(), getCachedState(), Block.NOTIFY_LISTENERS);
    }

    @Override public Object getRenderAttachmentData() { return worldContents(getCachedState()); }

    @Override protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putInt("Version", 1);
        NbtList cornices = new NbtList();
        for (BlockState part : contents.cornices()) cornices.add(NbtHelper.fromBlockState(part));
        nbt.put("Cornices", cornices);
        if (contents.cover() != null) nbt.put("Cover", NbtHelper.fromBlockState(contents.cover()));
    }

    @Override public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        ArrayList<BlockState> parts = new ArrayList<>();
        NbtList list = nbt.getList("Cornices", NbtElement.COMPOUND_TYPE);
        for (int index = 0; index < Math.min(list.size(), CorniceAssembly.MAX_CORNICES); index++) {
            BlockState part = NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), list.getCompound(index));
            if (part.getBlock() instanceof CorniceBlock) parts.add(part);
        }
        BlockState cover = nbt.contains("Cover", NbtElement.COMPOUND_TYPE)
                ? NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), nbt.getCompound("Cover")) : null;
        contents = new CorniceAssembly.Contents(parts, cover != null && cover.getBlock() instanceof CoverBlock ? cover : null);
        if (world != null && world.isClient) CorniceAssembly.redraw(world, pos);
    }

    @Override public BlockEntityUpdateS2CPacket toUpdatePacket() { return BlockEntityUpdateS2CPacket.create(this); }
    @Override public NbtCompound toInitialChunkDataNbt() { return createNbt(); }
}
