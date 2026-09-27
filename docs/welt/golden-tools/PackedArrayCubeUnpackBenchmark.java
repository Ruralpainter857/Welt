package org.pepsoft.util;

import org.pepsoft.worldpainter.nativeapi.Native;

import java.util.Arrays;

/** End-to-end comparison of PackedArrayCube's Java and Rust decode paths. */
public final class PackedArrayCubeUnpackBenchmark {
    private static volatile int sink;

    private PackedArrayCubeUnpackBenchmark() {
    }

    public static void main(String[] args) {
        final int warmupRounds = args.length > 0 ? Integer.parseInt(args[0]) : 5;
        final int measuredRounds = args.length > 1 ? Integer.parseInt(args[1]) : 11;
        final int iterations = args.length > 2 ? Integer.parseInt(args[2]) : 300;
        final int minimumWordSize = args.length > 3 ? Integer.parseInt(args[3]) : 1;
        final boolean straddleLongs = args.length > 4 && Boolean.parseBoolean(args[4]);
        final int paletteSize = args.length > 5 ? Integer.parseInt(args[5]) : 31;
        final PackedArrayCube<String>.PackedData packed = createFixture(
                minimumWordSize, straddleLongs, paletteSize).pack();

        for (int round = 0; round < warmupRounds; round++) {
            run(packed, minimumWordSize, straddleLongs, false, iterations);
            run(packed, minimumWordSize, straddleLongs, true, iterations);
        }
        final long[] javaTimes = new long[measuredRounds];
        final long[] rustTimes = new long[measuredRounds];
        for (int round = 0; round < measuredRounds; round++) {
            if ((round & 1) == 0) {
                javaTimes[round] = measure(packed, minimumWordSize, straddleLongs, false, iterations);
                rustTimes[round] = measure(packed, minimumWordSize, straddleLongs, true, iterations);
            } else {
                rustTimes[round] = measure(packed, minimumWordSize, straddleLongs, true, iterations);
                javaTimes[round] = measure(packed, minimumWordSize, straddleLongs, false, iterations);
            }
        }
        final double javaMedian = median(javaTimes) / (double) iterations;
        final double rustMedian = median(rustTimes) / (double) iterations;
        System.out.printf("Java path: %.3f us/unpack%n", javaMedian / 1_000.0);
        System.out.printf("Rust path: %.3f us/unpack%n", rustMedian / 1_000.0);
        System.out.printf("speedup:   %.2fx%n", javaMedian / rustMedian);
        System.out.printf("fixture:   16^3 cells, minimum word size %d, straddle=%s, palette=%d%n",
                minimumWordSize, straddleLongs, packed.palette.length);
        System.out.println("sink:      " + sink);
    }

    private static PackedArrayCube<String> createFixture(int minimumWordSize,
                                                         boolean straddleLongs,
                                                         int paletteSize) {
        final PackedArrayCube<String> cube = new PackedArrayCube<>(16, minimumWordSize,
                straddleLongs, String.class);
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    final int index = x + y * 16 + z * 256;
                    cube.setValue(x, y, z, "state-" + ((index * 37 + index / 11) % paletteSize));
                }
            }
        }
        Native.setExportEnabled(false);
        return cube;
    }

    private static long measure(PackedArrayCube<String>.PackedData packed,
                                int minimumWordSize, boolean straddleLongs,
                                boolean nativeEnabled, int iterations) {
        final long start = System.nanoTime();
        run(packed, minimumWordSize, straddleLongs, nativeEnabled, iterations);
        return System.nanoTime() - start;
    }

    private static void run(PackedArrayCube<String>.PackedData packed,
                            int minimumWordSize, boolean straddleLongs,
                            boolean nativeEnabled, int iterations) {
        Native.setExportEnabled(nativeEnabled);
        int sample = 0;
        for (int i = 0; i < iterations; i++) {
            final PackedArrayCube<String> decoded = new PackedArrayCube<>(16, packed.data,
                    packed.palette, minimumWordSize, straddleLongs, String.class);
            sample += decoded.getValue(i & 15, (i >>> 4) & 15, (i >>> 8) & 15).hashCode();
        }
        sink = sample;
    }

    private static long median(long[] values) {
        final long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
