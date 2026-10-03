package com.oliver.erydon.block;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import com.oliver.erydon.util.ClusterRecalcSafety;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.event.GameEvent;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.oliver.erydon.block.DoubleCircularColumnBlock.*;

/** Runs actual block callbacks against an in-memory world; no game window or save. */
public final class DoubleColumnRepairLaunchProbe implements PreLaunchEntrypoint {
    private DoubleCircularColumnBlock block;
    private int cases;

    @Override public void onPreLaunch() {
        try {
            SharedConstants.createGameVersion();
            Bootstrap.initialize();
            block = new DoubleCircularColumnBlock(AbstractBlock.Settings.create());
            for (int height : new int[]{4, 5, 8, 128}) {
                TestWorld world = world();
                column(world, BlockPos.ORIGIN, height, true);
                settle(world);
                assertLayout(world, height);
                require(world.states.values().stream().allMatch(state ->
                        state.get(BASE) == ColumnBlock.BaseStyle.NARROW
                        && state.get(CAPITAL) == ColumnBlock.CapitalStyle.NONE), "Repair lost selected styles");
                cases++;
            }
            for (int y : new int[]{0, 1, 2, 4, 5}) for (int x=0; x<2; x++) for (int z=0; z<2; z++) {
                TestWorld world=world(); column(world, BlockPos.ORIGIN, 6, false);
                BlockPos hit=new BlockPos(x,y,z);
                Set<BlockPos> removed=new HashSet<>(selectionCells(world,hit));
                require(removed.size()==(y==2 ? 4 : 8),"Wrong indivisible break section");
                block.onBreak(world,hit,world.getBlockState(hit),null);
                world.removeBlock(hit,false); // Vanilla removes the originally targeted cell after onBreak.
                require(world.states.size()==24-removed.size(),"Break removed another section");
                for (BlockPos cell:removed) require(world.getBlockState(cell).isAir(),"Broken section survived");
                settle(world);
                require(world.states.size()==24-removed.size(),"Repair recreated deleted blocks");
                cases++;
            }
            for (Section lower : new Section[]{Section.BASE_LOWER,Section.CAPITAL_LOWER}) {
                TestWorld world=world();
                pair(world,BlockPos.ORIGIN,lower);
                settle(world);
                require(world.states.size()==8 && world.getBlockState(BlockPos.ORIGIN).get(SECTION)==lower,
                        "Detached decorative section changed on paste");
                require(block.recalcCluster(world,BlockPos.ORIGIN).recalculated(),"Detached section cannot recalc");
                require(world.getBlockState(BlockPos.ORIGIN).get(SECTION)==lower,"Recalc changed detached section");
                cases++;
            }
            TestWorld orphan=world();
            layer(orphan,BlockPos.ORIGIN,Section.CAPITAL_UPPER);
            settle(orphan);
            require(orphan.states.values().stream().allMatch(state -> state.get(SECTION)==Section.SHAFT),
                    "An orphan half-capital was retained"); cases++;
            TestWorld partial=world(); column(partial,BlockPos.ORIGIN,5,true);
            partial.setBlockState(BlockPos.ORIGIN,Blocks.STONE.getDefaultState(),Block.NOTIFY_ALL);
            settle(partial);
            require(partial.getBlockState(BlockPos.ORIGIN).isOf(Blocks.STONE),"Repair overwrote an occupied corner");
            require(partial.getBlockState(new BlockPos(1,0,1)).get(SECTION)==Section.BASE_LOWER,
                    "A missing anchor prevented repair"); cases++;
            TestWorld adjacent=world(); column(adjacent,BlockPos.ORIGIN,6,false);
            column(adjacent,new BlockPos(2,0,0),6,false);
            // An overlapping cell belongs to a different anchor and must be left alone.
            BlockState foreign=block.getDefaultState().with(X,0).with(Z,0).with(SECTION,Section.SHAFT);
            adjacent.states.put(new BlockPos(1,0,0),foreign);
            Set<BlockPos> selected=new HashSet<>(selectionCells(adjacent,BlockPos.ORIGIN));
            require(!selected.contains(new BlockPos(1,0,0)),"Selection pulled in a different anchor");
            block.recalcCluster(adjacent,BlockPos.ORIGIN);
            require(adjacent.getBlockState(new BlockPos(1,0,0))==foreign,"Recalc merged a different anchor"); cases++;
            TestWorld unloaded=world(); column(unloaded,new BlockPos(15,0,0),6,true);
            unloaded.unloadedChunkX=1;
            Map<BlockPos,BlockState> before=Map.copyOf(unloaded.states);
            require(block.recalcCluster(unloaded,new BlockPos(15,0,0)).status()==RecalcStatus.UNLOADED_EDGE,
                    "Recalc treated an unloaded edge as air");
            settle(unloaded);
            require(unloaded.states.equals(before),"Unloaded edge mutated the column"); cases++;
            TestWorld halo=world(); column(halo,new BlockPos(13,0,0),6,true);
            halo.unloadedChunkX=1;
            before=Map.copyOf(halo.states);
            require(ClusterRecalcSafety.run(halo,()->block.recalcCluster(halo,new BlockPos(13,0,0)))
                    .status()==RecalcStatus.UNLOADED_EDGE,"Admin recalc ignored its unloaded read halo");
            require(halo.states.equals(before),"Admin recalc changed blocks before halo preflight"); cases++;
            TestWorld oversized=world(); column(oversized,BlockPos.ORIGIN,129,true);
            before=Map.copyOf(oversized.states);
            require(block.recalcCluster(oversized,BlockPos.ORIGIN).status()==RecalcStatus.TOO_LARGE,
                    "Oversized recalc was not bounded");
            require(oversized.states.equals(before),"Oversized recalc partially changed the column"); cases++;
            transforms();
            if (Boolean.getBoolean("erydon.large_column_test.axiom"))
                cases+=DoubleColumnAxiomLaunchChecks.run(block);
            System.out.println("ERYDON_LARGE_COLUMNS_OK: "+cases+" callback/repair/recalc/transform cases");
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }

    private void transforms() throws Exception {
        for (Section section:Section.values()) for (BlockRotation rotation:BlockRotation.values())
            for (BlockMirror mirror:BlockMirror.values()) {
                TestWorld world=world();
                for (int x=0;x<2;x++) for (int z=0;z<2;z++) {
                    BlockState state=block.getDefaultState().with(X,x).with(Z,z).with(SECTION,section);
                    BlockState transformed=state.rotate(rotation).mirror(mirror);
                    world.states.put(new BlockPos(transformed.get(X),0,transformed.get(Z)),transformed);
                    require(transformed.get(SECTION)==section,"Horizontal transform changed vertical section");
                    require(state.mirror(mirror).mirror(mirror)==state,"Mirror is not reversible");
                }
                for (BlockPos hit:world.states.keySet())
                    require(new HashSet<>(selectionCells(world,hit)).equals(world.states.keySet()),
                            "Transformed section membership split");
                cases++;
            }
    }

    private void assertLayout(TestWorld world,int height) {
        for (int y=0;y<height;y++) for(int x=0;x<2;x++) for(int z=0;z<2;z++) {
            Section expected=y==0 ? Section.BASE_LOWER : y==1 ? Section.BASE_UPPER
                    : y==height-2 ? Section.CAPITAL_LOWER : y==height-1 ? Section.CAPITAL_UPPER : Section.SHAFT;
            require(world.getBlockState(new BlockPos(x,y,z)).get(SECTION)==expected,"Incorrect settled section");
        }
    }
    private void column(TestWorld world,BlockPos origin,int height,boolean stale) {
        for(int y=0;y<height;y++) layer(world,origin.up(y),stale ? Section.SHAFT
                : y==0 ? Section.BASE_LOWER : y==1 ? Section.BASE_UPPER
                : y==height-2 ? Section.CAPITAL_LOWER : y==height-1 ? Section.CAPITAL_UPPER : Section.SHAFT);
    }
    private void pair(TestWorld world,BlockPos origin,Section lower) {
        layer(world,origin,lower); layer(world,origin.up(),lower==Section.BASE_LOWER ? Section.BASE_UPPER : Section.CAPITAL_UPPER);
    }
    private void layer(TestWorld world,BlockPos origin,Section section) {
        for(int x=0;x<2;x++) for(int z=0;z<2;z++) world.setBlockState(origin.add(x,0,z),
                block.getDefaultState().with(X,x).with(Z,z).with(SECTION,section)
                        .with(BASE,ColumnBlock.BaseStyle.NARROW).with(CAPITAL,ColumnBlock.CapitalStyle.NONE),Block.NOTIFY_ALL);
    }
    private void settle(TestWorld world) {
        int rounds=0;
        while(!world.scheduled.isEmpty()) {
            require(++rounds<8,"Repair did not converge");
            Set<BlockPos> due=new LinkedHashSet<>(world.scheduled); world.scheduled.clear();
            for(BlockPos pos:due) {
                BlockState state=world.getBlockState(pos);
                if(state.isOf(block)) block.scheduledTick(state,world,pos,Random.create(0));
            }
        }
    }
    static TestWorld world() throws Exception {
        TestWorld world=allocate(TestWorld.class);
        world.states=new HashMap<>(); world.scheduled=new LinkedHashSet<>(); world.unloadedChunkX=Integer.MIN_VALUE;
        return world;
    }
    static <T> T allocate(Class<T> type) throws Exception {
        Field field=Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(((Unsafe)field.get(null)).allocateInstance(type));
    }
    static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }

