package com.oliver.erydon.block;

import com.mojang.authlib.GameProfile;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.DebugStickItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldAccess;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Exercise the actual mixed-in debug stick, including its remembered-property behaviour. */
public final class WindowDebugControlsLaunchChecks {
    private WindowDebugControlsLaunchChecks() { }

    public static void run(WindowFrenchGeorgianBlock french) throws Exception {
        var arch = Registry.register(Registries.BLOCK, new Identifier("erydon", "window_debug_probe_arch"),
                new WindowArchBlock(Block.Settings.copy(Blocks.STONE)));
        Map<BlockPos, BlockState> states = new HashMap<>();
        WorldAccess world = (WorldAccess) Proxy.newProxyInstance(WorldAccess.class.getClassLoader(),
                new Class<?>[]{WorldAccess.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getBlockState" -> states.get(args[0]);
                    case "setBlockState" -> { states.put((BlockPos) args[0], (BlockState) args[1]); yield true; }
                    default -> throw new AssertionError("Unexpected debug-stick world call " + method.getName());
                });
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Player player = (Player) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(Player.class);
        var use = DebugStickItem.class.getDeclaredMethod("use", PlayerEntity.class, BlockState.class,
                WorldAccess.class, BlockPos.class, boolean.class, ItemStack.class);
        use.setAccessible(true);
        for (Block block : new Block[]{french, arch}) {
            String id = Registries.BLOCK.getId(block).toString();
            for (boolean staleInternal : new boolean[]{false, true}) {
                states.put(BlockPos.ORIGIN, block.getDefaultState());
                ItemStack stick = Items.DEBUG_STICK.getDefaultStack();
                if (staleInternal) stick.getOrCreateSubNbt("DebugProperty").putString(id, "piece");
                use.invoke(Items.DEBUG_STICK, player, states.get(BlockPos.ORIGIN), world, BlockPos.ORIGIN, false, stick);
                require(stick.getOrCreateSubNbt("DebugProperty").getString(id).equals("glass"),
                        "Glass was not the first visible window control");
                use.invoke(Items.DEBUG_STICK, player, states.get(BlockPos.ORIGIN), world, BlockPos.ORIGIN, true, stick);
                require(states.get(BlockPos.ORIGIN).get(WindowArchBlock.GLASS) == WindowArchBlock.Glass.TWO_WAY,
                        "First window control did not enable two-way glass");
                if (block == french) {
                    use.invoke(Items.DEBUG_STICK, player, states.get(BlockPos.ORIGIN), world, BlockPos.ORIGIN, false, stick);
                    require(stick.getOrCreateSubNbt("DebugProperty").getString(id).equals("sill_side"),
                            "Sill side did not follow the glass control");
                    use.invoke(Items.DEBUG_STICK, player, states.get(BlockPos.ORIGIN), world, BlockPos.ORIGIN, true, stick);
                    require(states.get(BlockPos.ORIGIN).get(WindowFrenchGeorgianBlock.SILL_SIDE)
                                    == WindowFrenchGeorgianBlock.SillSide.INWARD,
                            "The sill control did not select inward");
                }
            }
        }
        ItemStack ordinaryStick = Items.DEBUG_STICK.getDefaultStack();
        BlockState ordinary = Blocks.OAK_STAIRS.getDefaultState();
        states.put(BlockPos.ORIGIN, ordinary);
        use.invoke(Items.DEBUG_STICK, player, ordinary, world, BlockPos.ORIGIN, false, ordinaryStick);
        require(ordinaryStick.getOrCreateSubNbt("DebugProperty").getString("minecraft:oak_stairs")
                        .equals(ordinary.getBlock().getStateManager().getProperties().iterator().next().getName()),
                "An unrelated block's debug controls changed");
        System.out.println("ERYDON_WINDOW_DEBUG_CONTROLS_OK glass=first sill_side=second staleInternal=reset unrelated=preserved");
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class Player extends ServerPlayerEntity {
        private Player(MinecraftServer server, ServerWorld world, GameProfile profile) { super(server, world, profile); }
        @Override public boolean isCreativeLevelTwoOp() { return true; }
        @Override public boolean shouldCancelInteraction() { return false; }
        @Override public void sendMessage(Text message, boolean overlay) { }
        @Override public void sendMessageToClient(Text message, boolean overlay) { }
    }
}
