package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

public class PyramidAccessParityTest {
    @Test public void smallGroupsAndShortStorageMatchScalarSetters() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (int max : new int[] {128, 256, 384}) for (boolean rotated : new boolean[] {false, true}) {
                Dimension expected = PyramidRegionBenchmark.fixture(), actual = PyramidRegionBenchmark.fixture();
                for (Dimension dimension : new Dimension[] {expected, actual}) {
                    for (Tile tile : new java.util.ArrayList<>(dimension.getTiles())) dimension.removeTile(tile.getX(), tile.getY());
                    dimension.setMinHeight(0); dimension.setMaxHeight(max);
                    Tile tile = new Tile(0, 0, 0, max);
                    for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) tile.setHeight(x, y, 62);
                    dimension.addTile(tile); dimension.setEventsInhibited(true);
                }
                for (int stroke = 0; stroke < 8; stroke++) {
                    PyramidRegionBenchmark.scalar(expected, 64, 64, rotated);
                    assertTrue(PyramidAccess.tryApply(actual, 64, 64, rotated));
                }
                expected.setEventsInhibited(false); actual.setEventsInhibited(false); compare(expected, actual);
            }
        } finally { restore(old); }
    }
    @Test public void shapesMatchAcrossNegativeCoordinatesHolesAndTopHeight() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (boolean rotated : new boolean[] {false, true}) for (int centre : new int[] {-1, 127}) {
                Dimension expected = PyramidRegionBenchmark.fixture(), actual = PyramidRegionBenchmark.fixture();
                float top = expected.getMaxHeight() - 2f;
                expected.setHeightAt(centre, centre, top); actual.setHeightAt(centre, centre, top);
                expected.setEventsInhibited(true); actual.setEventsInhibited(true);
                for (int stroke = 0; stroke < 3; stroke++) {
                    PyramidRegionBenchmark.scalar(expected, centre, centre, rotated);
                    assertTrue("The compact JNI path must execute", PyramidAccess.tryApply(actual, centre, centre, rotated));
                }
                expected.setEventsInhibited(false); actual.setEventsInhibited(false); compare(expected, actual);
            }
        } finally { restore(old); }
    }
    @Test public void undoAndFallbackKeepBothPlanesAndOtherLayers() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            Dimension dimension = PyramidRegionBenchmark.fixture(); Tile tile = dimension.getTile(-1, -1);
            tile.setBitLayerValue(org.pepsoft.worldpainter.layers.Frost.INSTANCE, 127, 127, true);
            float before = dimension.getHeightAt(-1, -1); Terrain terrain = dimension.getTerrainAt(-1, -1);
            UndoManager undo = new UndoManager(); dimension.registerUndoManager(undo); undo.armSavePoint();
            dimension.setEventsInhibited(true); assertTrue(PyramidAccess.tryApply(dimension, -1, -1, false)); dimension.setEventsInhibited(false);
            assertEquals(before + 1f, dimension.getHeightAt(-1, -1), 0f); assertEquals(Terrain.SANDSTONE, dimension.getTerrainAt(-1, -1));
            assertTrue(undo.undo()); assertEquals(before, dimension.getHeightAt(-1, -1), 0f); assertEquals(terrain, dimension.getTerrainAt(-1, -1));
            assertTrue(undo.redo()); assertEquals(before + 1f, dimension.getHeightAt(-1, -1), 0f);
            assertTrue(tile.getBitLayerValue(org.pepsoft.worldpainter.layers.Frost.INSTANCE, 127, 127));
            assertFalse(PyramidAccess.tryApply(dimension, -1, -1, false)); dimension.setEventsInhibited(true);
            System.setProperty(Native.GEN_KEY, "false"); assertFalse(PyramidAccess.tryApply(dimension, -1, -1, true));
            assertEquals(before + 1f, dimension.getHeightAt(-1, -1), 0f); dimension.setEventsInhibited(false);
        } finally { restore(old); }
    }
    private static void compare(Dimension expected, Dimension actual) {
        for (Tile tile : expected.getTiles()) for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
            Tile other = actual.getTile(tile.getX(), tile.getY());
            assertEquals(tile.getRawHeight(x, y), other.getRawHeight(x, y));
            assertEquals(tile.getTerrain(x, y), other.getTerrain(x, y));
        }
    }
    private static void restore(String old) { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
}
