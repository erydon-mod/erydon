package com.oliver.erydon.command;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ErydonSwapBlockEntitySupportTest {
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
