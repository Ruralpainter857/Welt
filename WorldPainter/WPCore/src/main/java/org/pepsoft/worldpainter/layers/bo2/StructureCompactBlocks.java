package org.pepsoft.worldpainter.layers.bo2;

import org.jnbt.CompoundTag;
import org.jnbt.NBTInputStream;
import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import javax.vecmath.Point3i;
import java.io.*;
import java.nio.ByteBuffer;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Immutable compact positions/indices; no native calls during editor block access. */
final class StructureCompactBlocks {
    private static final int HEADER = 64, MAX_BYTES = 16 * 1024 * 1024;
    private static final AtomicLong COMPLETED = new AtomicLong();
    record Root(CompoundTag tag, byte[] frame) { }
    private final Material[] palette;
    private final byte[] data;
    private final int mode, count, width, length, height, slots, stride;
    private static final VarHandle WORD = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    static long completedObjects() { return COMPLETED.get(); }
    static Root readRoot(InputStream decompressed) throws IOException {
        if (!NativeSlices.isStructureLoadingAvailable()) {
            try (NBTInputStream in = new NBTInputStream(decompressed)) { return new Root((CompoundTag) in.readTag(), null); }
        }
        ByteArrayOutputStream collected = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192]; IOException readFailure = null;
        try {
            while (collected.size() < MAX_BYTES + 1) {
                int count = decompressed.read(buffer, 0, Math.min(buffer.length, MAX_BYTES + 1 - collected.size()));
                if (count < 0) break;
                collected.write(buffer, 0, count);
            }
        } catch (IOException failure) { readFailure = failure; }
        byte[] source = collected.toByteArray();
        if (readFailure != null) {
            // The original reader stops after one NBT root and can ignore a damaged later GZIP member.
            try (NBTInputStream in = new NBTInputStream(new ByteArrayInputStream(source))) {
                return new Root((CompoundTag) in.readTag(), null);
            } catch (IOException incompleteRoot) { throw readFailure; }
        }
        byte[] frame = source.length <= MAX_BYTES ? NativeSlices.extractStructure(source) : null;
        if (frame != null) {
            ByteBuffer header = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
            if (frame.length < HEADER || header.getInt(0) != 0x57535452 || header.getInt(4) != 1
                    || header.getInt(44) != frame.length) throw new IOException("Invalid native structure frame");
            int offset = header.getInt(36), size = header.getInt(40);
            if (offset < HEADER || size < 1 || (long) offset + size != frame.length) throw new IOException("Invalid native structure NBT range");
            try (NBTInputStream in = new NBTInputStream(new ByteArrayInputStream(frame, offset, size))) {
                return new Root((CompoundTag) in.readTag(), frame);
            }
        }
        // The bounded prefix also allows oversized inputs to resume through the original reader.
        try (NBTInputStream in = new NBTInputStream(new SequenceInputStream(new ByteArrayInputStream(source), decompressed))) {
            return new Root((CompoundTag) in.readTag(), null);
        }
    }

    StructureCompactBlocks(byte[] frame, Material[] palette) {
        ByteBuffer header = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        mode = header.getInt(8); count = header.getInt(16); slots = header.getInt(48); stride = header.getInt(52);
        width = header.getInt(20); length = header.getInt(24); height = header.getInt(28);
        int bytes = header.getInt(12), offset = header.getInt(36);
        if ((mode != 1 && mode != 2) || count < 0 || count > 262144 || header.getInt(32) != palette.length
                || bytes < 0 || (long) HEADER + bytes != offset || offset > frame.length
                || slots < 0 || slots > 524288 || mode == 1 && slots != 0
                || mode == 2 && (bytes != count * 16 + slots * 4 || count == 0 && slots != 0
                || count != 0 && (slots < count * 2 || (slots & (slots - 1)) != 0))
                || mode == 1 && ((stride != 1 && stride != 2) || width < 1 || length < 1 || height < 1 || (long) width * length > 262144
                || (long) width * length * height > 262144 || (long) width * length * height * stride != bytes))
            throw new IllegalArgumentException("Invalid compact structure storage");
        this.palette = palette;
        data = Arrays.copyOfRange(frame, HEADER, offset);
    }
    void completed() { COMPLETED.incrementAndGet(); }
    long storageBytes() { return data.length; }
    private int word(int offset) {
        return (int) WORD.get(data, offset);
    }
    private int index(int x, int y, int z) {
        if (mode == 1) {
            if (x < 0 || y < 0 || z < 0 || x >= width || y >= length || z >= height) return -1;
            int at = (x + y * width + z * width * length) * stride;
            return (stride == 1 ? data[at] & 255 : (data[at] & 255) | (data[at + 1] & 255) << 8) - 1;
        }
        if (count == 0) return -1;
        int hash = x * 0x9e3779b9 ^ y * 0x85ebca6b ^ z * 0xc2b2ae35;
        hash ^= hash >>> 16; hash *= 0x7feb352d; hash ^= hash >>> 15;
        int slot = hash & (slots - 1), start = count * 16;
        for (int probe = 0; probe < slots; probe++) {
            int entry = word(start + slot * 4);
            if (entry == 0) return -1;
            int at = (entry - 1) * 16;
            if (word(at) == x && word(at + 4) == y && word(at + 8) == z) return word(at + 12);
            slot = (slot + 1) & (slots - 1);
        }
        return -1;
    }
    Material material(int x, int y, int z) {
        int index = index(x, y, z);
        return index < 0 ? null : palette[index];
    }
    boolean mask(int x, int y, int z, boolean ignoreAir) {
        int index = index(x, y, z);
        return index >= 0 && (!ignoreAir || palette[index] != null && palette[index] != Material.AIR);
    }
    boolean isSparse() { return mode == 2; }
    Point3i sparseOffset() {
        // Inspect stored positions once instead of scanning a potentially huge empty volume.
        int lowestZ = Integer.MAX_VALUE, minX = 0, maxX = 0, minY = 0, maxY = 0;
        for (int i = 0; i < count; i++) {
            int at = i * 16, x = word(at), y = word(at + 4), z = word(at + 8);
            Material material = palette[word(at + 12)];
            if (x < 0 || y < 0 || z < 0 || x >= width || y >= length || z >= height
                    || material == null || material == Material.AIR) continue;
            if (z < lowestZ) { lowestZ = z; minX = maxX = x; minY = maxY = y; }
            else if (z == lowestZ) { minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
        }
        return lowestZ == Integer.MAX_VALUE ? null : new Point3i(-(minX + maxX) / 2, -(minY + maxY) / 2, -lowestZ);
    }
    Map<Point3i, Material> historicalMap() {
        Map<Point3i, Material> blocks = new HashMap<>();
        if (mode == 1) {
            for (int z = 0; z < height; z++) for (int y = 0; y < length; y++) for (int x = 0; x < width; x++) {
                Material material = material(x, y, z);
                if (material != null) blocks.put(new Point3i(x, y, z), material);
            }
        } else for (int i = 0; i < count; i++) {
            int at = i * 16;
            blocks.put(new Point3i(word(at), word(at + 4), word(at + 8)), palette[word(at + 12)]);
        }
        return blocks;
    }
}