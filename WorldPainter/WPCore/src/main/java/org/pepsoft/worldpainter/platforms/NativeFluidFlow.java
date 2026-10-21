package org.pepsoft.worldpainter.platforms;

import org.pepsoft.minecraft.Chunk;
import org.pepsoft.minecraft.ChunkPaletteBuffer;
import org.pepsoft.minecraft.Material;
import org.pepsoft.util.ProgressReceiver;
import org.pepsoft.worldpainter.exporting.MinecraftWorld;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import static org.pepsoft.minecraft.Constants.MC_LAVA;
import static org.pepsoft.minecraft.Constants.MC_WATER;

/** Runs the read-only fluid update scan against modern chunk palettes in one JNI call per chunk. */
public final class NativeFluidFlow {
    private static final int ANY_WATER = 1;
    private static final int CONTAINS_WATER = 2;
    private static final int LAVA = 4;
    private static final int SOLID = 8;

    private static final ThreadLocal<Buffers> BUFFERS = ThreadLocal.withInitial(Buffers::new);
    private static final AtomicLong NATIVE_CHUNKS = new AtomicLong();
    private static final AtomicLong JAVA_FALLBACK_CHUNKS = new AtomicLong();
    private static final AtomicLong PREPARATION_NANOS = new AtomicLong();
    private static final AtomicLong NATIVE_CALL_NANOS = new AtomicLong();
    private static final AtomicLong APPLY_NANOS = new AtomicLong();
    private static final AtomicLong SNAPSHOT_CHUNKS = new AtomicLong();
    private static final AtomicLong LIVE_PALETTE_CHUNKS = new AtomicLong();

    private NativeFluidFlow() {
    }

    public static void resetProfile() {
        NATIVE_CHUNKS.set(0L);
        JAVA_FALLBACK_CHUNKS.set(0L);
        PREPARATION_NANOS.set(0L);
        NATIVE_CALL_NANOS.set(0L);
        APPLY_NANOS.set(0L);
        SNAPSHOT_CHUNKS.set(0L);
        LIVE_PALETTE_CHUNKS.set(0L);
    }

    public static long[] profileSnapshot() {
        return new long[]{NATIVE_CHUNKS.get(), JAVA_FALLBACK_CHUNKS.get(),
                PREPARATION_NANOS.get(), NATIVE_CALL_NANOS.get(), APPLY_NANOS.get(),
                SNAPSHOT_CHUNKS.get(), LIVE_PALETTE_CHUNKS.get()};
    }

