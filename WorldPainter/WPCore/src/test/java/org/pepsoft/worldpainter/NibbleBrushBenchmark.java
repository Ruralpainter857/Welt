package org.pepsoft.worldpainter;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.layers.Resources;
import org.pepsoft.worldpainter.nativeapi.Native;

public final class NibbleBrushBenchmark {
    private static final float[] STRENGTHS = new float[255 * 255];
    static {
        for (int y = 0; y < 255; y++) for (int x = 0; x < 255; x++) {
            double radius = Math.sqrt((x - 127) * (x - 127) + (y - 127) * (y - 127));
            STRENGTHS[y * 255 + x] = (float) Math.max(0, 1 - radius / 127);
        }
    }

    static void oracle(Dimension dimension, Layer layer, int ox, int oy, int width, int height, float[] strengths, int mode) {
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int current = dimension.getLayerValueAt(layer, ox + x, oy + y);
            float strength = strengths[y * width + x];
            if (strength == 0f) continue;
            int target = mode == 0 ? 1 + Math.round(strength * 14) : mode == 1
                    ? 14 - Math.round(strength * 14) : 14 - (int) (strength * 14 + 0f);
            if (mode == 0 ? target > current : target < current) dimension.setLayerValueAt(layer, ox + x, oy + y, target);
        }
    }

    static void apply(Dimension dimension) {
        dimension.setEventsInhibited(true);
        try {
            for (int mode : new int[] {0, 2}) for (int y = -512; y < 512; y += 256) for (int x = -512; x < 512; x += 256)
                NibbleBrushAccess.apply(dimension, Resources.INSTANCE, x + 1, y + 1, 255, 255, STRENGTHS, mode);
        } finally { dimension.setEventsInhibited(false); }
    }

    public static void main(String[] args) {
        ThreadMXBean memory = (ThreadMXBean) ManagementFactory.getThreadMXBean(); memory.setThreadAllocatedMemoryEnabled(true);
        int fixed = args.length == 0 ? -1 : args[0].equals("rust") ? 1 : 0;
        for (int i = 0; i < 5; i++) { if (fixed < 0) { run(false, memory); run(true, memory); } else run(fixed == 1, memory); }
        double[][] times = new double[2][7], bytes = new double[2][7];
        for (int round = 0; round < 7; round++) for (int order = 0; order < (fixed < 0 ? 2 : 1); order++) {
            int mode = fixed < 0 ? (round + order) & 1 : fixed;
            double[] sample = run(mode == 1, memory); times[mode][round] = sample[0]; bytes[mode][round] = sample[1];
        }
        for (int mode = 0; mode < 2; mode++) {
            if (fixed >= 0 && fixed != mode) continue;
            Arrays.sort(times[mode]); Arrays.sort(bytes[mode]);
            System.out.printf(Locale.ROOT, "%s complete_32_layer_strokes_ms=%.3f heap_allocated_bytes=%.0f tiles=64%n",
                    mode == 0 ? "Java" : "Rust", times[mode][3], bytes[mode][3]);
        }
    }

    private static double[] run(boolean nativeMode, ThreadMXBean memory) {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode)); Dimension dimension = VerticalResizeBenchmark.fixture();
        long allocated = memory.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime(); apply(dimension);
        return new double[] {(System.nanoTime() - start) / 1_000_000.0, memory.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated};
    }
}
