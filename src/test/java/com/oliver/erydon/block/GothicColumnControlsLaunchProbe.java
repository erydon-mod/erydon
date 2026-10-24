package com.oliver.erydon.block;

import com.oliver.erydon.HighPolishSettings;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.oliver.erydon.block.ColumnBlock.*;

/** Calls the real onUse/state callbacks before client startup; no window or save. */
public final class GothicColumnControlsLaunchProbe implements PreLaunchEntrypoint {
    @Override public void onPreLaunch() {
        try {
            SharedConstants.createGameVersion();
            Bootstrap.initialize();
            Box bareBounds = rawPillarBounds();
            int cases = 0;
            Player player = allocate(Player.class);
            player.held = ItemStack.EMPTY;
            for (String family : List.of("gothic", "square", "circular")) {
                List<String> prefixes = family.equals("gothic")
                        ? HighPolishSettings.MATERIALS.stream().flatMap(material ->
                                java.util.stream.Stream.of(material, material + "_aged")).toList()
                        : List.of("control_probe");
                for (String prefix : prefixes) {
                    ColumnBlock block = Registry.register(Registries.BLOCK,
                            new Identifier("erydon", prefix + "_column_" + family),
                            new ColumnBlock(Block.Settings.copy(Blocks.STONE)));
                    for (Direction side : Direction.Type.HORIZONTAL) for (Orientation orientation : Orientation.values()) {
                        TestWorld world = allocate(TestWorld.class);
                        world.states = new HashMap<>();
                        for (int y = 0; y < 3; y++) world.states.put(new BlockPos(0, y, 0), block.getDefaultState()
                                .with(PART, y == 0 ? ColumnPart.BASE : y == 2 ? ColumnPart.CAPITAL : ColumnPart.PILLAR)
                                .with(CAPITAL_ORIENTATION, orientation));
                        CapitalStyle[] cycle = family.equals("circular")
                                ? new CapitalStyle[]{CapitalStyle.GUILLOCHE, CapitalStyle.NARROW, CapitalStyle.NONE, CapitalStyle.GEORGIAN}
                                : new CapitalStyle[]{CapitalStyle.GUILLOCHE, CapitalStyle.NONE, CapitalStyle.GEORGIAN};
                        BlockPos top = new BlockPos(0, 2, 0);
                        for (CapitalStyle expected : cycle) {
                            require(block.onUse(world.getBlockState(top), world, top, player, Hand.MAIN_HAND,
                                    hit(top, side, .7)) == ActionResult.SUCCESS, "Capital side-click was not handled: " + family);
                            for (BlockState state : world.states.values()) {
                                require(state.get(CAPITAL) == expected, "Side-click lost the selected capital in the stack");
                                require(state.get(BASE) == BaseStyle.FULL && state.get(CAPITAL_ORIENTATION) == orientation,
                                        "Capital cycling changed another option");
                                require(NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), NbtHelper.fromBlockState(state)) == state,
                                        "Column capital failed a saved-state round trip");
                            }
                            BlockState state = world.getBlockState(top);
                            require(withRecalculatedPart(state, true, false).get(CAPITAL) == expected,
                                    "Structural recalculation reset the capital option");
                            if (family.equals("gothic") && expected == CapitalStyle.NONE) {
                            var collision = state.getCollisionShape(world, top);
                            Box bounds = collision.getBoundingBox();
                            require(Math.abs(bounds.minX - bareBounds.minX) < 1e-8 && Math.abs(bounds.maxX - bareBounds.maxX) < 1e-8
                                            && Math.abs(bounds.minZ - bareBounds.minZ) < 1e-8 && Math.abs(bounds.maxZ - bareBounds.maxZ) < 1e-8,
                                    "Bare Gothic collision retains the invisible head's footprint");
                            require(collision == state.getOutlineShape(world, top), "Bare top outline differs from cached collision");
                            }
                            cases++;
                        }
                        BlockState before = world.getBlockState(top);
                        require(block.onUse(before, world, top, player, Hand.MAIN_HAND, hit(top, Direction.UP, 1)) == ActionResult.PASS,
                                "Top click intercepted ordinary block placement");
                        player.held = Items.DEBUG_STICK.getDefaultStack();
                        require(block.onUse(before, world, top, player, Hand.MAIN_HAND, hit(top, side, .7)) == ActionResult.PASS,
                                "Vanilla debug-stick cycling was intercepted");
                        player.held = ItemStack.EMPTY;
                        if (family.equals("gothic")) {
                            require(block.onUse(world.getBlockState(BlockPos.ORIGIN), world, BlockPos.ORIGIN, player,
                                    Hand.MAIN_HAND, hit(BlockPos.ORIGIN, side, .2)) == ActionResult.PASS,
                                    "Fixed Gothic base acquired a nonfunctional narrow option");
                        }
                    }
                }
            }
            System.out.println("ERYDON_GOTHIC_COLUMN_CONTROLS_OK cases=" + cases
                    + " GothicFinishes=54 sideClicks=all4 cycle=georgian,guilloche,none savedStates=preserved recalc=preserved");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static BlockHitResult hit(BlockPos pos, Direction side, double height) {
        return new BlockHitResult(new Vec3d(pos.getX() + .5, pos.getY() + height, pos.getZ() + .5), side, pos, false);
    }