    static boolean process(MinecraftWorld world, int x1, int x2, int y1, int y2,
                           int minY, int maxY, boolean flowWater, boolean flowLava,
                           ProgressReceiver progressReceiver) throws ProgressReceiver.OperationCancelled {
        if (!Native.isFluidFlowExportEnabled() || !NativeLoader.areSlicesAvailable()
                || (!flowWater && !flowLava)
                || x1 > x2 || y1 > y2
                || minY < world.getMinHeight() || maxY >= world.getMaxHeight()) {
            return false;
        }
        final int minChunkX = x1 >> 4, maxChunkX = x2 >> 4;
        final int minChunkZ = y1 >> 4, maxChunkZ = y2 >> 4;
        final long totalColumns = (long) (x2 - x1 + 1) * (y2 - y1 + 1);
        long processedColumns = 0;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            final int originX = chunkX << 4;
            final int localX1 = Math.max(0, x1 - originX), localX2 = Math.min(15, x2 - originX);
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                final int originZ = chunkZ << 4;
                final int localZ1 = Math.max(0, y1 - originZ), localZ2 = Math.min(15, y2 - originZ);
                final Chunk chunk = world.getChunkForEditing(chunkX, chunkZ);
                if (chunk != null) {
                    final boolean profile = Boolean.getBoolean("welt.export.profileFluidFlow");
                    final long preparationStart = profile ? System.nanoTime() : 0L;
                    final int highestNonAir = chunk.getHighestNonAirBlock();
                    final int chunkMaxY = Math.min(highestNonAir, maxY);
                    if (chunkMaxY >= minY + 1) {
                        final ChunkPaletteBuffer.LivePaletteView palette =
                                ChunkPaletteBuffer.openPaletteIndexView(chunk, minY, chunkMaxY);
                        if (palette == null) {
                            JAVA_FALLBACK_CHUNKS.incrementAndGet();
                            if (profile) PREPARATION_NANOS.addAndGet(System.nanoTime() - preparationStart);
                            processJavaChunk(world, chunkX, chunkZ, minY, maxY,
                                    localX1, localX2, localZ1, localZ2, flowWater, flowLava);
                        } else if (!processChunk(world, chunk, palette, chunkX, chunkZ,
                                minY, chunkMaxY, world.getMinHeight(), world.getMaxHeight() - 1,
                                localX1, localX2, localZ1, localZ2, flowWater, flowLava,
                                profile, preparationStart)) {
                            JAVA_FALLBACK_CHUNKS.incrementAndGet();
                            processJavaChunk(world, chunkX, chunkZ, minY, maxY,
                                    localX1, localX2, localZ1, localZ2, flowWater, flowLava);
                        }
                    }
                }
                processedColumns += (long) (localX2 - localX1 + 1) * (localZ2 - localZ1 + 1);
                if (progressReceiver != null) {
                    progressReceiver.setProgress(0.75f + 0.25f * (float) processedColumns / totalColumns);
                }
            }
        }
        return true;
    }

    private static boolean processChunk(MinecraftWorld world, Chunk chunk,
                                        ChunkPaletteBuffer.LivePaletteView palette,
                                        int chunkX, int chunkZ, int minY, int maxY,
                                        int worldMinY, int worldMaxY,
                                        int localX1, int localX2, int localZ1, int localZ2,
                                        boolean flowWater, boolean flowLava,
                                        boolean profile, long preparationStart) {
        final int height = maxY - minY + 1;
        final int sectionCount = palette.sectionCount();
        final Buffers buffers = BUFFERS.get();
        buffers.ensure(sectionCount, height);
        final int[][] sectionIndexes = buffers.sectionIndexes;
        final byte[][] paletteFlags = buffers.paletteFlags;
        for (int section = 0; section < sectionCount; section++) {
            sectionIndexes[section] = palette.indexes(section);
            final int paletteSize = palette.paletteSize(section);
            if (paletteSize <= 0 || paletteSize > 65_536) {
                Arrays.fill(sectionIndexes, 0, sectionCount, null);
                return false;
            }
            if (paletteFlags[section] == null || paletteFlags[section].length < paletteSize) {
                paletteFlags[section] = new byte[paletteSize];
            }
            final byte[] flags = paletteFlags[section];
            for (int index = 0; index < paletteSize; index++) {
                flags[index] = state(palette.paletteMaterial(section, index));
            }
            if (sectionIndexes[section] == null || sectionIndexes[section].length != 4096) {
                Arrays.fill(sectionIndexes, 0, sectionCount, null);
                return false;
            }
        }

        fillNeighbourEdges(world, chunkX, chunkZ, minY, height, buffers);
        if (profile) {
            PREPARATION_NANOS.addAndGet(System.nanoTime() - preparationStart);
        }
        final long nativeStart = profile ? System.nanoTime() : 0L;
        final boolean success;
        try {
            success = NativeSlices.findFluidUpdatesForChunk(minY, maxY, worldMinY, worldMaxY,
                    palette.minY(), sectionCount, flowWater, flowLava,
                    sectionIndexes, paletteFlags,
                    buffers.westEdge, buffers.eastEdge, buffers.northEdge, buffers.southEdge,
                    buffers.updates);
        } finally {
            Arrays.fill(sectionIndexes, 0, sectionCount, null);
        }
        if (profile) {
            NATIVE_CALL_NANOS.addAndGet(System.nanoTime() - nativeStart);
        }
        if (!success) {
            return false;
        }

        final long applyStart = profile ? System.nanoTime() : 0L;
        final int updateCapacity = height * 256;
        final int updateWordCount = (updateCapacity + Long.SIZE - 1) / Long.SIZE;
        if (buffers.updates.length < updateWordCount) {
            return false;
        }
        for (int wordIndex = 0; wordIndex < updateWordCount; wordIndex++) {
            long word = buffers.updates[wordIndex];
            while (word != 0L) {
                final int bit = Long.numberOfTrailingZeros(word);
                final int updateOffset = (wordIndex << 6) + bit;
                if (updateOffset >= updateCapacity) {
                    return false;
                }
                final int column = updateOffset / height;
                final int x = column >> 4, z = column & 15;
                final int y = minY + updateOffset % height;
                if ((x >= localX1) && (x <= localX2) && (z >= localZ1) && (z <= localZ2)
                        && (y > minY)) {
                    chunk.markForUpdateChunk(x, y, z);
                }
                word &= word - 1L;
            }
        }
        if (profile) {
            APPLY_NANOS.addAndGet(System.nanoTime() - applyStart);
        }
        if (palette.isSnapshot()) {
            SNAPSHOT_CHUNKS.incrementAndGet();
        } else {
            LIVE_PALETTE_CHUNKS.incrementAndGet();
        }
        NATIVE_CHUNKS.incrementAndGet();
        return true;
    }

    private static void fillNeighbourEdges(MinecraftWorld world, int chunkX, int chunkZ,
                                           int minY, int height, Buffers buffers) {
        final Chunk west = world.getChunk(chunkX - 1, chunkZ);
        final Chunk east = world.getChunk(chunkX + 1, chunkZ);
        final Chunk north = world.getChunk(chunkX, chunkZ - 1);
        final Chunk south = world.getChunk(chunkX, chunkZ + 1);
        for (int offset = 0; offset < height; offset++) {
            final int y = minY + offset;
            final int edgeOffset = offset << 4;
            for (int cell = 0; cell < 16; cell++) {
                buffers.westEdge[edgeOffset + cell] = state(west == null
                        ? Material.AIR : west.getMaterial(15, y, cell));
                buffers.eastEdge[edgeOffset + cell] = state(east == null
                        ? Material.AIR : east.getMaterial(0, y, cell));
                buffers.northEdge[edgeOffset + cell] = state(north == null
                        ? Material.AIR : north.getMaterial(cell, y, 15));
                buffers.southEdge[edgeOffset + cell] = state(south == null
                        ? Material.AIR : south.getMaterial(cell, y, 0));
            }
        }
    }

    private static byte state(Material material) {
        if (material == null) {
            return 0;
        }
        int flags = 0;
        final boolean containsWater = material.containsWater();
        if (containsWater || material.isNamed(MC_WATER)) {
            flags |= ANY_WATER;
        }
        if (containsWater) {
            flags |= CONTAINS_WATER;
        }
        if (material.isNamed(MC_LAVA)) {
            flags |= LAVA;
        }
        if (material.solid) {
            flags |= SOLID;
        }
        return (byte) flags;
    }

    private static void processJavaChunk(MinecraftWorld world, int chunkX, int chunkZ,
                                         int minY, int maxY,
                                         int localX1, int localX2, int localZ1, int localZ2,
                                         boolean flowWater, boolean flowLava) {
        final int originX = chunkX << 4, originZ = chunkZ << 4;
        final int worldMinY = world.getMinHeight(), worldMaxY = world.getMaxHeight() - 1;
        for (int x = localX1; x <= localX2; x++) {
            for (int z = localZ1; z <= localZ2; z++) {
                final int worldX = originX + x, worldZ = originZ + z;
                Material materialBelow = minY + 1 <= worldMinY
                        ? Material.AIR : world.getMaterialAt(worldX, worldZ, minY);
                Material materialAbove = world.getMaterialAt(worldX, worldZ, minY + 1);
                final int columnMaxY = Math.min(world.getHighestNonAirBlock(worldX, worldZ), maxY);
                for (int y = minY + 1; y <= columnMaxY; y++) {
                    final Material material = materialAbove;
                    materialAbove = (y < worldMaxY)
                            ? world.getMaterialAt(worldX, worldZ, y + 1) : Material.AIR;
                    if (flowWater && containsAnyWater(material)
                            && !isWaterContained(world, worldX, worldZ, y, materialBelow)) {
                        world.markForUpdateWorld(worldX, worldZ, y);
                    } else if (flowLava && material.isNamed(MC_LAVA)
                            && !isLavaContained(world, worldX, worldZ, y, materialBelow)) {
                        world.markForUpdateWorld(worldX, worldZ, y);
                    }
                    materialBelow = material;
                }
            }
        }
    }

    private static boolean containsAnyWater(Material material) {
        return material.containsWater() || material.isNamed(MC_WATER);
    }

    private static boolean isWaterContained(MinecraftWorld world, int x, int z,
                                            int y, Material materialBelow) {
        if (containsAnyWater(materialBelow)) {
            return true;
        }
        if (!materialBelow.containsWater() && !materialBelow.solid) {
            return false;
        }
        final Material north = world.getMaterialAt(x, z - 1, y);
        final Material east = world.getMaterialAt(x + 1, z, y);
        final Material south = world.getMaterialAt(x, z + 1, y);
        final Material west = world.getMaterialAt(x - 1, z, y);
        return (containsAnyWater(north) || north.solid)
                && (containsAnyWater(east) || east.solid)
                && (containsAnyWater(south) || south.solid)
                && (containsAnyWater(west) || west.solid);
    }

    private static boolean isLavaContained(MinecraftWorld world, int x, int z,
                                           int y, Material materialBelow) {
        if (materialBelow.isNamed(MC_LAVA)) {
            return true;
        }
        if (!materialBelow.isNamed(MC_LAVA) && !materialBelow.solid) {
            return false;
        }
        final Material north = world.getMaterialAt(x, z - 1, y);
        final Material east = world.getMaterialAt(x + 1, z, y);
        final Material south = world.getMaterialAt(x, z + 1, y);
        final Material west = world.getMaterialAt(x - 1, z, y);
        return (north.isNamed(MC_LAVA) || north.solid)
                && (east.isNamed(MC_LAVA) || east.solid)
                && (south.isNamed(MC_LAVA) || south.solid)
                && (west.isNamed(MC_LAVA) || west.solid);
    }

    private static final class Buffers {
        private final int[][] sectionIndexes = new int[256][];
        private final byte[][] paletteFlags = new byte[256][];
        private byte[] westEdge = new byte[0], eastEdge = new byte[0];
        private byte[] northEdge = new byte[0], southEdge = new byte[0];
        private long[] updates = new long[0];

        private void ensure(int sectionCount, int height) {
            final int edgeLength = height * 16;
            final int updateLength = height * 4;
            if (westEdge.length < edgeLength) westEdge = new byte[edgeLength];
            if (eastEdge.length < edgeLength) eastEdge = new byte[edgeLength];
            if (northEdge.length < edgeLength) northEdge = new byte[edgeLength];
            if (southEdge.length < edgeLength) southEdge = new byte[edgeLength];
            if (updates.length < updateLength) updates = new long[updateLength];
            if (sectionCount > sectionIndexes.length) {
                throw new IllegalArgumentException("Too many chunk sections: " + sectionCount);
            }
        }
    }
}
