package com.oliver.erydon.compat.daedalon;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.Identifier;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldAccess;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.DebugStickItem;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.world.border.WorldBorder;
import com.mojang.authlib.GameProfile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import java.nio.file.Files;
import java.nio.file.Path;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Real block properties exercise legacy helper ownership and remembered control selection. */
public final class CapitalDebugControlsLaunchChecks {
    private static final IntProperty X=IntProperty.of("offset_x",0,1),Y=IntProperty.of("offset_y",0,1),Z=IntProperty.of("offset_z",0,1);
    private static final EnumProperty<Size> SIZE=EnumProperty.of("size",Size.class);
    private static final EnumProperty<Orientation> ORIENTATION=EnumProperty.of("capital_orientation",Orientation.class);
    private enum Size implements StringIdentifiable { STANDARD,DOUBLE; public String asString() { return name().toLowerCase(java.util.Locale.ROOT); } }
    private enum Orientation implements StringIdentifiable { STRAIGHT,DIAGONAL,STRAIGHT_90,DIAGONAL_135; public String asString() { return name().toLowerCase(java.util.Locale.ROOT); } }
    private static final class Capital extends Block {
        Capital() { super(Settings.copy(Blocks.STONE)); }
        @Override protected void appendProperties(StateManager.Builder<Block,BlockState> builder) { builder.add(SIZE,ORIENTATION); }
    }
    private static final class Part extends Block {
        Part() { super(Settings.copy(Blocks.STONE)); }
        @Override protected void appendProperties(StateManager.Builder<Block,BlockState> builder) { builder.add(X,Y,Z); }
    }
    /** No server fields are needed by the debug-stick control entry point. */
    private static final class Player extends ServerPlayerEntity {
        private Text message;
        private boolean sneaking;
        private boolean spectator;
        private ItemStack held;
        private Player(MinecraftServer server,ServerWorld world,GameProfile profile) { super(server,world,profile); }
        @Override public boolean isCreativeLevelTwoOp() { return true; }
        @Override public boolean shouldCancelInteraction() { return false; }
        @Override public boolean isSneaking() { return sneaking; }
        @Override public boolean isSpectator() { return spectator; }
        @Override public ItemStack getStackInHand(Hand hand) { return held==null ? ItemStack.EMPTY : held; }
        @Override public void sendMessage(Text message,boolean overlay) { this.message=message; }
        @Override public void sendMessageToClient(Text message,boolean overlay) { this.message=message; }
    }
    public static void run() throws Exception {
        if(Boolean.getBoolean("erydon.capitalCompat.legacyProof")) verifyLegacyHandlerOrdering();
        // With a real companion mod loaded, its registration owns these IDs.
        if(Registries.BLOCK.containsId(new Identifier("daedalon","capital_part"))) return;
        var part=Registry.register(Registries.BLOCK,new Identifier("daedalon","capital_part"),new Part());
        var capital=Registry.register(Registries.BLOCK,new Identifier("daedalon","compat_probe_capital"),new Capital());
        var unrelated=Registry.register(Registries.BLOCK,new Identifier("daedalon","compat_probe_plinth"),new Capital());
        require(!CapitalDebugControls.handles(unrelated.getDefaultState()),"Non-capitals must retain companion controls");
        Map<BlockPos,BlockState> states=new HashMap<>();
        WorldBorder border=new WorldBorder();
        boolean[] outsideHeight={false};
        WorldAccess world=(WorldAccess)Proxy.newProxyInstance(CapitalDebugControlsLaunchChecks.class.getClassLoader(),new Class<?>[]{WorldAccess.class},(proxy,method,args) -> {
            if(method.getName().equals("getBlockState")) return states.getOrDefault(args[0],Blocks.AIR.getDefaultState());
            if(method.getName().equals("setBlockState")) { states.put((BlockPos)args[0],(BlockState)args[1]); return true; }
            if(method.getName().equals("isOutOfHeightLimit")) return outsideHeight[0];
            if(method.getName().equals("getWorldBorder")) return border;
            throw new AssertionError("Unexpected fixture world call "+method.getName());
        });
        BlockPos anchor=BlockPos.ORIGIN;
        var unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var player=(Player)((sun.misc.Unsafe)unsafeField.get(null)).allocateInstance(Player.class);
        var use=DebugStickItem.class.getDeclaredMethod("use",PlayerEntity.class,BlockState.class,WorldAccess.class,BlockPos.class,boolean.class,ItemStack.class);
        use.setAccessible(true);
        int checked=0;
        for(int y=0;y<2;y++) for(int x=0;x<2;x++) for(int z=0;z<2;z++) for(var offset:List.of(X,Y,Z)) {
            states.clear();
            states.put(anchor,capital.getDefaultState().with(SIZE,Size.DOUBLE));
            for(int py=0;py<2;py++) for(int px=0;px<2;px++) for(int pz=0;pz<2;pz++)
                if(px+py+pz>0) states.put(anchor.add(px,py,pz),part.getDefaultState().with(X,px).with(Y,py).with(Z,pz));
            BlockPos clicked=anchor.add(x,y,z);
            BlockState clickedState=states.get(clicked);
            if(x+y+z>0) { clickedState=clickedState.with(offset,1-clickedState.get(offset)); states.put(clicked,clickedState); }
            var target=CapitalDebugControls.target(world,clicked,clickedState);
            require(target!=null && target.pos().equals(anchor),"Corrupt offsets selected a different capital");
            String remembered=offset.getName();
            var stick=Items.DEBUG_STICK.getDefaultStack();
            stick.getOrCreateNbt().putString(CapitalDebugControls.REMEMBERED_PROPERTY,remembered);
            stick.getOrCreateSubNbt("DebugProperty").putString("daedalon:capital_part",remembered);
            for(String expected:List.of("capital_orientation","size","capital_orientation","size")) {
                var selected=CapitalDebugControls.select(target.state(),remembered,false,false);
                require(selected.getName().equals(expected),"Selection exposed internal offsets");
                remembered=selected.getName();
                require((Boolean)use.invoke(Items.DEBUG_STICK,player,world.getBlockState(clicked),world,clicked,false,stick),"Mixed-in property selection failed");
                require(expected.equals(stick.getOrCreateNbt().getString(CapitalDebugControls.REMEMBERED_PROPERTY)),"A companion handler ran before ERYDON and exposed offsets");
            }
            var size=CapitalDebugControls.select(target.state(),"size",true,false);
            var small=CapitalDebugControls.cycle(target.state(),size,false);
            require(small.get(SIZE)==Size.STANDARD && CapitalDebugControls.cycle(small,size,false).get(SIZE)==Size.DOUBLE,"Size stopped cycling");
            require((Boolean)use.invoke(Items.DEBUG_STICK,player,world.getBlockState(clicked),world,clicked,true,stick),"Mixed-in size update failed");
            require(states.get(anchor).get(SIZE)==Size.STANDARD,"Mixed-in size update did not edit its anchor");
            require((Boolean)use.invoke(Items.DEBUG_STICK,player,states.get(anchor),world,anchor,true,stick),"Mixed-in size regrowth failed");
            require(states.get(anchor).get(SIZE)==Size.DOUBLE,"Mixed-in size cycling stopped after shrinking");
            states.remove(anchor);
            if(x+y+z>0) require(CapitalDebugControls.target(world,clicked,clickedState)==null,"Orphan helper borrowed a missing anchor");
            checked++;
        }
        int blocked=0;
        var stick=Items.DEBUG_STICK.getDefaultStack();
        stick.getOrCreateNbt().putString(CapitalDebugControls.REMEMBERED_PROPERTY,"size");
        var hit=new BlockHitResult(Vec3d.ofCenter(anchor),Direction.UP,anchor,false);
        for(int y=0;y<2;y++) for(int x=0;x<2;x++) for(int z=0;z<2;z++) {
            if(x+y+z==0) continue;
            states.clear();
            states.put(anchor,capital.getDefaultState().with(SIZE,Size.STANDARD));
            BlockPos occupied=anchor.add(x,y,z);
            states.put(occupied,Blocks.STONE.getDefaultState());
            Map<BlockPos,BlockState> before=Map.copyOf(states);
            player.sneaking=true;
            player.held=ItemStack.EMPTY;
            player.message=null;
            require(CapitalDebugControls.warnBlockedEmptyHandResize(player,world,false,Hand.MAIN_HAND,hit)==ActionResult.FAIL,
                    "Blocked empty-hand resize must be handled on the server");
            require(states.equals(before),"Blocked empty-hand resize changed its footprint");
            requireBlockedWarning(player.message);
            player.message=null;
            require(CapitalDebugControls.warnBlockedEmptyHandResize(player,world,true,Hand.MAIN_HAND,hit)==ActionResult.PASS
                    && player.message==null,"Client must let the server handle the warning");
            player.spectator=true;
            require(CapitalDebugControls.warnBlockedEmptyHandResize(player,world,false,Hand.MAIN_HAND,hit)==ActionResult.PASS
                    && player.message==null,"Spectators cannot resize and must not receive a warning");
            player.spectator=false;
            player.held=stick;
            require(CapitalDebugControls.warnBlockedEmptyHandResize(player,world,false,Hand.MAIN_HAND,hit)==ActionResult.PASS
                    && player.message==null,"Held items must retain their normal interaction");
            player.held=ItemStack.EMPTY;
            player.sneaking=false;
            require(CapitalDebugControls.warnBlockedEmptyHandResize(player,world,false,Hand.MAIN_HAND,hit)==ActionResult.PASS
                    && player.message==null,"Normal empty-hand use must remain unchanged");
            player.message=null;
            require((Boolean)use.invoke(Items.DEBUG_STICK,player,states.get(anchor),world,anchor,true,stick),"Blocked resize must be handled");
            require(states.equals(before),"Blocked resize changed its anchor or obstructing blocks");
            requireBlockedWarning(player.message);
            states.remove(occupied);
            player.sneaking=true;
            player.message=null;
            require(CapitalDebugControls.warnBlockedEmptyHandResize(player,world,false,Hand.MAIN_HAND,hit)==ActionResult.PASS
                    && player.message==null,"Successful empty-hand growth must remain owned by Daedalon");
            require(states.get(anchor).get(SIZE)==Size.STANDARD,"Compatibility callback must not resize a clear capital itself");
            player.sneaking=false;
            require((Boolean)use.invoke(Items.DEBUG_STICK,player,states.get(anchor),world,anchor,true,stick),"Unblocked resize failed");
            require(states.get(anchor).get(SIZE)==Size.DOUBLE,"Clearing the obstruction must keep the same control usable");
            player.sneaking=true;
            player.message=null;
            require(CapitalDebugControls.warnBlockedEmptyHandResize(player,world,false,Hand.MAIN_HAND,hit)==ActionResult.PASS
                    && player.message==null,"Successful shrinking must remain owned by Daedalon");
            player.sneaking=false;
            blocked++;
        }
        states.clear(); states.put(anchor,capital.getDefaultState().with(SIZE,Size.STANDARD));
        outsideHeight[0]=true;
        use.invoke(Items.DEBUG_STICK,player,states.get(anchor),world,anchor,true,stick);
        require(states.get(anchor).get(SIZE)==Size.STANDARD,"Height limit must prevent growth");
        outsideHeight[0]=false;
        double previousSize=border.getSize(); border.setSize(1);
        use.invoke(Items.DEBUG_STICK,player,states.get(anchor),world,anchor,true,stick);
        require(states.get(anchor).get(SIZE)==Size.STANDARD,"World border must prevent growth");
        border.setSize(previousSize);
        player.sneaking=true;
        states.put(anchor,unrelated.getDefaultState());
        player.message=null;
        require(CapitalDebugControls.warnBlockedEmptyHandResize(player,world,false,Hand.MAIN_HAND,hit)==ActionResult.PASS
                && player.message==null,"Non-capitals must retain normal empty-hand interaction");
        System.out.println("ERYDON_CAPITAL_CONTROLS_OK: "+checked+" corrupt-offset/selection/size cases");
        System.out.println("ERYDON_CAPITAL_BLOCKED_OK: "+blocked+" occupied cells warn through empty-hand and debug-stick resizing without changing the footprint; height/world borders protected");
    }
    private static void requireBlockedWarning(Text message) {
        require(message!=null && message.getContent() instanceof TranslatableTextContent text
                && text.getKey().equals("message.erydon.companion_capital_size_blocked"),
                "Blocked resize did not send its localized warning");
    }
    private static void verifyLegacyHandlerOrdering() throws Exception {
        ClassNode node=new ClassNode();
        new ClassReader(Files.readAllBytes(Path.of(".mixin.out/class/net/minecraft/item/DebugStickItem.class"))).accept(node,0);
        int own=-1,legacy=-1;
        for(var method:node.methods) if(method.name.equals("use")) {
            int index=0;
            for(var instruction:method.instructions) {
                if(instruction instanceof MethodInsnNode call) {
                    if(call.name.contains("erydon$companionCapitalControls")) own=index;
                    if(call.name.contains("daedalon$useOrderedRememberedProperty")) legacy=index;
                }
                index++;
            }
        }
        require(own>=0 && legacy>=0 && own<legacy,"ERYDON must intercept capital helpers before the installed companion's handler: "+own+" / "+legacy);
        System.out.println("ERYDON_LEGACY_CAPITAL_ORDER_OK: ERYDON handler "+own+" precedes installed Daedalon handler "+legacy);
    }
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
