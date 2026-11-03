package org.pepsoft.worldpainter;

import org.junit.BeforeClass;
import org.junit.Test;
import org.pepsoft.worldpainter.heightMaps.CachedImageHeightMap;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static java.awt.image.DataBuffer.TYPE_USHORT;
import static org.junit.Assert.*;

/**
 * The cancellable capture of {@link CachedImageHeightMap#capture(File, String, BooleanSupplier)}: identical to the
 * uncancellable one when not cancelled, and when cancelled before or during the capture it stops without decoding the
 * rest of the image and leaves no cache file behind.
 */
public class BoundedTiffCaptureCancellationTest {
    @BeforeClass public static void fixtures() throws IOException {
        Files.createDirectories(BoundedTiffImportParityTest.DIR);
        // 70000 columns of 16 bits: decoded in four bands of at most 64 rows
        wide = BoundedTiffImportParityTest.fixture("cancellable", TYPE_USHORT, WIDTH, HEIGHT);
    }

    @Test public void anUncancelledCaptureIsIdenticalToTheUncancellableOne() throws Exception {
        final CountingSupplier never = new CountingSupplier(Integer.MAX_VALUE);
        try (CachedImageHeightMap cancellable = CachedImageHeightMap.capture(wide, wide.getName(), never);
             CachedImageHeightMap reference = CachedImageHeightMap.capture(wide, wide.getName())) {
            assertNotNull(cancellable);
            assertNotNull(reference);
            assertArrayEquals(reference.getRange(), cancellable.getRange(), 0.0);
            assertEquals(reference.getBitDepth(), cancellable.getBitDepth());
            final double[] expected = new double[WIDTH], actual = new double[WIDTH];
            for (int y = 0; y < HEIGHT; y++) {
                reference.readRegion(0, y, WIDTH, 1, expected, 0, WIDTH);
                cancellable.readRegion(0, y, WIDTH, 1, actual, 0, WIDTH);
                assertArrayEquals("row " + y, expected, actual, 0.0);
            }
        }
        // Polled once before the capture, and before and after each of the four bands at least
        assertTrue("polls: " + never.polls.get(), never.polls.get() >= 9);
    }

    @Test public void aCaptureCancelledBeforeItStartsLeavesNothing() throws Exception {
        final long before = cacheFiles();
        final CountingSupplier always = new CountingSupplier(0);
        try {
            CachedImageHeightMap.capture(wide, wide.getName(), always);
            fail("not cancelled");
        } catch (CancellationException e) {
            // Expected
        }
        assertEquals("polled once, before creating the cache", 1, always.polls.get());
        assertEquals("no cache file left", before, cacheFiles());
    }

    @Test public void aCaptureCancelledDuringItStopsAndLeavesNothing() throws Exception {
        // The number of polls of a complete capture, without relying on the order of the tests
        final CountingSupplier never = new CountingSupplier(Integer.MAX_VALUE);
        try (CachedImageHeightMap heightMap = CachedImageHeightMap.capture(wide, wide.getName(), never)) {
            assertNotNull(heightMap);
        }
        final int total = never.polls.get();
        final long before = cacheFiles();
        // Cancelled after the first band, then halfway
        for (int cancelAt: new int[] {4, total / 2}) {
            final CountingSupplier cancelled = new CountingSupplier(cancelAt);
            try {
                CachedImageHeightMap.capture(wide, wide.getName(), cancelled);
                fail("not cancelled at poll " + cancelAt);
            } catch (CancellationException e) {
                // Expected
            }
            assertTrue("stopped promptly after poll " + cancelAt + " (of " + total + "): " + cancelled.polls.get(), cancelled.polls.get() < total);
            assertEquals("no cache file left", before, cacheFiles());
        }
    }

    /** Becomes (and stays) true from the given number of polls on. */
    private static final class CountingSupplier implements BooleanSupplier {
        CountingSupplier(int cancelAt) {
            this.cancelAt = cancelAt;
        }

        @Override
        public boolean getAsBoolean() {
            return polls.getAndIncrement() >= cancelAt;
        }

        final int cancelAt;
        final AtomicInteger polls = new AtomicInteger();
    }

    private static long cacheFiles() throws IOException {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(path -> path.getFileName().toString().startsWith("welt-heightmap-")).count();
        }
    }

    private static final int WIDTH = 70000, HEIGHT = 200;
    private static File wide;
}
