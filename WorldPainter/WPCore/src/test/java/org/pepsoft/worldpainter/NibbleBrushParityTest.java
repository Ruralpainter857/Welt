package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.layers.Resources;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

public class NibbleBrushParityTest {
    private static final Layer CUSTOM = new Layer("brush-custom", "Custom", "", Layer.DataSize.NIBBLE, false, 1) {
        @Override public int getDefaultValue() { return 15; }
    };
    private static final Layer[] LAYERS = {Resources.INSTANCE, TileRotationParityTest.NIBBLES, CUSTOM};

    @Test
    public void compactBrushMatchesJavaOnAllThreeModesAndPackedBorders() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (Layer layer : LAYERS) for (boolean present : new boolean[] {false, true}) for (int mode = 0; mode < 3; mode++)
                for (int[] r : new int[][] {{0, 0, 128, 128}, {1, 123, 127, 5}, {127, 1, 1, 127}}) {
                    Tile left = fixture(layer, present), right = fixture(layer, present);
                    float[] strengths = strengths(r[2], r[3]);
                    int[] a = listen(left), b = listen(right); left.inhibitEvents(); right.inhibitEvents();
                    oracle(left, layer, r, strengths, mode);
                    assertTrue(right.editNibbleRegionNative(layer, r[0], r[1], r[2], r[3], strengths, 0, r[2], mode));
                    left.releaseEvents(); right.releaseEvents(); same(left, right, layer); assertArrayEquals(a, b);
                }
        } finally { restore(previous); }
    }

    @Test
    public void undoAndImmediateEventsAndInvalidStrengthsPreserveJavaBehavior() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (Layer layer : LAYERS) {
                Tile tile = fixture(layer, true), before = fixture(layer, true);
                tile.setTerrain(5, 6, Terrain.CUSTOM_1); tile.setHeight(5, 6, 75.125f);
                UndoManager undo = new UndoManager(); tile.register(undo); undo.armSavePoint();
                float[] strengths = strengths(128, 128);
                tile.inhibitEvents(); tile.editNibbleRegion(layer, 0, 0, 128, 128, strengths, 0, 128, 0); tile.releaseEvents();
                assertTrue(undo.undo()); same(before, tile, layer);
                assertTrue(undo.redo()); oracle(before, layer, new int[] {0, 0, 128, 128}, strengths, 0); same(before, tile, layer);
                assertEquals(Terrain.CUSTOM_1, tile.getTerrain(5, 6)); assertEquals(75.125f, tile.getHeight(5, 6), 0f);
                Tile left = fixture(layer, true), right = fixture(layer, true);
                int[] a = listen(left), b = listen(right);
                oracle(left, layer, new int[] {0, 0, 128, 128}, strengths, 2);
                right.editNibbleRegion(layer, 0, 0, 128, 128, strengths, 0, 128, 2);
                same(left, right, layer); assertArrayEquals(a, b);
            }
            Dimension left = VerticalResizeBenchmark.fixture(), right = VerticalResizeBenchmark.fixture();
            float[] invalid = {1f, 2f};
            left.setEventsInhibited(true); right.setEventsInhibited(true);
            try { NibbleBrushBenchmark.oracle(left, CUSTOM, -1, 0, 2, 1, invalid, 0); fail(); }
            catch (IllegalArgumentException expected) { }
            try { NibbleBrushAccess.apply(right, CUSTOM, -1, 0, 2, 1, invalid, 0); fail(); }
            catch (IllegalArgumentException expected) { }
            left.setEventsInhibited(false); right.setEventsInhibited(false);
            for (Tile tile : left.getTiles()) same(tile, right.getTile(tile.getX(), tile.getY()), CUSTOM);
        } finally { restore(previous); }
    }

    @Test
    public void dimensionBordersHolesAndCompleteStrokesAndWorkersMatch() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (int mode = 0; mode < 3; mode++) {
                Dimension left = VerticalResizeBenchmark.fixture(), right = VerticalResizeBenchmark.fixture();
                left.removeTile(-1, -1); right.removeTile(-1, -1);
                float[] strengths = strengths(255, 255);
                left.setEventsInhibited(true); right.setEventsInhibited(true);
                NibbleBrushBenchmark.oracle(left, Resources.INSTANCE, -129, -129, 255, 255, strengths, mode);
                NibbleBrushAccess.apply(right, Resources.INSTANCE, -129, -129, 255, 255, strengths, mode);
                left.setEventsInhibited(false); right.setEventsInhibited(false);
                for (Tile tile : left.getTiles()) same(tile, right.getTile(tile.getX(), tile.getY()), Resources.INSTANCE);
            }
            Dimension left = VerticalResizeBenchmark.fixture(), right = VerticalResizeBenchmark.fixture();
            System.setProperty(Native.GEN_KEY, "false"); NibbleBrushBenchmark.apply(left);
            System.setProperty(Native.GEN_KEY, "true"); NibbleBrushBenchmark.apply(right);
            for (Tile tile : left.getTiles()) same(tile, right.getTile(tile.getX(), tile.getY()), Resources.INSTANCE);
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 4; worker++) tasks.add(workers.submit(() -> {
                for (int mode = 0; mode < 3; mode++) {
                    Tile expected = fixture(CUSTOM, true), actual = fixture(CUSTOM, true); float[] strengths = strengths(128, 128);
                    oracle(expected, CUSTOM, new int[] {0, 0, 128, 128}, strengths, mode);
                    actual.inhibitEvents(); actual.editNibbleRegion(CUSTOM, 0, 0, 128, 128, strengths, 0, 128, mode); actual.releaseEvents();
                    same(expected, actual, CUSTOM);
                }
            }));
            for (var task : tasks) task.get();
        } finally { workers.shutdownNow(); restore(previous); }
    }

    private static Tile fixture(Layer layer, boolean present) {
        Tile tile = new Tile(-3, 4, -64, 320);
        if (present) { tile.inhibitEvents();
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) tile.setLayerValue(layer, x, y, (x + y * 3) % 16);
            tile.releaseEvents(); }
        return tile;
    }
    private static float[] strengths(int width, int height) {
        float[] values = new float[width * height];
        float[] cases = {0f, -0f, 1f, 0.5f, Float.NaN, Math.nextDown(0.5f / 14), Math.nextUp(0.5f / 14), 0.125f, 0.9f};
        for (int i = 0; i < values.length; i++) values[i] = cases[i % cases.length]; return values;
    }
    private static void oracle(Tile tile, Layer layer, int[] r, float[] strengths, int mode) {
        for (int y = 0; y < r[3]; y++) for (int x = 0; x < r[2]; x++) {
            int current = tile.getLayerValue(layer, r[0] + x, r[1] + y); float strength = strengths[y * r[2] + x];
            if (strength == 0f) continue;
            int target = mode == 0 ? 1 + Math.round(strength * 14) : mode == 1
                    ? 14 - Math.round(strength * 14) : 14 - (int) (strength * 14 + 0f);
            if (mode == 0 ? target > current : target < current) tile.setLayerValue(layer, r[0] + x, r[1] + y, target);
        }
    }
    private static void same(Tile left, Tile right, Layer layer) {
        assertEquals(left.hasLayer(layer), right.hasLayer(layer));
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) assertEquals(left.getLayerValue(layer, x, y), right.getLayerValue(layer, x, y));
    }
    private static int[] listen(Tile tile) {
        int[] counts = {0};
        tile.addListener((Tile.Listener) Proxy.newProxyInstance(Tile.Listener.class.getClassLoader(), new Class<?>[] {Tile.Listener.class},
                (p, m, a) -> { if (m.getName().equals("layerDataChanged")) counts[0]++; return null; })); return counts;
    }
    private static void restore(String previous) {
        if (previous == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, previous);
    }
}
