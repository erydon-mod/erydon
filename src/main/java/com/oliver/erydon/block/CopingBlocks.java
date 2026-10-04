package com.oliver.erydon.block;

import com.oliver.erydon.item.ErydonMaterialSources;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Shared material/finish registration, also exercised by the focused Fabric probe. */
public final class CopingBlocks {
    private CopingBlocks() { }
    public static void register(Function<String,Block> source, BiConsumer<String,Block> register) {
        for (String material : ErydonMaterialSources.materialPrefixes()) {
            for (String finish : List.of("", "aged", "rusticated", "hewn", "ashlar")) {
                String prefix = material + (finish.isEmpty() ? "" : "_" + finish);
                String stone = finish.equals("aged") ? material + "_aged_block" : prefix + "_block";
                register.accept(prefix + "_coping_georgian", new CopingBlock(AbstractBlock.Settings.copy(source.apply(stone))));
            }
        }
    }
}
