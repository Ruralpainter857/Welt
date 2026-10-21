package org.pepsoft.worldpainter.painting;

import org.junit.Test;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

public class FluidFloodPagedParityTest {
    private static FluidFloodSession start(Dimension d, int x, int y, boolean inverse, boolean lava) {
        System.setProperty(Native.GEN_KEY, "true"); d.setEventsInhibited(true);
        FluidFloodSession s = FluidFloodSession.tryStart(d, x, y, inverse, lava); assertNotNull("WLFD v2 must execute", s);
        return s;
    }
    private static void click(Dimension a, Dimension b, boolean inverse, boolean lava) {
        System.setProperty(Native.GEN_KEY, "false"); a.setEventsInhibited(true);
        FluidFloodBenchmark.scalar(a, -64, -64, inverse, lava); a.setEventsInhibited(false);
        FluidFloodSession s = start(b, -64, -64, inverse, lava);
        while (!s.isComplete()) s.advance(); b.setEventsInhibited(false); FluidFloodParityTest.same(a, b);
    }
    @Test public void raiseLowerAndConvertLargeWorldsWithHoles() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            for (boolean hole : new boolean[] {false, true}) {
                Dimension a = FluidFloodBenchmark.fixture(4), b = FluidFloodBenchmark.fixture(4);
                if (hole) { a.removeTile(0, 0); b.removeTile(0, 0); }
                a.getTile(-1, -1).setTerrain(1, 1, Terrain.CUSTOM_1); b.getTile(-1, -1).setTerrain(1, 1, Terrain.CUSTOM_1);
                for (int i = 0; i < 4; i++) click(a, b, false, false);
                click(a, b, false, true); click(a, b, true, true); click(a, b, true, false); click(a, b, false, false);
            }
        } finally { restore(old); }
    }
    @Test public void reenteringATileReachesItsOtherComponent() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension a = FluidFloodBenchmark.fixture(4), b = FluidFloodBenchmark.fixture(4);
            for (Dimension d : new Dimension[] {a, b}) {
                Tile t = d.getTile(-1, -1); t.inhibitEvents();
                for (int y = 0; y < 128; y++) t.setHeight(64, y, 100); t.releaseEvents();
            }
            System.setProperty(Native.GEN_KEY, "false"); a.setEventsInhibited(true);
            FluidFloodBenchmark.scalar(a, -96, -64, false, true); a.setEventsInhibited(false);
            FluidFloodSession s = start(b, -96, -64, false, true);
            while (!s.isComplete()) s.advance(); b.setEventsInhibited(false);
            assertTrue(s.getNativeCalls() > s.getTouchedTiles()); FluidFloodParityTest.same(a, b);
        } finally { restore(old); }
    }
    @Test public void workerHandoffVisitsEveryFullTileOnce() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            Dimension a = FluidFloodBenchmark.fixture(4), b = FluidFloodBenchmark.fixture(4);
            System.setProperty(Native.GEN_KEY, "false"); a.setEventsInhibited(true);
            FluidFloodBenchmark.scalar(a, -64, -64, false, true); a.setEventsInhibited(false);
            FluidFloodSession s = start(b, -64, -64, false, true); assertEquals(1, s.getNativeCalls()); s.advance();
            worker.submit(() -> { while (!s.isComplete()) s.advance(); }).get(); b.setEventsInhibited(false);
            assertEquals(16, s.getNativeCalls()); assertEquals(16, s.getTouchedTiles()); FluidFloodParityTest.same(a, b);
        } finally { worker.shutdownNow(); restore(old); }
    }
    @Test public void partialCancellationRestoresAllPlanesAndCompletedFillCanRedo() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension before = FluidFloodBenchmark.fixture(4), actual = FluidFloodBenchmark.fixture(4);
            var undo = new org.pepsoft.util.undo.UndoManager(); for (Tile tile : actual.getTiles()) tile.register(undo); undo.armSavePoint();
            FluidFloodSession s = start(actual, -64, -64, false, true); s.advance(); actual.setEventsInhibited(false);
            assertFalse(s.isComplete()); assertTrue(undo.undo()); FluidFloodParityTest.same(before, actual); undo.clearRedo(); undo.armSavePoint();
            Dimension expected = FluidFloodBenchmark.fixture(4); click(expected, actual, false, true);
            assertTrue(undo.undo()); FluidFloodParityTest.same(before, actual); assertTrue(undo.redo()); FluidFloodParityTest.same(expected, actual);
        } finally { restore(old); }
    }
    @Test public void customTilesAndUnsupportedCoordinatesFallbackBeforeWriting() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension d = FluidFloodBenchmark.fixture(4), expected = FluidFloodBenchmark.fixture(4);
            System.setProperty(Native.GEN_KEY, "true");
            assertNull(FluidFloodSession.tryStart(d, -64, -64, false, false)); FluidFloodParityTest.same(expected, d);
            d.setEventsInhibited(true); assertNull(FluidFloodSession.tryStart(d, 99999, 99999, false, false));
            d.addTile(new Tile(3, 0, d.getMinHeight(), d.getMaxHeight()) {}); assertNull(FluidFloodSession.tryStart(d, -64, -64, false, false));
            d.setEventsInhibited(false);
        } finally { restore(old); }
    }
    @Test public void tallUnsignedLevelsAndNegativeMinimumMatch() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension a = FluidFloodBenchmark.fixture(4), b = FluidFloodBenchmark.fixture(4);
            for (Dimension d : new Dimension[] {a, b}) {
                d.setMaxHeight(65536);
                for (Tile t : d.getTiles()) { t.inhibitEvents(); t.setMinMaxHeight(-64, 65536, HeightTransform.IDENTITY);
                    for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) t.setWaterLevel(x, y, 40000);
                    t.releaseEvents();
                }
            }
            click(a, b, false, true); click(a, b, true, true); click(a, b, false, false);
        } finally { restore(old); }
    }
    @Test public void runnerProcessesTheActualToolPath() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension a = FluidFloodBenchmark.fixture(4), b = FluidFloodBenchmark.fixture(4);
            System.setProperty(Native.GEN_KEY, "false"); a.setEventsInhibited(true);
            FluidFloodBenchmark.scalar(a, -64, -64, false, true); a.setEventsInhibited(false);
            System.setProperty(Native.GEN_KEY, "true"); b.setEventsInhibited(true);
            assertEquals(Boolean.TRUE, FluidFloodRunner.tryFill(b, -64, -64, false, true, "Fluid parity", null));
            b.setEventsInhibited(false); FluidFloodParityTest.same(a, b);
        } finally { restore(old); }
    }
    @Test public void deferredNotificationsMatchAcrossTileBoundaries() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension a = FluidFloodBenchmark.fixture(4), b = FluidFloodBenchmark.fixture(4);
            a.removeTile(0, 0); b.removeTile(0, 0);
            var ea = FluidFloodParityTest.events(a); var eb = FluidFloodParityTest.events(b);
            click(a, b, false, true); assertEquals(ea, eb);
            click(a, b, false, false); assertEquals(ea, eb);
            click(a, b, true, false); assertEquals(ea, eb);
        } finally { restore(old); }
    }
    @Test public void truncatedWaterLevelsFallbackWithoutMutation() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension d = FluidFloodBenchmark.fixture(4); d.setMaxHeight(65536);
            for (Tile t : d.getTiles()) { t.inhibitEvents(); t.setMinMaxHeight(-64, 65536, HeightTransform.IDENTITY);
                for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) t.setWaterLevel(x, y, 65471);
                t.releaseEvents();
            }
            System.setProperty(Native.GEN_KEY, "true"); d.setEventsInhibited(true);
            assertNull(FluidFloodSession.tryStart(d, -64, -64, false, false)); d.setEventsInhibited(false);
            for (Tile t : d.getTiles()) for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++)
                assertEquals(65471, t.getWaterLevel(x, y));
        } finally { restore(old); }
    }
    private static void restore(String old) { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
}
