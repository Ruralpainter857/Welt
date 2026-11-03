package org.pepsoft.worldpainter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.pepsoft.worldpainter.heightMaps.*;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.image.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.stream.Stream;

import static java.awt.image.DataBuffer.*;
import static org.junit.Assert.*;

/**
 * The bounded TIFF capture ({@link CachedImageHeightMap}) against the dense path ({@code ImageIO.read} and
 * {@link BitmapHeightMap}): metadata and range, every sample in and out of bounds, sample windows, complete imports in
 * several configurations (scaled/bicubic, offset, inverted, void below, existing tiles) with the native counters, the
 * import preview, a stable snapshot when the file changes after the capture, unsupported files, errors and the removal
 * of the private cache file. Fixtures are written under {@code target/heightmap-import-work/test}.
 */
public class BoundedTiffImportParityTest {
    @Before
    public void setUp() throws IOException {
        previousConfiguration = Configuration.getInstance();
        final Configuration configuration = new Configuration();
        configuration.setDefaultPlatform(DefaultPlugin.JAVA_ANVIL_1_19);
        Configuration.setInstance(configuration);
        previousGen = System.getProperty(Native.GEN_KEY);
        Files.createDirectories(DIR);
        cacheFilesBefore = cacheFiles();
    }

    @After
    public void tearDown() throws IOException {
        Configuration.setInstance(previousConfiguration);
        if (previousGen == null) {
            System.clearProperty(Native.GEN_KEY);
        } else {
            System.setProperty(Native.GEN_KEY, previousGen);
        }
        assertEquals("cache files left behind", cacheFilesBefore, cacheFiles());
    }

    @Test
    public void samplesMetadataAndWindowsMatchTheDenseImage() throws Exception {
        // The transfer types the dense reader actually produced for each written type, and the written types it refused
        final Map<Integer, Integer> covered = new TreeMap<>();
        final Set<Integer> refused = new TreeSet<>();
        for (int type: new int[] {TYPE_BYTE, TYPE_USHORT, TYPE_SHORT, TYPE_INT, TYPE_FLOAT, TYPE_DOUBLE}) {
            final File file = fixture("samples-" + type, type, 301, 517);
            final BufferedImage image;
            try {
                image = ImageIO.read(file);
            } catch (IOException e) {
                // The reader ImageIO.read() uses does not support the type (64 bit floating point for TwelveMonkeys):
                // the capture, with the same reader, must fail the same way and leave nothing behind
                try (CachedImageHeightMap cached = CachedImageHeightMap.capture(file, file.getName())) {
                    fail("type " + type + " is not readable densely but was captured: " + cached);
                } catch (IOException expected) {
                    // Expected
                }
                refused.add(type);
                continue;
            }
            assertNotNull("type " + type + " read densely", image);
            covered.put(type, image.getRaster().getTransferType());
            final BitmapHeightMap dense = BitmapHeightMap.build().withImage(image).now();
            final Raster written = jdkRead(file);
            for (int y = 0; y < 517; y += 37) {
                for (int x = 0; x < 301; x += 29) {
                    final double sample = written.getSampleDouble(x, y, 0);
                    assertEquals("type " + type + " fixture at " + x + "," + y, expectedHeight(type, x, y, 0),
                            ((type == TYPE_INT) && (sample < 0)) ? sample + 4294967296.0 : sample, 0.0);
                }
            }
            try (CachedImageHeightMap cached = CachedImageHeightMap.capture(file, file.getName())) {
                assertNotNull("type " + type + " must be captured", cached);
                final String label = "type " + type;
                assertEquals(label, dense.getWidth(), cached.getWidth());
                assertEquals(label, dense.getHeight(), cached.getHeight());
                assertEquals(label, dense.getBitDepth(), cached.getBitDepth());
                assertEquals(label, dense.isSigned(), cached.isSigned());
                assertEquals(label, dense.isFloatingPoint(), cached.isFloatingPoint());
                assertEquals(label, dense.getMinHeight(), cached.getMinHeight(), 0.0);
                assertEquals(label, dense.getMaxHeight(), cached.getMaxHeight(), 0.0);
                assertEquals(label, dense.getExtent(), cached.getExtent());
                assertArrayEquals(label + " range", dense.getRange(), cached.getRange(), 0.0);
                for (int y = -3; y < dense.getHeight() + 3; y++) {
                    for (int x = -3; x < dense.getWidth() + 3; x++) {
                        assertEquals(label + " at " + x + "," + y, Double.doubleToLongBits(dense.getHeight(x, y)), Double.doubleToLongBits(cached.getHeight(x, y)));
                    }
                }
                for (int[] window: new int[][] {{0, 0, 128, 128}, {250, 400, 128, 128}, {-50, -60, 128, 128}, {280, 500, 64, 40}, {-200, 300, 600, 3}}) {
                    final int n = window[2] * window[3];
                    final double[] expected = new double[n], actual = new double[n], row = new double[window[2]];
                    assertTrue(dense.fillSamples(window[0], window[1], window[2], window[3], expected, row));
                    heights(dense, expected);
                    assertTrue(cached.fillHeights(window[0], window[1], window[2], window[3], actual, row));
                    assertArrayEquals(label + " window " + Arrays.toString(window), expected, actual, 0.0);
                    assertTrue(dense.fillRepeatedSamples(window[0], window[1], window[2], window[3], expected, row));
                    heights(dense, expected);
                    assertTrue(cached.fillRepeatedHeights(window[0], window[1], window[2], window[3], actual, row));
                    assertArrayEquals(label + " repeated window " + Arrays.toString(window), expected, actual, 0.0);
                }
            }
        }
        // Explicit coverage: every integer and float32 type round trips with its own transfer type (signed 16 bit
        // included), and only 64 bit floating point is refused, by the dense reader as well as by the capture
        assertEquals(Map.of(TYPE_BYTE, TYPE_BYTE, TYPE_USHORT, TYPE_USHORT, TYPE_SHORT, TYPE_SHORT, TYPE_INT, TYPE_INT, TYPE_FLOAT, TYPE_FLOAT), covered);
        assertEquals(Set.of(TYPE_DOUBLE), refused);
    }

