package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

public class FluidBrushParityTest {
    @Test
    public void compactRegionsMatchOriginalSettersForBothFormatsAndExtremeValues() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (boolean tall : new boolean[] {false, true}) for (int lava = 0; lava < 3; lava++)
                for (boolean reset : new boolean[] {false, true}) for (int level : new int[] {-64, -1, 62, 70000, Integer.MIN_VALUE})
                    for (int[] r : new int[][] {{0, 0, 128, 128}, {127, 125, 1, 3}, {3, 127, 125, 1}}) {
                        Tile left = fixture(tall, lava), right = fixture(tall, lava);
                        float[] strengths = strengths(r[2], r[3]);
                        int[] a = listen(left), b = listen(right);
                        left.inhibitEvents(); right.inhibitEvents();
                        oracle(left, r, strengths, reset, level);
                        assertTrue(right.editFluidRegionNative(r[0], r[1], r[2], r[3], strengths, 0, r[3], reset, level));
                        left.releaseEvents(); right.releaseEvents();
                        same(left, right); assertArrayEquals(a, b);
                    }
        } finally { restore(previous); }
    }

    @Test
    public void undoAndOtherPlanesArePreserved() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (boolean tall : new boolean[] {false, true}) {
                Tile tile = fixture(tall, 2), before = fixture(tall, 2);
                tile.setHeight(5, 6, 75.125f); tile.setTerrain(5, 6, Terrain.CUSTOM_1);
                UndoManager undo = new UndoManager(); tile.register(undo); undo.armSavePoint();
                float[] strengths = strengths(128, 128);
                tile.inhibitEvents(); tile.editFluidRegion(0, 0, 128, 128, strengths, 0, 128, true, 62); tile.releaseEvents();
                assertTrue(undo.undo()); same(before, tile);
                assertTrue(undo.redo()); oracle(before, new int[] {0, 0, 128, 128}, strengths, true, 62); same(before, tile);
                assertEquals(75.125f, tile.getHeight(5, 6), 0f); assertEquals(Terrain.CUSTOM_1, tile.getTerrain(5, 6));
            }
        } finally { restore(previous); }
    }

    @Test
    public void dimensionBordersHolesWrappingAndImmediateEventsMatch() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (boolean reset : new boolean[] {false, true}) for (boolean inhibited : new boolean[] {false, true})
                for (int ox : new int[] {-257, Integer.MAX_VALUE - 127}) {
                    Dimension left = dimension(ox), right = dimension(ox);
                    float[] strengths = strengths(255, 255);
                    if (inhibited) { left.setEventsInhibited(true); right.setEventsInhibited(true); }
                    FluidBrushBenchmark.oracle(left, ox, -129, 255, 255, strengths, reset, 62);
                    FluidBrushAccess.apply(right, ox, -129, 255, 255, strengths, reset, 62);
                    if (inhibited) { left.setEventsInhibited(false); right.setEventsInhibited(false); }
                    for (Tile tile : left.getTiles()) same(tile, right.getTile(tile.getX(), tile.getY()));
                }
            Dimension dimension = VerticalResizeBenchmark.fixture();
            Tile first = dimension.getTiles().iterator().next();
            int[] events = listen(first); int old = first.getWaterLevel(0, 0);
            dimension.setEventsInhibited(true);
            FluidBrushAccess.apply(dimension, first.getX() * 128, first.getY() * 128, 1, 1, new float[] {1}, true, -1);
            FluidBrushAccess.apply(dimension, first.getX() * 128, first.getY() * 128, 1, 1, new float[] {-0f}, false, 62);
            dimension.setEventsInhibited(false);
            assertArrayEquals(new int[] {0, 0}, events); assertEquals(old, first.getWaterLevel(0, 0));
        } finally { restore(previous); }
    }

    @Test
    public void completeStrokesAndWorkerScratchAreEquivalent() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            Dimension left = VerticalResizeBenchmark.fixture(), right = VerticalResizeBenchmark.fixture();
            System.setProperty(Native.GEN_KEY, "false"); FluidBrushBenchmark.apply(left);
            System.setProperty(Native.GEN_KEY, "true"); FluidBrushBenchmark.apply(right);
            for (Tile tile : left.getTiles()) same(tile, right.getTile(tile.getX(), tile.getY()));
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 4; worker++) tasks.add(workers.submit(() -> {
                for (boolean tall : new boolean[] {false, true}) {
                    Tile expected = fixture(tall, 2), actual = fixture(tall, 2);
                    float[] strengths = strengths(128, 128);
                    oracle(expected, new int[] {0, 0, 128, 128}, strengths, true, 70);
                    actual.inhibitEvents(); actual.editFluidRegion(0, 0, 128, 128, strengths, 0, 128, true, 70); actual.releaseEvents();
                    same(expected, actual);
                }
            }));
            for (var task : tasks) task.get();
        } finally { workers.shutdownNow(); restore(previous); }
    }

    private static Dimension dimension(int ox) {
        Dimension dimension = VerticalResizeBenchmark.fixture();
        for (Tile tile : new java.util.ArrayList<>(dimension.getTiles())) dimension.removeTile(tile.getX(), tile.getY());
        for (int x = 0; x < 255; x += 64) for (int y = 0; y < 255; y += 64) {
            int tx = (ox + x) >> 7, ty = (-129 + y) >> 7;
            if ((tx & 1) == 0 && ty == -1 || dimension.getTile(tx, ty) != null) continue;
            Tile tile = new Tile(tx, ty, -64, 320);
            tile.setBitLayerValue(FloodWithLava.INSTANCE, 127, 127, true); dimension.addTile(tile);
        }
        return dimension;
    }

    private static Tile fixture(boolean tall, int lava) {
        Tile tile = new Tile(-3, 4, tall ? -64 : 0, tall ? 320 : 256);
        tile.inhibitEvents();
        for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
            tile.setWaterLevel(x, y, x * 3 + y * 7 - 64);
            if (lava == 2) tile.setBitLayerValue(FloodWithLava.INSTANCE, x, y, ((x + y) & 3) == 0);
        }
        if (lava == 1) {
            tile.setBitLayerValue(FloodWithLava.INSTANCE, 0, 0, true);
            tile.setBitLayerValue(FloodWithLava.INSTANCE, 0, 0, false);
        }
        tile.releaseEvents(); return tile;
    }

    private static float[] strengths(int width, int height) {
        float[] values = new float[width * height];
        float[] cases = {0f, -0f, 1f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (int i = 0; i < values.length; i++) values[i] = cases[i % cases.length];
        return values;
    }

    private static void oracle(Tile tile, int[] r, float[] strengths, boolean reset, int level) {
        for (int x = 0; x < r[2]; x++) for (int y = 0; y < r[3]; y++) if (strengths[x * r[3] + y] != 0f) {
            tile.setWaterLevel(r[0] + x, r[1] + y, level);
            if (reset) tile.setBitLayerValue(FloodWithLava.INSTANCE, r[0] + x, r[1] + y, false);
        }
    }

    private static void same(Tile left, Tile right) {
        assertEquals(left.hasLayer(FloodWithLava.INSTANCE), right.hasLayer(FloodWithLava.INSTANCE));
        for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
            assertEquals(left.getWaterLevel(x, y), right.getWaterLevel(x, y));
            assertEquals(left.getBitLayerValue(FloodWithLava.INSTANCE, x, y), right.getBitLayerValue(FloodWithLava.INSTANCE, x, y));
        }
    }

    private static int[] listen(Tile tile) {
        int[] counts = {0, 0};
        tile.addListener((Tile.Listener) Proxy.newProxyInstance(Tile.Listener.class.getClassLoader(), new Class<?>[] {Tile.Listener.class},
                (p, m, a) -> { if (m.getName().equals("waterLevelChanged")) counts[0]++;
                    if (m.getName().equals("layerDataChanged")) counts[1]++; return null; }));
        return counts;
    }

    private static void restore(String previous) {
        if (previous == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, previous);
    }
}
