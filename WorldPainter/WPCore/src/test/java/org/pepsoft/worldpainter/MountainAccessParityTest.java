package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

public class MountainAccessParityTest {
    @Test public void compactAndFallbackKeepRawQuantisationAndExceptionalStrengths() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            for (boolean tall : new boolean[] {false, true}) for (boolean inverse : new boolean[] {false, true}) {
                Tile expected = fixture(tall), actual = fixture(tall), fallback = fixture(tall);
                float[] forces = {0f, -1f, 0.3f, Float.NaN, Float.POSITIVE_INFINITY, 1f, 1.25f, Float.NEGATIVE_INFINITY, 0.5f};
                for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) {
                    float current = expected.getHeight(125 + x, 125 + y);
                    float target = MountainBrushBenchmark.target(-3 + x, -131 + y, forces[x * 3 + y], -64, 383, 180.25f, 1.25f, inverse);
                    if (inverse ? target < current : target > current) expected.setHeight(125 + x, 125 + y, target);
                }
                actual.inhibitEvents(); fallback.inhibitEvents(); System.setProperty(Native.GEN_KEY, "true");
                short[] shorts = tall ? null : new short[16384]; int[] ints = tall ? new int[16384] : null;
                for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                    if (tall) ints[x + y * 128] = actual.getRawHeight(x, y); else shorts[x + y * 128] = (short) actual.getRawHeight(x, y);
                }
                assertNotNull("WHED v3 JNI must execute", MountainAccess.edit(-1, -2, -64, 383, 125, 125, 3, 3,
                        forces, 0, 3, 180.25f, 1.25f, inverse, shorts, ints));
                actual.editMountainRegion(125, 125, 3, 3, forces, 0, 3, 180.25f, 1.25f, inverse, -64, 383);
                System.setProperty(Native.GEN_KEY, "false");
                fallback.editMountainRegion(125, 125, 3, 3, forces, 0, 3, 180.25f, 1.25f, inverse, -64, 383);
                actual.releaseEvents(); fallback.releaseEvents(); compare(expected, actual); compare(expected, fallback);
            }
        } finally { restore(old); }
    }
    @Test public void crossesTilesAndHolesWithUndoAndJavaFallback() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (int radius : new int[] {63, 127, 255}) {
                Dimension expected = ErosionRegionBenchmark.fixture(), actual = ErosionRegionBenchmark.fixture();
                UndoManager undo = new UndoManager(); actual.registerUndoManager(undo); undo.armSavePoint(); float before = actual.getHeightAt(-1, -1);
                expected.setEventsInhibited(true); actual.setEventsInhibited(true); float[] forces = MountainBrushBenchmark.forces(radius);
                for (int stroke = 0; stroke < 4; stroke++) {
                    boolean inverse = (stroke & 1) != 0; float peak = inverse ? 64 : 220;
                    MountainBrushBenchmark.scalar(expected, -radius, -radius, radius * 2 + 1, forces, peak, 1.25f, inverse);
                    assertTrue(MountainAccess.tryApply(actual, -radius, -radius, radius * 2 + 1, forces, peak, 1.25f, inverse));
                }
                expected.setEventsInhibited(false); actual.setEventsInhibited(false);
                for (Tile tile : expected.getTiles()) compare(tile, actual.getTile(tile.getX(), tile.getY()));
                assertTrue(undo.undo()); assertEquals(before, actual.getHeightAt(-1, -1), 0f);
                assertTrue(undo.redo()); assertEquals(expected.getHeightAt(-1, -1), actual.getHeightAt(-1, -1), 0f);
                assertFalse(MountainAccess.tryApply(actual, 0, 0, 1, new float[] {1f}, 200, 1, false));
            }
        } finally { restore(old); }
    }
    private static Tile fixture(boolean tall) {
        Tile tile = new Tile(-1, -2, -64, tall ? 320 : 192);
        for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) tile.setRawHeight(x, y, (x * 971 + y * 353) & 65535);
        return tile;
    }
    private static void compare(Tile expected, Tile actual) {
        for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
            assertEquals(expected.getRawHeight(x, y), actual.getRawHeight(x, y)); assertEquals(expected.getTerrain(x, y), actual.getTerrain(x, y));
        }
        assertEquals(expected.getLayers(), actual.getLayers());
    }
    private static void restore(String old) { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
}
