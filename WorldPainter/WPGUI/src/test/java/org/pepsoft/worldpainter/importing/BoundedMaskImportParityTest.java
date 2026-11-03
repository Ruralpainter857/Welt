package org.pepsoft.worldpainter.importing;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.pepsoft.util.ProgressReceiver;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.heightMaps.CachedImageHeightMap;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.awt.image.DataBuffer.*;
import static org.junit.Assert.*;

/**
 * Masks imported from the bounded TIFF capture ({@link MaskImporter#bounded}) against the old scalar path
 * ({@link ScalarMaskImporter} on the image as {@code ImageIO.read} loads it) and the dense native path: every target and
 * mapping kind of the existing parity tests, scales with bicubic resampling, removal of existing layers, negative
 * offsets and tiles missing from the dimension, with the native transaction counters of each path; 8, 16 and 32 bit
 * and floating point images; explicit dense fallbacks; cancellation and a new import from the same capture; and no cache
 * file left behind. Fixtures are written under {@code target/mask-import-work/test}.
 */
public class BoundedMaskImportParityTest {
    @Before
    public void setUp() throws IOException {
        previousGeneration = System.getProperty(Native.GEN_KEY);
        Files.createDirectories(DIR);
        cacheFilesBefore = cacheFiles();
    }

    @After
    public void tearDown() throws IOException {
        if (previousGeneration == null) {
            System.clearProperty(Native.GEN_KEY);
        } else {
            System.setProperty(Native.GEN_KEY, previousGeneration);
        }
        assertEquals("cache files left behind", cacheFilesBefore, cacheFiles());
    }

    @Test
    public void integerMasksMatchTheScalarAndDensePathsForEveryTarget() throws Exception {
        // The scenarios and images of MaskImportParityTest (colour is not a greyscale TIFF and stays dense)
        for (String scenario: new String[] {"terrain", "terrain-index", "discrete", "fixed", "bit", "chunk", "direct", "continuous"}) {
            final boolean small = scenario.equals("terrain-index") || scenario.equals("discrete") || scenario.equals("direct");
            final int type = small ? TYPE_BYTE : TYPE_USHORT;
            final File file = DIR.resolve(scenario + ".tif").toFile();
            writeTiff(file, type, 139, 139, (x, y) -> {
                final int value = (x * 971 + y * 313 + x * y * 17) & 65535;
                return scenario.equals("terrain-index") ? value % Terrain.VALUES.length : small ? value % 16 : value;
            });
            compare(file, type, scenario, Double.NaN);
        }
    }

    @Test
    public void thirtyTwoBitAndFloatingPointMasksMatchTheScalarPath() throws Exception {
        // Unsigned 32 bit samples of 2^31 and more (negative in the raster)
        final File int32 = DIR.resolve("int32.tif").toFile();
        writeTiff(int32, TYPE_INT, 139, 139, (x, y) -> (int) (((x * 971 + y * 313 + x * y * 17) & 65535) * 65537L));
        for (String scenario: new String[] {"continuous", "bit", "chunk", "terrain"}) {
            compare(int32, TYPE_INT, scenario, 2147483648.0);
        }
        // Floating point: TwelveMonkeys returns the samples clamped to 0..1, as for the dense path; zeros of both signs
        final File float32 = DIR.resolve("float32.tif").toFile();
        writeTiff(float32, TYPE_FLOAT, 139, 139, (x, y) -> Float.floatToRawIntBits((x == 0) ? -0.0f : ((x * 971 + y * 313 + x * y * 17) & 65535) / 65535f));
        for (String scenario: new String[] {"continuous", "bit", "chunk", "terrain", "fixed"}) {
            compare(float32, TYPE_FLOAT, scenario, 0.5);
        }
        // A 32 bit mask entirely at or above 2^31: the range of a mask starts from Integer.MAX_VALUE, so its low value
        // is 2147483647 although no sample has it; the bounded path keeps that
        final File high = DIR.resolve("int32-high.tif").toFile();
        writeTiff(high, TYPE_INT, 60, 50, (x, y) -> Integer.MIN_VALUE + x * 1000 + y);
        try (CachedImageHeightMap capture = CachedImageHeightMap.capture(high, high.getName())) {
            final MaskImporter dense = new MaskImporter(null, high, ImageIO.read(high)), bounded = MaskImporter.bounded(null, high, capture);
            assertEquals(2147483647.0, dense.getImageLowValue(), 0.0);
            assertEquals(dense.getImageLowValue(), bounded.getImageLowValue(), 0.0);
            assertEquals(dense.getImageHighValue(), bounded.getImageHighValue(), 0.0);
        }
    }

