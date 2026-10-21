package org.pepsoft.worldpainter;

import java.awt.Rectangle;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import org.pepsoft.worldpainter.nativeapi.Native;

public final class PyramidRegionBenchmark {
    static Dimension fixture() {
        Dimension dimension = TestData.createDimension(new Rectangle(0, 0, 128, 128), 62);
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) {
            if (x == 0 && y == 0 || x == 1 && y == 1) continue;
            Tile tile = new Tile(x, y, dimension.getMinHeight(), dimension.getMaxHeight());
            for (int a = 0; a < 128; a++) for (int b = 0; b < 128; b++) tile.setHeight(a, b, 62 + ((a * 3 + b) % 7) / 4f);
            dimension.addTile(tile);
        }
        return dimension;
    }
    static void scalar(Dimension dimension, int cx, int cy, boolean rotated) {
        float desired = dimension.getHeightAt(cx, cy);
        if (desired < dimension.getMaxHeight() - 1.5f) dimension.setHeightAt(cx, cy, desired + 1);
        dimension.setTerrainAt(cx, cy, Terrain.SANDSTONE);
        for (int ring = 1; ring < dimension.getMaxHeight() - dimension.getMinHeight(); ring++) {
            boolean raised = false;
            if (rotated) {
                for (int offset = 0; offset < ring; offset++) {
                    raised |= cell(dimension, cx - ring + offset, cy - offset, desired);
                    raised |= cell(dimension, cx + offset, cy - ring + offset, desired);
                    raised |= cell(dimension, cx + ring - offset, cy + offset, desired);
                    raised |= cell(dimension, cx - offset, cy + ring - offset, desired);
                }
            } else {
                for (int offset = -ring; offset <= ring; offset++) {
                    raised |= cell(dimension, cx + offset, cy - ring, desired);
                    raised |= cell(dimension, cx + offset, cy + ring, desired);
                }
                for (int offset = -ring + 1; offset < ring; offset++) {
                    raised |= cell(dimension, cx - ring, cy + offset, desired);
                    raised |= cell(dimension, cx + ring, cy + offset, desired);
                }
            }
            if (!raised) break;
            desired--;
        }
    }
    private static boolean cell(Dimension dimension, int x, int y, float desired) {
        if (dimension.getHeightAt(x, y) < desired) {
            dimension.setHeightAt(x, y, desired); dimension.setTerrainAt(x, y, Terrain.SANDSTONE); return true;
        }
        return false;
    }
    public static void main(String[] args) {
        boolean rust = args.length > 0 && args[0].equals("rust"); System.setProperty(Native.GEN_KEY, "true");
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        double[] times = new double[7]; long allocated = 0;
        for (int trial = -5; trial < 7; trial++) {
            Dimension dimension = fixture(); int centre = args.length > 1 && args[1].equals("flat") ? 64 : -1;
            if (centre == -1) dimension.setHeightAt(centre, centre, 200.25f); dimension.setEventsInhibited(true);
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
            for (int stroke = 0; stroke < 8; stroke++) {
                if (rust) { if (!PyramidAccess.tryApply(dimension, centre, centre, (stroke & 1) != 0)) throw new AssertionError("JNI path unavailable"); }
                else scalar(dimension, centre, centre, (stroke & 1) != 0);
            }
            double elapsed = (System.nanoTime() - start) / 1e6;
            allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            dimension.setEventsInhibited(false); if (trial >= 0) times[trial] = elapsed;
        }
        Arrays.sort(times); System.out.printf("%s pyramid_8_strokes_ms=%.3f heap_allocated_bytes=%d%n", rust ? "Rust" : "Java", times[3], allocated);
    }
}
