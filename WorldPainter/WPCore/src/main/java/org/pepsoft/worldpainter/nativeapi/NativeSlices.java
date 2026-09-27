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

/** Bulk JNI entry points. A null result asks the caller to use its Java path. */
public final class NativeSlices {
    static final int ABI_VERSION = 1;

    private NativeSlices() {
        throw new AssertionError("Non instanciable");
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
        try {
            return nativeFillThemeTerrains(originX, originY, width, height,
                    minHeight, maxHeight, waterHeight, randomise ? 1 : 0,
                    beaches ? 1 : 0, beachOrdinal, seed, heights,
                    terrainRangeOrdinals, output) == 0 ? output : null;
        } catch (final UnsatisfiedLinkError e) {
            return null;
        }
    }

    /** Returns row-major capped Euclidean distances for a packed bit-layer mask. */
    public static float[] edgeDistances(final int width, final int height,
                                        final float maxDistance, final byte[] mask) {
        final long area = (long) width * height;
        if (!Native.isExportEnabled() || !NativeLoader.areSlicesAvailable()
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
        final long area = (long) width * height;
        if (!Native.isExportEnabled() || !NativeLoader.areSlicesAvailable()
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
        if (!Native.isExportEnabled() || !NativeLoader.areSlicesAvailable()
                || tinyX == null || tinyY == null || dirtX == null || dirtY == null
                || columnMinZ == null || columnMaxZ == null || resourceValues == null
                || seeds == null || materialMinZ == null || materialMaxZ == null
                || dirtMaterials == null || chances == null || minZ > maxZ
                || height > 4096L || tinyX.length == 0 || tinyX.length > 256
                || tinyY.length != tinyX.length || dirtX.length != tinyX.length
                || dirtY.length != tinyX.length || columnMinZ.length != tinyX.length
                || columnMaxZ.length != tinyX.length || resourceValues.length != tinyX.length
                || seeds.length > 64 || materialMinZ.length != seeds.length
                || materialMaxZ.length != seeds.length || dirtMaterials.length != seeds.length
                || chances.length != seeds.length * 16L || tinyX.length * height > 1_048_576L) {
            return null;
        }
        final byte[] output = new byte[(int) (tinyX.length * height)];
        try {
            return nativeFillResourceMaterials(minZ, maxZ, tinyX, tinyY, dirtX, dirtY,
                    columnMinZ, columnMaxZ, resourceValues, seeds, materialMinZ,
                    materialMaxZ, dirtMaterials, chances, output) == 0 ? output : null;
        } catch (final UnsatisfiedLinkError e) {
            return null;
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

    private static native int nativeShadeColours(int[] colours, long[] packedAmounts);

    private static native int nativeFillThemeTerrains(int originX, int originY,
                                                       int width, int height,
                                                       int minHeight, int maxHeight,
                                                       int waterHeight, int randomise,
                                                       int beaches, int beachOrdinal,
                                                       long seed, int[] heights,
                                                       int[] terrainRangeOrdinals,
                                                       int[] output);

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
                                                           byte[] output);


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
