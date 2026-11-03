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
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.Arrays;
import java.util.Locale;

import static org.pepsoft.worldpainter.Constants.TILE_SIZE;

/**
 * The complete operation of the height map import dialog on a TIFF file, densely ({@code ImageIO.read} and
 * {@link BitmapHeightMap}) or bounded ({@link CachedImageHeightMap}): open, range, preview tiles and either an import
 * into a new dimension or, for images whose dimension would not fit the heap anyway, a sweep reading every tile of
 * heights as the import does. Run each mode in its own JVM for the memory figures.
 *
 * <p>Arguments: {@code dense|bounded file import|sweep repetitions previewTiles}. Prints one line per repetition and a
 * summary with the median times, the heap retained by the open image, the peaks of the heap pools, the direct buffers
 * and the process (peak working set), the size of the private cache file and checksums of the result.
 */
public final class BoundedTiffImportBenchmark {
    public static void main(String[] args) throws Exception {
        final boolean bounded = args[0].equals("bounded");
        final File file = new File(args[1]);
        final boolean importing = args[2].equals("import");
        final int repetitions = Integer.parseInt(args[3]), previewTiles = Integer.parseInt(args[4]);
        final Configuration configuration = new Configuration();
        configuration.setDefaultPlatform(DefaultPlugin.JAVA_ANVIL_1_19);
        Configuration.setInstance(configuration);
        System.setProperty(Native.GEN_KEY, Boolean.toString(NativeLoader.areSlicesAvailable()));
        final com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long thread = Thread.currentThread().getId();
        final double[][] times = new double[5][repetitions];
        long retained = -1, retainedAfterPreview = -1, cacheBytes = 0, allocated = 0, resultChecksum = 0, previewChecksum = 0;
        int nativeCalls = 0;
        for (int repetition = 0; repetition < repetitions; repetition++) {
            final long allocatedBefore = threads.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            // Open, as the dialog does
            final HeightMap map;
            final CachedImageHeightMap cached;
            if (bounded) {
                cached = CachedImageHeightMap.capture(file, file.getName());
                if (cached == null) {
                    throw new IllegalStateException("not captured");
                }
                map = cached;
                cacheBytes = cached.getCacheFileSize();
            } else {
                cached = null;
                final BufferedImage image = ImageIO.read(file);
                map = BitmapHeightMap.build().withName(file.getName()).withImage(image).withFile(file).now();
            }
            times[0][repetition] = (System.nanoTime() - start) / 1e6;
            try {
                start = System.nanoTime();
                final double[] range = map.getRange();
                times[1][repetition] = (System.nanoTime() - start) / 1e6;
                if (repetition == 0) {
                    retained = retainedHeap();
                }
                // Preview tiles from the centre, as the preview of the dialog draws them
                start = System.nanoTime();
                final HeightMapImportBenchmark.State state = HeightMapImportBenchmark.fixture(map, "fresh", false);
                final HeightMapImporter importer = state.importer();
                importer.setImageLowLevel(range[0]);
                importer.setImageHighLevel((range[1] > range[0]) ? range[1] : range[0] + 1);
                final HeightMapTileFactory importFactory = (HeightMapTileFactory) state.dimension().getTileFactory();
                final HeightMapTileFactory previewFactory = BoundedTiffImportParityTest.previewTileFactory();
                importer.setTileFactory(previewFactory);
                importer.setTheme(previewFactory.getTheme());
                final org.pepsoft.util.swing.TileProvider preview = importer.getPreviewProvider(null, ColourScheme.DEFAULT, true, 10, TileRenderer.LightOrigin.NORTHWEST);
                final BufferedImage previewImage = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
                final int side = (int) Math.ceil(Math.sqrt(previewTiles));
                final int centreX = map.getExtent().width / TILE_SIZE / 2 - side / 2, centreY = map.getExtent().height / TILE_SIZE / 2 - side / 2;
                long previewHash = 17;
                // The theme draws from a shared random generator
                HeightMapImportBenchmark.random().setSeed(7);
                for (int i = 0; i < previewTiles; i++) {
                    if (! preview.paintTile(previewImage, centreX + i % side, centreY + i / side, 0, 0)) {
                        throw new IllegalStateException("preview tile not drawn");
                    }
                    previewHash = previewHash * 31 + Arrays.hashCode(((DataBufferInt) previewImage.getRaster().getDataBuffer()).getData());
                }
                previewChecksum = previewHash;
                times[2][repetition] = (System.nanoTime() - start) / 1e6;
                if (repetition == 0) {
                    // With the block cache of the bounded image filled by the preview
                    retainedAfterPreview = retainedHeap();
                    java.lang.ref.Reference.reachabilityFence(map);
                    java.lang.ref.Reference.reachabilityFence(preview);
                }
                start = System.nanoTime();
                importer.setTileFactory(importFactory);
                importer.setTheme(importFactory.getTheme());
                if (importing) {
                    HeightMapImportBenchmark.random().setSeed(42);
                    state.run();
                    nativeCalls = importer.getLastNativeImportCalls();
                    long hash = 17;
                    for (Tile tile: state.dimension().getTiles()) {
                        for (int y = 0; y < TILE_SIZE; y += 7) {
                            for (int x = 0; x < TILE_SIZE; x += 7) {
                                hash = hash * 31 + Float.floatToIntBits(tile.getHeight(x, y)) + 7L * tile.getTerrain(x, y).ordinal();
                            }
                        }
                    }
                    resultChecksum = hash + state.dimension().getTileCount();
                } else {
                    resultChecksum = sweep(map);
                }
                times[3][repetition] = (System.nanoTime() - start) / 1e6;
            } finally {
                if (cached != null) {
                    cached.close();
                }
            }
            times[4][repetition] = times[0][repetition] + times[1][repetition] + times[2][repetition] + times[3][repetition];
            allocated = threads.getThreadAllocatedBytes(thread) - allocatedBefore;
            System.out.printf(Locale.ROOT, "rep=%d open_ms=%.1f range_ms=%.1f preview_ms=%.1f %s_ms=%.1f total_ms=%.1f allocated=%d%n",
                    repetition, times[0][repetition], times[1][repetition], times[2][repetition], importing ? "import" : "sweep",
                    times[3][repetition], times[4][repetition], allocated);
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
        System.out.printf(Locale.ROOT, "summary mode=%s file=%s work=%s reps=%d median_open_ms=%.1f median_range_ms=%.1f median_preview_ms=%.1f median_work_ms=%.1f median_total_ms=%.1f"
                        + " retained_heap_after_open=%d retained_heap_after_preview=%d heap_pool_peaks=%d direct_used=%d peak_working_set=%d cache_file_bytes=%d last_allocated=%d native_calls=%d result=%d preview=%d xmx=%d%n",
                bounded ? "bounded" : "dense", file.getName(), importing ? "import" : "sweep", repetitions, median(times[0]), median(times[1]), median(times[2]),
                median(times[3]), median(times[4]), retained, retainedAfterPreview, heapPoolPeaks, direct, peakWorkingSet(), cacheBytes, allocated, nativeCalls, resultChecksum, previewChecksum,
                Runtime.getRuntime().maxMemory());
    }

    /** Read every tile of heights, as the import does, without keeping a dimension. */
    private static long sweep(HeightMap map) {
        final int width = map.getExtent().width, height = map.getExtent().height;
        final double[] heights = new double[TILE_SIZE * TILE_SIZE], row = new double[TILE_SIZE];
        long hash = 17;
        for (int tileY = 0; tileY < height; tileY += TILE_SIZE) {
            for (int tileX = 0; tileX < width; tileX += TILE_SIZE) {
                if (map instanceof CachedImageHeightMap cached) {
                    cached.fillHeights(tileX, tileY, TILE_SIZE, TILE_SIZE, heights, row);
                } else {
                    final BitmapHeightMap bitmap = (BitmapHeightMap) map;
                    bitmap.fillSamples(tileX, tileY, TILE_SIZE, TILE_SIZE, heights, row);
                    if ((! bitmap.isFloatingPoint()) && (! bitmap.isSigned()) && (bitmap.getBitDepth() == 32)) {
                        for (int i = 0; i < heights.length; i++) {
                            if (heights[i] < 0) {
                                heights[i] += 4294967296.0;
                            }
                        }
                    }
                }
                for (int i = 0; i < heights.length; i += 13) {
                    hash = hash * 31 + Double.doubleToLongBits(heights[i]);
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
