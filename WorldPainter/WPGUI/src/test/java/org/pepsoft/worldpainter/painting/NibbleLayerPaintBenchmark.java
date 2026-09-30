package org.pepsoft.worldpainter.painting;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.layers.Resources;
import org.pepsoft.worldpainter.nativeapi.Native;

public final class NibbleLayerPaintBenchmark {
    public static void main(String[] args) {
        ThreadMXBean memory = (ThreadMXBean) ManagementFactory.getThreadMXBean(); memory.setThreadAllocatedMemoryEnabled(true);
        int fixed = args.length == 0 ? -1 : args[0].equals("rust") ? 1 : 0;
        for (int i = 0; i < 5; i++) { if (fixed < 0) { run(false, memory); run(true, memory); } else run(fixed == 1, memory); }
        double[][] times = new double[2][7], bytes = new double[2][7];
        for (int round = 0; round < 7; round++) for (int order = 0; order < (fixed < 0 ? 2 : 1); order++) {
            int mode = fixed < 0 ? (round + order) & 1 : fixed; double[] sample = run(mode == 1, memory);
            times[mode][round] = sample[0]; bytes[mode][round] = sample[1];
        }
        for (int mode = 0; mode < 2; mode++) {
            if (fixed >= 0 && fixed != mode) continue;
            Arrays.sort(times[mode]); Arrays.sort(bytes[mode]);
            System.out.printf(Locale.ROOT, "%s complete_painter_32_strokes_ms=%.3f heap_allocated_bytes=%.0f tiles=15%n",
                    mode == 0 ? "Java" : "Rust", times[mode][3], bytes[mode][3]);
        }
    }

    private static double[] run(boolean nativeMode, ThreadMXBean memory) {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
        Dimension dimension = NibbleLayerPaintParityTest.fixture(); NibbleLayerPaint painter = new NibbleLayerPaint(Resources.INSTANCE);
        NibbleLayerPaintParityTest.Brush brush = new NibbleLayerPaintParityTest.Brush();
        brush.setRadius(127); brush.setLevel(0.63f); painter.setBrush(brush);
        long allocated = memory.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
        dimension.setEventsInhibited(true);
        try {
            for (int y = -192; y <= 192; y += 128) for (int x = -192; x <= 192; x += 128) painter.apply(dimension, x, y, 0.73f);
            for (int y = -192; y <= 192; y += 128) for (int x = -192; x <= 192; x += 128) painter.remove(dimension, x, y, 0.73f);
        } finally { dimension.setEventsInhibited(false); }
        return new double[] {(System.nanoTime() - start) / 1_000_000.0, memory.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated};
    }
}
