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
    private static volatile boolean heightmapImageSymbolUnavailable;

    /** Convert one raw-height plane; an old native library retains the Java image path. */
    public static boolean convertHeightmapImage(ByteBuffer buffer) {
        if (heightmapImageSymbolUnavailable || !Native.isRenderEnabled() || !NativeLoader.areSlicesAvailable()
                || buffer == null || !buffer.isDirect() || buffer.isReadOnly()
                || buffer.position() != 0 || buffer.limit() != 65568) return false;
        try { return nativeConvertHeightmapImage(buffer, buffer.limit()) == 0; }
        catch (UnsatisfiedLinkError e) { heightmapImageSymbolUnavailable = true; return false; }
    }
    private static native int nativeConvertHeightmapImage(ByteBuffer buffer, int length);

    private static volatile boolean chunkNbtSymbolUnavailable;

    /** Index all NBT payloads once; malformed or oversized trees retain the Java reader. */
    public static boolean indexChunkNbt(byte[] bytes, int length, int[] index) {
        if (bytes == null || length < 1 || length > bytes.length || length > 4 * 1024 * 1024
                || index == null || index.length != 4 + 8192 * 8 || chunkNbtSymbolUnavailable
                || !NativeLoader.areSlicesAvailable()) return false;
        try { return nativeIndexChunkNbt(bytes, length, index) == 0
                && index[0] == 0x574e4254 && index[1] == 1 && index[2] > 0 && index[2] <= 8192
                && index[3] > 0 && index[3] <= length; }
        catch (UnsatisfiedLinkError e) { chunkNbtSymbolUnavailable = true; return false; }
    }
    private static native int nativeIndexChunkNbt(byte[] bytes, int length, int[] index);
    /** Converts a complete big-endian MCA header to little-endian words in place. */
    public static boolean decodeRegionHeader(ByteBuffer buffer) {
        if(buffer==null || !buffer.isDirect() || buffer.isReadOnly()
                || (buffer.limit()!=4096 && buffer.limit()!=8192) || !NativeLoader.areSlicesAvailable())return false;
        try{return nativeDecodeRegionHeader(buffer,buffer.limit())==0;}
        catch(UnsatisfiedLinkError e){return false;}
    }
    private static native int nativeDecodeRegionHeader(ByteBuffer buffer,int length);

    /** Query compact selection planes without writing either plane or retaining the direct address. */
    public static long selectionBounds(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable() || buffer == null
                || !buffer.isDirect() || buffer.limit() != 4192) return Long.MIN_VALUE;
        try { return nativeSelectionBounds(buffer); }
        catch (UnsatisfiedLinkError e) { return Long.MIN_VALUE; }
    }
    private static native long nativeSelectionBounds(ByteBuffer buffer);
    /** Process all configured combined-layer passes in a single direct tile transaction. */
    public static boolean applyCombinedLayer(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable() || buffer == null
                || !buffer.isDirect() || buffer.isReadOnly() || buffer.limit() < 32
                || buffer.limit() > 4 * 1024 * 1024) return false;
        try { return nativeCombinedLayer(buffer, buffer.limit()) == 0; }
        catch (UnsatisfiedLinkError e) { return false; }
    }
    private static native int nativeCombinedLayer(ByteBuffer buffer, int length);
    /** Apply a complete brush line to one compact tile without retaining its direct address. */
    public static boolean paintLineStrokeTile(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable() || buffer == null
                || !buffer.isDirect() || buffer.isReadOnly() || buffer.limit() < 256
                || buffer.limit() > 1024 * 1024) return false;
        try { return nativePaintLineStrokeTile(buffer, buffer.limit()) == 0; }
        catch (UnsatisfiedLinkError e) { return false; }
    }
    private static native int nativePaintLineStrokeTile(ByteBuffer buffer, int length);

    static final int ABI_VERSION = 1;
    public static final String TUNNEL_EDGE_RENDER_KEY = "wp.native.render.tunnelEdges";

    private NativeSlices() {
        throw new AssertionError("Non instanciable");
    }

    /**
     * Read-only tile storage; modes are range, minimum and maximum.
     * Callers must exclude concurrent writes for the duration of this call.
     * The JVM may pin or copy the array; this bridge creates no staging buffer.
     */
    public static long heightStatistics(short[] heights, int[] tallHeights, int maxRaw, int mode) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable() || mode < 0 || mode > 2
                || (heights == null) == (tallHeights == null)
                || (heights != null ? heights.length : tallHeights.length) != 16384) return Long.MIN_VALUE;
        try { return nativeHeightStatistics(heights, tallHeights, maxRaw, mode); }
        catch (UnsatisfiedLinkError e) { return Long.MIN_VALUE; }
    }
    private static native long nativeHeightStatistics(short[] heights, int[] tallHeights, int maxRaw, int mode);

    /** Rotates the complete v1 tile-plane buffer in place with no JNI array copies. */
    public static boolean rotateTilePlanes(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || buffer == null || !buffer.isDirect() || buffer.isReadOnly()
                || buffer.position() != 0 || buffer.limit() < 16 || buffer.limit() > 2 * 1024 * 1024) {
            return false;
        }
        try {
            return nativeRotateTilePlanes(buffer, buffer.limit()) == 0;
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    private static native int nativeRotateTilePlanes(ByteBuffer buffer, int length);

    /** Edits both selection planes in the fixed v1 direct buffer. */
    public static boolean editSelection(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || buffer == null || !buffer.isDirect() || buffer.isReadOnly()
                || buffer.position() != 0 || buffer.limit() != 4192 || buffer.capacity() != 4192) {
            return false;
        }
        try {
            return nativeEditSelection(buffer) == 0;
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    private static native int nativeEditSelection(ByteBuffer buffer);

    /** Resamples all height and layer planes using the versioned compact scaling ABI. */
    public static boolean resampleTile(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || buffer == null || !buffer.isDirect() || buffer.isReadOnly()
                || buffer.position() != 0 || buffer.limit() < 2104 || buffer.limit() > 8 * 1024 * 1024) return false;
        try { return nativeResampleTile(buffer, buffer.limit()) == 0; }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    private static native int nativeResampleTile(ByteBuffer buffer, int length);

    public static boolean resizeVerticalTile(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || buffer == null || !buffer.isDirect() || buffer.isReadOnly()
                || buffer.position() != 0 || buffer.limit() != 48 + 16384 * 8) return false;
        try { return nativeResizeVerticalTile(buffer) == 0; }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    private static native int nativeResizeVerticalTile(ByteBuffer buffer);

    public static boolean bakeAutoBiomes(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || buffer == null || !buffer.isDirect() || buffer.isReadOnly()
                || buffer.position() != 0 || buffer.limit() != 64 + 16384 * 8) return false;
        try { return nativeBakeAutoBiomes(buffer) == 0; }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    private static native int nativeBakeAutoBiomes(ByteBuffer buffer);

    public static boolean editLayerPlanes(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || buffer == null || !buffer.isDirect() || buffer.isReadOnly()
                || buffer.position() != 0 || buffer.limit() < 40 || buffer.limit() > 2 * 1024 * 1024) return false;
        try { return nativeEditLayerPlanes(buffer, buffer.limit()) == 0; }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    private static native int nativeEditLayerPlanes(ByteBuffer buffer, int length);

    /** WLPY v1 : accès direct exclusif pendant une transaction de modelage. */
    public static boolean shapePyramidRegion(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || buffer == null || !buffer.isDirect() || buffer.isReadOnly()
                || buffer.position() != 0 || buffer.limit() < 37 || buffer.limit() > 32 + 1023 * 1023 * 5) return false;
        try { return nativeShapePyramidRegion(buffer, buffer.limit()) == 0; }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    private static native int nativeShapePyramidRegion(ByteBuffer buffer, int length);

    /** Exploration et mutation de la peinture dans un même tampon de zone. */
    public static boolean floodPaintRegion(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable() || buffer == null || !buffer.isDirect()
                || buffer.isReadOnly() || buffer.position() != 0 || buffer.limit() < 66 || buffer.limit() > 64 + 65536 * 2) return false;
        try { return nativeFloodPaintRegion(buffer, buffer.limit()) == 0; } catch (UnsatisfiedLinkError e) { return false; }
    }
    private static native int nativeFloodPaintRegion(ByteBuffer buffer, int length);

    /** WMIM v1: source raster, mapping gates and one packed output plane share the transaction. */
    public static boolean importMaskTile(ByteBuffer buffer) {
        if(!Native.isGenEnabled()||!NativeLoader.areSlicesAvailable()||buffer==null||!buffer.isDirect()
                ||buffer.isReadOnly()||buffer.position()!=0||buffer.limit()<256||buffer.limit()>4*1024*1024)return false;
        try{return nativeImportMaskTile(buffer,buffer.limit())==0;}
        catch(UnsatisfiedLinkError e){return false;}
    }
    private static native int nativeImportMaskTile(ByteBuffer buffer,int length);

    /** WHTB v1: compact grouped tiles retain global stroke order across their shared theme. */
    public static boolean applyThemedHeightBrush(ByteBuffer buffer) {
        if(!Native.isGenEnabled()||!NativeLoader.areSlicesAvailable()||buffer==null||!buffer.isDirect()
                ||buffer.isReadOnly()||buffer.position()!=0||buffer.limit()<128||buffer.limit()>4*1024*1024)return false;
        try{return nativeApplyThemedHeightBrush(buffer,buffer.limit())==0;}
        catch(UnsatisfiedLinkError e){return false;}
    }
    private static native int nativeApplyThemedHeightBrush(ByteBuffer buffer,int length);

    /** WGLY v1: one packed glyph crop and all its paint planes remain in one buffer. */
    public static boolean paintGlyphTile(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable() || buffer == null || !buffer.isDirect()
                || buffer.isReadOnly() || buffer.position() != 0 || buffer.limit() < 256 || buffer.limit() > 64 * 1024) return false;
        try { return nativePaintGlyphTile(buffer, buffer.limit()) == 0; }
        catch (UnsatisfiedLinkError e) { return false; }
    }
    private static native int nativePaintGlyphTile(ByteBuffer buffer, int length);

    /** WHIM v1/v2/v3/v4/v5: factory generation, relief and themes share one exclusive packed tile. */
    public static boolean importHeightMapTile(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable() || buffer == null || !buffer.isDirect()
                || buffer.isReadOnly() || buffer.position() != 0 || buffer.limit() < 256 || buffer.limit() > 4 * 1024 * 1024) return false;
        try { return nativeImportHeightMapTile(buffer, buffer.limit()) == 0; }
        catch (UnsatisfiedLinkError e) { return false; }
    }
    private static native int nativeImportHeightMapTile(ByteBuffer buffer, int length);

    /** WLCP v1: source and destination planes stay in one exclusive buffer. */
    public static boolean copySelectionPlanes(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable() || buffer == null || !buffer.isDirect()
                || buffer.isReadOnly() || buffer.position() != 0 || buffer.limit() < 64 || buffer.limit() > 6 * 1024 * 1024) return false;
        try { return nativeCopySelectionPlanes(buffer, buffer.limit()) == 0; } catch (UnsatisfiedLinkError e) { return false; }
    }
    private static native int nativeCopySelectionPlanes(ByteBuffer buffer, int length);

    /** WLFH v1/v2 : relief, peinture et frontière d'une tuile dans un tampon exclusif. */
    public static boolean floodHeightRegion(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable() || buffer == null || !buffer.isDirect()
                || buffer.isReadOnly() || buffer.position() != 0 || (buffer.limit() != 84032 && buffer.limit() != 100416)) return false;
        try { return nativeFloodHeightRegion(buffer, buffer.limit()) == 0; } catch (UnsatisfiedLinkError e) { return false; }
    }
    private static native int nativeFloodHeightRegion(ByteBuffer buffer, int length);

    /** Une seule transition JNI pour l'exploration et les mutations fluides d'une zone. */
    public static boolean floodFluidRegion(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable() || buffer == null || !buffer.isDirect()
                || buffer.isReadOnly() || buffer.position() != 0 || buffer.limit() < 74 || buffer.limit() > 64 + 65536 * 10) return false;
        try { return nativeFloodFluidRegion(buffer, buffer.limit()) == 0; } catch (UnsatisfiedLinkError e) { return false; }
    }
    private static native int nativeFloodFluidRegion(ByteBuffer buffer, int length);

    public static boolean editRiverRegion(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable() || buffer == null || !buffer.isDirect()
                || buffer.isReadOnly() || buffer.position() != 0 || buffer.limit() < 85 || buffer.limit() > 64 + 511 * 511 * 21) return false;
        try { return nativeEditRiverRegion(buffer, buffer.limit()) == 0; } catch (UnsatisfiedLinkError e) { return false; }
    }
    private static native int nativeEditRiverRegion(ByteBuffer buffer, int length);

    public static boolean editHeightPlane(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || buffer == null || !buffer.isDirect() || buffer.isReadOnly()
                || buffer.position() != 0 || buffer.limit() < 40 || buffer.limit() > 80 + 131072) return false;
        try { return nativeEditHeightPlane(buffer, buffer.limit()) == 0; }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    private static native int nativeEditHeightPlane(ByteBuffer buffer, int length);

    public static boolean editFluidBrush(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || buffer == null || !buffer.isDirect() || buffer.isReadOnly()
                || buffer.position() != 0 || buffer.limit() < 64 || buffer.limit() > 100416) return false;
        try { return nativeEditFluidBrush(buffer, buffer.limit()) == 0; }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    private static native int nativeEditFluidBrush(ByteBuffer buffer, int length);

    public static boolean editNibbleBrush(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || buffer == null || !buffer.isDirect() || buffer.isReadOnly()
                || buffer.position() != 0 || buffer.limit() < 8240 || buffer.limit() > 122944) return false;
        try { return nativeEditNibbleBrush(buffer, buffer.limit()) == 0; }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    private static native int nativeEditNibbleBrush(ByteBuffer buffer, int length);

    public static boolean erodeCompactRegion(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable() || buffer == null
                || !buffer.isDirect() || buffer.isReadOnly() || buffer.limit() < 89 || buffer.limit() > 2362409) return false;
        try { return nativeErodeCompactRegion(buffer, buffer.limit()) == 0; }
        catch (UnsatisfiedLinkError e) { return false; }
    }
    private static native int nativeErodeCompactRegion(ByteBuffer buffer, int length);

    public static boolean smoothCompactRegion(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable() || buffer == null || !buffer.isDirect()
                || buffer.isReadOnly() || buffer.position() != 0 || buffer.limit() < 541 || buffer.limit() > 806836) return false;
        try { return nativeSmoothCompactRegion(buffer, buffer.limit()) == 0; }
        catch (UnsatisfiedLinkError e) { return false; }
    }
    private static native int nativeSmoothCompactRegion(ByteBuffer buffer, int length);

    public static boolean editMaskedPlane(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || buffer == null || !buffer.isDirect() || buffer.isReadOnly()
                || buffer.position() != 0 || buffer.limit() < 48 || buffer.limit() > 32816) return false;
        try { return nativeEditMaskedPlane(buffer, buffer.limit()) == 0; }
        catch (UnsatisfiedLinkError e) { return false; }
    }

    private static native int nativeEditMaskedPlane(ByteBuffer buffer, int length);

    public static boolean paintFilteredTerrain(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable() || buffer == null || !buffer.isDirect()
                || buffer.isReadOnly() || buffer.position() != 0 || buffer.limit() < 160 || buffer.limit() > 4194304) return false;
        try { return nativePaintFilteredTerrain(buffer, buffer.limit()) == 0; }
        catch (UnsatisfiedLinkError e) { return false; }
    }
    private static native int nativePaintFilteredTerrain(ByteBuffer buffer, int length);

    /** One bounded live bitmap patch and all transformed bicubic samples in a single call. */
    public static boolean fillBitmapPreview(ByteBuffer buffer) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable() || buffer == null || !buffer.isDirect()
                || buffer.isReadOnly() || buffer.position() != 0 || buffer.limit() < 128 || buffer.limit() > 4194304) return false;
        try { return nativeFillBitmapPreview(buffer, buffer.limit()) == 0; }
        catch (UnsatisfiedLinkError e) { return false; }
    }
    private static native int nativeFillBitmapPreview(ByteBuffer buffer, int length);

    /** Complete compact viewport tile; Java retains its original path when unsupported. */
    public static boolean renderViewportTile(ByteBuffer buffer) {
        if (!Native.isRenderEnabled() || !NativeLoader.areSlicesAvailable() || buffer == null || !buffer.isDirect()
                || buffer.isReadOnly() || buffer.position() != 0 || buffer.limit() < 128 || buffer.limit() > 4194304) return false;
        try { return nativeRenderViewportTile(buffer, buffer.limit()) == 0; }
        catch (UnsatisfiedLinkError e) { return false; }
    }
    private static native int nativeRenderViewportTile(ByteBuffer buffer, int length);

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

    /** Evaluates one base-map program and its slope without returning the intermediate halo. */
    public static boolean fillSlopeHeightMapTree(int originX, int originY, int width, int height,
                                                int shift, float scaling, int nodeCount, int[] opcodes,
                                                double[] values, double[] scales, int[] octaves, long[] seeds, double[] output) {
        long area = (long) width * height;
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable() || width <= 0 || height <= 0
                || area > 16384 || shift < 0 || shift > 31 || nodeCount <= 0 || nodeCount > 64
                || opcodes == null || opcodes.length < nodeCount || values == null || values.length < nodeCount
                || scales == null || scales.length < nodeCount || octaves == null || octaves.length < nodeCount
                || seeds == null || seeds.length < nodeCount || output == null || output.length != area) return false;
        try { return nativeFillSlopeHeightMapTree(originX, originY, width, height, shift, scaling, nodeCount,
                opcodes, values, scales, octaves, seeds, output) == 0; }
        catch (UnsatisfiedLinkError e) { return false; }
    }
    private static native int nativeFillSlopeHeightMapTree(int originX, int originY, int width, int height,
            int shift, float scaling, int nodeCount, int[] opcodes, double[] values, double[] scales,
            int[] octaves, long[] seeds, double[] output);

    /** Evaluates affine coordinates and the base map in one call without Java coordinate planes. */
    public static boolean fillAffineHeightMapTree(int originX, int originY, int width, int height,
            int shift, double[] matrix, int nodeCount, int[] opcodes, double[] values, double[] scales,
            int[] octaves, long[] seeds, double[] output) {
        long area = (long) width * height;
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable() || width <= 0 || height <= 0
                || area > 16384 || shift < 0 || shift > 31 || matrix == null || matrix.length != 6
                || nodeCount <= 0 || nodeCount > 64 || opcodes == null || opcodes.length < nodeCount
                || values == null || values.length < nodeCount || scales == null || scales.length < nodeCount
                || octaves == null || octaves.length < nodeCount || seeds == null || seeds.length < nodeCount
                || output == null || output.length != area) return false;
        try { return nativeFillAffineHeightMapTree(originX, originY, width, height, shift, matrix, nodeCount,
                opcodes, values, scales, octaves, seeds, output) == 0; }
        catch (UnsatisfiedLinkError e) { return false; }
    }
    private static native int nativeFillAffineHeightMapTree(int originX, int originY, int width, int height,
            int shift, double[] matrix, int nodeCount, int[] opcodes, double[] values, double[] scales,
            int[] octaves, long[] seeds, double[] output);

    /** Evaluates angle, distance and the displaced source without returning intermediate planes. */
    public static boolean fillDisplacementHeightMapTree(int originX, int originY, int width, int height,
            int shift, int angleCount, int distanceCount, int nodeCount, int[] opcodes, double[] values,
            double[] scales, int[] octaves, long[] seeds, double[] output) {
        long area = (long) width * height;
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable() || width <= 0 || height <= 0
                || area > 16384 || shift < 0 || shift > 31 || angleCount <= 0 || distanceCount <= 0
                || (long) angleCount + distanceCount >= nodeCount || nodeCount <= 0 || nodeCount > 64
                || opcodes == null || opcodes.length < nodeCount || values == null || values.length < nodeCount
                || scales == null || scales.length < nodeCount || octaves == null || octaves.length < nodeCount
                || seeds == null || seeds.length < nodeCount || output == null || output.length != area) return false;
        try { return nativeFillDisplacementHeightMapTree(originX, originY, width, height, shift, angleCount,
                distanceCount, nodeCount, opcodes, values, scales, octaves, seeds, output) == 0; }
        catch (UnsatisfiedLinkError e) { return false; }
    }
    private static native int nativeFillDisplacementHeightMapTree(int originX, int originY, int width, int height,
            int shift, int angleCount, int distanceCount, int nodeCount, int[] opcodes, double[] values,
            double[] scales, int[] octaves, long[] seeds, double[] output);

    /** Processes one erosion brush area in Rust and returns Java-ordered setter calls. */
    public static boolean erodeRawHeightRegion(final int radius, final int[] heights,
                                               final byte[] controls, final int[] writeLog,
                                               final int[] writeCount) {
        if (radius < 0 || (2L * radius + 1L) > 512L) {
            return false;
        }
        final long diameter = 2L * radius + 1L;
        final long windowWidth = diameter + 2L;
        final long operationArea = diameter * diameter;
        final long windowArea = windowWidth * windowWidth;
        final long controlLength = operationArea * 3L;
        final long writeLogLength = operationArea * 4L;
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || windowArea > 1_048_576L
                || controlLength > 1_048_576L || writeLogLength > 1_048_576L
                || heights == null || controls == null || writeLog == null
                || writeCount == null || heights.length != windowArea
                || controls.length != controlLength || writeLog.length != writeLogLength
                || writeCount.length != 1) {
            return false;
        }
        try {
            return nativeErodeRawHeightRegion(radius, heights, controls,
                    writeLog, writeCount) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Applies one raise/lower height-brush pass to reusable caller-owned arrays. */
    public static boolean applyHeightBrush(final boolean inverse, final float minHeight,
                                           final float maxHeight, final float adjustment,
                                           final float[] heights, final float[] strengths,
                                           final byte[] modified) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || heights == null || strengths == null || modified == null
                || heights.length == 0 || heights.length > 65_536
                || strengths.length != heights.length || modified.length != heights.length) {
            return false;
        }
        try {
            return nativeApplyHeightBrush(inverse ? 1 : 0, minHeight, maxHeight,
                    adjustment, heights, strengths, modified) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Applies flatten, raise-only, or lower-only blending to reusable brush buffers. */
    public static boolean applyFlattenBrush(final int mode, final float targetHeight,
                                            final float[] heights, final float[] strengths,
                                            final byte[] modified) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || mode < 0 || mode > 2 || heights == null || strengths == null || modified == null
                || heights.length == 0 || heights.length > 65_536
                || strengths.length != heights.length || modified.length != heights.length) {
            return false;
        }
        try {
            return nativeApplyFlattenBrush(mode, targetHeight,
                    heights, strengths, modified) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Calculates a Raise Mountain brush area in one native call. */
    public static boolean applyRaiseMountain(final int originX, final int originY,
                                             final int width, final int height,
                                             final int minZ, final int maxRange,
                                             final float peakHeight, final float peakFactor,
                                             final boolean inverse, final float noiseScale,
                                             final long noiseSeed, final float[] heights,
                                             final float[] strengths, final byte[] modified) {
        final long area = (long) width * height;
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || width <= 0 || height <= 0 || area <= 0 || area > 65_536L
                || heights == null || strengths == null || modified == null
                || heights.length != area || strengths.length != area
                || modified.length != area || !Float.isFinite(noiseScale) || noiseScale <= 0.0f) {
            return false;
        }
        try {
            return nativeApplyRaiseMountain(originX, originY, width, height,
                    minZ, maxRange, peakHeight, peakFactor, inverse ? 1 : 0,
                    noiseScale, noiseSeed, heights, strengths, modified) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Selects the water and lava actions for one Sponge brush stroke. */
    public static boolean applySpongeBrush(final boolean inverse, final int waterHeight,
                                           final float[] strengths, final byte[] actions) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || strengths == null || actions == null || strengths.length == 0
                || strengths.length > 65_536 || actions.length != strengths.length) {
            return false;
        }
        try {
            return nativeApplySpongeBrush(inverse ? 1 : 0, waterHeight,
                    strengths, actions) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Calculates the safe fluid level and terrain edits for one River Paint stroke. */
    public static boolean applyRiverPaint(final int radius, final int previousWaterLevel,
                                          final float depth, final boolean lava,
                                          final float[] heights, final int[] terrainHeights,
                                          final int[] waterLevels, final float[] strengths,
                                          final float[] slopeOffsets,
                                          final byte[] heightModified, final byte[] flooded,
                                          final byte[] beaches, final int[] waterLevelOutput) {
        final long diameter = 2L * radius + 1L;
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || radius < 0 || diameter > 255L) {
            return false;
        }
        final long area = diameter * diameter;
        if (heights == null || terrainHeights == null || waterLevels == null
                || strengths == null || slopeOffsets == null || heightModified == null
                || flooded == null || beaches == null || waterLevelOutput == null
                || area <= 0 || area > 65_536L
                || heights.length != area || terrainHeights.length != area
                || waterLevels.length != area || strengths.length != area
                || slopeOffsets.length != area || heightModified.length != area
                || flooded.length != area || beaches.length != area
                || waterLevelOutput.length != 1) {
            return false;
        }
        try {
            return nativeApplyRiverPaint(radius, previousWaterLevel, depth, lava ? 1 : 0,
                    heights, terrainHeights, waterLevels, strengths, slopeOffsets,
                    heightModified, flooded, beaches, waterLevelOutput) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Returns the ordered fill-call indices for a bounded row-major boundary mask. */
    public static boolean linearFloodFill(final int width, final int height,
                                          final int seedX, final int seedY,
                                          final byte[] boundary, final int[] fillIndices,
                                          final int[] fillCount, final int[] boundsHit) {
        final long area = (long) width * height;
        final long fillCapacity = area * 2L;
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || width <= 0 || height <= 0 || area <= 0 || area > 65_536L
                || seedX < 0 || seedY < 0 || seedX >= width || seedY >= height
                || boundary == null || fillIndices == null || fillCount == null || boundsHit == null
                || boundary.length != area || fillIndices.length < fillCapacity
                || fillCount.length != 1 || boundsHit.length != 1) {
            return false;
        }
        try {
            return nativeLinearFloodFill(width, height, seedX, seedY, boundary,
                    fillIndices, fillCount, boundsHit) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Calculates apply/remove values for one nibble-layer brush area. */
    public static boolean applyNibbleLayerBrush(final int mode, final int[] values,
                                                final float[] strengths,
                                                final byte[] modified) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || mode < 0 || mode > 2 || values == null || strengths == null || modified == null
                || values.length == 0 || values.length > 65_536
                || strengths.length != values.length || modified.length != values.length) {
            return false;
        }
        try {
            return nativeApplyNibbleLayerBrush(mode, values, strengths, modified) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Selects cells above the non-dithered layer-brush strength threshold. */
    public static boolean paintThresholdMask(final float[] strengths, final byte[] modified) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || strengths == null || modified == null || strengths.length == 0
                || strengths.length > 65_536 || modified.length != strengths.length) {
            return false;
        }
        try {
            return nativePaintThresholdMask(strengths, modified) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Rasterizes the slow line painter's pixel centers into caller-owned buffers. */
    public static int rasterizeLineCenters(final int x1, final int y1, final int x2, final int y2,
                                           final int[] coordinates, final int[] count) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || coordinates == null || coordinates.length < 2 || coordinates.length > 131_072
                || (coordinates.length & 1) != 0 || count == null || count.length != 1) {
            return -1;
        }
        try {
            return nativeRasterizeLineCenters(x1, y1, x2, y2, coordinates, count) == 0
                    ? count[0] : -1;
        } catch (final UnsatisfiedLinkError e) {
            return -1;
        }
    }

    /** Calculates the Pencil axis and snapped point into a three-int buffer. */
    public static boolean snapPencilCoordinates(final int x1, final int y1, final int x2,
                                                final int y2, final int axisHint,
                                                final int[] output) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || axisHint < -2 || axisHint > 3 || output == null || output.length != 3) {
            return false;
        }
        try {
            return nativeSnapPencilCoordinates(x1, y1, x2, y2, axisHint, output) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Smooths a brush area using caller-owned input, strength and output buffers. */
    public static boolean smoothHeightRegion(final int inputWidth, final int inputHeight,
                                             final float[] heights, final float[] strengths,
                                             final float[] output, final byte[] modified) {
        final long inputArea = (long) inputWidth * inputHeight;
        final long outputArea = (long) (inputWidth - 10) * (inputHeight - 10);
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || inputWidth < 11 || inputHeight < 11
                || inputArea > 65_536L || outputArea <= 0 || outputArea > 65_536L
                || heights == null || strengths == null || output == null || modified == null
                || heights.length != inputArea || strengths.length != outputArea
                || output.length != outputArea || modified.length != outputArea) {
            return false;
        }
        try {
            return nativeSmoothHeightRegion(inputWidth, inputHeight, heights,
                    strengths, output, modified) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Builds one square-pyramid height plane and ordered cell-change flags. */
    public static boolean raiseSquarePyramid(final int maxRing, final float centerHeight,
                                             final float maxHeight, final float[] heights,
                                             final byte[] modified) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable() || heights == null
                || modified == null || maxRing > 128) {
            return false;
        }
        final long radius = maxRing > 1 ? maxRing - 1L : 0L;
        final long side = 2L * radius + 1L;
        final long area = side * side;
        if (area > 65_536L || heights.length != area || modified.length != area) {
            return false;
        }
        try {
            return nativeRaiseSquarePyramid(maxRing, centerHeight, maxHeight,
                    heights, modified) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Builds one rotated-pyramid height plane and ordered cell-change flags. */
    public static boolean raiseRotatedPyramid(final int maxRing, final float centerHeight,
                                              final float maxHeight, final float[] heights,
                                              final byte[] modified) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable() || heights == null
                || modified == null || maxRing > 128) {
            return false;
        }
        final long radius = maxRing > 1 ? maxRing - 1L : 0L;
        final long side = 2L * radius + 1L;
        final long area = side * side;
        if (area > 65_536L || heights.length != area || modified.length != area) {
            return false;
        }
        try {
            return nativeRaiseRotatedPyramid(maxRing, centerHeight, maxHeight,
                    heights, modified) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Fills two height-map trees in one JNI call into separate caller-owned buffers. */
    public static boolean fillHeightMapTreePair(final int originX, final int originY,
                                                final int width, final int height,
                                                final int firstNodeCount,
                                                final int secondNodeCount,
                                                final int[] opcodes, final double[] values,
                                                final double[] scales, final int[] octaves,
                                                final long[] seeds,
                                                final double[] firstOutput,
                                                final double[] secondOutput) {
        final long area = (long) width * height;
        final long nodeCount = (long) firstNodeCount + secondNodeCount;
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || width <= 0 || height <= 0 || area > 1_048_576L
                || firstNodeCount <= 0 || firstNodeCount > 64
                || secondNodeCount <= 0 || secondNodeCount > 64
                || opcodes == null || values == null || scales == null
                || octaves == null || seeds == null
                || opcodes.length < nodeCount || values.length < nodeCount
                || scales.length < nodeCount || octaves.length < nodeCount
                || seeds.length < nodeCount
                || firstOutput == null || secondOutput == null
                || firstOutput == secondOutput
                || firstOutput.length != area || secondOutput.length != area) {
            return false;
        }
        try {
            return nativeFillHeightMapTreePair(originX, originY, width, height,
                    firstNodeCount, secondNodeCount, opcodes, values, scales,
                    octaves, seeds, firstOutput, secondOutput) == 0;
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

    /** Fills terrain and layer planes for one FancyTheme tile from its height neighborhood. */
    public static boolean fillFancyThemeTile(final int width, final int height,
                                             final int waterHeight, final int desertMaxHeight,
                                             final int terrainBase, final int terrainDesert,
                                             final int terrainSandstone, final int terrainBareGrass,
                                             final int terrainBeaches, final int terrainDirtAndGravel,
                                             final int terrainStoneAndGravel,
                                             final float[] tileHeights,
                                             final float[] heightNeighborhood,
                                             final double[] temperatures,
                                             final double[] humidities,
                                             final double[] forestValues,
                                             final byte[] output) {
        final long area = (long) width * height;
        final long neighborhoodArea = (long) (width + 10) * (height + 10);
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || width <= 0 || height <= 0 || width > 256 || height > 256
                || area > 65_536L || neighborhoodArea > 80_000L
                || tileHeights == null || tileHeights.length != area
                || heightNeighborhood == null || heightNeighborhood.length != neighborhoodArea
                || temperatures == null || temperatures.length != area
                || humidities == null || humidities.length != area
                || forestValues == null || forestValues.length != area
                || output == null || output.length != area * 7L
                || !isTerrainOrdinal(terrainBase) || !isTerrainOrdinal(terrainDesert)
                || !isTerrainOrdinal(terrainSandstone) || !isTerrainOrdinal(terrainBareGrass)
                || !isTerrainOrdinal(terrainBeaches) || !isTerrainOrdinal(terrainDirtAndGravel)
                || !isTerrainOrdinal(terrainStoneAndGravel)) {
            return false;
        }
        try {
            return nativeFillFancyThemeTile(width, height, waterHeight, desertMaxHeight,
                    terrainBase, terrainDesert, terrainSandstone, terrainBareGrass,
                    terrainBeaches, terrainDirtAndGravel, terrainStoneAndGravel,
                    tileHeights, heightNeighborhood, temperatures, humidities, forestValues,
                    output) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    private static boolean isTerrainOrdinal(final int ordinal) {
        return (ordinal >= 0) && (ordinal <= 255);
    }

    /** Fills compact terrain ordinals into a caller-owned byte plane for a fresh tile. */
    public static boolean fillSimpleThemeTerrainOrdinalsCompact(final int originX, final int originY,
                                                                  final int width, final int height,
                                                                  final int minHeight, final int maxHeight,
                                                                  final int waterHeight, final boolean randomise,
                                                                  final boolean beaches, final int beachOrdinal,
                                                                  final long seed, final int[] heights,
                                                                  final int[] terrainRangeOrdinals,
                                                                  final byte[] output) {
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
            return nativeFillThemeTerrainsCompact(originX, originY, width, height,
                    minHeight, maxHeight, waterHeight, randomise ? 1 : 0,
                    beaches ? 1 : 0, beachOrdinal, seed, heights,
                    terrainRangeOrdinals, output) == 0;
        } catch (final UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Converts Java-generated random draws into layer-major SimpleTheme bit planes in place. */
    public static boolean fillSimpleThemeRandomBitLayers(final int width, final int height,
                                                          final int minHeight, final int maxHeight,
                                                          final int[] quantisedHeights,
                                                          final int[][] bitLayerTables,
                                                          final byte[] rollsAndOutput) {
        final long area = (long) width * height;
        final long heightRange = (long) maxHeight - minHeight;
        final long outputLength = area * ((bitLayerTables != null) ? bitLayerTables.length : 0);
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()
                || width <= 0 || height <= 0 || area > 1_048_576L
                || heightRange <= 0 || heightRange > 1_048_576L
                || quantisedHeights == null || bitLayerTables == null
                || bitLayerTables.length == 0 || bitLayerTables.length > 64
                || outputLength > 1_048_576L || rollsAndOutput == null
                || quantisedHeights.length != area || rollsAndOutput.length < outputLength) {
            return false;
        }
        for (final int[] levels : bitLayerTables) {
            if ((levels == null) || (levels.length != heightRange)) {
                return false;
            }
        }
        try {
            return nativeFillSimpleThemeRandomBitLayers(width, height, minHeight, maxHeight,
                    quantisedHeights, bitLayerTables, rollsAndOutput) == 0;
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

    private static native int nativeFillHeightMapTreePair(int originX, int originY,
                                                           int width, int height,
                                                           int firstNodeCount,
                                                           int secondNodeCount,
                                                           int[] opcodes, double[] values,
                                                           double[] scales, int[] octaves,
                                                           long[] seeds,
                                                           double[] firstOutput,
                                                           double[] secondOutput);

    private static native int nativeFillHeightMapTreePoints(int nodeCount,
                                                            int[] opcodes, double[] values,
                                                            double[] scales, int[] octaves,
                                                            long[] seeds, float[] xCoordinates,
                                                            float[] yCoordinates, double[] output);

    private static native int nativeFillSlopeSamples(int inputWidth, int inputHeight,
                                                      float verticalScaling,
                                                      double[] baseSamples, double[] output);

    private static native int nativeErodeRawHeightRegion(int radius, int[] heights,
                                                          byte[] controls, int[] writeLog,
                                                          int[] writeCount);

    private static native int nativeApplyHeightBrush(int inverse, float minHeight,
                                                      float maxHeight, float adjustment,
                                                      float[] heights, float[] strengths,
                                                      byte[] modified);

    private static native int nativeApplyFlattenBrush(int mode, float targetHeight,
                                                       float[] heights, float[] strengths,
                                                       byte[] modified);

    private static native int nativeApplyRaiseMountain(int originX, int originY,
                                                        int width, int height,
                                                        int minZ, int maxRange,
                                                        float peakHeight, float peakFactor,
                                                        int inverse, float noiseScale,
                                                        long noiseSeed, float[] heights,
                                                        float[] strengths, byte[] modified);

    private static native int nativeApplySpongeBrush(int inverse, int waterHeight,
                                                      float[] strengths, byte[] actions);

    private static native int nativeApplyRiverPaint(int radius, int previousWaterLevel,
                                                     float depth, int lava, float[] heights,
                                                     int[] terrainHeights, int[] waterLevels,
                                                     float[] strengths, float[] slopeOffsets,
                                                     byte[] heightModified, byte[] flooded,
                                                     byte[] beaches, int[] waterLevelOutput);

    private static native int nativeLinearFloodFill(int width, int height, int seedX, int seedY,
                                                     byte[] boundary, int[] fillIndices,
                                                     int[] fillCount, int[] boundsHit);

    private static native int nativeApplyNibbleLayerBrush(int mode, int[] values,
                                                           float[] strengths, byte[] modified);

    private static native int nativePaintThresholdMask(float[] strengths, byte[] modified);

    private static native int nativeRasterizeLineCenters(int x1, int y1, int x2, int y2,
                                                          int[] coordinates, int[] count);

    private static native int nativeSnapPencilCoordinates(int x1, int y1, int x2, int y2,
                                                           int axisHint, int[] output);

    private static native int nativeSmoothHeightRegion(int inputWidth, int inputHeight,
                                                        float[] heights, float[] strengths,
                                                        float[] output, byte[] modified);

    private static native int nativeRaiseSquarePyramid(int maxRing, float centerHeight,
                                                        float maxHeight, float[] heights,
                                                        byte[] modified);

    private static native int nativeRaiseRotatedPyramid(int maxRing, float centerHeight,
                                                         float maxHeight, float[] heights,
                                                         byte[] modified);

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

    private static native int nativeFillThemeTerrainsCompact(int originX, int originY,
                                                              int width, int height,
                                                              int minHeight, int maxHeight,
                                                              int waterHeight, int randomise,
                                                              int beaches, int beachOrdinal,
                                                              long seed, int[] heights,
                                                              int[] terrainRangeOrdinals,
                                                              byte[] output);

    private static native int nativeFillFancyThemeTile(int width, int height,
                                                        int waterHeight, int desertMaxHeight,
                                                        int terrainBase, int terrainDesert,
                                                        int terrainSandstone, int terrainBareGrass,
                                                        int terrainBeaches, int terrainDirtAndGravel,
                                                        int terrainStoneAndGravel,
                                                        float[] tileHeights,
                                                        float[] heightNeighborhood,
                                                        double[] temperatures,
                                                        double[] humidities,
                                                        double[] forestValues,
                                                        byte[] output);

    private static native int nativeFillSimpleThemeLayerValues(int width, int height,
                                                                int minHeight, int maxHeight,
                                                                int firstHeight, int lastHeight,
                                                                int[] quantisedHeights,
                                                                int[][] layerTables,
                                                                 int[][] bitLayerTables,
                                                                 byte[] output);

    private static native int nativeFillSimpleThemeRandomBitLayers(int width, int height,
                                                                    int minHeight, int maxHeight,
                                                                    int[] quantisedHeights,
                                                                    int[][] bitLayerTables,
                                                                    byte[] rollsAndOutput);

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
