/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.pepsoft.worldpainter;

import org.pepsoft.worldpainter.heightMaps.NoiseHeightMap;
import org.pepsoft.worldpainter.heightMaps.FastNoiseLiteHeightMap;
import org.pepsoft.worldpainter.heightMaps.ConstantHeightMap;
import org.pepsoft.worldpainter.heightMaps.CombiningHeightMap;
import org.pepsoft.worldpainter.heightMaps.DifferenceHeightMap;
import org.pepsoft.worldpainter.heightMaps.DisplacementHeightMap;
import org.pepsoft.worldpainter.heightMaps.ProductHeightMap;
import org.pepsoft.worldpainter.heightMaps.MinimisingHeightMap;
import org.pepsoft.worldpainter.heightMaps.MaximisingHeightMap;
import org.pepsoft.worldpainter.heightMaps.MandelbrotHeightMap;
import org.pepsoft.worldpainter.heightMaps.BandedHeightMap;
import org.pepsoft.worldpainter.heightMaps.NinePatchHeightMap;
import org.pepsoft.worldpainter.heightMaps.SlopeHeightMap;
import org.pepsoft.worldpainter.heightMaps.TransformingHeightMap;
import org.pepsoft.worldpainter.heightMaps.ShelvingHeightMap;
import org.pepsoft.worldpainter.heightMaps.BitmapHeightMap;
import org.pepsoft.worldpainter.heightMaps.BicubicHeightMap;
import org.pepsoft.worldpainter.heightMaps.SumHeightMap;
import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import org.pepsoft.worldpainter.themes.SimpleTheme;
import org.pepsoft.worldpainter.themes.Theme;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.SortedMap;

import static org.pepsoft.util.MathUtils.clamp;
import static org.pepsoft.worldpainter.Constants.TILE_SIZE;
import static org.pepsoft.worldpainter.Constants.TILE_SIZE_BITS;

/**
 *
 * @author pepijn
 */
public class HeightMapTileFactory extends AbstractTileFactory {
    public HeightMapTileFactory(long seed, HeightMap heightMap, int minHeight, int maxHeight, boolean floodWithLava, Theme theme) {
        this.seed = seed;
        this.minHeight = minHeight;
        this.heightMap = heightMap;
        this.maxHeight = maxHeight;
        this.floodWithLava = floodWithLava;
        heightMap.setSeed(seed);
        theme.setSeed(seed);
        this.theme = theme;
    }

    @Override
    public int getMinHeight() {
        return minHeight;
    }

    @Override
    public final int getMaxHeight() {
        return maxHeight;
    }

    @Override
    public long getSeed() {
        return seed;
    }

    @Override
    public void setSeed(long seed) {
        this.seed = seed;
        heightMap.setSeed(seed);
        theme.setSeed(seed);
    }

    @Override
    public final void setMinMaxHeight(int minHeight, int maxHeight, HeightTransform transform) {
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        theme.setMinMaxHeight(minHeight, maxHeight, transform);
        if (! transform.isIdentity()) {
            heightMap = transform.transformHeightMap(heightMap);
        }
    }

    public final int getWaterHeight() {
        return theme.getWaterHeight();
    }

    public final void setWaterHeight(int waterHeight) {
        theme.setWaterHeight(waterHeight);
    }

    public final boolean isFloodWithLava() {
        return floodWithLava;
    }

    public final HeightMap getHeightMap() {
        return heightMap;
    }
    
    public final double getBaseHeight() {
        return heightMap.getBaseHeight();
    }

    public final void setHeightMap(HeightMap heightMap) {
        if (heightMap == null) {
            throw new NullPointerException();
        }
        this.heightMap = heightMap;
    }

    public Theme getTheme() {
        return theme;
    }

    public void setTheme(Theme theme) {
        this.theme = theme;
        theme.setMinMaxHeight(minHeight, maxHeight, HeightTransform.IDENTITY);
    }

    @Override
    public boolean isTilePresent(int x, int y) {
        Rectangle extent = getExtent();
        return (extent == null) || extent.contains(x, y);
    }

