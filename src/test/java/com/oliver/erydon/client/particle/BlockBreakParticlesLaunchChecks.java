package com.oliver.erydon.client.particle;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.particle.BlockDustParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.render.block.BlockModels;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.texture.Sprite;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

import java.lang.reflect.Field;

/** Calls the transformed ParticleManager without creating a window, world or graphics context. */
public final class BlockBreakParticlesLaunchChecks {
    private BlockBreakParticlesLaunchChecks() { }

    /** Run after Minecraft bootstrap, while the test registry is still writable. */
    public static void run() throws Exception {
        Field singleton = field(MinecraftClient.class, "instance");
        Object previous = singleton.get(null);
        try {
            MinecraftClient client = allocate(MinecraftClient.class);
            BlockRenderManager renderer = allocate(BlockRenderManager.class);
            field(BlockRenderManager.class, "models").set(renderer, new BlockModels(null) {
                @Override public Sprite getModelParticleSprite(BlockState state) { return null; }
            });
            field(MinecraftClient.class, "blockRenderManager").set(client, renderer);
            field(MinecraftClient.class, "blockColors").set(client, new BlockColors());
            singleton.set(null, client);
            Counter manager = allocate(Counter.class);
            require(count(manager, Blocks.STONE.getDefaultState()) == 64, "Vanilla stone debris changed");
            require(count(manager, fixture("unrelated", "cube", VoxelShapes.fullCube())) == 64,
                    "Other namespaces must retain vanilla debris");
            require(count(manager, fixture("erydon", "cube", VoxelShapes.fullCube())) == 16,
                    "Own namespace must reach the actual ParticleManager cap");
            int otherExpected = FabricLoader.getInstance().isModLoaded("daedalon") ? 16 : 64;
            require(count(manager, fixture("daedalon", "cube", VoxelShapes.fullCube())) == otherExpected,
                    "The other mod's independent mixin must compose without double emission");
            require(count(manager, fixture("erydon", "empty", VoxelShapes.empty())) == 0,
                    "Empty outlines must stay empty");
            require(count(manager, Blocks.AIR.getDefaultState()) == 0, "Air unexpectedly emitted debris");
            VoxelShape fragmented = VoxelShapes.empty();
            for (int i = 0; i < 32; i++) {
                double x = (i % 4) / 4.0, y = (i / 4 % 4) / 4.0, z = (i / 16) / 2.0;
                fragmented = VoxelShapes.union(fragmented,
                        VoxelShapes.cuboid(x, y, z, x + 0.1, y + 0.1, z + 0.1));
            }
            require(fragmented.getBoundingBoxes().size() > 16, "Fragmented regression fixture is trivial");
            require(count(manager, fixture("erydon", "fragmented", fragmented)) <= 16,
                    "Separate outline boxes multiplied the event budget");
            var arch = Registry.register(Registries.BLOCK, new Identifier("erydon", "break_budget_probe_romanesque"),
                    new com.oliver.erydon.block.ArchRomanesqueBlock(Block.Settings.copy(Blocks.STONE).dynamicBounds()));
            int states = 0, maximumOldBoxes = 0;
            for (var arrangement : com.oliver.erydon.block.ArchRomanesqueBlock.Arrangement.values())
                for (var facing : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
                    BlockState state = arch.getDefaultState()
                            .with(com.oliver.erydon.block.ArchRomanesqueBlock.ARRANGEMENT, arrangement)
                            .with(com.oliver.erydon.block.ArchRomanesqueBlock.FACING, facing);
                    VoxelShape shape = state.getOutlineShape(net.minecraft.world.EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
                    maximumOldBoxes = Math.max(maximumOldBoxes, shape.getBoundingBoxes().size());
                    int emitted = count(manager, state);
                    require(shape.isEmpty() ? emitted == 0 : emitted > 0 && emitted <= 16,
                            "Romanesque arch exceeded the break cap: " + state);
                    states++;
                }
            require(maximumOldBoxes > 16, "Romanesque regression must exercise fragmented geometry");
            System.out.println("ERYDON_ROMANESQUE_BREAK_PARTICLES_OK: " + states + " poses; old maximum outline boxes=" + maximumOldBoxes);
            System.out.println("ERYDON_BREAK_PARTICLES_OK: actual mixed-in ParticleManager; own blocks <=16; empty=0; vanilla/foreign=64; other mod loaded=" + FabricLoader.getInstance().isModLoaded("daedalon"));
        } finally {
            singleton.set(null, previous);
        }
    }

    private static BlockState fixture(String namespace, String name, VoxelShape shape) {
        return Registry.register(Registries.BLOCK, new Identifier(namespace, "break_budget_probe_erydon_" + name),
                new Block(Block.Settings.copy(Blocks.STONE).dynamicBounds()) {
                    @Override public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
                        return shape;
                    }
                }).getDefaultState();
    }

    private static int count(Counter manager, BlockState state) {
        manager.count = 0;
        manager.addBlockBreakParticles(new BlockPos(10, 70, -30), state);
        return manager.count;
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
    }
    private static final class Counter extends ParticleManager {
        int count;
        private Counter() { super(null, null); }
        @Override public void addParticle(Particle particle) {
            require(particle instanceof BlockDustParticle, "Break effect changed particle type");
            count++;
        }
    }
}
