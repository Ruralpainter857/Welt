package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

public class TerrainEditParityTest {
    @Test
    public void allOperationsAndFloatEdgeCasesMatchJavaWithIdenticalEvents() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (boolean tall : new boolean[] {false, true}) for (TerrainHeightOperation operation : TerrainHeightOperation.values())
                for (float value : new float[] {-500, -64, -0f, 0f, 0.001f, 62.125f, 800, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
                    Tile java = fixture(tall), rust = fixture(tall);
                    int[] a = listen(java), b = listen(rust);
                    java.inhibitEvents(); rust.inhibitEvents();
                    oracle(java, operation, value, -128, 511);
                    assertTrue("JNI path must execute", rust.editTerrainHeightNative(operation, value, -128, 511));
                    java.releaseEvents(); rust.releaseEvents();
                    assertSame(java, rust); assertArrayEquals(a, b);
                }
        } finally { restore(previous); }
    }

    @Test
    public void undoAndOtherPlanesArePreserved() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (boolean tall : new boolean[] {false, true}) for (TerrainHeightOperation operation : TerrainHeightOperation.values()) {
                Tile before = fixture(tall), actual = fixture(tall);
                UndoManager undo = new UndoManager(); actual.register(undo); undo.armSavePoint();
                actual.inhibitEvents(); actual.editTerrainHeight(operation, 65.25f, 0, 255); actual.releaseEvents();
                assertTrue(undo.undo()); assertSame(before, actual);
                assertTrue(undo.redo()); oracle(before, operation, 65.25f, 0, 255); assertSame(before, actual);
            }
        } finally { restore(previous); }
    }

    @Test
    public void wholeDimensionAndIndependentWorkersMatchJava() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            Dimension java = VerticalResizeBenchmark.fixture(), rust = VerticalResizeBenchmark.fixture();
            System.setProperty(Native.GEN_KEY, "false"); TerrainEditBenchmark.apply(java);
            System.setProperty(Native.GEN_KEY, "true"); TerrainEditBenchmark.apply(rust);
            for (Tile tile : java.getTiles()) assertSame(tile, rust.getTile(tile.getX(), tile.getY()));
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) futures.add(workers.submit(() -> {
                for (boolean tall : new boolean[] {true, false, true}) {
                    Tile expected = fixture(tall), actual = fixture(tall);
                    oracle(expected, TerrainHeightOperation.LOWER_BY, 16, -64, 319);
                    actual.inhibitEvents(); actual.editTerrainHeight(TerrainHeightOperation.LOWER_BY, 16, -64, 319); actual.releaseEvents();
                    assertSame(expected, actual);
                }
            }));
            for (var future : futures) future.get();
        } finally { workers.shutdownNow(); restore(previous); }
    }

    private static Tile fixture(boolean tall) {
        Tile tile = new Tile(-1, 2, tall ? -64 : 0, tall ? 320 : 256);
        tile.inhibitEvents();
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
            tile.setHeight(x, y, (x * 3 + y * 7) % 250 + ((x + y) & 255) / 256f);
            tile.setWaterLevel(x, y, 62 + (x + y) % 5);
        }
        tile.setTerrain(7, 8, Terrain.CUSTOM_1);
        tile.releaseEvents(); return tile;
    }

    private static void oracle(Tile tile, TerrainHeightOperation operation, float value, int min, int max) {
        for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
            if (operation == TerrainHeightOperation.SET) { tile.setHeight(x, y, value); continue; }
            float current = tile.getHeight(x, y);
            switch (operation) {
                case RAISE_TO: if (current < value) tile.setHeight(x, y, value); break;
                case LOWER_TO: if (current > value) tile.setHeight(x, y, value); break;
                case RAISE_BY: float raised = Math.min(current + value, max); if (current < raised) tile.setHeight(x, y, raised); break;
                case LOWER_BY: float lowered = Math.max(current - value, min); if (current > lowered) tile.setHeight(x, y, lowered); break;
            }
        }
    }

    private static void assertSame(Tile expected, Tile actual) {
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
            assertEquals(expected.getRawHeight(x, y), actual.getRawHeight(x, y));
            assertEquals(expected.getWaterLevel(x, y), actual.getWaterLevel(x, y));
        }
        assertEquals(expected.getTerrain(7, 8), actual.getTerrain(7, 8));
    }

    private static int[] listen(Tile tile) {
        int[] events = {0};
        tile.addListener((Tile.Listener) Proxy.newProxyInstance(Tile.Listener.class.getClassLoader(), new Class<?>[] {Tile.Listener.class},
                (p, m, a) -> { if (m.getName().equals("heightMapChanged")) events[0]++; return null; }));
        return events;
    }

    private static void restore(String previous) {
        if (previous == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, previous);
    }
}
