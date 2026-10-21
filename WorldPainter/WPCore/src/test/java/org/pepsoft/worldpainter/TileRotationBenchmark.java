package org.pepsoft.worldpainter;

import com.sun.management.ThreadMXBean;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/** Standalone, alternating benchmark of complete Tile.transform calls, including JNI copies and output allocation. */
public final class TileRotationBenchmark {
    private static volatile Tile result;

    public static void main(String[] args) {
        if (!NativeLoader.areSlicesAvailable()) throw new IllegalStateException("Release JNI library required");
        final Tile[] inputs = { TileRotationParityTest.fixture(false), TileRotationParityTest.fixture(true),
                new Tile(-3, 7, 0, 256), new Tile(-3, 7, -64, 512) };
        final ThreadMXBean memory = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        memory.setThreadAllocatedMemoryEnabled(true);
        for (int warmup = 0; warmup < 16; warmup++) {
            run(inputs, false, memory);
            run(inputs, true, memory);
        }
        final double[][] times = new double[2][9], bytes = new double[2][9];
        for (int round = 0; round < 9; round++) {
            for (int order = 0; order < 2; order++) {
                final int mode = (round + order) & 1;
                final double[] sample = run(inputs, mode == 1, memory);
                times[mode][round] = sample[0];
                bytes[mode][round] = sample[1];
            }
        }
        for (int mode = 0; mode < 2; mode++) {
            Arrays.sort(times[mode]);
            Arrays.sort(bytes[mode]);
            System.out.printf(Locale.ROOT, "%s median_ms_per_tile=%.4f allocated_heap_bytes_per_tile=%.0f%n",
                    mode == 0 ? "Java" : "Rust", times[mode][4], bytes[mode][4]);
        }
        System.out.printf(Locale.ROOT, "complete_tile_rotation_ratio=%.3fx process_RSS_after_both_modes_bytes=%d%n",
                times[0][4] / times[1][4], NativeSlices.currentProcessResidentBytes());
        System.out.println("RSS is shared by both modes; allocation counts exclude direct buffers and Rust scratch.");
    }

    private static double[] run(Tile[] inputs, boolean nativeMode, ThreadMXBean memory) {
        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
        final long thread = Thread.currentThread().getId();
        final long allocated = memory.getThreadAllocatedBytes(thread);
        final long start = System.nanoTime();
        final int count = 24;
        for (int i = 0; i < count; i++) {
            result = inputs[i % inputs.length].transform(TileRotationParityTest.ROTATIONS[i % 3]);
        }
        final long elapsed = System.nanoTime() - start;
        return new double[] { elapsed / (count * 1_000_000.0),
                (memory.getThreadAllocatedBytes(thread) - allocated) / (double) count };
    }
}
