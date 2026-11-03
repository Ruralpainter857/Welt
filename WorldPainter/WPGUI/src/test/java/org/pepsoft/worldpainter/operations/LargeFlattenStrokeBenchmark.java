package org.pepsoft.worldpainter.operations;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Configuration;
import org.pepsoft.worldpainter.HeightBrushAccess;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.nativeapi.Native;

/** Complete flatten ticks, including strengths, theme, undo and events; display redraw is excluded. */
public final class LargeFlattenStrokeBenchmark {
    static Flatten brush(BrushPipelineTestView view, int radius, boolean theme) throws Exception {
        Flatten brush = new Flatten(view);
        brush.setBrush(SymmetricBrush.LINEAR_CIRCLE.clone());
        brush.setRadius(radius);
        brush.setLevel(.63f);
        ((TerrainShapingOptions<?>) field(brush, "options")).setApplyTheme(theme);
        return brush;
    }

    static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    static void mode(Flatten brush, Flatten.Mode mode) throws Exception {
        Field field = Flatten.class.getDeclaredField("mode");
        field.setAccessible(true);
        field.set(brush, mode);
    }

    static long digest(Dimension dimension) throws Exception {
        Method method = LargeHeightStrokeBenchmark.class.getDeclaredMethod("digest", Dimension.class);
        method.setAccessible(true);
        return (long) method.invoke(null, dimension);
    }

    static void bind(BrushPipelineTestView view, Dimension dimension) throws Exception {
        // Attach the real operation model without asynchronous display work, which this harness excludes.
        Method method = org.pepsoft.worldpainter.WorldPainter.class.getDeclaredMethod("setDimension", Dimension.class, boolean.class);
        method.setAccessible(true);
        method.invoke(view, dimension, false);
    }

    private record Result(double millis, long allocated, long hash, long random, long heap, long direct, long peakPools) {}

    private static Result run(BrushPipelineTestView view, Flatten brush, int radius, boolean nativeMode, boolean memory, boolean theme) throws Exception {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
        Dimension dimension = LargeHeightStrokeBenchmark.fixture(radius);
        bind(view, dimension);
        LargeHeightStrokeParityTest.random().setSeed(197);
        var meter = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        meter.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        if (memory) {
            System.gc();
            ManagementFactory.getMemoryPoolMXBeans().forEach(pool -> pool.resetPeakUsage());
        }
        long calls = HeightBrushAccess.completedThemedCalls();
        long allocation = meter.getThreadAllocatedBytes(thread), start = System.nanoTime();
        for (int stroke = 0; stroke < 4; stroke++) {
            dimension.armSavePoint();
            brush.tick(-3, 5, false, stroke == 0, .85f);
        }
        double millis = (System.nanoTime() - start) / 1e6;
        allocation = meter.getThreadAllocatedBytes(thread) - allocation;
        if (HeightBrushAccess.completedThemedCalls() - calls != (nativeMode && theme ? 4 : 0)) {
            throw new AssertionError("Unexpected grouped flatten coverage");
        }
        long peak = 0, heap = 0, direct = 0;
        if (memory) {
            peak = ManagementFactory.getMemoryPoolMXBeans().stream().filter(pool -> pool.getType() == MemoryType.HEAP)
                    .mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
            System.gc();
            heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            direct = ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class).stream()
                    .filter(pool -> pool.getName().equals("direct")).mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed).sum();
        }
        long hash = digest(dimension), random = LargeHeightStrokeParityTest.random().nextLong();
        bind(view, null);
        java.lang.ref.Reference.reachabilityFence(dimension);
        return new Result(millis, allocation, hash, random, heap, direct, peak);
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "java" : args[0];
        boolean paired = mode.equals("compare"), memory = Boolean.getBoolean("welt.benchmark.largeFlattenMemory");
        boolean theme = Boolean.parseBoolean(System.getProperty("welt.benchmark.largeFlattenTheme", "true"));
        if ((!paired && !mode.equals("java") && !mode.equals("rust")) || (memory && paired)) throw new IllegalArgumentException("Invalid benchmark mode");
        int trials = Integer.getInteger("welt.benchmark.largeFlattenTrials", 7);
        if (trials < 3) throw new IllegalArgumentException("At least three trials required");
        System.setProperty("welt.native.largeHeightBrush", "true");
        System.setProperty(Native.GEN_KEY, "false");
        System.setProperty(Native.RENDER_KEY, "false");
        Configuration.setInstance(new Configuration());
        BrushPipelineTestView view = new BrushPipelineTestView();
        Flatten brush = brush(view, 255, theme);
        double[] javaTimes = new double[trials], rustTimes = new double[trials], ratios = new double[trials];
        long expectedHash = 0, expectedRandom = 0;
        for (int trial = -5; trial < trials; trial++) {
            Result java = null, rust = null;
            for (int pass = 0; pass < (paired ? 2 : 1); pass++) {
                boolean nativeMode = paired ? ((trial + pass) & 1) != 0 : mode.equals("rust");
                Result result = run(view, brush, 255, nativeMode, memory && trial >= 0, theme);
                if (trial == -5 && pass == 0) { expectedHash = result.hash; expectedRandom = result.random; }
                else if (expectedHash != result.hash || expectedRandom != result.random) throw new AssertionError("Flatten output or RNG changed");
                if (nativeMode) rust = result; else java = result;
                if (trial >= 0) System.out.printf(Locale.ROOT,
                        "flatten mode=%s theme=%s trial=%d ms=%.3f allocated=%d hash=%d random=%d heap=%d direct=%d peak_pool_sum=%d%n",
                        nativeMode ? "rust" : "java", theme, trial, result.millis, result.allocated, result.hash, result.random, result.heap, result.direct, result.peakPools);
            }
            if (trial >= 0) {
                if (java != null) javaTimes[trial] = java.millis;
                if (rust != null) rustTimes[trial] = rust.millis;
                if (paired) ratios[trial] = java.millis / rust.millis;
            }
        }
        Arrays.sort(javaTimes); Arrays.sort(rustTimes); Arrays.sort(ratios);
        System.out.printf(Locale.ROOT, "flatten_summary java_ms=%.3f rust_ms=%.3f paired_ratio=%.3f memory_instrumented=%s%n",
                javaTimes[trials / 2], rustTimes[trials / 2], ratios[trials / 2], memory);
    }
}