    @Override
    public Tile createTile(int tileX, int tileY) {
        final int maxZ = maxHeight - 1, myWaterHeight = getWaterHeight();
        final Tile tile = new Tile(tileX, tileY, minHeight, maxHeight);
        tile.inhibitEvents();
        final int worldTileX = tileX * TILE_SIZE, worldTileY = tileY * TILE_SIZE;
        try {
            final TransformingHeightMap translatedHeightMap = getBatchSafeTranslation(heightMap);
            final HeightMap batchHeightMap = (translatedHeightMap != null)
                    ? translatedHeightMap.getBaseHeightMap() : heightMap;
            final int heightMapOriginX = (translatedHeightMap != null)
                    ? worldTileX - translatedHeightMap.getOffsetX() : worldTileX;
            final int heightMapOriginY = (translatedHeightMap != null)
                    ? worldTileY - translatedHeightMap.getOffsetY() : worldTileY;
            NoiseHeightMap nativeNoiseMap = null;
            double nativeConstant = 0.0;
            boolean nativeConstantFirst = false;
            if (heightMap.getClass() == NoiseHeightMap.class) {
                nativeNoiseMap = (NoiseHeightMap) heightMap;
            } else if (heightMap.getClass() == SumHeightMap.class) {
                final SumHeightMap sum = (SumHeightMap) heightMap;
                if ((sum.getHeightMap1() instanceof ConstantHeightMap) && (sum.getHeightMap2() instanceof NoiseHeightMap)) {
                    nativeNoiseMap = (NoiseHeightMap) sum.getHeightMap2();
                    nativeConstant = ((ConstantHeightMap) sum.getHeightMap1()).getHeight();
                    nativeConstantFirst = true;
                } else if ((sum.getHeightMap1() instanceof NoiseHeightMap) && (sum.getHeightMap2() instanceof ConstantHeightMap)) {
                    nativeNoiseMap = (NoiseHeightMap) sum.getHeightMap1();
                    nativeConstant = ((ConstantHeightMap) sum.getHeightMap2()).getHeight();
                }
            }
            final boolean freshSimpleTheme = (theme.getClass() == SimpleTheme.class) && !floodWithLava;
            final boolean batchFreshSimpleTheme = freshSimpleTheme
                    && (isBatchSafeHeightMap(heightMap) || isNativeBandedHeightMap(heightMap)
                    || isNativeSlopeHeightMap(heightMap)
                    || isNativeDisplacementHeightMap(heightMap)
                    || isNativeTransformingHeightMap(heightMap)
                    || (translatedHeightMap != null)
                    || isBulkReadableBitmapHeightMap(heightMap)
                    || isNativeShelvingHeightMap(heightMap, worldTileX, worldTileY));
            final GenerationBuffers buffers = batchFreshSimpleTheme ? GENERATION_BUFFERS.get() : null;
            double[] nativeHeights = null;
            boolean completeHeightMapValuesAvailable = false;
            if (batchFreshSimpleTheme && Native.isGenEnabled()
                    && buffers.prepareHeightMapProgram(batchHeightMap)
                    && (buffers.heightMapNoiseCount > 0 || buffers.heightMapMandelbrotCount > 0
                    || buffers.heightMapBandedCount > 0 || buffers.heightMapNinePatchCount > 0
                    || buffers.heightMapFastNoiseCount > 0)) {
                final double[] output = buffers.nativeHeights();
                if (NativeSlices.fillHeightMapTree(heightMapOriginX, heightMapOriginY,
                        TILE_SIZE, TILE_SIZE, buffers.heightMapNodeCount,
                        buffers.heightMapOpcodes, buffers.heightMapValues,
                        buffers.heightMapScales, buffers.heightMapOctaves,
                        buffers.heightMapSeeds, output)) {
                    nativeHeights = output;
                    completeHeightMapValuesAvailable = true;
                }
            }
            if (nativeHeights == null && batchFreshSimpleTheme && Native.isGenEnabled()
                    && (heightMap.getClass() == SlopeHeightMap.class)) {
                final SlopeHeightMap slopeHeightMap = (SlopeHeightMap) heightMap;
                final HeightMap baseHeightMap = slopeHeightMap.getBaseHeightMap();
                if (!baseHeightMap.isConstant()
                        && areSlopeCoordinatesExactlyRepresentableAsFloats(worldTileX, worldTileY)
                        && buffers.prepareHeightMapProgram(baseHeightMap)
                        && (buffers.heightMapNoiseCount > 0 || buffers.heightMapMandelbrotCount > 0
                        || buffers.heightMapBandedCount > 0 || buffers.heightMapNinePatchCount > 0
                        || buffers.heightMapFastNoiseCount > 0)) {
                    final double[] baseSamples = buffers.nativeSlopeBaseHeights();
                    if (NativeSlices.fillHeightMapTree(worldTileX - 1, worldTileY - 1,
                            TILE_SIZE + 2, TILE_SIZE + 2, buffers.heightMapNodeCount,
                            buffers.heightMapOpcodes, buffers.heightMapValues,
                            buffers.heightMapScales, buffers.heightMapOctaves,
                            buffers.heightMapSeeds, baseSamples)) {
                        final double[] output = buffers.nativeHeights();
                        if (slopeHeightMap.fillSamples(baseSamples, TILE_SIZE + 2,
                                TILE_SIZE + 2, output)) {
                            nativeHeights = output;
                            completeHeightMapValuesAvailable = true;
                        }
                    }
                }
            }
            if (nativeHeights == null && batchFreshSimpleTheme && Native.isGenEnabled()
                    && isNativeTransformingHeightMap(heightMap)) {
                final TransformingHeightMap transforming = (TransformingHeightMap) heightMap;
                final HeightMap baseHeightMap = transforming.getBaseHeightMap();
                if (buffers.prepareHeightMapProgram(baseHeightMap)
                        && (buffers.heightMapNoiseCount > 0 || buffers.heightMapMandelbrotCount > 0
                        || buffers.heightMapBandedCount > 0 || buffers.heightMapNinePatchCount > 0
                        || buffers.heightMapFastNoiseCount > 0)) {
                    final AffineTransform transform = createTransformingHeightMapTransform(transforming);
                    final Point2D.Float coordinates = new Point2D.Float();
                    final float[] xCoordinates = buffers.displacementXCoordinates();
                    final float[] yCoordinates = buffers.displacementYCoordinates();
                    boolean coordinatesValid = true;
                    for (int y = 0; y < TILE_SIZE && coordinatesValid; y++) {
                        final int row = y * TILE_SIZE;
                        final int worldY = worldTileY + y;
                        for (int x = 0; x < TILE_SIZE; x++) {
                            final int index = row + x;
                            coordinates.setLocation((float) (worldTileX + x), (float) worldY);
                            transform.transform(coordinates, coordinates);
                            if (!Float.isFinite(coordinates.x) || !Float.isFinite(coordinates.y)) {
                                coordinatesValid = false;
                                break;
                            }
                            xCoordinates[index] = coordinates.x;
                            yCoordinates[index] = coordinates.y;
                        }
                    }
                    if (coordinatesValid) {
                        final double[] output = buffers.nativeHeights();
                        if (NativeSlices.fillHeightMapTreePoints(buffers.heightMapNodeCount,
                                buffers.heightMapOpcodes, buffers.heightMapValues,
                                buffers.heightMapScales, buffers.heightMapOctaves,
                                buffers.heightMapSeeds, xCoordinates, yCoordinates, output)) {
                            nativeHeights = output;
                            completeHeightMapValuesAvailable = true;
                        }
                    }
                }
            }
            if (nativeHeights == null && batchFreshSimpleTheme && Native.isGenEnabled()
                    && isNativeDisplacementHeightMap(heightMap)
                    && areTileCoordinatesExactlyRepresentableAsFloats(worldTileX, worldTileY)) {
                final DisplacementHeightMap displacement = (DisplacementHeightMap) heightMap;
                final double[] angleValues = buffers.nativeDisplacementAngleHeights();
                final double[] distanceValues = buffers.nativeDisplacementDistanceHeights();
                if (buffers.fillNativeHeightMapTree(displacement.getAngleMap(), worldTileX, worldTileY,
                        TILE_SIZE, TILE_SIZE, angleValues)
                        && buffers.fillNativeHeightMapTree(displacement.getDistanceMap(), worldTileX, worldTileY,
                        TILE_SIZE, TILE_SIZE, distanceValues)) {
                    final float[] xCoordinates = buffers.displacementXCoordinates();
                    final float[] yCoordinates = buffers.displacementYCoordinates();
                    boolean coordinatesValid = true;
                    for (int y = 0; y < TILE_SIZE && coordinatesValid; y++) {
                        final int row = y * TILE_SIZE;
                        final float worldY = worldTileY + y;
                        for (int x = 0; x < TILE_SIZE; x++) {
                            final int index = row + x;
                            final float worldX = worldTileX + x;
                            final double angle = angleValues[index];
                            final double distance = distanceValues[index];
                            final float actualX = (float) (worldX + Math.sin(angle) * distance);
                            final float actualY = (float) (worldY + Math.cos(angle) * distance);
                            if (!Float.isFinite(actualX) || !Float.isFinite(actualY)) {
                                coordinatesValid = false;
                                break;
                            }
                            xCoordinates[index] = actualX;
                            yCoordinates[index] = actualY;
                        }
                    }
                    if (coordinatesValid && buffers.prepareHeightMapProgram(displacement.getBaseHeightMap())) {
                        final double[] output = buffers.nativeHeights();
                        if (NativeSlices.fillHeightMapTreePoints(buffers.heightMapNodeCount,
                                buffers.heightMapOpcodes, buffers.heightMapValues,
                                buffers.heightMapScales, buffers.heightMapOctaves,
                                buffers.heightMapSeeds, xCoordinates, yCoordinates, output)) {
                            nativeHeights = output;
                            completeHeightMapValuesAvailable = true;
                        }
                    }
                }
            }
            final BitmapHeightMap bitmapHeightMap = getBulkReadableBitmapBase(heightMap);
            if (nativeHeights == null && batchFreshSimpleTheme && (bitmapHeightMap != null)) {
                final double[] output = buffers.nativeHeights();
                final boolean filled = isRepeatingBicubicHeightMap(heightMap)
                        ? bitmapHeightMap.fillRepeatedSamples(worldTileX, worldTileY,
                                TILE_SIZE, TILE_SIZE, output, buffers.bitmapRowSamples)
                        : bitmapHeightMap.fillSamples(worldTileX, worldTileY,
                                TILE_SIZE, TILE_SIZE, output, buffers.bitmapRowSamples);
                if (filled) {
                    nativeHeights = output;
                    completeHeightMapValuesAvailable = true;
                }
            }
            if (nativeNoiseMap != null) {
                if (nativeHeights != null) {
                    // The whole pure Sum/Noise expression has already been evaluated natively.
                } else if (batchFreshSimpleTheme) {
                    if (Native.isGenEnabled()) {
                        final double[] output = buffers.nativeHeights();
                        if (nativeNoiseMap.fillNativeHeights(worldTileX, worldTileY,
                                TILE_SIZE, TILE_SIZE, output)) {
                            nativeHeights = output;
                        }
                    }
                } else {
                    nativeHeights = nativeNoiseMap.getNativeHeights(
                            worldTileX, worldTileY, TILE_SIZE, TILE_SIZE);
                }
            }
            if (batchFreshSimpleTheme) {
                final float[] heights = buffers.heights;
                for (int x = 0; x < TILE_SIZE; x++) {
                    for (int y = 0; y < TILE_SIZE; y++) {
                        final int blockX = worldTileX + x, blockY = worldTileY + y;
                        final double rawHeight;
                        if (nativeHeights != null) {
                            final double noise = nativeHeights[y * TILE_SIZE + x];
                            rawHeight = completeHeightMapValuesAvailable || (nativeNoiseMap == heightMap) ? noise
                                    : (nativeConstantFirst ? nativeConstant + noise : noise + nativeConstant);
                        } else {
                            rawHeight = heightMap.getHeight(blockX, blockY);
                        }
                        heights[x | (y << TILE_SIZE_BITS)] = clamp(minHeight, (float) rawHeight, maxZ);
                    }
                }
                final int[] intHeights = tile.initializeHeightAndWaterLevels(
                        heights, myWaterHeight, buffers.intHeights);
                final SimpleTheme simpleTheme = (SimpleTheme) theme;
                final byte[] terrainOrdinals = buffers.terrainOrdinals;
                int lowestThemeHeight = Integer.MAX_VALUE;
                int highestThemeHeight = Integer.MIN_VALUE;
                for (int x = 0; x < TILE_SIZE; x++) {
                    for (int y = 0; y < TILE_SIZE; y++) {
                        final int index = x | (y << TILE_SIZE_BITS);
                        final int quantisedHeight = intHeights[index];
                        lowestThemeHeight = Math.min(lowestThemeHeight, quantisedHeight);
                        highestThemeHeight = Math.max(highestThemeHeight, quantisedHeight);
                        terrainOrdinals[index] = (byte) simpleTheme
                                .getTerrainForFreshTile(tile, x, y, quantisedHeight).ordinal();
                    }
                }
                tile.initializeTerrainOrdinals(terrainOrdinals);
                if (!simpleTheme.applyDeterministicLayersToFreshTile(tile, intHeights,
                        lowestThemeHeight, highestThemeHeight, terrainOrdinals)) {
                    for (int x = 0; x < TILE_SIZE; x++) {
                        for (int y = 0; y < TILE_SIZE; y++) {
                            simpleTheme.applyLayersToFreshTile(tile, x, y,
                                    intHeights[x | (y << TILE_SIZE_BITS)]);
                        }
                    }
                }
                return tile;
            }
            for (int x = 0; x < TILE_SIZE; x++) {
                for (int y = 0; y < TILE_SIZE; y++) {
                    final int blockX = worldTileX + x, blockY = worldTileY + y;
                    final double rawHeight;
                    if (nativeHeights != null) {
                        final double noise = nativeHeights[y * TILE_SIZE + x];
                        rawHeight = completeHeightMapValuesAvailable || (nativeNoiseMap == heightMap) ? noise
                                : (nativeConstantFirst ? nativeConstant + noise : noise + nativeConstant);
                    } else {
                        rawHeight = heightMap.getHeight(blockX, blockY);
                    }
                    final float height = clamp(minHeight, (float) rawHeight, maxZ);
                    tile.setHeight(x, y, height);
                    tile.setWaterLevel(x, y, myWaterHeight);
                    if (floodWithLava) {
                        tile.setBitLayerValue(FloodWithLava.INSTANCE, x, y, true);
                    }
                    if (freshSimpleTheme) {
                        ((SimpleTheme) theme).applyToFreshTile(tile, x, y);
                    } else {
                        theme.apply(tile, x, y);
                    }
                }
            }
            return tile;
        } finally {
            tile.releaseEvents();
        }
    }

