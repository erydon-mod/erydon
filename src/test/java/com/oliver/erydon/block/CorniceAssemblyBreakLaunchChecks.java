package com.oliver.erydon.block;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Actual mixed-in server mining retains Fabric cancellation and emits one original-part AFTER event. */
public final class CorniceAssemblyBreakLaunchChecks {
    private static Scope active;
    private static boolean registered;
    private CorniceAssemblyBreakLaunchChecks() { }

    public static void run() throws Exception {
        registerEvents();
        Method factory = CorniceAssemblyLaunchChecks.class.getDeclaredMethod("world");
        factory.setAccessible(true);
        ServerWorld world = (ServerWorld) factory.invoke(null);
        @SuppressWarnings("unchecked") Map<BlockPos, BlockState> states = (Map<BlockPos, BlockState>) field(world, "states").get(world);
        @SuppressWarnings("unchecked") Set<BlockPos> writes = (Set<BlockPos>) field(world, "writes").get(world);
        BlockPos pos = BlockPos.ORIGIN;
        states.put(pos.north(), Blocks.STONE.getDefaultState());
        states.put(pos.west(), Blocks.STONE.getDefaultState());
        states.put(pos.up(), Blocks.STONE.getDefaultState());
        BlockState selected = Registries.BLOCK.get(new Identifier("erydon", "glacium_cornice_modern")).getDefaultState()
                .with(CorniceBlock.FACING, Direction.NORTH);
        BlockState retained = Registries.BLOCK.get(new Identifier("erydon", "glacium_cornice_georgian")).getDefaultState()
                .with(CorniceBlock.FACING, Direction.WEST);
        BlockState cover = CorniceAssemblyLaunchChecks.coverBlock().getDefaultState()
                .with(CoverBlock.ATTACH, Direction.UP).with(CoverBlock.SIZE, CoverBlock.CoverSize.SMALL);
        require(CorniceAssembly.add(world, pos, selected) && CorniceAssembly.add(world, pos, retained)
                && CorniceAssembly.add(world, pos, cover), "Mining fixture could not create three paid parts");
        CorniceAssembly.Contents original = CorniceAssembly.contents(world, pos);
        BlockState carrier = world.getBlockState(pos);
        BlockEntity originalEntity = world.getBlockEntity(pos);
        // preLaunch runs before Minecraft assigns raw IDs to newly registered fixture states.
        for (BlockState state : List.of(selected, carrier)) if (Block.STATE_IDS.getRawId(state) < 0) Block.STATE_IDS.add(state);
        require(Block.getRawIdFromState(selected) != Block.getRawIdFromState(carrier),
                "Original-part break effect cannot be distinguished from the carrier's raw state ID");
        TestPlayer player = DoubleColumnRepairLaunchProbe.allocate(TestPlayer.class);
        player.fixture = world;
        player.held = new ItemStack(Items.STICK);
        player.creative = true;
        player.yaw = 180;
        TestNetwork network = DoubleColumnRepairLaunchProbe.allocate(TestNetwork.class);
        player.networkHandler = network;
        Field position = Entity.class.getDeclaredField("pos"); position.setAccessible(true);
        position.set(player, new Vec3d(.65, .95, 1.5));
        Vec3d start = player.getEyePos(), end = start.add(player.getRotationVec(1).multiply(6));
        int index = CorniceAssembly.partIndexAt(world, pos, start, end);
        require(index >= 0 && original.parts().get(index) == selected,
                "Mining ray did not distinguish the north original part from its shared west arm/cover");
        survivalDeltas(world, pos, player, selected, retained, position);
        ServerPlayerInteractionManager manager = new ServerPlayerInteractionManager(player);
        Field mode = ServerPlayerInteractionManager.class.getDeclaredField("gameMode"); mode.setAccessible(true);
        mode.set(manager, GameMode.CREATIVE);
        Scope scope = new Scope(world, player, pos, carrier, originalEntity, selected);
        active = scope;
        try {
            writes.clear();
            field(world, "modificationAllowed").setBoolean(world, false);
            require(!manager.tryBreakBlock(pos), "Protected shared cell allowed partial breaking");
            require(scope.before == 0 && scope.after == 0 && scope.canceled == 0 && writes.isEmpty()
                            && network.packets == 0 && CorniceAssembly.contents(world, pos).equals(original),
                    "Permission rejection ran break events, mutated paid contents or wrote a block");

            field(world, "modificationAllowed").setBoolean(world, true);
            scope.cancel = true;
            require(!manager.tryBreakBlock(pos), "Fabric BEFORE cancellation was bypassed by partial breaking");
            require(scope.before == 1 && scope.canceled == 1 && scope.after == 0 && writes.isEmpty()
                            && network.packets == 27 && CorniceAssembly.contents(world, pos).equals(original),
                    "Canceled shared break mutated a component or emitted AFTER");
            require(field(world, "breakEvents").getInt(world) == 0 && field(world, "events").getInt(world) == 0,
                    "Canceled shared break emitted vanilla particles or destruction events");

            scope.cancel = false;
            require(manager.tryBreakBlock(pos), "Allowed partial breaking did not succeed through actual server manager");
            require(scope.before == 2 && scope.canceled == 1 && scope.after == 1,
                    "Allowed shared break did not fire exactly one BEFORE and original-part AFTER");
            require(network.packets == 27, "Allowed break repeated Fabric's canceled-neighbour resynchronization");
            require(CorniceAssembly.contents(world, pos).equals(new CorniceAssembly.Contents(List.of(retained), original.cover())),
                    "Breaking the selected moulding removed another paid cornice or ceiling skin");
            require(world.getBlockState(pos).getBlock() instanceof CorniceAssemblyBlock
                            && world.getBlockEntity(pos) == originalEntity && writes.isEmpty(),
                    "Partial breaking removed/replaced the shared owner instead of updating its attachment");
            require(field(world, "breakEvents").getInt(world) == 1
                            && field(world, "lastBreakRawId").getInt(world) == Block.getRawIdFromState(selected)
                            && field(world, "events").getInt(world) == 1,
                    "Partial breaking omitted/duplicated the original part's particles or destruction event");
            require(player.held.getCount() == 1 && player.held.isOf(Items.STICK), "Creative partial breaking changed its held item");
            require(states.get(pos.up()).isOf(Blocks.STONE) && states.get(pos.north()).isOf(Blocks.STONE)
                            && states.get(pos.west()).isOf(Blocks.STONE), "Partial breaking wrote a support or ceiling");
        } finally {
            active = null;
        }
        System.out.println("ERYDON_CORNICE_ASSEMBLY_BREAK_OK: actual server tryBreakBlock rejects permissions and Fabric BEFORE cancellation; 27 real Fabric cancellation-resync packets captured by a network sink; one original-part AFTER, particle and destruction event; other paid cornice/cover retained; four survival mining deltas follow the aimed original part");
    }

