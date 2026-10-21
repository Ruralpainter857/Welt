package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.nativeapi.Native;

import java.awt.Rectangle;
import java.util.Arrays;

/** End-to-end comparison of Dimension.getDistancesToEdge with Java and Rust enabled. */
public final class EdgeDistanceBenchmark {
    private static volatile long sink;

    private EdgeDistanceBenchmark() {
    }

    public static void main(String[] args) {
        final int warmupRounds = (args.length > 0) ? Integer.parseInt(args[0]) : 6;
        final int measuredRounds = (args.length > 1) ? Integer.parseInt(args[1]) : 15;
        final int iterationsPerRound = (args.length > 2) ? Integer.parseInt(args[2]) : 4;
        final float maxDistance = (args.length > 3) ? Float.parseFloat(args[3]) : 32.0f;
        final int tileColumns = (args.length > 4) ? Integer.parseInt(args[4]) : 2;
        final int tileRows = (args.length > 5) ? Integer.parseInt(args[5]) : 1;
        final Dimension dimension = TestData.createDimension(
                new Rectangle(0, 0, tileColumns * Constants.TILE_SIZE, tileRows * Constants.TILE_SIZE), 62);
        for (int tileX = 0; tileX < tileColumns; tileX++) {
            for (int tileY = 0; tileY < tileRows; tileY++) {
                dimension.getTile(tileX, tileY).setBitLayerValue(Frost.INSTANCE);
            }
        }

        for (int i = 0; i < warmupRounds; i++) {
            run(dimension, false, maxDistance, tileColumns * Constants.TILE_SIZE,
                    tileRows * Constants.TILE_SIZE);
            run(dimension, true, maxDistance, tileColumns * Constants.TILE_SIZE,
                    tileRows * Constants.TILE_SIZE);
        }
        final long[] javaTimes = new long[measuredRounds];
        final long[] rustTimes = new long[measuredRounds];
        for (int round = 0; round < measuredRounds; round++) {
            if ((round & 1) == 0) {
                javaTimes[round] = measure(dimension, false, maxDistance, iterationsPerRound,
                        tileColumns * Constants.TILE_SIZE, tileRows * Constants.TILE_SIZE);
                rustTimes[round] = measure(dimension, true, maxDistance, iterationsPerRound,
                        tileColumns * Constants.TILE_SIZE, tileRows * Constants.TILE_SIZE);
            } else {
                rustTimes[round] = measure(dimension, true, maxDistance, iterationsPerRound,
                        tileColumns * Constants.TILE_SIZE, tileRows * Constants.TILE_SIZE);
                javaTimes[round] = measure(dimension, false, maxDistance, iterationsPerRound,
                        tileColumns * Constants.TILE_SIZE, tileRows * Constants.TILE_SIZE);
            }
        }
        final double javaMedian = median(javaTimes) / (double) iterationsPerRound;
        final double rustMedian = median(rustTimes) / (double) iterationsPerRound;
        System.out.printf("Java path: %.3f ms/bake%n", javaMedian / 1_000_000.0);
        System.out.printf("Rust path: %.3f ms/bake%n", rustMedian / 1_000_000.0);
        System.out.printf("speedup:   %.2fx%n", javaMedian / rustMedian);
        System.out.println("sink:      " + sink);
    }

    private static long measure(Dimension dimension, boolean nativeEnabled,
                                float maxDistance, int iterations, int width, int height) {
        final long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            run(dimension, nativeEnabled, maxDistance, width, height);
        }
        return System.nanoTime() - start;
    }

    private static void run(Dimension dimension, boolean nativeEnabled, float maxDistance,
                            int width, int height) {
        Native.setExportEnabled(nativeEnabled);
        final HeightMap distances = dimension.getDistancesToEdge(Frost.INSTANCE, maxDistance);
        long sample = 0;
        for (int y = 0; y < height; y += 31) {
            for (int x = 0; x < width; x += 37) {
                sample += Double.doubleToRawLongBits(distances.getHeight(x, y));
            }
        }
        sink = sample;
    }

    private static long median(long[] values) {
        final long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
