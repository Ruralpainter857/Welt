package org.pepsoft.worldpainter.operations;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.MaskedPlaneAccess;
import org.pepsoft.worldpainter.brushes.Brush;
import org.pepsoft.worldpainter.brushes.SymmetricBrush;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.painting.TerrainPaint;

/** Complete large terrain strokes with history and notifications; asynchronous display redraw is excluded. */
public final class LargeTerrainStrokeBenchmark {
    static Brush brush(int radius) {
        var brush = SymmetricBrush.LINEAR_CIRCLE.clone();
        brush.setRadius(radius);
        brush.setLevel(.37f);
        return brush;
    }

    static TerrainPaint paint(Brush brush) {
        var paint = new TerrainPaint(Terrain.CUSTOM_96);
        paint.setDither(false);
        paint.setBrush(brush);
        return paint;
    }

    private record Result(double millis, long allocation, long hash, long heap, long direct) { }

    private static Result run(int radius, boolean nativeMode, boolean memory) throws Exception {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
        Dimension dimension = LargeMountainStrokeBenchmark.fixture(radius, false);
        var paint = paint(brush(radius));
        var meter = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        meter.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        if (memory) System.gc();
        long allocation = meter.getThreadAllocatedBytes(thread), start = System.nanoTime();
        long calls = MaskedPlaneAccess.completedSampledTerrainCalls();
        for (int tick = 0; tick < 4; tick++) {
            dimension.armSavePoint();
            dimension.setEventsInhibited(true);
            try { paint.apply(dimension, -3 + tick * 31, 5 - tick * 19, .93f); }
            finally { dimension.setEventsInhibited(false); }
        }
        double millis = (System.nanoTime() - start) / 1e6;
        long completed = MaskedPlaneAccess.completedSampledTerrainCalls() - calls;
        if (nativeMode ? completed < 1 || completed > 4L * dimension.getTiles().size() : completed != 0) {
            throw new AssertionError("Unexpected native terrain tile coverage: " + completed);
        }
        allocation = meter.getThreadAllocatedBytes(thread) - allocation;
        long heap = -1, direct = -1;
        if (memory) {
            System.gc();
            Thread.sleep(100);
            heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                    .filter(pool -> pool.getName().equals("direct")).mapToLong(BufferPoolMXBean::getMemoryUsed).sum();
        }
        long hash = LargeMountainStrokeBenchmark.digest(dimension);
        java.lang.ref.Reference.reachabilityFence(paint);
        java.lang.ref.Reference.reachabilityFence(dimension);
        return new Result(millis, allocation, hash, heap, direct);
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "java" : args[0];
        if (!mode.equals("java") && !mode.equals("rust") && !mode.equals("compare")) throw new IllegalArgumentException("Invalid mode");
        boolean paired = mode.equals("compare"), memory = Boolean.getBoolean("welt.benchmark.largeTerrainMemory");
        if (memory && paired) throw new IllegalArgumentException("Memory runs require a separate single-mode JVM");
        int radius = Integer.getInteger("welt.benchmark.largeTerrainRadius", 383);
        System.setProperty("welt.native.largeTerrainBrush", "true");
        double[] javaTimes = new double[5], nativeTimes = new double[5], ratios = new double[5];
        long expectedHash = 0;
        for (int trial = -4; trial < 5; trial++) {
            Result java = null, rust = null;
            for (int pass = 0; pass < (paired ? 2 : 1); pass++) {
                boolean nativeMode = paired ? ((trial + pass) & 1) != 0 : mode.equals("rust");
                Result result = run(radius, nativeMode, memory && trial >= 0);
                if (trial == -4 && pass == 0) expectedHash = result.hash;
                else if (expectedHash != result.hash) throw new AssertionError("Terrain stroke output changed");
                if (nativeMode) rust = result; else java = result;
                if (trial >= 0) System.out.printf(Locale.ROOT,
                        "terrain mode=%s radius=%d trial=%d ms=%.3f allocated=%d hash=%d heap=%d direct=%d%n",
                        nativeMode ? "rust" : "java", radius, trial, result.millis, result.allocation, result.hash, result.heap, result.direct);
            }
            if (trial >= 0) {
                if (java != null) javaTimes[trial] = java.millis;
                if (rust != null) nativeTimes[trial] = rust.millis;
                if (paired) ratios[trial] = java.millis / rust.millis;
            }
        }
        Arrays.sort(javaTimes); Arrays.sort(nativeTimes); Arrays.sort(ratios);
        System.out.printf(Locale.ROOT, "terrain_summary java_ms=%.3f rust_ms=%.3f paired_ratio=%.3f%n",
                javaTimes[2], nativeTimes[2], ratios[2]);
    }
}
