/*
 * WorldPainter - a Minecraft map painting application.
 * Copyright (C) 2026 the Welt project and WorldPainter contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.pepsoft.minecraft;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.pepsoft.util.PackedArrayCube;

import static org.pepsoft.minecraft.Constants.MC_SNOW;
import static org.pepsoft.minecraft.Constants.MC_WATER;
import static org.pepsoft.minecraft.Material.AIR;
import static org.pepsoft.minecraft.Material.LAYERS;

/**
 * Reusable, direct-memory representation of one chunk's block palette.
 *
 * <p>This is the Java half of the versioned shared chunk-buffer ABI. It is not
 * enabled by the exporters yet: callers must keep the chunk and this view in
 * sync until the region is saved. The returned view is worker-local and is
 * invalidated by the next call to {@link #capture(Chunk, Material...)} on the
 * same thread.</p>
 */
public final class ChunkPaletteBuffer {
    public static final int MAGIC = 0x4b484357; // "WCHK" in little endian
    public static final int ABI_VERSION = 1;
    public static final int HEADER_BYTES = 68;
    public static final int SECTION_DESCRIPTOR_BYTES = 16;
    public static final int NEIGHBOR_DESCRIPTOR_BYTES = 16;
    private static final int HEADER_FORMAT_OFFSET = 48;
    private static final int HEADER_DATA_VERSION_OFFSET = 52;
    private static final int HEADER_MUTATION_SEQUENCE_OFFSET = 64;
    private static final int MAX_CHUNK_CELLS = 16 * 16 * 4096;

    private static final ThreadLocal<Arena> ARENAS = ThreadLocal.withInitial(Arena::new);
    private static final AtomicLong PROFILE_NANOS = new AtomicLong();
    private static final AtomicLong PROFILE_CHUNKS = new AtomicLong();
    private static final AtomicLong PROFILE_CELLS = new AtomicLong();
    private static final AtomicLong PROFILE_BYTES = new AtomicLong();
    private static final AtomicLong PROFILE_FALLBACKS = new AtomicLong();

    private ChunkPaletteBuffer() {
    }

    /**
     * Captures a chunk into a reusable direct buffer. Reserved materials are
     * added before scanning so native passes can write their palette indices
     * without rebuilding the palette. Returns {@code null} for unsupported
     * bounds or an unreadable chunk, allowing an explicit Java fallback.
     */
    public static View capture(Chunk chunk, Material... reservedMaterials) {
        return capture(chunk, getChunkDataVersion(chunk), reservedMaterials);
    }

    /** Captures one chunk and records preparation cost for the full-export profiler. */
    public static View captureProfiled(Chunk chunk) {
        final long start = System.nanoTime();
        final View view = capture(chunk);
        PROFILE_NANOS.addAndGet(System.nanoTime() - start);
        if (view == null) {
            PROFILE_FALLBACKS.incrementAndGet();
        } else {
            PROFILE_CHUNKS.incrementAndGet();
            PROFILE_CELLS.addAndGet((long) (view.maxY() - view.minY()) * 256L);
            PROFILE_BYTES.addAndGet(view.encodedByteCount());
        }
        return view;
    }

    public static void resetCaptureProfile() {
        PROFILE_NANOS.set(0);
        PROFILE_CHUNKS.set(0);
        PROFILE_CELLS.set(0);
        PROFILE_BYTES.set(0);
        PROFILE_FALLBACKS.set(0);
    }

    public static CaptureProfile captureProfile() {
        return new CaptureProfile(PROFILE_NANOS.get(), PROFILE_CHUNKS.get(), PROFILE_CELLS.get(),
                PROFILE_BYTES.get(), PROFILE_FALLBACKS.get());
    }

    public static final class CaptureProfile {
        public final long nanos, chunks, cells, encodedBytes, fallbacks;

        private CaptureProfile(long nanos, long chunks, long cells, long encodedBytes, long fallbacks) {
            this.nanos = nanos;
            this.chunks = chunks;
            this.cells = cells;
            this.encodedBytes = encodedBytes;
            this.fallbacks = fallbacks;
        }
    }

