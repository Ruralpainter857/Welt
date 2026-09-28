package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.minecraft.ChunkPaletteBuffer;
import org.pepsoft.minecraft.MC115AnvilChunk;
import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;
import static org.pepsoft.minecraft.Constants.MC_LAVA;
import static org.pepsoft.minecraft.Constants.MC_WATER;
import static org.pepsoft.minecraft.Material.STONE;
import static org.pepsoft.minecraft.Material.WATER;

public final class NativeFluidFlowJniTest {
    @Test
    public void fluidFeatureUsesWorkerSnapshotWithoutChangingChunkStorage() {
        assumeTrue("welt_slices is unavailable", NativeLoader.areSlicesAvailable());
        final String previousExport = System.getProperty(Native.EXPORT_KEY);
        final String previousFluidFlow = System.getProperty(Native.FLUID_FLOW_EXPORT_KEY);
        final String previousResources = System.getProperty(Native.RESOURCES_EXPORT_KEY);
        try {
            Native.setExportEnabled(true);
            System.setProperty(Native.FLUID_FLOW_EXPORT_KEY, "true");
            System.setProperty(Native.RESOURCES_EXPORT_KEY, "false");
            final MC115AnvilChunk chunk = new MC115AnvilChunk(0, 0, 16);
            chunk.setMaterial(2, 3, 4, STONE);
            assertEquals("fluid mode must preserve object-backed chunk storage", null,
                    ChunkPaletteBuffer.openLivePaletteView(chunk, 0, 3));
            final ChunkPaletteBuffer.LivePaletteView palette =
                    ChunkPaletteBuffer.openPaletteIndexView(chunk, 0, 3);
            assertNotNull("the read-only native pass must build a worker snapshot", palette);
            assertEquals(1, palette.sectionCount());
            assertEquals(STONE, palette.paletteMaterial(0, palette.indexes(0)[3 * 256 + 4 * 16 + 2]));
            assertEquals("the snapshot must not change the chunk representation", null,
                    ChunkPaletteBuffer.openLivePaletteView(chunk, 0, 3));
            final MC115AnvilChunk tallerChunk = new MC115AnvilChunk(1, 0, 32);
            tallerChunk.setMaterial(1, 17, 1, STONE);
            assertEquals(2, ChunkPaletteBuffer.openPaletteIndexView(tallerChunk, 0, 31).sectionCount());
            assertEquals(1, ChunkPaletteBuffer.openPaletteIndexView(chunk, 0, 3).sectionCount());
        } finally {
            restore(Native.EXPORT_KEY, previousExport);
            restore(Native.FLUID_FLOW_EXPORT_KEY, previousFluidFlow);
            restore(Native.RESOURCES_EXPORT_KEY, previousResources);
        }
    }

    @Test
    public void acceptsAValidSingleSectionBuffer() {
        assumeTrue("welt_slices is unavailable", NativeLoader.areSlicesAvailable());
        final String previousExport = System.getProperty(Native.EXPORT_KEY);
        final String previousFluidFlow = System.getProperty(Native.FLUID_FLOW_EXPORT_KEY);
        try {
            Native.setExportEnabled(true);
            System.setProperty(Native.FLUID_FLOW_EXPORT_KEY, "true");
            final boolean processed = NativeSlices.findFluidUpdatesForChunk(
                    0, 0, 0, 0, 0, 1, true, false,
                    new int[][]{new int[4096]}, new byte[][]{new byte[]{0}},
                    new byte[16], new byte[16], new byte[16], new byte[16], new byte[256]);
            assertTrue("the native fluid JNI entry should accept a valid palette", processed);
        } finally {
            restore(Native.EXPORT_KEY, previousExport);
            restore(Native.FLUID_FLOW_EXPORT_KEY, previousFluidFlow);
        }
    }

    @Test
    public void marksFlowingWaterFromSectionPaletteSnapshot() {
        assumeTrue("welt_slices is unavailable", NativeLoader.areSlicesAvailable());
        final String previousExport = System.getProperty(Native.EXPORT_KEY);
        final String previousFluidFlow = System.getProperty(Native.FLUID_FLOW_EXPORT_KEY);
        final String previousResources = System.getProperty(Native.RESOURCES_EXPORT_KEY);
        try {
            Native.setExportEnabled(true);
            System.setProperty(Native.FLUID_FLOW_EXPORT_KEY, "true");
            System.setProperty(Native.RESOURCES_EXPORT_KEY, "false");
            final MC115AnvilChunk chunk = new MC115AnvilChunk(0, 0, 16);
            chunk.setMaterial(2, 0, 3, STONE);
            chunk.setMaterial(2, 2, 3, WATER);
            final ChunkPaletteBuffer.LivePaletteView palette =
                    ChunkPaletteBuffer.openPaletteIndexView(chunk, 0, 2);
            assertNotNull(palette);
            final int paletteSize = palette.paletteSize(0);
            final byte[] flags = new byte[paletteSize];
            for (int index = 0; index < paletteSize; index++) {
                final Material material = palette.paletteMaterial(0, index);
                if (material == null) continue;
                int state = 0;
                if (material.containsWater() || material.isNamed(MC_WATER)) state |= 1;
                if (material.containsWater()) state |= 2;
                if (material.isNamed(MC_LAVA)) state |= 4;
                if (material.solid) state |= 8;
                flags[index] = (byte) state;
            }
            final int height = 3, column = 2 * 16 + 3;
            final byte[] updates = new byte[height * 256];
            assertTrue(NativeSlices.findFluidUpdatesForChunk(0, 2, 0, 15, palette.minY(), 1,
                    true, false, new int[][]{palette.indexes(0)}, new byte[][]{flags},
                    new byte[height * 16], new byte[height * 16],
                    new byte[height * 16], new byte[height * 16], updates));
            assertEquals(1, updates[column * height + 2]);
        } finally {
            restore(Native.EXPORT_KEY, previousExport);
            restore(Native.FLUID_FLOW_EXPORT_KEY, previousFluidFlow);
            restore(Native.RESOURCES_EXPORT_KEY, previousResources);
        }
    }

    private static void restore(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }
}
