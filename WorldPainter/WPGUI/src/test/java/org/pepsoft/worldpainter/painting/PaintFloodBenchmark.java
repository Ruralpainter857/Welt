package org.pepsoft.worldpainter.painting;

import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.nativeapi.Native;

/** Mesure le vrai remplissage DimensionPainter, avec les copies et les écritures. */
public final class PaintFloodBenchmark {
    static Dimension fixture() { return fixture(2); }
    static Dimension fixture(int side) {
        Dimension d = FluidFloodBenchmark.fixture();
        if (side != 2) {
            for (var point : new java.util.ArrayList<>(d.getTileCoords())) d.removeTile(point.x, point.y);
            for (int tx = -1; tx < side - 1; tx++) for (int ty = -1; ty < side - 1; ty++) {
                Tile tile = new Tile(tx, ty, d.getMinHeight(), d.getMaxHeight()); tile.inhibitEvents();
                for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) { tile.setHeight(x, y, 50); tile.setWaterLevel(x, y, 49); }
                tile.releaseEvents(); d.addTile(tile);
            }
        }
        for (Tile t : d.getTiles()) {
            t.inhibitEvents();
            for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
                t.setTerrain(x, y, x == 127 ? Terrain.STONE : Terrain.GRASS);
                t.setLayerValue(Biome.INSTANCE, x, y, x == 127 ? 254 : 42);
                t.setLayerValue(Resources.INSTANCE, x, y, x == 127 ? 15 : 5);
            }
            t.releaseEvents();
        }
        return d;
    }
    static Paint paint(String type, int pass) {
        Paint p = switch (type) {
            case "terrain" -> new TerrainPaint(pass == 0 ? Terrain.DIRT : Terrain.GRASS);
            case "biome" -> new DiscreteLayerPaint(Biome.INSTANCE, pass == 0 ? 77 : 42);
            case "nibble" -> new NibbleLayerPaint(Resources.INSTANCE);
            case "bit" -> new BitLayerPaint(Frost.INSTANCE);
            default -> throw new IllegalArgumentException(type);
        };
        var brush = SymmetricBrush.CONSTANT_SQUARE.clone(); brush.setLevel(.6f); p.setBrush(brush); return p;
    }
    public static void main(String[] args) {
        String type = args.length > 0 ? args[0] : "biome";
        boolean rust = args.length > 1 && args[1].equals("rust");
        org.pepsoft.worldpainter.nativeapi.NativeLoader.areSlicesAvailable();
        var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        double[] times = new double[7]; long allocated = 0;
        for (int trial = -5; trial < 7; trial++) {
            System.setProperty(Native.GEN_KEY, "false"); Dimension d = fixture(args.length > 2 ? Integer.parseInt(args[2]) : 2);
            Paint[] paints = {paint(type, 0), paint(type, 1)};
            DimensionPainter painter = new DimensionPainter(); d.setEventsInhibited(true);
            System.setProperty(Native.GEN_KEY, Boolean.toString(rust));
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
            for (int i = 0; i < 12; i++) {
                painter.setPaint(paints[i & 1]); painter.setUndo((type.equals("nibble") || type.equals("bit")) && (i & 1) != 0);
                if (!painter.fill(d, -64, -64, null)) throw new AssertionError("Incomplete fill");
            }
            d.setEventsInhibited(false);
            allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            if (trial >= 0) times[trial] = (System.nanoTime() - start) / 1e6;
        }
        java.util.Arrays.sort(times);
        System.out.printf("%s %s paint_fill_12_actions_ms=%.3f heap_allocated_bytes=%d%n", rust ? "Rust" : "Java", type, times[3], allocated);
    }
}
