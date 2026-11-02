package org.pepsoft.worldpainter;

import org.junit.Test;

import java.io.*;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.*;

import static org.junit.Assert.*;
import static org.pepsoft.worldpainter.ParallelGzipOutputStream.BLOCK_SIZE;

public class ParallelGzipOutputStreamTest {
    @Test
    public void roundTripsAtBlockBoundaries() throws IOException {
        final int[] sizes = {0, 1, 1000, BLOCK_SIZE - 1, BLOCK_SIZE, BLOCK_SIZE + 1, 2 * BLOCK_SIZE, 3 * BLOCK_SIZE + 12345};
        for (int threads: new int[] {1, 2, 5}) {
            for (int size: sizes) {
                for (boolean compressible: new boolean[] {true, false}) {
                    final byte[] data = data(size, compressible, size * 31L + threads);
                    for (int chunk: new int[] {1, 7, 4096, Integer.MAX_VALUE}) {
                        if ((chunk == 1) && (size > BLOCK_SIZE + 1)) {
                            continue; // Byte by byte is covered by the smaller sizes
                        }
                        final byte[] gzip = compress(data, threads, chunk);
                        final String label = "threads=" + threads + " size=" + size + " compressible=" + compressible + " chunk=" + chunk;
                        assertArrayEquals(label, data, gunzip(gzip));
                        assertSingleStandardMember(label, data, gzip);
                    }
                }
            }
        }
    }

