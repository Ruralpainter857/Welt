package org.pepsoft.worldpainter.painting;

import java.util.List;
import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import java.lang.reflect.Proxy;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.operations.Filter;
import org.pepsoft.worldpainter.panels.*;
import org.pepsoft.worldpainter.selection.SelectionBlock;
import org.pepsoft.worldpainter.selection.SelectionChunk;
import static org.junit.Assert.*;

public class FilteredTerrainPaintParityTest {
    @Test public void heightFeatherAndSlopeCrossTileHalosMatchJava() { compare(0, false, true); }
    @Test public void mixedSelectionAndNumericPredicatesMatchJava() { compare(1, false, true); }
    @Test public void automaticBiomesObserveEachStrokesTerrainAndLayerState() { compare(2, false, true); }
    @Test public void missingTilesKeepJavaBorders() { compare(0, true, true); }
    @Test public void customFilterKeepsFallback() { compare(3, false, false); }
    @Test public void byteAndAnnotationPredicatesMatchJava() { compare(4, false, true); }
    @Test public void unsignedShortHeightAndByteWaterMatchJava() { compare(0, false, true, true); }
    @Test public void completeFilteredLinesMatchOriginalPaintingPath() { compare(5, false, true); }
    @Test public void customTerrainBiomePaletteMatchesJavaAcrossRepeatedStrokes() {
        MixedMaterial old = Terrain.getCustomMaterial(0);
        try {
            Terrain.setCustomMaterial(0, new MixedMaterial("Filter parity", new MixedMaterial.Row(
                    org.pepsoft.minecraft.Material.STONE, 1, 1f), 2, null));
            compare(6, false, true);
        } finally { Terrain.setCustomMaterial(0, old); }
    }

    @Test public void rejectedOneTileStrokeRetainsOriginalEditingInhibition() {
        String gen = System.getProperty(Native.GEN_KEY), enabled = System.getProperty("welt.native.filteredTerrain");
        try {
            System.setProperty(Native.GEN_KEY, "false"); Dimension d = fixture(false);
            TerrainPaint paint = new TerrainPaint(Terrain.SAND); configure(paint, filter(d, 0));
            paint.getBrush().setRadius(12);
            System.setProperty(Native.GEN_KEY, "true"); System.setProperty("welt.native.filteredTerrain", "true");
            long calls = FilteredTerrainAccess.completedTransactions();
            d.setEventsInhibited(true);
            try {
                paint.apply(d, 64, 64, 0f);
                assertTrue(d.getTile(0, 0).isEventsInhibited());
                assertEquals(calls + 1, FilteredTerrainAccess.completedTransactions());
                assertEquals(Terrain.GRASS, d.getTerrainAt(64, 64));
            } finally { d.setEventsInhibited(false); }
        } finally { restore(Native.GEN_KEY, gen); restore("welt.native.filteredTerrain", enabled); }
    }

    @Test public void automaticGateUsesLargeSlopeStrokesAndHonoursDisableFlag() {
        String gen = System.getProperty(Native.GEN_KEY), enabled = System.getProperty("welt.native.filteredTerrain");
        try {
            System.setProperty(Native.GEN_KEY, "false");
            Dimension d = fixture(false);
            TerrainPaint paint = new TerrainPaint(Terrain.SAND); configure(paint, filter(d, 0));
            System.setProperty(Native.GEN_KEY, "true"); System.clearProperty("welt.native.filteredTerrain");
            long calls = FilteredTerrainAccess.completedTransactions();
            d.setEventsInhibited(true);
            try {
                paint.apply(d, 0, 0, 1f);
                assertEquals(calls, FilteredTerrainAccess.completedTransactions());
                paint.getBrush().setRadius(96);
                paint.apply(d, 0, 0, 1f);
                assertEquals(calls + 1, FilteredTerrainAccess.completedTransactions());
                System.setProperty("welt.native.filteredTerrain", "false");
                paint.apply(d, 0, 0, 1f);
                assertEquals(calls + 1, FilteredTerrainAccess.completedTransactions());
            } finally { d.setEventsInhibited(false); }
        } finally { restore(Native.GEN_KEY, gen); restore("welt.native.filteredTerrain", enabled); }
    }

