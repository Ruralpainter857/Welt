package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

public class CombinedBrushParityTest {
    @Test
    public void versionTwoExecutesNativelyForAllOptionalPlaneLayouts() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String previous = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            float[] strengths = {1f, 0f, Float.NaN, 0.5f}; byte[] mask = {0, 1, 1, 0};
            for (int flags = 1; flags <= 3; flags++) {
                ByteBuffer result = NibbleBrushAccess.editCombined(126, 126, 2, 2, strengths, mask, 0, 2, 0,
                        null, 0, (flags & 1) != 0 ? new byte[16384] : null, null,
                        (flags & 1) != 0 ? Terrain.CUSTOM_96.ordinal() : -1, (flags & 2) != 0 ? 42 : -1);
                assertNotNull("The v2 JNI kernel must execute", result);
                assertEquals(2, result.getInt(4)); assertEquals(3, result.getInt(28)); assertEquals(2, result.getInt(44));
                int first = 126 + 126 * 128;
                assertEquals(15, (result.get(64 + first / 2) >> (first % 2 * 4)) & 15);
                if ((flags & 1) != 0) assertEquals(Terrain.CUSTOM_96.ordinal(), result.get(8256 + first + 1) & 255);
                if ((flags & 2) != 0) assertEquals(42, result.get(8256 + ((flags & 1) != 0 ? 16384 : 0) + first + 1) & 255);
            }
        } finally { restore(previous); }
    }
    @Test
    public void compoundEditingPreservesUndoAndWorkerIsolation() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String previous = System.getProperty(Native.GEN_KEY);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            CombinedLayer layer = new CombinedLayer("Parity", "Parity", java.awt.Color.GREEN);
            float[] strengths = new float[16384]; java.util.Arrays.fill(strengths, 1f);
            byte[] mask = new byte[16384]; java.util.Arrays.fill(mask, (byte) 1);
            Tile tile = new Tile(0, 0, -64, 320); UndoManager undo = new UndoManager(); tile.register(undo); undo.armSavePoint();
            tile.inhibitEvents(); tile.editCombinedNibbleRegion(layer, Terrain.CUSTOM_96, 42, 0, 0, 128, 128, strengths, mask, 0, 128, 0); tile.releaseEvents();
            assertEquals(15, tile.getLayerValue(layer, 0, 0)); assertTrue(undo.undo());
            assertFalse(tile.hasLayer(layer)); assertFalse(tile.hasLayer(Biome.INSTANCE)); assertEquals(Terrain.GRASS, tile.getTerrain(0, 0));
            assertTrue(undo.redo()); assertEquals(15, tile.getLayerValue(layer, 0, 0));
            assertEquals(42, tile.getLayerValue(Biome.INSTANCE, 0, 0)); assertEquals(Terrain.CUSTOM_96, tile.getTerrain(0, 0));
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) tasks.add(workers.submit(() -> {
                Tile actual = new Tile(-1, -1, -64, 320); actual.inhibitEvents();
                actual.editCombinedNibbleRegion(layer, null, 255, 0, 0, 128, 128, strengths, mask, 0, 128, 0);
                actual.editCombinedNibbleRegion(layer, null, 255, 0, 0, 128, 128, strengths, mask, 0, 128, 2);
                actual.releaseEvents(); assertFalse(actual.hasLayer(Biome.INSTANCE)); assertEquals(0, actual.getLayerValue(layer, 0, 0));
            }));
            for (var task : tasks) task.get();
        } finally { workers.shutdownNow(); restore(previous); }
    }
    private static void restore(String previous) { if (previous == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, previous); }
}
