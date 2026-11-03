package org.pepsoft.worldpainter;

import org.pepsoft.util.ProgressReceiver;
import org.pepsoft.worldpainter.layers.Biome;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.layers.Populate;
import org.pepsoft.worldpainter.layers.Resources;
import org.pepsoft.worldpainter.nativeapi.Native;

import java.awt.Point;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whole-world rescaling benchmark: an irregular world with holes, negative coordinates and painted layers, scaled
 * through {@link Dimension#transform} and through {@link ScalingHelper#getAllScaledTiles} with a retained memory
 * breakdown. Set {@code org.pepsoft.worldpainter.threads} to fix the worker count.
 */
public final class ScalingWorldBenchmark {
    static volatile Object keep;

    static HeightMapTileFactory factory() {
        return TileFactoryFactory.createNoiseTileFactory(7L, Terrain.GRASS, -64, 320, 58, 62, false, true, 20f, 1.0);
    }

    /** About 300 tiles in a disc of radius 10 with holes, so that many generated border tiles are needed. */
    static Map<Point, Tile> world(HeightMapTileFactory factory) {
        final Map<Point, Tile> tiles = new HashMap<>();
        for (int tx = -10; tx < 10; tx++) {
            for (int ty = -10; ty < 10; ty++) {
                if (tx * tx + ty * ty > 100 || Math.floorMod(tx * 7 + ty * 3, 11) == 0) continue;
                final Tile tile = factory.createTile(tx, ty);
                tile.inhibitEvents();
                try {
                    final int n = tx * 31 + ty;
                    for (int x = 0; x < 128; x++) {
                        for (int y = 0; y < 128; y++) {
                            if (Math.floorMod(n, 3) == 0) tile.setHeight(x, y, tile.getHeight(x, y) + ((x * 3 + y * 7 + n) % 40) / 4f);
                            if (Math.floorMod(n, 4) == 0) tile.setLayerValue(Resources.INSTANCE, x, y, (x + y * 3) % 16);
                            if (Math.floorMod(n, 5) == 0) tile.setBitLayerValue(Frost.INSTANCE, x, y, (x + y) % 7 == 0);
                            if (Math.floorMod(n, 2) == 0) tile.setLayerValue(Biome.INSTANCE, x, y, (x * 5 + y + n) & 127);
                        }
                    }
                    if (Math.floorMod(n, 6) == 0) tile.setBitLayerValue(Populate.INSTANCE, 40, 72, true);
                } finally {
                    tile.releaseEvents();
                }
                tiles.put(new Point(tx, ty), tile);
            }
        }
        return tiles;
    }

    public static void main(String[] args) throws Exception {
        final boolean nativeMode = !(args.length > 0 && args[0].equals("java"));
        final float scale = args.length > 1 ? Float.parseFloat(args[1]) : 1.5f;
        final int samples = Integer.getInteger("welt.benchmark.scalingSamples", 5);
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
        final HeightMapTileFactory factory = factory();
        for (int i = 0; i < 3; i++) {
            transform(factory, scale);
            breakdown(factory, scale, false);
        }
        final double[] transformTimes = new double[samples], helperTimes = new double[samples];
        for (int i = 0; i < samples; i++) {
            // Alternate the complete Dimension path and the instrumented helper path
            transformTimes[i] = transform(factory, scale);
            helperTimes[i] = breakdown(factory, scale, i == samples - 1);
        }
        Arrays.sort(transformTimes);
        Arrays.sort(helperTimes);
        System.out.printf(Locale.ROOT, "%s scale=%s threads=%s dimension_transform_ms=%.1f helper_scale_ms=%.1f%n",
                nativeMode ? "Rust" : "Java", scale, System.getProperty("org.pepsoft.worldpainter.threads", "default"),
                transformTimes[samples / 2], helperTimes[samples / 2]);
    }

    private static double transform(HeightMapTileFactory factory, float scale) throws Exception {
        final Dimension dimension = new Dimension(new World2(TestData.PLATFORM, -64, 320), "Surface", 7L,
                factory(), Dimension.Anchor.NORMAL_DETAIL);
        for (Tile tile : world(factory).values()) dimension.addTile(tile);
        final long start = System.nanoTime();
        dimension.transform(CoordinateTransform.getScalingInstance(scale), null);
        final double elapsed = (System.nanoTime() - start) / 1e6;
        keep = dimension;
        keep = null;
        return elapsed;
    }

    private static double breakdown(HeightMapTileFactory factory, float scale, boolean report) throws Exception {
        Map<Point, Tile> sources = world(factory);
        final long sourceHeap = retainedHeap();
        final com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final Map<Thread, Long> workerAllocations = new ConcurrentHashMap<>();
        final ProgressReceiver progress = new ProgressReceiver() {
            // Pool threads are fresh per call, so their counters cover all their scaling allocations
            @Override public void setProgress(float progress) {
                workerAllocations.put(Thread.currentThread(), threads.getCurrentThreadAllocatedBytes());
            }
            @Override public void exceptionThrown(Throwable exception) { }
            @Override public void done() { }
            @Override public void setMessage(String message) { }
            @Override public void checkForCancellation() { }
            @Override public void reset() { }
            @Override public void subProgressStarted(org.pepsoft.util.SubProgressReceiver subProgressReceiver) { }
        };
        final long start = System.nanoTime();
        ScalingHelper helper = new ScalingHelper(sources, factory, scale);
        Set<Tile> result = helper.getAllScaledTiles(progress);
        final double elapsed = (System.nanoTime() - start) / 1e6;
        final long directBeforeGc = directBytes();
        final long allHeap = retainedHeap();
        final long directAfterGc = directBytes();
        final java.lang.reflect.Field field = ScalingHelper.class.getDeclaredField("additionalTiles");
        field.setAccessible(true);
        final Map<?, ?> additional = (Map<?, ?>) field.get(helper);
        final int additionalCount = additional.size();
        additional.clear();
        final long withoutBorders = retainedHeap();
        helper = null;
        final long withoutHelper = retainedHeap();
        final int outputCount = result.size();
        result = null;
        final long sourceOnly = retainedHeap();
        keep = sources;
        sources = null;
        keep = null;
        if (report) {
            long allocated = 0;
            for (long bytes : workerAllocations.values()) allocated += bytes;
            System.out.printf(Locale.ROOT, "source_tiles=%d output_tiles=%d border_tiles=%d workers=%d%n",
                    sourceCount(factory), outputCount, additionalCount, workerAllocations.size());
            System.out.printf(Locale.ROOT, "retained_source_bytes=%d retained_output_bytes=%d retained_border_bytes=%d"
                            + " retained_other_helper_bytes=%d source_drift_bytes=%d%n",
                    sourceHeap, withoutHelper - sourceOnly, allHeap - withoutBorders, withoutBorders - withoutHelper,
                    sourceOnly - sourceHeap);
            System.out.printf(Locale.ROOT, "worker_allocated_bytes=%d direct_bytes_after_scaling=%d direct_bytes_after_gc=%d%n",
                    allocated, directBeforeGc, directAfterGc);
        }
        return elapsed;
    }

    private static int sourceCount(HeightMapTileFactory factory) {
        int count = 0;
        for (int tx = -10; tx < 10; tx++) for (int ty = -10; ty < 10; ty++)
            if (tx * tx + ty * ty <= 100 && Math.floorMod(tx * 7 + ty * 3, 11) != 0) count++;
        return count;
    }

    /** Heap after repeated full collections; a benchmark estimate only, never used by tests. */
    private static long retainedHeap() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(40);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long directBytes() {
        long direct = 0;
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class))
            if (pool.getName().equals("direct")) direct += pool.getMemoryUsed();
        return direct;
    }
}
