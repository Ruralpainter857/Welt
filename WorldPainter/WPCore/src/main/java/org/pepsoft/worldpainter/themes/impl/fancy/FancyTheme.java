/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.pepsoft.worldpainter.themes.impl.fancy;

import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.heightMaps.*;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.layers.groundcover.GroundCoverLayer;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import org.pepsoft.worldpainter.themes.Theme;

import java.util.Random;

import static java.awt.Color.WHITE;
import static org.pepsoft.minecraft.Material.SNOW_BLOCK;
import static org.pepsoft.worldpainter.Constants.TILE_SIZE;
import static org.pepsoft.util.MathUtils.clamp;

/**
 *
 * @author SchmitzP
 */
public class FancyTheme implements Theme, Cloneable {
    public static final int HEIGHT_NEIGHBORHOOD_RADIUS = 5;
    private static final String FRESH_TILE_BATCH_PROPERTY = "wp.fancyTheme.freshTileBatch";
    private static final ThreadLocal<FancyThemeHeightContext> FRESH_TILE_HEIGHT_CONTEXTS =
            ThreadLocal.withInitial(FancyThemeHeightContext::new);

    public FancyTheme(int minHeight, int maxHeight, int waterHeight, HeightMap heightMap, Terrain baseTerrain) {
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        setWaterHeight(waterHeight);
        setHeightMap(heightMap);
        setDesertMaxHeight(waterHeight + 20);
        Random random = (heightMap != null) ? new Random(heightMap.getSeed()) : new Random();
        setTemperatureMap(new SumHeightMap(new NoiseHeightMap(60f, 10.0, 2, random.nextLong()), new ConstantHeightMap(-20f)));
        setHumidityMap(new NoiseHeightMap(100f, 10.0, 2, random.nextLong()));
        setForestMap(new NoiseHeightMap(1f, 1.0, 3, random.nextLong()));
        setBaseTerrain(baseTerrain);
        snowLayer.setThickness(5);
        snowLayer.setEdgeWidth(15);
        snowLayer.setEdgeShape(GroundCoverLayer.EdgeShape.SMOOTH);
    }

    @Override
    public void apply(Tile tile, int x, int y) {
        apply(tile, x, y, null);
    }

    /** Returns whether this built-in theme can use the pure-map fresh-tile path. */
    public final boolean supportsFreshTileBatch() {
        return Boolean.parseBoolean(System.getProperty(FRESH_TILE_BATCH_PROPERTY, "true"))
                && supportsFreshTileMaps();
    }

    /** Applies this theme to a complete fresh tile using one reusable neighbourhood cache. */
    public final void applyFreshTile(Tile tile, int worldTileX, int worldTileY) {
        applyFreshTile(tile, worldTileX, worldTileY, 0, 0, waterHeight, null);
    }

    /** Applies a precomputed height neighbourhood and initializes terrain heights before theming. */
    public final void applyFreshTile(Tile tile, int worldTileX, int worldTileY,
                                     int minHeight, int maxHeight, int tileWaterLevel,
                                     double[] rawHeightNeighborhood) {
        if (!supportsFreshTileMaps()) {
            throw new IllegalStateException("Fresh-tile batching is not supported for this FancyTheme");
        }
        final FancyThemeHeightContext context = FRESH_TILE_HEIGHT_CONTEXTS.get();
        context.prepare(this, worldTileX, worldTileY, rawHeightNeighborhood);
        if (rawHeightNeighborhood != null) {
            for (int x = 0; x < TILE_SIZE; x++) {
                for (int y = 0; y < TILE_SIZE; y++) {
                    final int worldX = worldTileX + x;
                    final int worldY = worldTileY + y;
                    tile.setHeight(x, y, clamp(minHeight, context.getHeight(worldX, worldY), maxHeight - 1));
                    tile.setWaterLevel(x, y, tileWaterLevel);
                }
            }
        }
        if (applyNativeFreshTile(tile, worldTileX, worldTileY, context)) {
            return;
        }
        for (int x = 0; x < TILE_SIZE; x++) {
            for (int y = 0; y < TILE_SIZE; y++) {
                apply(tile, x, y, context);
            }
        }
    }

