package com.oliver.erydon.command;

import com.oliver.erydon.Erydon;
import com.oliver.erydon.util.ClusterRecalcSupport;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SignBlock;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.List;

final class ErydonBlockTypeShowcase {
    private static final int GEORGIAN_WIDTH = 47;
    private static final int GEORGIAN_HEIGHT = 7;
    private static final int GEORGIAN_DEPTH = 20;
    private static final int GEORGIAN_TYPE_COUNT = 6;
    private static final Identifier GEORGIAN_TEMPLATE_ID =
            new Identifier(Erydon.MOD_ID, "showcase/blocktypes/georgian");

    private static final List<String> REQUIRED_GEORGIAN_BLOCK_IDS = List.of(
            "glacium_block",
            "glacium_slope",
            "glacium_wall_georgian",
            "glacium_alcove_georgian",
            "glacium_cornice_georgian",
            "glacium_surround_georgian",
            "glacium_window_french_georgian",
            "glacium_ceiling_coffered_georgian_white_small",
            "glacium_ceiling_coffered_georgian_black_small"
    );

    private static final List<ShowcaseSign> GEORGIAN_SIGNS = List.of(
            new ShowcaseSign(5, 1, 3,
                    "showcase.erydon.blocktypes.georgian.wall.line1",
                    "showcase.erydon.blocktypes.georgian.wall.line2"),
            new ShowcaseSign(4, 1, 13,
                    "showcase.erydon.blocktypes.georgian.alcove.line1",
                    "showcase.erydon.blocktypes.georgian.alcove.line2"),
            new ShowcaseSign(12, 1, 13,
                    "showcase.erydon.blocktypes.georgian.surround.line1",
                    "showcase.erydon.blocktypes.georgian.surround.line2"),
            new ShowcaseSign(21, 1, 13,
                    "showcase.erydon.blocktypes.georgian.window.line1",
                    "showcase.erydon.blocktypes.georgian.window.line2",
                    "showcase.erydon.blocktypes.georgian.window.line3"),
            new ShowcaseSign(27, 1, 13,
                    "showcase.erydon.blocktypes.georgian.cornice.line1",
                    "showcase.erydon.blocktypes.georgian.cornice.line2"),
            new ShowcaseSign(38, 1, 4,
                    "showcase.erydon.blocktypes.georgian.ceiling.line1",
                    "showcase.erydon.blocktypes.georgian.ceiling.line2",
                    "showcase.erydon.blocktypes.georgian.ceiling.line3"),
            new ShowcaseSign(25, 1, 3,
                    "showcase.erydon.blocktypes.georgian.title.line1",
                    "showcase.erydon.blocktypes.georgian.title.line2",
                    "showcase.erydon.blocktypes.georgian.title.line3")
    );

    private ErydonBlockTypeShowcase() {
    }

    static ShowcasePlacementPlan buildGeorgianPlan() {
        for (String path : REQUIRED_GEORGIAN_BLOCK_IDS) {
            if (!Registries.BLOCK.containsId(new Identifier(Erydon.MOD_ID, path))) {
                return null;
            }
        }
        return GeorgianPlan.INSTANCE;
    }

    private static void placeGeorgianShowcase(ServerWorld world, BlockPos outerMin) {
        StructureTemplate template = world.getStructureTemplateManager()
                .getTemplate(GEORGIAN_TEMPLATE_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing Georgian showcase structure " + GEORGIAN_TEMPLATE_ID));
        if (template.getSize().getX() != GEORGIAN_WIDTH
                || template.getSize().getY() != GEORGIAN_HEIGHT
                || template.getSize().getZ() != GEORGIAN_DEPTH) {
            throw new IllegalStateException("Georgian showcase structure has size " + template.getSize()
                    + " instead of " + GEORGIAN_WIDTH + "x" + GEORGIAN_HEIGHT + "x" + GEORGIAN_DEPTH);
        }

        StructurePlacementData placement = new StructurePlacementData()
                .setIgnoreEntities(true)
                .setPlaceFluids(true)
                .setUpdateNeighbors(true);
        if (!template.place(world, outerMin, outerMin, placement, world.getRandom(), Block.NOTIFY_ALL)) {
            throw new IllegalStateException("Could not place Georgian showcase structure " + GEORGIAN_TEMPLATE_ID);
        }

        for (ShowcaseSign sign : GEORGIAN_SIGNS) {
            placeSign(world, outerMin.add(sign.x(), sign.y(), sign.z()), sign.translationKeys());
        }

        ClusterRecalcSupport.scanBox(world, new ClusterRecalcSupport.Box(
                outerMin.getX(),
                outerMin.getY(),
                outerMin.getZ(),
                outerMin.getX() + GEORGIAN_WIDTH - 1,
                outerMin.getY() + GEORGIAN_HEIGHT - 1,
                outerMin.getZ() + GEORGIAN_DEPTH - 1
        ));
    }

    private static void placeSign(ServerWorld world, BlockPos pos, String... translationKeys) {
        BlockState signState = Blocks.OAK_SIGN.getDefaultState().with(SignBlock.ROTATION, 8);
        world.setBlockState(pos, signState, Block.NOTIFY_ALL);

        if (!(world.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
            throw new IllegalStateException("Could not create showcase sign at " + pos);
        }

        SignText text = new SignText();
        for (int line = 0; line < Math.min(translationKeys.length, 4); line++) {
            text = text.withMessage(line, Text.translatable(translationKeys[line]));
        }
        sign.setText(text, true);
        sign.setText(text, false);
        sign.markDirty();
        world.updateListeners(pos, signState, signState, Block.NOTIFY_ALL);
    }

    private enum GeorgianPlan implements ShowcasePlacementPlan {
        INSTANCE;

        @Override
        public int width() {
            return GEORGIAN_WIDTH;
        }

        @Override
        public int height() {
            return GEORGIAN_HEIGHT;
        }

        @Override
        public int depth() {
            return GEORGIAN_DEPTH;
        }

        @Override
        public int displayCount() {
            return GEORGIAN_TYPE_COUNT;
        }

        @Override
        public void place(ServerWorld world, BlockPos outerMin) {
            placeGeorgianShowcase(world, outerMin);
        }
    }

    private record ShowcaseSign(int x, int y, int z, String... translationKeys) {
    }
}
