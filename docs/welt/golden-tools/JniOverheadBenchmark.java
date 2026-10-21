package org.pepsoft.worldpainter.nativeapi;

/** Minimal warmed-up comparison of a Java validation call and the equivalent JNI call. */
public final class JniOverheadBenchmark {
    private static volatile long sink;

    private JniOverheadBenchmark() {
    }

    public static void main(String[] args) {
        final int iterations = args.length == 0 ? 2_000_000 : Integer.parseInt(args[0]);
        final int tilePixels = 128 * 128;
        for (int i = 0; i < 200_000; i++) {
            sink += javaCall(i, tilePixels);
            sink += nativeCall(i, tilePixels);
        }
        final double[] javaTimes = new double[5];
        final double[] jniTimes = new double[5];
        for (int round = 0; round < javaTimes.length; round++) {
            if ((round & 1) == 0) {
                javaTimes[round] = measureJava(iterations, tilePixels);
                jniTimes[round] = measureJni(iterations, tilePixels);
            } else {
                jniTimes[round] = measureJni(iterations, tilePixels);
                javaTimes[round] = measureJava(iterations, tilePixels);
            }
        }
        java.util.Arrays.sort(javaTimes);
        java.util.Arrays.sort(jniTimes);
        final double javaMedian = javaTimes[2];
        final double jniMedian = jniTimes[2];
        System.out.printf("rounds=%d iterations_per_round=%d%njava_ns_per_call=%.2f%njni_ns_per_call=%.2f%noverhead_ns=%.2f%n",
                javaTimes.length, iterations, javaMedian, jniMedian, jniMedian - javaMedian);
        if (sink == Long.MIN_VALUE) {
            throw new AssertionError("unreachable");
        }
    }

    private static double measureJava(int iterations, int tilePixels) {
        final long start = System.nanoTime();
        long result = 0;
        for (int i = 0; i < iterations; i++) {
            result += javaCall(i, tilePixels);
        }
        sink = result;
        return (System.nanoTime() - start) / (double) iterations;
    }

    private static double measureJni(int iterations, int tilePixels) {
        final long start = System.nanoTime();
        long result = 0;
        for (int i = 0; i < iterations; i++) {
            result += nativeCall(i, tilePixels);
        }
        sink = result;
        return (System.nanoTime() - start) / (double) iterations;
    }

    private static int javaCheck(int heights, int terrain, int water) {
        return valid(heights) && valid(terrain) && valid(water) ? 0 : 2;
    }

    private static int javaCall(int index, int tilePixels) {
        final int pattern = index & 3;
        final int heights = (pattern & 1) == 0 ? tilePixels : tilePixels + 1;
        final int terrain = (pattern & 2) == 0 ? 2 * tilePixels : 0;
        final int water = pattern == 3 ? -1 : tilePixels;
        return javaCheck(heights, terrain, water);
    }

    private static int nativeCall(int index, int tilePixels) {
        final int pattern = index & 3;
        final int heights = (pattern & 1) == 0 ? tilePixels : tilePixels + 1;
        final int terrain = (pattern & 2) == 0 ? 2 * tilePixels : 0;
        final int water = pattern == 3 ? -1 : tilePixels;
        return WpNative.nativeTileViewCheck(heights, terrain, water);
    }

    private static boolean valid(int length) {
        return length >= 0 && length % (128 * 128) == 0;
    }
}
