package org.pepsoft.worldpainter;

import java.lang.management.ManagementFactory;

public final class SmoothRegionBenchmark {
    static final class Scratch {
        final float[][] totals, heights; final int[][] counts;
        Scratch(int side) { totals = new float[side][side]; heights = new float[side][side]; counts = new int[side][side]; }
    }
    static void legacy(Dimension dimension, int ox, int oy, int side, float[] strengths, Scratch scratch) {
        int input = side + 10;
        for (int x = 0; x < input; x++) {
            java.util.Arrays.fill(scratch.totals[x], 0f); java.util.Arrays.fill(scratch.heights[x], 0f); java.util.Arrays.fill(scratch.counts[x], 0);
        }
        for (int x = 0; x < input; x++) {
            for (int y = 0; y < input; y++) {
                float current = dimension.getHeightAt(ox + x - 5, oy + y - 5);
                if (current == -Float.MAX_VALUE) continue;
                scratch.heights[x][y] = current;
                for (int dx = Math.max(x - 5, 0); dx <= Math.min(x + 5, input - 1); dx++)
                    for (int dy = Math.max(y - 5, 0); dy <= Math.min(y + 5, input - 1); dy++) {
                        scratch.totals[dx][dy] += current; scratch.counts[dx][dy]++;
                    }
            }
            if (x >= 10) for (int y = 5; y < side + 5; y++) {
                float strength = strengths[(x - 10) * side + y - 5];
                if (strength > 0f) dimension.setHeightAt(ox + x - 10, oy + y - 5,
                        strength * (scratch.totals[x - 5][y] / scratch.counts[x - 5][y])
                                + (1f - strength) * scratch.heights[x - 5][y]);
            }
        }
    }
    public static void main(String[] args) throws Exception {
        boolean rust = args.length > 0 && args[0].equals("rust");
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        double[] times = new double[7]; long allocated = 0;
        for (int trial = -5; trial < 7; trial++) {
            Dimension d = ErosionRegionBenchmark.fixture(); float[] forces = new float[127 * 127]; Scratch scratch = rust ? null : new Scratch(137);
            d.setEventsInhibited(true);
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
            for (int stroke = 0; stroke < 8; stroke++) {
                HeightBrushBenchmark.strengths(forces, 63, stroke);
                if (rust) {
                    boolean ok = SmoothHeightAccess.tryApply(d, -1, -1, 127, 127, forces);
                    if (!ok) throw new AssertionError("Native smooth unavailable");
                } else legacy(d, -1, -1, 127, forces, scratch);
            }
            double elapsed = (System.nanoTime() - start) / 1e6;
            allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            d.setEventsInhibited(false); if (trial >= 0) times[trial] = elapsed;
        }
        java.util.Arrays.sort(times);
        System.out.printf("%s full_8_smooth_strokes_ms=%.3f heap_allocated_bytes=%d%n", rust ? "Rust" : "Java", times[3], allocated);
    }
}
