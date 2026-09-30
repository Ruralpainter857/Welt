package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

public class UniformEditParityTest {
    private static final Layer[] LAYERS = {Biome.INSTANCE, Frost.INSTANCE, Populate.INSTANCE,
            TileRotationParityTest.NIBBLES, TileRotationParityTest.BYTES};

    @Test
    public void constantLayersMatchOriginalSettersIncludingPresenceAndEvents() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (Layer layer : LAYERS) for (boolean present : new boolean[] {false, true})
                for (int value : new int[] {0, layer.getDefaultValue(), layer.dataSize.maxValue / 2, layer.dataSize.maxValue}) {
                    Tile expected = fixture(layer, present), actual = fixture(layer, present);
                    int[] left = listen(expected), right = listen(actual);
                    expected.inhibitEvents(); actual.inhibitEvents();
                    oracle(expected, layer, value);
                    assertTrue(actual.assignLayerNative(layer, value));
                    expected.releaseEvents(); actual.releaseEvents();
                    sameLayer(expected, actual, layer); assertArrayEquals(left, right);
                    assertEquals(Terrain.CUSTOM_2, actual.getTerrain(5, 6));
                }
        } finally { restore(previous); }
    }

    @Test
    public void terrainMatchesOriginalIncludingNoOpAndUndo() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (Terrain value : new Terrain[] {Terrain.GRASS, Terrain.CUSTOM_1, Terrain.CUSTOM_96}) {
                Tile expected = fixture(Biome.INSTANCE, true), actual = fixture(Biome.INSTANCE, true);
                int[] left = listen(expected), right = listen(actual);
                UndoManager undo = new UndoManager(); actual.register(undo); undo.armSavePoint();
                expected.inhibitEvents(); actual.inhibitEvents();
                for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++)
                    if (expected.getTerrain(x, y) != value) expected.setTerrain(x, y, value);
                assertTrue(actual.fillTerrainNative(value));
                expected.releaseEvents(); actual.releaseEvents();
                assertArrayEquals(left, right); sameTerrain(expected, actual);
                int count = right[0]; actual.inhibitEvents(); assertTrue(actual.fillTerrainNative(value)); actual.releaseEvents();
                assertEquals(count, right[0]);
                assertTrue(undo.undo()); assertEquals(Terrain.CUSTOM_2, actual.getTerrain(5, 6));
                assertTrue(undo.redo()); sameTerrain(expected, actual);
                assertEquals(75.125f, actual.getHeight(5, 6), 0f);
            }
        } finally { restore(previous); }
    }

    @Test
    public void layersPreserveUndoAndImmediateEventsFallBackToJava() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (Layer layer : LAYERS) {
                Tile actual = fixture(layer, true), before = fixture(layer, true);
                UndoManager undo = new UndoManager(); actual.register(undo); undo.armSavePoint();
                actual.inhibitEvents(); actual.assignLayerValue(layer, 0); actual.releaseEvents();
                assertTrue(undo.undo()); sameLayer(before, actual, layer);
                assertTrue(undo.redo()); oracle(before, layer, 0); sameLayer(before, actual, layer);
                Tile expected = fixture(layer, true), immediate = fixture(layer, true);
                int[] left = listen(expected), right = listen(immediate);
                assertFalse(immediate.assignLayerNative(layer, 0));
                oracle(expected, layer, 0); immediate.assignLayerValue(layer, 0);
                sameLayer(expected, immediate, layer); assertArrayEquals(left, right);
            }
        } finally { restore(previous); }
    }

    @Test
    public void completeDimensionAndFourWorkerBuffersMatch() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            Dimension expected = VerticalResizeBenchmark.fixture(), actual = VerticalResizeBenchmark.fixture();
            UniformEditBenchmark.apply(expected, false); UniformEditBenchmark.apply(actual, true);
            for (Tile tile : expected.getTiles()) {
                Tile match = actual.getTile(tile.getX(), tile.getY());
                sameTerrain(tile, match); sameLayer(tile, match, Biome.INSTANCE);
            }
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) tasks.add(workers.submit(() -> {
                for (Layer layer : LAYERS) {
                    Tile left = fixture(layer, true), right = fixture(layer, true);
                    oracle(left, layer, layer.dataSize.maxValue);
                    right.inhibitEvents(); right.assignLayerValue(layer, layer.dataSize.maxValue); right.releaseEvents();
                    sameLayer(left, right, layer);
                }
            }));
            for (var task : tasks) task.get();
        } finally { workers.shutdownNow(); restore(previous); }
    }

    private static Tile fixture(Layer layer, boolean present) {
        Tile tile = new Tile(-3, 4, -64, 320);
        tile.inhibitEvents();
        tile.setTerrain(5, 6, Terrain.CUSTOM_2); tile.setHeight(5, 6, 75.125f);
        if (present) {
            int step = layer.dataSize == Layer.DataSize.BIT_PER_CHUNK ? 16 : 1;
            for (int x = 0; x < 128; x += step) for (int y = 0; y < 128; y += step) {
                if (layer.dataSize.maxValue == 1) tile.setBitLayerValue(layer, x, y, ((x / step + y / step) & 1) == 0);
                else tile.setLayerValue(layer, x, y, (x * 3 + y * 7) & layer.dataSize.maxValue);
            }
        }
        tile.releaseEvents(); return tile;
    }

    private static void oracle(Tile tile, Layer layer, int value) {
        int step = layer.dataSize == Layer.DataSize.BIT_PER_CHUNK ? 16 : 1;
        for (int x = 0; x < 128; x += step) for (int y = 0; y < 128; y += step) {
            if (layer.dataSize.maxValue == 1) tile.setBitLayerValue(layer, x, y, value != 0);
            else tile.setLayerValue(layer, x, y, value);
        }
    }

    private static void sameLayer(Tile left, Tile right, Layer layer) {
        assertEquals(left.hasLayer(layer), right.hasLayer(layer));
        for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
            if (layer.dataSize.maxValue == 1) assertEquals(left.getBitLayerValue(layer, x, y), right.getBitLayerValue(layer, x, y));
            else assertEquals(left.getLayerValue(layer, x, y), right.getLayerValue(layer, x, y));
        }
    }

    private static void sameTerrain(Tile left, Tile right) {
        for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) assertEquals(left.getTerrain(x, y), right.getTerrain(x, y));
    }

    private static int[] listen(Tile tile) {
        int[] events = {0, 0};
        tile.addListener((Tile.Listener) Proxy.newProxyInstance(Tile.Listener.class.getClassLoader(), new Class<?>[] {Tile.Listener.class},
                (p, m, a) -> { if (m.getName().equals("terrainChanged")) events[0]++;
                    if (m.getName().equals("layerDataChanged")) events[1]++; return null; }));
        return events;
    }

    private static void restore(String previous) {
        if (previous == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, previous);
    }
}