    private boolean applyNativeFreshTile(Tile tile, int worldTileX, int worldTileY,
                                         FancyThemeHeightContext context) {
        if (!Native.isGenEnabled() || !NativeLoader.areSlicesAvailable()) {
            return false;
        }
        final FancyThemeTileBuffers buffers = context.buffers();
        final int area = TILE_SIZE * TILE_SIZE;
        context.copyHeightNeighborhood(buffers.heightNeighborhood);
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                final int index = x | (y << 7);
                final int worldX = worldTileX + x;
                final int worldY = worldTileY + y;
                final float height = tile.getHeight(x, y);
                double temperature = temperatureMap.getHeight(worldX, worldY);
                temperature = temperature - Math.max(height - waterHeight, 0) / 2
                        + randomNoiseMap.getHeight(worldX, worldY);
                buffers.temperatures[index] = temperature;
                buffers.humidities[index] = humidityMap.getHeight(worldX, worldY)
                        + randomNoiseMap.getHeight(worldX, worldY);
                buffers.forestValues[index] = forestMap.getHeight(worldX, worldY);
                buffers.tileHeights[index] = height;
            }
        }
        if (!NativeSlices.fillFancyThemeTile(TILE_SIZE, TILE_SIZE, waterHeight, desertMaxHeight,
                baseTerrain.ordinal(), Terrain.DESERT.ordinal(), Terrain.SANDSTONE.ordinal(),
                Terrain.BARE_GRASS.ordinal(), Terrain.BEACHES.ordinal(),
                terrainDirtAndGravel.ordinal(), terrainStoneAndGravel.ordinal(),
                buffers.tileHeights, buffers.heightNeighborhood, buffers.temperatures,
                buffers.humidities, buffers.forestValues, buffers.output)) {
            return false;
        }

        final Terrain[] terrains = Terrain.values();
        for (int index = 0; index < area; index++) {
            if ((buffers.output[index] & 0xff) >= terrains.length) {
                return false;
            }
        }
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                final int index = x | (y << 7);
                tile.setTerrain(x, y, terrains[buffers.output[index] & 0xff]);
                final int jungle = buffers.output[area + index] & 0xff;
                if (jungle != 0) {
                    tile.setLayerValue(Jungle.INSTANCE, x, y, jungle);
                }
                final int swamp = buffers.output[area * 2 + index] & 0xff;
                if (swamp != 0) {
                    tile.setLayerValue(SwampLand.INSTANCE, x, y, swamp);
                }
                final int deciduous = buffers.output[area * 3 + index] & 0xff;
                if (deciduous != 0) {
                    tile.setLayerValue(DeciduousForest.INSTANCE, x, y, deciduous);
                }
                final int pine = buffers.output[area * 4 + index] & 0xff;
                if (pine != 0) {
                    tile.setLayerValue(PineForest.INSTANCE, x, y, pine);
                }
                if (buffers.output[area * 5 + index] != 0) {
                    tile.setBitLayerValue(Frost.INSTANCE, x, y, true);
                }
                if (buffers.output[area * 6 + index] != 0) {
                    tile.setBitLayerValue(snowLayer, x, y, true);
                }
            }
        }
        return true;
    }

    private void apply(Tile tile, int x, int y, FancyThemeHeightContext context) {
        final int worldX = (tile.getX() << 7) | x, worldY = (tile.getY() << 7) | y;
        double temperature = temperatureMap.getHeight(worldX, worldY);
        float height = tile.getHeight(x, y);
        temperature = temperature - Math.max(height - waterHeight, 0) / 2 + randomNoiseMap.getHeight(worldX, worldY);
        double humidity = humidityMap.getHeight(worldX, worldY) + randomNoiseMap.getHeight(worldX, worldY);
        final float slopeNOSO = Math.abs(getHeight(worldX, worldY - 1, context) - getHeight(worldX, worldY + 1, context));
        final float slopeNWSE = Math.abs(getHeight(worldX + 1, worldY - 1, context) - getHeight(worldX - 1, worldY + 1, context));
        final float slopeEAWE = Math.abs(getHeight(worldX + 1, worldY, context) - getHeight(worldX - 1, worldY, context));
        final float slopeSENW = Math.abs(getHeight(worldX + 1, worldY + 1, context) - getHeight(worldX - 1, worldY - 1, context));
        final float slope = Math.max(Math.max(slopeNOSO, slopeNWSE), Math.max(slopeEAWE, slopeSENW));
        if (slope > 2f) {
            tile.setTerrain(x, y, terrainStoneAndGravel);
        } else {
            if (slope > 1.5f) {
                tile.setTerrain(x, y, terrainDirtAndGravel);
            } else if (height < (waterHeight - 4)) {
                tile.setTerrain(x, y, Terrain.BEACHES);
            } else if ((height < (waterHeight + 2)) && isWaterNear(worldX, worldY, context)) {
                tile.setTerrain(x, y, Terrain.BEACHES);
                if ((temperature > 20) && (humidity > 55) && (slope < 0.75f) && (height < desertMaxHeight) && (forestMap.getHeight(worldX, worldY) > 0.35f)) {
                    tile.setLayerValue(Jungle.INSTANCE, x, y, 8);
                }
            } else if (temperature < -5) {
                tile.setTerrain(x, y, Terrain.BARE_GRASS);
            } else if (humidity < 40) {
                if ((slope < 0.75f) && (height < desertMaxHeight)) {
                    tile.setTerrain(x, y, Terrain.DESERT);
                } else {
                    tile.setTerrain(x, y, Terrain.SANDSTONE);
                }
            } else {
                tile.setTerrain(x, y, baseTerrain);
            }
            if ((height > (waterHeight - 4)) && (forestMap.getHeight(worldX, worldY) > 0.35f)) {
                if (temperature > 20) {
                    if (humidity > 55) {
                        if (height < waterHeight + 2) {
                            tile.setLayerValue(SwampLand.INSTANCE, x, y, 8);
                        } else {
                            tile.setLayerValue(Jungle.INSTANCE, x, y, 8);
                        }
                    } else if (humidity > 40) {
                        tile.setLayerValue(DeciduousForest.INSTANCE, x, y, 8);
                    }
                } else if (temperature > 10) {
                    if (humidity > 50) {
                        tile.setLayerValue(DeciduousForest.INSTANCE, x, y, 8);
                    }
                } else if (temperature > -20) {
                    if (humidity > 50) {
                        tile.setLayerValue(PineForest.INSTANCE, x, y, 8);
                    }
                }
            }
        }
        if (temperature < 0) {
            tile.setBitLayerValue(Frost.INSTANCE, x, y, true);
            if ((temperature < -10) && (humidity > 50) && (height > waterHeight) && (slope < 1.5f)) {
                tile.setBitLayerValue(snowLayer, x, y, true);
            }
        }
    }

    @Override
    public final int getWaterHeight() {
        return waterHeight;
    }

    @Override
    public final void setWaterHeight(int waterLevel) {
        this.waterHeight = waterLevel;
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
    public final void setMinMaxHeight(int minHeight, int maxHeight, HeightTransform transform) {
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        // TODO apply transform
    }

    @Override
    public final long getSeed() {
        return humidityMap.getSeed();
    }

    @Override
    public final void setSeed(long seed) {
        humidityMap.setSeed(seed);
        temperatureMap.setSeed(seed);
    }

    public final HeightMap getHumidityMap() {
        return humidityMap;
    }

    public final void setHumidityMap(HeightMap humidityMap) {
        this.humidityMap = humidityMap;
    }

    public final HeightMap getTemperatureMap() {
        return temperatureMap;
    }

    public final void setTemperatureMap(HeightMap temperatureMap) {
        this.temperatureMap = temperatureMap;
    }

    public final HeightMap getHeightMap() {
        return heightMap;
    }

    public final void setHeightMap(HeightMap heightMap) {
        this.heightMap = heightMap;
    }

    public final float getRockySlope() {
        return rockySlope;
    }

    public final void setRockySlope(float rockySlope) {
        this.rockySlope = rockySlope;
    }

    public final int getDesertMaxHeight() {
        return desertMaxHeight;
    }

    public final void setDesertMaxHeight(int desertMaxHeight) {
        this.desertMaxHeight = desertMaxHeight;
    }

    public final HeightMap getForestMap() {
        return forestMap;
    }

    public final void setForestMap(HeightMap forestMap) {
        this.forestMap = forestMap;
    }

    public final Terrain getBaseTerrain() {
        return baseTerrain;
    }

    public final void setBaseTerrain(Terrain baseTerrain) {
        this.baseTerrain = baseTerrain;
    }

    @Override
    public FancyTheme clone() {
        try {
            FancyTheme clone = (FancyTheme) super.clone();
            clone.forestMap = forestMap.clone();
            if (heightMap != null) {
                clone.heightMap = heightMap.clone();
            }
            clone.humidityMap = humidityMap.clone();
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    protected float getHeight(int x, int y) {
        return (float) heightMap.getHeight(x, y);
    }

    private float getHeight(int x, int y, FancyThemeHeightContext context) {
        return (context == null) ? getHeight(x, y) : context.getHeight(x, y);
    }

    private boolean isWaterNear(int x, int y) {
        if (getHeight(x, y) < waterHeight) {
            return true;
        }
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 5; dy++) {
                if (getHeight(x + dx, y + dy) < waterHeight) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isWaterNear(int x, int y, FancyThemeHeightContext context) {
        return (context == null) ? isWaterNear(x, y) : context.isWaterNear(x, y);
    }

    private boolean supportsFreshTileMaps() {
        return (getClass() == FancyTheme.class)
                && isPureHeightMap(heightMap)
                && isPureHeightMap(temperatureMap)
                && isPureHeightMap(humidityMap)
                && isPureHeightMap(forestMap)
                && isPureHeightMap(randomNoiseMap);
    }

    private static boolean isPureHeightMap(HeightMap map) {
        if (map == null) {
            return false;
        }
        final Class<?> type = map.getClass();
        if ((type == ConstantHeightMap.class)
                || (type == NoiseHeightMap.class)
                || (type == FastNoiseLiteHeightMap.class)
                || (type == MandelbrotHeightMap.class)
                || (type == BandedHeightMap.class)
                || (type == NinePatchHeightMap.class)
                || (type == BitmapHeightMap.class)
                || (type == BicubicHeightMap.class)) {
            return true;
        }
        if ((type == SumHeightMap.class) || (type == DifferenceHeightMap.class)
                || (type == ProductHeightMap.class) || (type == MinimisingHeightMap.class)
                || (type == MaximisingHeightMap.class)) {
            final CombiningHeightMap combining = (CombiningHeightMap) map;
            return isPureHeightMap(combining.getHeightMap1())
                    && isPureHeightMap(combining.getHeightMap2());
        }
        if (type == TransformingHeightMap.class) {
            return isPureHeightMap(((TransformingHeightMap) map).getBaseHeightMap());
        }
        if (type == DisplacementHeightMap.class) {
            final DisplacementHeightMap displacement = (DisplacementHeightMap) map;
            return isPureHeightMap(displacement.getBaseHeightMap())
                    && isPureHeightMap(displacement.getAngleMap())
                    && isPureHeightMap(displacement.getDistanceMap());
        }
        if (type == SlopeHeightMap.class) {
            return isPureHeightMap(((SlopeHeightMap) map).getBaseHeightMap());
        }
        if (type == ShelvingHeightMap.class) {
            return isPureHeightMap(((ShelvingHeightMap) map).getHeightMap(0));
        }
        return false;
    }

    private static final class FancyThemeHeightContext {
        private float[] javaHeights;
        private double[] nativeHeights;
        private final short[] waterPrefix = new short[PREFIX_SIZE * PREFIX_SIZE];
        private final FancyThemeTileBuffers buffers = new FancyThemeTileBuffers();
        private int originX, originY;

        FancyThemeTileBuffers buffers() {
            return buffers;
        }

        void copyHeightNeighborhood(float[] output) {
            if (output.length != HEIGHT_SIZE * HEIGHT_SIZE) {
                throw new IllegalArgumentException("Expected a complete height neighbourhood buffer");
            }
            if (nativeHeights != null) {
                for (int index = 0; index < output.length; index++) {
                    output[index] = (float) nativeHeights[index];
                }
            } else {
                System.arraycopy(javaHeights, 0, output, 0, output.length);
            }
        }

        void prepare(FancyTheme theme, int worldTileX, int worldTileY, double[] rawHeightNeighborhood) {
            if ((rawHeightNeighborhood != null)
                    && (rawHeightNeighborhood.length != HEIGHT_SIZE * HEIGHT_SIZE)) {
                throw new IllegalArgumentException("Expected the complete height neighbourhood");
            }
            originX = worldTileX - HEIGHT_NEIGHBORHOOD_RADIUS;
            originY = worldTileY - HEIGHT_NEIGHBORHOOD_RADIUS;
            nativeHeights = rawHeightNeighborhood;
            if (rawHeightNeighborhood == null) {
                if ((javaHeights == null) || (javaHeights.length != HEIGHT_SIZE * HEIGHT_SIZE)) {
                    javaHeights = new float[HEIGHT_SIZE * HEIGHT_SIZE];
                }
            }
            java.util.Arrays.fill(waterPrefix, (short) 0);
            for (int y = 0; y < HEIGHT_SIZE; y++) {
                int rowWaterCount = 0;
                final int heightRow = y * HEIGHT_SIZE;
                final int prefixRow = (y + 1) * PREFIX_SIZE;
                final int prefixAbove = y * PREFIX_SIZE;
                final int worldY = originY + y;
                for (int x = 0; x < HEIGHT_SIZE; x++) {
                    final int index = heightRow + x;
                    final float value;
                    if (nativeHeights != null) {
                        value = (float) nativeHeights[index];
                    } else {
                        value = theme.getHeight(originX + x, worldY);
                        javaHeights[index] = value;
                    }
                    if (value < theme.waterHeight) {
                        rowWaterCount++;
                    }
                    waterPrefix[prefixRow + x + 1] = (short)
                            (waterPrefix[prefixAbove + x + 1] + rowWaterCount);
                }
            }
        }

        float getHeight(int worldX, int worldY) {
            final int index = (worldY - originY) * HEIGHT_SIZE + (worldX - originX);
            return (nativeHeights != null) ? (float) nativeHeights[index] : javaHeights[index];
        }

        boolean isWaterNear(int worldX, int worldY) {
            final int left = worldX - originX - HEIGHT_NEIGHBORHOOD_RADIUS;
            final int top = worldY - originY - HEIGHT_NEIGHBORHOOD_RADIUS;
            final int right = left + WATER_WINDOW_SIZE;
            final int bottom = top + WATER_WINDOW_SIZE;
            final int count = waterPrefix[bottom * PREFIX_SIZE + right]
                    - waterPrefix[top * PREFIX_SIZE + right]
                    - waterPrefix[bottom * PREFIX_SIZE + left]
                    + waterPrefix[top * PREFIX_SIZE + left];
            return count > 0;
        }

        private static final int HEIGHT_SIZE = TILE_SIZE + HEIGHT_NEIGHBORHOOD_RADIUS * 2;
        private static final int PREFIX_SIZE = HEIGHT_SIZE + 1;
        private static final int WATER_WINDOW_SIZE = HEIGHT_NEIGHBORHOOD_RADIUS * 2 + 1;
    }

    private static final class FancyThemeTileBuffers {
        private final float[] tileHeights = new float[TILE_SIZE * TILE_SIZE];
        private final float[] heightNeighborhood = new float[
                (TILE_SIZE + HEIGHT_NEIGHBORHOOD_RADIUS * 2)
                        * (TILE_SIZE + HEIGHT_NEIGHBORHOOD_RADIUS * 2)];
        private final double[] temperatures = new double[TILE_SIZE * TILE_SIZE];
        private final double[] humidities = new double[TILE_SIZE * TILE_SIZE];
        private final double[] forestValues = new double[TILE_SIZE * TILE_SIZE];
        private final byte[] output = new byte[TILE_SIZE * TILE_SIZE * 7];
    }

    protected GroundCoverLayer snowLayer = new GroundCoverLayer("Mountain Snow", MixedMaterial.create("Deep Snow", SNOW_BLOCK), WHITE);
    protected Terrain terrainDirtAndGravel = Terrain.CUSTOM_1;
    protected Terrain terrainStoneAndGravel = Terrain.CUSTOM_2;

    private int minHeight, maxHeight, waterHeight, desertMaxHeight;
    /**
     * Humidity in %.
     */
    private HeightMap humidityMap;
    /**
     * Temperature in °C.
     */
    private HeightMap temperatureMap;
    private HeightMap heightMap;
    private float rockySlope = 1.5f;
    private HeightMap forestMap;
    private Terrain baseTerrain;
    private final HeightMap randomNoiseMap = new SumHeightMap(new ConstantHeightMap(-5f), new NoiseHeightMap(10f, 1.0, 3));

    private static final long serialVersionUID = 1L;
}
