package org.pepsoft.worldpainter;

import com.sun.management.ThreadMXBean;
import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.nativeapi.Native;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

public final class FluidBrushBenchmark {
    private static final float[] STRENGTHS = new float[255 * 255];
    static {
        for (int x = 0; x < 255; x++) for (int y = 0; y < 255; y++)
            if ((x - 127) * (x - 127) + (y - 127) * (y - 127) < 127 * 127) STRENGTHS[x * 255 + y] = 1;
    }

    static void oracle(Dimension dimension, int ox, int oy, int width, int height, float[] strengths, boolean reset, int level) {
        if (reset && level == -1) return;
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) if (strengths[x * height + y] != 0f) {
            dimension.setWaterLevelAt(ox + x, oy + y, reset ? level : dimension.getMinHeight());
            if (reset) dimension.setBitLayerValueAt(FloodWithLava.INSTANCE, ox + x, oy + y, false);
        }
    }

    static void apply(Dimension dimension) {
        dimension.setEventsInhibited(true);
        try {
            for (int x = -512; x < 512; x += 256) for (int y = -512; y < 512; y += 256)
                FluidBrushAccess.apply(dimension, x + 1, y + 1, 255, 255, STRENGTHS, true, 62);
        } finally { dimension.setEventsInhibited(false); }
    }

    public static void main(String[] args) {
        ThreadMXBean memory = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        memory.setThreadAllocatedMemoryEnabled(true);
        int fixed = args.length == 0 ? -1 : args[0].equals("rust") ? 1 : 0;
        for (int i = 0; i < 5; i++) {
            if (fixed < 0) { run(false, memory); run(true, memory); }
            else run(fixed == 1, memory);
        }
        double[][] times = new double[2][7], bytes = new double[2][7];
        for (int round = 0; round < 7; round++) for (int order = 0; order < (fixed < 0 ? 2 : 1); order++) {
            int mode = fixed < 0 ? (round + order) & 1 : fixed;
            double[] sample = run(mode == 1, memory); times[mode][round] = sample[0]; bytes[mode][round] = sample[1];
        }
        for (int mode = 0; mode < 2; mode++) {
            if (fixed >= 0 && mode != fixed) continue;
            Arrays.sort(times[mode]); Arrays.sort(bytes[mode]);
            System.out.printf(Locale.ROOT, "%s complete_16_fluid_strokes_ms=%.3f heap_allocated_bytes=%.0f tiles=64%n",
                    mode == 0 ? "Java" : "Rust", times[mode][3], bytes[mode][3]);
        }
    }

    private static double[] run(boolean nativeMode, ThreadMXBean memory) {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
        Dimension dimension = VerticalResizeBenchmark.fixture();
        long allocated = memory.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
        apply(dimension);
        return new double[] {(System.nanoTime() - start) / 1_000_000.0,
                memory.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated};
    }
}
