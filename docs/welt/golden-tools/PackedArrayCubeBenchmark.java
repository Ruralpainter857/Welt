package org.pepsoft.util;

import org.pepsoft.worldpainter.nativeapi.Native;

import java.util.Arrays;

/** End-to-end comparison of PackedArrayCube.pack with Java and Rust enabled. */
public final class PackedArrayCubeBenchmark {
    private static volatile long sink;

    private PackedArrayCubeBenchmark() {
    }

    public static void main(String[] args) {
        final int warmupRounds = args.length > 0 ? Integer.parseInt(args[0]) : 5;
        final int measuredRounds = args.length > 1 ? Integer.parseInt(args[1]) : 11;
        final int iterations = args.length > 2 ? Integer.parseInt(args[2]) : 400;
        final int minimumWordSize = args.length > 3 ? Integer.parseInt(args[3]) : 4;
        final boolean straddleLongs = args.length > 4 && Boolean.parseBoolean(args[4]);
        final int paletteSize = args.length > 5 ? Integer.parseInt(args[5]) : 40;
        final PackedArrayCube<String> cube = createCube(minimumWordSize, straddleLongs, paletteSize);

        for (int round = 0; round < warmupRounds; round++) {
            run(cube, false, iterations);
            run(cube, true, iterations);
        }
        final long[] javaTimes = new long[measuredRounds];
        final long[] rustTimes = new long[measuredRounds];
        for (int round = 0; round < measuredRounds; round++) {
            if ((round & 1) == 0) {
                javaTimes[round] = measure(cube, false, iterations);
                rustTimes[round] = measure(cube, true, iterations);
            } else {
                rustTimes[round] = measure(cube, true, iterations);
                javaTimes[round] = measure(cube, false, iterations);
            }
        }
        final double javaMedian = median(javaTimes) / (double) iterations;
        final double rustMedian = median(rustTimes) / (double) iterations;
        System.out.printf("Java path: %.3f us/pack%n", javaMedian / 1_000.0);
        System.out.printf("Rust path: %.3f us/pack%n", rustMedian / 1_000.0);
        System.out.printf("speedup:   %.2fx%n", javaMedian / rustMedian);
        System.out.printf("fixture:   16^3 cells, minimum word size %d, straddle=%s, palette=%d%n",
                minimumWordSize, straddleLongs, paletteSize);
        System.out.println("sink:      " + sink);
    }

    private static PackedArrayCube<String> createCube(int minimumWordSize,
                                                       boolean straddleLongs,
                                                       int paletteSize) {
        final PackedArrayCube<String> cube = new PackedArrayCube<>(16, minimumWordSize,
                straddleLongs, String.class);
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    final int index = x + y * 16 + z * 256;
                    final String value = (index % 211 == 0)
                            ? null : "state-" + ((index * 37 + index / 11) % paletteSize);
                    cube.setValue(x, y, z, value);
                }
            }
        }
        return cube;
    }

    private static long measure(PackedArrayCube<String> cube, boolean nativeEnabled, int iterations) {
        final long start = System.nanoTime();
        run(cube, nativeEnabled, iterations);
        return System.nanoTime() - start;
    }

    private static void run(PackedArrayCube<String> cube, boolean nativeEnabled, int iterations) {
        Native.setExportEnabled(nativeEnabled);
        long sample = 0;
        for (int i = 0; i < iterations; i++) {
            final PackedArrayCube<String>.PackedData packed = cube.pack("minecraft:air");
            sample += packed.data[i % packed.data.length] ^ packed.palette.length;
        }
        sink = sample;
    }

    private static long median(long[] values) {
        final long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