    /** Only batch pure built-in heightmaps; custom implementations may depend on interleaved tile writes. */
    private static boolean isBatchSafeHeightMap(HeightMap heightMap) {
        if ((heightMap.getClass() == ConstantHeightMap.class)
                || (heightMap.getClass() == NoiseHeightMap.class)
                || (heightMap.getClass() == FastNoiseLiteHeightMap.class)
                || (heightMap.getClass() == MandelbrotHeightMap.class)
                || (heightMap.getClass() == NinePatchHeightMap.class)) {
            return true;
        }
        if ((heightMap.getClass() == SumHeightMap.class)
                || (heightMap.getClass() == DifferenceHeightMap.class)
                || (heightMap.getClass() == ProductHeightMap.class)
                || (heightMap.getClass() == MinimisingHeightMap.class)
                || (heightMap.getClass() == MaximisingHeightMap.class)) {
            final CombiningHeightMap combining = (CombiningHeightMap) heightMap;
            return isBatchSafeHeightMap(combining.getHeightMap1())
                    && isBatchSafeHeightMap(combining.getHeightMap2());
        }
        return false;
    }

    private static boolean isNativeSlopeHeightMap(HeightMap heightMap) {
        if (heightMap.getClass() != SlopeHeightMap.class) {
            return false;
        }
        final HeightMap base = ((SlopeHeightMap) heightMap).getBaseHeightMap();
        return isBatchSafeHeightMap(base) || isNativeBandedHeightMap(base);
    }