    /**
     * Opens the chunk's canonical palette-index arrays for a grouped updater.
     * No block data is copied. Unsupported chunks, absent sections, single-
     * material sections, and object-backed cubes return {@code null} so the
     * caller can use the established Java path for the whole chunk.
     */
    public static LivePaletteView openLivePaletteView(Chunk chunk, Material... reservedMaterials) {
        if (chunk == null || chunk.isReadOnly() || reservedMaterials == null
                || (chunk.getMinHeight() & 15) != 0 || (chunk.getMaxHeight() & 15) != 0) {
            return null;
        }
        for (Material material : reservedMaterials) {
            if (material == null) {
                return null;
            }
        }

        final int minY = chunk.getMinHeight();
        final long worldHeight = (long) chunk.getMaxHeight() - minY;
        if (worldHeight <= 0 || worldHeight > 4096L || (worldHeight & 15L) != 0) {
            return null;
        }
        final int sectionCount = (int) (worldHeight >> 4);
        final PackedArrayCube<Material>[] cubes = newCubeArray(sectionCount);
        if (chunk instanceof MC115AnvilChunk anvil115) {
            final MC115AnvilChunk.Section[] sections = anvil115.getSections();
            if (sections.length != sectionCount) {
                return null;
            }
            for (int section = 0; section < sectionCount; section++) {
                if (sections[section] == null || sections[section].materials == null
                        || !sections[section].materials.hasPaletteIndexStorage()) {
                    return null;
                }
                cubes[section] = sections[section].materials;
            }
        } else if (chunk instanceof MC118AnvilChunk anvil118) {
            final MC118AnvilChunk.Section[] sections = anvil118.getSections();
            final int firstSection = (minY >> 4) + anvil118.undergroundSections;
            if (firstSection < 0 || firstSection + sectionCount > sections.length) {
                return null;
            }
            for (int section = 0; section < sectionCount; section++) {
                final MC118AnvilChunk.Section source = sections[firstSection + section];
                if (source == null || source.singleMaterial != null || source.materials == null
                        || !source.materials.hasPaletteIndexStorage()) {
                    return null;
                }
                cubes[section] = source.materials;
            }
        } else {
            return null;
        }

        for (PackedArrayCube<Material> cube : cubes) {
            for (Material material : reservedMaterials) {
                if (cube.ensurePaletteIndexForBulkUpdate(material) < 0) {
                    return null;
                }
            }
        }
        return new LivePaletteView(minY, cubes);
    }

    @SuppressWarnings("unchecked")
    private static PackedArrayCube<Material>[] newCubeArray(int length) {
        return (PackedArrayCube<Material>[]) new PackedArrayCube<?>[length];
    }

    /** A zero-copy view over compact palette-index storage for one live chunk. */
    public static final class LivePaletteView {
        private final int minY;
        private final PackedArrayCube<Material>[] sections;

        private LivePaletteView(int minY, PackedArrayCube<Material>[] sections) {
            this.minY = minY;
            this.sections = sections;
        }

        public int minY() { return minY; }
        public int sectionCount() { return sections.length; }
        public int[] indexes(int section) {
            return sections[section].getPaletteIndexesForBulkUpdate();
        }
        public int paletteSize(int section) { return sections[section].getPaletteIndexCount(); }
        public Material paletteMaterial(int section, int paletteIndex) {
            return sections[section].getPaletteValue(paletteIndex);
        }
        public int paletteIndex(int section, Material material) {
            return sections[section].ensurePaletteIndexForBulkUpdate(material);
        }
    }

