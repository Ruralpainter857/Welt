package org.pepsoft.worldpainter.painting;

import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.Test;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.panels.*;
import org.pepsoft.util.undo.UndoManager;
import static org.junit.Assert.*;

public class FilteredDiscretePaintParityTest {
    @Test public void biomesMatchWholeLinesAndOneTileStamps() { compare(Biome.INSTANCE, 200, false); }
    @Test public void annotationsMatchWholeLinesAndOneTileStamps() { compare(Annotations.INSTANCE, 5, false); }
    @Test public void biomePredicatesReadOutputPlane() { compare(Biome.INSTANCE, 200, true); }
    @Test public void annotationPredicatesReadOutputPlane() { compare(Annotations.INSTANCE, 5, true); }
    @Test public void invalidTargetsFallBackToJava() { compare(Biome.INSTANCE, 300, false); compare(Annotations.INSTANCE, 16, false); }
    @Test public void removalUsesTheCapturedDefaultForCustomLayers() {
        String gen = System.getProperty(Native.GEN_KEY), flag = System.getProperty("welt.native.filteredLayers");
        try {
            System.setProperty(Native.GEN_KEY, "false"); MutableDefaultLayer layer = new MutableDefaultLayer();
            Dimension expected = fixture(layer, 7), actual = fixture(layer, 7);
            DiscreteLayerPaint java = paint(expected, layer, 5, false), rust = paint(actual, layer, 5, false);
            java.setFilter(new CombinedFilter(List.of())); rust.setFilter(new CombinedFilter(List.of()));
            layer.defaultValue = 9;
            long calls = FilteredPaintAccess.completedTransactions();
            expected.setEventsInhibited(true); actual.setEventsInhibited(true);
            try {
                java.remove(expected, 64, 64, 1f);
                System.setProperty(Native.GEN_KEY, "true"); System.setProperty("welt.native.filteredLayers", "true");
                rust.remove(actual, 64, 64, 1f);
            } finally { expected.setEventsInhibited(false); actual.setEventsInhibited(false); }
            assertTrue(FilteredPaintAccess.completedTransactions() > calls);
            assertEquals(7, actual.getLayerValueAt(layer, 64, 64));
            for (Tile tile : expected.getTiles()) for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++)
                assertEquals(tile.getLayerValue(layer, x, y), actual.getTile(tile.getX(), tile.getY()).getLayerValue(layer, x, y));
        } finally { restore(Native.GEN_KEY, gen); restore("welt.native.filteredLayers", flag); }
    }
    private static final class MutableDefaultLayer extends Layer {
        int defaultValue = 7;
        MutableDefaultLayer() { super("welt.parity.default", "Default parity", "Custom defaults", DataSize.NIBBLE, true, 1); }
        @Override public int getDefaultValue() { return defaultValue; }
    }
    @Test public void absentDefaultsUndoAndEventsMatchJava() {
        String gen = System.getProperty(Native.GEN_KEY), flag = System.getProperty("welt.native.filteredLayers");
        try {
            System.setProperty(Native.GEN_KEY, "false"); Dimension d = fixture(Biome.INSTANCE, 200);
            UndoManager undo = new UndoManager();
            for (Tile tile : d.getTiles()) { tile.clearLayerData(Biome.INSTANCE); tile.register(undo); }
            undo.armSavePoint(); int[] events = {0};
            for (Tile tile : d.getTiles()) tile.addListener((Tile.Listener) Proxy.newProxyInstance(
                    Tile.Listener.class.getClassLoader(), new Class<?>[] {Tile.Listener.class}, (proxy, method, args) -> {
                        if (method.getName().equals("layerDataChanged")) events[0]++; return null;
                    }));
            DiscreteLayerPaint paint = paint(d, Biome.INSTANCE, 200, false);
            paint.setFilter(new CombinedFilter(List.of()));
            System.setProperty(Native.GEN_KEY, "true"); System.setProperty("welt.native.filteredLayers", "true");
            d.setEventsInhibited(true);
            try { paint.remove(d, 0, 0, 1f); } finally { d.setEventsInhibited(false); }
            for (Tile tile : d.getTiles()) assertFalse(tile.hasLayer(Biome.INSTANCE));
            assertEquals(0, events[0]);
            d.setEventsInhibited(true);
            try { paint.apply(d, 0, 0, 1f); assertEquals(0, events[0]); } finally { d.setEventsInhibited(false); }
            assertEquals(4, events[0]); assertEquals(200, d.getLayerValueAt(Biome.INSTANCE, 0, 0));
            assertTrue(undo.undo()); for (Tile tile : d.getTiles()) assertFalse(tile.hasLayer(Biome.INSTANCE));
        } finally { restore(Native.GEN_KEY, gen); restore("welt.native.filteredLayers", flag); }
    }
    private static Dimension fixture(Layer layer, int value) {
        Platform p = DefaultPlugin.JAVA_ANVIL_1_19;
        TileFactory factory = TileFactoryFactory.createFlatTileFactory(17L, Terrain.GRASS, p.minZ, p.standardMaxHeight, 62, 62, false, false);
        Dimension d = new Dimension(new World2(p, p.minZ, p.standardMaxHeight), "Surface", 17L, factory, Dimension.Anchor.NORMAL_DETAIL);
        for (int tx = -1; tx <= 0; tx++) for (int ty = -1; ty <= 0; ty++) {
            Tile tile = factory.createTile(tx, ty); tile.inhibitEvents();
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                tile.setHeight(x, y, 58 + ((x * 17 + y * 3) & 15) / 2f);
                tile.setLayerValue(layer, x, y, ((x + y) & 1) == 0 ? Math.min(value, 15) : layer.getDefaultValue());
            }
            tile.releaseEvents(); d.addTile(tile);
        }
        return d;
    }
    private static DiscreteLayerPaint paint(Dimension d, Layer layer, int value, boolean own) {
        DiscreteLayerPaint paint = new DiscreteLayerPaint(layer, value); paint.setDither(false);
        paint.setBrush(SymmetricBrush.LINEAR_CIRCLE.clone()); paint.getBrush().setRadius(24);
        paint.setFilter(own ? OnlyOnTerrainOrLayerFilter.create(d, new DefaultFilter.LayerValue(layer, Math.min(value, 15)))
                : new DefaultFilter(d, false, false, 60, 64, false, false, null, false, null, 80, false));
        return paint;
    }
    private static void compare(Layer layer, int value, boolean own) {
        String gen = System.getProperty(Native.GEN_KEY), flag = System.getProperty("welt.native.filteredLayers");
        try {
            System.setProperty(Native.GEN_KEY, "false"); Dimension expected = fixture(layer, value), actual = fixture(layer, value);
            DiscreteLayerPaint java = paint(expected, layer, value, own), rust = paint(actual, layer, value, own);
            DimensionPainter jp = new DimensionPainter(), rp = new DimensionPainter(); jp.setPaint(java); rp.setPaint(rust);
            for (boolean remove : new boolean[] {false, true}) for (float level : new float[] {1f, 0.75f, Float.NaN, Float.POSITIVE_INFINITY, 0f}) {
                long calls = FilteredPaintAccess.completedTransactions(); Throwable je = null, re = null;
                expected.setEventsInhibited(true); actual.setEventsInhibited(true);
                try {
                    System.setProperty(Native.GEN_KEY, "false");
                    try { jp.setUndo(remove); jp.drawLine(expected, -64, 0, 64, 0, level, false);
                        if (remove) java.remove(expected, 64, 64, level); else java.apply(expected, 64, 64, level);
                    } catch (IllegalArgumentException e) { je = e; }
                    System.setProperty(Native.GEN_KEY, "true"); System.setProperty("welt.native.filteredLayers", "true");
                    try { rp.setUndo(remove); rp.drawLine(actual, -64, 0, 64, 0, level, false);
                        if (remove) rust.remove(actual, 64, 64, level); else rust.apply(actual, 64, 64, level);
                    } catch (IllegalArgumentException e) { re = e; }
                } finally { expected.setEventsInhibited(false); actual.setEventsInhibited(false); }
                assertEquals(je == null, re == null);
                if (value <= 255 && (layer.dataSize == Layer.DataSize.BYTE || value <= 15))
                    assertTrue("Expected grouped JNI execution", FilteredPaintAccess.completedTransactions() > calls);
                for (Tile tile : expected.getTiles()) {
                    Tile other = actual.getTile(tile.getX(), tile.getY()); assertEquals(tile.hasLayer(layer), other.hasLayer(layer));
                    for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                        assertEquals(tile.getLayerValue(layer, x, y), other.getLayerValue(layer, x, y));
                        assertEquals(tile.getTerrain(x, y), other.getTerrain(x, y)); assertEquals(tile.getRawHeight(x, y), other.getRawHeight(x, y));
                    }
                }
            }
        } finally { restore(Native.GEN_KEY, gen); restore("welt.native.filteredLayers", flag); }
    }
    private static void restore(String key, String value) { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }
}