    private static boolean isNativeDisplacementHeightMap(HeightMap heightMap) {
        if (heightMap.getClass() != DisplacementHeightMap.class) {
            return false;
        }
        final DisplacementHeightMap displacement = (DisplacementHeightMap) heightMap;
        return !displacement.getBaseHeightMap().isConstant()
                && isSerializableHeightMapTree(displacement.getBaseHeightMap())
                && isSerializableHeightMapTree(displacement.getAngleMap())
                && isSerializableHeightMapTree(displacement.getDistanceMap());
    }

    private static boolean isNativeTransformingHeightMap(HeightMap heightMap) {
        if (heightMap.getClass() != TransformingHeightMap.class) {
            return false;
        }
        final TransformingHeightMap transforming = (TransformingHeightMap) heightMap;
        return ((transforming.getScaleX() != 1.0f) || (transforming.getScaleY() != 1.0f)
                || (transforming.getRotation() != 0.0f))
                && isSerializableHeightMapTree(transforming.getBaseHeightMap());
    }

    /** Rebuilds the exact affine operation order used by TransformingHeightMap. */
    private static AffineTransform createTransformingHeightMapTransform(TransformingHeightMap heightMap) {
        final AffineTransform transform = new AffineTransform();
        if ((heightMap.getScaleX() != 1.0f) || (heightMap.getScaleY() != 1.0f)) {
            transform.scale(1 / heightMap.getScaleX(), 1 / heightMap.getScaleY());
        }
        if ((heightMap.getOffsetX() != 0) || (heightMap.getOffsetY() != 0)) {
            transform.translate(-heightMap.getOffsetX(), -heightMap.getOffsetY());
        }
        if (heightMap.getRotation() != 0.0f) {
            transform.rotate(-heightMap.getRotation());
        }
        return transform;
    }

