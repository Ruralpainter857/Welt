import org.pepsoft.worldpainter.heightMaps.NoiseHeightMap;

import java.util.Arrays;

/**
 * Comparable wall-clock probe for a whole NoiseHeightMap tile in Java and via
 * the production JNI bridge. Run with wp.native.gen enabled and the matching
 * welt_slices library on java.library.path. This is a performance measurement,
 * not a correctness check.
 */
public final class NoiseHeightMapBulkBenchmark {
    private static volatile double sink;

    private NoiseHeightMapBulkBenchmark() {
    }

    public static void main(String[] args) {
        final int tilesPerRound = args.length > 0 ? Integer.parseInt(args[0]) : 64;
        final int octaves = args.length > 1 ? Integer.parseInt(args[1]) : 6;
        final NoiseHeightMap map = new NoiseHeightMap(512.0, 1.25, octaves, 0x1234_5678L);
        map.setSeed(0x3141_5926L);
        System.setProperty("wp.native.gen", "true");
        for (int i = 0; i < 24; i++) {
            sink = sampleJava(map, i);
            sink = sampleNative(map, i);
        }
        final double[] javaTimes = new double[5];
        final double[] nativeTimes = new double[5];
        for (int round = 0; round < javaTimes.length; round++) {
            if ((round & 1) == 0) {
                javaTimes[round] = measure(map, tilesPerRound, round, false);
                nativeTimes[round] = measure(map, tilesPerRound, round, true);
            } else {
                nativeTimes[round] = measure(map, tilesPerRound, round, true);
                javaTimes[round] = measure(map, tilesPerRound, round, false);
            }
        }
        Arrays.sort(javaTimes);
        Arrays.sort(nativeTimes);
        System.out.printf("tiles_per_round=%d octaves=%d java_ms_per_tile=%.4f native_ms_per_tile=%.4f speedup=%.2fx%n",
                tilesPerRound, octaves, javaTimes[2], nativeTimes[2], javaTimes[2] / nativeTimes[2]);
        if (Double.isNaN(sink)) {
            System.out.println("sink=NaN");
        }
    }

    private static double measure(NoiseHeightMap map, int tiles, int round, boolean nativePath) {
        final long start = System.nanoTime();
        double sum = 0.0;
        for (int i = 0; i < tiles; i++) {
            final int index = round * tiles + i + 100;
            sum += nativePath ? sampleNative(map, index) : sampleJava(map, index);
        }
        sink = sum;
        return (System.nanoTime() - start) / 1_000_000.0 / tiles;
    }

    private static double sampleJava(NoiseHeightMap map, int tileIndex) {
        final int originX = (tileIndex - 256) * 128;
        final int originY = 1024;
        double sum = 0.0;
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                sum += map.getHeight(originX + x, originY + y);
            }
        }
        return sum;
    }

    private static double sampleNative(NoiseHeightMap map, int tileIndex) {
        final double[] values = map.getNativeHeights((tileIndex - 256) * 128, 1024, 128, 128);
        if (values == null) {
            throw new IllegalStateException("welt_slices is unavailable; benchmark cannot measure the native path");
        }
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum;
    }
}
