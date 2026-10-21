package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

public class MaskedPlaneParityTest {
    private static final Layer[] LAYERS = {null, Biome.INSTANCE, Resources.INSTANCE, Frost.INSTANCE, Populate.INSTANCE};
    @Test
    public void maskedPlanesMatchSettersIncludingPresenceAndNoChangeEvents() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String previous = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (Layer layer : LAYERS) for (boolean present : new boolean[] {false, true})
                for (int value : values(layer)) for (int[] r : new int[][] {{0, 0, 128, 128}, {127, 125, 1, 3}, {1, 127, 127, 1}}) {
                    Tile left = fixture(layer, present), right = fixture(layer, present); byte[] mask = mask(r[2], r[3]);
                    int[] a = listen(left), b = listen(right); left.inhibitEvents(); right.inhibitEvents();
                    oracle(left, layer, value, r, mask);
                    assertTrue(right.editMaskedRegionNative(layer, Terrain.CUSTOM_96, value, r[0], r[1], r[2], r[3], mask, 0, r[2]));
                    left.releaseEvents(); right.releaseEvents(); same(left, right, layer); assertArrayEquals(a, b);
                }
        } finally { restore(previous); }
    }
    @Test
    public void undoAndOtherPlanesArePreserved() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String previous = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (Layer layer : LAYERS) {
                Tile tile = fixture(layer, true), before = fixture(layer, true);
                tile.setHeight(5, 6, 75.125f); tile.setWaterLevel(5, 6, 70);
                UndoManager undo = new UndoManager(); tile.register(undo); undo.armSavePoint(); byte[] mask = mask(128, 128);
                int value = layer == null ? 0 : layer.dataSize.maxValue;
                tile.inhibitEvents(); tile.editMaskedRegion(layer, Terrain.CUSTOM_96, value, 0, 0, 128, 128, mask, 0, 128); tile.releaseEvents();
                assertTrue(undo.undo()); same(before, tile, layer); assertTrue(undo.redo());
                oracle(before, layer, value, new int[] {0, 0, 128, 128}, mask); same(before, tile, layer);
                assertEquals(75.125f, tile.getHeight(5, 6), 0f); assertEquals(70, tile.getWaterLevel(5, 6));
            }
        } finally { restore(previous); }
    }
    @Test
    public void regionBordersHolesAndWorkerBuffersMatch() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String previous = System.getProperty(Native.GEN_KEY);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            for (Layer layer : LAYERS) {
                Dimension left = VerticalResizeBenchmark.fixture(), right = VerticalResizeBenchmark.fixture();
                left.removeTile(-1, -1); right.removeTile(-1, -1); byte[] mask = mask(255, 255);
                int value = layer == null ? 0 : layer.dataSize.maxValue;
                left.setEventsInhibited(true); right.setEventsInhibited(true);
                System.setProperty(Native.GEN_KEY, "false"); apply(left, layer, value, mask);
                System.setProperty(Native.GEN_KEY, "true"); apply(right, layer, value, mask);
                left.setEventsInhibited(false); right.setEventsInhibited(false);
                for (Tile tile : left.getTiles()) same(tile, right.getTile(tile.getX(), tile.getY()), layer);
            }
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) tasks.add(workers.submit(() -> {
                for (Layer layer : LAYERS) {
                    Tile left = fixture(layer, true), right = fixture(layer, true); byte[] mask = mask(128, 128);
                    oracle(left, layer, 0, new int[] {0, 0, 128, 128}, mask);
                    right.inhibitEvents(); right.editMaskedRegion(layer, Terrain.CUSTOM_96, 0, 0, 0, 128, 128, mask, 0, 128); right.releaseEvents();
                    same(left, right, layer);
                }
            }));
            for (var task : tasks) task.get();
        } finally { workers.shutdownNow(); restore(previous); }
    }
    private static void apply(Dimension dimension, Layer layer, int value, byte[] mask) {
        if (layer == null) MaskedPlaneAccess.applyTerrain(dimension, Terrain.CUSTOM_96, -129, -129, 255, 255, mask);
        else MaskedPlaneAccess.applyLayer(dimension, layer, value, -129, -129, 255, 255, mask);
    }
    private static int[] values(Layer layer) { return layer == null ? new int[] {0} : new int[] {0, layer.getDefaultValue(), layer.dataSize.maxValue}; }
    private static Tile fixture(Layer layer, boolean present) {
        Tile tile = new Tile(-3, 4, -64, 320);
        if (present) { tile.inhibitEvents();
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                if (layer == null) tile.setTerrain(x, y, ((x + y) & 1) == 0 ? Terrain.GRASS : Terrain.CUSTOM_1);
                else if (layer.dataSize.maxValue == 1) tile.setBitLayerValue(layer, x, y, ((x + y) & 3) != 0);
                else tile.setLayerValue(layer, x, y, (x + y * 3) & layer.dataSize.maxValue);
            }
            tile.releaseEvents(); }
        return tile;
    }
    private static byte[] mask(int width, int height) { byte[] mask = new byte[width * height];
        for (int i = 0; i < mask.length; i++) mask[i] = (byte) (i % 3 == 0 ? 0 : 1); return mask; }
    private static void oracle(Tile tile, Layer layer, int value, int[] r, byte[] mask) {
        for (int y = 0; y < r[3]; y++) for (int x = 0; x < r[2]; x++) if (mask[y * r[2] + x] != 0) {
            if (layer == null) tile.setTerrain(r[0] + x, r[1] + y, Terrain.CUSTOM_96);
            else if (layer.dataSize.maxValue == 1) tile.setBitLayerValue(layer, r[0] + x, r[1] + y, value != 0);
            else tile.setLayerValue(layer, r[0] + x, r[1] + y, value);
        }
    }
    private static void same(Tile left, Tile right, Layer layer) {
        if (layer != null) assertEquals(left.hasLayer(layer), right.hasLayer(layer));
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
            if (layer == null) assertEquals(left.getTerrain(x, y), right.getTerrain(x, y));
            else if (layer.dataSize.maxValue == 1) assertEquals(left.getBitLayerValue(layer, x, y), right.getBitLayerValue(layer, x, y));
            else assertEquals(left.getLayerValue(layer, x, y), right.getLayerValue(layer, x, y));
        }
    }
    private static int[] listen(Tile tile) { int[] counts = {0, 0};
        tile.addListener((Tile.Listener) Proxy.newProxyInstance(Tile.Listener.class.getClassLoader(), new Class<?>[] {Tile.Listener.class},
                (p, m, a) -> { if (m.getName().equals("terrainChanged")) counts[0]++;
                    if (m.getName().equals("layerDataChanged")) counts[1]++; return null; })); return counts; }
    private static void restore(String previous) { if (previous == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, previous); }
}
