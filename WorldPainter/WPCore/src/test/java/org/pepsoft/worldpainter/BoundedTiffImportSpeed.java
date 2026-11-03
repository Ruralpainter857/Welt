package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.heightMaps.BitmapHeightMap;
import org.pepsoft.worldpainter.heightMaps.CachedImageHeightMap;
import org.pepsoft.worldpainter.importing.HeightMapImporter;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.util.Arrays;
import java.util.Locale;

import static org.pepsoft.worldpainter.Constants.TILE_SIZE;

/**
 * Speed of the complete operation of the height map import dialog, dense against bounded, in one warmed up JVM: open,
 * range, preview tiles, import into a new dimension and close, timed together; the results are validated outside the
 * timed part on every iteration (native tile imports, checksums of the dimension and of the preview identical to the
 * first iteration). Warm-up iterations of both modes are excluded; measured rounds alternate the order (dense then
 * bounded, then bounded then dense). Exits with status 1 if any validation fails.
 *
 * <p>Arguments: {@code file warmups rounds previewTiles}.
 */
public final class BoundedTiffImportSpeed {
    public static void main(String[] args) throws Exception {
        final File file = new File(args[0]);
        final int warmups = Integer.parseInt(args[1]), rounds = Integer.parseInt(args[2]), previewTiles = Integer.parseInt(args[3]);
        final Configuration configuration = new Configuration();
        configuration.setDefaultPlatform(DefaultPlugin.JAVA_ANVIL_1_19);
        Configuration.setInstance(configuration);
        if (! NativeLoader.areSlicesAvailable()) {
            System.out.println("FAILED: native library not available");
            System.exit(1);
        }
        System.setProperty(Native.GEN_KEY, "true");
        final BoundedTiffImportSpeed speed = new BoundedTiffImportSpeed(file, previewTiles);
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
                        + " native_calls=%d result=%d preview=%d failures=%d%n", file.getName(), warmups, rounds, median(dense), median(bounded), median(ratios),
                Arrays.stream(ratios).min().orElse(0), Arrays.stream(ratios).max().orElse(0), speed.nativeCalls, speed.result, speed.preview, speed.failures);
        System.exit((speed.failures == 0) ? 0 : 1);
    }

    private BoundedTiffImportSpeed(File file, int previewTiles) {
        this.file = file;
        previewImages = new BufferedImage[previewTiles];
        for (int i = 0; i < previewTiles; i++) {
            previewImages[i] = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        }
    }

    /** One complete operation; returns its wall time in milliseconds, validated afterwards. */
    private double iteration(boolean boundedMode) throws Exception {
        System.gc();
        final long start = System.nanoTime();
        final HeightMap map;
        final CachedImageHeightMap cached = boundedMode ? CachedImageHeightMap.capture(file, file.getName()) : null;
        if (boundedMode) {
            if (cached == null) {
                throw new IllegalStateException("not captured");
            }
            map = cached;
        } else {
            map = BitmapHeightMap.build().withName(file.getName()).withImage(ImageIO.read(file)).withFile(file).now();
        }
        final HeightMapImportBenchmark.State state;
        final int calls;
        try {
            final double[] range = map.getRange();
            state = HeightMapImportBenchmark.fixture(map, "fresh", false);
            final HeightMapImporter importer = state.importer();
            importer.setImageLowLevel(range[0]);
            importer.setImageHighLevel((range[1] > range[0]) ? range[1] : range[0] + 1);
            final HeightMapTileFactory importFactory = (HeightMapTileFactory) state.dimension().getTileFactory();
            final HeightMapTileFactory previewFactory = BoundedTiffImportParityTest.previewTileFactory();
            importer.setTileFactory(previewFactory);
            importer.setTheme(previewFactory.getTheme());
            final org.pepsoft.util.swing.TileProvider preview = importer.getPreviewProvider(null, ColourScheme.DEFAULT, true, 10, TileRenderer.LightOrigin.NORTHWEST);
            final int side = (int) Math.ceil(Math.sqrt(previewImages.length));
            final int centreX = map.getExtent().width / TILE_SIZE / 2 - side / 2, centreY = map.getExtent().height / TILE_SIZE / 2 - side / 2;
            HeightMapImportBenchmark.random().setSeed(7);
            boolean drawn = true;
            for (int i = 0; i < previewImages.length; i++) {
                drawn &= preview.paintTile(previewImages[i], centreX + i % side, centreY + i / side, 0, 0);
            }
            importer.setTileFactory(importFactory);
            importer.setTheme(importFactory.getTheme());
            HeightMapImportBenchmark.random().setSeed(42);
            state.run();
            calls = drawn ? importer.getLastNativeImportCalls() : -1;
        } finally {
            if (cached != null) {
                cached.close();
            }
        }
        final double elapsed = (System.nanoTime() - start) / 1e6;
        // Validation, not timed
        long hash = 17;
        for (Tile tile: state.dimension().getTiles()) {
            for (int y = 0; y < TILE_SIZE; y++) {
                for (int x = 0; x < TILE_SIZE; x++) {
                    hash = hash * 31 + Float.floatToIntBits(tile.getHeight(x, y)) + 7L * tile.getTerrain(x, y).ordinal() + 13L * tile.getWaterLevel(x, y);
                }
            }
        }
        hash += state.dimension().getTileCount();
        long previewHash = 17;
        for (BufferedImage image: previewImages) {
            previewHash = previewHash * 31 + Arrays.hashCode(((DataBufferInt) image.getRaster().getDataBuffer()).getData());
        }
        if (nativeCalls == Integer.MIN_VALUE) {
            nativeCalls = calls;
            result = hash;
            preview = previewHash;
        }
        if ((calls <= 0) || (calls != nativeCalls) || (hash != result) || (previewHash != preview) || (cached != null && ! cached.isClosed())) {
            failures++;
            System.out.printf(Locale.ROOT, "FAILED validation: bounded=%b native_calls=%d result=%d preview=%d%n", boundedMode, calls, hash, previewHash);
        }
        return elapsed;
    }

    private static double median(double[] values) {
        final double[] sorted = values.clone();
        Arrays.sort(sorted);
        return (sorted.length % 2 == 1) ? sorted[sorted.length / 2] : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
    }

    private final File file;
    private final BufferedImage[] previewImages;
    private int nativeCalls = Integer.MIN_VALUE, failures;
    private long result, preview;
}
