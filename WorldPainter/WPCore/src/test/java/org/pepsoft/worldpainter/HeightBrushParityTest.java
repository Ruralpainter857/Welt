package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

public class HeightBrushParityTest {
    @Test public void compactKernelsMatchScalarForAllModesAndHeightFormats() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            float[] forces = {0f, -1f, Float.NaN, 0.25f, 0.7f, 1f, 2f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
            for (boolean tall : new boolean[] {false, true}) for (int mode = 0; mode <= 4; mode++)
                for (float value : new float[] {0f, -3.125f, 63.123f, Float.NaN, Float.POSITIVE_INFINITY}) {
                    Tile expected = fixture(tall), actual = fixture(tall);
                    float min = -64, max = tall ? 319 : 191;
                    for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) {
                        float strength = forces[x * 3 + y], current = expected.getHeight(125 + x, 125 + y);
                        float target = mode == 0 ? Math.min(current + value, max) : mode == 1 ? Math.max(current - value, min) : value;
                        if (strength > 0f) {
                            float edited = strength * target + (1f - strength) * current;
                            if (mode == 2 || ((mode == 0 || mode == 3) ? edited > current : edited < current))
                                expected.setHeight(125 + x, 125 + y, edited);
                        }
                    }
                    actual.inhibitEvents();
                    HeightBrushAccess.Scratch result = HeightBrushAccess.edit(-64, 125, 125, 3, 3, forces, 0, 3, mode, value,
                            min, max, tall ? null : shorts(actual), tall ? ints(actual) : null);
                    assertNotNull("WHED v2 must execute through JNI", result);
                    actual.editHeightBrushRegion(125, 125, 3, 3, forces, 0, 3, mode, value, min, max);
                    actual.releaseEvents();
                    compare(expected, actual);
                    Tile fallback = fixture(tall); fallback.inhibitEvents(); System.setProperty(Native.GEN_KEY, "false");
                    fallback.editHeightBrushRegion(125, 125, 3, 3, forces, 0, 3, mode, value, min, max);
                    fallback.releaseEvents(); System.setProperty(Native.GEN_KEY, "true"); compare(expected, fallback);
                }
        } finally { restore(old); }
    }

    @Test public void regionCrossesTilesHolesAndPreservesUndo() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            Dimension expected = ErosionRegionBenchmark.fixture(), actual = ErosionRegionBenchmark.fixture();
            UndoManager undo = new UndoManager(); actual.registerUndoManager(undo); undo.armSavePoint();
            float before = actual.getHeightAt(-1, -1);
            float[] forces = new float[255 * 255];
            expected.setEventsInhibited(true); actual.setEventsInhibited(true);
            for (int mode = 0; mode <= 4; mode++) {
                HeightBrushBenchmark.strengths(forces, 127, mode);
                HeightBrushBenchmark.scalar(expected, -63, -63, 255, forces, mode, 64.5f);
                assertTrue(HeightBrushAccess.tryApply(actual, -63, -63, 255, 255, forces, mode, 64.5f,
                        actual.getMinHeight(), actual.getMaxHeight() - 1));
            }
            expected.setEventsInhibited(false); actual.setEventsInhibited(false);
            for (Tile tile : expected.getTiles()) compare(tile, actual.getTile(tile.getX(), tile.getY()));
            assertTrue(undo.undo()); assertEquals(before, actual.getHeightAt(-1, -1), 0f);
            assertTrue(undo.redo()); assertEquals(expected.getHeightAt(-1, -1), actual.getHeightAt(-1, -1), 0f);
            assertFalse(HeightBrushAccess.tryApply(actual, 0, 0, 1, 1, new float[] {1f}, 0, 3f, -64, 319));
        } finally { restore(old); }
    }

    private static Tile fixture(boolean tall) {
        Tile tile = new Tile(0, 0, -64, tall ? 320 : 192);
        for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) tile.setRawHeight(x, y, (x * 971 + y * 353) & 65535);
        return tile;
    }
    private static short[] shorts(Tile tile) { short[] data = new short[16384]; for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) data[x + y * 128] = (short) tile.getRawHeight(x, y); return data; }
    private static int[] ints(Tile tile) { int[] data = new int[16384]; for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) data[x + y * 128] = tile.getRawHeight(x, y); return data; }
    private static void compare(Tile a, Tile b) { for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) assertEquals(a.getRawHeight(x, y), b.getRawHeight(x, y)); }
    private static void restore(String old) { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
}
