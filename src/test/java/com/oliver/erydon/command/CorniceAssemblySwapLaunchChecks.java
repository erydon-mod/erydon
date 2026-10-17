package com.oliver.erydon.command;

import com.oliver.erydon.block.CorniceAssembly;
import com.oliver.erydon.block.CorniceAssemblyLaunchChecks;
import com.oliver.erydon.block.CorniceBlock;
import com.oliver.erydon.block.CoverBlock;
import com.oliver.erydon.block.entity.CorniceAssemblyBlockEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Exercises the command's real batch/undo guards while cover refreshes mutate a neighbouring entity. */
public final class CorniceAssemblySwapLaunchChecks {
    private static final Identifier TARGET = new Identifier("erydon", "cornice_swap_test_cover");
    private CorniceAssemblySwapLaunchChecks() { }

    public static void run() throws Exception {
        CoverBlock target = Registry.register(Registries.BLOCK, TARGET,
                new CoverBlock(Block.Settings.copy(Blocks.STONE).nonOpaque().luminance(CoverBlock::luminance)));
        target.getStateManager().getStates().forEach(BlockState::initShapeCache);
        Method factory = CorniceAssemblyLaunchChecks.class.getDeclaredMethod("world");
        factory.setAccessible(true);
        ServerWorld world = (ServerWorld) factory.invoke(null);
        Field statesField = world.getClass().getDeclaredField("states");
        statesField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<BlockPos, BlockState> states = (Map<BlockPos, BlockState>) statesField.get(world);
        BlockState cornice = Registries.BLOCK.get(new Identifier("erydon", "glacium_cornice_modern")).getDefaultState();
        BlockState originalCover = CorniceAssemblyLaunchChecks.coverBlock().getDefaultState()
                .with(CoverBlock.ATTACH, Direction.UP).with(CoverBlock.SIZE, CoverBlock.CoverSize.LARGE);
        List<BlockPos> cells = List.of(BlockPos.ORIGIN, BlockPos.ORIGIN.east());
        for (BlockPos pos : cells) {
            states.put(pos.up(), Blocks.STONE.getDefaultState());
            states.put(pos.north(), Blocks.STONE.getDefaultState());
            require(CorniceAssembly.add(world, pos, cornice), "Swap fixture could not add its paid cornice");
            require(CorniceAssembly.add(world, pos, originalCover), "Swap fixture could not share its cover cell");
        }
        List<NbtCompound> original = cells.stream().map(pos -> entity(world, pos).createNbtWithId()).toList();
        Identifier source = Registries.BLOCK.getId(originalCover.getBlock());
        ErydonSwapCommand.SwapPlan plan = new ErydonSwapCommand.SwapPlan(Text.literal("fixture source"),
                Text.literal("fixture target"), id -> id.equals(source) ? Optional.of(TARGET) : Optional.empty());
        Class<?> boxType = nested("Box");
        Constructor<?> boxConstructor = boxType.getDeclaredConstructor(int.class, int.class, int.class,
                int.class, int.class, int.class);
        boxConstructor.setAccessible(true);
        Object box = boxConstructor.newInstance(0, 0, 0, 1, 0, 0);
        Method swap = ErydonSwapCommand.class.getDeclaredMethod("swapInBox", ServerWorld.class, boxType,
                ErydonSwapCommand.SwapPlan.class);
        swap.setAccessible(true);
        Object outcome = swap.invoke(null, world, box, plan);
        require((int) accessor(outcome, "replacedBlocks") == 2, "Actual swap did not replace both shared cells");
        Object undo = lastUndo(world);
        @SuppressWarnings("unchecked") List<Object> entries = (List<Object>) accessor(undo, "entries");
        require(entries.size() == 2, "Swap omitted a shared cell from undo");
        for (Object entry : entries) {
            BlockPos pos = (BlockPos) accessor(entry, "pos");
            require(entity(world, pos).createNbtWithId().equals(accessor(entry, "swappedBlockEntityNbt")),
                    "Undo guard captured a temporary cover boundary before the other material changed");
            require(CorniceAssembly.contents(world, pos).cover().isOf(target), "Shared cover material was not swapped");
        }
        require(undo(world, undo) == 2, "Undo rechecked a neighbour after an earlier restore changed its cover EXT");
        for (int index = 0; index < cells.size(); index++) require(original.get(index).equals(entity(world, cells.get(index)).createNbtWithId()),
                "Actual swap/undo failed to restore an original paid part or settled cover extension");

        swap.invoke(null, world, box, plan);
        BlockPos edited = cells.get(0);
        CorniceAssembly.Contents current = CorniceAssembly.contents(world, edited);
        BlockState changedCornice = Registries.BLOCK.get(new Identifier("erydon", "glacium_cornice_georgian"))
                .getDefaultState().with(CorniceBlock.FACING, cornice.get(CorniceBlock.FACING));
        entity(world, edited).setContents(new CorniceAssembly.Contents(List.of(changedCornice), current.cover()));
        require(undo(world, lastUndo(world)) == 1, "Undo overwrote an independently edited shared cell");
        require(CorniceAssembly.contents(world, edited).cornices().equals(List.of(changedCornice))
                        && CorniceAssembly.contents(world, edited).cover().isOf(target),
                "An ineligible shared cell lost its independent edit or paid cover");
        require(CorniceAssembly.contents(world, cells.get(1)).cover().isOf(originalCover.getBlock()),
                "An unchanged neighbouring cell did not undo");
        System.out.println("ERYDON_CORNICE_ASSEMBLY_SWAP_OK: settled two-cell cover material guards, complete undo, independent edit retained");
    }

    private static CorniceAssemblyBlockEntity entity(ServerWorld world, BlockPos pos) {
        return (CorniceAssemblyBlockEntity) world.getBlockEntity(pos);
    }

    private static Class<?> nested(String name) throws ClassNotFoundException {
        return Class.forName(ErydonSwapCommand.class.getName() + "$" + name);
    }

    private static Object accessor(Object record, String name) throws Exception {
        Method method = record.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(record);
    }

    private static Object lastUndo(ServerWorld world) throws Exception {
        Field field = ErydonSwapCommand.class.getDeclaredField("LAST_UNDO_BY_WORLD");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(null)).get(world);
    }

    private static int undo(ServerWorld world, Object undo) throws Exception {
        Method method = ErydonSwapCommand.class.getDeclaredMethod("undoSwap", ServerWorld.class, nested("LastSwapUndo"));
        method.setAccessible(true);
        return (int) accessor(method.invoke(null, world, undo), "restoredBlocks");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
