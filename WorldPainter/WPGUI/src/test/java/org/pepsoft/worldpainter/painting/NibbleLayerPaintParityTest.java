package org.pepsoft.worldpainter.painting;

import org.junit.Test;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.brushes.AbstractBrush;
import org.pepsoft.worldpainter.brushes.BrushShape;
import org.pepsoft.worldpainter.heightMaps.ConstantHeightMap;
import org.pepsoft.worldpainter.layers.Resources;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.themes.SimpleTheme;
import static org.junit.Assert.*;

public class NibbleLayerPaintParityTest {
    @Test
    public void actualPainterMatchesOriginalAcrossSingleTileAndMultipleTilePaths() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        try {
            for (int radius : new int[] {0, 3, 127}) for (int centre : new int[] {-1, 64}) {
                Dimension left = fixture(), right = fixture();
                NibbleLayerPaint paint = new NibbleLayerPaint(Resources.INSTANCE);
                Brush brush = new Brush(); brush.setRadius(radius); brush.setLevel(0.63f); paint.setBrush(brush);
                left.setEventsInhibited(true); right.setEventsInhibited(true);
                System.setProperty(Native.GEN_KEY, "false"); paint.apply(left, centre, centre, 0.73f); paint.remove(left, centre, centre, 0.73f);
                System.setProperty(Native.GEN_KEY, "true"); paint.apply(right, centre, centre, 0.73f); paint.remove(right, centre, centre, 0.73f);
                left.setEventsInhibited(false); right.setEventsInhibited(false); same(left, right);
            }
        } finally { restore(previous); }
    }

    @Test
    public void filteredAndImmediateNotificationPathsKeepOriginalBehavior() {
        String previous = System.getProperty(Native.GEN_KEY);
        try {
            for (boolean filtered : new boolean[] {false, true}) {
                Dimension left = fixture(), right = fixture(); NibbleLayerPaint paint = new NibbleLayerPaint(Resources.INSTANCE);
                Brush brush = new Brush(); brush.setRadius(3); paint.setBrush(brush);
                if (filtered) paint.setFilter((x, y, strength) -> strength * 0.5f);
                System.setProperty(Native.GEN_KEY, "false"); paint.apply(left, -1, -1, 0.8f); paint.remove(left, -1, -1, 0.8f);
                System.setProperty(Native.GEN_KEY, "true"); paint.apply(right, -1, -1, 0.8f); paint.remove(right, -1, -1, 0.8f);
                same(left, right);
            }
        } finally { restore(previous); }
    }

    static Dimension fixture() {
        Dimension dimension = new Dimension(new World2(DefaultPlugin.JAVA_ANVIL_1_18, -64, 320), "Surface", 0,
                new HeightMapTileFactory(0, new ConstantHeightMap(62), -64, 320, false,
                        SimpleTheme.createSingleTerrain(Terrain.GRASS, -64, 320, 62)), Dimension.Anchor.NORMAL_DETAIL);
        for (int x = -2; x < 2; x++) for (int y = -2; y < 2; y++) {
            if (x == -1 && y == -1) continue;
            Tile tile = new Tile(x, y, -64, 320); tile.inhibitEvents();
            for (int ly = 0; ly < 128; ly++) for (int lx = 0; lx < 128; lx++)
                tile.setLayerValue(Resources.INSTANCE, lx, ly, (lx + ly * 3) & 15);
            tile.releaseEvents(); dimension.addTile(tile);
        }
        return dimension;
    }
    private static void same(Dimension left, Dimension right) {
        for (Tile tile : left.getTiles()) {
            Tile match = right.getTile(tile.getX(), tile.getY());
            assertEquals(tile.hasLayer(Resources.INSTANCE), match.hasLayer(Resources.INSTANCE));
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++)
                assertEquals(tile.getLayerValue(Resources.INSTANCE, x, y), match.getLayerValue(Resources.INSTANCE, x, y));
        }
    }
    static class Brush extends AbstractBrush {
        private int radius = 3; private float level = 1f;
        Brush() { super("Parity"); }
        @Override public float getStrength(int x, int y) { return level * getFullStrength(x, y); }
        @Override public float getFullStrength(int x, int y) {
            return radius == 0 ? 1f : (float) Math.max(0, 1 - Math.sqrt(x * x + y * y) / radius);
        }
        @Override public int getRadius() { return radius; }
        @Override public void setRadius(int radius) { this.radius = radius; }
        @Override public float getLevel() { return level; }
        @Override public void setLevel(float level) { this.level = level; }
        @Override public BrushShape getBrushShape() { return BrushShape.CIRCLE; }
    }
    private static void restore(String previous) {
        if (previous == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, previous);
    }
}