    private static boolean isSerializableHeightMapTree(HeightMap heightMap) {
        if ((heightMap.getClass() == ConstantHeightMap.class)
                || (heightMap.getClass() == NoiseHeightMap.class)
                || (heightMap.getClass() == FastNoiseLiteHeightMap.class)
                || (heightMap.getClass() == MandelbrotHeightMap.class)
                || (heightMap.getClass() == BandedHeightMap.class)) {
            return true;
        }
        if (heightMap.getClass() == NinePatchHeightMap.class) {
            final NinePatchHeightMap ninePatch = (NinePatchHeightMap) heightMap;
            return ninePatch.getInnerSizeX() == ninePatch.getInnerSizeY();
        }
        if ((heightMap.getClass() == SumHeightMap.class)
                || (heightMap.getClass() == DifferenceHeightMap.class)
                || (heightMap.getClass() == ProductHeightMap.class)
                || (heightMap.getClass() == MinimisingHeightMap.class)
                || (heightMap.getClass() == MaximisingHeightMap.class)) {
            final CombiningHeightMap combining = (CombiningHeightMap) heightMap;
            return isSerializableHeightMapTree(combining.getHeightMap1())
                    && isSerializableHeightMapTree(combining.getHeightMap2());
        }
        if (heightMap.getClass() == ShelvingHeightMap.class) {
            return isSerializableHeightMapTree(((ShelvingHeightMap) heightMap).getHeightMap(0));
        }
        return false;
    }

