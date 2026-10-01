package org.pepsoft.worldpainter.painting;

import org.junit.Test;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

public class PaintFloodPagedParityTest {
    @Test public void largeWorldsAndMissingTilesMatchJava() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            for (String type : new String[] {"terrain", "biome", "nibble", "bit"}) for (boolean hole : new boolean[] {false, true}) {
                Dimension a = PaintFloodBenchmark.fixture(4), b = PaintFloodBenchmark.fixture(4);
                if (hole) { a.removeTile(-1, 0); b.removeTile(-1, 0); }
                for (int pass = 0; pass < 4; pass++) {
                    Paint paint = PaintFloodBenchmark.paint(type, pass & 1);
                    boolean undo = (type.equals("nibble") || type.equals("bit")) && (pass & 1) != 0;
                    PaintFloodParityTest.apply(a, paint, undo, false, -64, -64);
                    PaintFloodParityTest.apply(b, paint, undo, true, -64, -64);
                    PaintFloodParityTest.same(a, b);
                }
            }
        } finally { restore(old); }
    }
    @Test public void sparseHolesDoNotCycleAndRemovalKeepsItsOwnConnectivity() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension a = PaintFloodBenchmark.fixture(4), b = PaintFloodBenchmark.fixture(4);
            for (Dimension d : new Dimension[] {a, b}) for (var point : new java.util.ArrayList<>(d.getTileCoords()))
                if (!(point.x == -1 && point.y == -1) && !(point.x == 2 && point.y == 2)) d.removeTile(point.x, point.y);
            Paint paint = PaintFloodBenchmark.paint("bit", 0);
            PaintFloodParityTest.apply(a, paint, false, false, -64, -64);
            PaintFloodParityTest.apply(b, paint, false, true, -64, -64); PaintFloodParityTest.same(a, b);
            PaintFloodParityTest.apply(a, paint, true, false, -64, -64);
            PaintFloodParityTest.apply(b, paint, true, true, -64, -64); PaintFloodParityTest.same(a, b);
        } finally { restore(old); }
    }
    @Test public void undoRedoPreserveTheWholeLargeWorld() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension before = PaintFloodBenchmark.fixture(4), actual = PaintFloodBenchmark.fixture(4), expected = PaintFloodBenchmark.fixture(4);
            var undo = new org.pepsoft.util.undo.UndoManager(); for (Tile tile : actual.getTiles()) tile.register(undo); undo.armSavePoint();
            Paint paint = PaintFloodBenchmark.paint("biome", 0);
            PaintFloodParityTest.apply(expected, paint, false, false, -64, -64);
            PaintFloodParityTest.apply(actual, paint, false, true, -64, -64); PaintFloodParityTest.same(expected, actual);
            assertTrue(undo.undo()); PaintFloodParityTest.same(before, actual); assertTrue(undo.redo()); PaintFloodParityTest.same(expected, actual);
        } finally { restore(old); }
    }
    @Test public void componentsThatReenterATileKeepGlobalConnectivity() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension expected = PaintFloodBenchmark.fixture(4), actual = PaintFloodBenchmark.fixture(4);
            for (Dimension d : new Dimension[] {expected, actual}) {
                Tile first = d.getTile(-1, -1), bypass = d.getTile(-1, 0); first.inhibitEvents(); bypass.inhibitEvents();
                for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
                    first.setTerrain(x, y, x == 64 ? Terrain.STONE : Terrain.GRASS); bypass.setTerrain(x, y, Terrain.GRASS);
                }
                first.releaseEvents(); bypass.releaseEvents();
            }
            PaintFloodParityTest.apply(expected, new TerrainPaint(Terrain.DIRT), false, false, -96, -64);
            System.setProperty(Native.GEN_KEY, "true"); actual.setEventsInhibited(true);
            PaintFloodSession session = PaintFloodSession.tryStart(actual, -96, -64, null, Terrain.DIRT, 0, PaintFloodAccess.EQUAL);
            assertNotNull(session); while (!session.isComplete()) session.advance(); actual.setEventsInhibited(false);
            assertTrue(session.getNativeCalls() > session.getTouchedTiles()); PaintFloodParityTest.same(expected, actual);
            assertEquals(Terrain.DIRT, actual.getTile(-1, -1).getTerrain(127, 64));
        } finally { restore(old); }
    }
    @Test public void resumableWorkerVisitsEachFullTileOnce() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            Dimension expected = PaintFloodBenchmark.fixture(4), actual = PaintFloodBenchmark.fixture(4);
            PaintFloodParityTest.apply(expected, PaintFloodBenchmark.paint("bit", 0), false, false, -64, -64);
            System.setProperty(Native.GEN_KEY, "true"); actual.setEventsInhibited(true);
            PaintFloodSession session = PaintFloodSession.tryStart(actual, -64, -64, Frost.INSTANCE, null, 1, PaintFloodAccess.EQUAL);
            assertNotNull("JNI v2 must execute", session); assertFalse(session.isComplete()); assertEquals(1, session.getNativeCalls());
            session.advance(); assertEquals(2, session.getNativeCalls());
            worker.submit(() -> { while (!session.isComplete()) session.advance(); }).get(); actual.setEventsInhibited(false);
            assertEquals(16, session.getNativeCalls()); assertEquals(16, session.getTouchedTiles()); PaintFloodParityTest.same(expected, actual);
        } finally { worker.shutdownNow(); restore(old); }
    }
    private static void restore(String old) { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
}
