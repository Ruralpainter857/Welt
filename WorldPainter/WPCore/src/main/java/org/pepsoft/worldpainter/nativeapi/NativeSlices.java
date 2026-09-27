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

    private static native int nativeFillThemeTerrains(int originX, int originY,
                                                       int width, int height,
                                                       int minHeight, int maxHeight,
                                                       int waterHeight, int randomise,
                                                       int beaches, int beachOrdinal,
                                                       long seed, int[] heights,
                                                       int[] terrainRangeOrdinals,
                                                       int[] output);


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
