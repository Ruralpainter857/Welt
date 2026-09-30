package org.pepsoft.worldpainter.painting;

import org.junit.Test;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

public class MaskedPaintParityTest {
    @Test
    public void actualPaintersMatchJavaOnPixelsSingleTilesAndTileBorders() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String previous = System.getProperty(Native.GEN_KEY);
        try {
            for (String type : new String[] {"terrain", "biome", "bit", "chunk", "nibble"})
                for (int radius : new int[] {0, 3, 127}) for (int centre : new int[] {-1, 64}) {
                    Dimension left = NibbleLayerPaintParityTest.fixture(), right = NibbleLayerPaintParityTest.fixture();
                    Paint painter = paint(type); MaskedPaintBenchmark.SolidBrush brush = new MaskedPaintBenchmark.SolidBrush();
                    brush.setRadius(radius); painter.setBrush(brush); left.setEventsInhibited(true); right.setEventsInhibited(true);
                    System.setProperty(Native.GEN_KEY, "false"); painter.apply(left, centre, centre, 1f); painter.remove(left, centre, centre, 1f);
                    System.setProperty(Native.GEN_KEY, "true"); painter.apply(right, centre, centre, 1f); painter.remove(right, centre, centre, 1f);
                    left.setEventsInhibited(false); right.setEventsInhibited(false); same(left, right);
                }
        } finally { restore(previous); }
    }
    @Test
    public void thresholdsFiltersDitherAndImmediateEventsKeepOriginalPaths() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable()); String previous = System.getProperty(Native.GEN_KEY);
        try {
            for (String type : new String[] {"terrain", "biome", "bit", "chunk"}) for (int variant = 0; variant < 4; variant++) {
                Dimension left = NibbleLayerPaintParityTest.fixture(), right = NibbleLayerPaintParityTest.fixture();
                Paint painter = paint(type); MaskedPaintBenchmark.SolidBrush brush = new MaskedPaintBenchmark.SolidBrush();
                brush.setRadius(3); painter.setBrush(brush);
                if (variant == 1) painter.setFilter((x, y, strength) -> strength * 0.5f);
                if (variant == 2) painter.setDither(true);
                if (variant != 3) { left.setEventsInhibited(true); right.setEventsInhibited(true); }
                for (float level : new float[] {0f, 0.75f, Math.nextUp(0.75f), Float.NaN, 1f}) {
                    // Le dithering à force unitaire court-circuite les tirages et donne une comparaison déterministe.
                    if (variant == 2 && level != 1f) continue;
                    System.setProperty(Native.GEN_KEY, "false"); painter.apply(left, 127, 127, level);
                    System.setProperty(Native.GEN_KEY, "true"); painter.apply(right, 127, 127, level);
                }
                if (variant != 3) { left.setEventsInhibited(false); right.setEventsInhibited(false); } same(left, right);
            }
        } finally { restore(previous); }
    }
    private static Paint paint(String type) { return type.equals("nibble") ? new DiscreteLayerPaint(Resources.INSTANCE, 12) : MaskedPaintBenchmark.paint(type); }
    private static void same(Dimension left, Dimension right) {
        for (Tile tile : left.getTiles()) {
            Tile match = right.getTile(tile.getX(), tile.getY());
            for (Layer layer : new Layer[] {Biome.INSTANCE, Resources.INSTANCE, Frost.INSTANCE, Populate.INSTANCE}) assertEquals(tile.hasLayer(layer), match.hasLayer(layer));
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                assertEquals(tile.getTerrain(x, y), match.getTerrain(x, y));
                assertEquals(tile.getLayerValue(Biome.INSTANCE, x, y), match.getLayerValue(Biome.INSTANCE, x, y));
                assertEquals(tile.getLayerValue(Resources.INSTANCE, x, y), match.getLayerValue(Resources.INSTANCE, x, y));
                assertEquals(tile.getBitLayerValue(Frost.INSTANCE, x, y), match.getBitLayerValue(Frost.INSTANCE, x, y));
                assertEquals(tile.getBitLayerValue(Populate.INSTANCE, x, y), match.getBitLayerValue(Populate.INSTANCE, x, y));
            }
        }
    }
    private static void restore(String previous) { if (previous == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, previous); }
}