    /** Captures a chunk and records the requested Minecraft DataVersion when known. */
    public static View capture(Chunk chunk, int dataVersion, Material... reservedMaterials) {
        if (chunk == null || chunk.isReadOnly() || reservedMaterials == null || dataVersion < -1) {
            return null;
        }
        final int minY = chunk.getMinHeight();
        final int maxY = chunk.getMaxHeight();
        if (minY >= maxY || (minY & 15) != 0 || (maxY & 15) != 0
                || (long) maxY - minY > 4096L) {
            return null;
        }
        final int sectionCount = (maxY - minY) >> 4;
        final int cellCount = sectionCount * 4096;
        if (cellCount <= 0 || cellCount > MAX_CHUNK_CELLS) {
            return null;
        }

        final Arena arena = ARENAS.get();
        arena.ensureScratch(cellCount);
        arena.palette.clear();
        arena.clearPaletteLookup();
        for (Material material : reservedMaterials) {
            if (material == null) {
                return null;
            }
            arena.paletteIndex(material);
        }

        try {
            if (!captureFormatNativeChunkStorage(chunk, arena, sectionCount, minY)) {
                int cell = 0;
                for (int section = 0; section < sectionCount; section++) {
                    final int sectionY = minY + (section << 4);
                    for (int y = sectionY; y < sectionY + 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                final Material material = chunk.getMaterial(x, y, z);
                                arena.indices[cell++] = arena.paletteIndex((material != null) ? material : AIR);
                            }
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            arena.palette.clear();
            arena.clearPaletteLookup();
            return null;
        }

        final int indexWidthBytes = (arena.palette.size() <= 0x100) ? 1
                : (arena.palette.size() <= 0x10000) ? 2 : 4;
        if (arena.palette.size() > 1_048_576) {
            return null;
        }
        final int indexBytes = indexWidthBytes;
        final int descriptorsOffset = HEADER_BYTES;
        final int paletteFlagsOffset = descriptorsOffset + sectionCount * SECTION_DESCRIPTOR_BYTES;
        final int neighborCount = 0;
        final int neighborOffset = paletteFlagsOffset + arena.palette.size() * Integer.BYTES;
        final int dataOffset = neighborOffset + neighborCount * NEIGHBOR_DESCRIPTOR_BYTES;
        final long totalBytes = (long) dataOffset + (long) cellCount * indexBytes;
        if (totalBytes > Integer.MAX_VALUE) {
            return null;
        }
        arena.ensureBuffer((int) totalBytes);
        final ByteBuffer buffer = arena.buffer;
        buffer.clear();
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.putInt(ABI_VERSION);
        buffer.putInt(HEADER_BYTES);
        buffer.putInt((int) totalBytes);
        buffer.putInt(chunk.getxPos());
        buffer.putInt(chunk.getzPos());
        buffer.putInt(minY);
        buffer.putInt(sectionCount);
        buffer.putInt(arena.palette.size());
        buffer.putInt(descriptorsOffset);
        buffer.putInt(paletteFlagsOffset);
        buffer.putInt(dataOffset);
        buffer.putInt(formatCode(chunk));
        buffer.putInt(dataVersion);
        buffer.putInt(neighborCount);
        buffer.putInt(neighborOffset);
        buffer.putInt(0); // Pass sequence starts before the first mutation.

        for (int section = 0; section < sectionCount; section++) {
            buffer.putInt(minY + (section << 4));
            buffer.putInt(indexWidthBytes);
            buffer.putInt(dataOffset + section * 4096 * indexBytes);
            buffer.putInt(4096);
        }
        for (Material material : arena.palette) {
            buffer.putInt(semanticFlags(material));
        }
        for (int i = 0; i < cellCount; i++) {
            final int index = arena.indices[i];
            switch (indexBytes) {
                case 1 -> buffer.put((byte) index);
                case 2 -> buffer.putShort((short) index);
                case 4 -> buffer.putInt(index);
                default -> throw new AssertionError(indexBytes);
            }
        }
        buffer.limit((int) totalBytes);
        buffer.position(0);
        final Material[] paletteSnapshot = arena.snapshotPalette();
        return new View(buffer, paletteSnapshot, arena.palette.size(), minY,
                sectionCount, indexWidthBytes, indexBytes, chunk.getxPos(), chunk.getzPos(),
                formatCode(chunk), dataVersion);
    }

    private static int formatCode(Chunk chunk) {
        if (chunk instanceof MC12AnvilChunk) return 1;
        if (chunk instanceof MC115AnvilChunk) return 2;
        if (chunk instanceof MC118AnvilChunk) return 3;
        return 0;
    }

    private static int getChunkDataVersion(Chunk chunk) {
        if (chunk instanceof MC115AnvilChunk chunk115) {
            return (chunk115.inputDataVersion != null) ? chunk115.inputDataVersion : -1;
        }
        if (chunk instanceof MC118AnvilChunk chunk118) {
            return (chunk118.inputDataVersion != null) ? chunk118.inputDataVersion : -1;
        }
        return -1;
    }

    /** Stable v1 semantic bits consumed by export kernels; Java keeps exact Materials. */
    private static int semanticFlags(Material material) {
        int flags = 0;
        if (material.empty) flags |= 1;
        if (material.insubstantial) flags |= 1 << 1;
        if (material.containsWater()) flags |= 1 << 2;
        if (material.isNamed(MC_WATER) && material.getProperty(LAYERS, 0) == 0) flags |= 1 << 3;
        if (material.canSupportSnow) flags |= 1 << 4;
        if (material.leafBlock || material.sustainsLeaves) flags |= 1 << 5;
        if (material.isNamed(MC_SNOW)) flags |= 1 << 6;
        if (material == Material.SNOW) flags |= 1 << 7;
        return flags;
    }

    /** Reads native chunk storage directly where its layout is established and tested. */
    private static boolean captureFormatNativeChunkStorage(Chunk chunk, Arena arena,
                                                            int sectionCount, int minY) {
        if (chunk instanceof MC12AnvilChunk legacy) {
            final MC12AnvilChunk.Section[] sections = legacy.getSections();
            final int legacyGeneration = arena.beginLegacyLookup();
            for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
                final MC12AnvilChunk.Section section = sections[sectionIndex];
                final int base = sectionIndex * 4096;
                for (int i = 0; i < 4096; i++) {
                    int blockType = 0;
                    int dataValue = 0;
                    if (section != null) {
                        blockType = section.blocks[i] & 0xff;
                        if (section.add != null) {
                            final int nibble = i >> 1;
                            final int highId = ((i & 1) == 0)
                                    ? section.add[nibble] & 0x0f : (section.add[nibble] >>> 4) & 0x0f;
                            blockType |= highId << 8;
                        }
                        final int nibble = i >> 1;
                        dataValue = ((i & 1) == 0)
                                ? section.data[nibble] & 0x0f : (section.data[nibble] >>> 4) & 0x0f;
                    }
                    arena.indices[base + i] = (blockType == 0) ? arena.paletteIndex(AIR)
                            : arena.legacyPaletteIndex((blockType << 4) | dataValue, legacyGeneration);
                }
            }
            return true;
        }
        if (chunk instanceof MC115AnvilChunk anvil115) {
            final MC115AnvilChunk.Section[] sections = anvil115.getSections();
            for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
                final MC115AnvilChunk.Section section = sections[sectionIndex];
                final int base = sectionIndex * 4096;
                if (section == null) {
                    Arrays.fill(arena.indices, base, base + 4096, arena.paletteIndex(AIR));
                } else {
                    addMaterialIndexes(arena, section.materials, base);
                }
            }
            return true;
        }
        if (chunk instanceof MC118AnvilChunk anvil118) {
            final MC118AnvilChunk.Section[] sections = anvil118.getSections();
            final int firstSection = (minY >> 4) + anvil118.undergroundSections;
            for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
                final MC118AnvilChunk.Section section = sections[sectionIndex + firstSection];
                final int base = sectionIndex * 4096;
                if (section == null) {
                    Arrays.fill(arena.indices, base, base + 4096, arena.paletteIndex(AIR));
                } else if (section.singleMaterial != null) {
                    Arrays.fill(arena.indices, base, base + 4096,
                            arena.paletteIndex(section.singleMaterial));
                } else if (section.materials != null) {
                    addMaterialIndexes(arena, section.materials, base);
                } else {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static void addMaterialIndexes(Arena arena, PackedArrayCube<Material> materials, int targetOffset) {
        if (materials.hasPaletteIndexStorage()) {
            final int sourcePaletteSize = materials.getPaletteIndexCount();
            arena.ensurePaletteRemap(sourcePaletteSize);
            for (int i = 0; i < sourcePaletteSize; i++) {
                final Material material = materials.getPaletteValue(i);
                arena.paletteRemap[i] = arena.paletteIndex((material != null) ? material : AIR);
            }
            materials.copyPaletteIndexesTo(arena.indices, targetOffset);
            for (int i = 0; i < 4096; i++) {
                final int sourceIndex = arena.indices[targetOffset + i];
                if ((sourceIndex < 0) || (sourceIndex >= sourcePaletteSize)) {
                    throw new IllegalStateException("Invalid cube palette index " + sourceIndex);
                }
                arena.indices[targetOffset + i] = arena.paletteRemap[sourceIndex];
            }
            return;
        }
        for (int i = 0; i < 4096; i++) {
            final Material material = materials.getValueAtIndex(i);
            arena.indices[targetOffset + i] = arena.paletteIndex((material != null) ? material : AIR);
        }
    }

    /** A worker-local view. Do not retain it beyond the current region task. */
    public static final class View {
        private final ByteBuffer buffer;
        private final Material[] palette;
        private final int paletteCount, minY, sectionCount, indexWidthBytes, indexBytes, chunkX, chunkZ;
        private final int formatCode, dataVersion;

        private View(ByteBuffer buffer, Material[] palette, int paletteCount, int minY, int sectionCount,
                     int indexWidthBytes, int indexBytes, int chunkX, int chunkZ,
                     int formatCode, int dataVersion) {
            this.buffer = buffer;
            this.palette = palette;
            this.paletteCount = paletteCount;
            this.minY = minY;
            this.sectionCount = sectionCount;
            this.indexWidthBytes = indexWidthBytes;
            this.indexBytes = indexBytes;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.formatCode = formatCode;
            this.dataVersion = dataVersion;
        }

        /** The returned duplicate shares memory; its position/limit are independent. */
        public ByteBuffer buffer() {
            return buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        }

        public int paletteSize() { return paletteCount; }
        public int encodedByteCount() { return buffer.limit(); }
        public int indexWidthBytes() { return indexWidthBytes; }
        public Material material(int paletteIndex) {
            if (paletteIndex < 0 || paletteIndex >= paletteCount) {
                throw new IndexOutOfBoundsException("palette index " + paletteIndex);
            }
            return palette[paletteIndex];
        }
        public int minY() { return minY; }
        public int maxY() { return minY + (sectionCount << 4); }
        public int chunkX() { return chunkX; }
        public int chunkZ() { return chunkZ; }
        public int formatCode() { return formatCode; }
        public int dataVersion() { return dataVersion; }
        public int mutationSequence() { return buffer.getInt(HEADER_MUTATION_SEQUENCE_OFFSET); }

        /** Records completion order for a pass/group mutation on this view. */
        public int completeMutationBatch() {
            final int next = mutationSequence() + 1;
            if (next <= 0) {
                throw new IllegalStateException("chunk mutation sequence overflow");
            }
            buffer.putInt(HEADER_MUTATION_SEQUENCE_OFFSET, next);
            return next;
        }

        /** Reads a palette index using chunk-local x/z and world y coordinates. */
        public int indexAt(int x, int y, int z) {
            final int offset = indexOffset(x, y, z);
            return switch (indexBytes) {
                case 1 -> buffer.get(offset) & 0xff;
                case 2 -> buffer.getShort(offset) & 0xffff;
                case 4 -> buffer.getInt(offset);
                default -> throw new AssertionError(indexBytes);
            };
        }

        /** Writes a previously reserved palette index into the shared chunk data. */
        public void setIndexAt(int x, int y, int z, int paletteIndex) {
            if (paletteIndex < 0 || paletteIndex >= paletteCount) {
                throw new IndexOutOfBoundsException("palette index " + paletteIndex);
            }
            final int offset = indexOffset(x, y, z);
            switch (indexBytes) {
                case 1 -> buffer.put(offset, (byte) paletteIndex);
                case 2 -> buffer.putShort(offset, (short) paletteIndex);
                case 4 -> buffer.putInt(offset, paletteIndex);
                default -> throw new AssertionError(indexBytes);
            }
        }

        private int indexOffset(int x, int y, int z) {
            if ((x & ~15) != 0 || (z & ~15) != 0 || y < minY || y >= maxY()) {
                throw new IndexOutOfBoundsException("block " + x + "," + y + "," + z);
            }
            final int cell = (((y - minY) << 8) | (z << 4) | x);
            final int dataOffset = HEADER_BYTES + sectionCount * SECTION_DESCRIPTOR_BYTES
                    + paletteCount * Integer.BYTES;
            return dataOffset + cell * indexBytes;
        }
    }

    private static final class Arena {
        private ByteBuffer buffer = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN);
        private int[] indices = new int[0];
        private int[] paletteRemap = new int[0];
        private Material[] paletteSnapshot = new Material[0];
        private final List<Material> palette = new ArrayList<>();
        private Material[] paletteLookupKeys = new Material[16];
        private int[] paletteLookupValues = new int[16];
        private Material lastPaletteMaterial;
        private int lastPaletteIndex;
        private final int[] legacyPaletteIndexes = new int[1 << 16];
        private final int[] legacyLookupGenerations = new int[1 << 16];
        private int legacyGeneration;

        private int paletteIndex(Material material) {
            if (material == lastPaletteMaterial) {
                return lastPaletteIndex;
            }
            int slot = spread(material.hashCode()) & (paletteLookupKeys.length - 1);
            while (paletteLookupKeys[slot] != null) {
                if (paletteLookupKeys[slot].equals(material)) {
                    lastPaletteMaterial = material;
                    lastPaletteIndex = paletteLookupValues[slot];
                    return lastPaletteIndex;
                }
                slot = (slot + 1) & (paletteLookupKeys.length - 1);
            }
            ensurePaletteLookup(palette.size() + 1);
            slot = spread(material.hashCode()) & (paletteLookupKeys.length - 1);
            while (paletteLookupKeys[slot] != null) {
                slot = (slot + 1) & (paletteLookupKeys.length - 1);
            }
            final int index = palette.size();
            palette.add(material);
            paletteLookupKeys[slot] = material;
            paletteLookupValues[slot] = index;
            lastPaletteMaterial = material;
            lastPaletteIndex = index;
            return index;
        }

        private void clearPaletteLookup() {
            Arrays.fill(paletteLookupKeys, null);
            lastPaletteMaterial = null;
            lastPaletteIndex = 0;
        }

        private int beginLegacyLookup() {
            legacyGeneration++;
            if (legacyGeneration == 0) {
                Arrays.fill(legacyLookupGenerations, 0);
                legacyGeneration = 1;
            }
            return legacyGeneration;
        }

        private int legacyPaletteIndex(int combinedIndex, int generation) {
            if (legacyLookupGenerations[combinedIndex] == generation) {
                return legacyPaletteIndexes[combinedIndex];
            }
            final int paletteIndex = paletteIndex(Material.getByCombinedIndex(combinedIndex));
            legacyPaletteIndexes[combinedIndex] = paletteIndex;
            legacyLookupGenerations[combinedIndex] = generation;
            return paletteIndex;
        }

        private void ensurePaletteLookup(int required) {
            if (required * 4 <= paletteLookupKeys.length * 3) {
                return;
            }
            final Material[] oldKeys = paletteLookupKeys;
            final int[] oldValues = paletteLookupValues;
            final int newCapacity = oldKeys.length << 1;
            paletteLookupKeys = new Material[newCapacity];
            paletteLookupValues = new int[newCapacity];
            for (int i = 0; i < oldKeys.length; i++) {
                final Material key = oldKeys[i];
                if (key != null) {
                    int slot = spread(key.hashCode()) & (newCapacity - 1);
                    while (paletteLookupKeys[slot] != null) {
                        slot = (slot + 1) & (newCapacity - 1);
                    }
                    paletteLookupKeys[slot] = key;
                    paletteLookupValues[slot] = oldValues[i];
                }
            }
        }

        private static int spread(int hash) {
            return hash ^ (hash >>> 16);
        }

        private Material[] snapshotPalette() {
            if (paletteSnapshot.length < palette.size()) {
                paletteSnapshot = new Material[grow(paletteSnapshot.length, palette.size())];
            }
            for (int i = 0; i < palette.size(); i++) {
                paletteSnapshot[i] = palette.get(i);
            }
            return paletteSnapshot;
        }

        private void ensureScratch(int required) {
            if (indices.length < required) {
                indices = new int[grow(indices.length, required)];
            }
        }

        private void ensurePaletteRemap(int required) {
            if (paletteRemap.length < required) {
                paletteRemap = new int[grow(paletteRemap.length, required)];
            }
        }

        private void ensureBuffer(int required) {
            if (buffer.capacity() < required) {
                buffer = ByteBuffer.allocateDirect(grow(buffer.capacity(), required))
                        .order(ByteOrder.LITTLE_ENDIAN);
            }
        }

        private static int grow(int current, int required) {
            int capacity = Math.max(4096, current);
            while (capacity < required) {
                final int next = capacity << 1;
                if (next <= capacity) {
                    return required;
                }
                capacity = next;
            }
            return capacity;
        }
    }
}
