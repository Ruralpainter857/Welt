package org.pepsoft.worldpainter;

import com.sun.management.ThreadMXBean;
import org.pepsoft.worldpainter.layers.Biome;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.layers.Resources;
import org.pepsoft.worldpainter.nativeapi.Native;

import java.awt.Point;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Complete, single-worker world rescaling benchmark, including output tile creation. */
public final class ScalingBenchmark {
    static volatile List<Tile> output;

    static Map<Point, Tile> fixture() {
        final Map<Point, Tile> tiles = new HashMap<>();
        for (int tx = -1; tx <= 0; tx++) {
            for (int ty = -1; ty <= 0; ty++) {
                final Tile tile = new Tile(tx, ty, TestData.MIN_HEIGHT, TestData.MAX_HEIGHT);
                tile.inhibitEvents();
                try {
                    for (int x = 0; x < 128; x++) {
                        for (int y = 0; y < 128; y++) {
                            tile.setHeight(x, y, 32.125f + (x * 3 + y * 7 + tx + ty) % 160);
                            tile.setWaterLevel(x, y, 62 + (x + y) % 5);
                            tile.setTerrain(x, y, (x + y) % 3 == 0 ? Terrain.SAND : Terrain.GRASS);
                            tile.setLayerValue(Resources.INSTANCE, x, y, (x + y * 3) % 16);
                            tile.setBitLayerValue(Frost.INSTANCE, x, y, (x + y) % 7 == 0);
                            tile.setLayerValue(Biome.INSTANCE, x, y, (x * 5 + y) % 255);
                        }
                    }
                } finally { tile.releaseEvents(); }
                tiles.put(new Point(tx, ty), tile);
            }
        }
        return tiles;
    }

    public static void main(String[] args) {
        final Map<Point, Tile> tiles = fixture();
        final TileFactory factory = TestData.createTileFactory(62);
        final ThreadMXBean memory = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        memory.setThreadAllocatedMemoryEnabled(true);
        final boolean javaOnly = args.length > 0 && args[0].equals("java");
        final boolean rustOnly = args.length > 0 && args[0].equals("rust");
        final boolean isolated = javaOnly || rustOnly;
        for (int i = 0; i < 6; i++) {
            if (!rustOnly) run(tiles, factory, false, memory);
            if (!javaOnly) run(tiles, factory, true, memory);
        }
        final double[][] times = new double[2][7], bytes = new double[2][7];
        for (int round = 0; round < 7; round++) {
            for (int order = 0; order < (isolated ? 1 : 2); order++) {
                final int mode = javaOnly ? 0 : rustOnly ? 1 : (round + order) & 1;
                final double[] sample = run(tiles, factory, mode == 1, memory);
                times[mode][round] = sample[0]; bytes[mode][round] = sample[1];
            }
        }
        for (int mode = rustOnly ? 1 : 0; mode < (javaOnly ? 1 : 2); mode++) {
            Arrays.sort(times[mode]); Arrays.sort(bytes[mode]);
            System.out.printf(Locale.ROOT, "%s full_scale_ms=%.3f allocated_heap_bytes=%.0f output_tiles=%d%n",
                    mode == 0 ? "Java" : "Rust", times[mode][3], bytes[mode][3], output.size());
        }
        System.out.printf(Locale.ROOT, "process_RSS_bytes=%d heap_used_after_benchmark_bytes=%d%n",
                org.pepsoft.worldpainter.nativeapi.NativeSlices.currentProcessResidentBytes(),
                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        System.out.println(isolated ? "RSS covers the isolated benchmark process; heap usage includes pending garbage."
                : "RSS is shared by both modes; heap usage includes pending garbage.");
    }

    private static double[] run(Map<Point, Tile> tiles, TileFactory factory, boolean nativeMode, ThreadMXBean memory) {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
        final long allocated = memory.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
        final ScalingHelper helper = new ScalingHelper(tiles, factory, 1.5f);
        final List<Point> coordinates = new ArrayList<>(helper.getTileCoords());
        coordinates.sort(Comparator.comparingInt((Point p) -> p.x).thenComparingInt(p -> p.y));
        final List<Tile> result = new ArrayList<>();
        for (Point coordinate : coordinates) result.add(helper.createScaledTile(coordinate.x, coordinate.y));
        output = result;
        return new double[] {(System.nanoTime() - start) / 1_000_000.0,
                memory.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated};
    }
}
