package org.pepsoft.worldpainter.painting;

import java.util.List;
import org.junit.Test;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.layers.Resources;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.operations.Filter;
import org.pepsoft.worldpainter.panels.*;
import org.pepsoft.util.undo.UndoManager;
import static org.junit.Assert.*;

public class FilteredNibblePaintParityTest {
    @Test public void applyingAndBothRemovalModesMatchOriginalJava() {
        compare(true, 0); compare(false, 0);
    }
    @Test public void predicatesReadingThePaintedLayerStayCoherent() {
        compare(true, 1); compare(false, 1);
    }
    @Test public void invalidUnclampedTargetsReplayJavaPartialWrites() { compare(false, 2); }
    @Test public void completeApplyAndRemovalLinesMatchJava() { compare(false, 4); }
    @Test public void absentLayerAndUndoKeepOriginalStorageBehavior() {
        String gen = System.getProperty(Native.GEN_KEY), flag = System.getProperty("welt.native.filteredLayers");
        try {
            System.setProperty(Native.GEN_KEY, "false"); Dimension d = fixture();
            UndoManager undo = new UndoManager();
            for (Tile tile : d.getTiles()) { tile.clearLayerData(Resources.INSTANCE); tile.register(undo); }
            undo.armSavePoint();
            NibbleLayerPaint paint = paint(d, 2);
            System.setProperty(Native.GEN_KEY, "true"); System.setProperty("welt.native.filteredLayers", "true");
            d.setEventsInhibited(true);
            try { paint.remove(d, 0, 0, 0.8f); }
            finally { d.setEventsInhibited(false); }
            for (Tile tile : d.getTiles()) assertFalse(tile.hasLayer(Resources.INSTANCE));
            d.setEventsInhibited(true);
            try { paint.apply(d, 0, 0, 1f); }
            finally { d.setEventsInhibited(false); }
            assertTrue(d.getLayerValueAt(Resources.INSTANCE, 0, 0) > 0);
            assertTrue(undo.undo());
            for (Tile tile : d.getTiles()) assertFalse(tile.hasLayer(Resources.INSTANCE));
        } finally { restore(Native.GEN_KEY, gen); restore("welt.native.filteredLayers", flag); }
    }

    private static Dimension fixture() {
        Platform p = DefaultPlugin.JAVA_ANVIL_1_19;
        TileFactory factory = TileFactoryFactory.createFlatTileFactory(17L, Terrain.GRASS, p.minZ, p.standardMaxHeight,
                62, 62, false, false);
        Dimension d = new Dimension(new World2(p, p.minZ, p.standardMaxHeight), "Surface", 17L, factory, Dimension.Anchor.NORMAL_DETAIL);
        for (int tx = -1; tx <= 0; tx++) for (int ty = -1; ty <= 0; ty++) {
            Tile tile = factory.createTile(tx, ty); tile.inhibitEvents();
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                tile.setLayerValue(Resources.INSTANCE, x, y, (x + y * 3) & 15);
                tile.setHeight(x, y, 58 + ((x * 17 + y * 3) & 15) / 2f);
            }
            tile.releaseEvents(); d.addTile(tile);
        }
        return d;
    }
    private static NibbleLayerPaint paint(Dimension d, int mode) {
        NibbleLayerPaint paint = new NibbleLayerPaint(Resources.INSTANCE);
        paint.setBrush(SymmetricBrush.LINEAR_CIRCLE.clone()); paint.getBrush().setRadius(24);
        paint.getBrush().setLevel(0.7f);
        Filter filter = mode == 1 ? new DefaultFilter(d, false, false, Integer.MIN_VALUE, Integer.MIN_VALUE, false,
                true, new DefaultFilter.LayerValue(Resources.INSTANCE, 5), false, null, -1, false)
                : mode == 2 ? new CombinedFilter(List.of()) : new DefaultFilter(d, false, false,
                60, 64, true, false, null, false, null, 20, false);
        paint.setFilter(filter); return paint;
    }
    private static void compare(boolean oneTile, int mode) {
        String gen = System.getProperty(Native.GEN_KEY), flag = System.getProperty("welt.native.filteredLayers");
        try {
            System.setProperty(Native.GEN_KEY, "false");
            Dimension expected = fixture(), actual = fixture();
            NibbleLayerPaint java = paint(expected, mode), rust = paint(actual, mode);
            DimensionPainter javaPainter = new DimensionPainter(), rustPainter = new DimensionPainter();
            javaPainter.setPaint(java); rustPainter.setPaint(rust);
            int centre = oneTile ? 64 : 0;
            float[] levels = mode == 2 ? new float[] {2f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}
                    : new float[] {0.8f, 0f, Float.NaN, 1f};
            for (boolean remove : new boolean[] {false, true}) for (float level : levels) {
                long calls = FilteredPaintAccess.completedTransactions();
                Throwable je = null, re = null;
                expected.setEventsInhibited(true); actual.setEventsInhibited(true);
                try {
                    System.setProperty(Native.GEN_KEY, "false");
                    try {
                        if (mode == 4) { javaPainter.setUndo(remove); javaPainter.drawLine(expected, -64, 0, 64, 0, level, false); }
                        else if (remove) java.remove(expected, centre, centre, level); else java.apply(expected, centre, centre, level);
                    }
                    catch (IllegalArgumentException e) { je = e; }
                    System.setProperty(Native.GEN_KEY, "true"); System.setProperty("welt.native.filteredLayers", "true");
                    try {
                        if (mode == 4) { rustPainter.setUndo(remove); rustPainter.drawLine(actual, -64, 0, 64, 0, level, false); }
                        else if (remove) rust.remove(actual, centre, centre, level); else rust.apply(actual, centre, centre, level);
                    }
                    catch (IllegalArgumentException e) { re = e; }
                } finally { expected.setEventsInhibited(false); actual.setEventsInhibited(false); }
                assertEquals(je == null, re == null);
                if (mode != 2) assertTrue("Expected grouped JNI execution", FilteredPaintAccess.completedTransactions() > calls);
                for (Tile tile : expected.getTiles()) {
                    Tile other = actual.getTile(tile.getX(), tile.getY());
                    assertEquals(tile.hasLayer(Resources.INSTANCE), other.hasLayer(Resources.INSTANCE));
                    for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                        assertEquals("nibble " + x + "," + y, tile.getLayerValue(Resources.INSTANCE, x, y), other.getLayerValue(Resources.INSTANCE, x, y));
                        assertEquals(tile.getTerrain(x, y), other.getTerrain(x, y));
                        assertEquals(tile.getRawHeight(x, y), other.getRawHeight(x, y));
                    }
                }
            }
        } finally {
            restore(Native.GEN_KEY, gen); restore("welt.native.filteredLayers", flag);
        }
    }
    private static void restore(String key, String value) { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }
}
