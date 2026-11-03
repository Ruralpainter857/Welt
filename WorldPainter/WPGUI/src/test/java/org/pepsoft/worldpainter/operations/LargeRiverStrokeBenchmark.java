package org.pepsoft.worldpainter.operations;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.util.Arrays;
import java.util.Locale;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.Configuration;
import org.pepsoft.worldpainter.DefaultPlugin;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.HeightMapTileFactory;
import org.pepsoft.worldpainter.RiverAccess;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.World2;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.heightMaps.ConstantHeightMap;
import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.themes.SimpleTheme;

/**
 * Complete River Paint drags (several ticks, including strengths, edge search, mutations, undo save points and
 * events; display redraw excluded) on a heterogeneous fixture: varied heights and water, lava, a missing tile and
 * negative coordinates. Java and native runs alternate; the digest and the native transaction count are checked
 * outside the timed part. Memory figures are only collected in separate runs ({@code -Dwelt.benchmark.largeRiverMemory}).
 *
 * <p>Arguments: {@code java|rust|compare}; {@code -Dwelt.benchmark.largeRiverRadius} (383), {@code ...Tall} (true),
 * {@code ...Trials} (7).
 */
public final class LargeRiverStrokeBenchmark {
    /** The centres of the ticks of a drag; the first one measures the depth and fluid. */
    static final int[][] TICKS = {{-3, 5}, {37, -21}, {-81, 63}, {11, 9}};

