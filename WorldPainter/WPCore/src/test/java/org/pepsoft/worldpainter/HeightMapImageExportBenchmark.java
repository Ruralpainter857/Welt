package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.exporting.HeightMapExporter;
import org.pepsoft.worldpainter.heightMaps.ConstantHeightMap;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;

/** Measures complete heightmap image creation and file writing; decoding is outside timing. */
public final class HeightMapImageExportBenchmark {
    private static volatile long checksum;

    private static BufferedImage readSamples(File file, HeightMapExporter.Format format) throws Exception {
        if (format == HeightMapExporter.Format.INTEGER_LOW_RESOLUTION
                || format == HeightMapExporter.Format.INTEGER_HIGH_RESOLUTION) return ImageIO.read(file);
        // The default reader may clamp scientific float samples to the display range.
        // Decode with the JDK TIFF reader to retain negative heights and values above one.
        try (var input = ImageIO.createImageInputStream(file)) {
            var readers = ImageIO.getImageReaders(input);
            while (readers.hasNext()) {
                var reader = readers.next();
                try {
                    if (reader.getClass().getName().equals("com.sun.imageio.plugins.tiff.TIFFImageReader")) {
                        reader.setInput(input);
                        return reader.read(0);
                    }
                } finally {
                    reader.dispose();
                }
            }
        }
        throw new AssertionError("JDK TIFF reader unavailable for raw float validation");
    }

    public static void main(String[] args) throws Exception {
        int side = Integer.getInteger("welt.benchmark.imageSide", 4);
        int warmups = Integer.getInteger("welt.benchmark.imageWarmups", 10);
        if (side < 1 || side > 16 || warmups < 5 || warmups > 100) {
            throw new IllegalArgumentException("Invalid benchmark dimensions or warmup count");
        }
        HeightMapExporter.Format format = HeightMapExporter.Format.valueOf(
                System.getProperty("welt.benchmark.imageFormat", "INTEGER_HIGH_RESOLUTION"));
        String extension = System.getProperty("welt.benchmark.imageExtension", "png");
        var platform = DefaultPlugin.JAVA_ANVIL_1_19;
        var factory = new HeightMapTileFactory(197, new ConstantHeightMap(64), -64, 320,
                false, BitmapWorldCreationBenchmark.theme(-64, 320));
        var world = new World2(platform, -64, 320);
        var dimension = new Dimension(world, "Image benchmark", 197, factory,
                Dimension.Anchor.NORMAL_DETAIL, false);
        for (int ty = 0; ty < side; ty++) for (int tx = 0; tx < side; tx++) {
            // Include negative coordinates and a missing tile inside the image extent.
            if (side > 2 && tx == 1 && ty == 1) continue;
            Tile tile = new Tile(tx - side / 2, ty - side / 2, -64, 320);
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                int raw = Math.floorMod((x + tx * 128) * 193 + (y + ty * 128) * 79
                        + x * y * 13, 60000);
                tile.setHeight(x, y, raw / 256f - 64);
            }
            dimension.addTile(tile);
        }
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        double[] times = new double[9];
        long[] allocations = new long[9];
        File output = Files.createTempFile("welt-heightmap-benchmark-", "." + extension).toFile();
        long expectedHash = 0, bytes = 0;
        float[] heightRange = dimension.getHeightRange();
        try {
            for (int trial = -warmups; trial < 9; trial++) {
                long before = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
                HeightMapExporter exporter = new HeightMapExporter(dimension, format);
                if (!exporter.exportToFile(output)) throw new AssertionError("No image writer available");
                long elapsed = System.nanoTime() - start;
                long allocated = bean.getThreadAllocatedBytes(thread) - before;
                // Compare decoded samples, rather than compressed file bytes or timestamps.
                BufferedImage image = readSamples(output, format);
                if (image == null || image.getWidth() != side * 128 || image.getHeight() != side * 128) {
                    throw new AssertionError("Invalid exported image dimensions");
                }
                long hash = 1;
                var raster = image.getRaster();
                for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                    Tile tile = dimension.getTile(x / 128 - side / 2, y / 128 - side / 2);
                    double expected = tile == null ? 0 : switch (format) {
                        case INTEGER_HIGH_RESOLUTION -> tile.getRawHeight(x & 127, y & 127);
                        case INTEGER_LOW_RESOLUTION -> tile.getIntHeight(x & 127, y & 127) + 64;
                        case FLOAT_ONE_TO_ONE -> tile.getHeight(x & 127, y & 127);
                        case FLOAT_NORMALISED -> (tile.getHeight(x & 127, y & 127) - heightRange[0])
                                / (heightRange[1] - heightRange[0]);
                    };
                    double actual = raster.getSampleDouble(x, y, 0);
                    if (Double.doubleToLongBits(expected) != Double.doubleToLongBits(actual)) {
                        throw new AssertionError("Exported height mismatch at " + x + "," + y + ": expected=" + expected + ", actual=" + actual);
                    }
                    hash = hash * 31 + Double.doubleToRawLongBits(actual);
                }
                if (trial == -warmups) expectedHash = hash;
                else if (hash != expectedHash) throw new AssertionError("Unstable exported samples");
                checksum = hash;
                bytes = output.length();
                if (trial >= 0) { times[trial] = elapsed / 1e6; allocations[trial] = allocated; }
            }
            Arrays.sort(times); Arrays.sort(allocations);
            System.out.printf(Locale.ROOT,
                    "heightmapImage format=%s extension=%s side=%d medianMs=%.3f callerAllocatedBytes=%d fileBytes=%d sampleHash=%d%n",
                    format, extension, side, times[4], allocations[4], bytes, checksum);
        } finally {
            Files.deleteIfExists(output.toPath());
        }
    }
}
