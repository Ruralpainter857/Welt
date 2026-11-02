package org.pepsoft.worldpainter;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.layers.Biome;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.*;
import static org.pepsoft.worldpainter.Dimension.Anchor.NORMAL_DETAIL;

/**
 * Real save/load cycle through {@link WorldIO} with the parallel compressor and with the original single threaded
 * one: complete canonical comparison of the world graph, cross reading of both kinds of files, serial version UIDs,
 * no transient undo state in the stream, truncated and invalid files, and release of the compression threads. Files
 * are written under {@code target/world-io/test} only.
 */
public class WorldIoRoundTripTest {
    private MixedMaterial originalCustomMaterial;
    @Before public void rememberCustomMaterial() { originalCustomMaterial = Terrain.getCustomMaterial(0); }
    @After public void restoreCustomMaterial() { Terrain.setCustomMaterial(0, originalCustomMaterial); }

    @Test
    public void layeredWorldRoundTripsWithBothCompressors() throws Exception {
        final World2 world = WorldIoFixtures.create("layers", 8);
        final Path parallel = file("layers-parallel.world"), plain = file("layers-plain.world");
        save(world, parallel, true);
        final long expected = WorldCanonicalDigest.of(world); // After prepareForSaving() side effects
        save(world, plain, false);
        assertEquals(expected, WorldCanonicalDigest.of(world));
        // Each reader reads both files (the loader is the same code for both formats, which are both plain gzip)
        assertEquals("parallel file", expected, WorldCanonicalDigest.of(load(parallel)));
        assertEquals("plain file", expected, WorldCanonicalDigest.of(load(plain)));
        // The uncompressed streams are the same apart from the metadata timestamp
        final byte[] parallelRaw = gunzip(parallel), plainRaw = gunzip(plain);
        assertEquals(plainRaw.length, parallelRaw.length);
        assertTrue("streams differ beyond the timestamp", differingBytes(parallelRaw, plainRaw) <= 8);
    }