    /**
     * Tiles around the drag with heights and water levels varying per cell, lava in a band, terrain grass and one
     * missing tile. The first centre lies below its water level so that the drag has a depth.
     */
    static Dimension fixture(int radius, boolean tall) {
        final int maxHeight = tall ? 320 : 192;
        final var factory = new HeightMapTileFactory(197, new ConstantHeightMap(64), -64, maxHeight, false,
                SimpleTheme.createDefault(Terrain.GRASS, -64, maxHeight, 62, true, true));
        // The platform requires a build height of at least 256; short (8 bit water) tiles are set on the dimension
        final var world = new World2(DefaultPlugin.JAVA_ANVIL_1_19, -64, 320);
        final var dimension = new Dimension(world, "Large river", 197, factory, Dimension.Anchor.NORMAL_DETAIL, true);
        if (! tall) dimension.setMaxHeight(maxHeight);
        final int reach = radius + 90;
        for (int ty = (-reach) >> 7; ty <= reach >> 7; ty++) for (int tx = (-reach) >> 7; tx <= reach >> 7; tx++) {
            if (tx == 1 && ty == 1) continue;
            final Tile tile = new Tile(tx, ty, -64, maxHeight);
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                final int wx = (tx << 7) + x, wy = (ty << 7) + y;
                tile.setRawHeight(x, y, 26000 + Math.floorMod(wx * 193 + wy * 79, 9000) + Math.floorMod(wx * 7 - wy * 11, 600) * 7);
                tile.setWaterLevel(x, y, 40 + Math.floorMod(wx * 3 + wy * 5, 30));
                tile.setTerrain(x, y, Terrain.GRASS);
                if (Math.floorMod(wx + 2 * wy, 97) < 9) tile.setBitLayerValue(FloodWithLava.INSTANCE, x, y, true);
            }
            dimension.addTile(tile);
        }
        // A deep spot under water at the first centre
        dimension.setHeightAt(TICKS[0][0], TICKS[0][1], 30f);
        dimension.setWaterLevelAt(TICKS[0][0], TICKS[0][1], 62);
        dimension.registerUndoManager(new UndoManager(8));
        return dimension;
    }

    static RiverPaint brush(BrushPipelineTestView view, int radius) {
        final RiverPaint brush = new RiverPaint(view);
        brush.setBrush(SymmetricBrush.LINEAR_CIRCLE.clone());
        brush.setRadius(radius);
        brush.setLevel(1.0f);
        return brush;
    }

    /** One drag: a tick at each centre, each preceded by an undo save point as the editor does. */
    static void drag(RiverPaint brush, Dimension dimension) {
        for (int tick = 0; tick < TICKS.length; tick++) {
            dimension.armSavePoint();
            brush.tick(TICKS[tick][0], TICKS[tick][1], false, tick == 0, 1.0f);
        }
    }

    /** Completed native large river transactions, or -1 before that API exists. */
    static long largeCalls() {
        try {
            return (long) RiverAccess.class.getMethod("completedLargeCalls").invoke(null);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    private record Result(double millis, long allocated, long hash, long calls, long heap, long direct, long peakPools) {}

    private static Result run(BrushPipelineTestView view, RiverPaint brush, int radius, boolean tall, boolean nativeMode, boolean memory) throws Exception {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
        final Dimension dimension = fixture(radius, tall);
        LargeMountainStrokeBenchmark.bind(view, dimension);
        final var meter = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        meter.setThreadAllocatedMemoryEnabled(true);
        final long thread = Thread.currentThread().getId();
        if (memory) {
            System.gc();
            ManagementFactory.getMemoryPoolMXBeans().forEach(pool -> pool.resetPeakUsage());
        }
        final long calls = largeCalls();
        long allocation = meter.getThreadAllocatedBytes(thread);
        final long start = System.nanoTime();
        drag(brush, dimension);
        final double millis = (System.nanoTime() - start) / 1e6;
        allocation = meter.getThreadAllocatedBytes(thread) - allocation;
        final long completed = (calls < 0) ? -1 : largeCalls() - calls;
        long peak = 0, heap = 0, direct = 0;
        if (memory) {
            peak = ManagementFactory.getMemoryPoolMXBeans().stream().filter(pool -> pool.getType() == MemoryType.HEAP)
                    .mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
            System.gc();
            heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                    .filter(pool -> pool.getName().equals("direct")).mapToLong(BufferPoolMXBean::getMemoryUsed).sum();
        }
        final long hash = LargeMountainStrokeBenchmark.digest(dimension);
        LargeMountainStrokeBenchmark.bind(view, null);
        java.lang.ref.Reference.reachabilityFence(dimension);
        return new Result(millis, allocation, hash, completed, heap, direct, peak);
    }

    public static void main(String[] args) throws Exception {
        final String mode = (args.length == 0) ? "java" : args[0];
        final boolean paired = mode.equals("compare"), memory = Boolean.getBoolean("welt.benchmark.largeRiverMemory");
        if ((! paired && ! mode.equals("java") && ! mode.equals("rust")) || (memory && paired)) throw new IllegalArgumentException("Invalid benchmark mode");
        final int radius = Integer.getInteger("welt.benchmark.largeRiverRadius", 383);
        final boolean tall = Boolean.parseBoolean(System.getProperty("welt.benchmark.largeRiverTall", "true"));
        final int trials = Integer.getInteger("welt.benchmark.largeRiverTrials", 7), warmups = Integer.getInteger("welt.benchmark.largeRiverWarmups", 5);
        if (trials < 3) throw new IllegalArgumentException("At least three trials required");
        System.setProperty("welt.native.largeRiver", "true");
        System.setProperty(Native.RENDER_KEY, "false");
        Configuration.setInstance(new Configuration());
        final BrushPipelineTestView view = new BrushPipelineTestView();
        final RiverPaint brush = brush(view, radius);
        final double[] javaTimes = new double[trials], rustTimes = new double[trials], ratios = new double[trials];
        long expectedHash = 0;
        for (int trial = -warmups; trial < trials; trial++) {
            Result java = null, rust = null;
            for (int pass = 0; pass < (paired ? 2 : 1); pass++) {
                final boolean nativeMode = paired ? (((trial + pass) & 1) != 0) : mode.equals("rust");
                final Result result = run(view, brush, radius, tall, nativeMode, memory && (trial >= 0));
                if ((trial == -warmups) && (pass == 0)) expectedHash = result.hash;
                else if (expectedHash != result.hash) throw new AssertionError("River Paint output changed");
                // Radii up to 255 keep the WLRV v1 transaction
                if (nativeMode && (result.calls >= 0) && (result.calls != ((radius > 255) ? TICKS.length : 0))) throw new AssertionError("Native large river transactions: " + result.calls);
                if ((! nativeMode) && (result.calls > 0)) throw new AssertionError("Native transactions in Java mode");
                if (nativeMode) rust = result; else java = result;
                if (trial >= 0) System.out.printf(Locale.ROOT,
                        "river mode=%s radius=%d tall=%s trial=%d ms=%.3f allocated=%d hash=%d native_calls=%d heap=%d direct=%d peak_pool_sum=%d%n",
                        nativeMode ? "rust" : "java", radius, tall, trial, result.millis, result.allocated, result.hash, result.calls, result.heap, result.direct, result.peakPools);
            }
            if (trial >= 0) {
                if (java != null) javaTimes[trial] = java.millis;
                if (rust != null) rustTimes[trial] = rust.millis;
                if (paired) ratios[trial] = java.millis / rust.millis;
            }
        }
        Arrays.sort(javaTimes); Arrays.sort(rustTimes); Arrays.sort(ratios);
        System.out.printf(Locale.ROOT, "river_summary radius=%d tall=%s java_ms=%.3f rust_ms=%.3f paired_ratio=%.3f memory_instrumented=%s%n",
                radius, tall, javaTimes[trials / 2], rustTimes[trials / 2], ratios[trials / 2], memory);
    }
}
