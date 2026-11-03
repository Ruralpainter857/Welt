package org.pepsoft.worldpainter.importing;

import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.heightMaps.CachedImageHeightMap;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.Arrays;
import java.util.Locale;

/**
 * Speed of the complete operation of the mask import dialog, dense against bounded, in one warmed up JVM: open, analyse,
 * import into a dimension with existing tiles and close, timed together (the dimension, which exists before the dialog
 * opens, is set up outside the timed part); validated outside the timed part on every iteration (one native transaction
 * per tile, digest identical to the first iteration, capture closed). Warm-up iterations of both modes are excluded;
 * measured rounds alternate the order. Exits with status 1 if any validation fails.
 *
 * <p>Arguments: {@code file warmups rounds}.
 */
public final class BoundedMaskImportSpeed {
    public static void main(String[] args) throws Exception {
        final File file = new File(args[0]);
        final int warmups = Integer.parseInt(args[1]), rounds = Integer.parseInt(args[2]);
        if (! NativeLoader.areSlicesAvailable()) {
            System.out.println("FAILED: native library not available");
            System.exit(1);
        }
        System.setProperty(Native.GEN_KEY, "true");
        final BoundedMaskImportSpeed speed = new BoundedMaskImportSpeed(file);
        for (int i = 0; i < warmups; i++) {
            speed.iteration(false);
            speed.iteration(true);
        }
        final double[] dense = new double[rounds], bounded = new double[rounds], ratios = new double[rounds];
        for (int round = 0; round < rounds; round++) {
            final boolean boundedFirst = (round % 2) == 1;
            final double first = speed.iteration(boundedFirst), second = speed.iteration(! boundedFirst);
            dense[round] = boundedFirst ? second : first;
            bounded[round] = boundedFirst ? first : second;
            ratios[round] = bounded[round] / dense[round];
            System.out.printf(Locale.ROOT, "round=%d order=%s dense_ms=%.1f bounded_ms=%.1f ratio=%.3f%n", round, boundedFirst ? "B/A" : "A/B",
                    dense[round], bounded[round], ratios[round]);
        }
        System.out.printf(Locale.ROOT, "summary file=%s warmups=%d rounds=%d median_dense_ms=%.1f median_bounded_ms=%.1f median_ratio=%.3f min_ratio=%.3f max_ratio=%.3f"
                        + " native_calls=%d result=%d failures=%d%n", file.getName(), warmups, rounds, median(dense), median(bounded), median(ratios),
                Arrays.stream(ratios).min().orElse(0), Arrays.stream(ratios).max().orElse(0), speed.calls, speed.result, speed.failures);
        System.exit((speed.failures == 0) ? 0 : 1);
    }

    private BoundedMaskImportSpeed(File file) {
        this.file = file;
    }

    /** One complete operation; returns its wall time in milliseconds, validated afterwards. */
    private double iteration(boolean boundedMode) throws Exception {
        final Dimension dimension = BoundedMaskImportBenchmark.fixture(file);
        System.gc();
        final long start = System.nanoTime();
        final CachedImageHeightMap capture = boundedMode ? CachedImageHeightMap.capture(file, file.getName()) : null;
        final int importCalls;
        try {
            final MaskImporter importer = boundedMode ? MaskImporter.bounded(dimension, file, capture) : new MaskImporter(dimension, file, ImageIO.read(file));
            MaskImportBenchmark.configure(importer, "continuous", 1, true);
            importer.doImport(null);
            importCalls = importer.getLastNativeImportCalls();
        } finally {
            if (capture != null) {
                capture.close();
            }
        }
        final double elapsed = (System.nanoTime() - start) / 1e6;
        // Validation, not timed
        final long digest = BoundedMaskImportBenchmark.digest(dimension);
        if (calls == Integer.MIN_VALUE) {
            calls = importCalls;
            result = digest;
        }
        if ((importCalls != dimension.getTileCount()) || (importCalls != calls) || (digest != result) || ((capture != null) && (! capture.isClosed()))) {
            failures++;
            System.out.printf(Locale.ROOT, "FAILED validation: bounded=%b native_calls=%d tiles=%d result=%d%n", boundedMode, importCalls, dimension.getTileCount(), digest);
        }
        return elapsed;
    }

    private static double median(double[] values) {
        final double[] sorted = values.clone();
        Arrays.sort(sorted);
        return (sorted.length % 2 == 1) ? sorted[sorted.length / 2] : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
    }

    private final File file;
    private int calls = Integer.MIN_VALUE, failures;
    private long result;
}
