package org.pepsoft.worldpainter;

import com.sun.management.ThreadMXBean;
import org.pepsoft.worldpainter.layers.Resources;
import org.pepsoft.worldpainter.nativeapi.Native;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

public final class LayerEditBenchmark {
    static Dimension fixture() {
        Dimension dimension = VerticalResizeBenchmark.fixture();
        for (Tile tile : dimension.getTiles()) {
            tile.inhibitEvents();
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++)
                tile.setLayerValue(Resources.INSTANCE, x, y, (x * 3 + y * 7) & 15);
            tile.releaseEvents();
        }
        return dimension;
    }

    static void apply(Dimension dimension) throws Exception {
        apply(dimension, false);
    }

    static void apply(Dimension dimension, boolean fill) throws Exception {
        dimension.visitTilesForEditing().andDo(tile -> {
            if (fill) tile.raiseLayerTo(Resources.INSTANCE, 12);
            else tile.invertLayer(Resources.INSTANCE);
        }, null);
    }

    public static void main(String[] args) throws Exception {
        ThreadMXBean memory = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        memory.setThreadAllocatedMemoryEnabled(true);
        boolean javaOnly = Arrays.asList(args).contains("java"), rustOnly = Arrays.asList(args).contains("rust");
        boolean isolated = javaOnly || rustOnly, fill = Arrays.asList(args).contains("fill");
        for (int i = 0; i < 5; i++) { if (!rustOnly) run(false, memory, fill); if (!javaOnly) run(true, memory, fill); }
        double[][] times = new double[2][7], bytes = new double[2][7];
        for (int round = 0; round < 7; round++) for (int order = 0; order < (isolated ? 1 : 2); order++) {
            int mode = javaOnly ? 0 : rustOnly ? 1 : (round + order) & 1;
            double[] sample = run(mode == 1, memory, fill); times[mode][round] = sample[0]; bytes[mode][round] = sample[1];
        }
        for (int mode = rustOnly ? 1 : 0; mode < (javaOnly ? 1 : 2); mode++) {
            Arrays.sort(times[mode]); Arrays.sort(bytes[mode]);
            System.out.printf(Locale.ROOT, "%s full_layer_%s_ms=%.3f heap_allocated_bytes=%.0f tiles=64%n",
                    mode == 0 ? "Java" : "Rust", fill ? "fill" : "invert", times[mode][3], bytes[mode][3]);
        }
        System.out.printf(Locale.ROOT, "process_RSS_bytes=%d%n",
                org.pepsoft.worldpainter.nativeapi.NativeSlices.currentProcessResidentBytes());
        System.out.println(isolated ? "RSS covers one benchmark process, not peak memory."
                : "RSS is shared by both modes, not peak memory.");
    }

    private static double[] run(boolean nativeMode, ThreadMXBean memory, boolean fill) throws Exception {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
        Dimension dimension = fixture();
        long allocated = memory.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
        apply(dimension, fill);
        return new double[] {(System.nanoTime() - start) / 1_000_000.0,
                memory.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated};
    }
}
