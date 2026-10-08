package com.oliver.erydon.block;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.EmptyBlockView;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Dedicated-server smoke proof; exits before creating a server, world, window or save. */
public final class GlazingSlopeServerLaunchProbe implements PreLaunchEntrypoint {
    private static final List<String> SHAPES = List.of(
            "straight", "inner_left", "inner_right", "outer_left", "outer_right");

    @Override public void onPreLaunch() {
        try {
            require(FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER,
                    "Glazing server probe must run through KnotServer");
            SharedConstants.createGameVersion();
            Bootstrap.initialize();
            run();
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    public static void run() throws Exception {
        for (Class<?> type : List.of(GlazingSlopeGeometry.class, GlazingSlopeGeometry.Template.class,
                GlazingSlopeGeometry.Profile.class, GlazingSlopeBlock.class, GlazingShallowSlopeBlock.class)) {
            assertNoClientReference(type);
        }
        Block[] blocks = {
                new GlazingSlopeBlock(AbstractBlock.Settings.copy(Blocks.GLASS)),
                new GlazingShallowSlopeBlock(AbstractBlock.Settings.copy(Blocks.GLASS), GlazingShallowSlopeBlock.Variant.LOWER),
                new GlazingShallowSlopeBlock(AbstractBlock.Settings.copy(Blocks.GLASS), GlazingShallowSlopeBlock.Variant.UPPER)
        };
        int poses = 0, blockShapes = 0, shallowHalfAliases = 0;
        for (GlazingSlopeGeometry.Profile profile : GlazingSlopeGeometry.Profile.values()) {
            Block block = blocks[profile.ordinal()];
            List<BlockHalf> halves = profile == GlazingSlopeGeometry.Profile.STANDARD
                    ? List.of(BlockHalf.BOTTOM, BlockHalf.TOP) : List.of(BlockHalf.BOTTOM);
            for (Direction facing : Direction.Type.HORIZONTAL) for (String corner : SHAPES) for (BlockHalf half : halves) {
                List<GlazingSlopeGeometry.Face> faces = GlazingSlopeGeometry.faces(profile, facing, corner, half);
                VoxelShape shape = GlazingSlopeGeometry.shape(profile, facing, corner, half);
                require(!faces.isEmpty() && !shape.isEmpty(), "Empty server glazing geometry: " + profile + " " + corner);
                require(faces == GlazingSlopeGeometry.faces(profile, facing, corner, half), "Faces were not cached");
                require(shape == GlazingSlopeGeometry.shape(profile, facing, corner, half), "Shape was not cached");
                var boxes = shape.getBoundingBoxes();
                var expectedBoxes = shape.getBoundingBoxes();
                var bounds = shape.getBoundingBox();
                require(!boxes.isEmpty() && boxes != expectedBoxes, "Shape boxes must be fresh mutable lists");
                boxes.clear();
                require(shape.getBoundingBoxes().equals(expectedBoxes) && shape.getBoundingBox().equals(bounds),
                        "Mutating returned boxes changed the cached geometry");
                for (var face : faces) for (var vertex : face.vertices()) {
                    require(Double.isFinite(vertex.x()) && Double.isFinite(vertex.y()) && Double.isFinite(vertex.z())
                                    && Double.isFinite(vertex.u()) && Double.isFinite(vertex.v()),
                            "Non-finite server glazing vertex");
                }
                BlockState state = block.getDefaultState().with(GlazingSlopeBlock.FACING, facing).with(GlazingSlopeBlock.HALF, half);
                state = profile == GlazingSlopeGeometry.Profile.STANDARD
                        ? state.with(GlazingSlopeBlock.SHAPE, GlazingSlopeBlock.SlopeShape.valueOf(corner.toUpperCase(Locale.ROOT)))
                        : state.with(GlazingShallowSlopeBlock.SHAPE, GlazingShallowSlopeBlock.SlopeShape.valueOf(corner.toUpperCase(Locale.ROOT)));
                require(state.getOutlineShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN, ShapeContext.absent()) == shape,
                        "Block outline did not use the common cached geometry");
                require(state.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN, ShapeContext.absent()) == shape,
                        "Block collision did not use the common cached geometry");
                blockShapes += 2;
                if (profile != GlazingSlopeGeometry.Profile.STANDARD) {
                    require(faces == GlazingSlopeGeometry.faces(profile, facing, corner, BlockHalf.TOP), "Shallow HALF duplicated faces");
                    require(shape == GlazingSlopeGeometry.shape(profile, facing, corner, BlockHalf.TOP), "Shallow HALF duplicated shapes");
                    BlockState legacy = state.with(GlazingSlopeBlock.HALF, BlockHalf.TOP);
                    require(legacy.getOutlineShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN, ShapeContext.absent()) == shape
                                    && legacy.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN, ShapeContext.absent()) == shape,
                            "Legacy shallow HALF changed the registered section");
                    shallowHalfAliases++;
                }
                poses++;
            }
        }
        require(poses == 80 && blockShapes == 160 && shallowHalfAliases == 40, "Incomplete dedicated-server pose matrix");
        System.out.println("ERYDON_GLAZING_SERVER_OK poses=" + poses + " blockShapeChecks=" + blockShapes
                + " shallowHalfAliases=" + shallowHalfAliases);
    }

    private static void assertNoClientReference(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var stream = type.getResourceAsStream(resource)) {
            require(stream != null, "Missing probe class bytes: " + resource);
            String bytes = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
            require(!bytes.contains("net/minecraft/client/") && !bytes.contains("net/fabricmc/fabric/api/renderer/"),
                    "Client-only reference in common glazing class: " + type.getName());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
