package org.pepsoft.worldpainter.importing;

import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.heightMaps.CachedImageHeightMap;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;

import javax.imageio.ImageIO;
import java.io.File;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.Arrays;
import java.util.Locale;

/**
 * Memory and time of the complete operation of the mask import dialog on a TIFF file, densely ({@code ImageIO.read}
 * and {@link MaskImporter#MaskImporter(Dimension, File, java.awt.image.BufferedImage)}) or bounded (the capture of
 * mission 04 and {@link MaskImporter#bounded(Dimension, File, CachedImageHeightMap)}): open and analyse, then either
 * import into a dimension with existing tiles or, for images whose world would not fit the heap anyway, only open and
 * analyse ({@code source}). Run each mode in its own JVM for the memory figures. Validated: native transactions for
 * every tile, a digest of the result.
 *
 * <p>Arguments: {@code dense|bounded file import|source repetitions}.
 */
public final class BoundedMaskImportBenchmark {
    public static void main(String[] args) throws Exception {
        final boolean bounded = args[0].equals("bounded"), importing = args[2].equals("import");
        final File file = new File(args[1]);
        final int repetitions = Integer.parseInt(args[3]);
        if (! NativeLoader.areSlicesAvailable()) {
            System.out.println("FAILED: native library not available");
            System.exit(1);
        }
        System.setProperty(Native.GEN_KEY, "true");
        final double[] open = new double[repetitions], work = new double[repetitions];
        long retainedAfterOpen = -1, retainedWithWorld = -1, retainedAfterClose = -1, cacheBytes = 0, result = 0;
        int calls = 0, tiles = 0, failures = 0;
        for (int repetition = 0; repetition < repetitions; repetition++) {
            // The dimension is set up before the timed operation, as it exists before the dialog opens
            final Dimension dimension = importing ? fixture(file) : null;
            long start = System.nanoTime();
            final CachedImageHeightMap capture = bounded ? CachedImageHeightMap.capture(file, file.getName()) : null;
            final MaskImporter importer;
            if (bounded) {
                importer = (capture != null) ? MaskImporter.bounded(dimension, file, capture) : null;
                if (importer == null) {
                    throw new IllegalStateException("bounded import declined");
                }
                cacheBytes = capture.getCacheFileSize();
            } else {
                importer = new MaskImporter(dimension, file, ImageIO.read(file));
            }
            open[repetition] = (System.nanoTime() - start) / 1e6;
            try {
                if (repetition == 0) {
                    retainedAfterOpen = retainedHeap();
                }
                start = System.nanoTime();
                if (importing) {
                    MaskImportBenchmark.configure(importer, "continuous", 1, true);
                    importer.doImport(null);
                    calls = importer.getLastNativeImportCalls();
                    tiles = dimension.getTileCount();
                }
                work[repetition] = (System.nanoTime() - start) / 1e6;
                if (repetition == 0) {
                    retainedWithWorld = retainedHeap();
                    java.lang.ref.Reference.reachabilityFence(importer);
                }
            } finally {
                if (capture != null) {
                    capture.close();
                }
            }
            if (repetition == 0) {
                // The dialog releases the capture after the import: what remains is the world
                retainedAfterClose = retainedHeap();
                java.lang.ref.Reference.reachabilityFence(dimension);
            }
            if (importing) {
                final long digest = digest(dimension);
                if ((repetition > 0) && (digest != result)) {
                    failures++;
                }
                result = digest;
                if (calls != tiles) {
                    failures++;
                }
            } else {
                result = Double.doubleToLongBits(importer.getImageLowValue()) * 31 + Double.doubleToLongBits(importer.getImageHighValue());
            }
            System.out.printf(Locale.ROOT, "rep=%d open_analyse_ms=%.1f import_ms=%.1f low=%s high=%s%n", repetition, open[repetition], work[repetition],
                    importer.getImageLowValue(), importer.getImageHighValue());
        }
        long heapPoolPeaks = 0;
        for (MemoryPoolMXBean pool: ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                heapPoolPeaks += pool.getPeakUsage().getUsed();
            }
        }
        long direct = 0;
        for (BufferPoolMXBean pool: ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if (pool.getName().equals("direct")) {
                direct = pool.getMemoryUsed();
            }
        }
        System.out.printf(Locale.ROOT, "summary mode=%s file=%s work=%s reps=%d median_open_analyse_ms=%.1f median_import_ms=%.1f retained_heap_after_open=%d"
                        + " retained_heap_during_import=%d retained_heap_after_close=%d heap_pool_peaks=%d direct_used=%d peak_working_set=%d cache_file_bytes=%d native_calls=%d tiles=%d result=%d failures=%d xmx=%d%n",
                bounded ? "bounded" : "dense", file.getName(), importing ? "import" : "source", repetitions, median(open), median(work), retainedAfterOpen, retainedWithWorld,
                retainedAfterClose, heapPoolPeaks, direct, peakWorkingSet(), cacheBytes, calls, tiles, result, failures, Runtime.getRuntime().maxMemory());
        System.exit((failures == 0) ? 0 : 1);
    }

    /** A dimension with existing tiles covering the image at the offsets of {@link MaskImportBenchmark}. */
    static Dimension fixture(File file) throws java.io.IOException {
        final int[] size = size(file);
        return MaskImportBenchmark.fixture(size[0], size[1], -31, -47);
    }

    /** The size of an image, without decoding it. */
    static int[] size(File file) throws java.io.IOException {
        try (var in = ImageIO.createImageInputStream(file)) {
            final javax.imageio.ImageReader reader = ImageIO.getImageReaders(in).next();
            try {
                reader.setInput(in, false, true);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        }
    }

    static long digest(Dimension dimension) {
        long hash = 17;
        for (Tile tile: dimension.getTiles()) {
            hash = hash * 31 + tile.getX() * 65537L + tile.getY();
            for (Layer layer: tile.getLayers()) {
                for (int y = 0; y < 128; y += 3) {
                    for (int x = 0; x < 128; x += 3) {
                        hash = hash * 31 + ((layer.dataSize.maxValue == 1) ? (tile.getBitLayerValue(layer, x, y) ? 1 : 0) : tile.getLayerValue(layer, x, y));
                    }
                }
            }
            for (int y = 0; y < 128; y += 5) {
                for (int x = 0; x < 128; x += 5) {
                    hash = hash * 31 + tile.getTerrain(x, y).ordinal();
                }
            }
        }
        return hash;
    }

    private static long retainedHeap() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(50);
        }
        final Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static double median(double[] values) {
        final double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static long peakWorkingSet() throws Exception {
        final Process process = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                "(Get-Process -Id " + ProcessHandle.current().pid() + ").PeakWorkingSet64").start();
        return Long.parseLong(new String(process.getInputStream().readAllBytes()).trim());
    }
}
