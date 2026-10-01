package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

public class RiverAccessParityTest {
    @Test public void repeatedStrokesPreserveAllPlanesAndTheFallingWaterLevel() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (int radius : new int[] {3, 63, 127, 255}) {
                Dimension expected = ErosionRegionBenchmark.fixture(), actual = ErosionRegionBenchmark.fixture();
                expected.setEventsInhibited(true); actual.setEventsInhibited(true);
                float[] forces = MountainBrushBenchmark.forces(radius), slopes = RiverRegionBenchmark.slopes(forces); int previous = 52;
                forces[0] = .25f; forces[1] = Float.NaN; forces[2] = .250001f; slopes = RiverRegionBenchmark.slopes(forces);
                for (int stroke = 0; stroke < 4; stroke++) {
                    boolean lava = (stroke & 1) != 0;
                    int java = RiverRegionBenchmark.scalar(expected, -radius, -radius, radius * 2 + 1, forces, slopes, previous, 5f, lava);
                    Integer rust = RiverAccess.tryApply(actual, -radius, -radius, radius * 2 + 1, forces, slopes, previous, 5f, lava);
                    assertNotNull("Compact JNI must execute", rust); assertEquals(java, rust.intValue()); previous = java;
                    for (Tile tile : expected.getTiles()) compare(tile, actual.getTile(tile.getX(), tile.getY()));
                }
                expected.setEventsInhibited(false); actual.setEventsInhibited(false);
            }
        } finally { restore(old); }
    }
    @Test public void shortAndTallWaterRemainUnsignedAndUndoPreservesTheWholeEdit() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            for (boolean tall : new boolean[] {false, true}) {
                Dimension expected = ErosionRegionBenchmark.fixture(), actual = ErosionRegionBenchmark.fixture();
                for (Dimension d : new Dimension[] {expected, actual}) {
                    for (Tile tile : new java.util.ArrayList<>(d.getTiles())) d.removeTile(tile.getX(), tile.getY());
                    d.setMinHeight(-64); d.setMaxHeight(tall ? 320 : 192);
                    Tile tile = new Tile(0, 0, -64, tall ? 320 : 192);
                    for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
                        tile.setHeight(x, y, 62.5f); tile.setWaterLevel(x, y, tall ? 40000 : 180);
                    }
                    d.addTile(tile); d.setEventsInhibited(true);
                }
                UndoManager undo = new UndoManager(); actual.registerUndoManager(undo); undo.armSavePoint();
                float[] forces = MountainBrushBenchmark.forces(3), slopes = RiverRegionBenchmark.slopes(forces);
                int java = RiverRegionBenchmark.scalar(expected, 61, 61, 7, forces, slopes, 70, 5f, true);
                assertEquals(Integer.valueOf(java), RiverAccess.tryApply(actual, 61, 61, 7, forces, slopes, 70, 5f, true));
                expected.setEventsInhibited(false); actual.setEventsInhibited(false); compare(expected.getTile(0, 0), actual.getTile(0, 0));
                assertTrue(undo.undo()); assertEquals(62.5f, actual.getHeightAt(64, 64), 0f); assertFalse(actual.getBitLayerValueAt(FloodWithLava.INSTANCE, 64, 64));
                assertTrue(undo.redo()); compare(expected.getTile(0, 0), actual.getTile(0, 0));
                assertNull(RiverAccess.tryApply(actual, 61, 61, 7, forces, slopes, 70, 5f, false));
            }
        } finally { restore(old); }
    }
    private static void compare(Tile expected, Tile actual) {
        for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
            assertEquals(expected.getRawHeight(x, y), actual.getRawHeight(x, y)); assertEquals(expected.getWaterLevel(x, y), actual.getWaterLevel(x, y));
            assertEquals(expected.getTerrain(x, y), actual.getTerrain(x, y)); assertEquals(expected.getBitLayerValue(FloodWithLava.INSTANCE, x, y), actual.getBitLayerValue(FloodWithLava.INSTANCE, x, y));
        }
        assertEquals(expected.getLayers(), actual.getLayers());
    }
    private static void restore(String old) { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
}
