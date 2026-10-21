package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

public class FluidResetParityTest {
    @Test
    public void bothFluidTypesStorageWidthsWrappingAndEventsMatchJava() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (boolean tall : new boolean[] {false, true}) for (boolean lava : new boolean[] {false, true})
                for (boolean present : new boolean[] {false, true}) for (int level : new int[] {-500, -64, 0, 62, 255, 70000, Integer.MIN_VALUE}) {
                    Tile java = fixture(tall, present), rust = fixture(tall, present);
                    int[] a = listen(java), b = listen(rust);
                    java.inhibitEvents(); rust.inhibitEvents();
                    oracle(java, level, lava);
                    assertTrue("JNI path must execute", rust.resetFluidsNative(level, lava));
                    java.releaseEvents(); rust.releaseEvents();
                    assertSame(java, rust); assertArrayEquals(a, b);
                }
        } finally { restore(previous); }
    }

    @Test
    public void undoRestoresWaterAndThePresenceOfTheLavaLayer() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (boolean tall : new boolean[] {false, true}) for (boolean lava : new boolean[] {false, true}) {
                Tile before = fixture(tall, !lava), actual = fixture(tall, !lava);
                UndoManager undo = new UndoManager(); actual.register(undo); undo.armSavePoint();
                actual.inhibitEvents(); actual.resetFluids(62, lava); actual.releaseEvents();
                assertTrue(undo.undo()); assertSame(before, actual);
                assertTrue(undo.redo()); oracle(before, 62, lava); assertSame(before, actual);
            }
        } finally { restore(previous); }
    }

    @Test
    public void wholeDimensionAndWorkerBuffersPreserveParity() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            Dimension java = VerticalResizeBenchmark.fixture(), rust = VerticalResizeBenchmark.fixture();
            System.setProperty(Native.GEN_KEY, "false"); FluidResetBenchmark.apply(java);
            System.setProperty(Native.GEN_KEY, "true"); FluidResetBenchmark.apply(rust);
            for (Tile tile : java.getTiles()) assertSame(tile, rust.getTile(tile.getX(), tile.getY()));
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 4; worker++) futures.add(workers.submit(() -> {
                for (boolean tall : new boolean[] {false, true}) {
                    Tile expected = fixture(tall, true), actual = fixture(tall, true);
                    oracle(expected, 70, true);
                    actual.inhibitEvents(); actual.resetFluids(70, true); actual.releaseEvents();
                    assertSame(expected, actual);
                }
            }));
            for (var future : futures) future.get();
        } finally { workers.shutdownNow(); restore(previous); }
    }

    private static Tile fixture(boolean tall, boolean lavaPresent) {
        Tile tile = new Tile(-1, 2, tall ? -64 : 0, tall ? 320 : 256);
        tile.inhibitEvents();
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
            tile.setWaterLevel(x, y, 62 + (x + y) % 5);
            if (lavaPresent) tile.setBitLayerValue(FloodWithLava.INSTANCE, x, y, (x + y) % 3 == 0);
        }
        tile.setBitLayerValue(Frost.INSTANCE, 7, 8, true);
        tile.setTerrain(7, 8, Terrain.CUSTOM_1); tile.setHeight(7, 8, 75.125f);
        tile.releaseEvents(); return tile;
    }

    private static void oracle(Tile tile, int level, boolean lava) {
        if (!lava) tile.clearLayerData(FloodWithLava.INSTANCE);
        for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
            tile.setWaterLevel(x, y, level);
            if (lava) tile.setBitLayerValue(FloodWithLava.INSTANCE, x, y, true);
        }
    }

    private static void assertSame(Tile expected, Tile actual) {
        assertEquals(expected.hasLayer(FloodWithLava.INSTANCE), actual.hasLayer(FloodWithLava.INSTANCE));
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
            assertEquals(expected.getWaterLevel(x, y), actual.getWaterLevel(x, y));
            assertEquals(expected.getBitLayerValue(FloodWithLava.INSTANCE, x, y), actual.getBitLayerValue(FloodWithLava.INSTANCE, x, y));
            assertEquals(expected.getRawHeight(x, y), actual.getRawHeight(x, y));
        }
        assertEquals(expected.getTerrain(7, 8), actual.getTerrain(7, 8));
        assertEquals(expected.getBitLayerValue(Frost.INSTANCE, 7, 8), actual.getBitLayerValue(Frost.INSTANCE, 7, 8));
    }

    private static int[] listen(Tile tile) {
        int[] events = new int[3];
        tile.addListener((Tile.Listener) Proxy.newProxyInstance(Tile.Listener.class.getClassLoader(), new Class<?>[] {Tile.Listener.class},
                (p, m, a) -> {
                    if (m.getName().equals("waterLevelChanged")) events[0]++;
                    if (m.getName().equals("layerDataChanged")) events[1]++;
                    if (m.getName().equals("allLayersChanged")) events[2]++;
                    return null;
                }));
        return events;
    }

    private static void restore(String previous) {
        if (previous == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, previous);
    }
}