    /** Constructor bypass is confined to the test: only overridden in-memory operations are used. */
    static final class TestWorld extends ServerWorld {
        Map<BlockPos,BlockState> states;
        Set<BlockPos> scheduled;
        int unloadedChunkX;
        private TestWorld() { super(null,null,null,null,null,null,null,false,0,List.of(),false,null); }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos,Blocks.AIR.getDefaultState()); }
        @Override public int getBottomY() { return -64; }
        @Override public int getHeight() { return 512; }
        @Override public boolean isChunkLoaded(BlockPos pos) { return (pos.getX()>>4)!=unloadedChunkX; }
        @Override public void scheduleBlockTick(BlockPos pos,Block block,int delay) { scheduled.add(pos.toImmutable()); }
        @Override public boolean setBlockState(BlockPos pos,BlockState state,int flags) {
            BlockState old=getBlockState(pos);
            if(old==state) return false;
            if(state.isAir()) states.remove(pos); else states.put(pos.toImmutable(),state);
            old.onStateReplaced(this,pos,state,false);
            if(old.getBlock()!=state.getBlock()) state.onBlockAdded(this,pos,old,false);
            return true;
        }
        @Override public boolean removeBlock(BlockPos pos,boolean moved) { return setBlockState(pos,Blocks.AIR.getDefaultState(),Block.NOTIFY_ALL); }
        @Override public void syncWorldEvent(PlayerEntity player,int id,BlockPos pos,int data) {}
        @Override public void emitGameEvent(GameEvent event,BlockPos pos,GameEvent.Emitter emitter) {}
    }
}
