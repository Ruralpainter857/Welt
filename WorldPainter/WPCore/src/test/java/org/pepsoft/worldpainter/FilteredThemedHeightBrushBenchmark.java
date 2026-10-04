package org.pepsoft.worldpainter;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import org.pepsoft.worldpainter.panels.DefaultFilter;

/** Full filtered themed strokes; fixture creation and parity inspection are outside timing. */
public final class FilteredThemedHeightBrushBenchmark {
    @jdk.jfr.Name("welt.FilteredThemedStroke")
    @jdk.jfr.Label("Complete filtered themed strokes")
    private static final class StrokeEvent extends jdk.jfr.Event { }

    private record Result(double millis, long allocated, Dimension dimension, long randomState) { }

    static void scalar(Dimension dimension, int origin, int side, float[] forces,
                       DefaultFilter filter, int mode, float value) {
        for (int x = 0; x < side; x++) {
            for (int y = 0; y < side; y++) {
                int wx = origin + x, wy = origin + y;
                float current = dimension.getHeightAt(wx, wy);
                float target = mode == 0 ? Math.min(current + value, dimension.getMaxHeight() - 1)
                        : mode == 1 ? Math.max(current - value, dimension.getMinHeight()) : value;
                float strength = filter.modifyStrength(wx, wy, forces[x * side + y]);
                if (!(strength > 0.0f)) {
                    continue;
                }
                float edited = strength * target + (1.0f - strength) * current;
                if (mode == 2 || ((mode == 0 || mode == 3) ? edited > current : edited < current)) {
                    dimension.setHeightAt(wx, wy, edited);
                    // The next filter observes the new height, terrain and theme layers.
                    dimension.applyTheme(wx, wy);
                }
            }
        }
    }

    private static Result run(int radius, int selectedMode) throws Exception {
        Dimension dimension = FilteredHeightBrushBenchmark.fixture();
        DefaultFilter filter = FilteredHeightBrushBenchmark.filter(dimension);
        int side = 2 * radius + 1, origin = -radius / 2;
        float[] forces = new float[side * side];
        ThemeResetParityTest.random().setSeed(99);
        long change = dimension.getChangeNo();
        var meter = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        StrokeEvent event = new StrokeEvent();
        event.begin();
        long allocated = meter.getThreadAllocatedBytes(thread), start = System.nanoTime();
        for (int stroke = 0; stroke < 8; stroke++) {
            HeightBrushBenchmark.strengths(forces, radius, stroke);
            int mode = selectedMode < 0 ? stroke % 2 : selectedMode;
            float value = mode < 2 ? 8.0f : stroke % 2 == 0 ? 85.125f : 110.5f;
            dimension.setEventsInhibited(true);
            try {
                scalar(dimension, origin, side, forces, filter, mode, value);
            } finally {
                dimension.setEventsInhibited(false);
            }
        }
        double millis = (System.nanoTime() - start) / 1e6;
        long bytes = meter.getThreadAllocatedBytes(thread) - allocated;
        event.end();
        event.commit();
        if (dimension.getChangeNo() == change) {
            throw new AssertionError("Fixture did not edit heights");
        }
        return new Result(millis, bytes, dimension, ThemeResetParityTest.random().nextLong());
    }

    public static void main(String[] args) throws Exception {
        int radius = Integer.getInteger("welt.benchmark.filteredThemeRadius", 127);
        int warmups = Integer.getInteger("welt.benchmark.filteredThemeWarmups", 20);
        int trials = Integer.getInteger("welt.benchmark.filteredThemeTrials", 9);
        int mode = Integer.getInteger("welt.benchmark.filteredThemeMode", -1);
        if (radius < 1 || radius > 127 || warmups < 0 || trials < 1 || mode < -1 || mode > 4) {
            throw new IllegalArgumentException("Invalid benchmark parameters");
        }
        double[] times = new double[trials];
        long[] bytes = new long[trials];
        for (int i = -warmups; i < trials; i++) {
            Result first = run(radius, mode), repeat = run(radius, mode);
            ThemedFlattenBrushBenchmark.same(first.dimension, repeat.dimension);
            if (first.randomState != repeat.randomState) {
                throw new AssertionError("Theme RNG is not reproducible");
            }
            if (i >= 0) {
                times[i] = first.millis;
                bytes[i] = first.allocated;
            }
        }
        Arrays.sort(times);
        Arrays.sort(bytes);
        System.out.printf(Locale.ROOT,
                "filteredThemedHeight radius=%d mode=%d javaMs=%.3f range=%.3f..%.3f allocated=%d parity=repeatable%n",
                radius, mode, times[trials / 2], times[0], times[trials - 1], bytes[trials / 2]);
    }
}
