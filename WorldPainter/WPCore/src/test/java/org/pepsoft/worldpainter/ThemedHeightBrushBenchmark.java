package org.pepsoft.worldpainter;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/** Complete strokes include force preparation, height edits, themes and notifications. */
public final class ThemedHeightBrushBenchmark {
    static Dimension fixture() {
        Dimension d = ErosionRegionBenchmark.fixture();
        ((HeightMapTileFactory)d.getTileFactory()).setTheme(ThemeResetParityTest.theme(true, true));
        return d;
    }
    static void scalar(Dimension d, int ox, int oy, int side, float[] forces, boolean inverse, float value) {
        for (int x=0;x<side;x++) for(int y=0;y<side;y++) {
            float strength=forces[x*side+y];
            if (!(strength>0f)) continue;
            int wx=ox+x,wy=oy+y;float current=d.getHeightAt(wx,wy);
            float target=inverse?Math.max(current-value,d.getMinHeight()):Math.min(current+value,d.getMaxHeight()-1);
            float edited=strength*target+(1f-strength)*current;
            if(inverse?edited<current:edited>current){d.setHeightAt(wx,wy,edited);d.applyTheme(wx,wy);}
        }
    }
    private record Result(double millis, long allocated, Dimension dimension, long randomState) { }

    private static Result run(boolean rust, int radius) throws Exception {
        int side = 2 * radius + 1, origin = -radius / 2;
        Dimension dimension = fixture();
        float[] forces = new float[side * side];
        ThemeResetParityTest.random().setSeed(99);
        var meter = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        long calls = HeightBrushAccess.completedThemedCalls();
        long allocated = meter.getThreadAllocatedBytes(thread), start = System.nanoTime();
        for (int stroke = 0; stroke < 8; stroke++) {
            HeightBrushBenchmark.strengths(forces, radius, stroke);
            dimension.setEventsInhibited(true);
            try {
                if (rust) {
                    if (!HeightBrushAccess.tryApplyThemed(dimension, origin, origin, side, side,
                            forces, stroke % 2, 7.5f, dimension.getMinHeight(), dimension.getMaxHeight() - 1)) {
                        throw new AssertionError("Native themed brush unavailable");
                    }
                } else {
                    scalar(dimension, origin, origin, side, forces, stroke % 2 != 0, 7.5f);
                }
            } finally {
                dimension.setEventsInhibited(false);
            }
        }
        double millis = (System.nanoTime() - start) / 1e6;
        long bytes = meter.getThreadAllocatedBytes(thread) - allocated;
        if (HeightBrushAccess.completedThemedCalls() - calls != (rust ? 8 : 0)) {
            throw new AssertionError("Unexpected grouped transaction count");
        }
        return new Result(millis, bytes, dimension, ThemeResetParityTest.random().nextLong());
    }

    public static void main(String[] args) throws Exception {
        String selected = args.length == 0 ? "java" : args[0];
        int radius = args.length > 1 ? Integer.parseInt(args[1]) : 127;
        int warmups = Integer.getInteger("welt.benchmark.themedHeightWarmups", 20);
        int trials = Integer.getInteger("welt.benchmark.themedHeightTrials", 9);
        if ((!selected.equals("java") && !selected.equals("rust") && !selected.equals("compare"))
                || radius < 64 || radius > 127 || warmups < 0 || trials < 1) {
            throw new IllegalArgumentException("Invalid benchmark parameters");
        }
        boolean paired = selected.equals("compare");
        double[] times = new double[trials], nativeTimes = new double[trials], ratios = new double[trials];
        long[] bytes = new long[trials], nativeBytes = new long[trials];
        for (int trial = -warmups; trial < trials; trial++) {
            if (paired) {
                Result java = null, rust = null;
                for (int pass = 0; pass < 2; pass++) {
                    if (((trial + pass) & 1) == 0) java = run(false, radius);
                    else rust = run(true, radius);
                }
                ThemedFlattenBrushBenchmark.same(java.dimension, rust.dimension);
                if (java.randomState != rust.randomState) throw new AssertionError("Theme RNG differs");
                if (trial >= 0) {
                    times[trial] = java.millis; nativeTimes[trial] = rust.millis;
                    ratios[trial] = java.millis / rust.millis;
                    bytes[trial] = java.allocated; nativeBytes[trial] = rust.allocated;
                }
            } else {
                Result result = run(selected.equals("rust"), radius);
                if (trial >= 0) { times[trial] = result.millis; bytes[trial] = result.allocated; }
            }
        }
        Arrays.sort(times); Arrays.sort(nativeTimes); Arrays.sort(ratios);
        Arrays.sort(bytes); Arrays.sort(nativeBytes);
        int middle = trials / 2;
        if (paired) System.out.printf(Locale.ROOT,
                "themedHeight radius=%d javaMs=%.3f rustMs=%.3f ratio=%.3f range=%.3f..%.3f allocated=%d rustAllocated=%d parity=exact%n",
                radius, times[middle], nativeTimes[middle], ratios[middle], ratios[0], ratios[trials - 1],
                bytes[middle], nativeBytes[middle]);
        else System.out.printf(Locale.ROOT, "themedHeight radius=%d mode=%s medianMs=%.3f allocated=%d%n",
                radius, selected, times[middle], bytes[middle]);
    }
}