    private static Box rawPillarBounds() throws Exception {
        String path = "assets/erydon/authoring_models/block/column/gothic/column_gothic_pillar.json";
        double minX = Double.POSITIVE_INFINITY, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;
        try (var input = GothicColumnControlsLaunchProbe.class.getClassLoader().getResourceAsStream(path)) {
            require(input != null, "Missing authored Gothic pillar for collision proof");
            var document = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var raw : document.getAsJsonArray("elements")) {
                var element = raw.getAsJsonObject();
                var from = element.getAsJsonArray("from");
                var to = element.getAsJsonArray("to");
                var rotation = element.getAsJsonObject("rotation");
                require(rotation == null || "y".equals(rotation.get("axis").getAsString()), "Unexpected Gothic shaft rotation");
                double angle = rotation == null ? 0 : Math.toRadians(rotation.get("angle").getAsDouble());
                double ox = rotation == null ? 8 : rotation.getAsJsonArray("origin").get(0).getAsDouble();
                double oz = rotation == null ? 8 : rotation.getAsJsonArray("origin").get(2).getAsDouble();
                for (double x : new double[]{from.get(0).getAsDouble(), to.get(0).getAsDouble()}) {
                    for (double z : new double[]{from.get(2).getAsDouble(), to.get(2).getAsDouble()}) {
                        double rx = (ox + (x - ox) * Math.cos(angle) + (z - oz) * Math.sin(angle)) / 16;
                        double rz = (oz - (x - ox) * Math.sin(angle) + (z - oz) * Math.cos(angle)) / 16;
                        minX = Math.min(minX, rx); maxX = Math.max(maxX, rx);
                        minZ = Math.min(minZ, rz); maxZ = Math.max(maxZ, rz);
                    }
                }
            }
        }
        return new Box(minX, 0, minZ, maxX, 1, maxZ);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }

    private static final class TestWorld extends ServerWorld {
        Map<BlockPos, BlockState> states;
        private TestWorld() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.getDefaultState()); }
        @Override public boolean setBlockState(BlockPos pos, BlockState state, int flags) {
            states.put(pos.toImmutable(), state);
            return true;
        }
    }

    private static final class Player extends ServerPlayerEntity {
        ItemStack held;
        private Player() { super(null, null, null); }
        @Override public ItemStack getStackInHand(Hand hand) { return held; }
        @Override public boolean isSneaking() { return false; }
        @Override public void sendMessage(Text text, boolean overlay) { }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