    private static boolean areSlopeCoordinatesExactlyRepresentableAsFloats(int originX, int originY) {
        final long limit = 1L << 24;
        return ((long) originX - 1 >= -limit) && ((long) originX + TILE_SIZE <= limit)
                && ((long) originY - 1 >= -limit) && ((long) originY + TILE_SIZE <= limit);
    }

    /** The measured Rust fast path currently specializes a standalone smooth banded map. */
    private static boolean isNativeBandedHeightMap(HeightMap heightMap) {
        return (heightMap.getClass() == BandedHeightMap.class)
                && ((BandedHeightMap) heightMap).isSmooth();
    }

    /** BitmapHeightMap supplies a bulk reader that preserves clipping and repeat semantics. */
    private static boolean isBulkReadableBitmapHeightMap(HeightMap heightMap) {
        return getBulkReadableBitmapBase(heightMap) != null;
    }

    /** Integer sampling of BicubicHeightMap delegates directly to its bitmap child, with optional wrapping. */
    private static BitmapHeightMap getBulkReadableBitmapBase(HeightMap heightMap) {
        if (heightMap.getClass() == BitmapHeightMap.class) {
            return (BitmapHeightMap) heightMap;
        }
        if (heightMap.getClass() == BicubicHeightMap.class) {
            final HeightMap base = ((BicubicHeightMap) heightMap).getHeightMap(0);
            if (base.getClass() == BitmapHeightMap.class) {
                return (BitmapHeightMap) base;
            }
        }
        return null;
    }

    private static boolean isRepeatingBicubicHeightMap(HeightMap heightMap) {
        return (heightMap.getClass() == BicubicHeightMap.class)
                && ((BicubicHeightMap) heightMap).isRepeat();
    }

    /** Returns only exact, translation-only wrappers with a batch-safe child. */
    private static TransformingHeightMap getBatchSafeTranslation(HeightMap heightMap) {
        if ((heightMap.getClass() == TransformingHeightMap.class)) {
            final TransformingHeightMap transforming = (TransformingHeightMap) heightMap;
            final HeightMap base = transforming.getBaseHeightMap();
            if ((transforming.getScaleX() == 1.0f) && (transforming.getScaleY() == 1.0f)
                    && (transforming.getRotation() == 0.0f)
                    && (isBatchSafeHeightMap(base) || isNativeBandedHeightMap(base))) {
                return transforming;
            }
        }
        return null;
    }

    /** Requires integral coordinates to survive ShelvingHeightMap's float delegation exactly. */
    private static boolean isNativeShelvingHeightMap(HeightMap heightMap, int originX, int originY) {
        if (heightMap.getClass() != ShelvingHeightMap.class) {
            return false;
        }
        final HeightMap base = ((ShelvingHeightMap) heightMap).getHeightMap(0);
        return (isBatchSafeHeightMap(base) || isNativeBandedHeightMap(base))
                && areTileCoordinatesExactlyRepresentableAsFloats(originX, originY);
    }

    private static boolean areTileCoordinatesExactlyRepresentableAsFloats(int originX, int originY) {
        final int exactFloatLimit = 1 << 24;
        return (originX >= -exactFloatLimit) && (originX <= exactFloatLimit - TILE_SIZE + 1)
                && (originY >= -exactFloatLimit) && (originY <= exactFloatLimit - TILE_SIZE + 1);
    }

    private static final class GenerationBuffers {
        private final float[] heights = new float[TILE_SIZE * TILE_SIZE];
        private final int[] intHeights = new int[TILE_SIZE * TILE_SIZE];
        private final byte[] terrainOrdinals = new byte[TILE_SIZE * TILE_SIZE];
        private final int[] heightMapOpcodes = new int[64];
        private final double[] heightMapValues = new double[64];
        private final double[] heightMapScales = new double[64];
        private final int[] heightMapOctaves = new int[64];
        private final long[] heightMapSeeds = new long[64];
        private final double[] bitmapRowSamples = new double[TILE_SIZE];
        private int heightMapNodeCount;
        private int heightMapNoiseCount;
        private int heightMapFastNoiseCount;
        private int heightMapMandelbrotCount;
        private int heightMapBandedCount;
        private int heightMapNinePatchCount;
        private double[] nativeHeightValues;
        private double[] nativeSlopeBaseValues;
        private double[] nativeDisplacementAngleValues;
        private double[] nativeDisplacementDistanceValues;
        private float[] displacementXCoordinateValues;
        private float[] displacementYCoordinateValues;

