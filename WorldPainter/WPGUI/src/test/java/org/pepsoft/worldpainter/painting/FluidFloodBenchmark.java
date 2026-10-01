package org.pepsoft.worldpainter.painting;

import java.awt.Rectangle;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.nativeapi.Native;

/** Mesure l'outil complet : exploration, données des tuiles et application des fluides. */
public final class FluidFloodBenchmark {
    static World2 world() {
        Platform platform = DefaultPlugin.JAVA_ANVIL_1_19;
        return new World2(platform, platform.minZ, platform.standardMaxHeight);
    }
    public static Dimension fixture() { return fixture(2); }
    public static Dimension fixture(int side) {
        World2 world = world();
        int min = world.getMinHeight(), max = world.getMaxHeight();
        var factory = new HeightMapTileFactory(0L, new org.pepsoft.worldpainter.heightMaps.ConstantHeightMap(62), min, max,
                false, org.pepsoft.worldpainter.themes.SimpleTheme.createSingleTerrain(Terrain.GRASS, min, max, 62));
        Dimension d = new Dimension(world, "Fluid", 0, factory, Dimension.Anchor.NORMAL_DETAIL, false);
        for (int tx = -1; tx < side - 1; tx++) for (int ty = -1; ty < side - 1; ty++) {
            Tile t = new Tile(tx, ty, d.getMinHeight(), d.getMaxHeight()); t.inhibitEvents();
            for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
                t.setHeight(x, y, side == 2 && x == 127 ? 100 : 50 + (y % 11) / 256f); t.setWaterLevel(x, y, 49);
            }
            t.releaseEvents(); d.addTile(t);
        }
        return d;
    }
    public static void scalar(Dimension d, int sx, int sy, boolean inverse, boolean lava) {
        int height = d.getIntHeightAt(sx, sy), water = d.getWaterLevelAt(sx, sy);
        if (height == Integer.MIN_VALUE) return;
        boolean present = water > height;
        if (inverse && !present) return;
        boolean convert = present && lava != d.getBitLayerValueAt(FloodWithLava.INSTANCE, sx, sy);
        int base = Math.max(height, water);
        if (!convert && (inverse ? base <= d.getMinHeight() : base >= d.getMaxHeight() - 1)) return;
        int level = inverse ? base - 1 : base + 1;
        GeneralQueueLinearFloodFiller f = new GeneralQueueLinearFloodFiller(new GeneralQueueLinearFloodFiller.FillMethod() {
            @Override public String getDescription() { return "Fluid parity"; }
            @Override public Rectangle getBounds() { return new Rectangle(d.getLowestX() * 128, d.getLowestY() * 128, d.getWidth() * 128, d.getHeight() * 128); }
            @Override public boolean isNativeSnapshotSafe() { return true; }
            @Override public boolean isBoundary(int x, int y) {
                int h = d.getIntHeightAt(x, y), w = d.getWaterLevelAt(x, y);
                if (h == Integer.MIN_VALUE) return true;
                if (convert) return w <= h || d.getBitLayerValueAt(FloodWithLava.INSTANCE, x, y) == lava;
                return inverse ? w <= h || w <= level : h >= level || w >= level;
            }
            @Override public void fill(int x, int y) {
                if (!convert) d.setWaterLevelAt(x, y, level);
                d.setBitLayerValueAt(FloodWithLava.INSTANCE, x, y, lava);
            }
        });
        if (!f.floodFill(sx, sy, null)) throw new AssertionError("Cancelled");
    }
    public static void main(String[] args) throws Exception {
        boolean rust = args.length > 0 && args[0].equals("rust");
        int side = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        org.pepsoft.worldpainter.nativeapi.NativeLoader.areSlicesAvailable();
        double[] times = new double[7]; long allocated = 0;
        var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        for (int trial = -5; trial < 7; trial++) {
            System.setProperty(Native.GEN_KEY, "false");
            Dimension d = fixture(side); d.setEventsInhibited(true);
            System.setProperty(Native.GEN_KEY, Boolean.toString(rust));
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
            for (int i = 0; i < 12; i++) {
                if (rust) { if (!FluidFloodAccess.tryFill(d, -64, -64, false, (i & 3) == 2)) {
                    FluidFloodSession session = FluidFloodSession.tryStart(d, -64, -64, false, (i & 3) == 2);
                    if (session == null) throw new AssertionError("JNI unavailable");
                    while (!session.isComplete()) session.advance();
                } }
                else scalar(d, -64, -64, false, (i & 3) == 2);
            }
            d.setEventsInhibited(false);
            allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            if (trial >= 0) times[trial] = (System.nanoTime() - start) / 1e6;
        }
        java.util.Arrays.sort(times);
        System.out.printf("%s fluid_12_clicks_ms=%.3f heap_allocated_bytes=%d%n", rust ? "Rust" : "Java", times[3], allocated);
    }
}
