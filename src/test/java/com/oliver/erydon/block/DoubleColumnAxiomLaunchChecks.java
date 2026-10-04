package com.oliver.erydon.block;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static com.oliver.erydon.block.DoubleCircularColumnBlock.*;
import static com.oliver.erydon.block.DoubleColumnRepairLaunchProbe.*;

/** Optional checks against the user's actual named Axiom classes and our selection mixin. */
final class DoubleColumnAxiomLaunchChecks {
    static int run(DoubleCircularColumnBlock block) throws Exception {
        MinecraftClient client=allocate(MinecraftClient.class);
        Field instance=MinecraftClient.class.getDeclaredField("instance"); instance.setAccessible(true); instance.set(null,client);
        TestClientWorld world=allocate(TestClientWorld.class); world.states=new java.util.HashMap<>();
        client.world=world;
        for(int y=0;y<5;y++) for(int x=0;x<2;x++) for(int z=0;z<2;z++) {
            Section section=y==0 ? Section.BASE_LOWER : y==1 ? Section.BASE_UPPER
                    : y==2 ? Section.SHAFT : y==3 ? Section.CAPITAL_LOWER : Section.CAPITAL_UPPER;
            world.states.put(new BlockPos(x,y,z),block.getDefaultState().with(X,x).with(Z,z).with(SECTION,section));
        }
        Class<?> type=Class.forName("com.moulberry.axiom.buildertools.BuilderToolSelectionState");
        require(java.util.Arrays.stream(type.getDeclaredFields()).anyMatch(field ->
                field.getName().equals("erydon$unexpandedSelection")),"Column selection mixin was not applied");
        Object selection=type.getConstructor().newInstance();
        Method left=type.getMethod("leftClick",BlockHitResult.class),right=type.getMethod("rightClick",BlockHitResult.class);
        Method contains=type.getMethod("selectionContains",BlockPos.class),restore=type.getMethod("getSelectionRestore");
        BlockPos base=new BlockPos(1,0,1),shaft=new BlockPos(1,2,1);
        // right-click before the first corner exercises the early-return hook.
        right.invoke(selection,hit(base));
        left.invoke(selection,hit(base)); right.invoke(selection,hit(base));
        for(BlockPos cell:selectionCells(world,base)) require((boolean)contains.invoke(selection,cell),"Actual Axiom split the base");
        Object saved=restore.invoke(selection);
        require(saved.getClass().getMethod("pos1").invoke(saved).equals(base),"Axiom moved the user's first corner");
        require(saved.getClass().getMethod("set").invoke(saved)==null,"Undo stored temporary completion as the user selection");
        left.invoke(selection,hit(shaft)); right.invoke(selection,hit(shaft));
        for(BlockPos cell:selectionCells(world,shaft)) require((boolean)contains.invoke(selection,cell),"Actual Axiom split the shaft");
        require(!(boolean)contains.invoke(selection,base),"Shrinking retained the old base section");
        type.getMethod("restoreFrom",saved.getClass()).invoke(selection,saved);
        for(BlockPos cell:selectionCells(world,base)) require((boolean)contains.invoke(selection,cell),"Restoration did not complete the base");
        type.getMethod("resetSelection").invoke(selection);
        require(!(boolean)contains.invoke(selection,base),"Reset retained completed cells");
        // Use the real operation entry point. Its completion must reflect the current world.
        type.getMethod("setPos1",BlockPos.class).invoke(selection,shaft);
        type.getMethod("setPos2",BlockPos.class).invoke(selection,shaft);
        BlockPos removed=new BlockPos(0,2,0); world.states.remove(removed);
        type.getMethod("createSelectionBuffer").invoke(selection);
        require(!(boolean)contains.invoke(selection,removed),"Tool operation retained a stale completed cell");
        for(BlockPos cell:selectionCells(world,shaft)) require((boolean)contains.invoke(selection,cell),"Operation lost a remaining shaft cell");
        // Capital selection must work for either layer and every style, even before
        // deferred paste repair has replaced stale shaft labels on the top layers.
        world.states.put(removed,block.getDefaultState().with(SECTION,Section.SHAFT));
        int capitals=0;
        for(ColumnBlock.CapitalStyle style:ColumnBlock.CapitalStyle.values()) {
            for(int y=3;y<=4;y++) for(int x=0;x<2;x++) for(int z=0;z<2;z++)
                world.states.put(new BlockPos(x,y,z),block.getDefaultState().with(X,x).with(Z,z)
                        .with(SECTION,Section.SHAFT).with(CAPITAL,style));
            for(int y=3;y<=4;y++) for(int x=0;x<2;x++) for(int z=0;z<2;z++) {
                BlockPos capital=new BlockPos(x,y,z);
                type.getMethod("setPos1",BlockPos.class).invoke(selection,capital);
                type.getMethod("setPos2",BlockPos.class).invoke(selection,capital);
                java.util.List<BlockPos> cells=selectionCells(world,capital);
                require(cells.size()==8,"Stale capital was not resolved as a complete section");
                for(BlockPos cell:cells) require((boolean)contains.invoke(selection,cell),"Actual Axiom split the capital: "+style);
                require(!(boolean)contains.invoke(selection,shaft),"Capital selection included its shaft");
                Object capitalRestore=restore.invoke(selection);
                require(capitalRestore.getClass().getMethod("pos1").invoke(capitalRestore).equals(capital),"Capital completion moved the corner");
                type.getMethod("createSelectionBuffer").invoke(selection);
                for(BlockPos cell:cells) require((boolean)contains.invoke(selection,cell),"Operation split a capital");
                capitals++;
            }
        }
        System.out.println("ERYDON_LARGE_COLUMN_AXIOM_OK: actual selection/corner/shrink/restore/reset/operation entry points, "+capitals+" capital/style cases");
        return 7+capitals;
    }
    private static BlockHitResult hit(BlockPos pos) { return new BlockHitResult(Vec3d.ofCenter(pos),Direction.UP,pos,false); }
    private static final class TestClientWorld extends ClientWorld {
        Map<BlockPos,BlockState> states;
        private TestClientWorld() { super(null,null,null,null,0,0,null,null,false,0); }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos,Blocks.AIR.getDefaultState()); }
        @Override public int getBottomY() { return -64; }
        @Override public int getHeight() { return 384; }
        @Override public boolean isChunkLoaded(BlockPos pos) { return true; }
    }
}
