package com.oliver.erydon.migration;

import com.oliver.erydon.block.GlazingVerticalSlopeBlock;
import com.oliver.erydon.client.migration.IdAliasSearchVocabulary;
import com.oliver.erydon.item.ErydonBlockItem;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

import java.util.List;

/** Actual permanent aliases, command lookup and saved block/item data for the four renamed panes. */
public final class GlazingVerticalDiagonalMigrationLaunchChecks {
    public static void run() throws Exception {
        int states = 0;
        for (String finish : List.of("tinted", "silver", "crystal", "bronze")) {
            String oldPath = "glazing_framed_" + finish + "_slope_vertical";
            String path = "glazing_framed_" + finish + "_vertical_diagonal";
            Identifier oldId = new Identifier("erydon", oldPath), id = new Identifier("erydon", path);
            var entry = ErydonIdMigration.findByCanonicalId(id);
            require(entry != null && entry.permanentAlias() && entry.oldId().equals(oldId), "Missing published glazing migration: " + id);
            Block block;
            if (Registries.BLOCK.containsId(id)) {
                block = Registries.BLOCK.get(id);
                require(block instanceof GlazingVerticalSlopeBlock, "Vertical diagonal changed its block geometry");
            } else {
                block = Registry.register(Registries.BLOCK, id, new GlazingVerticalSlopeBlock(AbstractBlock.Settings.copy(Blocks.GLASS)));
                Registry.register(Registries.ITEM, id, new ErydonBlockItem(block, new Item.Settings()));
            }
            // Invoke exactly the registration used by the complete production startup loop.
            ErydonIdMigration.registerAlias(entry);
            Item item = Registries.ITEM.get(id);
            require(Registries.BLOCK.get(oldId) == block && Registries.ITEM.get(oldId) == item, "Published block/item alias did not resolve");
            require(!Registries.BLOCK.getIds().contains(oldId) && !Registries.ITEM.getIds().contains(oldId), "Legacy glazing leaked duplicate catalogue entries");
            require(Registries.BLOCK.getId(block).equals(id) && Registries.ITEM.getId(item).equals(id), "Alias replaced canonical glazing identity");
            for (Direction facing : Direction.Type.HORIZONTAL) for (boolean wet : new boolean[]{false, true}) {
                var expected = block.getDefaultState().with(GlazingVerticalSlopeBlock.FACING, facing).with(GlazingVerticalSlopeBlock.WATERLOGGED, wet);
                NbtCompound saved = NbtHelper.fromBlockState(expected);
                saved.putString("Name", oldId.toString());
                var restored = NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), saved);
                require(restored == expected, "Published world/structure palette lost glazing state: " + saved);
                require(NbtHelper.fromBlockState(restored).getString("Name").equals(id.toString()), "Restored palette did not save canonical ID");
                String command = oldId + "[facing=" + facing.asString() + ",waterlogged=" + wet + "]";
                require(BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), command, false).blockState() == expected,
                        "Published command/tool block state no longer resolves: " + command);
                states++;
            }
            NbtCompound itemNbt = new NbtCompound();
            itemNbt.putString("id", oldId.toString()); itemNbt.putByte("Count", (byte) 7);
            NbtCompound custom = new NbtCompound(); custom.putString("migration_probe", "preserve"); itemNbt.put("tag", custom);
            ItemStack stack = ItemStack.fromNbt(itemNbt);
            require(stack.isOf(item) && stack.getCount() == 7 && stack.getNbt() != null
                            && stack.getNbt().getString("migration_probe").equals("preserve"), "Published inventory item lost identity/count/custom data");
            require(stack.writeNbt(new NbtCompound()).getString("id").equals(id.toString()), "Restored inventory did not save canonical ID");
            List<String> vocabulary = IdAliasSearchVocabulary.terms(stack);
            require(vocabulary.containsAll(List.of(oldId.toString(), oldPath, oldPath.replace('_', ' '), entry.oldDisplayName(),
                            id.toString(), path, entry.canonicalDisplayName(), "vertical diagonal", "vertical slope", "ramp", "wedge", "roof", "glass", "pane")),
                    "Creative/item-browser compatibility lost published/current glazing vocabulary: " + id);
        }
        System.out.println("ERYDON_GLAZING_VERTICAL_DIAGONAL_MIGRATION_OK: aliases=4 blockStates=" + states + " itemStacks=4");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
