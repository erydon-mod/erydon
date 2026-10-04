package com.oliver.erydon.compat.daedalon;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Keeps older companion capital helpers out of the debug stick's editable controls. */
public final class CapitalDebugControls {
    public static final String REMEMBERED_PROPERTY="DaedalonDebugProperty";
    private CapitalDebugControls() { }
    public record Target(BlockPos pos,BlockState state) { }

    public static void register() {
        UseBlockCallback.EVENT.register((player,world,hand,hit) ->
                warnBlockedEmptyHandResize(player,world,world.isClient,hand,hit));
    }

    public static ActionResult warnBlockedEmptyHandResize(PlayerEntity player,WorldAccess world,
                                                          boolean client,Hand hand,BlockHitResult hit) {
        // Let the client send the interaction so the server can display the warning.
        if(client || player.isSpectator() || !player.isSneaking() || !player.getStackInHand(hand).isEmpty()) return ActionResult.PASS;
        BlockPos clicked=hit.getBlockPos();
        Target target=target(world,clicked,world.getBlockState(clicked));
        if(target==null || large(target.state()) || canGrow(world,target.pos())) return ActionResult.PASS;
        player.sendMessage(Text.translatable("message.erydon.companion_capital_size_blocked"),true);
        return ActionResult.FAIL;
    }