        private boolean prepareHeightMapProgram(HeightMap heightMap) {
            heightMapNodeCount = 0;
            heightMapNoiseCount = 0;
            heightMapFastNoiseCount = 0;
            heightMapMandelbrotCount = 0;
            heightMapBandedCount = 0;
            heightMapNinePatchCount = 0;
            return appendHeightMapNode(heightMap)
                    && ((heightMapNinePatchCount == 0) || Native.isNinePatchGenEnabled());
        }

        private boolean appendHeightMapNode(HeightMap heightMap) {
            if (heightMapNodeCount >= heightMapOpcodes.length) {
                return false;
            }
            final int index = heightMapNodeCount;
            if (heightMap.getClass() == ConstantHeightMap.class) {
                heightMapOpcodes[index] = 0;
                heightMapValues[index] = ((ConstantHeightMap) heightMap).getHeight();
                heightMapNodeCount++;
                return true;
            }
            if (heightMap.getClass() == NoiseHeightMap.class) {
                final NoiseHeightMap noise = (NoiseHeightMap) heightMap;
                heightMapOpcodes[index] = 1;
                heightMapValues[index] = noise.getHeight();
                heightMapScales[index] = noise.getScale();
                heightMapOctaves[index] = noise.getOctaves();
                heightMapSeeds[index] = noise.getSeed() + noise.getSeedOffset();
                heightMapNoiseCount++;
                heightMapNodeCount++;
                return true;
            }
            if (heightMap.getClass() == FastNoiseLiteHeightMap.class) {
                final FastNoiseLiteHeightMap noise = (FastNoiseLiteHeightMap) heightMap;
                heightMapOpcodes[index] = 13;
                heightMapValues[index] = noise.getHeight();
                heightMapScales[index] = 1.0 / (Constants.LARGE_BLOBS * noise.getScale());
                heightMapOctaves[index] = noise.getOctaves();
                heightMapSeeds[index] = noise.getSeed() + noise.getSeedOffset();
                heightMapFastNoiseCount++;
                heightMapNodeCount++;
                return true;
            }
            if (heightMap.getClass() == MandelbrotHeightMap.class) {
                heightMapOpcodes[index] = 8;
                heightMapNodeCount++;
                heightMapMandelbrotCount++;
                return true;
            }
            if (heightMap.getClass() == BandedHeightMap.class) {
                final BandedHeightMap banded = (BandedHeightMap) heightMap;
                heightMapOpcodes[index] = banded.isSmooth() ? 10 : 9;
                heightMapValues[index] = banded.getSegment1EndHeight();
                heightMapScales[index] = banded.getSegment2EndHeight();
                heightMapOctaves[index] = banded.getSegment1Length();
                heightMapSeeds[index] = banded.getSegment2Length();
                heightMapNodeCount++;
                heightMapBandedCount++;
                return true;
            }
            if (heightMap.getClass() == NinePatchHeightMap.class) {
                final NinePatchHeightMap ninePatch = (NinePatchHeightMap) heightMap;
                if (ninePatch.getInnerSizeX() != ninePatch.getInnerSizeY()) {
                    return false;
                }
                heightMapOpcodes[index] = 12;
                heightMapValues[index] = ninePatch.getHeight();
                heightMapScales[index] = ninePatch.getCoastSize();
                heightMapOctaves[index] = ninePatch.getInnerSizeX();
                heightMapSeeds[index] = ninePatch.getBorderSize();
                heightMapNodeCount++;
                heightMapNinePatchCount++;
                return true;
            }
            if (heightMap.getClass() == ShelvingHeightMap.class) {
                final ShelvingHeightMap shelving = (ShelvingHeightMap) heightMap;
                if (!appendHeightMapNode(shelving.getHeightMap(0))
                        || heightMapNodeCount >= heightMapOpcodes.length) {
                    return false;
                }
                final int operatorIndex = heightMapNodeCount++;
                heightMapOpcodes[operatorIndex] = 11;
                heightMapOctaves[operatorIndex] = shelving.getShelveHeight();
                heightMapSeeds[operatorIndex] = shelving.getShelveStrength();
                return true;
            }
            final int operator;
            if (heightMap.getClass() == SumHeightMap.class) {
                operator = 2;
            } else if (heightMap.getClass() == DifferenceHeightMap.class) {
                operator = 3;
            } else if (heightMap.getClass() == ProductHeightMap.class) {
                operator = 4;
            } else if (heightMap.getClass() == MinimisingHeightMap.class) {
                operator = 5;
            } else if (heightMap.getClass() == MaximisingHeightMap.class) {
                operator = 6;
            } else {
                return false;
            }
            if (heightMap instanceof CombiningHeightMap) {
                final CombiningHeightMap combining = (CombiningHeightMap) heightMap;
                if (!appendHeightMapNode(combining.getHeightMap1())
                        || !appendHeightMapNode(combining.getHeightMap2())
                        || heightMapNodeCount >= heightMapOpcodes.length) {
                    return false;
                }
                heightMapOpcodes[heightMapNodeCount++] = operator;
                return true;
            }
            return false;
        }

