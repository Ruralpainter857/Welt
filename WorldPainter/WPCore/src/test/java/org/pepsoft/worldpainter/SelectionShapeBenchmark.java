package org.pepsoft.worldpainter;

import com.sun.management.ThreadMXBean;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/** Complete multi-tile add/remove benchmark, including shape predicates, COW, notifications and JNI. */
public final class SelectionShapeBenchmark {
    public static void main(String[] args) {
        final Dimension[] dimensions = {SelectionShapeParityTest.fixture(), SelectionShapeParityTest.fixture()};
        final Shape shape = new Ellipse2D.Double(-47.5, -52.75, 211.2, 219.7);
        final ThreadMXBean allocations = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        allocations.setThreadAllocatedMemoryEnabled(true);
        for (int warmup = 0; warmup < 64; warmup++) {
            run(dimensions[0], shape, false, allocations);
            run(dimensions[1], shape, true, allocations);
        }
        final double[][] times = new double[2][9], bytes = new double[2][9];
        for (int round = 0; round < 9; round++) {
            for (int order = 0; order < 2; order++) {
                final int mode = (round + order) & 1;
                final double[] sample = run(dimensions[mode], shape, mode == 1, allocations);
                times[mode][round] = sample[0];
                bytes[mode][round] = sample[1];
            }
        }
        for (int mode = 0; mode < 2; mode++) {
            Arrays.sort(times[mode]); Arrays.sort(bytes[mode]);
            System.out.printf(Locale.ROOT, "%s median_ms_per_add_remove=%.4f allocated_heap_bytes_per_add_remove=%.0f%n",
                    mode == 0 ? "Java" : "Rust", times[mode][4], bytes[mode][4]);
        }
        System.out.printf(Locale.ROOT, "complete_shape_selection_ratio=%.3fx shared_process_RSS_bytes=%d%n",
                times[0][4] / times[1][4], NativeSlices.currentProcessResidentBytes());
        System.out.println("Native direct scratch: 4192 bytes per worker. RSS is shared; allocation counts exclude native memory.");
    }

    private static double[] run(Dimension dimension, Shape shape, boolean nativeMode, ThreadMXBean memory) {
        final long thread = Thread.currentThread().getId();
        final long allocated = memory.getThreadAllocatedBytes(thread), start = System.nanoTime();
        for (int i = 0; i < 16; i++) {
            SelectionShapeParityTest.edit(dimension, shape, true, nativeMode);
            SelectionShapeParityTest.edit(dimension, shape, false, nativeMode);
        }
        return new double[] {(System.nanoTime() - start) / 16_000_000.0,
                (memory.getThreadAllocatedBytes(thread) - allocated) / 16.0};
    }
}
