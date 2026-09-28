/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.pepsoft.worldpainter.layers.exporters;

import org.pepsoft.minecraft.Chunk;
import org.pepsoft.minecraft.Material;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.Platform;
import org.pepsoft.worldpainter.exporting.AbstractLayerExporter;
import org.pepsoft.worldpainter.exporting.Fixup;
import org.pepsoft.worldpainter.exporting.MinecraftWorld;
import org.pepsoft.worldpainter.exporting.SecondPassLayerExporter;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import java.awt.*;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static java.util.Collections.singleton;
import static org.pepsoft.minecraft.Constants.MC_SNOW;
import static org.pepsoft.minecraft.Constants.MC_WATER;
import static org.pepsoft.minecraft.Material.*;
import static org.pepsoft.worldpainter.exporting.SecondPassLayerExporter.Stage.ADD_FEATURES;

/**
 * @author pepijn
 */
public class FrostExporter extends AbstractLayerExporter<Frost> implements SecondPassLayerExporter {
    public FrostExporter(Dimension dimension, Platform platform, ExporterSettings settings) {
        super(dimension, platform, (settings != null) ? settings : new FrostSettings(), Frost.INSTANCE);
    }

    @Override
    public Set<Stage> getStages() {
        return singleton(ADD_FEATURES);
    }

    @Override
    public List<Fixup> addFeatures(final Rectangle area, final Rectangle exportedArea, final MinecraftWorld minecraftWorld) {
        final FrostSettings settings = (FrostSettings) super.settings;
        final boolean frostEverywhere = settings.isFrostEverywhere();
        final int mode = settings.getMode();
        final boolean snowUnderTrees = settings.isSnowUnderTrees();
        final Random random = createRandom(); // Only used for random snow height, so it's not a big deal if it's different every time
        String customNoSnowOnIds = System.getProperty("org.pepsoft.worldpainter.noSnowOn");
        if ((customNoSnowOnIds != null) && (! customNoSnowOnIds.trim().isEmpty())) {
            throw new IllegalArgumentException("The org.pepsoft.worldpainter.noSnowOn property is no longer supported; please let the author know if you need it");
        }
        final long nativeColumnLength = (long) maxZ - minHeight + 1L;
        final boolean nativeFrost = Native.isFrostExportEnabled()
                && nativeColumnLength > 0 && nativeColumnLength <= 4096
                && (mode == FrostSettings.MODE_FLAT || mode == FrostSettings.MODE_RANDOM
                    || mode == FrostSettings.MODE_SMOOTH
                    || mode == FrostSettings.MODE_SMOOTH_AT_ALL_ELEVATIONS)
                && NativeLoader.areSlicesAvailable();
        if (nativeFrost && hasSafeBatchBounds(area)) {
            applyNativeBatches(minecraftWorld, area, frostEverywhere, snowUnderTrees, mode, random,
                    (int) nativeColumnLength);
            return null;
        }
        for (int x = area.x; x < area.x + area.width; x++) {
            for (int y = area.y; y < area.y + area.height; y++) {
                if (frostEverywhere || dimension.getBitLayerValueAt(Frost.INSTANCE, x, y)) {
                    int highestNonAirBlock = minecraftWorld.getHighestNonAirBlock(x, y);
                    applyJavaColumn(minecraftWorld, x, y, highestNonAirBlock,
                            frostEverywhere, snowUnderTrees, mode, random);
                }
            }
        }
        return null;
    }

    /** Random source seam for reproducible exporter tests; production keeps the historical seed source. */
    protected Random createRandom() {
        return new Random();
    }

    private static boolean hasSafeBatchBounds(Rectangle area) {
        return area.width > 0 && area.height > 0
                && (long) area.x + area.width <= Integer.MAX_VALUE
                && (long) area.y + area.height <= Integer.MAX_VALUE;
    }

