package org.pepsoft.worldpainter.operations;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import org.pepsoft.worldpainter.Configuration;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.FluidBrushAccess;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.nativeapi.Native;

/** Complete dry/reset ticks with history and notifications, excluding asynchronous display redraw. */
public final class LargeSpongeStrokeBenchmark {
    static Dimension fixture(int radius) throws Exception {
        Dimension dimension = LargeMountainStrokeBenchmark.fixture(radius, false);
        dimension.unregisterUndoManager();
        for (Tile tile : dimension.getTiles()) {
            tile.inhibitEvents();
            try {
                for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) {
                    tile.setWaterLevel(x, y, 65 + ((x + y) & 7));
                    if (((x + 3 * y) & 7) == 0) tile.setBitLayerValue(FloodWithLava.INSTANCE, x, y, true);
                }
            } finally { tile.releaseEvents(); }
        }
        dimension.registerUndoManager(new org.pepsoft.util.undo.UndoManager(8));
        return dimension;
    }

    static Sponge brush(BrushPipelineTestView view, int radius) {
        Sponge brush = new Sponge(view);
        brush.setBrush(SymmetricBrush.LINEAR_CIRCLE.clone());
        brush.setRadius(radius);
        brush.setLevel(.63f);
        return brush;
    }

    static long affectedTiles(Dimension dimension, Sponge brush) {
        int radius = brush.getBrush().getEffectiveRadius();
        long count = 0;
        for (Tile tile : dimension.getTiles()) {
            int x1 = Math.max(tile.getX() * 128, -3 - radius), x2 = Math.min(tile.getX() * 128 + 127, -3 + radius);
            int y1 = Math.max(tile.getY() * 128, 5 - radius), y2 = Math.min(tile.getY() * 128 + 127, 5 + radius);
            boolean touched = false;
            for (int x = x1; x <= x2 && !touched; x++) for (int y = y1; y <= y2; y++) {
                if (brush.getBrush().getStrength(x + 3, y - 5) != 0f) { touched = true; break; }
            }
            if (touched) count++;
        }
        return count;
    }

    private record Result(double millis, long allocation, long hash, long heap, long direct) {}

    private static Result run(BrushPipelineTestView view, Sponge brush, int radius, boolean nativeMode, boolean memory) throws Exception {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
        Dimension dimension = fixture(radius);
        LargeMountainStrokeBenchmark.bind(view, dimension);
        var meter = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        meter.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        long expectedTransactions = nativeMode ? 4 * affectedTiles(dimension, brush) : 0;
        if (memory) System.gc();
        long calls = FluidBrushAccess.completedNativeCalls();
        long allocated = meter.getThreadAllocatedBytes(thread), start = System.nanoTime();
        for (int tick = 0; tick < 4; tick++) {
            dimension.armSavePoint();
            brush.tick(-3, 5, (tick & 1) != 0, tick == 0, .85f);
        }
        double millis = (System.nanoTime() - start) / 1e6;
        allocated = meter.getThreadAllocatedBytes(thread) - allocated;
        long transactions = FluidBrushAccess.completedNativeCalls() - calls;
        if (transactions != expectedTransactions) throw new AssertionError("Unexpected native sponge coverage: " + transactions + "/" + expectedTransactions);
        long heap = 0, direct = 0;
        if (memory) {
            System.gc();
            heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            direct = ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class).stream()
                    .filter(pool -> pool.getName().equals("direct")).mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed).sum();
        }
        long hash = LargeMountainStrokeBenchmark.digest(dimension);
        LargeMountainStrokeBenchmark.bind(view, null);
        java.lang.ref.Reference.reachabilityFence(dimension);
        return new Result(millis, allocated, hash, heap, direct);
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "java" : args[0];
        boolean paired = mode.equals("compare"), memory = Boolean.getBoolean("welt.benchmark.largeSpongeMemory");
        if ((!paired && !mode.equals("java") && !mode.equals("rust")) || (paired && memory)) throw new IllegalArgumentException("Invalid mode");
        int trials = Integer.getInteger("welt.benchmark.largeSpongeTrials", 7);
        int radius = Integer.getInteger("welt.benchmark.largeSpongeRadius", 383);
        if (trials < 3) throw new IllegalArgumentException("At least three trials required");
        System.setProperty("welt.native.largeFluidBrush", "true");
        System.setProperty(Native.RENDER_KEY, "false");
        System.setProperty(Native.GEN_KEY, "false");
        Configuration.setInstance(new Configuration());
        BrushPipelineTestView view = new BrushPipelineTestView();
        Sponge brush = brush(view, radius);
        double[] left = new double[trials], right = new double[trials], ratios = new double[trials];
        long expected = 0;
        for (int trial = -5; trial < trials; trial++) {
            Result java = null, rust = null;
            for (int pass = 0; pass < (paired ? 2 : 1); pass++) {
                boolean nativeMode = paired ? ((trial & 1) == pass) : mode.equals("rust");
                Result result = run(view, brush, radius, nativeMode, memory);
                if (expected != 0 && expected != result.hash) throw new AssertionError("Complete sponge result differs");
                expected = result.hash;
                if (nativeMode) rust = result; else java = result;
                if (trial >= 0) System.out.printf(Locale.ROOT,
                        "sponge mode=%s trial=%d ms=%.3f allocated=%d hash=%d heap=%d direct=%d%n",
                        nativeMode ? "rust" : "java", trial, result.millis, result.allocation, result.hash, result.heap, result.direct);
            }
            if (trial >= 0) {
                left[trial] = java == null ? 0 : java.millis;
                right[trial] = rust == null ? 0 : rust.millis;
                ratios[trial] = paired ? java.millis / rust.millis : 0;
            }
        }
        Arrays.sort(left); Arrays.sort(right); Arrays.sort(ratios);
        System.out.printf(Locale.ROOT, "sponge_summary java_ms=%.3f rust_ms=%.3f paired_ratio=%.3f memory_instrumented=%s%n",
                left[trials / 2], right[trials / 2], ratios[trials / 2], memory);
    }
}
