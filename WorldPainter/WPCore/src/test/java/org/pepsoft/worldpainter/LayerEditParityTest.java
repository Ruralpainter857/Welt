package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

public class LayerEditParityTest {
    private static final Layer[] LAYERS = {Frost.INSTANCE, Populate.INSTANCE,
            TileRotationParityTest.NIBBLES, TileRotationParityTest.BYTES};

    @Test
    public void compactEditsMatchCellByCellJavaIncludingLayerPresenceAndEvents() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (Layer layer : LAYERS) for (boolean present : new boolean[] {false, true})
                for (int operation = 0; operation < 4; operation++) {
                    int minimum = operation == 0 ? 0 : operation == 1 ? 0 : operation == 2 ? layer.dataSize.maxValue / 2 : layer.dataSize.maxValue;
                    Tile java = fixture(layer, present), rust = fixture(layer, present);
                    int[] javaEvents = listen(java), rustEvents = listen(rust);
                    java.inhibitEvents(); rust.inhibitEvents();
                    oracle(java, layer, operation == 0, minimum);
                    assertTrue("JNI path must execute", rust.editLayerNative(layer, operation == 0, minimum));
                    java.releaseEvents(); rust.releaseEvents();
                    assertSameLayer(java, rust, layer);
                    assertArrayEquals(javaEvents, rustEvents);
                }
        } finally { restore(previous); }
    }

    @Test
    public void editsPreserveUndoAndOtherSharedPlanes() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (Layer layer : LAYERS) {
                Tile tile = fixture(layer, true), before = fixture(layer, true);
                tile.setHeight(7, 8, 75.125f); tile.setTerrain(7, 8, Terrain.CUSTOM_1);
                UndoManager undo = new UndoManager(); tile.register(undo); undo.armSavePoint();
                tile.inhibitEvents(); tile.invertLayer(layer); tile.releaseEvents();
                assertTrue(undo.undo()); assertSameLayer(before, tile, layer);
                assertTrue(undo.redo());
                oracle(before, layer, true, 0); assertSameLayer(before, tile, layer);
                assertEquals(75.125f, tile.getHeight(7, 8), 0f);
                assertEquals(Terrain.CUSTOM_1, tile.getTerrain(7, 8));
                Tile untouched = new Tile(1, 0, -64, 320);
                assertEquals(0, untouched.getRawHeight(7, 8));
                assertFalse(untouched.hasLayer(layer));
            }
        } finally { restore(previous); }
    }

    @Test
    public void worldOperationAndWorkerBuffersMatchJava() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            Dimension java = LayerEditBenchmark.fixture(), rust = LayerEditBenchmark.fixture();
            System.setProperty(Native.GEN_KEY, "false"); LayerEditBenchmark.apply(java);
            System.setProperty(Native.GEN_KEY, "true"); LayerEditBenchmark.apply(rust);
            for (Tile tile : java.getTiles()) assertSameLayer(tile, rust.getTile(tile.getX(), tile.getY()), Resources.INSTANCE);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 4; worker++) futures.add(workers.submit(() -> {
                for (Layer layer : LAYERS) {
                    Tile expected = fixture(layer, true), actual = fixture(layer, true);
                    oracle(expected, layer, true, 0);
                    actual.inhibitEvents(); actual.invertLayer(layer); actual.releaseEvents();
                    assertSameLayer(expected, actual, layer);
                }
            }));
            for (var future : futures) future.get();
        } finally { workers.shutdownNow(); restore(previous); }
    }

    private static Tile fixture(Layer layer, boolean present) {
        Tile tile = new Tile(-3, 4, -64, 320);
        if (present) {
            tile.inhibitEvents();
            int step = layer.dataSize == Layer.DataSize.BIT_PER_CHUNK ? 16 : 1;
            for (int x = 0; x < 128; x += step) for (int y = 0; y < 128; y += step) {
                if (layer.dataSize.maxValue == 1) tile.setBitLayerValue(layer, x, y, ((x / step + y / step) & 3) != 0);
                else tile.setLayerValue(layer, x, y, (x * 3 + y * 7) & layer.dataSize.maxValue);
            }
            tile.releaseEvents();
        }
        return tile;
    }

    private static void oracle(Tile tile, Layer layer, boolean invert, int minimum) {
        int step = layer.dataSize == Layer.DataSize.BIT_PER_CHUNK ? 16 : 1;
        for (int x = 0; x < 128; x += step) for (int y = 0; y < 128; y += step) {
            if (layer.dataSize.maxValue == 1) {
                boolean old = tile.getBitLayerValue(layer, x, y);
                if (invert || (!old && minimum > 0)) tile.setBitLayerValue(layer, x, y, invert ? !old : true);
            } else {
                int old = tile.getLayerValue(layer, x, y);
                if (invert || old < minimum) tile.setLayerValue(layer, x, y, invert ? layer.dataSize.maxValue - old : minimum);
            }
        }
    }

    private static void assertSameLayer(Tile expected, Tile actual, Layer layer) {
        assertEquals(expected.hasLayer(layer), actual.hasLayer(layer));
        for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
            if (layer.dataSize.maxValue == 1) assertEquals(expected.getBitLayerValue(layer, x, y), actual.getBitLayerValue(layer, x, y));
            else assertEquals(expected.getLayerValue(layer, x, y), actual.getLayerValue(layer, x, y));
        }
    }

    private static int[] listen(Tile tile) {
        int[] events = {0};
        tile.addListener((Tile.Listener) Proxy.newProxyInstance(Tile.Listener.class.getClassLoader(), new Class<?>[] {Tile.Listener.class},
                (p, m, a) -> { if (m.getName().equals("layerDataChanged")) events[0]++; return null; }));
        return events;
    }

    private static void restore(String previous) {
        if (previous == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, previous);
    }
}