    /** Packs eligible columns, invokes Rust once per batch, then applies results in Java order. */
    private void applyNativeBatches(MinecraftWorld world, Rectangle area,
                                    boolean frostEverywhere, boolean snowUnderTrees, int mode,
                                    Random random, int columnLength) {
        final int batchCapacity = Math.min(64, 1_048_576 / columnLength);
        final byte[] flags = new byte[batchCapacity * columnLength];
        final byte[] snowLayers = new byte[batchCapacity * columnLength];
        final byte[] updates = new byte[batchCapacity * columnLength];
        final int[] highest = new int[batchCapacity];
        final float[] heightsFloat = new float[batchCapacity];
        final int[] heightsInt = new int[batchCapacity];
        final int[] frostBitCounts = new int[batchCapacity];
        final int[] lowestVisitedZ = new int[batchCapacity];
        final int[] packedMaxZ = new int[batchCapacity];
        final int[] packedOffsets = new int[batchCapacity];
        final int[] packedSlots = new int[batchCapacity];
        final int[] highestByColumn = new int[batchCapacity];
        final Chunk[] chunksByColumn = new Chunk[batchCapacity];
        final boolean[] active = new boolean[batchCapacity];
        final long totalColumns = (long) area.width * area.height;

        for (long batchStart = 0; batchStart < totalColumns; batchStart += batchCapacity) {
            final int currentCount = (int) Math.min(batchCapacity, totalColumns - batchStart);
            java.util.Arrays.fill(packedSlots, 0, currentCount, -1);
            java.util.Arrays.fill(active, 0, currentCount, false);
            int nativeCount = 0;
            for (int local = 0; local < currentCount; local++) {
                final long index = batchStart + local;
                final int x = area.x + (int) (index / area.height);
                final int y = area.y + (int) (index % area.height);
                if (!frostEverywhere && !dimension.getBitLayerValueAt(Frost.INSTANCE, x, y)) {
                    continue;
                }
                active[local] = true;
                final Chunk chunk = world.getChunk(x >> 4, y >> 4);
                final Chunk editingChunk = world.getChunkForEditing(x >> 4, y >> 4);
                chunksByColumn[local] = editingChunk;
                final int highestNonAir = world.getHighestNonAirBlock(x, y);
                highestByColumn[local] = highestNonAir;
                final int segmentMaxZ = (highestNonAir < maxZ) ? highestNonAir + 1 : maxZ;
                final int segmentLength = segmentMaxZ - minHeight + 1;
                if ((chunk != null) && (editingChunk != null)
                        && snapshotNativeColumn(chunk, x, y, x & 0xf, y & 0xf,
                        highestNonAir, mode, snowUnderTrees, nativeCount,
                        nativeCount * columnLength, segmentLength, flags, snowLayers, highest, heightsFloat,
                        heightsInt, frostBitCounts, lowestVisitedZ)) {
                    packedSlots[local] = nativeCount;
                    packedMaxZ[nativeCount] = segmentMaxZ;
                    nativeCount++;
                }
            }

            int batchMinZ = maxZ;
            for (int slot = 0; slot < nativeCount; slot++) {
                batchMinZ = Math.min(batchMinZ, lowestVisitedZ[slot]);
            }
            // Omit the depths none of the descending Java scans would reach.
            // The zeroed prefixes cover columns whose scan stopped higher.
            int packedCellCount = 0;
            for (int slot = 0; slot < nativeCount; slot++) {
                final int length = packedMaxZ[slot] - batchMinZ + 1;
                final int source = slot * columnLength + batchMinZ - minHeight;
                packedOffsets[slot] = packedCellCount;
                System.arraycopy(flags, source, flags, packedCellCount, length);
                System.arraycopy(snowLayers, source, snowLayers, packedCellCount, length);
                packedCellCount += length;
            }

            final boolean nativeBatchSucceeded = nativeCount > 0
                    && NativeSlices.frostColumns(batchMinZ, maxZ, nativeCount,
                    packedMaxZ, frostEverywhere, snowUnderTrees, mode, flags, snowLayers,
                    highest, heightsFloat, heightsInt, frostBitCounts, updates);

            // Keep Java's original x/y mutation order, including fallback columns.
            for (int local = 0; local < currentCount; local++) {
                if (!active[local]) {
                    continue;
                }
                final long index = batchStart + local;
                final int x = area.x + (int) (index / area.height);
                final int y = area.y + (int) (index % area.height);
                final int slot = packedSlots[local];
                if (nativeBatchSucceeded && slot >= 0) {
                    applyNativeColumnResult(chunksByColumn[local], x & 0xf, y & 0xf,
                            highestByColumn[local],
                            heightsInt[slot], mode, random, packedOffsets[slot],
                            batchMinZ, packedMaxZ[slot],
                            flags, snowLayers, updates);
                } else {
                    applyJavaColumn(world, x, y, highestByColumn[local],
                            frostEverywhere, snowUnderTrees, mode, random);
                }
            }
        }
    }

