package org.pepsoft.worldpainter.painting;

import java.util.List;
import java.lang.reflect.Proxy;
import org.junit.Test;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.selection.SelectionChunk;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.panels.*;
import org.pepsoft.util.undo.UndoManager;
import static org.junit.Assert.*;

public class FilteredBitPaintParityTest {
    @Test public void blockBitsMatchCompleteApplicationAndRemovalLines() { compare(Frost.INSTANCE, false); }
    @Test public void chunkBitsMatchCompleteApplicationAndRemovalLines() { compare(SelectionChunk.INSTANCE, false); }
    @Test public void blockPredicatesReadTheMutatingOutput() { compare(Frost.INSTANCE, true); }
    @Test public void chunkPredicatesReadTheMutatingOutput() { compare(SelectionChunk.INSTANCE, true); }
    @Test public void absentStorageRemovalAndUndoMatchJava() {
        String gen = System.getProperty(Native.GEN_KEY), flag = System.getProperty("welt.native.filteredLayers");
        try {
            System.setProperty(Native.GEN_KEY, "false");
            Dimension d = fixture(Frost.INSTANCE); UndoManager undo = new UndoManager();
            for (Tile tile : d.getTiles()) { tile.clearLayerData(Frost.INSTANCE); tile.register(undo); }
            undo.armSavePoint();
            int[] events = {0};
            for (Tile tile : d.getTiles()) tile.addListener((Tile.Listener) Proxy.newProxyInstance(
                    Tile.Listener.class.getClassLoader(), new Class<?>[] {Tile.Listener.class}, (proxy, method, args) -> {
                        if (method.getName().equals("layerDataChanged")) events[0]++;
                        return null;
                    }));
            BitLayerPaint paint = paint(d, Frost.INSTANCE, false);
            paint.setFilter(new CombinedFilter(List.of()));
            System.setProperty(Native.GEN_KEY, "true"); System.setProperty("welt.native.filteredLayers", "true");
            d.setEventsInhibited(true);
            try { paint.remove(d, 0, 0, 1f); } finally { d.setEventsInhibited(false); }
            for (Tile tile : d.getTiles()) assertFalse(tile.hasLayer(Frost.INSTANCE));
            assertEquals(0, events[0]);
            d.setEventsInhibited(true);
            try { paint.apply(d, 0, 0, 1f); assertEquals(0, events[0]); } finally { d.setEventsInhibited(false); }
            assertEquals(4, events[0]);
            assertTrue(d.getBitLayerValueAt(Frost.INSTANCE, 0, 0));
            assertTrue(undo.undo());
            for (Tile tile : d.getTiles()) assertFalse(tile.hasLayer(Frost.INSTANCE));
        } finally { restore(Native.GEN_KEY, gen); restore("welt.native.filteredLayers", flag); }
    }
    @Test public void customFiltersKeepJavaFallback() {
        String gen = System.getProperty(Native.GEN_KEY), flag = System.getProperty("welt.native.filteredLayers");
        try {
            System.setProperty(Native.GEN_KEY, "false"); Dimension d = fixture(Frost.INSTANCE);
            BitLayerPaint paint = paint(d, Frost.INSTANCE, false); paint.setFilter((x, y, strength) -> 0f);
            long calls = FilteredPaintAccess.completedTransactions();
            System.setProperty(Native.GEN_KEY, "true"); System.setProperty("welt.native.filteredLayers", "true");
            d.setEventsInhibited(true);
            try { paint.apply(d, 0, 0, 1f); } finally { d.setEventsInhibited(false); }
            assertEquals(calls, FilteredPaintAccess.completedTransactions());
        } finally { restore(Native.GEN_KEY, gen); restore("welt.native.filteredLayers", flag); }
    }
    private static Dimension fixture(Layer layer) {
        Platform p = DefaultPlugin.JAVA_ANVIL_1_19;
        TileFactory factory = TileFactoryFactory.createFlatTileFactory(17L, Terrain.GRASS,
                p.minZ, p.standardMaxHeight, 62, 62, false, false);
        Dimension d = new Dimension(new World2(p, p.minZ, p.standardMaxHeight), "Surface", 17L, factory, Dimension.Anchor.NORMAL_DETAIL);
        for (int tx = -1; tx <= 0; tx++) for (int ty = -1; ty <= 0; ty++) {
            Tile tile = factory.createTile(tx, ty); tile.inhibitEvents();
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                tile.setHeight(x, y, 58 + ((x * 17 + y * 3) & 15) / 2f);
                if (((x / 16 + y / 16) & 1) == 0) tile.setBitLayerValue(layer, x, y, true);
            }
            tile.releaseEvents(); d.addTile(tile);
        }
        return d;
    }
    private static BitLayerPaint paint(Dimension d, Layer layer, boolean own) {
        BitLayerPaint paint = new BitLayerPaint(layer); paint.setDither(false);
        paint.setBrush(SymmetricBrush.LINEAR_CIRCLE.clone()); paint.getBrush().setRadius(24);
        paint.setFilter(own ? new DefaultFilter(d, false, false, Integer.MIN_VALUE, Integer.MIN_VALUE, false,
                true, layer, false, null, -1, false)
                : new DefaultFilter(d, false, false, 60, 64, false, false, null, false, null, 80, false));
        return paint;
    }
    private static void compare(Layer layer, boolean own) {
        String gen = System.getProperty(Native.GEN_KEY), flag = System.getProperty("welt.native.filteredLayers");
        try {
            System.setProperty(Native.GEN_KEY, "false");
            Dimension expected = fixture(layer), actual = fixture(layer);
            BitLayerPaint java = paint(expected, layer, own), rust = paint(actual, layer, own);
            DimensionPainter jp = new DimensionPainter(), rp = new DimensionPainter(); jp.setPaint(java); rp.setPaint(rust);
            for (boolean remove : new boolean[] {false, true}) for (float level : new float[] {1f, 0.75f, Float.NaN, Float.POSITIVE_INFINITY, 0f}) {
                long calls = FilteredPaintAccess.completedTransactions();
                expected.setEventsInhibited(true); actual.setEventsInhibited(true);
                try {
                    System.setProperty(Native.GEN_KEY, "false"); jp.setUndo(remove); jp.drawLine(expected, -64, 0, 64, 0, level, false);
                    if (remove) java.remove(expected, 64, 64, level); else java.apply(expected, 64, 64, level);
                    System.setProperty(Native.GEN_KEY, "true"); System.setProperty("welt.native.filteredLayers", "true");
                    rp.setUndo(remove); rp.drawLine(actual, -64, 0, 64, 0, level, false);
                    if (remove) rust.remove(actual, 64, 64, level); else rust.apply(actual, 64, 64, level);
                } finally { expected.setEventsInhibited(false); actual.setEventsInhibited(false); }
                assertTrue("Expected grouped JNI execution", FilteredPaintAccess.completedTransactions() > calls);
                for (Tile tile : expected.getTiles()) {
                    Tile other = actual.getTile(tile.getX(), tile.getY());
                    assertEquals(tile.hasLayer(layer), other.hasLayer(layer));
                    for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                        assertEquals(tile.getBitLayerValue(layer, x, y), other.getBitLayerValue(layer, x, y));
                        assertEquals(tile.getRawHeight(x, y), other.getRawHeight(x, y));
                        assertEquals(tile.getTerrain(x, y), other.getTerrain(x, y));
                    }
                }
            }
        } finally { restore(Native.GEN_KEY, gen); restore("welt.native.filteredLayers", flag); }
    }
    private static void restore(String key, String value) { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }
}
