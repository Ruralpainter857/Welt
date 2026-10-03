package org.pepsoft.minecraft;

import org.jnbt.*;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Complete chunk-tag decoding with one bounded native tree index per stream. */
public final class ChunkTagReader {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int NODE_WORDS = 8, HEADER_WORDS = 4, MAX_NODES = 8192;
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    private static final AtomicLong COMPLETED = new AtomicLong();

    private ChunkTagReader() { }

    @FunctionalInterface
    interface StreamSource { InputStream open() throws IOException; }

    public static long completedCalls() { return COMPLETED.get(); }

    /** Reopening permits the original reader to preserve errors and ignored trailing data. */
    public static Tag read(RegionFile region, int x, int z) throws IOException {
        return read(() -> region.getChunkDataInputStream(x, z));
    }

    static Tag read(StreamSource source) throws IOException {
        if (!Boolean.parseBoolean(System.getProperty("welt.native.chunkNbt", "true")) || !NativeLoader.areSlicesAvailable()) {
            return readJava(source);
        }
        Scratch scratch = SCRATCH.get();
        int length;
        try (InputStream stream = source.open()) {
            if (stream == null) { return null; }
            length = scratch.capture(stream);
        } catch (IOException e) {
            // The original reader can accept a root before an invalid compressed trailer.
            return readJava(source);
        }
        if (length < 0) { return readJava(source); }
        if (!Boolean.parseBoolean(System.getProperty("welt.native.chunkNbtKernel", "true"))
                || !NativeSlices.indexChunkNbt(scratch.bytes, length, scratch.index)) {
            // Reuse the captured snapshot, including unusual lengths accepted by JNBT.
            try (NBTInputStream in = new NBTInputStream(new ByteArrayInputStream(scratch.bytes, 0, length))) {
                return in.readTag();
            }
        }
        COMPLETED.incrementAndGet();
        return scratch.materialise(0, ByteBuffer.wrap(scratch.bytes, 0, length).order(ByteOrder.BIG_ENDIAN));
    }

    private static Tag readJava(StreamSource source) throws IOException {
        InputStream stream = source.open();
        if (stream == null) { return null; }
        try (NBTInputStream in = new NBTInputStream(stream)) { return in.readTag(); }
    }

    private static final class Scratch {
        byte[] bytes = new byte[65536];
        final int[] index = new int[HEADER_WORDS + MAX_NODES * NODE_WORDS];

        int capture(InputStream stream) throws IOException {
            int length = 0;
            while (true) {
                if (length == bytes.length) {
                    if (length == MAX_BYTES) { return -1; }
                    bytes = Arrays.copyOf(bytes, Math.min(MAX_BYTES, bytes.length * 2));
                }
                int count = stream.read(bytes, length, bytes.length - length);
                if (count < 0) { return length; }
                if (count == 0) {
                    int value = stream.read();
                    if (value < 0) { return length; }
                    bytes[length++] = (byte) value;
                } else { length += count; }
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        Tag materialise(int node, ByteBuffer data) {
            int offset = HEADER_WORDS + node * NODE_WORDS;
            int kind = index[offset], payload = index[offset + 3], length = index[offset + 4];
            String name = string(index[offset + 1], index[offset + 2]);
            switch (kind) {
                case 0: return new EndTag();
                case 1: return new ByteTag(name, bytes[payload]);
                case 2: return new ShortTag(name, data.getShort(payload));
                case 3: return new IntTag(name, data.getInt(payload));
                case 4: return new LongTag(name, data.getLong(payload));
                case 5: return new FloatTag(name, data.getFloat(payload));
                case 6: return new DoubleTag(name, data.getDouble(payload));
                case 7: return new ByteArrayTag(name, Arrays.copyOfRange(bytes, payload, payload + length));
                case 8: return new StringTag(name, string(payload, length));
                case 9: {
                    List<Tag> children = new ArrayList<>();
                    int child = node + 1;
                    for (int i = 0; i < index[offset + 5]; i++) {
                        children.add(materialise(child, data));
                        child = index[HEADER_WORDS + child * NODE_WORDS + 6];
                    }
                    return new ListTag(name, NBTUtils.getTypeClass(index[offset + 7]), children);
                }
                case 10: {
                    Map<String, Tag> children = new HashMap<>();
                    int child = node + 1;
                    for (int i = 0; i < index[offset + 5]; i++) {
                        Tag value = materialise(child, data);
                        children.put(value.getName(), value);
                        child = index[HEADER_WORDS + child * NODE_WORDS + 6];
                    }
                    return new CompoundTag(name, children);
                }
                case 11: {
                    int[] values = new int[length];
                    data.position(payload); data.asIntBuffer().get(values);
                    return new IntArrayTag(name, values);
                }
                case 12: {
                    long[] values = new long[length];
                    data.position(payload); data.asLongBuffer().get(values);
                    return new LongArrayTag(name, values);
                }
                default: throw new AssertionError("Invalid native NBT directory");
            }
        }

        private String string(int offset, int length) {
            return length == 0 ? "" : new String(bytes, offset, length, StandardCharsets.UTF_8);
        }
    }
}
