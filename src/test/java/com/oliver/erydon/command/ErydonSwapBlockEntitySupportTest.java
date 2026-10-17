package com.oliver.erydon.command;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ErydonSwapBlockEntitySupportTest {
    @Test
    void sharedCornicesSwapEveryPaidPartAndKeepCoverSettingsAndUndoSource() {
        NbtCompound original = new NbtCompound();
        original.putString("id", "erydon:cornice_assembly");
        original.putInt("Version", 1);
        NbtList parts = new NbtList();
        for (String shape : new String[]{"diagonal_primary", "broad_left_secondary"}) {
            NbtCompound part = new NbtCompound();
            part.putString("Name", "erydon:aganite_cornice_modern");
            NbtCompound properties = new NbtCompound();
            properties.putString("shape", shape);
            properties.putString("facing", "west");
            part.put("Properties", properties);
            parts.add(part);
        }
        original.put("Cornices", parts);
        NbtCompound cover = new NbtCompound();
        cover.putString("Name", "erydon:cover_bronze");
        NbtCompound properties = new NbtCompound();
        properties.putString("finish", "gloss");
        properties.putString("light", "bright");
        cover.put("Properties", properties);
        original.put("Cover", cover);

        NbtCompound swapped = ErydonSwapBlockEntitySupport.swapComponents(original, id ->
                id.getPath().equals("aganite_cornice_modern")
                        ? new Identifier("erydon", "aganite_aged_cornice_modern") : id);
        NbtList actual = swapped.getList("Cornices", 10);
        assertEquals(2, actual.size());
        for (int index = 0; index < actual.size(); index++) {
            assertEquals("erydon:aganite_aged_cornice_modern", actual.getCompound(index).getString("Name"));
            assertEquals(parts.getCompound(index).getCompound("Properties"), actual.getCompound(index).getCompound("Properties"));
            assertEquals("erydon:aganite_cornice_modern", parts.getCompound(index).getString("Name"));
        }
        assertEquals(cover, swapped.getCompound("Cover"));
        assertEquals(original, ErydonSwapBlockEntitySupport.swapComponents(swapped,
                id -> id.getPath().equals("aganite_aged_cornice_modern")
                        ? new Identifier("erydon", "aganite_cornice_modern") : id));
    }

    @Test
    void fountainPlinthConversionPreservesPropertiesBowlsAndOriginalUndoData() {
        NbtCompound original = new NbtCompound();
        original.putString("id", "daedalon:fountain_basin");
        original.putInt("BowlCount", 3);
        NbtCompound plinth = new NbtCompound();
        plinth.putString("Name", "daedalon:aganite_kion_plinth");
        NbtCompound properties = new NbtCompound();
        properties.putString("facing", "west");
        properties.putString("size", "large");
        plinth.put("Properties", properties);
        original.put("Plinth", plinth);

        NbtCompound swapped = ErydonSwapBlockEntitySupport.swapComponents(original,
                id -> new Identifier("daedalon", "aganite_aged_kion_plinth"));
        assertEquals("daedalon:aganite_aged_kion_plinth", swapped.getCompound("Plinth").getString("Name"));
        assertEquals(properties, swapped.getCompound("Plinth").getCompound("Properties"));
        assertEquals(3, swapped.getInt("BowlCount"));
        assertEquals("daedalon:aganite_kion_plinth", original.getCompound("Plinth").getString("Name"));
        assertEquals(original, ErydonSwapBlockEntitySupport.swapComponents(original, id -> id));
    }

    @Test
    void unrelatedBlockEntityDataIsNeverInterpretedAsAFountain() {
        NbtCompound original = new NbtCompound();
        original.putString("id", "minecraft:chest");
        original.putString("CustomName", "Test");
        assertEquals(original, ErydonSwapBlockEntitySupport.swapComponents(original, id -> {
            fail("Unrelated data must not be remapped");
            return id;
        }));
        assertNull(ErydonSwapBlockEntitySupport.swapComponents(null, id -> id));
    }
}
