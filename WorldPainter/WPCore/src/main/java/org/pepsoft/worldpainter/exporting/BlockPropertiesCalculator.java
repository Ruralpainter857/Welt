/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.pepsoft.worldpainter.exporting;

import org.pepsoft.minecraft.Chunk;
import org.pepsoft.minecraft.Material;
import org.pepsoft.util.Box;
import org.pepsoft.worldpainter.Platform;

import java.util.Arrays;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.Math.max;
import static org.pepsoft.minecraft.Constants.MC_DISTANCE;
import static org.pepsoft.minecraft.Constants.MC_WATER;
import static org.pepsoft.minecraft.Material.*;
import static org.pepsoft.util.MathUtils.clamp;
import static org.pepsoft.worldpainter.Platform.ATTRIBUTE_WATER_OPACITY;
import static org.pepsoft.worldpainter.Platform.Capability.LEAF_DISTANCES;
import static org.pepsoft.worldpainter.Platform.Capability.PRECALCULATED_LIGHT;
import static org.pepsoft.worldpainter.exporting.WorldExportSettings.Step.LEAVES;
import static org.pepsoft.worldpainter.exporting.WorldExportSettings.Step.LIGHTING;

/**
 * A block properties calculator for MinecraftWorlds. This can calculate properties of blocks that are influenced by
 * neighbouring blocks and may therefore need multiple passes. It can currently calculate three properties: daylight,
 * block light and leaf distance. It can also remove leaf blocks for which the distance is too great.
 * 
 * <p>The process consists of three passes. In the first pass, the blocks are set to their initial values:
 *
 * <ul>
 *     <li>Daylight is initialised to full daylight for those blocks not covered from above
 *     <li>Block light is initialised to the appropriate value for each material according to the materials database
 *     <li>The leaf distance property is removed for all leaf blocks
 * </ul>
 *
 * <p>In the second pass, the previously calculated values are propagated into the surrounding blocks. The second pass
 * should be repeated until no changes result from it, meaning the area has been fully processed.
 *
 * <p>In the third pass the process is finalised, e.g. any floating leaf blocks are removed. The third pass should be
 * executed after the second pass has returned {@code false}.
 *
 * <p>This class uses the Minecraft coordinate system.
 *
 * @author pepijn
 */
public class BlockPropertiesCalculator {
    private static final String FRONTIER_PROPERTY = "welt.export.blockPropertiesFrontier";
    private static final String FRONTIER_PROFILE_PROPERTY = "welt.export.profileBlockPropertiesFrontier";
    private static final String DISABLE_INITIAL_HEIGHT_CACHE_PROPERTY = "welt.export.disableInitialHeightCache";
    private static final AtomicLong FRONTIER_PROFILE_PASSES = new AtomicLong();
    private static final AtomicLong FRONTIER_PROFILE_RECTANGLE_CELLS = new AtomicLong();
    private static final AtomicLong FRONTIER_PROFILE_PROCESSED_CELLS = new AtomicLong();
    private static final AtomicLong FRONTIER_PROFILE_CHANGED_CELLS = new AtomicLong();

    public static void resetFrontierProfile() {
        FRONTIER_PROFILE_PASSES.set(0);
        FRONTIER_PROFILE_RECTANGLE_CELLS.set(0);
        FRONTIER_PROFILE_PROCESSED_CELLS.set(0);
        FRONTIER_PROFILE_CHANGED_CELLS.set(0);
    }

    /** Returns pass count, equivalent rectangle cells, visited cells, and changed cells. */
    public static long[] frontierProfileSnapshot() {
        return new long[]{FRONTIER_PROFILE_PASSES.get(), FRONTIER_PROFILE_RECTANGLE_CELLS.get(),
                FRONTIER_PROFILE_PROCESSED_CELLS.get(), FRONTIER_PROFILE_CHANGED_CELLS.get()};
    }

    public BlockPropertiesCalculator(MinecraftWorld world, Platform platform, WorldExportSettings worldExportSettings, BlockBasedExportSettings exportSettings) {
        this.world = world;
        skyLight = isSkyLightNeeded(platform, worldExportSettings, exportSettings);
        blockLight = isBlockLightNeeded(platform, worldExportSettings, exportSettings);
        leafDistance = isLeafDistanceNeeded(platform, worldExportSettings, exportSettings);
        removeFloatingLeaves = leafDistance && exportSettings.isRemoveFloatingLeaves();
        if ((! skyLight) && (! blockLight) && (! leafDistance)) {
            throw new IllegalArgumentException("Nothing to do");
        }
        minHeight = world.getMinHeight();
        maxHeight = world.getMaxHeight();
        waterOpacity = platform.getAttribute(ATTRIBUTE_WATER_OPACITY);
    }

    /**
     * Get the current dirty area in Minecraft coordinates.
     *
     * @return The current dirty area in Minecraft coordinates.
     */
    public Box getDirtyArea() {
        return dirtyArea;
    }

