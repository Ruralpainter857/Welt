package org.pepsoft.worldpainter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * A gzip output stream that compresses on several threads, producing a single standard gzip member that any gzip
 * reader (including {@link java.util.zip.GZIPInputStream}) decompresses to exactly the bytes written.
 *
 * <p>The data is cut into blocks which are deflated independently at the same compression level as
 * {@link GZIPOutputStream}, each primed with the last 32 KiB of the previous block as a preset dictionary and ended
 * with a sync flush, so that the raw deflate streams can be concatenated; only the last block is final. The CRC-32 and
 * size trailer is computed on the writing thread.
 *
 * <p>Memory is bounded: at most twice as many blocks as there are worker threads are in flight, and input and output
 * block buffers and {@link Deflater}s are recycled. The worker threads, deflaters and buffers belong to this stream
 * and are released by {@link #close()}, also after a failure. Closing closes the underlying stream, like
 * {@code GZIPOutputStream}.
 */
final class ParallelGzipOutputStream extends OutputStream {
    ParallelGzipOutputStream(OutputStream out, int threads) throws IOException {
        this(out, threads, () -> new Deflater(Deflater.DEFAULT_COMPRESSION, true));
    }

    /** For tests: {@code deflaterFactory} must create raw ({@code nowrap}) deflaters. */
    ParallelGzipOutputStream(OutputStream out, int threads, Supplier<Deflater> deflaterFactory) throws IOException {
        if (threads < 1) {
            throw new IllegalArgumentException("threads " + threads);
        }
        this.out = out;
        this.deflaterFactory = deflaterFactory;
        maxInFlight = threads * 2;
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                runnable -> {
                    final Thread thread = new Thread(runnable, "Parallel gzip");
                    thread.setDaemon(true);
                    return thread;
                });
        // Safety net for a stream that is never closed; close() shuts the pool down immediately
        pool.allowCoreThreadTimeOut(true);
        executor = pool;
        input = new byte[BLOCK_SIZE];
        out.write(HEADER);
    }

    @Override
    public void write(int b) throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
        crc.update(b);
        size++;
        input[inputLength++] = (byte) b;
        if (inputLength == BLOCK_SIZE) {
            submit(false);
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
        if ((off < 0) || (len < 0) || (len > b.length - off)) {
            throw new IndexOutOfBoundsException();
        }
        crc.update(b, off, len);
        size += len;
        while (len > 0) {
            final int count = Math.min(len, BLOCK_SIZE - inputLength);
            System.arraycopy(b, off, input, inputLength, count);
            inputLength += count;
            off += count;
            len -= count;
            if (inputLength == BLOCK_SIZE) {
                submit(false);
            }
        }
    }

    /** Writes out the blocks completed so far; blocks still being filled or compressed are not forced out. */
    @Override
    public void flush() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
        out.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        try {
            submit(true);
            while (! pending.isEmpty()) {
                writeOldest();
            }
            final byte[] trailer = new byte[8];
            writeIntLE(trailer, 0, (int) crc.getValue());
            writeIntLE(trailer, 4, (int) size);
            out.write(trailer);
        } catch (IOException e) {
            failure = e;
        } finally {
            executor.shutdownNow();
            for (Future<Block> future: pending) {
                future.cancel(true);
            }
            pending.clear();
            // End the pooled deflaters; a worker still compressing ends its own when it finishes (see compress())
            synchronized (deflaters) {
                released = true;
                deflaters.forEach(Deflater::end);
                deflaters.clear();
            }
            // A closed stream that is still referenced must not keep its blocks
            input = null;
            previousTail = null;
            inputs.clear();
            outputs.clear();
            try {
                out.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void submit(boolean last) throws IOException {
        final byte[] data = input;
        final int length = inputLength;
        final byte[] dictionary;
        if (dictionaryLength > 0) {
            dictionary = new byte[dictionaryLength];
            System.arraycopy(previousTail, 0, dictionary, 0, dictionaryLength);
        } else {
            dictionary = null;
        }
        // The tail of this block primes the next one
        final int tail = Math.min(DICTIONARY_SIZE, length);
        if (tail == DICTIONARY_SIZE) {
            System.arraycopy(data, length - tail, previousTail, 0, tail);
            dictionaryLength = tail;
        } else if (tail > 0) {
            final int keep = Math.min(dictionaryLength, DICTIONARY_SIZE - tail);
            System.arraycopy(previousTail, dictionaryLength - keep, previousTail, 0, keep);
            System.arraycopy(data, 0, previousTail, keep, tail);
            dictionaryLength = keep + tail;
        }
        while (pending.size() >= maxInFlight) {
            writeOldest();
        }
        pending.add(executor.submit(() -> compress(data, length, dictionary, last)));
        if (! last) {
            final byte[] recycled = inputs.poll();
            input = (recycled != null) ? recycled : new byte[BLOCK_SIZE];
            inputLength = 0;
        }
    }

    private Block compress(byte[] data, int length, byte[] dictionary, boolean last) {
        Deflater deflater;
        synchronized (deflaters) {
            deflater = deflaters.poll();
        }
        if (deflater == null) {
            deflater = deflaterFactory.get();
        }
        try {
            if (dictionary != null) {
                deflater.setDictionary(dictionary);
            }
            deflater.setInput(data, 0, length);
            final byte[] recycled = outputs.poll();
            byte[] output = (recycled != null) ? recycled : new byte[OUTPUT_SIZE];
            int outputLength = 0;
            if (last) {
                deflater.finish();
                while (! deflater.finished()) {
                    if (outputLength == output.length) {
                        output = Arrays.copyOf(output, output.length + output.length / 2);
                    }
                    outputLength += deflater.deflate(output, outputLength, output.length - outputLength);
                }
            } else {
                // A sync flush is complete when it leaves room in the output buffer
                while (true) {
                    if (outputLength == output.length) {
                        output = Arrays.copyOf(output, output.length + output.length / 2);
                    }
                    final int space = output.length - outputLength;
                    final int count = deflater.deflate(output, outputLength, space, Deflater.SYNC_FLUSH);
                    outputLength += count;
                    if (count < space) {
                        break;
                    }
                }
            }
            return new Block(data, output, outputLength);
        } finally {
            // Each deflater is ended exactly once: here if the stream was closed meanwhile, otherwise by close()
            synchronized (deflaters) {
                if (released) {
                    deflater.end();
                } else {
                    deflater.reset();
                    deflaters.add(deflater);
                }
            }
        }
    }

    private void writeOldest() throws IOException {
        final Block block;
        try {
            block = pending.remove().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while compressing");
        } catch (ExecutionException e) {
            throw new IOException("Compression failed", e.getCause());
        }
        out.write(block.output, 0, block.outputLength);
        if (inputs.size() < maxInFlight) {
            inputs.add(block.input);
        }
        if (outputs.size() < maxInFlight) {
            outputs.add(block.output);
        }
    }

    private static void writeIntLE(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) value;
        buffer[offset + 1] = (byte) (value >> 8);
        buffer[offset + 2] = (byte) (value >> 16);
        buffer[offset + 3] = (byte) (value >> 24);
    }

    private final OutputStream out;
    private final Supplier<Deflater> deflaterFactory;
    private final int maxInFlight;
    private final ExecutorService executor;
    private final ArrayDeque<Future<Block>> pending = new ArrayDeque<>();
    /** Guarded by itself, as is {@link #released}. */
    private final ArrayDeque<Deflater> deflaters = new ArrayDeque<>();
    private final ArrayDeque<byte[]> inputs = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<byte[]> outputs = new ConcurrentLinkedQueue<>();
    private final CRC32 crc = new CRC32();
    private byte[] previousTail = new byte[DICTIONARY_SIZE];
    private byte[] input;
    private int inputLength, dictionaryLength;
    private long size;
    private boolean closed, released;

    static final int BLOCK_SIZE = 512 * 1024;
    private static final int DICTIONARY_SIZE = 32 * 1024;
    /** Room for a block that does not compress, plus deflate's stored block and flush overhead. */
    private static final int OUTPUT_SIZE = BLOCK_SIZE + BLOCK_SIZE / 64 + 1024;
    /** The header {@link GZIPOutputStream} writes, so that files differ only in the deflate data. */
    private static final byte[] HEADER;

    static {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            // Empty member: only the header is used
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        HEADER = Arrays.copyOf(bytes.toByteArray(), 10);
    }

    private record Block(byte[] input, byte[] output, int outputLength) {}
}
