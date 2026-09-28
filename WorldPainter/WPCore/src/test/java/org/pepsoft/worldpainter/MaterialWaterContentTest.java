package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.minecraft.Material;
import org.pepsoft.minecraft.Property;

import java.lang.reflect.InvocationTargetException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.pepsoft.minecraft.Material.LEVEL;
import static org.pepsoft.minecraft.Material.SEA_PICKLE_1;
import static org.pepsoft.minecraft.Material.WATER;
import static org.pepsoft.minecraft.Material.WATERLOGGED;

public final class MaterialWaterContentTest {
    @Test
    public void containsWaterRecognisesWaterloggedVariantsAndSources() {
        assertTrue(SEA_PICKLE_1.containsWater());
        assertFalse(SEA_PICKLE_1.withProperty(WATERLOGGED, false).containsWater());
        assertTrue(WATER.containsWater());
        assertFalse(WATER.withProperty(LEVEL, 1).containsWater());
        assertFalse(Material.AIR.containsWater());
    }

    @Test
    public void parsesPrimitivePropertiesWithTheSameValues() {
        assertEquals(Integer.valueOf(13), new Property<>("level", Integer.class).fromString("13"));
        assertEquals(Boolean.TRUE, new Property<>("waterlogged", Boolean.class).fromString("true"));
        assertEquals(Boolean.FALSE, new Property<>("waterlogged", Boolean.class).fromString("invalid"));
        assertEquals("north", new Property<>("facing", String.class).fromString("north"));
    }

    @Test
    public void invalidIntegerPropertyRetainsInvocationFailureShape() {
        try {
            new Property<>("level", Integer.class).fromString("invalid");
            fail("Expected an invalid integer property to fail");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().startsWith("InvocationTargetException when trying to parse"));
            assertTrue(e.getCause() instanceof InvocationTargetException);
            assertTrue(e.getCause().getCause() instanceof NumberFormatException);
        }
    }
}
