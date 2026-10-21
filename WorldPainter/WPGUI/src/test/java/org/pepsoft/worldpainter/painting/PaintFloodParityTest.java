package org.pepsoft.worldpainter.painting;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

public class PaintFloodParityTest {
    static void same(Dimension a, Dimension b) {
        assertEquals(a.getTileCoords(), b.getTileCoords());
        for (Tile ta : a.getTiles()) {
            Tile tb = b.getTile(ta.getX(), ta.getY()); assertEquals(ta.getLayers(), tb.getLayers());
            for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
                assertEquals(ta.getRawHeight(x, y), tb.getRawHeight(x, y)); assertEquals(ta.getWaterLevel(x, y), tb.getWaterLevel(x, y));
                assertEquals(ta.getTerrain(x, y), tb.getTerrain(x, y));
                for (Layer layer : ta.getLayers()) {
                    if (layer.dataSize == Layer.DataSize.BIT || layer.dataSize == Layer.DataSize.BIT_PER_CHUNK)
                        assertEquals(ta.getBitLayerValue(layer, x, y), tb.getBitLayerValue(layer, x, y));
                    else assertEquals(ta.getLayerValue(layer, x, y), tb.getLayerValue(layer, x, y));
                }
            }
        }
    }
    static void apply(Dimension d, Paint paint, boolean inverse, boolean nativeMode, int x, int y) {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
        DimensionPainter painter = new DimensionPainter(); painter.setPaint(paint); painter.setUndo(inverse);
        d.setEventsInhibited(true); assertTrue(painter.fill(d, x, y, null)); d.setEventsInhibited(false);
    }
    @Test public void allSupportedPaintsAndHolesMatchJava() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            for (String type : new String[] {"terrain", "biome", "nibble", "bit"}) for (boolean hole : new boolean[] {false, true}) {
                Dimension a = PaintFloodBenchmark.fixture(), b = PaintFloodBenchmark.fixture();
                if (hole) { a.removeTile(0, -1); b.removeTile(0, -1); }
                for (int i = 0; i < 6; i++) {
                    Paint paint = PaintFloodBenchmark.paint(type, i & 1);
                    boolean undo = (type.equals("nibble") || type.equals("bit")) && (i & 1) != 0;
                    apply(a, paint, undo, false, -64, -64); apply(b, paint, undo, true, -64, -64); same(a, b);
                }
                if (type.equals("biome")) {
                    Paint paint = PaintFloodBenchmark.paint(type, 0);
                    apply(a, paint, true, false, -64, -64); apply(b, paint, true, true, -64, -64); same(a, b);
                }
            }
        } finally { restore(old); }
    }
    @Test public void chunkLayerKeepsMutationDependentBoundary() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            for (int seed : new int[] {-64, -1, 0, 15, 16, 127}) {
                Dimension a = PaintFloodBenchmark.fixture(), b = PaintFloodBenchmark.fixture(); Paint paint = new BitLayerPaint(Populate.INSTANCE);
                apply(a, paint, false, false, seed, seed); apply(b, paint, false, true, seed, seed); same(a, b);
                apply(a, paint, true, false, seed, seed); apply(b, paint, true, true, seed, seed); same(a, b);
            }
        } finally { restore(old); }
    }
    @Test public void missingLayersWithNonZeroDefaultsAndHolesRemainExact() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            Layer layer = new Layer("welt.test.fill.default", "Default", "", Layer.DataSize.NIBBLE, false, 35) {
                @Override public int getDefaultValue() { return 7; }
            };
            Dimension a = PaintFloodBenchmark.fixture(), b = PaintFloodBenchmark.fixture();
            for (Dimension d : new Dimension[] {a, b}) {
                d.removeTile(0, -1);
                for (int y = 0; y < 128; y++) d.getTile(-1, -1).setLayerValue(layer, 127, y, 15);
            }
            Paint paint = new NibbleLayerPaint(layer);
            var brush = org.pepsoft.worldpainter.brushes.SymmetricBrush.CONSTANT_SQUARE.clone(); brush.setLevel(.6f); paint.setBrush(brush);
            apply(a, paint, false, false, -64, -64); apply(b, paint, false, true, -64, -64); same(a, b);
            apply(a, paint, true, false, -64, -64); apply(b, paint, true, true, -64, -64); same(a, b);
        } finally { restore(old); }
    }
    @Test public void customPaintThatChangesNeighboursKeepsJavaOrder() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            Dimension a = PaintFloodBenchmark.fixture(), b = PaintFloodBenchmark.fixture();
            Paint paint = new NibbleLayerPaint(Resources.INSTANCE) {
                @Override public void applyPixel(Dimension d, int x, int y) {
                    super.applyPixel(d, x, y); d.setLayerValueAt(Resources.INSTANCE, x + 1, y, 15);
                }
            };
            var brush = org.pepsoft.worldpainter.brushes.SymmetricBrush.CONSTANT_SQUARE.clone(); brush.setLevel(.6f); paint.setBrush(brush);
            apply(a, paint, false, false, -64, -64); apply(b, paint, false, true, -64, -64); same(a, b);
        } finally { restore(old); }
    }
    @Test public void directNativePathAndUndoRedoAreVerified() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String old = System.getProperty(Native.GEN_KEY);
        try {
            for (String type : new String[] {"terrain", "biome", "nibble", "bit"}) {
                Dimension before = PaintFloodBenchmark.fixture(), actual = PaintFloodBenchmark.fixture(), expected = PaintFloodBenchmark.fixture();
                UndoManager undo = new UndoManager(); for (Tile tile : actual.getTiles()) tile.register(undo); undo.armSavePoint();
                Paint paint = PaintFloodBenchmark.paint(type, 0); apply(expected, paint, false, false, -64, -64);
                System.setProperty(Native.GEN_KEY, "true"); actual.setEventsInhibited(true);
                Layer layer = paint instanceof LayerPaint ? ((LayerPaint) paint).getLayer() : null;
                Terrain terrain = paint instanceof TerrainPaint ? ((TerrainPaint) paint).getTerrain() : null;
                int target = type.equals("biome") ? 77 : type.equals("nibble") ? 1 + Math.round(paint.getBrush().getLevel() * 14) : 1;
                assertTrue(PaintFloodAccess.tryFill(actual, -64, -64, layer, terrain, target, type.equals("nibble") ? PaintFloodAccess.RAISE : PaintFloodAccess.EQUAL));
                actual.setEventsInhibited(false); same(expected, actual);
                assertTrue(undo.undo()); same(before, actual); assertTrue(undo.redo()); same(expected, actual);
                assertFalse(PaintFloodAccess.tryFill(actual, -64, -64, layer, terrain, target, PaintFloodAccess.EQUAL));
            }
        } finally { restore(old); }
    }
    private static void restore(String old) { if (old == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, old); }
}