    @Test
    public void headerAndSizeMatchGzipOutputStream() throws IOException {
        final byte[] data = data(5 * BLOCK_SIZE + 999, true, 7);
        final ByteArrayOutputStream reference = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(reference)) {
            out.write(data);
        }
        final byte[] parallel = compress(data, 4, 65536);
        assertArrayEquals(Arrays.copyOf(reference.toByteArray(), 10), Arrays.copyOf(parallel, 10));
        // Same level; independent blocks primed with a dictionary cost very little
        assertTrue("size " + parallel.length + " vs " + reference.size(), parallel.length <= reference.size() * 1.01 + 64);
    }

    @Test
    public void writeFailureIsReportedAndReleasesThreads() throws Exception {
        final int before = gzipThreads();
        final FailingStream sink = new FailingStream(BLOCK_SIZE / 4);
        final ParallelGzipOutputStream out = new ParallelGzipOutputStream(sink, 3);
        IOException failure = null;
        try {
            out.write(data(6 * BLOCK_SIZE, false, 3));
        } catch (IOException e) {
            failure = e;
        }
        try {
            out.close();
        } catch (IOException e) {
            failure = (failure == null) ? e : failure;
        }
        assertNotNull("the failure must be reported", failure);
        assertTrue("underlying stream must be closed", sink.closed);
        waitForThreads(before);
        try {
            out.write(1);
            fail("write after close must fail");
        } catch (IOException e) {
            // Expected
        }
    }

    @Test
    public void closeReleasesThreadsAndClosesUnderlyingStream() throws Exception {
        final int before = gzipThreads();
        final FailingStream sink = new FailingStream(Integer.MAX_VALUE);
        try (ParallelGzipOutputStream out = new ParallelGzipOutputStream(sink, 4)) {
            out.write(data(3 * BLOCK_SIZE, true, 11));
        }
        assertTrue(sink.closed);
        waitForThreads(before);
    }

    @Test
    public void workerFailureIsReportedAndCloseIsRepeatable() throws Exception {
        final int before = gzipThreads();
        final FailingStream sink = new FailingStream(Integer.MAX_VALUE);
        final ParallelGzipOutputStream out = new ParallelGzipOutputStream(sink, 3, () -> new Deflater(Deflater.DEFAULT_COMPRESSION, true) {
            @Override
            public int deflate(byte[] b, int off, int len, int flush) {
                throw new IllegalStateException("Simulated compression failure");
            }
        });
        IOException failure = null;
        try {
            out.write(data(8 * BLOCK_SIZE, true, 5));
        } catch (IOException e) {
            failure = e;
        }
        try {
            out.close();
        } catch (IOException e) {
            failure = (failure == null) ? e : failure;
        }
        assertNotNull("the worker failure must be reported", failure);
        assertTrue(failure.getMessage(), failure.getCause() instanceof IllegalStateException);
        assertTrue(sink.closed);
        waitForThreads(before);
        out.close(); // Repeated close is a no-op
        out.close();
    }

    @Test
    public void closingAnEmptyStreamTwiceWritesOneValidMember() throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final ParallelGzipOutputStream out = new ParallelGzipOutputStream(bytes, 2);
        out.close();
        out.close();
        assertArrayEquals(new byte[0], gunzip(bytes.toByteArray()));
        assertSingleStandardMember("empty", new byte[0], bytes.toByteArray());
    }

    /**
     * The start of a block repeating the end of the previous one compresses only if the dictionary is used (zlib
     * matches at most 32 KiB - 262 bytes back, so the period is 16 KiB).
     */
    @Test
    public void blocksArePrimedWithThePrevious32KiB() throws IOException {
        final byte[] tail = data(16 * 1024, false, 13);
        final byte[] data = new byte[2 * BLOCK_SIZE];
        final byte[] first = data(BLOCK_SIZE, false, 17);
        System.arraycopy(first, 0, data, 0, BLOCK_SIZE);
        System.arraycopy(tail, 0, data, BLOCK_SIZE - tail.length, tail.length);
        for (int off = BLOCK_SIZE; off < data.length; off += tail.length) {
            System.arraycopy(tail, 0, data, off, Math.min(tail.length, data.length - off));
        }
        final byte[] gzip = compress(data, 2, 65536);
        assertArrayEquals(data, gunzip(gzip));
        assertSingleStandardMember("dictionary", data, gzip);
        // The incompressible first block costs ~512 KiB; the second, made of copies of the first one's last 16 KiB,
        // a few hundred bytes with the dictionary, over 16 KiB without
        assertTrue("second block not primed: " + gzip.length, gzip.length < BLOCK_SIZE + 8 * 1024);
    }

    /** A closed stream that is still referenced keeps no block buffers, deflaters or pending work. */
    @Test
    public void closedStreamStillReferencedReleasesItsBuffers() throws Exception {
        final ParallelGzipOutputStream out = new ParallelGzipOutputStream(new ByteArrayOutputStream(), 3);
        out.write(data(5 * BLOCK_SIZE + 77, true, 19));
        out.close();
        assertNull(field(out, "input"));
        assertNull(field(out, "previousTail"));
        for (String name: new String[] {"inputs", "outputs", "pending", "deflaters"}) {
            assertTrue(name + " not emptied", ((java.util.Collection<?>) field(out, name)).isEmpty());
        }
        assertNotNull(out); // Keep the closed stream reachable up to here
    }

    /**
     * A worker still compressing when the (interrupted) caller closes the stream ends its deflater itself when it
     * finishes; every deflater is ended exactly once.
     */
    @Test
    public void lateWorkerEndsItsDeflaterAfterInterruptedClose() throws Exception {
        final int before = gzipThreads();
        final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean blockOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        final java.util.List<CountingDeflater> created = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final FailingStream sink = new FailingStream(Integer.MAX_VALUE);
        final ParallelGzipOutputStream out = new ParallelGzipOutputStream(sink, 2, () -> {
            final CountingDeflater deflater = new CountingDeflater() {
                @Override
                public int deflate(byte[] b, int off, int len, int flush) {
                    if (blockOnce.getAndSet(false)) {
                        started.countDown();
                        // Not interruptible, like a long native deflate
                        boolean interrupted = false;
                        while (true) {
                            try {
                                release.await();
                                break;
                            } catch (InterruptedException e) {
                                interrupted = true;
                            }
                        }
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return super.deflate(b, off, len, flush);
                }
            };
            created.add(deflater);
            return deflater;
        });
        out.write(data(BLOCK_SIZE, true, 23)); // One full block: a worker starts on it and blocks
        assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));
        Thread.currentThread().interrupt();
        try {
            out.close();
            fail("close while interrupted and waiting for a worker must fail");
        } catch (IOException e) {
            // Expected (InterruptedIOException)
        } finally {
            Thread.interrupted();
        }
        assertTrue(sink.closed);
        release.countDown(); // The worker finishes after close() returned
        waitForThreads(before);
        assertFalse(created.isEmpty());
        for (CountingDeflater deflater: created) {
            assertEquals("deflater ended exactly once", 1, deflater.ends.get());
        }
        assertNull(field(out, "input"));
        assertTrue(((java.util.Collection<?>) field(out, "deflaters")).isEmpty());
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        final java.lang.reflect.Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    static class CountingDeflater extends Deflater {
        CountingDeflater() {
            super(Deflater.DEFAULT_COMPRESSION, true);
        }

        @Override
        public void end() {
            ends.incrementAndGet();
            super.end();
        }

        final java.util.concurrent.atomic.AtomicInteger ends = new java.util.concurrent.atomic.AtomicInteger();
    }

    static byte[] compress(byte[] data, int threads, int chunk) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream out = new ParallelGzipOutputStream(bytes, threads)) {
            if (chunk == 1) {
                for (byte b: data) {
                    out.write(b);
                }
            } else {
                for (int off = 0; off < data.length; off += chunk) {
                    out.write(data, off, Math.min(chunk, data.length - off));
                }
            }
        }
        return bytes.toByteArray();
    }

    static byte[] gunzip(byte[] gzip) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
            return in.readAllBytes();
        }
    }

    /** One member: the raw deflate data ends exactly before the 8 byte trailer, which holds the CRC-32 and size. */
    private static void assertSingleStandardMember(String label, byte[] data, byte[] gzip) throws IOException {
        final Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(gzip, 10, gzip.length - 10);
            final byte[] output = new byte[data.length + 1];
            int total = 0;
            while (! inflater.finished()) {
                final int count = inflater.inflate(output, total, output.length - total);
                if ((count == 0) && (inflater.needsInput() || inflater.needsDictionary())) {
                    fail(label + ": truncated deflate stream");
                }
                total += count;
            }
            assertEquals(label + ": length", data.length, total);
            assertEquals(label + ": trailer only after the deflate data", 8, inflater.getRemaining());
        } catch (DataFormatException e) {
            throw new IOException(e);
        } finally {
            inflater.end();
        }
        final CRC32 crc = new CRC32();
        crc.update(data);
        final int offset = gzip.length - 8;
        assertEquals(label + ": crc", (int) crc.getValue(), intLE(gzip, offset));
        assertEquals(label + ": isize", data.length, intLE(gzip, offset + 4));
    }

    private static int intLE(byte[] b, int offset) {
        return (b[offset] & 0xff) | (b[offset + 1] & 0xff) << 8 | (b[offset + 2] & 0xff) << 16 | (b[offset + 3] & 0xff) << 24;
    }

    static byte[] data(int size, boolean compressible, long seed) {
        final Random random = new Random(seed);
        final byte[] data = new byte[size];
        if (compressible) {
            for (int i = 0; i < size; i++) {
                data[i] = (byte) ((i / 37) % 11 + ((random.nextInt(8) == 0) ? random.nextInt(5) : 0));
            }
        } else {
            random.nextBytes(data);
        }
        return data;
    }

    static int gzipThreads() {
        return (int) Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.getName().equals("Parallel gzip") && thread.isAlive()).count();
    }

    static void waitForThreads(int expected) throws InterruptedException {
        for (int i = 0; (i < 100) && (gzipThreads() > expected); i++) {
            Thread.sleep(20);
        }
        assertEquals("compression threads must be gone after close", expected, gzipThreads());
    }

    static final class FailingStream extends OutputStream {
        FailingStream(long limit) {
            this.limit = limit;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (closed) {
                throw new IOException("closed");
            }
            written += len;
            if (written > limit) {
                throw new IOException("Simulated disk full");
            }
        }

        @Override
        public void close() {
            closed = true;
        }

        private final long limit;
        long written;
        boolean closed;
    }
}
