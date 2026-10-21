package org.pepsoft.worldpainter;

import java.lang.management.ManagementFactory;

public final class HeightBrushBenchmark {
    static void strengths(float[] values, int radius, int stroke) {
        int side = radius * 2 + 1;
        for (int x = 0; x < side; x++) for (int y = 0; y < side; y++) {
            float distance = (float) Math.sqrt((x - radius) * (x - radius) + (y - radius) * (y - radius));
            values[x * side + y] = Math.max(0f, 1f - distance / radius) * (0.25f + stroke % 3 * 0.1f);
        }
    }

    static void scalar(Dimension dimension, int ox, int oy, int side, float[] strengths, int mode, float value) {
        for (int x = 0; x < side; x++) for (int y = 0; y < side; y++) {
            float current = dimension.getHeightAt(ox + x, oy + y), strength = strengths[x * side + y];
            float target = mode == 0 ? Math.min(current + value, dimension.getMaxHeight() - 1)
                    : mode == 1 ? Math.max(current - value, dimension.getMinHeight()) : value;
            if (strength > 0f) {
                float edited = strength * target + (1f - strength) * current;
                if (mode == 2 || ((mode == 0 || mode == 3) ? edited > current : edited < current))
                    dimension.setHeightAt(ox + x, oy + y, edited);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        boolean rust = args.length > 0 && args[0].equals("rust");
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        double[] times = new double[7]; long allocated = 0;
        for (int trial = -5; trial < 7; trial++) {
            Dimension d = ErosionRegionBenchmark.fixture(); float[] forces = new float[255 * 255];
            d.setEventsInhibited(true);
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
            for (int stroke = 0; stroke < 20; stroke++) {
                strengths(forces, 127, stroke); int mode = stroke % 5;
                float value = mode < 2 ? 7.5f : 62.25f;
                if (rust) {
                    boolean ok = HeightBrushAccess.tryApply(d, -63, -63, 255, 255, forces, mode, value,
                            d.getMinHeight(), d.getMaxHeight() - 1);
                    if (!ok) throw new AssertionError("Native height brush unavailable");
                } else scalar(d, -63, -63, 255, forces, mode, value);
            }
            double elapsed = (System.nanoTime() - start) / 1e6;
            allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            d.setEventsInhibited(false); if (trial >= 0) times[trial] = elapsed;
        }
        java.util.Arrays.sort(times);
        System.out.printf("%s height_flatten_20_strokes_ms=%.3f heap_allocated_bytes=%d%n", rust ? "Rust" : "Java", times[3], allocated);
    }
}