    @Test
    public void unsupportedMasksKeepTheDensePath() throws Exception {
        // Signed 16 bit: a mask ranges over unsigned samples, which the capture range cannot give
        final File signed = DIR.resolve("signed16.tif").toFile();
        writeTiff(signed, TYPE_SHORT, 40, 30, (x, y) -> x * 100 - 1500);
        try (CachedImageHeightMap capture = CachedImageHeightMap.capture(signed, signed.getName())) {
            assertNotNull("signed 16 bit is captured as a height map", capture);
            assertNull("but not imported as a mask", MaskImporter.bounded(null, signed, capture));
        }
        // (Floating point reaching the type bounds is declined too, but a float TIFF read by TwelveMonkeys is clamped to
        // 0..1 and never does.) Colour, PNG and other formats are not captured at all
        final File colour = DIR.resolve("colour.tif").toFile();
        final BufferedImage rgb = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(rgb, "TIFF", colour));
        assertNull(CachedImageHeightMap.capture(colour, colour.getName()));
        final File png = DIR.resolve("grey.png").toFile();
        assertTrue(ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_USHORT_GRAY), "PNG", png));
        assertNull(CachedImageHeightMap.capture(png, png.getName()));
        // A closed capture is not imported
        final File grey = DIR.resolve("closed.tif").toFile();
        writeTiff(grey, TYPE_USHORT, 40, 30, (x, y) -> x + y);
        final CachedImageHeightMap closed = CachedImageHeightMap.capture(grey, grey.getName());
        closed.close();
        assertNull(MaskImporter.bounded(null, grey, closed));
        // The flag is distinct from that of height maps and off by default
        if (System.getProperty("welt.import.boundedMaskTiff") == null) {
            assertFalse(MaskImporter.isBoundedEnabled());
        }
        final String previous = System.setProperty("welt.import.boundedTiff", "true");
        try {
            assertEquals("the height map flag does not enable masks", Boolean.getBoolean("welt.import.boundedMaskTiff"), MaskImporter.isBoundedEnabled());
        } finally {
            if (previous == null) {
                System.clearProperty("welt.import.boundedTiff");
            } else {
                System.setProperty("welt.import.boundedTiff", previous);
            }
        }
    }

    @Test
    public void aCancelledImportAndANewImportFromTheSameCapture() throws Exception {
        final File file = DIR.resolve("cancel.tif").toFile();
        writeTiff(file, TYPE_USHORT, 300, 300, (x, y) -> (x * 977 + y * 131) & 0xffff);
        final BufferedImage image = ImageIO.read(file);
        try (CachedImageHeightMap capture = CachedImageHeightMap.capture(file, file.getName())) {
            System.setProperty(Native.GEN_KEY, Boolean.toString(NativeLoader.areSlicesAvailable()));
            final Dimension cancelled = MaskImportBenchmark.fixture(300, 300, -31, -47);
            final MaskImporter first = MaskImporter.bounded(cancelled, file, capture);
            MaskImportBenchmark.configure(first, "continuous", 1, true);
            final int[] calls = new int[1];
            try {
                first.doImport((ProgressReceiver) java.lang.reflect.Proxy.newProxyInstance(ProgressReceiver.class.getClassLoader(), new Class<?>[] {ProgressReceiver.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("setProgress") && (++calls[0] == 4)) {
                                throw new ProgressReceiver.OperationCancelledByUser();
                            }
                            return null;
                        }));
                fail("the import must be cancelled");
            } catch (ProgressReceiver.OperationCancelled e) {
                // Expected
            }
            assertFalse("the importer only borrows the capture", capture.isClosed());
            // Two more imports from the same immutable capture, as successive operations of the dialog would do
            for (int i = 0; i < 2; i++) {
                final Dimension expected = MaskImportBenchmark.fixture(300, 300, -31, -47), actual = MaskImportBenchmark.fixture(300, 300, -31, -47);
                final MaskImporter dense = new MaskImporter(expected, file, image);
                MaskImportBenchmark.configure(dense, "continuous", 1, true);
                dense.doImport(null);
                final MaskImporter bounded = MaskImporter.bounded(actual, file, capture);
                MaskImportBenchmark.configure(bounded, "continuous", 1, true);
                bounded.doImport(null);
                MaskImportParityTest.same(expected, actual);
                assertEquals(dense.getLastNativeImportCalls(), bounded.getLastNativeImportCalls());
            }
        }
    }

    /**
     * Import a mask file in every configuration of a scenario and compare: the scalar path on the dense image (the
     * reference), the dense native path, and the bounded path with Java and with native transactions.
     */
    private void compare(File file, int type, String scenario, double threshold) throws Exception {
        final BufferedImage image = ImageIO.read(file);
        assertEquals("dense transfer type of " + file.getName(), type, image.getRaster().getTransferType());
        try (CachedImageHeightMap capture = CachedImageHeightMap.capture(file, file.getName())) {
            assertNotNull("captured: " + file.getName(), capture);
            for (float scale: new float[] {1, .75f, 1.5f}) {
                for (boolean remove: new boolean[] {false, true}) {
                    final String label = file.getName() + " " + scenario + " scale " + scale + " remove " + remove;
                    final int width = Math.round(image.getWidth() * scale), height = Math.round(image.getHeight() * scale);
                    System.setProperty(Native.GEN_KEY, "false");
                    final Dimension expected = MaskImportBenchmark.fixture(width, height, -31, -47);
                    final ScalarMaskImporter scalar = new ScalarMaskImporter(expected, file, image);
                    MaskImportBenchmark.configure(scalar, scenario, scale, remove);
                    if (! Double.isNaN(threshold)) {
                        scalar.setThreshold(threshold);
                    }
                    final List<Float> expectedProgress = new ArrayList<>();
                    scalar.doImport(progress(expectedProgress));
                    for (String mode: new String[] {"dense native", "bounded java", "bounded native"}) {
                        final boolean nativeImport = mode.endsWith("native");
                        if (nativeImport && (! NativeLoader.areSlicesAvailable())) {
                            continue;
                        }
                        System.setProperty(Native.GEN_KEY, Boolean.toString(nativeImport));
                        final Dimension actual = MaskImportBenchmark.fixture(width, height, -31, -47);
                        final MaskImporter importer = mode.startsWith("dense") ? new MaskImporter(actual, file, image) : MaskImporter.bounded(actual, file, capture);
                        assertNotNull(label + " " + mode, importer);
                        final MaskImporter reference = new MaskImporter(null, file, image);
                        assertEquals(label + " input type", reference.getInputType(), importer.getInputType());
                        assertEquals(label + " low", Double.doubleToRawLongBits(reference.getImageLowValue()), Double.doubleToRawLongBits(importer.getImageLowValue()));
                        assertEquals(label + " high", Double.doubleToRawLongBits(reference.getImageHighValue()), Double.doubleToRawLongBits(importer.getImageHighValue()));
                        assertEquals(label + " max", reference.getImageMaxValue(), importer.getImageMaxValue(), 0.0);
                        assertEquals(label + " scaling", reference.getScalingNotSupportedReason(), importer.getScalingNotSupportedReason());
                        MaskImportBenchmark.configure(importer, scenario, scale, remove);
                        MaskImportBenchmark.configure(reference, scenario, scale, remove);
                        assertEquals(label + " possible mappings", descriptions(reference.getPossibleMappings()), descriptions(importer.getPossibleMappings()));
                        if (! Double.isNaN(threshold)) {
                            importer.setThreshold(threshold);
                        }
                        final List<Float> actualProgress = new ArrayList<>();
                        importer.doImport(progress(actualProgress));
                        assertEquals(label + " " + mode + " progress", expectedProgress, actualProgress);
                        MaskImportParityTest.same(expected, actual);
                        // Plans actually executed: one transaction per existing tile natively, none otherwise
                        assertEquals(label + " " + mode + " native transactions", nativeImport ? actual.getTileCount() : 0, importer.getLastNativeImportCalls());
                        assertFalse(label + " capture still owned by the caller", capture.isClosed());
                    }
                }
            }
        }
    }

    private static String descriptions(MaskImporter.PossibleMappingsResult result) {
        return result.mappings.stream().map(Mapping::getDescription).sorted().collect(Collectors.joining("|")) + "/" + result.reason;
    }

    private static ProgressReceiver progress(List<Float> values) {
        return (ProgressReceiver) java.lang.reflect.Proxy.newProxyInstance(ProgressReceiver.class.getClassLoader(), new Class<?>[] {ProgressReceiver.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setProgress")) {
                        values.add((Float) args[0]);
                    }
                    return null;
                });
    }

    public interface Samples {
        /** The raw sample at a position: the integer sample, or the bits of a float. */
        int at(int x, int y);
    }

    /**
     * A single band TIFF as one uncompressed strip, written directly so that floating point samples are not normalised as
     * the JDK writer does and signed 16 bit samples are flagged as such.
     */
    public static void writeTiff(File file, int type, int width, int height, Samples samples) throws IOException {
        final int bits = (type == TYPE_BYTE) ? 8 : ((type == TYPE_USHORT) || (type == TYPE_SHORT)) ? 16 : 32;
        final int sampleFormat = (type == TYPE_FLOAT) ? 3 : (type == TYPE_SHORT) ? 2 : 1;
        final ByteBuffer data = ByteBuffer.allocate(width * height * bits / 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int sample = samples.at(x, y);
                switch (bits) {
                    case 8 -> data.put((byte) sample);
                    case 16 -> data.putShort((short) sample);
                    default -> data.putInt(sample);
                }
            }
        }
        final int entries = 10, dataOffset = 8 + 2 + entries * 12 + 4;
        final ByteBuffer header = ByteBuffer.allocate(dataOffset).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8).putShort((short) entries);
        final int[][] fields = {{256, 4, width}, {257, 4, height}, {258, 3, bits}, {259, 3, 1}, {262, 3, 1},
                {273, 4, dataOffset}, {277, 3, 1}, {278, 4, height}, {279, 4, data.capacity()}, {339, 3, sampleFormat}};
        for (int[] field: fields) {
            header.putShort((short) field[0]).putShort((short) field[1]).putInt(1);
            if (field[1] == 3) {
                header.putShort((short) field[2]).putShort((short) 0);
            } else {
                header.putInt(field[2]);
            }
        }
        header.putInt(0);
        try (var out = Files.newOutputStream(file.toPath())) {
            out.write(header.array());
            out.write(data.array());
        }
    }

    private static long cacheFiles() throws IOException {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(path -> path.getFileName().toString().startsWith("welt-heightmap-")).count();
        }
    }

    private String previousGeneration;
    private long cacheFilesBefore;

    static final Path DIR = Path.of("target", "mask-import-work", "test");
}
