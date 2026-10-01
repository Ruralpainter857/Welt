package org.pepsoft.worldpainter.painting;

import java.nio.*;
import org.junit.Test;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import static org.junit.Assert.*;

public class HeightFloodParityTest {
    @Test public void fullToolMatchesAcrossHolesAndFractionalHeights() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            for (int side : new int[] {2, 4}) for (boolean hole : new boolean[] {false, true}) {
                Dimension a = FluidFloodBenchmark.fixture(side), b = FluidFloodBenchmark.fixture(side);
                if (hole) { a.removeTile(0, -1); b.removeTile(0, -1); }
                for (Dimension d : new Dimension[] {a, b}) {
                    Tile t = d.getTile(-1, -1); t.inhibitEvents();
                    for (int y = 0; y < 128; y++) { t.setHeight(100, y, y == 64 ? 50.499f : 50.5f); }
                    t.setTerrain(1, 1, Terrain.CUSTOM_1); t.releaseEvents();
                }
                var ea = FluidFloodParityTest.events(a); var eb = FluidFloodParityTest.events(b);
                for (int pass = 0; pass < 5; pass++) {
                    HeightFloodBenchmark.fill(a, -64, -64, false); HeightFloodBenchmark.fill(b, -64, -64, true);
                    FluidFloodParityTest.same(a, b); assertEquals(ea, eb);
                }
            }
        } finally { restore(old); }
    }
    @Test public void disconnectedComponentsCanReenterTheFirstTile() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension a = FluidFloodBenchmark.fixture(4), b = FluidFloodBenchmark.fixture(4);
            for (Dimension d : new Dimension[] {a, b}) {
                Tile t = d.getTile(-1, -1); t.inhibitEvents(); for (int y = 0; y < 128; y++) t.setHeight(64, y, 100); t.releaseEvents();
            }
            HeightFloodBenchmark.fill(a, -96, -64, false);
            System.setProperty(Native.GEN_KEY, "true"); b.setEventsInhibited(true);
            HeightFloodSession s = HeightFloodSession.tryStart(b, -96, -64); assertNotNull(s);
            while (!s.isComplete()) s.advance(); b.setEventsInhibited(false);
            assertTrue(s.getNativeCalls() > s.getTouchedTiles()); FluidFloodParityTest.same(a, b);
        } finally { restore(old); }
    }
    @Test public void missingTilesStillConnectSeparatedRealTiles() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension a = FluidFloodBenchmark.fixture(4), b = FluidFloodBenchmark.fixture(4);
            for (Dimension d : new Dimension[] {a, b}) for (var point : new java.util.ArrayList<>(d.getTileCoords()))
                if (!(point.x == -1 && point.y == -1) && !(point.x == 2 && point.y == 2)) d.removeTile(point.x, point.y);
            HeightFloodBenchmark.fill(a, -64, -64, false); HeightFloodBenchmark.fill(b, -64, -64, true);
            FluidFloodParityTest.same(a, b); assertEquals(51, b.getIntHeightAt(300, 300));
        } finally { restore(old); }
    }
    @Test public void workerHandoffAndUndoRedoKeepAllPlanes() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            Dimension before = FluidFloodBenchmark.fixture(4), actual = FluidFloodBenchmark.fixture(4), expected = FluidFloodBenchmark.fixture(4);
            var undo = new org.pepsoft.util.undo.UndoManager(); actual.registerUndoManager(undo); undo.armSavePoint();
            HeightFloodBenchmark.fill(expected, -64, -64, false);
            System.setProperty(Native.GEN_KEY, "true"); actual.setEventsInhibited(true);
            HeightFloodSession s = HeightFloodSession.tryStart(actual, -64, -64); assertNotNull(s); assertEquals(1, s.getNativeCalls());
            worker.submit(() -> { while (!s.isComplete()) s.advance(); }).get(); actual.setEventsInhibited(false);
            assertEquals(16, s.getNativeCalls()); FluidFloodParityTest.same(expected, actual);
            assertTrue(undo.undo()); FluidFloodParityTest.same(before, actual); assertTrue(undo.redo()); FluidFloodParityTest.same(expected, actual);
        } finally { worker.shutdownNow(); restore(old); }
    }
    @Test public void invalidAbiAndUnsupportedTilesDoNotWrite() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true"); ByteBuffer d = ByteBuffer.allocateDirect(84032).order(ByteOrder.LITTLE_ENDIAN);
            d.putInt(0, 0x48464c57).putInt(4, 1).putInt(8, 128).putInt(12, 128).putInt(16, 51).putInt(24, 1);
            byte[] before = new byte[d.capacity()]; d.duplicate().get(before); assertFalse(NativeSlices.floodHeightRegion(d));
            byte[] after = new byte[d.capacity()]; d.duplicate().get(after); assertArrayEquals(before, after);
            assertFalse(NativeSlices.floodHeightRegion(d.asReadOnlyBuffer()));
            Dimension actual = FluidFloodBenchmark.fixture(4), expected = FluidFloodBenchmark.fixture(4);
            assertNull(HeightFloodSession.tryStart(actual, -64, -64)); FluidFloodParityTest.same(expected, actual);
            actual.setEventsInhibited(true); actual.addTile(new Tile(3, 0, actual.getMinHeight(), actual.getMaxHeight()) {});
            assertNull(HeightFloodSession.tryStart(actual, -64, -64)); actual.setEventsInhibited(false);
        } finally { restore(old); }
    }
    @Test public void shortStorageAndHeightCeilingStayExact() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            for (int min : new int[] {0, -128}) {
                int max = min + 256;
                var factory = new HeightMapTileFactory(0L, new org.pepsoft.worldpainter.heightMaps.ConstantHeightMap(min + 50), min, max, false,
                        org.pepsoft.worldpainter.themes.SimpleTheme.createSingleTerrain(Terrain.GRASS, min, max, min + 62));
                Dimension a = new Dimension(FluidFloodBenchmark.world(), "Fill", 0, factory, Dimension.Anchor.NORMAL_DETAIL, false);
                Dimension b = new Dimension(FluidFloodBenchmark.world(), "Fill", 0, factory, Dimension.Anchor.NORMAL_DETAIL, false);
                for (Dimension d : new Dimension[] {a, b}) for (int tx = -1; tx <= 0; tx++) {
                    Tile t = new Tile(tx, -1, min, max); t.inhibitEvents();
                    for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
                        t.setHeight(x, y, min + 50.499f); t.setWaterLevel(x, y, min + 70);
                    }
                    t.setHeight(0, 0, max - 1); t.releaseEvents(); d.addTile(t);
                }
                for (int pass = 0; pass < 3; pass++) {
                    HeightFloodBenchmark.fill(a, -64, -64, false); HeightFloodBenchmark.fill(b, -64, -64, true); FluidFloodParityTest.same(a, b);
                }
                HeightFloodBenchmark.fill(a, -128, -128, false); HeightFloodBenchmark.fill(b, -128, -128, true); FluidFloodParityTest.same(a, b);
            }
        } finally { restore(old); }
    }
    private static void restore(String old) { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
}
