package com.oliver.erydon.block;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.item.AutomaticItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

import static com.oliver.erydon.block.WindowArchBlock.*;

/** Persisted arch controls, real shapes and cluster operations in the existing isolated launch probe. */
public final class WindowNoSillLaunchChecks {
    private WindowNoSillLaunchChecks() { }

    public static void run() throws Exception {
        var block = Registry.register(Registries.BLOCK, new Identifier("erydon", "aganite_window_arch"),
                new WindowArchBlock(AbstractBlock.Settings.create()));
        require(block.getDefaultState().get(SILL_ENABLED), "Old arch windows must retain the sill by default");
        require(block.getStateManager().getStates().size() == 512, "Unexpected arch state expansion");
        int states = 0, clusters = 0;
        for (BlockState state : block.getStateManager().getStates()) {
            var saved = NbtHelper.fromBlockState(state);
            require(NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), saved) == state,
                    "Arch sill visibility did not survive persistence");
            if (state.get(SILL_ENABLED)) {
                saved.getCompound("Properties").remove("sill_enabled");
                require(NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), saved) == state,
                        "A published arch state without sill_enabled lost its original appearance");
            } else {
                BlockState frameOnly = state.with(SILL, false).with(SILL_ENABLED, true);
                VoxelShape expected = block.getCollisionShape(frameOnly, null, BlockPos.ORIGIN, null);
                same(block.getCollisionShape(state, null, BlockPos.ORIGIN, null), expected,
                        "No-sill Arch collision differs from its native jamb feet/opening");
                same(block.getOutlineShape(state, null, BlockPos.ORIGIN, null), expected,
                        "No-sill Arch outline differs from its native jamb feet/opening");
                VoxelShape particles = expected.isEmpty() ? expected : VoxelShapes.cuboid(expected.getBoundingBox());
                same(block.getBreakParticleShape(state), particles,
                        "No-sill Arch particles differ from its authored frame bounds");
            }
            require(block.getCollisionShape(state, null, BlockPos.ORIGIN, null)
                            == block.getCollisionShape(state, null, BlockPos.ORIGIN, null),
                    "Arch sill option stopped reusing cached shapes");
            same(block.getRaycastShape(state, null, BlockPos.ORIGIN), VoxelShapes.fullCube(),
                    "An empty arch opening lost easy targeting");
            for (BlockMirror mirror : BlockMirror.values()) {
                BlockState mirrored = block.mirror(state, mirror);
                require(mirrored.get(SILL_ENABLED) == state.get(SILL_ENABLED)
                                && block.mirror(mirrored, mirror) == state,
                        "Arch mirroring changed the sill option or its reversible piece mapping");
            }
            for (BlockRotation rotation : BlockRotation.values())
                require(block.rotate(state, rotation).get(SILL_ENABLED) == state.get(SILL_ENABLED),
                        "Arch rotation changed the sill option");
            states++;
        }
        for (Direction facing : Direction.Type.HORIZONTAL) for (boolean enabled : new boolean[]{false, true}) {
            var world = WindowFrenchGeorgianLaunchProbe.world();
            BlockPos anchor = new BlockPos(0, 64, 0);
            Direction right = facing.rotateYClockwise();
            for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++)
                world.states.put(anchor.offset(right, x).up(y), block.getDefaultState().with(FACING, facing)
                        .with(SILL_ENABLED, enabled).with(GLASS, Glass.TWO_WAY).with(OPEN, true));
            require(block.recalcCluster(world, anchor).recalculated(), "Arch cluster did not recalculate");
            require(world.states.values().stream().allMatch(s -> s.get(SILL_ENABLED) == enabled),
                    "Arch reflow reset the selected sill option");
            for (var entry : world.states.entrySet())
                require(entry.getValue().get(SILL) == (entry.getKey().getY() == anchor.getY()),
                        "Arch no-sill option corrupted automatic bottom-row metadata");
            for (Direction extension : new Direction[]{Direction.UP, right}) {
                BlockPos target = extension == Direction.UP ? anchor.up(3) : anchor.offset(right, 3);
                var context = new AutomaticItemPlacementContext(world, target, facing.getOpposite(),
                        new ItemStack(Items.STONE), extension);
                BlockState placed = block.getPlacementState(context);
                require(placed != null && placed.get(SILL_ENABLED) == enabled
                                && placed.get(GLASS) == Glass.TWO_WAY && placed.get(OPEN),
                        "A new arch window did not inherit its neighbour's sill/glass/open controls");
            }
            BlockState before = world.getBlockState(anchor), after = before.with(SILL_ENABLED, !enabled);
            world.states.put(anchor, after);
            block.onBlockAdded(after, world, anchor, before, false);
            require(world.states.values().stream().allMatch(s -> s.get(SILL_ENABLED) != enabled),
                    "Arch debug edit did not synchronise the connected cluster");
            require(block.recalcCluster(world, anchor).recalculated(), "Arch reflow after debug edit failed");
            require(world.states.values().stream().allMatch(s -> s.get(SILL_ENABLED) != enabled
                            && s.get(GLASS) == Glass.TWO_WAY && s.get(OPEN)),
                    "Arch reflow lost sill selection or existing glass/open options");
            clusters++;
        }
        System.out.println("ERYDON_ARCH_NO_SILL_OK states=" + states + " clusters=" + clusters
                + " legacyDefault=preserved persistence=exact shapes=cached transforms=preserved inheritance+reflow+debugSync=passed");
    }

    private static VoxelShape rail(Direction facing) {
        boolean alongX = facing.getAxis() == Direction.Axis.Z;
        return VoxelShapes.cuboid(alongX ? 0 : 6.516 / 16, 0, alongX ? 6.516 / 16 : 0,
                alongX ? 1 : 9.484 / 16, .5 / 16, alongX ? 9.484 / 16 : 1);
    }

    private static void same(VoxelShape a, VoxelShape b, String message) {
        require(!VoxelShapes.matchesAnywhere(a, b, BooleanBiFunction.NOT_SAME), message);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
