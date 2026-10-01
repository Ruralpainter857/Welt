package org.pepsoft.worldpainter.painting;

import java.nio.*;
import org.junit.Test;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import org.pepsoft.util.undo.UndoManager;
import static org.junit.Assert.*;

public class FluidFloodParityTest {
    static void same(Dimension a, Dimension b) {
        assertEquals(a.getTileCoords(), b.getTileCoords());
        for (Tile ta : a.getTiles()) {
            Tile tb = b.getTile(ta.getX(), ta.getY()); assertEquals(ta.getLayers(), tb.getLayers());
            for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
                assertEquals(ta.getRawHeight(x, y), tb.getRawHeight(x, y));
                assertEquals(ta.getTerrain(x, y), tb.getTerrain(x, y));
                assertEquals(ta.getWaterLevel(x, y), tb.getWaterLevel(x, y));
                assertEquals(ta.getBitLayerValue(FloodWithLava.INSTANCE, x, y), tb.getBitLayerValue(FloodWithLava.INSTANCE, x, y));
            }
        }
    }
    private static void click(Dimension a, Dimension b, int sx, int sy, boolean inverse, boolean lava) {
        System.setProperty(Native.GEN_KEY, "false"); a.setEventsInhibited(true);
        FluidFloodBenchmark.scalar(a, sx, sy, inverse, lava); a.setEventsInhibited(false);
        System.setProperty(Native.GEN_KEY, "true"); b.setEventsInhibited(true);
        assertTrue("JNI must handle this zone", FluidFloodAccess.tryFill(b, sx, sy, inverse, lava)); b.setEventsInhibited(false);
        same(a, b);
    }
    @Test public void raiseLowerConvertAndHolesMatchRealJavaFiller() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            for (boolean hole : new boolean[] {false, true}) {
                Dimension a = FluidFloodBenchmark.fixture(), b = FluidFloodBenchmark.fixture();
                if (hole) { a.removeTile(0, -1); b.removeTile(0, -1); }
                for (int pass = 0; pass < 8; pass++) click(a, b, -64, -64, false, (pass & 3) == 2);
                click(a, b, -64, -64, true, false); click(a, b, -64, -64, true, true);
                click(a, b, 64, 64, false, true); click(a, b, 64, 64, true, true);
                click(a, b, 127, 127, false, false); click(a, b, 127, 127, true, false);
                if (hole) click(a, b, 64, -64, true, false);
            }
        } finally { restore(old); }
    }
    @Test public void shortWaterAndUnsignedTallWaterStayExact() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            for (boolean tall : new boolean[] {false, true}) {
                int min = tall ? -64 : 0, max = tall ? 65536 : 256;
                var factory = new HeightMapTileFactory(0L, new org.pepsoft.worldpainter.heightMaps.ConstantHeightMap(50), min, max,
                        false, org.pepsoft.worldpainter.themes.SimpleTheme.createSingleTerrain(Terrain.GRASS, min, max, 62));
                Dimension a = new Dimension(FluidFloodBenchmark.world(), "Fluid", 0, factory, Dimension.Anchor.NORMAL_DETAIL, false);
                Dimension b = new Dimension(FluidFloodBenchmark.world(), "Fluid", 0, factory, Dimension.Anchor.NORMAL_DETAIL, false);
                for (Dimension d : new Dimension[] {a, b}) {
                    Tile t = new Tile(-1, -1, min, max); t.inhibitEvents();
                    for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) { t.setHeight(x, y, 50.5f); t.setWaterLevel(x, y, tall ? 40000 : 100); }
                    t.setTerrain(0, 0, Terrain.CUSTOM_1); t.releaseEvents(); d.addTile(t);
                }
                click(a, b, -64, -64, false, true); click(a, b, -64, -64, true, true);
                click(a, b, -64, -64, true, false); click(a, b, -64, -64, false, false);
            }
        } finally { restore(old); }
    }
    @Test public void undoRedoAndUnsupportedCasesPreserveAllPlanes() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension before = FluidFloodBenchmark.fixture(), actual = FluidFloodBenchmark.fixture(), expected = FluidFloodBenchmark.fixture();
            UndoManager undo = new UndoManager(); for (Tile tile : actual.getTiles()) tile.register(undo); undo.armSavePoint();
            click(expected, actual, -64, -64, false, true);
            assertTrue(undo.undo()); same(before, actual); assertTrue(undo.redo()); same(expected, actual);
            assertFalse(FluidFloodAccess.tryFill(actual, -64, -64, false, true)); same(expected, actual);
            actual.addTile(new Tile(32, 0, actual.getMinHeight(), actual.getMaxHeight())); actual.setEventsInhibited(true);
            assertFalse(FluidFloodAccess.tryFill(actual, -64, -64, false, true)); actual.setEventsInhibited(false);
        } finally { restore(old); }
    }
    static java.util.Map<String, Integer> events(Dimension d) {
        var events = new java.util.TreeMap<String, Integer>();
        for (Tile tile : d.getTiles()) tile.addListener((Tile.Listener) java.lang.reflect.Proxy.newProxyInstance(Tile.Listener.class.getClassLoader(),
                new Class<?>[] {Tile.Listener.class}, (p, m, a) -> {
                    events.merge(tile.getX() + "/" + tile.getY() + "/" + m.getName(), 1, Integer::sum); return null;
                }));
        return events;
    }
    @Test public void rectangularRegionsAndDeferredEventsMatch() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension a = FluidFloodBenchmark.fixture(), b = FluidFloodBenchmark.fixture();
            for (Dimension d : new Dimension[] {a, b}) { d.removeTile(-1, 0); d.removeTile(0, 0); }
            var ea = events(a); var eb = events(b);
            click(a, b, -64, -64, false, true); assertEquals(ea, eb);
            click(a, b, -64, -64, false, false); assertEquals(ea, eb);
            click(a, b, -64, -64, true, false); assertEquals(ea, eb);
        } finally { restore(old); }
    }
    @Test public void malformedAbiIsRejectedBeforeMutation() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            ByteBuffer d = ByteBuffer.allocateDirect(74).order(ByteOrder.LITTLE_ENDIAN);
            d.putInt(0, 0x44464c57).putInt(4, 1).putInt(8, 1).putInt(12, 1).putInt(24, 0).putInt(28, 51).putInt(32, 2);
            byte[] before = new byte[74]; d.duplicate().get(before);
            assertFalse(NativeSlices.floodFluidRegion(d)); byte[] after = new byte[74]; d.duplicate().get(after); assertArrayEquals(before, after);
            assertFalse(NativeSlices.floodFluidRegion(d.asReadOnlyBuffer())); assertFalse(NativeSlices.floodFluidRegion(ByteBuffer.allocate(74)));
        } finally { restore(old); }
    }
    private static void restore(String old) { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
}