    /**
     * Set the dirty area in Minecraft coordinates.
     *
     * @param dirtyArea The dirty area in Minecraft coordinates to set.
     */
    public void setDirtyArea(Box dirtyArea) {
        this.dirtyArea = dirtyArea;
        originalDirtyArea = dirtyArea.clone();
        final int x1InChunks = dirtyArea.getX1() >> 4, z1InChunks = dirtyArea.getZ1() >> 4,
                x2InChunks = (dirtyArea.getX2() - 1) >> 4, z2InChunks = (dirtyArea.getZ2() - 1) >> 4;
        maxHeightsXOffset = x1InChunks;
        maxHeightsZOffset = z1InChunks;
        maxHeights = new int[z2InChunks - z1InChunks + 1][x2InChunks - x1InChunks + 1];
        for (int[] row: maxHeights) {
            Arrays.fill(row, Integer.MIN_VALUE);
        }
        // Create a map of the highest block in each column that could possibly be affected (e.g. could have block light
        // > 0, sky light < 15, or leaf blocks)
        for (int chunkZ = z1InChunks; chunkZ <= z2InChunks; chunkZ++) {
            for (int chunkX = x1InChunks; chunkX <= x2InChunks; chunkX++) {
                final Chunk chunk = world.getChunk(chunkX, chunkZ);
                final Integer cachedHighestNonAirBlock = (chunk != null && initialChunkHeightCache != null)
                        ? initialChunkHeightCache.remove(chunk) : null;
                final int highestPossibleAffectedBlock = (chunk != null)
                        ? Math.min(((cachedHighestNonAirBlock != null) ? cachedHighestNonAirBlock : chunk.getHighestNonAirBlock()) + 15, maxHeight - 1)
                        : Integer.MIN_VALUE;
                // Make sure the surrounding chunks are also at least as high, since blocks from this chunk could affect
                // the block lighting in them
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        final int xInMaxHeightsMap = chunkX + dx - maxHeightsXOffset;
                        final int zInMaxHeightsMap = chunkZ + dz - maxHeightsZOffset;
                        if ((xInMaxHeightsMap >= 0) && (zInMaxHeightsMap >= 0) && (zInMaxHeightsMap < maxHeights.length) && (xInMaxHeightsMap < maxHeights[0].length)) {
                            maxHeights[zInMaxHeightsMap][xInMaxHeightsMap] = max(maxHeights[zInMaxHeightsMap][xInMaxHeightsMap], highestPossibleAffectedBlock);
                        }
                    }
                }
            }
        }
        // The cache only bridges the initial per-chunk pass and this area setup. Do not retain chunks
        // outside the dirty area, or keep references alive for later propagation passes.
        if (initialChunkHeightCache != null) {
            initialChunkHeightCache.clear();
            initialChunkHeightCache = null;
        }
        useChangedBlockFrontier = Boolean.parseBoolean(System.getProperty(FRONTIER_PROPERTY, "true"));
        if (useChangedBlockFrontier) {
            initialiseChangedBlockFrontier(x1InChunks, x2InChunks, z1InChunks, z2InChunks);
        } else {
            activeFrontier = null;
            nextFrontier = null;
            frontierChunkPresent = null;
            frontierInitialScan = false;
        }
    }

    private void initialiseChangedBlockFrontier(int x1InChunks, int x2InChunks,
                                                int z1InChunks, int z2InChunks) {
        frontierChunkXOffset = x1InChunks;
        frontierChunkZOffset = z1InChunks;
        frontierChunksZ = z2InChunks - z1InChunks + 1;
        final int chunkCount = (x2InChunks - x1InChunks + 1) * frontierChunksZ;
        activeFrontier = new BitSet[chunkCount];
        nextFrontier = new BitSet[chunkCount];
        frontierChunkPresent = new boolean[chunkCount];
        frontierInitialScan = true;
        for (int chunkX = x1InChunks; chunkX <= x2InChunks; chunkX++) {
            for (int chunkZ = z1InChunks; chunkZ <= z2InChunks; chunkZ++) {
                final int slot = frontierSlot(chunkX, chunkZ);
                frontierChunkPresent[slot] = world.getChunk(chunkX, chunkZ) != null;
            }
        }
    }

    private int frontierSlot(int chunkX, int chunkZ) {
        return (chunkX - frontierChunkXOffset) * frontierChunksZ + chunkZ - frontierChunkZOffset;
    }

    private void addChangedBlockFrontier(int x, int y, int z) {
        addFrontierCell(x, y, z);
        addFrontierCell(x - 1, y, z);
        addFrontierCell(x + 1, y, z);
        addFrontierCell(x, y - 1, z);
        addFrontierCell(x, y + 1, z);
        addFrontierCell(x, y, z - 1);
        addFrontierCell(x, y, z + 1);
    }

    private void addFrontierCell(int x, int y, int z) {
        if ((x < originalDirtyArea.getX1()) || (x >= originalDirtyArea.getX2())
                || (y < originalDirtyArea.getY1()) || (y >= originalDirtyArea.getY2())
                || (z < originalDirtyArea.getZ1()) || (z >= originalDirtyArea.getZ2())) {
            return;
        }
        final int chunkX = x >> 4, chunkZ = z >> 4;
        final int slot = frontierSlot(chunkX, chunkZ);
        if (!frontierChunkPresent[slot]) {
            return;
        }
        final int maxY = Math.min(originalDirtyArea.getY2() - 1,
                maxHeights[chunkZ - maxHeightsZOffset][chunkX - maxHeightsXOffset]);
        if (y > maxY) {
            return;
        }
        final int cell = ((maxHeight - 1 - y) << 8) | ((z & 15) << 4) | (x & 15);
        BitSet candidates = nextFrontier[slot];
        if (candidates == null) {
            candidates = new BitSet();
            nextFrontier[slot] = candidates;
        }
        candidates.set(cell);
    }

    /**
     * Propagates the selected block properties within the current dirty area.
     * An exact frontier schedules only changed cells and their six face
     * neighbours on subsequent passes. Set {@code welt.export.blockPropertiesFrontier=false}
     * to use the historical rectangular scan. Returns {@code false} when no values changed.
     */
    public boolean secondPass() {
        final int x1InChunks = dirtyArea.getX1() >> 4, z1InChunks = dirtyArea.getZ1() >> 4,
                x2InChunks = (dirtyArea.getX2() - 1) >> 4, z2InChunks = (dirtyArea.getZ2() - 1) >> 4;
        final PassChanges changes = new PassChanges();
        final boolean profileFrontier = Boolean.getBoolean(FRONTIER_PROFILE_PROPERTY);
        long rectangleCells = 0, processedCells = 0;
        for (int chunkX = x1InChunks; chunkX <= x2InChunks; chunkX++) {
            for (int chunkZ = z1InChunks; chunkZ <= z2InChunks; chunkZ++) {
                final Chunk chunk = world.getChunk(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                final int maxY = Math.min(dirtyArea.getY2() - 1,
                        maxHeights[chunkZ - maxHeightsZOffset][chunkX - maxHeightsXOffset]);
                if (maxY < dirtyArea.getY1()) {
                    continue;
                }
                final int minXInChunk = Math.max(0, dirtyArea.getX1() - (chunkX << 4));
                final int maxXInChunk = Math.min(16, dirtyArea.getX2() - (chunkX << 4));
                final int minZInChunk = Math.max(0, dirtyArea.getZ1() - (chunkZ << 4));
                final int maxZInChunk = Math.min(16, dirtyArea.getZ2() - (chunkZ << 4));
                if ((minXInChunk >= maxXInChunk) || (minZInChunk >= maxZInChunk)) {
                    continue;
                }
                final int slot = useChangedBlockFrontier ? frontierSlot(chunkX, chunkZ) : -1;
                final boolean processFrontier = useChangedBlockFrontier && !frontierInitialScan;
                final BitSet candidates = processFrontier ? activeFrontier[slot] : null;
                final int scanCount = (maxY - dirtyArea.getY1() + 1)
                        * (maxXInChunk - minXInChunk) * (maxZInChunk - minZInChunk);
                if (profileFrontier) {
                    rectangleCells += scanCount;
                }
                if (processFrontier) {
                    if (candidates == null) {
                        continue;
                    }
                    for (int cell = candidates.nextSetBit(0); cell >= 0; cell = candidates.nextSetBit(cell + 1)) {
                        final int y = maxHeight - 1 - (cell >>> 8);
                        final int zInChunk = (cell >>> 4) & 15, xInChunk = cell & 15;
                        if ((y < dirtyArea.getY1()) || (y > maxY)
                                || (xInChunk < minXInChunk) || (xInChunk >= maxXInChunk)
                                || (zInChunk < minZInChunk) || (zInChunk >= maxZInChunk)) {
                            continue;
                        }
                        if (profileFrontier) {
                            processedCells++;
                        }
                        final int x = (chunkX << 4) | xInChunk, z = (chunkZ << 4) | zInChunk;
                        if (processSecondPassCell(chunk, xInChunk, y, zInChunk, x, z)) {
                            changes.record(x, y, z);
                            addChangedBlockFrontier(x, y, z);
                        }
                    }
                } else {
                    if (profileFrontier) {
                        processedCells += scanCount;
                    }
                    // Keep the default path's original nested iteration order and
                    // avoid coordinate division or a per-cell frontier branch.
                    for (int y = maxY; y >= dirtyArea.getY1(); y--) {
                        for (int zInChunk = minZInChunk; zInChunk < maxZInChunk; zInChunk++) {
                            for (int xInChunk = minXInChunk; xInChunk < maxXInChunk; xInChunk++) {
                                final int x = (chunkX << 4) | xInChunk, z = (chunkZ << 4) | zInChunk;
                                if (processSecondPassCell(chunk, xInChunk, y, zInChunk, x, z)) {
                                    changes.record(x, y, z);
                                    if (useChangedBlockFrontier) {
                                        addChangedBlockFrontier(x, y, z);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (profileFrontier) {
            FRONTIER_PROFILE_PASSES.incrementAndGet();
            FRONTIER_PROFILE_RECTANGLE_CELLS.addAndGet(rectangleCells);
            FRONTIER_PROFILE_PROCESSED_CELLS.addAndGet(processedCells);
            FRONTIER_PROFILE_CHANGED_CELLS.addAndGet(changes.changedCells);
        }
        if (changes.changed) {
            dirtyArea.setX1(Math.max(changes.lowestX - 1, originalDirtyArea.getX1()));
            dirtyArea.setX2(Math.min(changes.highestX + 2, originalDirtyArea.getX2()));
            dirtyArea.setZ1(Math.max(changes.lowestZ - 1, originalDirtyArea.getZ1()));
            dirtyArea.setZ2(Math.min(changes.highestZ + 2, originalDirtyArea.getZ2()));
            dirtyArea.setY1(max(changes.lowestY, minHeight));
            dirtyArea.setY2(Math.min(changes.highestY + 1, maxHeight));
            if (useChangedBlockFrontier) {
                final BitSet[] previousFrontier = activeFrontier;
                activeFrontier = nextFrontier;
                nextFrontier = previousFrontier;
                for (BitSet candidates : nextFrontier) {
                    if (candidates != null) {
                        candidates.clear();
                    }
                }
                frontierInitialScan = false;
            }
        }
        return changes.changed;
    }

    private boolean processSecondPassCell(Chunk chunk, int xInChunk, int y, int zInChunk, int x, int z) {
        boolean changedBlock = false;
        Material material = chunk.getMaterial(xInChunk, y, zInChunk);
        if (leafDistance && material.leafBlock) {
            final int currentDistance = material.getProperty(DISTANCE, 8);
            final int distance = Math.min(currentDistance, calculateDistance(chunk, x, y, z));
            if (distance != currentDistance) {
                material = material.withProperty(DISTANCE, distance);
                chunk.setMaterial(xInChunk, y, zInChunk, material);
                changedBlock = true;
            }
        }
        final int opacity = ((skyLight || blockLight) && !material.opaque) ? getOpacity(material) : 0;
        if (skyLight) {
            final int currentSkylightLevel = chunk.getSkyLightLevel(xInChunk, y, zInChunk);
            final int newSkylightLevel;
            if (material.opaque) {
                newSkylightLevel = 0;
            } else {
                newSkylightLevel = (currentSkylightLevel < 15)
                        ? calculateSkyLightLevel(chunk, x, y, z, material, opacity) : 15;
            }
            if (newSkylightLevel != currentSkylightLevel) {
                chunk.setSkyLightLevel(xInChunk, y, zInChunk, newSkylightLevel);
                changedBlock = true;
            }
        }
        if (blockLight) {
            final int currentBlockLightLevel = chunk.getBlockLightLevel(xInChunk, y, zInChunk);
            final int newBlockLightLevel = material.opaque
                    ? (material.blockLight > 0 ? currentBlockLightLevel : 0)
                    : max(currentBlockLightLevel, calculateBlockLightLevel(chunk, x, y, z, material, opacity));
            if (newBlockLightLevel != currentBlockLightLevel) {
                chunk.setBlockLightLevel(xInChunk, y, zInChunk, newBlockLightLevel);
                changedBlock = true;
            }
        }
        return changedBlock;
    }
    /**
     * Set the blocks to their initial values for one entire chunk.
     */
    public int[] firstPass(Chunk chunk) {
        final int highestNonAirBlock = chunk.getHighestNonAirBlock();
        if (cacheInitialChunkHeights) {
            if (initialChunkHeightCache == null) {
                initialChunkHeightCache = new IdentityHashMap<>();
            }
            initialChunkHeightCache.put(chunk, highestNonAirBlock);
        }
        for (int x = 0; x < 16; x++) {
            Arrays.fill(DAYLIGHT[x], true);
            Arrays.fill(HEIGHT[x], clamp(minHeight, highestNonAirBlock, maxHeight - 1));
        }
        // The point above which there are only transparent, non light source and non-leaf blocks
        int dirtyVolumeHighMark = minHeight;
        // The point below which there are only non-transparent, non light source and non-leaf blocks
        int dirtyVolumeLowMark = maxHeight - 1;
        int maxY = clamp(minHeight - 1, highestNonAirBlock, maxHeight - 1);
        // Round to top of section:
        maxY = (((maxY >> 4) + 1) << 4) - 1;
        for (int y = maxY; y >= minHeight; y--) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Material material = chunk.getMaterial(x, y, z);
                    if (leafDistance && material.leafBlock) {
                        if (material.isPropertySet(MC_DISTANCE)) {
                            material = material.withoutProperty(MC_DISTANCE);
                            chunk.setMaterial(x, y, z, material);
                        }
                        if (y < dirtyVolumeLowMark) {
                            dirtyVolumeLowMark = y;
                        }
                        if (y > dirtyVolumeHighMark) {
                            dirtyVolumeHighMark = y;
                        }
                    }
                    if (skyLight) {
                        final int skyLightLevel = chunk.getSkyLightLevel(x, y, z);
                        final int newSkyLightLevel;
                        if (! material.opaque) {
                            // Transparent or translucent block
                            if (y < dirtyVolumeLowMark) {
                                dirtyVolumeLowMark = y;
                            }
                            int opacity = getOpacity(material);
                            if ((opacity == 0) && (DAYLIGHT[x][z])) {
                                // Propagate daylight down
                                newSkyLightLevel = 15;
                                HEIGHT[x][z] = y;
                            } else {
                                if ((opacity > 0) && (y > dirtyVolumeHighMark)) {
                                    dirtyVolumeHighMark = y;
                                }
                                newSkyLightLevel = 0; // TODO adjust with transparency of block above and 1-per-block falloff rather than going straight to zero
                                DAYLIGHT[x][z] = false;
                            }
                        } else {
                            if (y > dirtyVolumeHighMark) {
                                dirtyVolumeHighMark = y;
                            }
                            newSkyLightLevel = 0;
                            DAYLIGHT[x][z] = false;
                        }
                        if (newSkyLightLevel != skyLightLevel) {
                            chunk.setSkyLightLevel(x, y, z, newSkyLightLevel);
                        }
                    }
                    if (blockLight) {
                        final int blockLightLevel = chunk.getBlockLightLevel(x, y, z);
                        final int newBlockLightLevel;
                        if (material.blockLight > 0) {
                            if (y > dirtyVolumeHighMark) {
                                dirtyVolumeHighMark = y;
                            }
                            if (y < dirtyVolumeLowMark) {
                                dirtyVolumeLowMark = y;
                            }
                            newBlockLightLevel = material.blockLight;
                        } else {
                            newBlockLightLevel = 0;
                        }
                        if (newBlockLightLevel != blockLightLevel) {
                            chunk.setBlockLightLevel(x, y, z, newBlockLightLevel);
                        }
                    }
                }
            }
        }
        if (skyLight) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    if (chunk.getHeight(x, z) != HEIGHT[x][z]) {
                        chunk.setHeight(x, z, HEIGHT[x][z]);
                    }
                }
            }
        }
        return new int[] { dirtyVolumeLowMark, dirtyVolumeHighMark} ;
    }

    /**
     * Set the blocks to their initial values for the current dirty area.
     */
    public void firstPass() {
        final int x1InChunks = dirtyArea.getX1() >> 4, z1InChunks = dirtyArea.getZ1() >> 4,
                x2InChunks = (dirtyArea.getX2() - 1) >> 4, z2InChunks = (dirtyArea.getZ2() - 1) >> 4;
        for (int chunkX = x1InChunks; chunkX <= x2InChunks; chunkX++) {
            for (int chunkZ = z1InChunks; chunkZ <= z2InChunks; chunkZ++) {
                final Chunk chunk = world.getChunk(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                int maxY = clamp(minHeight - 1, chunk.getHighestNonAirBlock(), dirtyArea.getY2());
                for (int xInChunk = 0; xInChunk < 16; xInChunk++) {
                    for (int zInChunk = 0; zInChunk < 16; zInChunk++) {
                        final int x = (chunkX << 4) | xInChunk, z = (chunkZ << 4) | zInChunk;
                        int skyLightLevelAbove = (maxY >= (world.getMaxHeight() - 1)) ? 15 : world.getSkyLightLevel(x, z, maxY + 1);
                        for (int y = maxY; y >= dirtyArea.getY1(); y--) {
                            Material material = chunk.getMaterial(xInChunk, y, zInChunk);
                            if (leafDistance && material.leafBlock) {
                                if (material.isPropertySet(MC_DISTANCE)) {
                                    material = material.withoutProperty(MC_DISTANCE);
                                    chunk.setMaterial(xInChunk, y, zInChunk, material);
                                }
                            }
                            if (skyLight) {
                                final int skyLightLevel = chunk.getSkyLightLevel(xInChunk, y, zInChunk);
                                final int newSkyLightLevel;
                                if (! material.opaque) {
                                    // Transparent block, or unknown block. We err on the
                                    // side of transparency for unknown blocks to try and
                                    // cause less visible lighting bugs
                                    int transparency = getOpacity(material);
                                    if ((transparency == 0) && (skyLightLevelAbove == 15)) {
                                        // Propagate daylight down
                                        newSkyLightLevel = 15;
                                    } else {
                                        newSkyLightLevel = max(skyLightLevelAbove - max(transparency, 1), 0);
                                    }
                                } else {
                                    newSkyLightLevel = 0;
                                }
                                skyLightLevelAbove = newSkyLightLevel;
                                if (newSkyLightLevel != skyLightLevel) {
                                    chunk.setSkyLightLevel(xInChunk, y, zInChunk, newSkyLightLevel);
                                }
                            }
                            if (blockLight) {
                                final int blockLightLevel = chunk.getBlockLightLevel(xInChunk, y, zInChunk);
                                final int newBlockLightLevel = material.blockLight;
                                if (newBlockLightLevel != blockLightLevel) {
                                    chunk.setBlockLightLevel(xInChunk, y, zInChunk, newBlockLightLevel);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * This should be invoked once after {@link #secondPass()} has returned {@code false}, to take any necessary final
     * steps of the process, such as removing floating leaf blocks.
     */
    public void finalise() {
        if (! (removeFloatingLeaves || skyLight)) {
            return;
        }
        final int x1InChunks = originalDirtyArea.getX1() >> 4, z1InChunks = originalDirtyArea.getZ1() >> 4,
                x2InChunks = (originalDirtyArea.getX2() - 1) >> 4, z2InChunks = (originalDirtyArea.getZ2() - 1) >> 4;
        for (int chunkX = x1InChunks; chunkX <= x2InChunks; chunkX++) {
            for (int chunkZ = z1InChunks; chunkZ <= z2InChunks; chunkZ++) {
                final Chunk chunk = world.getChunkForEditing(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                final int maxY = Math.min(originalDirtyArea.getY2() - 1, maxHeights[chunkZ - maxHeightsZOffset][chunkX - maxHeightsXOffset]);
                for (int xInChunk = 0; xInChunk < 16; xInChunk++) {
                    for (int zInChunk = 0; zInChunk < 16; zInChunk++) {
                        final int x = (chunkX << 4) | xInChunk, z = (chunkZ << 4) | zInChunk;
                        for (int y = maxY; y >= originalDirtyArea.getY1() ; y--) {
                            Material material = chunk.getMaterial(xInChunk, y, zInChunk);
                            // TODO this class is a "calculator"; the actual removal of leaves should be moved up to the caller
                            if (removeFloatingLeaves && material.leafBlock && material.isPropertySet(MC_DISTANCE) && (material.getProperty(DISTANCE) > 6) && (! material.is(PERSISTENT))) {
                                material = AIR;
                                chunk.setMaterial(xInChunk, y, zInChunk, material);
                                if (skyLight) {
                                    final int currentSkylightLevel = chunk.getSkyLightLevel(xInChunk, y, zInChunk);
                                    final int newSkyLightLevel = (currentSkylightLevel < 15) ? calculateSkyLightLevel(chunk, x, y, z, material, getOpacity(material)) : 15;
                                    if (newSkyLightLevel != currentSkylightLevel) {
                                        chunk.setSkyLightLevel(xInChunk, y, zInChunk, newSkyLightLevel);
                                        // As a quick hack to avoid the worst lighting bugs, propagate new daylight down
                                        // until we hit a non-transparent block
                                        if (newSkyLightLevel == 15) {
                                            for (int y2 = y - 1; y2 >= originalDirtyArea.getY1() ; y2--) {
                                                if (chunk.getMaterial(xInChunk, y2, zInChunk).transparent) {
                                                    chunk.setSkyLightLevel(xInChunk, y, zInChunk, newSkyLightLevel);
                                                } else {
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                }
                                if (blockLight) {
                                    final int currentBlockLightLevel = chunk.getBlockLightLevel(xInChunk, y, zInChunk);
                                    final int newBlockLightLevel = calculateBlockLightLevel(chunk, x, y, z, material, getOpacity(material));
                                    if (newBlockLightLevel != currentBlockLightLevel) {
                                        chunk.setBlockLightLevel(xInChunk, y, zInChunk, newBlockLightLevel);
                                    }
                                }
                                // NOTE: in theory we should start all the way over with the lighting calculations, but
                                // that would take a huge amount of time again, so instead we just hope the lighting
                                // bugs are not too obvious
                            } else if (skyLight && material.receivesLight) {
                                // Dirty hack to fix lighting of weird blocks that receive light but don't transmit it
                                // NOTE: this is not entirely correct since it only looks above, but it's correct in 99%
                                // of cases and hardly noticeable in the other 1%
                                final int skyLightAbove = (y < (maxHeight - 1)) ? chunk.getSkyLightLevel(xInChunk, y + 1, zInChunk) : 15;
                                final int newSkyLight = (skyLightAbove == 15) ? 15 : max(skyLightAbove - 1, 0);
                                chunk.setSkyLightLevel(xInChunk, y, zInChunk, newSkyLight);
                            }
                        }
                    }
                }
            }
        }
    }

    public static boolean isBlockPropertiesPassNeeded(Platform platform, WorldExportSettings worldExportSettings, BlockBasedExportSettings exportSettings) {
        boolean skyLight = isSkyLightNeeded(platform, worldExportSettings, exportSettings);
        boolean blockLight = isBlockLightNeeded(platform, worldExportSettings, exportSettings);
        boolean leafDistance = isLeafDistanceNeeded(platform, worldExportSettings, exportSettings);
        return skyLight || blockLight || leafDistance;
    }

    private static boolean isLeafDistanceNeeded(Platform platform, WorldExportSettings worldExportSettings, BlockBasedExportSettings exportSettings) {
        return ((worldExportSettings == null) || (worldExportSettings.getStepsToSkip() == null) || (!worldExportSettings.getStepsToSkip().contains(LEAVES)))
                && exportSettings.isCalculateLeafDistance()
                && platform.capabilities.contains(LEAF_DISTANCES);
    }

    private static boolean isBlockLightNeeded(Platform platform, WorldExportSettings worldExportSettings, BlockBasedExportSettings exportSettings) {
        return ((worldExportSettings == null) || (worldExportSettings.getStepsToSkip() == null) || (!worldExportSettings.getStepsToSkip().contains(LIGHTING)))
                && exportSettings.isCalculateBlockLight()
                && platform.capabilities.contains(PRECALCULATED_LIGHT);
    }

    private static boolean isSkyLightNeeded(Platform platform, WorldExportSettings worldExportSettings, BlockBasedExportSettings exportSettings) {
        return ((worldExportSettings == null) || (worldExportSettings.getStepsToSkip() == null) || (!worldExportSettings.getStepsToSkip().contains(LIGHTING)))
                && exportSettings.isCalculateSkyLight()
                && platform.capabilities.contains(PRECALCULATED_LIGHT);
    }

    private int getOpacity(Material material) {
        if (material.containsWater()) {
            return waterOpacity;
        } else {
            return material.opacity;
        }
    }

    // MC coordinate system
    private int calculateSkyLightLevel(Chunk chunk, int x, int y, int z, Material material, int opacity) {
        int skyLightLevel = getSkyLightLevelAt(chunk, x, y + 1, z);
        if ((skyLightLevel == 15)
                && (waterOpacity == 1)
                && (material.isNamed(MC_WATER))
                && ((y >= maxHeight - 1) || world.getMaterialAt(x, z, y + 1).empty)) {
            // This seems to be a special case in MC 1.15. TODO: keep an eye on whether this was a bug or intended behaviour!
            return 15;
        }
        int highestSurroundingSkyLight = skyLightLevel;
        if (highestSurroundingSkyLight < 15) {
            skyLightLevel = getSkyLightLevelAt(chunk, x - 1, y, z);
            if (skyLightLevel > highestSurroundingSkyLight) {
                highestSurroundingSkyLight = skyLightLevel;
            }
            if (highestSurroundingSkyLight < 15) {
                skyLightLevel = getSkyLightLevelAt(chunk, x + 1, y, z);
                if (skyLightLevel > highestSurroundingSkyLight) {
                    highestSurroundingSkyLight = skyLightLevel;
                }
                if (highestSurroundingSkyLight < 15) {
                    skyLightLevel = getSkyLightLevelAt(chunk, x, y, z - 1);
                    if (skyLightLevel > highestSurroundingSkyLight) {
                        highestSurroundingSkyLight = skyLightLevel;
                    }
                    if (highestSurroundingSkyLight < 15) {
                        skyLightLevel = getSkyLightLevelAt(chunk, x, y, z + 1);
                        if (skyLightLevel > highestSurroundingSkyLight) {
                            highestSurroundingSkyLight = skyLightLevel;
                        }
                        if (highestSurroundingSkyLight < 15) {
                            skyLightLevel = getSkyLightLevelAt(chunk, x, y - 1, z);
                            if (skyLightLevel > highestSurroundingSkyLight) {
                                highestSurroundingSkyLight = skyLightLevel;
                            }
                        }
                    }
                }
            }
        }
        return max(highestSurroundingSkyLight - max(opacity, 1), 0);
    }

    // MC coordinate system
    private int calculateBlockLightLevel(Chunk chunk, int x, int y, int z,
                                         Material material, int opacity) {
        int blockLightLevel = getBlockLightLevelAt(chunk, x, y + 1, z);
        int highestSurroundingBlockLight = blockLightLevel;
        if (highestSurroundingBlockLight < 15) {
            blockLightLevel = getBlockLightLevelAt(chunk, x - 1, y, z);
            if (blockLightLevel > highestSurroundingBlockLight) {
                highestSurroundingBlockLight = blockLightLevel;
            }
            if (highestSurroundingBlockLight < 15) {
                blockLightLevel = getBlockLightLevelAt(chunk, x + 1, y, z);
                if (blockLightLevel > highestSurroundingBlockLight) {
                    highestSurroundingBlockLight = blockLightLevel;
                }
                if (highestSurroundingBlockLight < 15) {
                    blockLightLevel = getBlockLightLevelAt(chunk, x, y, z - 1);
                    if (blockLightLevel > highestSurroundingBlockLight) {
                        highestSurroundingBlockLight = blockLightLevel;
                    }
                    if (highestSurroundingBlockLight < 15) {
                        blockLightLevel = getBlockLightLevelAt(chunk, x, y, z + 1);
                        if (blockLightLevel > highestSurroundingBlockLight) {
                            highestSurroundingBlockLight = blockLightLevel;
                        }
                        if (highestSurroundingBlockLight < 15) {
                            blockLightLevel = getBlockLightLevelAt(chunk, x, y - 1, z);
                            if (blockLightLevel > highestSurroundingBlockLight) {
                                highestSurroundingBlockLight = blockLightLevel;
                            }
                        }
                    }
                }
            }
        }
        return max(highestSurroundingBlockLight - max(opacity, 1), 0);
    }

    // MC coordinate system
    private int getSkyLightLevelAt(Chunk chunk, int x, int y, int z) {
        if (y < minHeight) {
            return 0;
        } else if (y >= maxHeight) {
            return 15;
        } else if (((x >> 4) == chunk.getxPos()) && ((z >> 4) == chunk.getzPos())) {
            return chunk.getSkyLightLevel(x & 0xf, y, z & 0xf);
        } else {
            return world.getSkyLightLevel(x, z, y);
        }
    }

    // MC coordinate system
    private int getBlockLightLevelAt(Chunk chunk, int x, int y, int z) {
        if ((y < minHeight) || (y >= maxHeight)) {
            return 0;
        } else if (((x >> 4) == chunk.getxPos()) && ((z >> 4) == chunk.getzPos())) {
            return chunk.getBlockLightLevel(x & 0xf, y, z & 0xf);
        } else {
            return world.getBlockLightLevel(x, z, y);
        }
    }

    // MC coordinate system
    private int calculateDistance(Chunk chunk, int x, int y, int z) {
        int distance = getLeafDistanceTo(chunk, x, y, z + 1);
        if (distance == 1) {
            return distance;
        }
        distance = Math.min(distance, getLeafDistanceTo(chunk, x - 1, y, z));
        if (distance == 1) {
            return distance;
        }
        distance = Math.min(distance, getLeafDistanceTo(chunk, x, y - 1, z));
        if (distance == 1) {
            return distance;
        }
        distance = Math.min(distance, getLeafDistanceTo(chunk, x + 1, y, z));
        if (distance == 1) {
            return distance;
        }
        distance = Math.min(distance, getLeafDistanceTo(chunk, x, y + 1, z));
        if (distance == 1) {
            return distance;
        }
        return Math.min(distance, getLeafDistanceTo(chunk, x, y, z - 1));
    }

    // MC coordinate system
    private int getLeafDistanceTo(Chunk chunk, int x, int y, int z) {
        final Material material;
        if ((y < minHeight) || (y >= maxHeight)) {
            return 7;
        } else if (((x >> 4) == chunk.getxPos()) && ((z >> 4) == chunk.getzPos())) {
            material = chunk.getMaterial(x & 0xf, y, z & 0xf);
        } else {
            material = world.getMaterialAt(x, z, y);
        }
        if (material.sustainsLeaves) {
            return 1;
        } else if (material.leafBlock && material.isPropertySet(MC_DISTANCE)) {
            return material.getProperty(DISTANCE) + 1;
        } else {
            return 7;
        }
    }

    private final MinecraftWorld world;
    private final boolean skyLight, blockLight, leafDistance, removeFloatingLeaves;
    private final int minHeight, maxHeight, waterOpacity;
    private final boolean cacheInitialChunkHeights = !Boolean.getBoolean(DISABLE_INITIAL_HEIGHT_CACHE_PROPERTY);
    private IdentityHashMap<Chunk, Integer> initialChunkHeightCache;
    private Box originalDirtyArea, dirtyArea;
    private int[][] maxHeights;
    private int maxHeightsXOffset, maxHeightsZOffset;
    private boolean useChangedBlockFrontier;
    private int frontierChunkXOffset, frontierChunkZOffset, frontierChunksZ;
    private BitSet[] activeFrontier, nextFrontier;
    private boolean[] frontierChunkPresent;
    private boolean frontierInitialScan;

    private final boolean[][] DAYLIGHT = new boolean[16][16];
    private final int[][] HEIGHT = new int[16][16];

    private static final class PassChanges {
        private boolean changed;
        private int lowestX = Integer.MAX_VALUE, highestX = Integer.MIN_VALUE;
        private int lowestZ = Integer.MAX_VALUE, highestZ = Integer.MIN_VALUE;
        private int lowestY = Integer.MAX_VALUE, highestY = Integer.MIN_VALUE;
        private long changedCells;

        private void record(int x, int y, int z) {
            changed = true;
            changedCells++;
            lowestX = Math.min(lowestX, x);
            highestX = Math.max(highestX, x);
            lowestZ = Math.min(lowestZ, z);
            highestZ = Math.max(highestZ, z);
            lowestY = Math.min(lowestY, y - 1);
            highestY = Math.max(highestY, y + 1);
        }
    }
}
