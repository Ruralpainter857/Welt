package org.pepsoft.worldpainter.layers;

import java.awt.Color;
import java.awt.Rectangle;
import java.lang.management.ManagementFactory;
import java.lang.management.BufferPoolMXBean;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.TestData;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.nativeapi.Native;

/** Measures the complete editor action, including reading, random draws and applying all planes. */
public final class CombinedLayerBenchmark {
    public static CombinedLayer layer() {
        CombinedLayer layer = new CombinedLayer("Benchmark", "Combined layer fixture", Color.GREEN);
        layer.setLayers(List.of(Resources.INSTANCE, DeciduousForest.INSTANCE, Frost.INSTANCE, Populate.INSTANCE));
        layer.setFactors(Map.of(Resources.INSTANCE, 1.25f, DeciduousForest.INSTANCE, 0.75f,
                Frost.INSTANCE, 0.8f, Populate.INSTANCE, 1.5f));
        return layer;
    }

    public static Dimension fixture(CombinedLayer source) {
        Dimension world = TestData.createDimension(new Rectangle(0, 0, 128, 128), 62);
        world.removeTile(0, 0);
        for (int tx = -4; tx < 4; tx++) for (int ty = -4; ty < 4; ty++) {
            Tile tile = new Tile(tx, ty, -64, 320);
            tile.inhibitEvents();
            for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
                tile.setLayerValue(source, x, y, (x * 3 + y * 7 + tx + ty) & 15);
            }
            tile.releaseEvents();
            world.addTile(tile);
        }
        return world;
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("compare")) {
            compare();
            return;
        }
        boolean rust = args.length > 0 && args[0].equals("rust");
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        double[] times = new double[7];
        long[] allocations = new long[7];
        int checksum = 0;
        for (int trial = -5; trial < 7; trial++) {
            // Build both fixtures through the same Java path, excluding unrelated native scratch buffers.
            System.setProperty(Native.GEN_KEY, "false");
            CombinedLayer source = layer();
            Dimension world = fixture(source);
            System.setProperty(Native.GEN_KEY, Boolean.toString(rust));
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
            long start = System.nanoTime();
            checksum += source.apply(world).size();
            if (trial >= 0) {
                times[trial] = (System.nanoTime() - start) / 1e6;
                allocations[trial] = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            }
        }
        Arrays.sort(times);
        Arrays.sort(allocations);
        long direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(pool -> pool.getName().equals("direct")).mapToLong(BufferPoolMXBean::getMemoryUsed).sum();
        System.out.printf(java.util.Locale.ROOT, "%s combined_layer_world_ms=%.3f allocated_bytes=%d direct_buffer_bytes=%d checksum=%d%n",
                args.length > 0 ? args[0] : "java", times[3], allocations[3], direct, checksum);
    }

    /** Paired runs use identical fresh fixtures and alternate order within one warmed JVM. */
    private static void compare() {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        double[][] times = new double[2][9];
        long[][] allocations = new long[2][9];
        int checksum = 0;
        for (int trial = -5; trial < 9; trial++) for (int order = 0; order < 2; order++) {
            int engine = (trial & 1) == 0 ? order : 1 - order;
            System.setProperty(Native.GEN_KEY, "false");
            CombinedLayer source = layer();
            Dimension world = fixture(source);
            System.setProperty(Native.GEN_KEY, Boolean.toString(engine == 1));
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
            long start = System.nanoTime();
            checksum += source.apply(world).size();
            double elapsed = (System.nanoTime() - start) / 1e6;
            long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            if (trial >= 0) { times[engine][trial] = elapsed; allocations[engine][trial] = allocated; }
        }
        double[] ratios = new double[9];
        for (int trial = 0; trial < 9; trial++) ratios[trial] = times[0][trial] / times[1][trial];
        for (int engine = 0; engine < 2; engine++) {
            Arrays.sort(times[engine]); Arrays.sort(allocations[engine]);
            System.out.printf(java.util.Locale.ROOT,
                    "%s paired_combined_layer_world_ms=%.3f allocated_bytes=%d checksum=%d%n",
                    engine == 0 ? "java" : "rust", times[engine][4], allocations[engine][4], checksum);
        }
        Arrays.sort(ratios);
        System.out.printf(java.util.Locale.ROOT, "paired_ratio_median=%.3f min=%.3f max=%.3f%n",
                ratios[4], ratios[0], ratios[8]);
    }
}