    private static void survivalDeltas(ServerWorld world, BlockPos pos, TestPlayer player, BlockState north,
                                       BlockState west, Field position) throws ReflectiveOperationException {
        player.creative = false;
        for (BlockState selected : List.of(north, west)) {
            boolean isNorth = selected == north;
            position.set(player, isNorth ? new Vec3d(.65, .95, 1.5) : new Vec3d(1.5, .95, .65));
            player.yaw = isNorth ? 180 : 90;
            player.fastPart = selected;
            for (boolean harvest : new boolean[]{false, true}) {
                player.harvestable = harvest ? selected : null;
                float expected = selected.calcBlockBreakingDelta(player, world, pos);
                player.lastSpeed = null; player.lastHarvest = null;
                float actual = world.getBlockState(pos).calcBlockBreakingDelta(player, world, pos);
                require(expected > 0 && Float.isFinite(actual) && Math.abs(actual - expected) < .0000001F
                                && player.lastSpeed == selected && player.lastHarvest == selected,
                        "Survival mining speed/harvest divisor used the carrier instead of the aimed original part");
            }
        }
        player.creative = true;
        player.harvestable = null;
        player.yaw = 180;
        position.set(player, new Vec3d(.65, .95, 1.5));
    }

    private static void registerEvents() {
        if (registered) return;
        registered = true;
        // This probe precedes mod initializers: install the pinned Fabric cancellation router explicitly.
        new net.fabricmc.fabric.impl.event.interaction.InteractionEventsRouter().onInitialize();
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, entity) -> {
            Scope scope = scope(world, player, pos);
            if (scope == null) return true;
            require(state == scope.carrier && entity == scope.entity, "Fabric BEFORE did not receive the original shared carrier/entity");
            scope.before++;
            return !scope.cancel;
        });
        PlayerBlockBreakEvents.CANCELED.register((world, player, pos, state, entity) -> {
            Scope scope = scope(world, player, pos);
            if (scope == null) return;
            require(state == scope.carrier && entity == scope.entity, "Fabric cancellation lost the original shared carrier/entity");
            scope.canceled++;
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, entity) -> {
            Scope scope = scope(world, player, pos);
            if (scope == null) return;
            require(state == scope.selected && entity == null, "Fabric AFTER exposed the carrier rather than the removed original part");
            require(!CorniceAssembly.contents(world, pos).cornices().contains(scope.selected), "Fabric AFTER fired before the selected part was removed");
            scope.after++;
        });
    }

    private static Scope scope(World world, net.minecraft.entity.player.PlayerEntity player, BlockPos pos) {
        Scope scope = active;
        return scope != null && scope.world == world && scope.player == player && scope.pos.equals(pos) ? scope : null;
    }

    private static Field field(Object instance, String name) throws ReflectiveOperationException {
        Field field = instance.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }

    private static final class Scope {
        final ServerWorld world;
        final TestPlayer player;
        final BlockPos pos;
        final BlockState carrier, selected;
        final BlockEntity entity;
        boolean cancel;
        int before, canceled, after;
        Scope(ServerWorld world, TestPlayer player, BlockPos pos, BlockState carrier, BlockEntity entity, BlockState selected) {
            this.world = world; this.player = player; this.pos = pos; this.carrier = carrier; this.entity = entity; this.selected = selected;
        }
    }

    /** The real manager and final eye/rotation methods run; unrelated network/player storage stays absent. */
    private static final class TestPlayer extends ServerPlayerEntity {
        ServerWorld fixture;
        ItemStack held;
        BlockState fastPart, harvestable, lastSpeed, lastHarvest;
        boolean creative = true;
        float yaw = 180;
        private TestPlayer() { super(null, null, null); }
        @Override public ServerWorld getServerWorld() { return fixture; }
        @Override public World getWorld() { return fixture; }
        @Override public ItemStack getMainHandStack() { return held; }
        @Override public boolean isBlockBreakingRestricted(World world, BlockPos pos, GameMode mode) { return false; }
        @Override public boolean canHarvest(BlockState state) { lastHarvest = state; return state == harvestable; }
        @Override public float getBlockBreakingSpeed(BlockState state) { lastSpeed = state; return state == fastPart ? 9 : .1F; }
        @Override public boolean isCreative() { return creative; }
        @Override public boolean isSpectator() { return false; }
        @Override public double getEyeY() { return .95; }
        @Override public float getPitch(float tickDelta) { return 0; }
        @Override public float getYaw(float tickDelta) { return yaw; }
    }

    /** Capture the pinned router's actual packet path without opening a socket or allocating player storage. */
    private static final class TestNetwork extends ServerPlayNetworkHandler {
        int packets;
        private TestNetwork() { super(null, null, null); }
        @Override public void sendPacket(Packet<?> packet) {
            require(packet instanceof BlockUpdateS2CPacket, "Canceled shared break emitted an unexpected packet type");
            packets++;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
