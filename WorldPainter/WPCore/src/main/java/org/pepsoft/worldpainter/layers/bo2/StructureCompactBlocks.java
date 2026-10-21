package org.pepsoft.worldpainter.layers.bo2;

import org.jnbt.CompoundTag;
import org.jnbt.NBTInputStream;
import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import javax.vecmath.Point3i;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Immutable compact positions/indices; no native calls during editor block access. */
final class StructureCompactBlocks extends CompactCoordinateIndex {
    private static final int HEADER = 64, MAX_BYTES = 16 * 1024 * 1024;
    private static final AtomicLong COMPLETED = new AtomicLong();
    private static final AtomicLong[] PROJECT_PROFILE = {new AtomicLong(),new AtomicLong(),new AtomicLong(),new AtomicLong()};
    static long[] projectProfile() { return Arrays.stream(PROJECT_PROFILE).mapToLong(AtomicLong::get).toArray(); }
    record Root(CompoundTag tag, byte[] frame) { }
    private final Material[] palette;

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

    /** Rebuild compact immutable indices after reading the unchanged historical project fields. */
    static StructureCompactBlocks captureProject(Map<Point3i, Material> blocks, Point3i dimensions) {
        if (!NativeSlices.isStructureSnapshotAvailable() || blocks == null || blocks.getClass() != HashMap.class
                || blocks.isEmpty() || blocks.size() > 262144) return null;
        boolean profile = Boolean.getBoolean("welt.native.structureProjectProfile");
        long started = profile ? System.nanoTime() : 0;
        byte[] source = new byte[32 + blocks.size() * 16];
        ByteBuffer records = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        records.putInt(0, 0x52505357).putInt(4, 1).putInt(8, blocks.size())
                .putInt(12, dimensions.x).putInt(16, dimensions.y).putInt(20, dimensions.z);
        IdentityHashMap<Material, Integer> ids = new IdentityHashMap<>();
        ArrayList<Material> materials = new ArrayList<>();
        int at = 32;
        for (var entry : blocks.entrySet()) {
            Point3i position = entry.getKey(); Material material = entry.getValue();
            if (position == null || position.getClass() != Point3i.class || material == null) return null;
            Integer id = ids.get(material);
            if (id == null) {
                if (materials.size() == 65535) return null;
                id = materials.size(); ids.put(material, id); materials.add(material);
            }
            records.putInt(at, position.x).putInt(at + 4, position.y)
                    .putInt(at + 8, position.z).putInt(at + 12, id);
            at += 16;
        }
        records.putInt(24, materials.size());
        long prepared = profile ? System.nanoTime() : 0;
        byte[] frame = NativeSlices.packStructureSnapshot(source);
        long nativeDone = profile ? System.nanoTime() : 0;
        if (frame == null) return null;
        StructureCompactBlocks result = new StructureCompactBlocks(frame, materials.toArray(new Material[0]));
        if (profile) {
            PROJECT_PROFILE[0].incrementAndGet();
            PROJECT_PROFILE[1].addAndGet(prepared - started);
            PROJECT_PROFILE[2].addAndGet(nativeDone - prepared);
            PROJECT_PROFILE[3].addAndGet(System.nanoTime() - nativeDone);
        }
        return result;
    }

    StructureCompactBlocks(byte[] frame, Material[] palette) {
        super(frame, palette.length);
        this.palette = palette;

    }
    void completed() { COMPLETED.incrementAndGet(); }
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