    @Test
    public void unsignedThirtyTwoBitSamplesAreRawInBitmapsAndHeightsInTheCapture() throws Exception {
        // Raw raster samples of 2^31 and more are negative; heights (getHeight(), fillHeights()) are unsigned
        final File file = fixture("int32-anchor", TYPE_INT, 30, 20);
        final BitmapHeightMap dense = BitmapHeightMap.build().withImage(ImageIO.read(file)).now();
        try (CachedImageHeightMap cached = CachedImageHeightMap.capture(file, file.getName())) {
            assertFalse(cached.isSigned());
            assertEquals(32, cached.getBitDepth());
            assertEquals(4294967295.0, cached.getMaxHeight(), 0.0);
            // Column 0 holds Integer.MIN_VALUE + y
            final double[] raw = new double[30 * 20], heights = new double[30 * 20], row = new double[30];
            assertTrue(dense.fillSamples(0, 0, 30, 20, raw, row));
            assertTrue(cached.fillHeights(0, 0, 30, 20, heights, row));
            assertEquals(Integer.MIN_VALUE + 3.0, raw[3 * 30], 0.0);
            assertEquals(2147483648.0 + 3.0, dense.getHeight(0, 3), 0.0);
            assertEquals(2147483648.0 + 3.0, cached.getHeight(0, 3), 0.0);
            assertEquals(2147483648.0 + 3.0, heights[3 * 30], 0.0);
            assertTrue(cached.getRange()[1] >= 2147483648.0);
            assertArrayEquals(dense.getRange(), cached.getRange(), 0.0);
        }
    }