    public static boolean handles(BlockState state) {
        var id=Registries.BLOCK.getId(state.getBlock());
        return id.getNamespace().equals("daedalon") && (id.getPath().equals("capital_part")
                || id.getPath().endsWith("_capital") && controls(state).size()==2);
    }
    private static boolean part(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).toString().equals("daedalon:capital_part");
    }
    public static List<Property<?>> controls(BlockState state) {
        var manager=state.getBlock().getStateManager();
        Property<?> size=manager.getProperty("size"),orientation=manager.getProperty("capital_orientation");
        return size==null || orientation==null ? List.of() : List.of(size,orientation);
    }
    private static boolean large(BlockState state) {
        if (!handles(state) || part(state)) return false;
        String size=value(state,controls(state).get(0));
        return size.equals("double") || size.equals("large");
    }
    private static boolean complete(BlockView world,BlockPos anchor) {
        if (!large(world.getBlockState(anchor))) return false;
        for(int y=0;y<2;y++) for(int x=0;x<2;x++) for(int z=0;z<2;z++)
            if(x+y+z>0 && !part(world.getBlockState(anchor.add(x,y,z)))) return false;
        return true;
    }
    public static Target target(BlockView world,BlockPos clicked,BlockState state) {
        if (!handles(state)) return null;
        if (!part(state)) return new Target(clicked,state);
        BlockPos expected=clicked.add(-offset(state,"offset_x"),-offset(state,"offset_y"),-offset(state,"offset_z"));
        if (complete(world,expected)) return new Target(expected,world.getBlockState(expected));
        BlockPos found=null;
        // A complete 2x2x2 footprint cannot borrow another capital's occupied anchor.
        for(int y=0;y<2;y++) for(int x=0;x<2;x++) for(int z=0;z<2;z++) {
            BlockPos candidate=clicked.add(-x,-y,-z);
            if(candidate.equals(expected) || !complete(world,candidate)) continue;
            if(found!=null) return null;
            found=candidate;
        }
        return found==null ? null : new Target(found,world.getBlockState(found));
    }
    private static int offset(BlockState state,String name) {
        var property=state.getBlock().getStateManager().getProperty(name);
        if(property==null) return 0;
        return Integer.parseInt(value(state,property));
    }
    private static void repairParts(WorldAccess world,Target target) {
        if(!large(target.state())) return;
        for(int y=0;y<2;y++) for(int x=0;x<2;x++) for(int z=0;z<2;z++) {
            if(x+y+z==0) continue;
            BlockPos pos=target.pos().add(x,y,z);
            BlockState state=world.getBlockState(pos);
            if(!part(state)) continue;
            BlockState corrected=withNamedValue(withNamedValue(withNamedValue(state,"offset_x",x),"offset_y",y),"offset_z",z);
            if(corrected!=state) world.setBlockState(pos,corrected,Block.NOTIFY_LISTENERS);
        }
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static BlockState withNamedValue(BlockState state,String name,Integer next) {
        Property property=state.getBlock().getStateManager().getProperty(name);
        return property!=null && property.getValues().contains(next) ? state.with(property,next) : state;
    }
    public static Property<?> select(BlockState state,String remembered,boolean update,boolean backwards) {
        List<Property<?>> controls=controls(state);
        if(controls.isEmpty()) return null;
        int index=0;
        for(int i=0;i<controls.size();i++) if(controls.get(i).getName().equals(remembered)) index=i;
        return update ? controls.get(index) : controls.get(Math.floorMod(index+(backwards ? -1 : 1),controls.size()));
    }
    private static boolean canGrow(WorldAccess world,BlockPos anchor) {
        for(int y=0;y<2;y++) for(int x=0;x<2;x++) for(int z=0;z<2;z++) {
            if(x+y+z==0) continue;
            BlockPos pos=anchor.add(x,y,z);
            if(world.isOutOfHeightLimit(pos) || !world.getWorldBorder().contains(pos)) return false;
            BlockState existing=world.getBlockState(pos);
            if(!existing.isAir() && !(part(existing) && pos.add(-offset(existing,"offset_x"),
                    -offset(existing,"offset_y"),-offset(existing,"offset_z")).equals(anchor))) return false;
        }
        return true;
    }
    public static boolean use(PlayerEntity player,BlockState clickedState,WorldAccess world,BlockPos clicked,
                              boolean update,ItemStack stack) {
        if(!player.isCreativeLevelTwoOp()) return false;
        Target target=target(world,clicked,clickedState);
        if(target==null) return false;
        repairParts(world,target);
        Property<?> selected=select(target.state(),stack.getOrCreateNbt().getString(REMEMBERED_PROPERTY),update,player.shouldCancelInteraction());
        if(selected==null) return false;
        stack.getOrCreateNbt().putString(REMEMBERED_PROPERTY,selected.getName());
        var vanilla=stack.getOrCreateSubNbt("DebugProperty");
        vanilla.putString(Registries.BLOCK.getId(target.state().getBlock()).toString(),selected.getName());
        vanilla.putString("daedalon:capital_part",selected.getName());
        BlockState displayed=target.state();
        if(update) {
            BlockState next=cycle(displayed,selected,player.shouldCancelInteraction());
            if(selected.getName().equals("size") && !large(displayed) && large(next) && !canGrow(world,target.pos())) {
                ((ServerPlayerEntity)player).sendMessageToClient(Text.translatable("message.erydon.companion_capital_size_blocked"),true);
                return true;
            }
            world.setBlockState(target.pos(),next,18);
            displayed=world.getBlockState(target.pos());
        }
        Text label=Text.translatable(selected.getName().equals("size") ? "property.erydon.companion_capital_size" : "property.erydon.companion_capital_orientation");
        String raw=value(displayed,selected);
        Text option=Text.translatable(selected.getName().equals("size")
                ? "option.erydon.companion_capital_size."+(raw.equals("double") || raw.equals("large") ? "large" : "small")
                : "option.daedalon.capital.orientation."+raw);
        ((ServerPlayerEntity)player).sendMessageToClient(Text.translatable(update ? "item.minecraft.debug_stick.update" : "item.minecraft.debug_stick.select",label,option),true);
        return true;
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    public static BlockState cycle(BlockState state,Property property,boolean backwards) {
        List<Comparable> values=new ArrayList<>(property.getValues());
        values.sort(Comparator.comparingInt(v -> rank(property.name(v))));
        int index=values.indexOf(state.get(property));
        return state.with(property,values.get(Math.floorMod(index+(backwards ? -1 : 1),values.size())));
    }
    private static int rank(String value) {
        return switch(value) { case "standard","small","straight" -> 0; case "double","large","diagonal" -> 1; case "straight_90" -> 2; case "diagonal_135" -> 3; default -> 100; };
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static String value(BlockState state,Property property) { return property.name(state.get(property)); }
}
