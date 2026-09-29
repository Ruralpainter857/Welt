/*
 * WorldPainter - une application de peinture de cartes pour Minecraft.
 * Copyright (C) 2025 le projet Welt et contributeurs de WorldPainter.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.pepsoft.worldpainter.nativeapi;

import java.nio.ByteBuffer;

/** Bulk JNI entry points. A null result asks the caller to use its Java path. */
public final class NativeSlices {
    static final int ABI_VERSION = 1;
    public static final String TUNNEL_EDGE_RENDER_KEY = "wp.native.render.tunnelEdges";

    private NativeSlices() {
        throw new AssertionError("Non instanciable");
    }

    /**
     * Returns whether native tunnel edge caches are enabled. This specialised path is enabled by
     * default when its kernel is available; the dedicated property can disable it without changing
     * other render kernels.
     */
    public static boolean isTunnelEdgeRenderingEnabled() {
        return Boolean.parseBoolean(System.getProperty(TUNNEL_EDGE_RENDER_KEY,
                "true")) && NativeLoader.areSlicesAvailable();
    }

    /** Validates the shared chunk-buffer ABI in place, without copying its bytes. */
    public static boolean validateChunkPaletteBuffer(final ByteBuffer buffer) {
        if (!NativeLoader.areSlicesAvailable() || buffer == null || !buffer.isDirect()
                || buffer.position() != 0 || buffer.remaining() < 68) {
            return false;
        }
        try {
            return nativeValidateChunkPaletteBuffer(buffer.slice()) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Computes Java's slope operator in a bulk native pass, or leaves fallback to the caller. */
    public static boolean fillSlopeSamples(final double[] baseSamples,
                                           final int inputWidth, final int inputHeight,
                                           final float verticalScaling, final double[] output) {
        final long inputArea = (long) inputWidth * inputHeight;
        final long outputArea = (long) (inputWidth - 2) * (inputHeight - 2);
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || inputWidth < 3 || inputHeight < 3 || inputArea > 1_048_576L
                || outputArea <= 0 || outputArea > 1_048_576L
                || baseSamples == null || output == null
                || baseSamples.length != inputArea || output.length != outputArea) {
            return false;
        }
        try {
            return nativeFillSlopeSamples(inputWidth, inputHeight,
                    verticalScaling, baseSamples, output) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Applies two packed ColourUtils.multiply passes to an ARGB tile buffer in place. */
    public static boolean shadeColours(final int[] colours, final long[] packedAmounts) {
        if (!Native.isRenderEnabled() || !NativeLoader.areSlicesAvailable()
                || colours == null || packedAmounts == null || colours.length == 0
                || colours.length > 1_048_576 || packedAmounts.length != colours.length) {
            return false;
        }
        try {
            return nativeShadeColours(colours, packedAmounts) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Applies two packed 16-bit ColourUtils.multiply amounts to an ARGB tile in place. */
    public static boolean shadeColoursCompact(final int[] colours, final int[] packedAmounts) {
        if (!Native.isRenderEnabled() || !NativeLoader.areSlicesAvailable()
                || colours == null || packedAmounts == null || colours.length == 0
                || colours.length > 1_048_576 || packedAmounts.length != colours.length) {
            return false;
        }
        try {
            return nativeShadeColoursCompact(colours, packedAmounts) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    public static double[] noiseHeights(final int originX, final int originY,
                                        final int width, final int height,
                                        final double dHeight, final double scale,
                                        final int octaves, final long effectiveSeed) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || width <= 0 || height <= 0 || (long) width * height > 1_048_576L) {
            return null;
        }
        final double[] output = new double[width * height];
        try {
            return nativeFillNoiseHeights(originX, originY, width, height,
                    dHeight, scale, octaves, effectiveSeed, output) == 0 ? output : null;
        } catch (final UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** Fills caller-owned row-major output, allowing tile workers to reuse scratch storage. */
    public static boolean fillNoiseHeights(final int originX, final int originY,
                                          final int width, final int height,
                                          final double dHeight, final double scale,
                                          final int octaves, final long effectiveSeed,
                                          final double[] output) {
        final long area = (long) width * height;
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || width <= 0 || height <= 0 || area > 1_048_576L
                || output == null || output.length != area) {
            return false;
        }
        try {
            return nativeFillNoiseHeights(originX, originY, width, height,
                    dHeight, scale, octaves, effectiveSeed, output) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Fills a post-order expression of pure built-in heightmaps into caller-owned output. */
    public static boolean fillHeightMapTree(final int originX, final int originY,
                                            final int width, final int height,
                                            final int nodeCount,
                                            final int[] opcodes, final double[] values,
                                            final double[] scales, final int[] octaves,
                                            final long[] seeds, final double[] output) {
        final long area = (long) width * height;
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || width <= 0 || height <= 0 || area > 1_048_576L
                || nodeCount <= 0 || nodeCount > 64
                || opcodes == null || opcodes.length < nodeCount
                || values == null || values.length < nodeCount
                || scales == null || scales.length < nodeCount
                || octaves == null || octaves.length < nodeCount
                || seeds == null || seeds.length < nodeCount
                || output == null || output.length != area) {
            return false;
        }
        try {
            return nativeFillHeightMapTree(originX, originY, width, height, nodeCount, opcodes,
                    values, scales, octaves, seeds, output) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Fills a height-map expression at explicit float coordinates. */
    public static boolean fillHeightMapTreePoints(final int nodeCount,
                                                  final int[] opcodes, final double[] values,
                                                  final double[] scales, final int[] octaves,
                                                  final long[] seeds, final float[] xCoordinates,
                                                  final float[] yCoordinates, final double[] output) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || nodeCount <= 0 || nodeCount > 64
                || opcodes == null || opcodes.length < nodeCount
                || values == null || values.length < nodeCount
                || scales == null || scales.length < nodeCount
                || octaves == null || octaves.length < nodeCount
                || seeds == null || seeds.length < nodeCount
                || xCoordinates == null || yCoordinates == null || output == null
                || output.length == 0 || output.length > 1_048_576
                || xCoordinates.length != output.length || yCoordinates.length != output.length) {
            return false;
        }
        try {
            return nativeFillHeightMapTreePoints(nodeCount, opcodes, values, scales,
                    octaves, seeds, xCoordinates, yCoordinates, output) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Returns native terrain ordinals for one already-quantised SimpleTheme tile. */
    public static int[] simpleThemeTerrains(final int originX, final int originY,
                                            final int width, final int height,
                                            final int minHeight, final int maxHeight,
                                            final int waterHeight, final boolean randomise,
                                            final boolean beaches, final int beachOrdinal,
                                            final long seed, final int[] heights,
                                            final int[] terrainRangeOrdinals) {
        final long area = (long) width * height;
        final long rangeLength = (long) maxHeight - minHeight;
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || heights == null || terrainRangeOrdinals == null
                || width <= 0 || height <= 0 || area > 1_048_576L
                || rangeLength <= 0 || rangeLength > 1_048_576L
                || heights.length != area || terrainRangeOrdinals.length != rangeLength) {
            return null;
        }
        final int[] output = new int[(int) area];
        return fillSimpleThemeTerrainOrdinals(originX, originY, width, height,
                minHeight, maxHeight, waterHeight, randomise, beaches, beachOrdinal,
                seed, heights, terrainRangeOrdinals, output) ? output : null;
    }

    /** Fills a caller-owned ordinal buffer for one already-quantised tile. */
    public static boolean fillSimpleThemeTerrainOrdinals(final int originX, final int originY,
                                                         final int width, final int height,
                                                         final int minHeight, final int maxHeight,
                                                         final int waterHeight, final boolean randomise,
                                                         final boolean beaches, final int beachOrdinal,
                                                         final long seed, final int[] heights,
                                                         final int[] terrainRangeOrdinals,
                                                         final int[] output) {
        final long area = (long) width * height;
        final long rangeLength = (long) maxHeight - minHeight;
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || heights == null || terrainRangeOrdinals == null || output == null
                || width <= 0 || height <= 0 || area > 1_048_576L
                || rangeLength <= 0 || rangeLength > 1_048_576L
                || heights.length != area || terrainRangeOrdinals.length != rangeLength
                || output.length != area) {
            return false;
        }
        try {
            return nativeFillThemeTerrains(originX, originY, width, height,
                    minHeight, maxHeight, waterHeight, randomise ? 1 : 0,
                    beaches ? 1 : 0, beachOrdinal, seed, heights,
                    terrainRangeOrdinals, output) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Fills layer-major deterministic SimpleTheme values for one quantised tile. */
    public static boolean fillSimpleThemeLayerValues(final int width, final int height,
                                                     final int minHeight, final int maxHeight,
                                                     final int firstHeight, final int lastHeight,
                                                     final int[] quantisedHeights,
                                                     final int[][] layerTables,
                                                     final int[][] bitLayerTables,
                                                     final byte[] output) {
        final long area = (long) width * height;
        final long heightRange = (long) maxHeight - minHeight;
        if ((layerTables == null) || (bitLayerTables == null)) {
            return false;
        }
        final long totalLayers = (long) layerTables.length + bitLayerTables.length;
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || width <= 0 || height <= 0 || area > 1_048_576L
                || heightRange <= 0 || heightRange > 1_048_576L
                || quantisedHeights == null || layerTables == null || bitLayerTables == null
                || output == null || quantisedHeights.length != area
                || totalLayers == 0 || totalLayers > 64
                || area * totalLayers > 1_048_576L
                || output.length < area * totalLayers) {
            return false;
        }
        for (final int[] levels : layerTables) {
            if ((levels == null) || (levels.length != heightRange)) {
                return false;
            }
        }
        for (final int[] levels : bitLayerTables) {
            if ((levels == null) || (levels.length != heightRange)) {
                return false;
            }
        }
        try {
            return nativeFillSimpleThemeLayerValues(width, height, minHeight, maxHeight,
                    firstHeight, lastHeight, quantisedHeights, layerTables, bitLayerTables, output) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Returns row-major capped Euclidean distances for a packed bit-layer mask. */
    public static float[] edgeDistances(final int width, final int height,
                                        final float maxDistance, final byte[] mask) {
        return edgeDistances(width, height, maxDistance, mask, Native.isExportEnabled());
    }

    /** Returns capped edge distances for interactive tunnel rendering when render acceleration is enabled. */
    public static float[] edgeDistancesForRendering(final int width, final int height,
                                                    final float maxDistance, final byte[] mask) {
        return edgeDistances(width, height, maxDistance, mask, isTunnelEdgeRenderingEnabled());
    }

    private static float[] edgeDistances(final int width, final int height,
                                         final float maxDistance, final byte[] mask,
                                         final boolean enabled) {
        final long area = (long) width * height;
        if (!enabled || !NativeLoader.areSlicesAvailable()
                || mask == null || width <= 0 || height <= 0
                || area > 1_048_576L || mask.length != area
                || !Float.isFinite(maxDistance) || maxDistance < 0.0f || maxDistance > 512.0f) {
            return null;
        }
        final float[] output = new float[(int) area];
        try {
            return nativeBakeEdgeDistances(width, height, maxDistance, mask, output) == 0
                    ? output : null;
        } catch (final UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** Returns the max floor height propagated from edge pixels over the integer rasterized disk. */
    public static float[] edgeHeights(final int width, final int height, final int radius,
                                      final float minHeight, final byte[] sources,
                                      final float[] sourceHeights) {
        return edgeHeights(width, height, radius, minHeight, sources, sourceHeights,
                Native.isExportEnabled());
    }

    private static float[] edgeHeights(final int width, final int height, final int radius,
                                       final float minHeight, final byte[] sources,
                                       final float[] sourceHeights, final boolean enabled) {
        final long area = (long) width * height;
        if (!enabled || !NativeLoader.areSlicesAvailable()
                || sources == null || sourceHeights == null || width <= 0 || height <= 0
                || area > 1_048_576L || sources.length != area || sourceHeights.length != area
                || radius < 0 || radius > 512 || !Float.isFinite(minHeight)) {
            return null;
        }
        final float[] output = new float[(int) area];
        try {
            return nativeBakeEdgeHeights(width, height, radius, minHeight,
                    sources, sourceHeights, output) == 0 ? output : null;
        } catch (final UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** Packs an already palette-mapped cube using Minecraft's long-array layout. */
    public static long[] packArrayCube(final int[] paletteIndices, final int bitsPerIndex,
                                       final boolean straddleLongs) {
        if (!Native.isExportEnabled() || !NativeLoader.areSlicesAvailable()
                || paletteIndices == null || paletteIndices.length > 1_048_576
                || bitsPerIndex < 1 || bitsPerIndex > 32) {
            return null;
        }
        final long outputLength;
        if ((bitsPerIndex == 4) && ((paletteIndices.length % 16) == 0)) {
            outputLength = paletteIndices.length / 16L;
        } else if (straddleLongs) {
            outputLength = 64L * bitsPerIndex;
        } else {
            final int valuesPerLong = 64 / bitsPerIndex;
            outputLength = ((long) paletteIndices.length + valuesPerLong - 1L) / valuesPerLong;
        }
        if (outputLength > Integer.MAX_VALUE) {
            return null;
        }
        final long[] output = new long[(int) outputLength];
        try {
            return nativePackArrayCube(paletteIndices, bitsPerIndex,
                    straddleLongs ? 1 : 0, output) == 0 ? output : null;
        } catch (final UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** Decodes Minecraft's packed palette indexes or returns null for Java fallback. */
    public static int[] unpackArrayCube(final long[] data, final int arraySize,
                                        final int bitsPerIndex, final int paletteSize) {
        if (!Native.isExportEnabled() || !NativeLoader.areSlicesAvailable()
                || data == null || data.length > 1_048_576
                || arraySize < 0 || arraySize > 1_048_576
                || bitsPerIndex < 1 || bitsPerIndex > 32 || paletteSize <= 0) {
            return null;
        }
        final int[] output = new int[arraySize];
        try {
            return nativeUnpackArrayCube(data, arraySize, bitsPerIndex,
                    paletteSize, output) == 0 ? output : null;
        } catch (final UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** Returns first matching resource material indices per block, or null for Java fallback. */
    public static byte[] resourceMaterials(final int minZ, final int maxZ,
                                          final double[] tinyX, final double[] tinyY,
                                          final double[] dirtX, final double[] dirtY,
                                          final int[] columnMinZ, final int[] columnMaxZ,
                                          final int[] resourceValues, final long[] seeds,
                                          final int[] materialMinZ, final int[] materialMaxZ,
                                          final byte[] dirtMaterials, final float[] chances) {
        final long height = (long) maxZ - minZ + 1L;
        if (height <= 0L || height > 4096L || tinyX == null || tinyX.length == 0
                || tinyX.length > 256 || tinyX.length * height > 1_048_576L) {
            return null;
        }
        final byte[] output = new byte[(int) (tinyX.length * height)];
        return resourceMaterialsInto(minZ, maxZ, tinyX, tinyY, dirtX, dirtY,
                columnMinZ, columnMaxZ, resourceValues, seeds, materialMinZ,
                materialMaxZ, dirtMaterials, chances, output) ? output : null;
    }

    /** Fills a caller-owned result buffer to avoid one allocation per chunk. */
    public static boolean resourceMaterialsInto(final int minZ, final int maxZ,
                                          final double[] tinyX, final double[] tinyY,
                                          final double[] dirtX, final double[] dirtY,
                                          final int[] columnMinZ, final int[] columnMaxZ,
                                          final int[] resourceValues, final long[] seeds,
                                          final int[] materialMinZ, final int[] materialMaxZ,
                                          final byte[] dirtMaterials, final float[] chances,
                                          final byte[] output) {
        return resourceMaterialsInto(minZ, maxZ, tinyX, tinyY, dirtX, dirtY,
                columnMinZ, columnMaxZ, resourceValues, seeds, materialMinZ,
                materialMaxZ, dirtMaterials, chances, output, null);
    }

    /** Variant that optionally records JNI input-copy and Rust-kernel nanoseconds. */
    public static boolean resourceMaterialsInto(final int minZ, final int maxZ,
                                          final double[] tinyX, final double[] tinyY,
                                          final double[] dirtX, final double[] dirtY,
                                          final int[] columnMinZ, final int[] columnMaxZ,
                                          final int[] resourceValues, final long[] seeds,
                                          final int[] materialMinZ, final int[] materialMaxZ,
                                          final byte[] dirtMaterials, final float[] chances,
                                          final byte[] output, final long[] profileNanos) {
        final long height = (long) maxZ - minZ + 1L;
        if (!Native.isExportEnabled() || !NativeLoader.areSlicesAvailable()
                || tinyX == null || tinyY == null || dirtX == null || dirtY == null
                || columnMinZ == null || columnMaxZ == null || resourceValues == null
                || seeds == null || materialMinZ == null || materialMaxZ == null
                || dirtMaterials == null || chances == null || output == null || minZ > maxZ
                || height > 4096L || tinyX.length == 0 || tinyX.length > 256
                || tinyY.length != tinyX.length || dirtX.length != tinyX.length
                || dirtY.length != tinyX.length || columnMinZ.length != tinyX.length
                || columnMaxZ.length != tinyX.length || resourceValues.length != tinyX.length
                || seeds.length > 64 || materialMinZ.length != seeds.length
                || materialMaxZ.length != seeds.length || dirtMaterials.length != seeds.length
                || chances.length != seeds.length * 16L || tinyX.length * height > 1_048_576L
                || output.length != tinyX.length * height
                || (profileNanos != null && profileNanos.length != 5)) {
            return false;
        }
        try {
            return nativeFillResourceMaterials(minZ, maxZ, tinyX, tinyY, dirtX, dirtY,
                    columnMinZ, columnMaxZ, resourceValues, seeds, materialMinZ,
                    materialMaxZ, dirtMaterials, chances, output, profileNanos) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Computes Resources and applies the selected materials to live chunk palette indexes. */
    public static boolean resourceMaterialsIntoPalette(final int minZ, final int maxZ,
                                          final double[] tinyX, final double[] tinyY,
                                          final double[] dirtX, final double[] dirtY,
                                          final int[] columnMinZ, final int[] columnMaxZ,
                                          final int[] resourceValues, final long[] seeds,
                                          final int[] materialMinZ, final int[] materialMaxZ,
                                          final byte[] dirtMaterials, final float[] chances,
                                          final byte[] output, final int sectionMinY,
                                          final int sectionCount, final int[][] sectionIndexes,
                                          final byte[][] paletteFlags,
                                          final int[][] outputPaletteIndexes,
                                          final long[] profileNanos, final long[] applyNanos) {
        final long height = (long) maxZ - minZ + 1L;
        if (!Native.isResourcesExportEnabled() || !NativeLoader.areSlicesAvailable()
                || tinyX == null || tinyY == null || dirtX == null || dirtY == null
                || columnMinZ == null || columnMaxZ == null || resourceValues == null
                || seeds == null || materialMinZ == null || materialMaxZ == null
                || dirtMaterials == null || chances == null || output == null
                || sectionIndexes == null || paletteFlags == null || outputPaletteIndexes == null
                || minZ > maxZ || height > 4096L || tinyX.length == 0 || tinyX.length > 256
                || tinyY.length != tinyX.length || dirtX.length != tinyX.length
                || dirtY.length != tinyX.length || columnMinZ.length != tinyX.length
                || columnMaxZ.length != tinyX.length || resourceValues.length != tinyX.length
                || seeds.length > 64 || materialMinZ.length != seeds.length
                || materialMaxZ.length != seeds.length || dirtMaterials.length != seeds.length
                || chances.length != seeds.length * 16L || tinyX.length * height > 1_048_576L
                || output.length != tinyX.length * height || (sectionMinY & 15) != 0
                || sectionCount <= 0 || sectionCount > sectionIndexes.length
                || sectionCount > paletteFlags.length || sectionCount > outputPaletteIndexes.length
                || minZ < sectionMinY || maxZ >= sectionMinY + (long) sectionCount * 16L
                || sectionCount != (((maxZ - sectionMinY) >> 4) + 1)
                || (profileNanos != null && profileNanos.length != 5)
                || (applyNanos != null && applyNanos.length != 1)) {
            return false;
        }
        for (int section = 0; section < sectionCount; section++) {
            if (sectionIndexes[section] == null || sectionIndexes[section].length != 4096
                    || paletteFlags[section] == null || paletteFlags[section].length == 0
                    || outputPaletteIndexes[section] == null
                    || outputPaletteIndexes[section].length != seeds.length * 2) {
                return false;
            }
            for (int paletteIndex : outputPaletteIndexes[section]) {
                if (paletteIndex < 0 || paletteIndex >= paletteFlags[section].length) {
                    return false;
                }
            }
        }
        try {
            return nativeFillResourceMaterialsIntoPalette(minZ, maxZ,
                    tinyX, tinyY, dirtX, dirtY, columnMinZ, columnMaxZ, resourceValues,
                    seeds, materialMinZ, materialMaxZ, dirtMaterials, chances, output,
                    profileNanos, sectionMinY, sectionCount, sectionIndexes, paletteFlags,
                    outputPaletteIndexes, applyNanos) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Finds ordered fluid updates using one chunk's palette and precomputed neighbouring edges. */
    public static boolean findFluidUpdatesForChunk(final int minY, final int maxY,
                                                    final int worldMinY, final int worldMaxY,
                                                    final int sectionMinY, final int sectionCount,
                                                    final boolean flowWater, final boolean flowLava,
                                                    final int[][] sectionIndexes,
                                                    final byte[][] paletteFlags,
                                                    final byte[] westEdge, final byte[] eastEdge,
                                                    final byte[] northEdge, final byte[] southEdge,
                                                    final long[] updates) {
        final long height = (long) maxY - minY + 1L;
        if (!Native.isFluidFlowExportEnabled() || !NativeLoader.areSlicesAvailable()
                || minY > maxY || minY < worldMinY || maxY > worldMaxY
                || height > 4096L || (sectionMinY & 15) != 0
                || sectionCount <= 0 || sectionCount > 256
                || sectionIndexes == null || paletteFlags == null
                || sectionCount > sectionIndexes.length || sectionCount > paletteFlags.length
                || westEdge == null || eastEdge == null || northEdge == null || southEdge == null
                || updates == null || !flowWater && !flowLava
                || minY < sectionMinY
                || maxY >= sectionMinY + (long) sectionCount * 16L
                || sectionCount != (((maxY - sectionMinY) >> 4) + 1)
                || height * 16L > Integer.MAX_VALUE || height * 256L > Integer.MAX_VALUE
                || westEdge.length < height * 16L || eastEdge.length < height * 16L
                || northEdge.length < height * 16L || southEdge.length < height * 16L
                || updates.length < (height * 256L + Long.SIZE - 1L) / Long.SIZE) {
            return false;
        }
        for (int section = 0; section < sectionCount; section++) {
            if (sectionIndexes[section] == null || sectionIndexes[section].length != 4096
                    || paletteFlags[section] == null || paletteFlags[section].length == 0
                    || paletteFlags[section].length > 65_536) {
                return false;
            }
        }
        try {
            return nativeFindFluidUpdatesForChunk(minY, maxY, worldMinY, worldMaxY,
                    sectionMinY, sectionCount, flowWater ? 1 : 0, flowLava ? 1 : 0,
                    sectionIndexes, paletteFlags, westEdge, eastEdge, northEdge, southEdge,
                    updates) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }


    /** False leaves the FrostExporter on its original Java path. */
    public static boolean frostColumn(final int minZ, final int maxZ, final int highestNonAir,
                                      final boolean frostEverywhere, final boolean frostLayerPresent,
                                      final boolean snowUnderTrees, final int mode,
                                      final float heightFloat, final int heightInt,
                                      final int frostBitCount, final byte[] flags,
                                      final byte[] snowLayers, final byte[] updates) {
        final long columnLength = (long) maxZ - minZ + 1L;
        if (!Native.isExportEnabled() || !NativeLoader.areSlicesAvailable()
                || flags == null || snowLayers == null || updates == null
                || columnLength <= 0 || columnLength > 4096 || flags.length != columnLength
                || snowLayers.length != columnLength || updates.length != columnLength) {
            return false;
        }
        try {
            return nativeFrostColumn(minZ, maxZ, highestNonAir,
                    frostEverywhere ? 1 : 0, frostLayerPresent ? 1 : 0,
                    snowUnderTrees ? 1 : 0, mode, heightFloat, heightInt,
                    frostBitCount, flags, snowLayers, updates) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Applies one export mode to a packed set of eligible columns in one JNI call. */
    public static boolean frostColumns(final int minZ, final int maxZ, final int columnCount,
                                       final int[] columnMaxZs,
                                       final boolean frostEverywhere, final boolean snowUnderTrees,
                                       final int mode, final byte[] flags, final byte[] snowLayers,
                                       final int[] highestNonAir, final float[] heightFloats,
                                       final int[] heightInts, final int[] frostBitCounts,
                                       final byte[] updates) {
        final long columnLength = (long) maxZ - minZ + 1L;
        final long cellCount = columnLength * columnCount;
        if (!Native.isExportEnabled() || !NativeLoader.areSlicesAvailable()
                || columnCount <= 0 || columnLength <= 0 || columnLength > 4096
                || cellCount > 1_048_576L || flags == null || snowLayers == null
                || columnMaxZs == null
                || highestNonAir == null || heightFloats == null || heightInts == null
                || frostBitCounts == null || updates == null
                || flags.length < cellCount || snowLayers.length < cellCount
                || updates.length < cellCount || columnMaxZs.length < columnCount
                || highestNonAir.length < columnCount
                || heightFloats.length < columnCount || heightInts.length < columnCount
                || frostBitCounts.length < columnCount) {
            return false;
        }
        try {
            return nativeFrostColumns(minZ, maxZ, columnCount,
                    columnMaxZs, frostEverywhere ? 1 : 0, snowUnderTrees ? 1 : 0, mode,
                    flags, snowLayers, highestNonAir, heightFloats,
                    heightInts, frostBitCounts, updates) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    private static native int nativeFillNoiseHeights(int originX, int originY,
                                                      int width, int height,
                                                      double dHeight, double scale,
                                                      int octaves, long effectiveSeed,
                                                      double[] output);

    private static native int nativeFillHeightMapTree(int originX, int originY,
                                                       int width, int height,
                                                       int nodeCount,
                                                       int[] opcodes, double[] values,
                                                       double[] scales, int[] octaves,
                                                       long[] seeds, double[] output);

    private static native int nativeFillHeightMapTreePoints(int nodeCount,
                                                            int[] opcodes, double[] values,
                                                            double[] scales, int[] octaves,
                                                            long[] seeds, float[] xCoordinates,
                                                            float[] yCoordinates, double[] output);

    private static native int nativeFillSlopeSamples(int inputWidth, int inputHeight,
                                                      float verticalScaling,
                                                      double[] baseSamples, double[] output);

    private static native int nativeShadeColours(int[] colours, long[] packedAmounts);
    private static native int nativeShadeColoursCompact(int[] colours, int[] packedAmounts);

    private static native int nativeFillThemeTerrains(int originX, int originY,
                                                       int width, int height,
                                                       int minHeight, int maxHeight,
                                                       int waterHeight, int randomise,
                                                       int beaches, int beachOrdinal,
                                                       long seed, int[] heights,
                                                       int[] terrainRangeOrdinals,
                                                       int[] output);

    private static native int nativeFillSimpleThemeLayerValues(int width, int height,
                                                                int minHeight, int maxHeight,
                                                                int firstHeight, int lastHeight,
                                                                int[] quantisedHeights,
                                                                int[][] layerTables,
                                                                int[][] bitLayerTables,
                                                                byte[] output);

    private static native int nativeBakeEdgeDistances(int width, int height,
                                                       float maxDistance, byte[] mask,
                                                       float[] output);

    private static native int nativeBakeEdgeHeights(int width, int height, int radius,
                                                     float minHeight, byte[] sources,
                                                     float[] sourceHeights, float[] output);

    private static native int nativePackArrayCube(int[] paletteIndices, int bitsPerIndex,
                                                   int straddleLongs, long[] output);

    private static native int nativeUnpackArrayCube(long[] data, int arraySize,
                                                     int bitsPerIndex, int paletteSize,
                                                     int[] output);

    private static native int nativeFillResourceMaterials(int minZ, int maxZ,
                                                           double[] tinyX, double[] tinyY,
                                                           double[] dirtX, double[] dirtY,
                                                           int[] columnMinZ, int[] columnMaxZ,
                                                           int[] resourceValues, long[] seeds,
                                                           int[] materialMinZ, int[] materialMaxZ,
                                                           byte[] dirtMaterials, float[] chances,
                                                           byte[] output, long[] profileNanos);

    private static native int nativeFillResourceMaterialsIntoPalette(int minZ, int maxZ,
                                                           double[] tinyX, double[] tinyY,
                                                           double[] dirtX, double[] dirtY,
                                                           int[] columnMinZ, int[] columnMaxZ,
                                                           int[] resourceValues, long[] seeds,
                                                           int[] materialMinZ, int[] materialMaxZ,
                                                           byte[] dirtMaterials, float[] chances,
                                                           byte[] output, long[] profileNanos,
                                                           int sectionMinY, int sectionCount,
                                                           int[][] sectionIndexes,
                                                           byte[][] paletteFlags, int[][] outputPaletteIndexes,
                                                           long[] applyNanos);

    private static native int nativeFindFluidUpdatesForChunk(int minY, int maxY,
                                                             int worldMinY, int worldMaxY,
                                                             int sectionMinY, int sectionCount,
                                                             int flowWater, int flowLava,
                                                             int[][] sectionIndexes,
                                                             byte[][] paletteFlags,
                                                             byte[] westEdge, byte[] eastEdge,
                                                             byte[] northEdge, byte[] southEdge,
                                                             long[] updates);

    private static native int nativeValidateChunkPaletteBuffer(ByteBuffer buffer);

    /** Returns the current process working set in bytes, or -1 if unavailable. */
    public static long currentProcessResidentBytes() {
        if (!NativeLoader.areSlicesAvailable()) {
            return -1L;
        }
        try {
            return nativeCurrentProcessResidentBytes();
        } catch (final UnsatisfiedLinkError e) {
            return -1L;
        }
    }

    private static native long nativeCurrentProcessResidentBytes();


    private static native int nativeFrostColumn(int minZ, int maxZ, int highestNonAir,
                                                int frostEverywhere, int frostLayerPresent,
                                                int snowUnderTrees, int mode,
                                                float heightFloat, int heightInt,
                                                int frostBitCount, byte[] flags,
                                                byte[] snowLayers, byte[] updates);

    private static native int nativeFrostColumns(int minZ, int maxZ, int columnCount,
                                                 int[] columnMaxZs,
                                                 int frostEverywhere, int snowUnderTrees,
                                                 int mode, byte[] flags, byte[] snowLayers,
                                                 int[] highestNonAir, float[] heightFloats,
                                                 int[] heightInts, int[] frostBitCounts,
                                                 byte[] updates);

    static native int nativeAbiVersion();
}