        private double[] nativeHeights() {
            if (nativeHeightValues == null) {
                nativeHeightValues = new double[TILE_SIZE * TILE_SIZE];
            }
            return nativeHeightValues;
        }

        private double[] nativeSlopeBaseHeights() {
            if (nativeSlopeBaseValues == null) {
                nativeSlopeBaseValues = new double[(TILE_SIZE + 2) * (TILE_SIZE + 2)];
            }
            return nativeSlopeBaseValues;
        }

        private double[] nativeDisplacementAngleHeights() {
            if (nativeDisplacementAngleValues == null) {
                nativeDisplacementAngleValues = new double[TILE_SIZE * TILE_SIZE];
            }
            return nativeDisplacementAngleValues;
        }

        private double[] nativeDisplacementDistanceHeights() {
            if (nativeDisplacementDistanceValues == null) {
                nativeDisplacementDistanceValues = new double[TILE_SIZE * TILE_SIZE];
            }
            return nativeDisplacementDistanceValues;
        }

        private float[] displacementXCoordinates() {
            if (displacementXCoordinateValues == null) {
                displacementXCoordinateValues = new float[TILE_SIZE * TILE_SIZE];
            }
            return displacementXCoordinateValues;
        }

        private float[] displacementYCoordinates() {
            if (displacementYCoordinateValues == null) {
                displacementYCoordinateValues = new float[TILE_SIZE * TILE_SIZE];
            }
            return displacementYCoordinateValues;
        }

        private boolean fillNativeHeightMapTree(HeightMap map, int originX, int originY,
                                                int width, int height, double[] output) {
            return prepareHeightMapProgram(map)
                    && NativeSlices.fillHeightMapTree(originX, originY, width, height,
                    heightMapNodeCount, heightMapOpcodes, heightMapValues,
                    heightMapScales, heightMapOctaves, heightMapSeeds, output);
        }
    }

    private static final ThreadLocal<GenerationBuffers> GENERATION_BUFFERS =
            ThreadLocal.withInitial(GenerationBuffers::new);

    @Override
    public Rectangle getExtent() {
        Rectangle heightMapExtent = heightMap.getExtent();
        if (heightMapExtent != null) {
            int tileX1 = heightMapExtent.x >> TILE_SIZE_BITS;
            int tileY1 = heightMapExtent.y >> TILE_SIZE_BITS;
            int tileX2 = (heightMapExtent.x + heightMapExtent.width - 1) >> TILE_SIZE_BITS;
            int tileY2 = (heightMapExtent.y + heightMapExtent.height - 1) >> TILE_SIZE_BITS;
            return new Rectangle(tileX1, tileY1, (tileX2 - tileX1) + 1, (tileY2 - tileY1) + 1);
        } else {
            return null;
        }
    }

    @Override
    public final void applyTheme(Tile tile, int x, int y) {
        theme.apply(tile, x, y);
    }

    @Override
    public void transform(CoordinateTransform transform) {
        heightMap = transform.transform(heightMap);
    }

    protected final void setRandomise(boolean randomise) {
        this.randomise = randomise;
    }

    protected final void setBeaches(boolean beaches) {
        this.beaches = beaches;
    }
    
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        
        // Legacy map support
        if (maxHeight == 0) {
            maxHeight = 128;
        }
        if (version < 1) {
            theme = (terrainRanges != null)
                ? new SimpleTheme(seed, waterHeight, terrainRanges, null, minHeight, maxHeight, randomise, beaches)
                : new SimpleTheme(seed, waterHeight, terrainRangesTable, minHeight, maxHeight, randomise, beaches);
            waterHeight = -1;
            terrainRanges = null;
            terrainRangesTable = null;
            randomise = false;
            beaches = false;
        }
        version = CURRENT_VERSION;
    }
    
    @Deprecated
    int waterHeight = -1;
    
    @Deprecated
    private Terrain[] terrainRangesTable;
    private final boolean floodWithLava;
    private int minHeight, maxHeight;
    @Deprecated
    private SortedMap<Integer, Terrain> terrainRanges;
    @Deprecated
    private boolean randomise, beaches;
    private long seed;
    private HeightMap heightMap;
    private Theme theme;
    private int version = CURRENT_VERSION;

    private static final long serialVersionUID = 2011032801L;
    
    private static final int CURRENT_VERSION = 1;
}