    /**
     * Worlds saved by older WorldPainter versions (read only). Loading such a file normalises some values (as the
     * reference code does too), so the logical comparison starts after one save/load: from there both compressors
     * write the same object stream, and save/load is stable.
     */
    @Test
    public void legacyWorldFilesRoundTrip() throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null && !Files.isDirectory(root.resolve("WorldPainter/WPCore/src/test/resources"))) root = root.getParent();
        assertNotNull("Repository root is required for legacy world compatibility tests", root);
        final Path[] legacy = {root.resolve("WorldPainter/WPCore/src/test/resources/testset/test-v2.3.6-1.world"),
                root.resolve("WorldPainter/WPGUI/src/test/resources/Generated World.world")};
        for (Path source: legacy) {
            assertTrue("Legacy fixture missing: " + source.getFileName(), Files.isRegularFile(source));
            final String name = source.getFileName().toString().replace(' ', '_');
            final World2 world = load(source);
            final Path parallel = file("legacy-parallel-" + name), plain = file("legacy-plain-" + name);
            save(world, parallel, true);
            save(world, plain, false);
            final byte[] parallelRaw = gunzip(parallel), plainRaw = gunzip(plain);
            assertEquals(name, plainRaw.length, parallelRaw.length);
            assertTrue(name + ": streams differ beyond the timestamp", differingBytes(parallelRaw, plainRaw) <= 8);
            final World2 normalised = load(parallel);
            assertEquals(name + ": plain file", WorldCanonicalDigest.of(normalised), WorldCanonicalDigest.of(load(plain)));
            final Path again = file("legacy-again-" + name);
            save(normalised, again, true);
            assertEquals(name + ": second round trip", WorldCanonicalDigest.of(normalised), WorldCanonicalDigest.of(load(again)));
        }
    }

    @Test
    public void digestDetectsASingleChangedCell() throws Exception {
        final World2 world = WorldIoFixtures.create("noise", 4);
        final Path path = file("noise-mutation.world");
        save(world, path, true);
        final World2 loaded = load(path);
        final long before = WorldCanonicalDigest.of(loaded);
        final Tile tile = loaded.getDimension(NORMAL_DETAIL).getTile(-1, -1);
        tile.setRawHeight(77, 3, tile.getRawHeight(77, 3) + 1);
        assertNotEquals(before, WorldCanonicalDigest.of(loaded));
    }

    @Test
    public void editedWorldWithUndoHistorySavesCurrentStateOnly() throws Exception {
        final World2 world = WorldIoFixtures.create("noise", 4);
        final Dimension dimension = world.getDimension(NORMAL_DETAIL);
        final UndoManager undoManager = new UndoManager(10);
        dimension.registerUndoManager(undoManager);
        for (int edit = 0; edit < 5; edit++) {
            undoManager.armSavePoint();
            final Tile tile = dimension.getTile(edit % 2 - 1, 0);
            tile.setLayerValue(Biome.INSTANCE, edit, edit, 10 + edit);
            tile.setHeight(edit, 0, 70 + edit);
        }
        assertTrue(undoManager.undo()); // A history frame is current while saving
        final Path path = file("noise-undo.world");
        save(world, path, true);
        final long expected = WorldCanonicalDigest.of(world);
        dimension.unregisterUndoManager();
        assertEquals(expected, WorldCanonicalDigest.of(load(path)));
        final String raw = new String(gunzip(path), StandardCharsets.ISO_8859_1);
        for (String forbidden: new String[] {"org.pepsoft.util.undo", "TileHistoryStorage", "UndoManager", "PropertyChangeSupport", "ParallelGzip"}) {
            assertFalse("transient state serialised: " + forbidden, raw.contains(forbidden));
        }
    }

    @Test
    public void serialVersionUidsAreUnchanged() {
        assertEquals(2011062401L, ObjectStreamClass.lookup(World2.class).getSerialVersionUID());
        assertEquals(2011062401L, ObjectStreamClass.lookup(Dimension.class).getSerialVersionUID());
        assertEquals(2011040101L, ObjectStreamClass.lookup(Tile.class).getSerialVersionUID());
    }

    @Test
    public void truncatedAndInvalidFilesFailUnderstandably() throws Exception {
        final World2 world = WorldIoFixtures.create("noise", 4);
        final Path path = file("noise-truncate.world");
        save(world, path, true);
        final byte[] bytes = Files.readAllBytes(path);
        // Cut in the trailer only: the object stream stops reading once the world is read, before gzip checks the
        // trailer, so such a file loads; that must not depend on the compressor
        final Path plain = file("noise-truncate-plain.world");
        save(world, plain, false);
        final byte[] plainBytes = Files.readAllBytes(plain);
        assertEquals("trailer truncation behaves as before", loads(Arrays.copyOf(plainBytes, plainBytes.length - 4)),
                loads(Arrays.copyOf(bytes, bytes.length - 4)));
        for (int length: new int[] {0, 5, 100, bytes.length / 2, bytes.length - 2000}) {
            final Path truncated = file("truncated-" + length + ".world");
            Files.write(truncated, Arrays.copyOf(bytes, length));
            try {
                load(truncated);
                fail("truncated file of " + length + " bytes loaded");
            } catch (UnloadableWorldException e) {
                assertNotNull(e.getMessage());
            } catch (IOException e) {
                // Accepted, as before: plain I/O failures are not wrapped
                assertNotNull(e.getMessage());
            }
        }
        final Path invalid = file("invalid.world");
        Files.write(invalid, "not a world".getBytes(StandardCharsets.US_ASCII));
        try {
            load(invalid);
            fail("invalid file loaded");
        } catch (UnloadableWorldException e) {
            // As before: GZIPInputStream reports this as a ZipException
            assertEquals("ZipException while loading world", e.getMessage());
            assertEquals("Not in GZIP format", e.getCause().getMessage());
        }
    }

    @Test
    public void failedSaveReportsErrorAndReleasesThreads() throws Exception {
        final World2 world = WorldIoFixtures.create("noise", 4);
        final int before = ParallelGzipOutputStreamTest.gzipThreads();
        final ParallelGzipOutputStreamTest.FailingStream sink = new ParallelGzipOutputStreamTest.FailingStream(200_000);
        try {
            new WorldIO(world).save(sink);
            fail("save to a failing stream succeeded");
        } catch (IOException e) {
            // Expected
        }
        if (! "false".equals(System.getProperty("welt.worldio.parallelGzip"))) {
            // The JDK's GZIPOutputStream does not close the underlying stream when finishing fails; the parallel
            // compressor does
            assertTrue(sink.closed);
        }
        ParallelGzipOutputStreamTest.waitForThreads(before);
        // The world is unaffected and still saves
        final Path path = file("noise-after-failure.world");
        save(world, path, true);
        assertEquals(WorldCanonicalDigest.of(world), WorldCanonicalDigest.of(load(path)));
        ParallelGzipOutputStreamTest.waitForThreads(before);
    }

    static void save(World2 world, Path path, boolean parallel) throws IOException {
        final String previous = System.getProperty("welt.worldio.parallelGzip");
        System.setProperty("welt.worldio.parallelGzip", Boolean.toString(parallel && ! "false".equals(previous)));
        try {
            new WorldIO(world).save(new FileOutputStream(path.toFile()));
        } finally {
            if (previous == null) {
                System.clearProperty("welt.worldio.parallelGzip");
            } else {
                System.setProperty("welt.worldio.parallelGzip", previous);
            }
        }
    }

    static World2 load(Path path) throws IOException, UnloadableWorldException {
        final WorldIO io = new WorldIO();
        io.load(new FileInputStream(path.toFile()));
        return io.getWorld();
    }

    private static boolean loads(byte[] bytes) throws IOException {
        final Path path = file("truncated-trailer.world");
        Files.write(path, bytes);
        try {
            load(path);
            return true;
        } catch (UnloadableWorldException | IOException e) {
            return false;
        }
    }

    private static byte[] gunzip(Path path) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(path))) {
            return in.readAllBytes();
        }
    }

    private static int differingBytes(byte[] a, byte[] b) {
        int count = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                count++;
            }
        }
        return count;
    }

    private static Path file(String name) throws IOException {
        final Path dir = Path.of("target", "world-io", "test");
        Files.createDirectories(dir);
        return dir.resolve(name);
    }
}