    /** Snapshots one column into the next packed slot; mixed columns use Java. */
    private boolean snapshotNativeColumn(Chunk chunk, int x, int y, int chunkX, int chunkZ, int highestNonAir,
                                         int mode, boolean snowUnderTrees, int slot, int base, int segmentLength,
                                         byte[] flags, byte[] snowLayers, int[] highest,
                                         float[] heightsFloat, int[] heightsInt,
                                         int[] frostBitCounts, int[] lowestVisitedZ) {
        if (highestNonAir < minHeight || highestNonAir > maxZ) {
            return false;
        }
        int leafBlocksEncountered = 0;
        boolean hasFreezableWater = false;
        int lowest = minHeight;
        for (int offset = segmentLength - 1; offset >= 0; offset--) {
            final int z = minHeight + offset;
            final int cell = base + offset;
            if (z > highestNonAir) {
                flags[cell] = 1 << 6;
                snowLayers[cell] = 0;
                continue;
            }
            final Material material = chunk.getMaterial(chunkX, z, chunkZ);
            snowLayers[cell] = 0;
            int bits = 0;
            if (material.isNamed(MC_WATER) && (material.getProperty(LAYERS, 0) == 0)) bits |= 1;
            if (material.containsWater()) bits |= 1 << 1;
            if (material.insubstantial) bits |= 1 << 2;
            if (material.canSupportSnow) bits |= 1 << 3;
            if (material.leafBlock) bits |= 1 << 4;
            if (material.sustainsLeaves) bits |= 1 << 5;
            if (material.empty) bits |= 1 << 6;
            if (material == GRASS || material == FERN) bits |= 1 << 7;
            flags[cell] = (byte) bits;
            final boolean leafSupport = material.canSupportSnow
                    && (material.leafBlock || material.sustainsLeaves);
            if (leafSupport) {
                leafBlocksEncountered++;
            }
            hasFreezableWater |= (bits & 1) != 0 || ((bits & 6) == 6);
            if (leafBlocksEncountered > 0 && hasFreezableWater) {
                // A prior leaf placement can change what a later water pass
                // clears. Let the original Java scan preserve write order.
                return false;
            }
            if (material.isNamed(MC_SNOW)) {
                snowLayers[cell] = material.getProperty(LAYERS, 0).byteValue();
            }
            if (material == SNOW) {
                snowLayers[cell] |= (byte) 0x80;
            }
            // The Java scan cannot observe cells below this point. Clearing the
            // unused prefix also prevents stale data from a previous batch.
            if (hasFreezableWater || (material.canSupportSnow
                    && (!leafSupport || (!snowUnderTrees && leafBlocksEncountered > 1)))) {
                java.util.Arrays.fill(flags, base, cell, (byte) 0);
                java.util.Arrays.fill(snowLayers, base, cell, (byte) 0);
                lowest = z;
                break;
            }
        }
        final int heightInt = dimension.getIntHeightAt(x, y);
        final float heightFloat = ((mode == FrostSettings.MODE_SMOOTH)
                || (mode == FrostSettings.MODE_SMOOTH_AT_ALL_ELEVATIONS))
                ? dimension.getHeightAt(x, y) : 0.0f;
        final int frostBitCount = (mode == FrostSettings.MODE_SMOOTH
                || mode == FrostSettings.MODE_SMOOTH_AT_ALL_ELEVATIONS)
                ? dimension.getBitLayerCount(Frost.INSTANCE, x, y, 1) : 0;
        highest[slot] = highestNonAir;
        heightsFloat[slot] = heightFloat;
        heightsInt[slot] = heightInt;
        frostBitCounts[slot] = frostBitCount;
        lowestVisitedZ[slot] = lowest;
        return true;
    }