    @Test
    public void floatSpecialValuesMatchTheDenseImage() throws Exception {
        // -0, NaN and both infinities, in samples, range and complete imports with fixed levels
        final File file = DIR.resolve("float-specials.tif").toFile();
        final ColorModel model = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), false, false, Transparency.OPAQUE, TYPE_FLOAT);
        final WritableRaster raster = model.createCompatibleWritableRaster(260, 140);
        final float[] specials = {-0.0f, 0.0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -Float.MAX_VALUE, Float.MIN_VALUE};
        for (int y = 0; y < 140; y++) {
            for (int x = 0; x < 260; x++) {
                raster.setSample(x, y, 0, ((x + y) % 11 == 0) ? specials[(x / 11 + y) % specials.length] : (float) (200 * Math.sin(x / 9.0) - 50));
            }
        }
        writeTiff(file, raster, TYPE_FLOAT, false);
        final BitmapHeightMap dense = BitmapHeightMap.build().withName(file.getName()).withImage(ImageIO.read(file)).withFile(file).now();
        assertEquals("fixture holds the special values", Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits((float) dense.getHeight(0, 0)));
        try (CachedImageHeightMap cached = CachedImageHeightMap.capture(file, file.getName())) {
            for (int y = 0; y < 140; y++) {
                for (int x = 0; x < 260; x++) {
                    assertEquals("at " + x + "," + y, Double.doubleToRawLongBits(dense.getHeight(x, y)), Double.doubleToRawLongBits(cached.getHeight(x, y)));
                }
            }
            final double[] denseRange = dense.getRange(), cachedRange = cached.getRange();
            assertEquals("low", Double.doubleToLongBits(denseRange[0]), Double.doubleToLongBits(cachedRange[0]));
            assertEquals("high", Double.doubleToLongBits(denseRange[1]), Double.doubleToLongBits(cachedRange[1]));
            final double[] levels = {-250, 150};
            for (boolean nativeImport: NativeLoader.areSlicesAvailable() ? new boolean[] {false, true} : new boolean[] {false}) {
                System.setProperty(Native.GEN_KEY, Boolean.toString(nativeImport));
                for (String variant: new String[] {"plain", "void"}) {
                    final Result expected = importWith(dense, variant, levels), actual = importWith(cached, variant, levels);
                    assertEquals(variant + " " + nativeImport + ": native tile imports", expected.nativeCalls, actual.nativeCalls);
                    assertEquals(variant + " " + nativeImport + ": tiles", expected.tiles, actual.tiles);
                    assertArrayEquals(variant + " " + nativeImport + ": preview", expected.preview, actual.preview);
                }
            }
        }
    }

    @Test
    public void completeImportsAndPreviewsMatchTheDenseImage() throws Exception {
        importParity(TYPE_USHORT, new String[] {"plain", "scaled", "offset", "inverted", "void", "existing", "raise"});
    }

    @Test
    public void importsOfOtherSampleTypesMatchTheDenseImage() throws Exception {
        importParity(TYPE_BYTE, new String[] {"plain", "scaled", "inverted"});
        importParity(TYPE_FLOAT, new String[] {"plain", "scaled", "void"});
        importParity(TYPE_INT, new String[] {"plain", "scaled", "offset", "void"});
    }

    @Test
    public void unsignedThirtyTwoBitHeightsAreTheSameInJavaAndNativeImports() throws Exception {
        // Raw samples of 2^31 and more are negative in the raster; as heights they must stay unsigned on both paths
        if (! NativeLoader.areSlicesAvailable()) {
            return;
        }
        final File file = fixture("int32-paths", TYPE_INT, 300, 260);
        final BitmapHeightMap dense = BitmapHeightMap.build().withImage(ImageIO.read(file)).now();
        final double[] levels = {0, 4294967295.0};
        for (String variant: new String[] {"plain", "offset", "void"}) {
            System.setProperty(Native.GEN_KEY, "false");
            final Result java = importWith(dense, variant, levels);
            System.setProperty(Native.GEN_KEY, "true");
            final Result nativeResult = importWith(dense, variant, levels);
            assertTrue(variant + ": native import expected", nativeResult.nativeCalls > 0);
            assertEquals(variant + ": java and native tiles", java.tiles, nativeResult.tiles);
        }
    }

    private void importParity(int type, String[] variants) throws Exception {
        final File file = fixture("import-" + type, type, 330, 270);
        final BufferedImage image = ImageIO.read(file);
        assertEquals("fixture type " + type, type, image.getRaster().getTransferType());
        final BitmapHeightMap dense = BitmapHeightMap.build().withName(file.getName()).withImage(image).withFile(file).now();
        final double[] levels = (type == TYPE_USHORT) ? new double[] {0, 65535} : dense.getRange();
        try (CachedImageHeightMap cached = CachedImageHeightMap.capture(file, file.getName())) {
            for (boolean nativeImport: NativeLoader.areSlicesAvailable() ? new boolean[] {false, true} : new boolean[] {false}) {
                System.setProperty(Native.GEN_KEY, Boolean.toString(nativeImport));
                for (String variant: variants) {
                    final String label = "type " + type + " " + variant + (nativeImport ? " native" : " java");
                    final Result expected = importWith(dense, variant, levels), actual = importWith(cached, variant, levels);
                    assertEquals(label + ": native tile imports", expected.nativeCalls, actual.nativeCalls);
                    if (nativeImport && (variant.equals("plain") || variant.equals("offset"))) {
                        assertTrue(label + ": native import expected", actual.nativeCalls > 0);
                    }
                    assertEquals(label + ": tiles", expected.tiles.keySet(), actual.tiles.keySet());
                    for (Map.Entry<Point, Long> entry: expected.tiles.entrySet()) {
                        assertEquals(label + ": tile " + entry.getKey(), entry.getValue(), actual.tiles.get(entry.getKey()));
                    }
                    assertArrayEquals(label + ": preview", expected.preview, actual.preview);
                    if (type == TYPE_INT) {
                        // Unsigned 32 bit heights: both against an oracle reading every height through getHeight()
                        final Result oracle = importWith(new GetHeightOracle(dense), variant, levels);
                        assertEquals(label + ": oracle tiles", oracle.tiles, expected.tiles);
                        assertArrayEquals(label + ": oracle preview", oracle.preview, expected.preview);
                    }
                }
            }
        }
    }

    @Test
    public void thirtyTwoBitTilesOfTheHeightMapTileFactoryMatchAGetHeightOracle() throws Exception {
        // The bulk bitmap path of HeightMapTileFactory read raw (signed) samples for unsigned 32 bit images
        final File file = fixture("int32-factory", TYPE_INT, 300, 260);
        final BitmapHeightMap dense = BitmapHeightMap.build().withImage(ImageIO.read(file)).now();
        try (CachedImageHeightMap cached = CachedImageHeightMap.capture(file, file.getName())) {
            for (boolean nativeGeneration: new boolean[] {false, true}) {
                System.setProperty(Native.GEN_KEY, Boolean.toString(nativeGeneration));
                final HeightMapTileFactory oracleFactory = factory(new GetHeightOracle(dense)), denseFactory = factory(dense), cachedFactory = factory(cached);
                for (Point tile: new Point[] {new Point(0, 0), new Point(1, 1), new Point(2, 0), new Point(-1, 0)}) {
                    final long expected = digest(oracleFactory.createTile(tile.x, tile.y));
                    assertEquals("dense " + tile + " native " + nativeGeneration, expected, digest(denseFactory.createTile(tile.x, tile.y)));
                    assertEquals("bounded " + tile + " native " + nativeGeneration, expected, digest(cachedFactory.createTile(tile.x, tile.y)));
                }
                // Column 0 holds 2^31 + y as a height: the highest level, not the lowest
                final Tile tile = denseFactory.createTile(0, 0);
                assertEquals(DefaultPlugin.JAVA_ANVIL_1_19.standardMaxHeight - 1, tile.getIntHeight(0, 5));
            }
        }
    }

    private static HeightMapTileFactory factory(HeightMap heightMap) {
        final HeightMapTileFactory preview = previewTileFactory();
        final Platform platform = DefaultPlugin.JAVA_ANVIL_1_19;
        return new HeightMapTileFactory(197, heightMap, platform.minZ, platform.standardMaxHeight, false, preview.getTheme());
    }

    @Test
    public void floatRangesOfTiffFilesFollowTheDenseScanOrder() throws Exception {
        // End to end through the TIFF reader. TwelveMonkeys, which ImageIO.read() uses, clamps floating point samples to
        // 0..1, so beyond the signs of zero these files cannot reach the type bounds or the infinities: those orders
        // are covered with rasters by CachedImageHeightMapTest. Here: the first zero of the x major scan (+0 at (0,1)
        // before -0 at (1,0), and the reverse), then arrangements of special values compared with the dense range in
        // every configuration
        final float max = Float.MAX_VALUE, inf = Float.POSITIVE_INFINITY;
        final Object[][] cases = {
                {0.5f, new float[][] {{0, 0, 0.75f}, {0, 1, 0.0f}, {1, 0, -0.0f}}, new double[] {0.0, 0.75}},
                {0.5f, new float[][] {{0, 0, 0.75f}, {0, 1, -0.0f}, {1, 0, 0.0f}}, new double[] {-0.0, 0.75}},
                {5f, new float[][] {{0, 3, -max}, {1, 0, max}, {2, 0, -inf}, {3, 1, inf}}, null},
                {5f, new float[][] {{0, 2, -inf}, {1, 1, max}, {2, 0, inf}}, null},
                {-5f, new float[][] {{0, 1, -0.0f}, {1, 0, 0.0f}, {4, 4, Float.NaN}}, null}};
        for (int i = 0; i < cases.length; i++) {
            final File file = DIR.resolve("range-" + i + ".tif").toFile();
            final WritableRaster raster = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), false, false, Transparency.OPAQUE, TYPE_FLOAT).createCompatibleWritableRaster(6, 5);
            for (int y = 0; y < 5; y++) {
                for (int x = 0; x < 6; x++) {
                    raster.setSample(x, y, 0, (float) cases[i][0]);
                }
            }
            for (float[] point: (float[][]) cases[i][1]) {
                raster.setSample((int) point[0], (int) point[1], 0, point[2]);
            }
            writeTiff(file, raster, TYPE_FLOAT, false);
            try (CachedImageHeightMap cached = CachedImageHeightMap.capture(file, file.getName())) {
                final double[] actual = cached.getRange();
                if (cases[i][2] != null) {
                    final double[] expected = (double[]) cases[i][2];
                    assertEquals("case " + i + " low", Double.doubleToRawLongBits(expected[0]), Double.doubleToRawLongBits(actual[0]));
                    assertEquals("case " + i + " high", Double.doubleToRawLongBits(expected[1]), Double.doubleToRawLongBits(actual[1]));
                }
                for (String nativeGeneration: new String[] {"false", "true"}) {
                    for (String floatRanges: new String[] {"false", "true"}) {
                        System.setProperty(Native.GEN_KEY, nativeGeneration);
                        final String previous = System.setProperty("welt.native.floatingImageRanges", floatRanges);
                        try {
                            final double[] dense = BitmapHeightMap.build().withImage(ImageIO.read(file)).now().getRange();
                            final String label = "case " + i + " gen " + nativeGeneration + " float ranges " + floatRanges;
                            assertEquals(label + " low", Double.doubleToRawLongBits(dense[0]), Double.doubleToRawLongBits(actual[0]));
                            assertEquals(label + " high", Double.doubleToRawLongBits(dense[1]), Double.doubleToRawLongBits(actual[1]));
                        } finally {
                            if (previous == null) {
                                System.clearProperty("welt.native.floatingImageRanges");
                            } else {
                                System.setProperty("welt.native.floatingImageRanges", previous);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void anUnverifiedTiffReaderKeepsTheDensePath() throws Exception {
        // Put the JDK TIFF reader before TwelveMonkeys: ImageIO.read() then uses it, and the capture declines
        final File file = fixture("other-reader", TYPE_USHORT, 120, 90);
        final javax.imageio.spi.IIORegistry registry = javax.imageio.spi.IIORegistry.getDefaultInstance();
        javax.imageio.spi.ImageReaderSpi jdk = null, twelveMonkeys = null;
        for (Iterator<javax.imageio.spi.ImageReaderSpi> i = registry.getServiceProviders(javax.imageio.spi.ImageReaderSpi.class, true); i.hasNext(); ) {
            final javax.imageio.spi.ImageReaderSpi spi = i.next();
            if (spi.getClass().getName().equals("com.sun.imageio.plugins.tiff.TIFFImageReaderSpi")) {
                jdk = spi;
            } else if (spi.getClass().getName().equals("com.twelvemonkeys.imageio.plugins.tiff.TIFFImageReaderSpi")) {
                twelveMonkeys = spi;
            }
        }
        assertNotNull(jdk);
        assertNotNull(twelveMonkeys);
        registry.unsetOrdering(javax.imageio.spi.ImageReaderSpi.class, twelveMonkeys, jdk);
        registry.setOrdering(javax.imageio.spi.ImageReaderSpi.class, jdk, twelveMonkeys);
        try {
            try (var in = ImageIO.createImageInputStream(file)) {
                assertEquals("com.sun.imageio.plugins.tiff.TIFFImageReader", ImageIO.getImageReaders(in).next().getClass().getName());
            }
            assertNull(CachedImageHeightMap.capture(file, file.getName()));
        } finally {
            registry.unsetOrdering(javax.imageio.spi.ImageReaderSpi.class, jdk, twelveMonkeys);
            registry.setOrdering(javax.imageio.spi.ImageReaderSpi.class, twelveMonkeys, jdk);
        }
        try (CachedImageHeightMap cached = CachedImageHeightMap.capture(file, file.getName())) {
            assertNotNull("captured again with the verified reader", cached);
        }
    }

    @Test
    public void compressedStripsAreCapturedButALargeCompressedStripIsDeclined() throws Exception {
        // Deflate compressed strips of a few rows, as the JDK writer makes them: captured, identical samples
        final File compressed = DIR.resolve("deflate-strips.tif").toFile();
        final ColorModel model = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), false, false, Transparency.OPAQUE, TYPE_USHORT);
        final WritableRaster raster = model.createCompatibleWritableRaster(310, 290);
        for (int y = 0; y < 290; y++) {
            for (int x = 0; x < 310; x++) {
                raster.setSample(x, y, 0, (x * 977 + y * 131) & 0xffff);
            }
        }
        writeImage(new BufferedImage(model, raster, false, null), compressed, "Deflate");
        final BitmapHeightMap dense = BitmapHeightMap.build().withImage(ImageIO.read(compressed)).now();
        try (CachedImageHeightMap cached = CachedImageHeightMap.capture(compressed, compressed.getName())) {
            assertNotNull("compressed strips captured", cached);
            for (int y = 0; y < 290; y++) {
                for (int x = 0; x < 310; x++) {
                    assertEquals(dense.getHeight(x, y), cached.getHeight(x, y), 0.0);
                }
            }
        }
        // One compressed strip higher than a band (66000 columns of 8 bits: bands of 128 rows): every band would decode
        // the strip from its start again, so the capture declines and the dense path, which reads it once, stays
        final File strip = DIR.resolve("deflate-one-strip.tif").toFile();
        writeSingleStrip(strip, 66000, 300, true);
        assertNotNull(ImageIO.read(strip));
        assertNull(CachedImageHeightMap.capture(strip, strip.getName()));
    }

    /** An 8 bit greyscale TIFF stored as one strip, Deflate compressed or not. */
    private static void writeSingleStrip(File file, int width, int height, boolean deflate) throws IOException {
        final WritableRaster raster = Raster.createBandedRaster(TYPE_BYTE, width, height, 1, null);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                raster.setSample(x, y, 0, (x / 7 + y * 3) & 0xff);
            }
        }
        writeTiff(file, raster, TYPE_BYTE, deflate);
    }

    /** Every height through getHeight(), so that no bulk or native path of a bitmap applies. */
    static final class GetHeightOracle extends AbstractHeightMap {
        GetHeightOracle(HeightMap base) {
            super("oracle");
            this.base = base;
        }

        @Override
        public double getHeight(int x, int y) {
            return base.getHeight(x, y);
        }

        @Override
        public double getHeight(float x, float y) {
            return base.getHeight(x, y);
        }

        @Override
        public Rectangle getExtent() {
            return base.getExtent();
        }

        @Override
        public javax.swing.Icon getIcon() {
            return null;
        }

        @Override
        public double[] getRange() {
            return base.getRange();
        }

        private final HeightMap base;
    }

    @Test
    public void clonesShareTheCacheButOnlyTheOwnerClosesIt() throws Exception {
        final File file = fixture("clone", TYPE_USHORT, 300, 280);
        final CachedImageHeightMap cached = CachedImageHeightMap.capture(file, file.getName());
        assertNotNull(cached);
        final double expected = cached.getHeight(290, 270);
        final CachedImageHeightMap clone = cached.clone();
        final HeightMap transformed = new TransformingHeightMap("t", cached, 1, 1, 5, 5, 0).clone();
        clone.close();
        assertFalse("closing a clone does not close the cache", cached.isClosed());
        assertEquals("one private cache file", cacheFilesBefore + 1, cacheFiles());
        assertEquals(expected, clone.getHeight(290, 270), 0.0);
        assertEquals(expected, cached.getHeight(290, 270), 0.0);
        assertEquals(expected, transformed.getHeight(295, 275), 0.0);
        // Concurrent readers through the clones and the owner share one bounded block cache
        final Thread[] threads = new Thread[4];
        final Throwable[] failure = new Throwable[1];
        final BitmapHeightMap dense = BitmapHeightMap.build().withImage(ImageIO.read(file)).now();
        for (int t = 0; t < threads.length; t++) {
            final HeightMap reader = (t % 2 == 0) ? cached : cached.clone();
            threads[t] = new Thread(() -> {
                try {
                    for (int i = 0; i < 40000; i++) {
                        final int x = (i * 37) % 300, y = (i * 53) % 280;
                        if (reader.getHeight(x, y) != dense.getHeight(x, y)) {
                            throw new AssertionError("different height at " + x + "," + y);
                        }
                    }
                } catch (Throwable e) {
                    failure[0] = e;
                }
            });
            threads[t].start();
        }
        for (Thread thread: threads) {
            thread.join();
        }
        assertNull(failure[0]);
        cached.close();
        assertTrue("the clones see the owner close", clone.isClosed());
        assertEquals(cacheFilesBefore, cacheFiles());
        assertEquals(cached.getMinHeight(), clone.getHeight(10, 10), 0.0);
        assertEquals(cached.getMinHeight(), transformed.getHeight(15, 15), 0.0);
    }

    @Test
    public void veryWideImagesAreDecodedInThinnerBands() throws Exception {
        // 70000 columns of 16 bits: a band of one row of blocks (128 rows) would exceed the band budget of 16 MiB, so it
        // is decoded in bands of 64 rows, which fill the blocks in two steps, across a row of blocks boundary. Images of
        // moderate width (the other tests) use bands of several rows of blocks
        final int width = 70000;
        final File file = fixture("wide", TYPE_USHORT, width, 200);
        final BitmapHeightMap dense = BitmapHeightMap.build().withImage(ImageIO.read(file)).now();
        try (CachedImageHeightMap cached = CachedImageHeightMap.capture(file, file.getName())) {
            assertNotNull(cached);
            assertArrayEquals(dense.getRange(), cached.getRange(), 0.0);
            final double[] row = new double[width];
            for (int y = 0; y < 200; y++) {
                cached.readRegion(0, y, width, 1, row, 0, width);
                for (int x = 0; x < width; x++) {
                    if (row[x] != dense.getHeight(x, y)) {
                        fail("height at " + x + "," + y + ": " + dense.getHeight(x, y) + " != " + row[x]);
                    }
                }
            }
        }
    }

    @Test
    public void aCancelledImportLeavesTheCaptureUsable() throws Exception {
        final File file = fixture("cancel", TYPE_USHORT, 400, 400);
        try (CachedImageHeightMap cached = CachedImageHeightMap.capture(file, file.getName())) {
            final HeightMapImportBenchmark.State state = HeightMapImportBenchmark.fixture(cached, "fresh", false);
            final int[] progressCalls = new int[1];
            try {
                state.importer().importToDimension(state.dimension(), true, new org.pepsoft.util.ProgressReceiver() {
                    @Override public void setProgress(float progress) throws OperationCancelled {
                        if (++progressCalls[0] == 3) {
                            throw new OperationCancelledByUser();
                        }
                    }
                    @Override public void exceptionThrown(Throwable exception) { }
                    @Override public void done() { }
                    @Override public void setMessage(String message) { }
                    @Override public void checkForCancellation() { }
                    @Override public void reset() { }
                    @Override public void subProgressStarted(org.pepsoft.util.SubProgressReceiver subProgressReceiver) { }
                });
                fail("the import must be cancelled");
            } catch (org.pepsoft.util.ProgressReceiver.OperationCancelled e) {
                // Expected
            }
            assertFalse(cached.isClosed());
            assertEquals("one private cache file", cacheFilesBefore + 1, cacheFiles());
            // A new import after the cancelled one is complete and identical to the dense one
            final double[] levels = {0, 65535};
            final BitmapHeightMap dense = BitmapHeightMap.build().withImage(ImageIO.read(file)).now();
            assertEquals(importWith(dense, "plain", levels).tiles, importWith(cached, "plain", levels).tiles);
        }
    }

    @Test
    public void captureIsAStableSnapshotAndCloseRemovesTheCache() throws Exception {
        final File file = fixture("snapshot", TYPE_USHORT, 200, 150);
        final BitmapHeightMap before = BitmapHeightMap.build().withImage(ImageIO.read(file)).now();
        final CachedImageHeightMap cached = CachedImageHeightMap.capture(file, file.getName());
        assertNotNull(cached);
        assertEquals("one private cache file", cacheFilesBefore + 1, cacheFiles());
        // Replace the file with different content, then delete it
        write(file, TYPE_USHORT, 200, 150, 7);
        Files.delete(file.toPath());
        for (int y = 0; y < 150; y++) {
            for (int x = 0; x < 200; x++) {
                assertEquals(before.getHeight(x, y), cached.getHeight(x, y), 0.0);
            }
        }
        final HeightMap clone = cached.clone();
        cached.close();
        cached.close();
        assertTrue(cached.isClosed());
        assertEquals("cache file deleted on close", cacheFilesBefore, cacheFiles());
        // Late readers (a preview still drawing) see the lowest value instead of failing
        assertEquals(cached.getMinHeight(), clone.getHeight(10, 10), 0.0);
        try {
            new java.io.ObjectOutputStream(java.io.OutputStream.nullOutputStream()).writeObject(cached);
            fail("must not be serialisable");
        } catch (java.io.NotSerializableException e) {
            // Expected
        }
    }

    @Test
    public void unsupportedAndBrokenFilesLeaveNothingBehind() throws Exception {
        // Not a TIFF, or not single band greyscale: the dense path stays in charge
        final File png = DIR.resolve("not-tiff.png").toFile();
        final BufferedImage grey = new BufferedImage(40, 30, BufferedImage.TYPE_USHORT_GRAY);
        ImageIO.write(grey, "png", png);
        assertNull(CachedImageHeightMap.capture(png, png.getName()));
        final File rgb = DIR.resolve("rgb.tif").toFile();
        writeImage(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), rgb);
        assertNull(CachedImageHeightMap.capture(rgb, rgb.getName()));
        final File text = DIR.resolve("text.tif").toFile();
        Files.writeString(text.toPath(), "not an image");
        assertNull(CachedImageHeightMap.capture(text, text.getName()));
        // A truncated TIFF fails with an I/O error and its partial cache is removed
        final File full = fixture("truncate", TYPE_USHORT, 600, 600);
        final byte[] bytes = Files.readAllBytes(full.toPath());
        final File truncated = DIR.resolve("truncated.tif").toFile();
        Files.write(truncated.toPath(), Arrays.copyOf(bytes, bytes.length / 3));
        try (CachedImageHeightMap cached = CachedImageHeightMap.capture(truncated, truncated.getName())) {
            fail("a truncated image must not be captured: " + cached);
        } catch (IOException | RuntimeException e) {
            // Expected: the error is reported, nothing is left behind (checked in tearDown())
        }
    }

    private record Result(Map<Point, Long> tiles, int nativeCalls, int[] preview) { }

    /** Convert raw samples of a bitmap to the heights its getHeight() returns (unsigned 32 bit integers). */
    private static void heights(BitmapHeightMap bitmap, double[] samples) {
        if ((! bitmap.isFloatingPoint()) && (! bitmap.isSigned()) && (bitmap.getBitDepth() == 32)) {
            for (int i = 0; i < samples.length; i++) {
                if (samples[i] < 0) {
                    samples[i] += 4294967296.0;
                }
            }
        }
    }

    private static Result importWith(HeightMap image, String variant, double[] levels) throws Exception {
        HeightMap map = image;
        switch (variant) {
            case "scaled" -> map = new TransformingHeightMap("scaled", image.smoothed(), 1.5f, 1.5f, -20, 9, 0);
            case "offset" -> map = new TransformingHeightMap("offset", image, 1, 1, -61, 37, 0);
            case "inverted" -> map = new DifferenceHeightMap(new ConstantHeightMap(levels[1]), image);
            default -> { }
        }
        final String mode = variant.equals("existing") ? "existing" : variant.equals("raise") ? "raise" : "fresh";
        final HeightMapImportBenchmark.State state = HeightMapImportBenchmark.fixture(map, mode, variant.equals("void"));
        state.importer().setImageLowLevel(levels[0]);
        state.importer().setImageHighLevel(levels[1]);
        state.importer().setVoidBelowLevel(levels[0] + (levels[1] - levels[0]) / 16);
        HeightMapImportBenchmark.random().setSeed(4242);
        state.run();
        final Map<Point, Long> tiles = new TreeMap<>(Comparator.comparingInt((Point p) -> p.y).thenComparingInt(p -> p.x));
        for (Tile tile: state.dimension().getTiles()) {
            tiles.put(new Point(tile.getX(), tile.getY()), digest(tile));
        }
        // The import test theme has a layer without renderer; the preview uses a renderable theme
        final HeightMapTileFactory previewFactory = previewTileFactory();
        state.importer().setTileFactory(previewFactory);
        state.importer().setTheme(previewFactory.getTheme());
        final org.pepsoft.util.swing.TileProvider preview = state.importer().getPreviewProvider(null, ColourScheme.DEFAULT, true, 10, TileRenderer.LightOrigin.NORTHWEST);
        assertNotNull("preview provider", preview);
        final BufferedImage previewImage = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        // The theme draws from a shared random generator. A tile may legitimately not be drawn (all void), but at least
        // one must be, and preview errors are logged and counted by the test scripts
        HeightMapImportBenchmark.random().setSeed(7);
        final boolean drawn0 = preview.paintTile(previewImage, 0, 0, 0, 0), drawn1 = preview.paintTile(previewImage, 1, 1, 128, 128),
                drawn2 = preview.paintTile(previewImage, 2, 0, 0, 128);
        assertTrue("a preview tile drawn", drawn0 || drawn1 || drawn2);
        previewImage.setRGB(255, 255, (drawn0 ? 1 : 0) | (drawn1 ? 2 : 0) | (drawn2 ? 4 : 0));
        return new Result(tiles, state.importer().getLastNativeImportCalls(), ((DataBufferInt) previewImage.getRaster().getDataBuffer()).getData().clone());
    }

    /** A tile factory with a theme the preview can render (terrain by height, frost on the peaks, resources). */
    static HeightMapTileFactory previewTileFactory() {
        final Platform platform = DefaultPlugin.JAVA_ANVIL_1_19;
        final TreeMap<Integer, Terrain> ranges = new TreeMap<>();
        ranges.put(platform.minZ - 1, Terrain.BEACHES);
        ranges.put(50, Terrain.GRASS);
        ranges.put(100, Terrain.STONE);
        final Map<org.pepsoft.worldpainter.themes.Filter, Layer> layers = new LinkedHashMap<>();
        layers.put((x, y, z, l) -> z < 100 ? 0 : z < 120 ? 7 : 15, org.pepsoft.worldpainter.layers.Frost.INSTANCE);
        final org.pepsoft.worldpainter.themes.SimpleTheme theme = new org.pepsoft.worldpainter.themes.SimpleTheme(197, 62, ranges, layers,
                platform.minZ, platform.standardMaxHeight, true, true);
        return new HeightMapTileFactory(197, new ConstantHeightMap(62), platform.minZ, platform.standardMaxHeight, false, theme);
    }

    private static long digest(Tile tile) {
        long hash = 17;
        final List<Layer> layers = new ArrayList<>(tile.getLayers());
        layers.sort(Comparator.comparing(Layer::getId));
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                hash = hash * 31 + Float.floatToIntBits(tile.getHeight(x, y));
                hash = hash * 31 + tile.getWaterLevel(x, y);
                hash = hash * 31 + tile.getTerrain(x, y).ordinal();
                for (Layer layer: layers) {
                    hash = hash * 31 + ((layer.getDataSize() == Layer.DataSize.BIT || layer.getDataSize() == Layer.DataSize.BIT_PER_CHUNK)
                            ? (tile.getBitLayerValue(layer, x, y) ? 1 : 0) : tile.getLayerValue(layer, x, y));
                }
            }
        }
        return hash * 31 + layers.toString().hashCode();
    }

    static File fixture(String name, int type, int width, int height) throws IOException {
        final File file = DIR.resolve(name + ".tif").toFile();
        write(file, type, width, height, 0);
        return file;
    }

    static void write(File file, int type, int width, int height, int variant) throws IOException {
        final ColorModel model = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), false, false, Transparency.OPAQUE, type);
        final WritableRaster raster = model.createCompatibleWritableRaster(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final double wave = Math.sin((x + variant * 13) / 23.0) * Math.cos(y / 17.0);
                switch (type) {
                    case TYPE_BYTE -> raster.setSample(x, y, 0, (int) (127.5 + 127 * wave));
                    case TYPE_USHORT -> raster.setSample(x, y, 0, (int) (32767.5 + 32767 * wave));
                    case TYPE_SHORT -> raster.setSample(x, y, 0, (int) (32767 * wave));
                    case TYPE_INT -> raster.setSample(x, y, 0, (x % 7 == 0) ? Integer.MIN_VALUE + y : (int) (2e9 * wave));
                    case TYPE_FLOAT -> raster.setSample(x, y, 0, (float) (300 * wave - 64.125));
                    default -> raster.setSample(x, y, 0, 1e5 * wave - 0.0625);
                }
            }
        }
        if ((type == TYPE_FLOAT) || (type == TYPE_DOUBLE)) {
            writeTiff(file, raster, type, false);
        } else {
            writeImage(new BufferedImage(model, raster, false, null), file);
        }
    }

    /** The raster of a TIFF file as the JDK reader returns it (it does not clamp floating point samples). */
    static Raster jdkRead(File file) throws IOException {
        try (var in = ImageIO.createImageInputStream(file)) {
            for (Iterator<javax.imageio.ImageReader> i = ImageIO.getImageReaders(in); i.hasNext(); ) {
                final javax.imageio.ImageReader reader = i.next();
                if (reader.getClass().getName().equals("com.sun.imageio.plugins.tiff.TIFFImageReader")) {
                    try {
                        reader.setInput(in, false, true);
                        return reader.read(0).getRaster();
                    } finally {
                        reader.dispose();
                    }
                }
            }
        }
        throw new IllegalStateException("no JDK TIFF reader");
    }

    /** The sample {@link #write(File, int, int, int, int)} writes at a position, as a height. */
    static double expectedHeight(int type, int x, int y, int variant) {
        final double wave = Math.sin((x + variant * 13) / 23.0) * Math.cos(y / 17.0);
        return switch (type) {
            case TYPE_BYTE -> (int) (127.5 + 127 * wave);
            case TYPE_USHORT -> (int) (32767.5 + 32767 * wave);
            case TYPE_SHORT -> (int) (32767 * wave);
            case TYPE_INT -> ((x % 7 == 0) ? Integer.MIN_VALUE + y : (int) (2e9 * wave)) & 0xffffffffL;
            case TYPE_FLOAT -> (float) (300 * wave - 64.125);
            default -> 1e5 * wave - 0.0625;
        };
    }

    /**
     * A single band TIFF of the samples of a raster as one strip, Deflate compressed or not, written directly so that
     * floating point samples are not normalised as the JDK writer does.
     */
    static void writeTiff(File file, Raster raster, int type, boolean deflate) throws IOException {
        final int width = raster.getWidth(), height = raster.getHeight();
        final int bits = switch (type) {
            case TYPE_BYTE -> 8;
            case TYPE_USHORT, TYPE_SHORT -> 16;
            case TYPE_INT, TYPE_FLOAT -> 32;
            default -> 64;
        };
        final int sampleFormat = (type == TYPE_FLOAT || type == TYPE_DOUBLE) ? 3 : (type == TYPE_SHORT) ? 2 : 1;
        final java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
        try (java.io.OutputStream out = deflate ? new java.util.zip.DeflaterOutputStream(data) : data) {
            final java.nio.ByteBuffer row = java.nio.ByteBuffer.allocate(width * bits / 8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int y = 0; y < height; y++) {
                row.clear();
                for (int x = 0; x < width; x++) {
                    switch (type) {
                        case TYPE_BYTE -> row.put((byte) raster.getSample(x, y, 0));
                        case TYPE_USHORT, TYPE_SHORT -> row.putShort((short) raster.getSample(x, y, 0));
                        case TYPE_INT -> row.putInt(raster.getSample(x, y, 0));
                        case TYPE_FLOAT -> row.putFloat(raster.getSampleFloat(x, y, 0));
                        default -> row.putDouble(raster.getSampleDouble(x, y, 0));
                    }
                }
                out.write(row.array());
            }
        }
        final int entries = 10, dataOffset = 8 + 2 + entries * 12 + 4;
        final java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(dataOffset).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        header.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8).putShort((short) entries);
        final int[][] fields = {{256, 4, width}, {257, 4, height}, {258, 3, bits}, {259, 3, deflate ? 8 : 1}, {262, 3, 1},
                {273, 4, dataOffset}, {277, 3, 1}, {278, 4, height}, {279, 4, data.size()}, {339, 3, sampleFormat}};
        for (int[] field: fields) {
            header.putShort((short) field[0]).putShort((short) field[1]).putInt(1);
            if (field[1] == 3) {
                header.putShort((short) field[2]).putShort((short) 0);
            } else {
                header.putInt(field[2]);
            }
        }
        header.putInt(0);
        Files.deleteIfExists(file.toPath());
        try (java.io.OutputStream out = Files.newOutputStream(file.toPath())) {
            out.write(header.array());
            data.writeTo(out);
        }
    }

    private static void writeImage(BufferedImage image, File file) throws IOException {
        writeImage(image, file, null);
    }

    private static void writeImage(BufferedImage image, File file, String compression) throws IOException {
        ImageWriter writer = null;
        for (Iterator<ImageWriter> i = ImageIO.getImageWritersByFormatName("tiff"); i.hasNext(); ) {
            final ImageWriter candidate = i.next();
            if (candidate.getClass().getName().startsWith("com.sun")) {
                writer = candidate;
            }
        }
        Files.deleteIfExists(file.toPath());
        try (var out = ImageIO.createImageOutputStream(file)) {
            writer.setOutput(out);
            final javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
            if (compression != null) {
                param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionType(compression);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static long cacheFiles() throws IOException {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(path -> path.getFileName().toString().startsWith("welt-heightmap-")).count();
        }
    }

    private Configuration previousConfiguration;
    private String previousGen;
    private long cacheFilesBefore;

    static final Path DIR = Path.of("target", "heightmap-import-work", "test");
}
