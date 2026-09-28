package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.minecraft.Material;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.pepsoft.minecraft.Material.SEA_PICKLE_1;
import static org.pepsoft.minecraft.Material.WATER;
import static org.pepsoft.minecraft.Material.WATERLOGGED;

public final class MaterialWaterContentTest {
    @Test
    public void containsWaterRecognisesWaterloggedVariantsAndSources() {
        assertTrue(SEA_PICKLE_1.containsWater());
        assertFalse(SEA_PICKLE_1.withProperty(WATERLOGGED, false).containsWater());
        assertTrue(WATER.containsWater());
        assertFalse(Material.AIR.containsWater());
    }
}