    private void applyNativeColumnResult(Chunk chunk, int chunkX, int chunkZ, int highestNonAir,
                                         int heightInt, int mode, Random random, int base,
                                         int segmentMinZ, int segmentMaxZ, byte[] flags, byte[] snowLayers,
                                         byte[] updates) {
        if (mode == FrostSettings.MODE_RANDOM) {
            final long snowZ = (long) heightInt + 1L;
            if (snowZ > segmentMinZ && snowZ <= segmentMaxZ) {
                final int snowOffset = (int) snowZ - segmentMinZ;
                final int snowCell = base + snowOffset;
                final int supportFlags = flags[base + snowOffset - 1] & 0xff;
                if ((updates[snowCell] & 0xff) >= 3
                        && (supportFlags & 0x08) != 0 && (supportFlags & 0x30) == 0) {
                    // Rust used layer 1 as a placeholder. Draw at exactly the
                    // point where Java's MODE_RANDOM would call nextInt(3).
                    final int drawn = random.nextInt(3) + 1;
                    final int existing = snowLayers[snowCell] & 0x7f;
                    updates[snowCell] = (byte) (Math.max(drawn, existing) + 2);
                }
            }
        }
        for (int z = segmentMinZ; z <= Math.min(highestNonAir, segmentMaxZ - 1); z++) {
            if (updates[base + z - segmentMinZ] == 2) {
                chunk.setMaterial(chunkX, z, chunkZ, ICE);
                for (int above = z + 1; above <= highestNonAir; above++) {
                    if (updates[base + above - segmentMinZ] == 1) {
                        chunk.setMaterial(chunkX, above, chunkZ, AIR);
                    } else {
                        break;
                    }
                }
                return;
            }
        }
        for (int z = segmentMaxZ; z >= segmentMinZ; z--) {
            final int update = updates[base + z - segmentMinZ] & 0xff;
            if (update >= 3 && update <= 10) {
                final int below = z - segmentMinZ - 1;
                final int belowFlags = (below >= 0) ? flags[base + below] & 0xff : 0;
                final boolean onLeaves = ((belowFlags & 0x08) != 0) && ((belowFlags & 0x30) != 0);
                chunk.setMaterial(chunkX, z, chunkZ,
                        onLeaves ? SNOW : SNOW.withProperty(LAYERS, update - 2));
            }
        }
    }

    private void applyJavaColumn(MinecraftWorld minecraftWorld, int x, int y, int highestNonAirBlock,
                                 boolean frostEverywhere, boolean snowUnderTrees, int mode,
                                 Random random) {
        Material previousMaterial = (highestNonAirBlock == maxZ)
                ? minecraftWorld.getMaterialAt(x, y, maxZ) : AIR;
        int leafBlocksEncountered = 0;
        for (int height = Math.min(highestNonAirBlock, maxZ - 1); height >= minHeight; height--) {
            Material material = minecraftWorld.getMaterialAt(x, y, height);
            if ((material.isNamed(MC_WATER) && (material.getProperty(LAYERS, 0) == 0))
                    || (material.containsWater() && material.insubstantial)) {
                minecraftWorld.setMaterialAt(x, y, height, ICE);
                for (int dz = height + 1; dz <= highestNonAirBlock; dz++) {
                    if (minecraftWorld.getMaterialAt(x, y, dz).insubstantial) {
                        minecraftWorld.setMaterialAt(x, y, dz, AIR);
                    } else {
                        break;
                    }
                }
                break;
            } else if (material.canSupportSnow) {
                if ((material.leafBlock) || (material.sustainsLeaves)) {
                    if (previousMaterial.empty) {
                        minecraftWorld.setMaterialAt(x, y, height + 1, SNOW);
                    }
                    leafBlocksEncountered++;
                    if ((!snowUnderTrees) && (leafBlocksEncountered > 1)) {
                        break;
                    }
                } else {
                    if (previousMaterial.empty || (previousMaterial == GRASS)
                            || (previousMaterial == FERN) || (previousMaterial == SNOW)) {
                        if ((mode == FrostSettings.MODE_SMOOTH_AT_ALL_ELEVATIONS)
                                || (height == dimension.getIntHeightAt(x, y))) {
                            switch (mode) {
                                case FrostSettings.MODE_FLAT:
                                    placeSnow(minecraftWorld, x, y, height, 1);
                                    break;
                                case FrostSettings.MODE_RANDOM:
                                    placeSnow(minecraftWorld, x, y, height, random.nextInt(3) + 1);
                                    break;
                                case FrostSettings.MODE_SMOOTH:
                                case FrostSettings.MODE_SMOOTH_AT_ALL_ELEVATIONS:
                                    int layers = (int) Math.floor((dimension.getHeightAt(x, y) + 0.5f
                                            - dimension.getIntHeightAt(x, y)) / 0.125f) + 1;
                                    if ((layers > 1) && (!frostEverywhere)) {
                                        layers = Math.max(Math.min(layers,
                                                dimension.getBitLayerCount(Frost.INSTANCE, x, y, 1) - 1), 1);
                                    }
                                    placeSnow(minecraftWorld, x, y, height, layers);
                                    break;
                            }
                        } else {
                            placeSnow(minecraftWorld, x, y, height, 1);
                        }
                    }
                    break;
                }
            } else {
                previousMaterial = material;
                continue;
            }
            previousMaterial = material;
        }
    }

