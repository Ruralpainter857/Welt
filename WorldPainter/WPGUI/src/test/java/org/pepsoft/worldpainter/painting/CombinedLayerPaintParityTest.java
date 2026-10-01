package org.pepsoft.worldpainter.painting;

import org.junit.Test;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

public class CombinedLayerPaintParityTest {
    @Test
    public void completePaintingAndRemovalKeepJavaResultsOnAllConfigurations() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String previous = System.getProperty(Native.GEN_KEY);
        try {
            for (int flags = 1; flags <= 3; flags++) for (boolean onExport : new boolean[] {false, true})
                for (int radius : new int[] {0, 3, 127}) for (int centre : new int[] {-1, 64}) {
                    CombinedLayer layer = new CombinedLayer("Parity", "Parity", java.awt.Color.GREEN);
                    layer.setTerrain((flags & 1) != 0 ? Terrain.CUSTOM_96 : null); layer.setBiome((flags & 2) != 0 ? 42 : -1);
                    layer.setApplyTerrainAndBiomeOnExport(onExport); CombinedLayerPaint painter = new CombinedLayerPaint(layer);
                    MaskedPaintBenchmark.SolidBrush brush = new MaskedPaintBenchmark.SolidBrush(); brush.setRadius(radius); brush.setLevel(0.63f); painter.setBrush(brush);
                    Dimension left = NibbleLayerPaintParityTest.fixture(), right = NibbleLayerPaintParityTest.fixture();
                    left.setEventsInhibited(true); right.setEventsInhibited(true);
                    System.setProperty(Native.GEN_KEY, "false"); painter.apply(left, centre, centre, 1f);
                    System.setProperty(Native.GEN_KEY, "true"); painter.apply(right, centre, centre, 1f); same(left, right, layer);
                    System.setProperty(Native.GEN_KEY, "false"); painter.remove(left, centre, centre, 1f);
                    System.setProperty(Native.GEN_KEY, "true"); painter.remove(right, centre, centre, 1f);
                    left.setEventsInhibited(false); right.setEventsInhibited(false); same(left, right, layer);
                }
        } finally { restore(previous); }
    }
    private static void same(Dimension left, Dimension right, Layer layer) {
        for (Tile tile : left.getTiles()) {
            Tile match = right.getTile(tile.getX(), tile.getY());
            assertEquals(tile.hasLayer(layer), match.hasLayer(layer)); assertEquals(tile.hasLayer(Biome.INSTANCE), match.hasLayer(Biome.INSTANCE));
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                assertEquals(tile.getLayerValue(layer, x, y), match.getLayerValue(layer, x, y));
                assertEquals(tile.getTerrain(x, y), match.getTerrain(x, y));
                assertEquals(tile.getLayerValue(Biome.INSTANCE, x, y), match.getLayerValue(Biome.INSTANCE, x, y));
            }
        }
    }
    private static void restore(String previous) { if (previous == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, previous); }
}