    @Test public void completePlanesKeepUndoAndCoalescedTerrainEvents() {
        String gen = System.getProperty(Native.GEN_KEY), enabled = System.getProperty("welt.native.filteredTerrain");
        try {
            System.setProperty(Native.GEN_KEY, "false");
            Dimension d = fixture(false);
            UndoManager undo = new UndoManager();
            int[] events = {0};
            for (Tile tile : d.getTiles()) {
                tile.register(undo);
                tile.addListener((Tile.Listener) Proxy.newProxyInstance(Tile.Listener.class.getClassLoader(),
                        new Class<?>[] {Tile.Listener.class}, (proxy, method, args) -> {
                            if (method.getName().equals("terrainChanged")) events[0]++;
                            return null;
                        }));
            }
            undo.armSavePoint();
            TerrainPaint paint = new TerrainPaint(Terrain.SAND);
            configure(paint, new CombinedFilter(List.of()));
            System.setProperty(Native.GEN_KEY, "true"); System.setProperty("welt.native.filteredTerrain", "true");
            long calls = FilteredTerrainAccess.completedTransactions();
            d.setEventsInhibited(true);
            try { paint.apply(d, 0, 0, 1f); assertEquals(0, events[0]); }
            finally { d.setEventsInhibited(false); }
            assertEquals(calls + 1, FilteredTerrainAccess.completedTransactions());
            assertEquals(Terrain.SAND, d.getTerrainAt(0, 0));
            assertEquals(4, events[0]);
            assertTrue(undo.undo());
            for (Tile tile : d.getTiles()) for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++)
                assertEquals(Terrain.GRASS, tile.getTerrain(x, y));
        } finally { restore(Native.GEN_KEY, gen); restore("welt.native.filteredTerrain", enabled); }
    }

    private static void compare(int mode, boolean missing, boolean nativeExpected) {
        compare(mode, missing, nativeExpected, false);
    }
    private static void compare(int mode, boolean missing, boolean nativeExpected, boolean shortHeight) {
        String gen = System.getProperty(Native.GEN_KEY), enabled = System.getProperty("welt.native.filteredTerrain");
        try {
            System.setProperty(Native.GEN_KEY, "false");
            Dimension expected = fixture(missing, shortHeight), actual = fixture(missing, shortHeight);
            Terrain target = mode == 6 ? Terrain.CUSTOM_1 : Terrain.SAND;
            Paint reference = new TerrainPaintBatchPathTest.ReferenceTerrainPaint(target);
            Paint nativePaint = new TerrainPaint(target);
            configure(reference, filter(expected, mode)); configure(nativePaint, filter(actual, mode));
            DimensionPainter referencePainter = new DimensionPainter(), actualPainter = new DimensionPainter();
            referencePainter.setPaint(reference); actualPainter.setPaint(nativePaint);
            long calls = FilteredTerrainAccess.completedTransactions();
            expected.setEventsInhibited(true); actual.setEventsInhibited(true);
            try {
                for (int i = 0; i < 5; i++) {
                    int x = -64 + i * 31, y = 48 - i * 24;
                    float dynamic = i == 4 ? Float.NaN : i == 3 ? 0.8f : 1f;
                    System.setProperty(Native.GEN_KEY, "false");
                    if (mode == 5) referencePainter.drawLine(expected, -96, y, 96, y, dynamic, false);
                    else reference.apply(expected, x, y, dynamic);
                    System.setProperty(Native.GEN_KEY, "true");
                    System.setProperty("welt.native.filteredTerrain", "true");
                    if (mode == 5) actualPainter.drawLine(actual, -96, y, 96, y, dynamic, false);
                    else nativePaint.apply(actual, x, y, dynamic);
                }
            } finally { expected.setEventsInhibited(false); actual.setEventsInhibited(false); }
            assertEquals(nativeExpected, FilteredTerrainAccess.completedTransactions() > calls);
            int changedCells = 0;
            for (Tile tile : expected.getTiles()) {
                Tile other = actual.getTile(tile.getX(), tile.getY());
                for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                    assertEquals("terrain " + tile.getX() + "," + tile.getY() + ":" + x + "," + y,
                            tile.getTerrain(x, y), other.getTerrain(x, y));
                    assertEquals(tile.getRawHeight(x, y), other.getRawHeight(x, y));
                    assertEquals(tile.getLayerValue(Resources.INSTANCE, x, y), other.getLayerValue(Resources.INSTANCE, x, y));
                    assertEquals(tile.getLayerValue(Biome.INSTANCE, x, y), other.getLayerValue(Biome.INSTANCE, x, y));
                    if (other.getTerrain(x, y) != Terrain.GRASS) changedCells++;
                }
            }
            if (mode == 6) assertTrue("Custom terrain case must paint cells", changedCells > 0);
        } finally {
            restore(Native.GEN_KEY, gen); restore("welt.native.filteredTerrain", enabled);
        }
    }
    private static void restore(String key, String value) { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }
    private static void configure(Paint paint, Filter filter) {
        paint.setBrush(SymmetricBrush.LINEAR_CIRCLE.clone()); paint.getBrush().setRadius(64);
        paint.setDither(false); paint.setFilter(filter);
    }
    private static Filter filter(Dimension d, int mode) {
        int absent = Integer.MIN_VALUE;
        return switch (mode) {
            case 0, 5 -> new DefaultFilter(d, false, false, 60, 64, true, false, null, false, null, 20, false);
            case 1 -> new DefaultFilter(d, true, false, 64, 60, true, true,
                    List.of(new DefaultFilter.LayerValue(Resources.INSTANCE, 5), Frost.INSTANCE),
                    true, TerrainOrLayerFilter.LAVA, -1, false);
            case 2, 6 -> OnlyOnTerrainOrLayerFilter.create(d, new DefaultFilter.LayerValue(Biome.INSTANCE, -4))
                    .and(new DefaultFilter(d, false, false, absent, absent, false, false, null, false, null, -1, false));
            case 4 -> OnlyOnTerrainOrLayerFilter.create(d, new DefaultFilter.LayerValue(Annotations.INSTANCE, 2))
                    .and(ExceptOnTerrainOrLayerFilter.create(d, new DefaultFilter.LayerValue(Biome.INSTANCE, 12)));
            default -> (x, y, strength) -> x % 3 == 0 ? strength : 0f;
        };
    }
    private static Dimension fixture(boolean missing) {
        return fixture(missing, false);
    }
    private static Dimension fixture(boolean missing, boolean shortHeight) {
        Platform p = DefaultPlugin.JAVA_ANVIL_1_19;
        int min = shortHeight ? 0 : p.minZ, max = shortHeight ? 256 : p.standardMaxHeight;
        World2 world = new World2(p, min, max);
        TileFactory factory = TileFactoryFactory.createFlatTileFactory(17L, Terrain.GRASS, min,
                max, 62, 62, false, false);
        Dimension d = new Dimension(world, "Surface", 17L, factory, Dimension.Anchor.NORMAL_DETAIL);
        for (int tx = -1; tx <= 0; tx++) for (int ty = -1; ty <= 0; ty++) {
            if (missing && tx == 0 && ty == -1) continue;
            Tile tile = factory.createTile(tx, ty); tile.inhibitEvents();
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                tile.setHeight(x, y, 58 + ((x * 17 + y * 3) & 15) / 2f);
                tile.setWaterLevel(x, y, 61);
                tile.setLayerValue(Resources.INSTANCE, x, y, (x * 3 + y) & 15);
                tile.setLayerValue(Annotations.INSTANCE, x, y, (x + y) & 3);
                tile.setLayerValue(Biome.INSTANCE, x, y, (x & 1) == 0 ? 255 : 12);
                tile.setBitLayerValue(Frost.INSTANCE, x, y, (x & 3) == 0);
                tile.setBitLayerValue(FloodWithLava.INSTANCE, x, y, (y & 3) == 1);
                tile.setBitLayerValue(SelectionBlock.INSTANCE, x, y, (x + y) % 7 == 0);
                tile.setBitLayerValue(SelectionChunk.INSTANCE, x, y, x < 16 && y < 16);
                tile.setLayerValue(DeciduousForest.INSTANCE, x, y, (y & 1) == 0 ? 5 : 0);
            }
            tile.releaseEvents(); d.addTile(tile);
        }
        return d;
    }
}