    /**
     * Place a snow block with a specific thickness, but only if thicker snow is
     * not already present.
     */
    private void placeSnow(MinecraftWorld minecraftWorld, int x, int y, int height, int layers) {
        if ((layers < 1) || (layers > 8)) {
            throw new IllegalArgumentException("layers " + layers);
        }
        Material existingMaterial = minecraftWorld.getMaterialAt(x, y, height + 1);
        if (existingMaterial.isNamed(MC_SNOW)) {
            // If there is already snow there, don't lower it
            layers = Math.max(layers, existingMaterial.getProperty(LAYERS));
        }
        minecraftWorld.setMaterialAt(x, y, height + 1, SNOW.withProperty(LAYERS, layers));
    }

    public static class FrostSettings implements ExporterSettings {
        @Override
        public boolean isApplyEverywhere() {
            return frostEverywhere;
        }

        @Override
        public Frost getLayer() {
            return Frost.INSTANCE;
        }

        public boolean isFrostEverywhere() {
            return frostEverywhere;
        }

        public void setFrostEverywhere(boolean frostEverywhere) {
            this.frostEverywhere = frostEverywhere;
        }

        public int getMode() {
            return mode;
        }

        public void setMode(int mode) {
            this.mode = mode;
        }

        public boolean isSnowUnderTrees() {
            return snowUnderTrees;
        }

        public void setSnowUnderTrees(boolean snowUnderTrees) {
            this.snowUnderTrees = snowUnderTrees;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final FrostSettings other = (FrostSettings) obj;
            if (this.frostEverywhere != other.frostEverywhere) {
                return false;
            }
            if (this.mode != other.mode) {
                return false;
            }
            if (this.snowUnderTrees != other.snowUnderTrees) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int hash = 3;
            hash = 23 * hash + (this.frostEverywhere ? 1 : 0);
            hash = 23 * hash + mode;
            hash = 23 * hash + (this.snowUnderTrees ? 1 : 0);
            return hash;
        }

        @Override
        public FrostSettings clone() {
            try {
                return (FrostSettings) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new RuntimeException(e);
            }
        }

        private boolean frostEverywhere;
        private int mode = MODE_SMOOTH;
        private boolean snowUnderTrees = true;

        public static final int MODE_FLAT = 0; // Always place thin snow blocks
        public static final int MODE_RANDOM = 1; // Place random height snow blocks on the surface
        public static final int MODE_SMOOTH = 2; // Place smooth snow blocks on the surface
        public static final int MODE_SMOOTH_AT_ALL_ELEVATIONS = 3; // Place smooth snow blocks at any elevation

        private static final long serialVersionUID = 2011060801L;
    }
}
