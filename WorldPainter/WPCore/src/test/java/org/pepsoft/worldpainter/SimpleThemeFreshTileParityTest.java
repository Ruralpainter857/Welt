package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.worldpainter.heightMaps.ConstantHeightMap;
import org.pepsoft.worldpainter.heightMaps.BitmapHeightMap;
import org.pepsoft.worldpainter.heightMaps.BicubicHeightMap;
import org.pepsoft.worldpainter.heightMaps.BandedHeightMap;
import org.pepsoft.worldpainter.heightMaps.DifferenceHeightMap;
import org.pepsoft.worldpainter.heightMaps.DisplacementHeightMap;
import org.pepsoft.worldpainter.heightMaps.NoiseHeightMap;
import org.pepsoft.worldpainter.heightMaps.MinimisingHeightMap;
import org.pepsoft.worldpainter.heightMaps.MaximisingHeightMap;
import org.pepsoft.worldpainter.heightMaps.MandelbrotHeightMap;
import org.pepsoft.worldpainter.heightMaps.NinePatchHeightMap;
import org.pepsoft.worldpainter.heightMaps.ProductHeightMap;
import org.pepsoft.worldpainter.heightMaps.SumHeightMap;
import org.pepsoft.worldpainter.heightMaps.TransformingHeightMap;
import org.pepsoft.worldpainter.heightMaps.ShelvingHeightMap;
import org.pepsoft.worldpainter.heightMaps.SlopeHeightMap;
import org.pepsoft.worldpainter.layers.Annotations;
import org.pepsoft.worldpainter.layers.Biome;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.layers.Populate;
import org.pepsoft.worldpainter.layers.Resources;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.themes.Filter;
import org.pepsoft.worldpainter.themes.HeightFilter;
import org.pepsoft.worldpainter.themes.SimpleTheme;

import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Verifies the optimized initialization path against ordinary theme application. */
public final class SimpleThemeFreshTileParityTest {
    @Test
    public void javaNoiseHeightMapSimpleThemeBatchMatchesPerCellFallback() {
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            Native.setGenEnabled(false);
            final HeightMapTileFactory referenceFactory = new HeightMapTileFactory(42L,
                    new SumHeightMap(new ConstantHeightMap(62), new NoiseHeightMap(20.0, 1.0, 1, 0L)),
                    0, 256, false, createNoisySimpleTheme(true));
            final HeightMapTileFactory batchFactory = new HeightMapTileFactory(42L,
                    new SumHeightMap(new ConstantHeightMap(62), new NoiseHeightMap(20.0, 1.0, 1, 0L)),
                    0, 256, false, createNoisySimpleTheme(false));
            final Tile reference = referenceFactory.createTile(-3, 7);
            final Tile batch = batchFactory.createTile(-3, 7);
            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    assertEquals("height at " + x + ',' + y,
                            Float.floatToRawIntBits(reference.getHeight(x, y)),
                            Float.floatToRawIntBits(batch.getHeight(x, y)));
                    assertEquals("water at " + x + ',' + y,
                            reference.getWaterLevel(x, y), batch.getWaterLevel(x, y));
                    assertEquals("terrain at " + x + ',' + y,
                            reference.getTerrain(x, y), batch.getTerrain(x, y));
                    assertEquals("Frost at " + x + ',' + y,
                            reference.getBitLayerValue(Frost.INSTANCE, x, y),
                            batch.getBitLayerValue(Frost.INSTANCE, x, y));
                }
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
        }
    }

    @Test
    public void nativeNestedSumHeightMapsMatchJavaFreshTile() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            final HeightMap javaMap = new SumHeightMap(
                    new SumHeightMap(new ConstantHeightMap(42.25),
                            new NoiseHeightMap(17.5, 0.75, 2, -0x1020_3040L)),
                    new NoiseHeightMap(8.25, 1.5, 4, 0x5566_7788L));
            final HeightMap nativeMap = new SumHeightMap(
                    new SumHeightMap(new ConstantHeightMap(42.25),
                            new NoiseHeightMap(17.5, 0.75, 2, -0x1020_3040L)),
                    new NoiseHeightMap(8.25, 1.5, 4, 0x5566_7788L));
            final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L, javaMap,
                    0, 256, false, createSimpleTheme(true));
            final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L, nativeMap,
                    0, 256, false, createSimpleTheme(false));
            Native.setGenEnabled(false);
            final Tile javaTile = javaFactory.createTile(-2, 5);
            Native.setGenEnabled(true);
            final Tile nativeTile = nativeFactory.createTile(-2, 5);
            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    assertEquals("height at " + x + ',' + y,
                            Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                            Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                    assertEquals("water at " + x + ',' + y,
                            javaTile.getWaterLevel(x, y), nativeTile.getWaterLevel(x, y));
                    assertEquals("terrain at " + x + ',' + y,
                            javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
                }
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
        }
    }

    @Test
    public void deterministicSimpleThemeLayersBatchMatchesPerCellForEveryStorageSize() {
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            Native.setGenEnabled(false);
            final HeightMap referenceHeightMap = createFrostExerciseHeightMap();
            final SimpleTheme referenceTheme = createDeterministicLayerTheme(false);
            referenceHeightMap.setSeed(42L);
            referenceTheme.setSeed(42L);
            final HeightMapTileFactory batchFactory = new HeightMapTileFactory(42L,
                    createFrostExerciseHeightMap(), 0, 256, false, createDeterministicLayerTheme(false));
            final Tile reference = createPerCellFreshTile(referenceHeightMap, referenceTheme, -3, 7,
                    new float[Constants.TILE_SIZE * Constants.TILE_SIZE],
                    new int[Constants.TILE_SIZE * Constants.TILE_SIZE],
                    new byte[Constants.TILE_SIZE * Constants.TILE_SIZE]);
            final Tile batch = batchFactory.createTile(-3, 7);
            boolean sawFrost = false, sawPopulate = false, sawResources = false, sawBiome = false;
            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    assertEquals("height at " + x + ',' + y,
                            Float.floatToRawIntBits(reference.getHeight(x, y)),
                            Float.floatToRawIntBits(batch.getHeight(x, y)));
                    assertEquals("terrain at " + x + ',' + y,
                            reference.getTerrain(x, y), batch.getTerrain(x, y));
                    assertEquals("Frost at " + x + ',' + y,
                            reference.getBitLayerValue(Frost.INSTANCE, x, y),
                            batch.getBitLayerValue(Frost.INSTANCE, x, y));
                    assertEquals("Populate at " + x + ',' + y,
                            reference.getBitLayerValue(Populate.INSTANCE, x, y),
                            batch.getBitLayerValue(Populate.INSTANCE, x, y));
                    assertEquals("Resources at " + x + ',' + y,
                            reference.getLayerValue(Resources.INSTANCE, x, y),
                            batch.getLayerValue(Resources.INSTANCE, x, y));
                    assertEquals("Annotations at " + x + ',' + y,
                            reference.getLayerValue(Annotations.INSTANCE, x, y),
                            batch.getLayerValue(Annotations.INSTANCE, x, y));
                    assertEquals("Biome at " + x + ',' + y,
                            reference.getLayerValue(Biome.INSTANCE, x, y),
                            batch.getLayerValue(Biome.INSTANCE, x, y));
                    sawFrost |= batch.getBitLayerValue(Frost.INSTANCE, x, y);
                    sawPopulate |= batch.getBitLayerValue(Populate.INSTANCE, x, y);
                    sawResources |= batch.getLayerValue(Resources.INSTANCE, x, y) != Resources.INSTANCE.getDefaultValue();
                    sawBiome |= batch.getLayerValue(Biome.INSTANCE, x, y) != Biome.INSTANCE.getDefaultValue();
                }
            }
            assertTrue("fixture should set a BIT layer", sawFrost);
            assertTrue("fixture should set a BIT_PER_CHUNK layer", sawPopulate);
            assertTrue("fixture should set a NIBBLE layer", sawResources);
            assertTrue("fixture should set a BYTE layer", sawBiome);
        } finally {
            restoreGenerationFlag(previousFlag);
        }
    }

    @Test
    public void tileBulkLayerInitializationPreservesBlockAndChunkBitSemantics() {
        final int area = Constants.TILE_SIZE * Constants.TILE_SIZE;
        final byte[] frostValues = new byte[area];
        final byte[] populateValues = new byte[area];
        final byte[] resourceValues = new byte[area];
        final byte[] biomeValues = new byte[area];
        java.util.Arrays.fill(resourceValues, (byte) Resources.INSTANCE.getDefaultValue());
        java.util.Arrays.fill(biomeValues, (byte) Biome.INSTANCE.getDefaultValue());
        final int setCell = 7 | (19 << Constants.TILE_SIZE_BITS);
        frostValues[setCell] = 1;
        populateValues[setCell] = 1;
        resourceValues[setCell] = 4;
        biomeValues[setCell] = 3;
        final Tile tile = new Tile(0, 0, 0, 256);
        tile.inhibitEvents();
        try {
            tile.initializeLayerValues(Frost.INSTANCE, frostValues);
            tile.initializeLayerValues(Populate.INSTANCE, populateValues);
            tile.initializeLayerValues(Resources.INSTANCE, resourceValues);
            tile.initializeLayerValues(Biome.INSTANCE, biomeValues);
        } finally {
            tile.releaseEvents();
        }
        assertTrue(tile.getBitLayerValue(Frost.INSTANCE, 7, 19));
        assertTrue(tile.getBitLayerValue(Populate.INSTANCE, 7, 19));
        assertTrue("BIT_PER_CHUNK expands to the rest of the 16x16 chunk",
                tile.getBitLayerValue(Populate.INSTANCE, 1, 17));
        assertTrue("another cell in the same 16x16 chunk is also set",
                tile.getBitLayerValue(Populate.INSTANCE, 15, 31));
        assertTrue("a different chunk remains clear", !tile.getBitLayerValue(Populate.INSTANCE, 16, 16));
        assertTrue("BIT remains per-cell", !tile.getBitLayerValue(Frost.INSTANCE, 1, 17));
        assertEquals(4, tile.getLayerValue(Resources.INSTANCE, 7, 19));
        assertEquals(Resources.INSTANCE.getDefaultValue(), tile.getLayerValue(Resources.INSTANCE, 6, 19));
        assertEquals(3, tile.getLayerValue(Biome.INSTANCE, 7, 19));
        assertEquals(Biome.INSTANCE.getDefaultValue(), tile.getLayerValue(Biome.INSTANCE, 6, 19));
    }

    @Test
    public void benchmarkDeterministicThemeLayerBatchWhenRequested() throws Exception {
        assumeTrue(Boolean.getBoolean("welt.simpletheme.bulk-layers.benchmark"));
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            final HeightMap baselineHeightMap = createFrostExerciseHeightMap();
            final SimpleTheme baselineTheme = createDeterministicLayerTheme(false);
            baselineHeightMap.setSeed(73L);
            baselineTheme.setSeed(73L);
            final HeightMapTileFactory batchFactory = new HeightMapTileFactory(73L,
                    createFrostExerciseHeightMap(), 0, 256, false, createDeterministicLayerTheme(false));
            final int tileCount = 24, rounds = 7;
            final double[] legacyMillis = new double[rounds];
            final double[] batchMillis = new double[rounds];
            for (int warmup = 0; warmup < 3; warmup++) {
                benchmarkPerCellFreshTiles(baselineHeightMap, baselineTheme, tileCount, warmup);
                benchmarkTiles(batchFactory, tileCount, warmup, false);
            }
            for (int round = 0; round < rounds; round++) {
                if ((round & 1) == 0) {
                    legacyMillis[round] = benchmarkPerCellFreshTiles(baselineHeightMap, baselineTheme,
                            tileCount, round);
                    batchMillis[round] = benchmarkTiles(batchFactory, tileCount, round, false);
                } else {
                    batchMillis[round] = benchmarkTiles(batchFactory, tileCount, round, false);
                    legacyMillis[round] = benchmarkPerCellFreshTiles(baselineHeightMap, baselineTheme,
                            tileCount, round);
                }
            }
            java.util.Arrays.sort(legacyMillis);
            java.util.Arrays.sort(batchMillis);
            final BenchmarkMemorySupport.Snapshot legacyMemory = BenchmarkMemorySupport.measure(
                    () -> benchmarkPerCellFreshTiles(baselineHeightMap, baselineTheme, tileCount, rounds));
            final BenchmarkMemorySupport.Snapshot batchMemory = BenchmarkMemorySupport.measure(
                    () -> benchmarkTiles(batchFactory, tileCount, rounds, false));
            final int median = rounds / 2;
            System.out.printf("SimpleTheme deterministic layers Java per-cell %.3f ms/tile, batched %.3f ms/tile, "
                            + "speedup %.3fx, per-cell_memory=[%s], batched_memory=[%s]%n",
                    legacyMillis[median], batchMillis[median], legacyMillis[median] / batchMillis[median],
                    legacyMemory, batchMemory);
        } finally {
            restoreGenerationFlag(previousFlag);
        }
    }

    @Test
    public void nativeSingleNoiseTreeMatchesJavaFreshTile() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            final HeightMap javaMap = new SumHeightMap(new ConstantHeightMap(42.25),
                    new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L));
            final HeightMap nativeMap = new SumHeightMap(new ConstantHeightMap(42.25),
                    new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L));
            final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L, javaMap,
                    0, 256, false, createSimpleTheme(true));
            final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L, nativeMap,
                    0, 256, false, createSimpleTheme(false));
            Native.setGenEnabled(false);
            final Tile javaTile = javaFactory.createTile(-3, 7);
            Native.setGenEnabled(true);
            final Tile nativeTile = nativeFactory.createTile(-3, 7);
            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    assertEquals("height at " + x + ',' + y,
                            Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                            Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                    assertEquals("water at " + x + ',' + y,
                            javaTile.getWaterLevel(x, y), nativeTile.getWaterLevel(x, y));
                    assertEquals("terrain at " + x + ',' + y,
                            javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
                }
            }
        } finally {
            restoreGenerationFlag(previousFlag);
        }
    }

    @Test
    public void nativeSingleNoiseTreeWithFrostMatchesJavaFreshTile() throws Exception {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            final HeightMap javaMap = createFrostExerciseHeightMap();
            final HeightMap nativeMap = createFrostExerciseHeightMap();
            final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L, javaMap,
                    0, 256, false, createNoisySimpleTheme(true));
            final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L, nativeMap,
                    0, 256, false, createNoisySimpleTheme(false));
            resetSimpleThemeRandom(0x53494d504c45L);
            Native.setGenEnabled(false);
            final Tile javaTile = javaFactory.createTile(-3, 7);
            resetSimpleThemeRandom(0x53494d504c45L);
            Native.setGenEnabled(true);
            final Tile nativeTile = nativeFactory.createTile(-3, 7);
            int frostCells = 0;
            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    assertEquals("height at " + x + ',' + y,
                            Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                            Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                    assertEquals("water at " + x + ',' + y,
                            javaTile.getWaterLevel(x, y), nativeTile.getWaterLevel(x, y));
                    assertEquals("terrain at " + x + ',' + y,
                            javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
                    final boolean frost = javaTile.getBitLayerValue(Frost.INSTANCE, x, y);
                    assertEquals("Frost at " + x + ',' + y, frost,
                            nativeTile.getBitLayerValue(Frost.INSTANCE, x, y));
                    if (frost) {
                        frostCells++;
                    }
                }
            }
            assertTrue("the fixture must exercise non-empty Frost data", frostCells > 0);
        } finally {
            restoreGenerationFlag(previousFlag);
        }
    }

    @Test
    public void nativeProductAndDifferenceHeightMapsMatchJavaFreshTile() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            final HeightMap javaMap = new DifferenceHeightMap(
                    new ProductHeightMap(
                            new SumHeightMap(new ConstantHeightMap(20.1),
                                    new NoiseHeightMap(38.0, 0.8, 2, 0x1234_5678L)),
                            new NoiseHeightMap(0.35, 2.1, 5, -0x2468_1357L)),
                    new ConstantHeightMap(1.75));
            final HeightMap nativeMap = new DifferenceHeightMap(
                    new ProductHeightMap(
                            new SumHeightMap(new ConstantHeightMap(20.1),
                                    new NoiseHeightMap(38.0, 0.8, 2, 0x1234_5678L)),
                            new NoiseHeightMap(0.35, 2.1, 5, -0x2468_1357L)),
                    new ConstantHeightMap(1.75));
            final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L, javaMap,
                    0, 256, false, createSimpleTheme(false));
            final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L, nativeMap,
                    0, 256, false, createSimpleTheme(false));
            Native.setGenEnabled(false);
            final Tile javaTile = javaFactory.createTile(-2, 5);
            Native.setGenEnabled(true);
            final Tile nativeTile = nativeFactory.createTile(-2, 5);
            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    assertEquals("height at " + x + ',' + y,
                            Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                            Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                    assertEquals("water at " + x + ',' + y,
                            javaTile.getWaterLevel(x, y), nativeTile.getWaterLevel(x, y));
                    assertEquals("terrain at " + x + ',' + y,
                            javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
                }
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
        }
    }

    @Test
    public void nativeMinimumAndMaximumHeightMapsMatchJavaFreshTile() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            final HeightMap javaMap = new MaximisingHeightMap(
                    new MinimisingHeightMap(
                            new SumHeightMap(new ConstantHeightMap(48.0),
                                    new NoiseHeightMap(38.0, 0.8, 2, 0x1234_5678L)),
                            new NoiseHeightMap(22.0, 2.1, 5, -0x2468_1357L)),
                    new ConstantHeightMap(54.0));
            final HeightMap nativeMap = new MaximisingHeightMap(
                    new MinimisingHeightMap(
                            new SumHeightMap(new ConstantHeightMap(48.0),
                                    new NoiseHeightMap(38.0, 0.8, 2, 0x1234_5678L)),
                            new NoiseHeightMap(22.0, 2.1, 5, -0x2468_1357L)),
                    new ConstantHeightMap(54.0));
            final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L, javaMap,
                    0, 256, false, createSimpleTheme(false));
            final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L, nativeMap,
                    0, 256, false, createSimpleTheme(false));
            Native.setGenEnabled(false);
            final Tile javaTile = javaFactory.createTile(-2, 5);
            Native.setGenEnabled(true);
            final Tile nativeTile = nativeFactory.createTile(-2, 5);
            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    assertEquals("height at " + x + ',' + y,
                            Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                            Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                    assertEquals("water at " + x + ',' + y,
                            javaTile.getWaterLevel(x, y), nativeTile.getWaterLevel(x, y));
                    assertEquals("terrain at " + x + ',' + y,
                            javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
                }
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
        }
    }

    @Test
    public void nativeMandelbrotHeightMapMatchesJavaFreshTile() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            final HeightMap javaMap = new SumHeightMap(new ConstantHeightMap(58.0), new MandelbrotHeightMap());
            final HeightMap nativeMap = new SumHeightMap(new ConstantHeightMap(58.0), new MandelbrotHeightMap());
            final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L, javaMap,
                    0, 256, false, createSimpleTheme(false));
            final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L, nativeMap,
                    0, 256, false, createSimpleTheme(false));
            Native.setGenEnabled(false);
            final Tile javaTile = javaFactory.createTile(0, 0);
            Native.setGenEnabled(true);
            final Tile nativeTile = nativeFactory.createTile(0, 0);
            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    assertEquals("height at " + x + ',' + y,
                            Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                            Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                    assertEquals("water at " + x + ',' + y,
                            javaTile.getWaterLevel(x, y), nativeTile.getWaterLevel(x, y));
                    assertEquals("terrain at " + x + ',' + y,
                            javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
                }
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
        }
    }

    @Test
    public void nativeBandedHeightMapsMatchJavaFreshTile() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            for (final boolean smooth : new boolean[] {false, true}) {
                for (final int[] tile : new int[][] {{-2, 5}, {1000, -1000}}) {
                    final HeightMap javaMap = new BandedHeightMap("banded", 9, 116.5, 7, 88.25, smooth);
                    final HeightMap nativeMap = new BandedHeightMap("banded", 9, 116.5, 7, 88.25, smooth);
                    final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L, javaMap,
                            0, 256, false, createSimpleTheme(true));
                    final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L, nativeMap,
                            0, 256, false, createSimpleTheme(false));
                    Native.setGenEnabled(false);
                    final Tile javaTile = javaFactory.createTile(tile[0], tile[1]);
                    Native.setGenEnabled(true);
                    final Tile nativeTile = nativeFactory.createTile(tile[0], tile[1]);
                    for (int x = 0; x < Constants.TILE_SIZE; x++) {
                        for (int y = 0; y < Constants.TILE_SIZE; y++) {
                            assertEquals("height at " + x + ',' + y + " smooth=" + smooth,
                                    Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                                    Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                            assertEquals("terrain at " + x + ',' + y + " smooth=" + smooth,
                                    javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
                        }
                    }
                }
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
        }
    }

    @Test
    public void nativeNinePatchHeightMapMatchesJavaFreshTile() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        final String previousNinePatchFlag = System.getProperty(Native.NINE_PATCH_GEN_KEY);
        try {
            final int[][] parameters = {{0, 8, 12}, {7, 0, 10}, {9, 6, 0}, {16, 11, 13}};
            for (final int[] parameter : parameters) {
                for (final boolean combined : new boolean[] {false, true}) {
                    for (final int[] tile : new int[][] {{0, 0}, {-1, 0}, {0, 1}, {-2, -2}, {1000, -1000}}) {
                        final HeightMap javaBase = new NinePatchHeightMap(
                                parameter[0], parameter[1], parameter[2], 117.25);
                        final HeightMap nativeBase = new NinePatchHeightMap(
                                parameter[0], parameter[1], parameter[2], 117.25);
                        final HeightMap javaMap = combined
                                ? new SumHeightMap(new ConstantHeightMap(3.25), javaBase) : javaBase;
                        final HeightMap nativeMap = combined
                                ? new SumHeightMap(new ConstantHeightMap(3.25), nativeBase) : nativeBase;
                        final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L, javaMap,
                                0, 256, false, createSimpleTheme(true));
                        final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L, nativeMap,
                                0, 256, false, createSimpleTheme(false));
                        Native.setGenEnabled(false);
                        Native.setNinePatchGenEnabled(false);
                        final Tile javaTile = javaFactory.createTile(tile[0], tile[1]);
                        Native.setGenEnabled(true);
                        Native.setNinePatchGenEnabled(true);
                        final Tile nativeTile = nativeFactory.createTile(tile[0], tile[1]);
                        for (int x = 0; x < Constants.TILE_SIZE; x++) {
                            for (int y = 0; y < Constants.TILE_SIZE; y++) {
                                assertEquals("height at " + x + ',' + y + " parameters="
                                                + java.util.Arrays.toString(parameter)
                                                + " combined=" + combined,
                                        Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                                        Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                                assertEquals("terrain at " + x + ',' + y,
                                        javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
                            }
                        }
                    }
                }
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
            if (previousNinePatchFlag == null) {
                System.clearProperty(Native.NINE_PATCH_GEN_KEY);
            } else {
                System.setProperty(Native.NINE_PATCH_GEN_KEY, previousNinePatchFlag);
            }
        }
    }

    @Test
    public void nativeSlopeHeightMapMatchesJavaFreshTile() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            for (final float scale : new float[] {1.0f, 2.5f}) {
                for (final int[] tile : new int[][] {{-2, 5}, {0, 0}, {1000, -1000}}) {
                    final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L,
                            new SlopeHeightMap(new SumHeightMap(new ConstantHeightMap(42.25),
                                    new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L)), scale),
                            0, 256, false, createSimpleTheme(false));
                    final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L,
                            new SlopeHeightMap(new SumHeightMap(new ConstantHeightMap(42.25),
                                    new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L)), scale),
                            0, 256, false, createSimpleTheme(false));
                    Native.setGenEnabled(false);
                    final Tile javaTile = javaFactory.createTile(tile[0], tile[1]);
                    Native.setGenEnabled(true);
                    final Tile nativeTile = nativeFactory.createTile(tile[0], tile[1]);
                    for (int x = 0; x < Constants.TILE_SIZE; x++) {
                        for (int y = 0; y < Constants.TILE_SIZE; y++) {
                            assertEquals("slope height at " + x + ',' + y + " scale=" + scale,
                                    Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                                    Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                            assertEquals("terrain at " + x + ',' + y,
                                    javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
                        }
                    }
                }
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
        }
    }

    @Test
    public void nativeDisplacementHeightMapMatchesJavaFreshTile() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            for (final int[] tile : new int[][] {{-2, 5}, {0, 0}, {1000, -1000}, {131073, 0}}) {
                final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L,
                        new DisplacementHeightMap(
                                new SumHeightMap(new ConstantHeightMap(42.25),
                                        new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L)),
                                new NoiseHeightMap(6.0, 0.75, 2, 0x1234_5678L),
                                new NoiseHeightMap(8.0, 1.25, 2, -0x2468_1357L)),
                        0, 256, false, createSimpleTheme(false));
                final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L,
                        new DisplacementHeightMap(
                                new SumHeightMap(new ConstantHeightMap(42.25),
                                        new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L)),
                                new NoiseHeightMap(6.0, 0.75, 2, 0x1234_5678L),
                                new NoiseHeightMap(8.0, 1.25, 2, -0x2468_1357L)),
                        0, 256, false, createSimpleTheme(false));
                Native.setGenEnabled(false);
                final Tile javaTile = javaFactory.createTile(tile[0], tile[1]);
                Native.setGenEnabled(true);
                final Tile nativeTile = nativeFactory.createTile(tile[0], tile[1]);
                for (int x = 0; x < Constants.TILE_SIZE; x++) {
                    for (int y = 0; y < Constants.TILE_SIZE; y++) {
                        assertEquals("displacement height at " + x + ',' + y,
                                Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                                Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                        assertEquals("terrain at " + x + ',' + y,
                                javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
                    }
                }
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
        }
    }

    @Test
    public void nativeTranslatedHeightMapMatchesJavaFreshTile() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            for (final int[] tile : new int[][] {{-2, 5}, {1000, -1000}}) {
                final HeightMap javaMap = new TransformingHeightMap("translation",
                        new SumHeightMap(new ConstantHeightMap(42.25),
                                new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L)),
                        1.0f, 1.0f, -13, 29, 0.0f);
                final HeightMap nativeMap = new TransformingHeightMap("translation",
                        new SumHeightMap(new ConstantHeightMap(42.25),
                                new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L)),
                        1.0f, 1.0f, -13, 29, 0.0f);
                final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L, javaMap,
                        0, 256, false, createSimpleTheme(true));
                final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L, nativeMap,
                        0, 256, false, createSimpleTheme(false));
                Native.setGenEnabled(false);
                final Tile javaTile = javaFactory.createTile(tile[0], tile[1]);
                Native.setGenEnabled(true);
                final Tile nativeTile = nativeFactory.createTile(tile[0], tile[1]);
                for (int x = 0; x < Constants.TILE_SIZE; x++) {
                    for (int y = 0; y < Constants.TILE_SIZE; y++) {
                        assertEquals("height at " + x + ',' + y,
                                Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                                Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                        assertEquals("terrain at " + x + ',' + y,
                                javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
                    }
                }
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
        }
    }

    @Test
    public void nativeShelvingHeightMapMatchesJavaFreshTile() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            for (final int[] tile : new int[][] {
                    {-2, 5}, {1000, -1000}, {131071, -131072}, {131073, -131073}}) {
                final ShelvingHeightMap javaMap = new ShelvingHeightMap(
                        new SumHeightMap(new ConstantHeightMap(42.25),
                                new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L)));
                final ShelvingHeightMap nativeMap = new ShelvingHeightMap(
                        new SumHeightMap(new ConstantHeightMap(42.25),
                                new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L)));
                javaMap.setShelveHeight(37);
                javaMap.setShelveStrength(11);
                nativeMap.setShelveHeight(37);
                nativeMap.setShelveStrength(11);
                final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L, javaMap,
                        0, 256, false, createSimpleTheme(true));
                final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L, nativeMap,
                        0, 256, false, createSimpleTheme(false));
                Native.setGenEnabled(false);
                final Tile javaTile = javaFactory.createTile(tile[0], tile[1]);
                Native.setGenEnabled(true);
                final Tile nativeTile = nativeFactory.createTile(tile[0], tile[1]);
                for (int x = 0; x < Constants.TILE_SIZE; x++) {
                    for (int y = 0; y < Constants.TILE_SIZE; y++) {
                        assertEquals("height at " + x + ',' + y,
                                Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                                Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                        assertEquals("terrain at " + x + ',' + y,
                                javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
                    }
                }
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
        }
    }

    @Test
    public void javaHeightMapSimpleThemeBatchMatchesPerCellFallback() {
        final HeightMap heightMap = new SumHeightMap(new ConstantHeightMap(30), new ConstantHeightMap(32));
        final HeightMapTileFactory referenceFactory = new HeightMapTileFactory(42L, heightMap,
                0, 256, false, createSimpleTheme(true));
        final HeightMapTileFactory batchFactory = new HeightMapTileFactory(42L,
                new SumHeightMap(new ConstantHeightMap(30), new ConstantHeightMap(32)),
                0, 256, false, createSimpleTheme(false));
        final Tile reference = referenceFactory.createTile(-3, 7);
        final Tile batch = batchFactory.createTile(-3, 7);
        for (int x = 0; x < Constants.TILE_SIZE; x++) {
            for (int y = 0; y < Constants.TILE_SIZE; y++) {
                assertEquals("height at " + x + ',' + y,
                        Float.floatToRawIntBits(reference.getHeight(x, y)),
                        Float.floatToRawIntBits(batch.getHeight(x, y)));
                assertEquals("water at " + x + ',' + y,
                        reference.getWaterLevel(x, y), batch.getWaterLevel(x, y));
                assertEquals("terrain at " + x + ',' + y,
                        reference.getTerrain(x, y), batch.getTerrain(x, y));
            }
        }
    }

    @Test
    public void rowMajorTerrainBatchPreservesBitLayerRandomSequence() throws Exception {
        final Map<Filter, Layer> layers = new java.util.LinkedHashMap<>();
        layers.put(new HeightFilter(0, 256, 70, 180, true), FloodWithLava.INSTANCE);
        layers.put(new HeightFilter(0, 256, 55, 200, false), Resources.INSTANCE);
        layers.put(new HeightFilter(0, 256, 80, 170, false), Biome.INSTANCE);
        final SortedMap<Integer, Terrain> ranges = new TreeMap<>();
        ranges.put(-1, Terrain.GRASS);
        ranges.put(90, Terrain.STONE_MIX);
        final SimpleTheme legacyTheme = new SimpleTheme(0L, 62, ranges, layers, 0, 256, true, true) { };
        final SimpleTheme batchTheme = new SimpleTheme(0L, 62, ranges, layers, 0, 256, true, true);
        legacyTheme.setDiscreteValues(java.util.Collections.singletonMap(Biome.INSTANCE, 4));
        batchTheme.setDiscreteValues(java.util.Collections.singletonMap(Biome.INSTANCE, 4));
        final HeightMapTileFactory legacyFactory = new HeightMapTileFactory(42L,
                new SumHeightMap(new ConstantHeightMap(75), new NoiseHeightMap(18, 0.8, 3, 0x1234_5678L)),
                0, 256, false, legacyTheme);
        final HeightMapTileFactory batchFactory = new HeightMapTileFactory(42L,
                new SumHeightMap(new ConstantHeightMap(75), new NoiseHeightMap(18, 0.8, 3, 0x1234_5678L)),
                0, 256, false, batchTheme);
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            Native.setGenEnabled(false);
            resetSimpleThemeRandom(0x57454c54L);
            final Tile legacy = legacyFactory.createTile(-3, 7);
            resetSimpleThemeRandom(0x57454c54L);
            final Tile batch = batchFactory.createTile(-3, 7);
            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    assertEquals("height at " + x + ',' + y,
                            Float.floatToRawIntBits(legacy.getHeight(x, y)),
                            Float.floatToRawIntBits(batch.getHeight(x, y)));
                    assertEquals("water at " + x + ',' + y,
                            legacy.getWaterLevel(x, y), batch.getWaterLevel(x, y));
                    assertEquals("terrain at " + x + ',' + y,
                            legacy.getTerrain(x, y), batch.getTerrain(x, y));
                    assertEquals("random BIT layer at " + x + ',' + y,
                            legacy.getBitLayerValue(FloodWithLava.INSTANCE, x, y),
                            batch.getBitLayerValue(FloodWithLava.INSTANCE, x, y));
                    assertEquals("Resources at " + x + ',' + y,
                            legacy.getLayerValue(Resources.INSTANCE, x, y),
                            batch.getLayerValue(Resources.INSTANCE, x, y));
                    assertEquals("Biome at " + x + ',' + y,
                            legacy.getLayerValue(Biome.INSTANCE, x, y),
                            batch.getLayerValue(Biome.INSTANCE, x, y));
                }
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
        }
    }

    @Test
    public void bitmapHeightMapBulkRasterMatchesPerCellSampling() {
        for (final int[] imageTypeAndChannel : new int[][] {
                {BufferedImage.TYPE_BYTE_GRAY, 0},
                {BufferedImage.TYPE_USHORT_GRAY, 0},
                {BufferedImage.TYPE_3BYTE_BGR, 2}}) {
            for (final int[] dimensions : new int[][] {{260, 259}, {64, 73}}) {
                final BufferedImage image = new BufferedImage(dimensions[0], dimensions[1], imageTypeAndChannel[0]);
                for (int x = 0; x < image.getWidth(); x++) {
                    for (int y = 0; y < image.getHeight(); y++) {
                        final int red = (x * 17 + y * 3) & 0xff;
                        final int green = (x * 5 + y * 11) & 0xff;
                        final int blue = (x * 7 + y * 13) & 0xff;
                        image.setRGB(x, y, 0xff000000 | (red << 16) | (green << 8) | blue);
                    }
                }
                for (final boolean repeat : new boolean[] {false, true}) {
                    for (final boolean bicubic : new boolean[] {false, true}) {
                        final BitmapHeightMap legacyBitmap = BitmapHeightMap.build()
                                .withImage(image).withChannel(imageTypeAndChannel[1]).withRepeat(repeat).now();
                        final BitmapHeightMap batchBitmap = BitmapHeightMap.build()
                                .withImage(image).withChannel(imageTypeAndChannel[1]).withRepeat(repeat).now();
                        final HeightMap legacyMap = bicubic ? new BicubicHeightMap(legacyBitmap) : legacyBitmap;
                        final HeightMap batchMap = bicubic ? new BicubicHeightMap(batchBitmap) : batchBitmap;
                        final HeightMapTileFactory legacyFactory = new HeightMapTileFactory(42L, legacyMap,
                                0, 256, false, createSimpleTheme(true));
                        final HeightMapTileFactory batchFactory = new HeightMapTileFactory(42L, batchMap,
                                0, 256, false, createSimpleTheme(false));
                        for (final int[] tile : new int[][] {
                                {0, 0}, {1, 1}, {2, 2}, {-1, 0}, {0, -1}, {-1, -1}}) {
                            final Tile legacy = legacyFactory.createTile(tile[0], tile[1]);
                            final Tile batch = batchFactory.createTile(tile[0], tile[1]);
                            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                                    assertEquals("height for image type " + imageTypeAndChannel[0]
                                                    + " repeat=" + repeat + " bicubic=" + bicubic
                                                    + " at " + x + ',' + y,
                                            Float.floatToRawIntBits(legacy.getHeight(x, y)),
                                            Float.floatToRawIntBits(batch.getHeight(x, y)));
                                    assertEquals("water at " + x + ',' + y,
                                            legacy.getWaterLevel(x, y), batch.getWaterLevel(x, y));
                                    assertEquals("terrain at " + x + ',' + y,
                                            legacy.getTerrain(x, y), batch.getTerrain(x, y));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void batchedLavaFloodCandidateMatchesPerCellFactory() throws Exception {
        final HeightMapTileFactory legacyFactory = new HeightMapTileFactory(42L,
                createFrostExerciseHeightMap(), 0, 256, true, createNoisyMixedSimpleTheme(true));
        final HeightMapTileFactory batchedFactory = new HeightMapTileFactory(42L,
                createFrostExerciseHeightMap(), 0, 256, true, createNoisyMixedSimpleTheme(false));
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            Native.setGenEnabled(false);
            resetSimpleThemeRandom(0x57454c54L);
            final Tile legacy = legacyFactory.createTile(-3, 7);
            resetSimpleThemeRandom(0x57454c54L);
            final Tile batched = batchedFactory.createTile(-3, 7);
            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    assertEquals("height at " + x + ',' + y,
                            Float.floatToRawIntBits(legacy.getHeight(x, y)),
                            Float.floatToRawIntBits(batched.getHeight(x, y)));
                    assertEquals("water at " + x + ',' + y,
                            legacy.getWaterLevel(x, y), batched.getWaterLevel(x, y));
                    assertEquals("terrain at " + x + ',' + y,
                            legacy.getTerrain(x, y), batched.getTerrain(x, y));
                    assertEquals("lava at " + x + ',' + y,
                            legacy.getBitLayerValue(FloodWithLava.INSTANCE, x, y),
                            batched.getBitLayerValue(FloodWithLava.INSTANCE, x, y));
                    assertEquals("Resources at " + x + ',' + y,
                            legacy.getLayerValue(Resources.INSTANCE, x, y),
                            batched.getLayerValue(Resources.INSTANCE, x, y));
                    assertEquals("Biome at " + x + ',' + y,
                            legacy.getLayerValue(Biome.INSTANCE, x, y),
                            batched.getLayerValue(Biome.INSTANCE, x, y));
                }
            }
        } finally {
            restoreGenerationFlag(previousFlag);
        }
    }

    @Test
    public void lavaFloodBatchPathMatchesWhenThemeDoesNotOverrideLavaLayer() throws Exception {
        final HeightMapTileFactory legacyFactory = new HeightMapTileFactory(42L,
                createFrostExerciseHeightMap(), 0, 256, true, createNoisySimpleTheme(true));
        final HeightMapTileFactory batchedFactory = new HeightMapTileFactory(42L,
                createFrostExerciseHeightMap(), 0, 256, true, createNoisySimpleTheme(false));
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            Native.setGenEnabled(false);
            resetSimpleThemeRandom(0x57454c54L);
            final Tile legacy = legacyFactory.createTile(-3, 7);
            resetSimpleThemeRandom(0x57454c54L);
            final Tile batched = batchedFactory.createTile(-3, 7);
            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    assertEquals("height at " + x + ',' + y,
                            Float.floatToRawIntBits(legacy.getHeight(x, y)),
                            Float.floatToRawIntBits(batched.getHeight(x, y)));
                    assertEquals("water at " + x + ',' + y,
                            legacy.getWaterLevel(x, y), batched.getWaterLevel(x, y));
                    assertEquals("terrain at " + x + ',' + y,
                            legacy.getTerrain(x, y), batched.getTerrain(x, y));
                    assertEquals("lava at " + x + ',' + y,
                            legacy.getBitLayerValue(FloodWithLava.INSTANCE, x, y),
                            batched.getBitLayerValue(FloodWithLava.INSTANCE, x, y));
                    assertEquals("Frost at " + x + ',' + y,
                            legacy.getBitLayerValue(Frost.INSTANCE, x, y),
                            batched.getBitLayerValue(Frost.INSTANCE, x, y));
                }
            }
        } finally {
            restoreGenerationFlag(previousFlag);
        }
    }

    @Test
    public void nativeScaledAndRotatedHeightMapMatchesJavaFreshTile() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            for (final int[] tile : new int[][] {{-2, 5}, {0, 0}, {131073, -131074}}) {
                final HeightMap javaMap = new TransformingHeightMap("scaled and rotated",
                        new SumHeightMap(new ConstantHeightMap(42.25),
                                new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L)),
                        1.35f, 0.72f, -13, 29, 0.37f);
                final HeightMap nativeMap = new TransformingHeightMap("scaled and rotated",
                        new SumHeightMap(new ConstantHeightMap(42.25),
                                new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L)),
                        1.35f, 0.72f, -13, 29, 0.37f);
                final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L, javaMap,
                        0, 256, false, createSimpleTheme(true));
                final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L, nativeMap,
                        0, 256, false, createSimpleTheme(false));
                Native.setGenEnabled(false);
                final Tile javaTile = javaFactory.createTile(tile[0], tile[1]);
                Native.setGenEnabled(true);
                final Tile nativeTile = nativeFactory.createTile(tile[0], tile[1]);
                for (int x = 0; x < Constants.TILE_SIZE; x++) {
                    for (int y = 0; y < Constants.TILE_SIZE; y++) {
                        assertEquals("transformed height at " + x + ',' + y + " tile=" + tile[0] + ',' + tile[1],
                                Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                                Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                        assertEquals("water at " + x + ',' + y,
                                javaTile.getWaterLevel(x, y), nativeTile.getWaterLevel(x, y));
                        assertEquals("terrain at " + x + ',' + y,
                                javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
                    }
                }
            }
        } finally {
            restoreGenerationFlag(previousFlag);
        }
    }

    @Test
    public void benchmarkNativeScaledAndRotatedHeightMapWhenRequested() throws Exception {
        assumeTrue(Boolean.getBoolean("welt.transforming.benchmark"));
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L,
                    new TransformingHeightMap("scaled and rotated",
                            new SumHeightMap(new ConstantHeightMap(42.25),
                                    new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L)),
                            1.35f, 0.72f, -13, 29, 0.37f),
                    0, 256, false, createSimpleTheme(true));
            final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L,
                    new TransformingHeightMap("scaled and rotated",
                            new SumHeightMap(new ConstantHeightMap(42.25),
                                    new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L)),
                            1.35f, 0.72f, -13, 29, 0.37f),
                    0, 256, false, createSimpleTheme(false));
            final int tileCount = 24, rounds = 7;
            final double[] javaMillis = new double[rounds], nativeMillis = new double[rounds];
            for (int warmup = 0; warmup < 4; warmup++) {
                benchmarkTiles(javaFactory, tileCount, warmup, false);
                benchmarkTiles(nativeFactory, tileCount, warmup, true);
            }
            for (int round = 0; round < rounds; round++) {
                if ((round & 1) == 0) {
                    javaMillis[round] = benchmarkTiles(javaFactory, tileCount, round, false);
                    nativeMillis[round] = benchmarkTiles(nativeFactory, tileCount, round, true);
                } else {
                    nativeMillis[round] = benchmarkTiles(nativeFactory, tileCount, round, true);
                    javaMillis[round] = benchmarkTiles(javaFactory, tileCount, round, false);
                }
            }
            java.util.Arrays.sort(javaMillis);
            java.util.Arrays.sort(nativeMillis);
            final BenchmarkMemorySupport.Snapshot javaMemory = BenchmarkMemorySupport.measure(
                    () -> benchmarkTiles(javaFactory, tileCount, rounds, false));
            final BenchmarkMemorySupport.Snapshot nativeMemory = BenchmarkMemorySupport.measure(
                    () -> benchmarkTiles(nativeFactory, tileCount, rounds, true));
            System.out.printf("Transformed heightmap Java %.3f ms/tile, Rust/JNI %.3f ms/tile, ratio %.3fx "
                            + "java_memory=[%s] native_memory=[%s]%n",
                    javaMillis[rounds / 2], nativeMillis[rounds / 2],
                    javaMillis[rounds / 2] / nativeMillis[rounds / 2], javaMemory, nativeMemory);
        } finally {
            restoreGenerationFlag(previousFlag);
        }
    }

    @Test
    public void benchmarkNativeSingleNoiseTreeWhenRequested() throws Exception {
        assumeTrue(Boolean.getBoolean("welt.noise.tree.benchmark"));
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            final HeightMap javaMap = new SumHeightMap(new ConstantHeightMap(42.25),
                    new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L));
            final HeightMap nativeMap = new SumHeightMap(new ConstantHeightMap(42.25),
                    new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L));
            final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L, javaMap,
                    0, 256, false, createSimpleTheme(true));
            final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L, nativeMap,
                    0, 256, false, createSimpleTheme(false));
            final int tileCount = 24, rounds = 7;
            final double[] javaMillis = new double[rounds], nativeMillis = new double[rounds];
            for (int warmup = 0; warmup < 4; warmup++) {
                benchmarkTiles(javaFactory, tileCount, warmup, false);
                benchmarkTiles(nativeFactory, tileCount, warmup, true);
            }
            for (int round = 0; round < rounds; round++) {
                if ((round & 1) == 0) {
                    javaMillis[round] = benchmarkTiles(javaFactory, tileCount, round, false);
                    nativeMillis[round] = benchmarkTiles(nativeFactory, tileCount, round, true);
                } else {
                    nativeMillis[round] = benchmarkTiles(nativeFactory, tileCount, round, true);
                    javaMillis[round] = benchmarkTiles(javaFactory, tileCount, round, false);
                }
            }
            java.util.Arrays.sort(javaMillis);
            java.util.Arrays.sort(nativeMillis);
            final BenchmarkMemorySupport.Snapshot javaMemory = BenchmarkMemorySupport.measure(
                    () -> benchmarkTiles(javaFactory, tileCount, rounds, false));
            final BenchmarkMemorySupport.Snapshot nativeMemory = BenchmarkMemorySupport.measure(
                    () -> benchmarkTiles(nativeFactory, tileCount, rounds, true));
            System.out.printf("Single-noise SimpleTheme Java %.3f ms/tile, Rust/JNI %.3f ms/tile, ratio %.3fx "
                            + "java_memory=[%s] native_memory=[%s]%n",
                    javaMillis[rounds / 2], nativeMillis[rounds / 2],
                    javaMillis[rounds / 2] / nativeMillis[rounds / 2], javaMemory, nativeMemory);
        } finally {
            restoreGenerationFlag(previousFlag);
        }
    }

    @Test
    public void benchmarkFreshTileThemeLayersWhenRequested() throws Exception {
        assumeTrue(Boolean.getBoolean("welt.simpletheme.layers.benchmark"));
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            final HeightMapTileFactory legacyFactory = new HeightMapTileFactory(73L,
                    createFrostExerciseHeightMap(),
                    0, 256, false, createNoisySimpleTheme(true));
            final HeightMapTileFactory batchedJavaFactory = new HeightMapTileFactory(73L,
                    createFrostExerciseHeightMap(),
                    0, 256, false, createNoisySimpleTheme(false));
            final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L,
                    createFrostExerciseHeightMap(),
                    0, 256, false, createNoisySimpleTheme(false));
            final int tileCount = 24, rounds = 7;
            final double[] legacyMillis = new double[rounds];
            final double[] batchedJavaMillis = new double[rounds];
            final double[] nativeMillis = new double[rounds];
            for (int warmup = 0; warmup < 3; warmup++) {
                benchmarkTiles(legacyFactory, tileCount, warmup, false);
                benchmarkTiles(batchedJavaFactory, tileCount, warmup, false);
                benchmarkTiles(nativeFactory, tileCount, warmup, true);
            }
            for (int round = 0; round < rounds; round++) {
                switch (round % 3) {
                    case 0:
                        legacyMillis[round] = benchmarkTiles(legacyFactory, tileCount, round, false);
                        batchedJavaMillis[round] = benchmarkTiles(batchedJavaFactory, tileCount, round, false);
                        nativeMillis[round] = benchmarkTiles(nativeFactory, tileCount, round, true);
                        break;
                    case 1:
                        batchedJavaMillis[round] = benchmarkTiles(batchedJavaFactory, tileCount, round, false);
                        nativeMillis[round] = benchmarkTiles(nativeFactory, tileCount, round, true);
                        legacyMillis[round] = benchmarkTiles(legacyFactory, tileCount, round, false);
                        break;
                    default:
                        nativeMillis[round] = benchmarkTiles(nativeFactory, tileCount, round, true);
                        legacyMillis[round] = benchmarkTiles(legacyFactory, tileCount, round, false);
                        batchedJavaMillis[round] = benchmarkTiles(batchedJavaFactory, tileCount, round, false);
                }
            }
            java.util.Arrays.sort(legacyMillis);
            java.util.Arrays.sort(batchedJavaMillis);
            java.util.Arrays.sort(nativeMillis);
            final BenchmarkMemorySupport.Snapshot legacyMemory = BenchmarkMemorySupport.measure(
                    () -> benchmarkTiles(legacyFactory, tileCount, rounds, false));
            final BenchmarkMemorySupport.Snapshot batchedJavaMemory = BenchmarkMemorySupport.measure(
                    () -> benchmarkTiles(batchedJavaFactory, tileCount, rounds, false));
            final BenchmarkMemorySupport.Snapshot nativeMemory = BenchmarkMemorySupport.measure(
                    () -> benchmarkTiles(nativeFactory, tileCount, rounds, true));
            final int median = rounds / 2;
            System.out.printf("Fresh tile with Frost layers: legacy Java %.3f ms/tile, batched Java %.3f, "
                            + "Rust height + Java layers %.3f; legacy_memory=[%s] batched_java_memory=[%s] "
                            + "native_memory=[%s]%n",
                    legacyMillis[median], batchedJavaMillis[median], nativeMillis[median],
                    legacyMemory, batchedJavaMemory, nativeMemory);
        } finally {
            restoreGenerationFlag(previousFlag);
        }
    }

    @Test
    public void benchmarkFreshTileMixedRandomLayersWhenRequested() throws Exception {
        assumeTrue(Boolean.getBoolean("welt.simpletheme.mixed.layers.benchmark"));
        final HeightMapTileFactory legacyFactory = new HeightMapTileFactory(73L,
                createFrostExerciseHeightMap(), 0, 256, false, createNoisyMixedSimpleTheme(true));
        final HeightMapTileFactory batchedFactory = new HeightMapTileFactory(73L,
                createFrostExerciseHeightMap(), 0, 256, false, createNoisyMixedSimpleTheme(false));
        final int tileCount = 24, rounds = 9;
        final double[] legacyMillis = new double[rounds];
        final double[] batchedMillis = new double[rounds];
        for (int warmup = 0; warmup < 3; warmup++) {
            benchmarkMixedTiles(legacyFactory, tileCount, warmup);
            benchmarkMixedTiles(batchedFactory, tileCount, warmup);
        }
        for (int round = 0; round < rounds; round++) {
            if ((round & 1) == 0) {
                legacyMillis[round] = benchmarkMixedTiles(legacyFactory, tileCount, round);
                batchedMillis[round] = benchmarkMixedTiles(batchedFactory, tileCount, round);
            } else {
                batchedMillis[round] = benchmarkMixedTiles(batchedFactory, tileCount, round);
                legacyMillis[round] = benchmarkMixedTiles(legacyFactory, tileCount, round);
            }
        }
        java.util.Arrays.sort(legacyMillis);
        java.util.Arrays.sort(batchedMillis);
        final BenchmarkMemorySupport.Snapshot legacyMemory = BenchmarkMemorySupport.measure(
                () -> benchmarkMixedTiles(legacyFactory, tileCount, rounds));
        final BenchmarkMemorySupport.Snapshot batchedMemory = BenchmarkMemorySupport.measure(
                () -> benchmarkMixedTiles(batchedFactory, tileCount, rounds));
        final int median = rounds / 2;
        System.out.printf("Fresh mixed random-bit SimpleTheme Java %.3f ms/tile, grouped %.3f, ratio %.3fx "
                        + "legacy_memory=[%s] grouped_memory=[%s]%n",
                legacyMillis[median], batchedMillis[median], legacyMillis[median] / batchedMillis[median],
                legacyMemory, batchedMemory);

        final HeightMap mixedHeightMap = createFrostExerciseHeightMap();
        final SimpleTheme mixedTheme = createNoisyMixedSimpleTheme(false);
        final double[] cellwiseLayerMillis = new double[rounds];
        final double[] groupedLayerMillis = new double[rounds];
        for (int warmup = 0; warmup < 3; warmup++) {
            benchmarkMixedLayerPath(mixedHeightMap, mixedTheme, tileCount, warmup, false);
            benchmarkMixedLayerPath(mixedHeightMap, mixedTheme, tileCount, warmup, true);
        }
        for (int round = 0; round < rounds; round++) {
            if ((round & 1) == 0) {
                cellwiseLayerMillis[round] = benchmarkMixedLayerPath(
                        mixedHeightMap, mixedTheme, tileCount, round, false);
                groupedLayerMillis[round] = benchmarkMixedLayerPath(
                        mixedHeightMap, mixedTheme, tileCount, round, true);
            } else {
                groupedLayerMillis[round] = benchmarkMixedLayerPath(
                        mixedHeightMap, mixedTheme, tileCount, round, true);
                cellwiseLayerMillis[round] = benchmarkMixedLayerPath(
                        mixedHeightMap, mixedTheme, tileCount, round, false);
            }
        }
        java.util.Arrays.sort(cellwiseLayerMillis);
        java.util.Arrays.sort(groupedLayerMillis);
        final BenchmarkMemorySupport.Snapshot cellwiseLayerMemory = BenchmarkMemorySupport.measure(
                () -> benchmarkMixedLayerPath(mixedHeightMap, mixedTheme, tileCount, rounds, false));
        final BenchmarkMemorySupport.Snapshot groupedLayerMemory = BenchmarkMemorySupport.measure(
                () -> benchmarkMixedLayerPath(mixedHeightMap, mixedTheme, tileCount, rounds, true));
        System.out.printf("Fresh tile, same batched height/terrain path: random-layer fallback %.3f ms/tile, "
                        + "grouped deterministic layers %.3f, ratio %.3fx fallback_memory=[%s] grouped_memory=[%s]%n",
                cellwiseLayerMillis[median], groupedLayerMillis[median],
                cellwiseLayerMillis[median] / groupedLayerMillis[median],
                cellwiseLayerMemory, groupedLayerMemory);
    }

    @Test
    public void benchmarkLavaFloodBatchCandidateWhenRequested() throws Exception {
        assumeTrue(Boolean.getBoolean("welt.simpletheme.lava-flood.benchmark"));
        final HeightMapTileFactory legacyFactory = new HeightMapTileFactory(73L,
                createFrostExerciseHeightMap(), 0, 256, true, createNoisySimpleTheme(true));
        final HeightMapTileFactory batchedFactory = new HeightMapTileFactory(73L,
                createFrostExerciseHeightMap(), 0, 256, true, createNoisySimpleTheme(false));
        final int tileCount = 24, rounds = 9;
        final double[] legacyMillis = new double[rounds];
        final double[] batchedMillis = new double[rounds];
        for (int warmup = 0; warmup < 3; warmup++) {
            benchmarkFloodFactoryTiles(legacyFactory, tileCount, warmup);
            benchmarkFloodFactoryTiles(batchedFactory, tileCount, warmup);
        }
        for (int round = 0; round < rounds; round++) {
            if ((round & 1) == 0) {
                legacyMillis[round] = benchmarkFloodFactoryTiles(legacyFactory, tileCount, round);
                batchedMillis[round] = benchmarkFloodFactoryTiles(batchedFactory, tileCount, round);
            } else {
                batchedMillis[round] = benchmarkFloodFactoryTiles(batchedFactory, tileCount, round);
                legacyMillis[round] = benchmarkFloodFactoryTiles(legacyFactory, tileCount, round);
            }
        }
        java.util.Arrays.sort(legacyMillis);
        java.util.Arrays.sort(batchedMillis);
        final BenchmarkMemorySupport.Snapshot legacyMemory = BenchmarkMemorySupport.measure(
                () -> benchmarkFloodFactoryTiles(legacyFactory, tileCount, rounds));
        final BenchmarkMemorySupport.Snapshot batchedMemory = BenchmarkMemorySupport.measure(
                () -> benchmarkFloodFactoryTiles(batchedFactory, tileCount, rounds));
        final int median = rounds / 2;
        System.out.printf("Fresh flood-with-lava tiles: Java fallback %.3f ms/tile, batched candidate %.3f, "
                        + "ratio %.3fx fallback_memory=[%s] candidate_memory=[%s]%n",
                legacyMillis[median], batchedMillis[median], legacyMillis[median] / batchedMillis[median],
                legacyMemory, batchedMemory);
    }

    private static double benchmarkMixedTiles(HeightMapTileFactory factory, int tileCount, int round)
            throws Exception {
        resetSimpleThemeRandom(0x57454c54L);
        return benchmarkTiles(factory, tileCount, round, false);
    }

    private static double benchmarkFloodFactoryTiles(HeightMapTileFactory factory, int tileCount, int round)
            throws Exception {
        resetSimpleThemeRandom(0x57454c54L);
        if (factory.isFloodWithLava()) {
            return benchmarkTiles(factory, tileCount, round, false);
        }
        Native.setGenEnabled(false);
        final long start = System.nanoTime();
        int sink = 0;
        for (int tileIndex = 0; tileIndex < tileCount; tileIndex++) {
            final int tileX = Math.floorMod(tileIndex * 7 + round, 9) - 4;
            final int tileY = Math.floorMod(tileIndex * 13 + round * 3, 9) - 4;
            final Tile tile = factory.createTile(tileX, tileY);
            tile.setBitLayerValue(FloodWithLava.INSTANCE);
            sink ^= Float.floatToRawIntBits(tile.getHeight(tileIndex & 127, (tileIndex * 17) & 127));
        }
        benchmarkSink ^= sink;
        return (System.nanoTime() - start) / 1_000_000.0 / tileCount;
    }

    private static double benchmarkMixedLayerPath(HeightMap heightMap, SimpleTheme theme, int tileCount,
                                                  int round, boolean groupedLayers) throws Exception {
        resetSimpleThemeRandom(0x57454c54L);
        final int area = Constants.TILE_SIZE * Constants.TILE_SIZE;
        final float[] heights = new float[area];
        final int[] intHeights = new int[area];
        final byte[] terrainOrdinals = new byte[area];
        Native.setGenEnabled(false);
        final long start = System.nanoTime();
        int sink = 0;
        for (int tileIndex = 0; tileIndex < tileCount; tileIndex++) {
            final int tileX = Math.floorMod(tileIndex * 7 + round, 9) - 4;
            final int tileY = Math.floorMod(tileIndex * 13 + round * 3, 9) - 4;
            final int worldTileX = tileX << Constants.TILE_SIZE_BITS;
            final int worldTileY = tileY << Constants.TILE_SIZE_BITS;
            final Tile tile = new Tile(tileX, tileY, 0, 256);
            tile.inhibitEvents();
            try {
                for (int x = 0; x < Constants.TILE_SIZE; x++) {
                    for (int y = 0; y < Constants.TILE_SIZE; y++) {
                        final int index = x | (y << Constants.TILE_SIZE_BITS);
                        heights[index] = org.pepsoft.util.MathUtils.clamp(0,
                                (float) heightMap.getHeight(worldTileX + x, worldTileY + y), 255);
                    }
                }
                final int[] quantisedHeights = tile.initializeHeightAndWaterLevels(
                        heights, theme.getWaterHeight(), intHeights);
                int lowestHeight = Integer.MAX_VALUE;
                int highestHeight = Integer.MIN_VALUE;
                for (int height : quantisedHeights) {
                    lowestHeight = Math.min(lowestHeight, height);
                    highestHeight = Math.max(highestHeight, height);
                }
                for (int x = 0; x < Constants.TILE_SIZE; x++) {
                    for (int y = 0; y < Constants.TILE_SIZE; y++) {
                        final int index = x | (y << Constants.TILE_SIZE_BITS);
                        terrainOrdinals[index] = (byte) theme
                                .getTerrainForFreshTile(tile, x, y, quantisedHeights[index]).ordinal();
                    }
                }
                tile.initializeTerrainOrdinals(terrainOrdinals);
                if (groupedLayers) {
                    if (!theme.applyDeterministicLayersToFreshTile(tile, quantisedHeights,
                            lowestHeight, highestHeight, terrainOrdinals)) {
                        if (theme.applyDeterministicValueLayersToFreshTile(tile, quantisedHeights,
                                lowestHeight, highestHeight, terrainOrdinals)) {
                            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                                    theme.applyBitLayersToFreshTile(tile, x, y,
                                            quantisedHeights[x | (y << Constants.TILE_SIZE_BITS)]);
                                }
                            }
                        } else {
                            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                                    theme.applyLayersToFreshTile(tile, x, y,
                                            quantisedHeights[x | (y << Constants.TILE_SIZE_BITS)]);
                                }
                            }
                        }
                    }
                } else {
                    for (int x = 0; x < Constants.TILE_SIZE; x++) {
                        for (int y = 0; y < Constants.TILE_SIZE; y++) {
                            theme.applyLayersToFreshTile(tile, x, y,
                                    quantisedHeights[x | (y << Constants.TILE_SIZE_BITS)]);
                        }
                    }
                }
                sink ^= Float.floatToRawIntBits(tile.getHeight(tileIndex & 127, (tileIndex * 17) & 127));
                sink ^= tile.getLayerValue(Resources.INSTANCE, tileIndex & 127, (tileIndex * 17) & 127);
            } finally {
                tile.releaseEvents();
            }
        }
        benchmarkSink ^= sink;
        return (System.nanoTime() - start) / 1_000_000.0 / tileCount;
    }

    @Test
    public void benchmarkLinearBandedHeightMapWhenRequested() throws Exception {
        assumeTrue(Boolean.getBoolean("welt.banded.linear.benchmark"));
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L,
                    new BandedHeightMap("linear banded", 9, 116.5, 7, 88.25, false),
                    0, 256, false, createSimpleTheme(false));
            final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L,
                    new BandedHeightMap("linear banded", 9, 116.5, 7, 88.25, false),
                    0, 256, false, createSimpleTheme(false));
            final int tileCount = 24, rounds = 7;
            final double[] javaMillis = new double[rounds];
            final double[] nativeMillis = new double[rounds];
            for (int warmup = 0; warmup < 3; warmup++) {
                benchmarkTiles(javaFactory, tileCount, warmup, false);
                benchmarkTiles(nativeFactory, tileCount, warmup, true);
            }
            for (int round = 0; round < rounds; round++) {
                if ((round & 1) == 0) {
                    javaMillis[round] = benchmarkTiles(javaFactory, tileCount, round, false);
                    nativeMillis[round] = benchmarkTiles(nativeFactory, tileCount, round, true);
                } else {
                    nativeMillis[round] = benchmarkTiles(nativeFactory, tileCount, round, true);
                    javaMillis[round] = benchmarkTiles(javaFactory, tileCount, round, false);
                }
            }
            java.util.Arrays.sort(javaMillis);
            java.util.Arrays.sort(nativeMillis);
            final BenchmarkMemorySupport.Snapshot javaMemory = BenchmarkMemorySupport.measure(
                    () -> benchmarkTiles(javaFactory, tileCount, rounds, false));
            final BenchmarkMemorySupport.Snapshot nativeMemory = BenchmarkMemorySupport.measure(
                    () -> benchmarkTiles(nativeFactory, tileCount, rounds, true));
            final int median = rounds / 2;
            System.out.printf("Linear BandedHeightMap full createTile Java %.3f ms/tile, Rust/JNI %.3f ms/tile, "
                            + "ratio %.3fx java_memory=[%s] native_memory=[%s]%n",
                    javaMillis[median], nativeMillis[median], javaMillis[median] / nativeMillis[median],
                    javaMemory, nativeMemory);
        } finally {
            restoreGenerationFlag(previousFlag);
        }
    }

    private static double benchmarkPerCellFreshTiles(HeightMap heightMap, SimpleTheme theme,
                                                      int tileCount, int round) {
        Native.setGenEnabled(false);
        final int area = Constants.TILE_SIZE * Constants.TILE_SIZE;
        final float[] heights = new float[area];
        final int[] intHeights = new int[area];
        final byte[] terrainOrdinals = new byte[area];
        final long start = System.nanoTime();
        int sink = 0;
        for (int tile = 0; tile < tileCount; tile++) {
            final int tileX = Math.floorMod(tile * 7 + round, 9) - 4;
            final int tileY = Math.floorMod(tile * 13 + round * 3, 9) - 4;
            final Tile generated = createPerCellFreshTile(heightMap, theme, tileX, tileY,
                    heights, intHeights, terrainOrdinals);
            sink ^= Float.floatToRawIntBits(generated.getHeight(tile & 127, (tile * 17) & 127));
        }
        benchmarkSink ^= sink;
        return (System.nanoTime() - start) / 1_000_000.0 / tileCount;
    }

    private static Tile createPerCellFreshTile(HeightMap heightMap, SimpleTheme theme,
                                                int tileX, int tileY, float[] heights,
                                                int[] intHeights, byte[] terrainOrdinals) {
        final int tileSize = Constants.TILE_SIZE;
        final int worldTileX = tileX << Constants.TILE_SIZE_BITS;
        final int worldTileY = tileY << Constants.TILE_SIZE_BITS;
        final Tile tile = new Tile(tileX, tileY, 0, 256);
        tile.inhibitEvents();
        try {
            for (int x = 0; x < tileSize; x++) {
                for (int y = 0; y < tileSize; y++) {
                    final int index = x | (y << Constants.TILE_SIZE_BITS);
                    heights[index] = org.pepsoft.util.MathUtils.clamp(0,
                            (float) heightMap.getHeight(worldTileX + x, worldTileY + y), 255);
                }
            }
            final int[] quantisedHeights = tile.initializeHeightAndWaterLevels(
                    heights, theme.getWaterHeight(), intHeights);
            for (int x = 0; x < tileSize; x++) {
                for (int y = 0; y < tileSize; y++) {
                    final int index = x | (y << Constants.TILE_SIZE_BITS);
                    final int height = quantisedHeights[index];
                    terrainOrdinals[index] = (byte) theme.getTerrainForFreshTile(tile, x, y, height).ordinal();
                    theme.applyLayersToFreshTile(tile, x, y, height);
                }
            }
            tile.initializeTerrainOrdinals(terrainOrdinals);
            return tile;
        } finally {
            tile.releaseEvents();
        }
    }

    private static double benchmarkTiles(HeightMapTileFactory factory, int tileCount,
                                         int round, boolean nativeEnabled) {
        Native.setGenEnabled(nativeEnabled);
        final long start = System.nanoTime();
        int sink = 0;
        for (int tile = 0; tile < tileCount; tile++) {
            final int tileX = Math.floorMod(tile * 7 + round, 9) - 4;
            final int tileY = Math.floorMod(tile * 13 + round * 3, 9) - 4;
            sink ^= Float.floatToRawIntBits(factory.createTile(tileX, tileY).getHeight(tile & 127, (tile * 17) & 127));
        }
        benchmarkSink ^= sink;
        return (System.nanoTime() - start) / 1_000_000.0 / tileCount;
    }

    private static void restoreGenerationFlag(String previousFlag) {
        if (previousFlag == null) {
            System.clearProperty(Native.GEN_KEY);
        } else {
            System.setProperty(Native.GEN_KEY, previousFlag);
        }
    }

    @Test
    public void repeatingBicubicHeightMapBulkRasterMatchesPerCellSampling() {
        for (final int[] imageTypeAndChannel : new int[][] {
                {BufferedImage.TYPE_BYTE_GRAY, 0},
                {BufferedImage.TYPE_USHORT_GRAY, 0},
                {BufferedImage.TYPE_3BYTE_BGR, 2}}) {
            for (final int[] dimensions : new int[][] {{260, 259}, {64, 73}}) {
                final BufferedImage image = new BufferedImage(dimensions[0], dimensions[1], imageTypeAndChannel[0]);
                for (int x = 0; x < image.getWidth(); x++) {
                    for (int y = 0; y < image.getHeight(); y++) {
                        final int red = (x * 17 + y * 3) & 0xff;
                        final int green = (x * 5 + y * 11) & 0xff;
                        final int blue = (x * 7 + y * 13) & 0xff;
                        image.setRGB(x, y, 0xff000000 | (red << 16) | (green << 8) | blue);
                    }
                }
                final BitmapHeightMap legacyBitmap = BitmapHeightMap.build()
                        .withImage(image).withChannel(imageTypeAndChannel[1]).now();
                final BitmapHeightMap batchBitmap = BitmapHeightMap.build()
                        .withImage(image).withChannel(imageTypeAndChannel[1]).now();
                final HeightMapTileFactory legacyFactory = new HeightMapTileFactory(42L,
                        new BicubicHeightMap(legacyBitmap, true), 0, 256, false, createSimpleTheme(true));
                final HeightMapTileFactory batchFactory = new HeightMapTileFactory(42L,
                        new BicubicHeightMap(batchBitmap, true), 0, 256, false, createSimpleTheme(false));
                for (final int[] tile : new int[][] {
                        {0, 0}, {1, 1}, {-1, 0}, {0, -1}, {-1, -1}, {2, -2}}) {
                    final Tile legacy = legacyFactory.createTile(tile[0], tile[1]);
                    final Tile batch = batchFactory.createTile(tile[0], tile[1]);
                    for (int x = 0; x < Constants.TILE_SIZE; x++) {
                        for (int y = 0; y < Constants.TILE_SIZE; y++) {
                            assertEquals("height for image type " + imageTypeAndChannel[0]
                                            + " at " + x + ',' + y,
                                    Float.floatToRawIntBits(legacy.getHeight(x, y)),
                                    Float.floatToRawIntBits(batch.getHeight(x, y)));
                            assertEquals("water at " + x + ',' + y,
                                    legacy.getWaterLevel(x, y), batch.getWaterLevel(x, y));
                            assertEquals("terrain at " + x + ',' + y,
                                    legacy.getTerrain(x, y), batch.getTerrain(x, y));
                        }
                    }
                }
            }
        }
    }

    @Test
    public void freshTileApplicationMatchesExistingPath() {
        final SimpleTheme theme = SimpleTheme.createDefault(Terrain.GRASS, 0, 256, 62, true, true);
        final Tile ordinary = newTileWithHeights();
        final Tile optimized = newTileWithHeights();
        for (int x = 0; x < Constants.TILE_SIZE; x++) {
            for (int y = 0; y < Constants.TILE_SIZE; y++) {
                theme.apply(ordinary, x, y);
            }
        }
        for (int x = 0; x < Constants.TILE_SIZE; x++) {
            for (int y = 0; y < Constants.TILE_SIZE; y++) {
                theme.applyToFreshTile(optimized, x, y);
            }
        }
        for (int x = 0; x < Constants.TILE_SIZE; x++) {
            for (int y = 0; y < Constants.TILE_SIZE; y++) {
                assertEquals("terrain at " + x + ',' + y,
                        ordinary.getTerrain(x, y), optimized.getTerrain(x, y));
                assertEquals("frost bit at " + x + ',' + y,
                        ordinary.getBitLayerValue(Frost.INSTANCE, x, y),
                        optimized.getBitLayerValue(Frost.INSTANCE, x, y));
            }
        }
    }

    @Test
    public void nativeBulkTileInitializationMatchesJava() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            for (final int maxHeight : new int[] {256, 512}) {
                final long seed = 0x3141_5926L;
                final HeightMapTileFactory javaFactory = TileFactoryFactory.createNoiseTileFactory(
                        seed, Terrain.GRASS, 0, maxHeight, 58, 62, false, true, 20.0f, 1.0);
                final HeightMapTileFactory nativeFactory = TileFactoryFactory.createNoiseTileFactory(
                        seed, Terrain.GRASS, 0, maxHeight, 58, 62, false, true, 20.0f, 1.0);
                Native.setGenEnabled(false);
                final Tile javaTile = javaFactory.createTile(-3, 7);
                Native.setGenEnabled(true);
                final Tile nativeTile = nativeFactory.createTile(-3, 7);
                assertNotNull(nativeTile);
                for (int x = 0; x < Constants.TILE_SIZE; x++) {
                    for (int y = 0; y < Constants.TILE_SIZE; y++) {
                        assertEquals("height at " + x + ',' + y,
                                Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                                Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                        assertEquals("water at " + x + ',' + y,
                                javaTile.getWaterLevel(x, y), nativeTile.getWaterLevel(x, y));
                        assertEquals("terrain at " + x + ',' + y,
                                javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
                        assertEquals("Frost at " + x + ',' + y,
                                javaTile.getBitLayerValue(Frost.INSTANCE, x, y),
                                nativeTile.getBitLayerValue(Frost.INSTANCE, x, y));
                    }
                }
            }
        } finally {
            if (previousFlag == null) {
                System.clearProperty(Native.GEN_KEY);
            } else {
                System.setProperty(Native.GEN_KEY, previousFlag);
            }
        }
    }

    @Test
    public void bulkInitializationCoalescesHeightAndWaterEvents() {
        final Tile tile = new Tile(0, 0, 0, 256);
        final int[] changes = new int[2];
        tile.addListener(new Tile.Listener() {
            @Override
            public void heightMapChanged(Tile changedTile) {
                changes[0]++;
            }

            @Override
            public void terrainChanged(Tile changedTile) {
                throw new AssertionError("terrain should not change");
            }

            @Override
            public void waterLevelChanged(Tile changedTile) {
                changes[1]++;
            }

            @Override
            public void layerDataChanged(Tile changedTile, Set<Layer> changedLayers) {
                throw new AssertionError("layers should not change");
            }

            @Override
            public void allBitLayerDataChanged(Tile changedTile) {
                throw new AssertionError("bit layers should not change");
            }

            @Override
            public void allNonBitlayerDataChanged(Tile changedTile) {
                throw new AssertionError("layers should not change");
            }

            @Override
            public void seedsChanged(Tile changedTile) {
                throw new AssertionError("seeds should not change");
            }
        });
        tile.inhibitEvents();
        tile.initializeHeightAndWaterLevels(new float[Constants.TILE_SIZE * Constants.TILE_SIZE], 62);
        tile.releaseEvents();
        assertEquals("height notification", 1, changes[0]);
        assertEquals("water notification", 1, changes[1]);
    }

    @Test
    public void bulkTerrainInitializationCoalescesTerrainEvent() {
        final Tile tile = new Tile(0, 0, 0, 256);
        final int[] terrainChanges = new int[1];
        tile.addListener(new Tile.Listener() {
            @Override
            public void heightMapChanged(Tile changedTile) {
                throw new AssertionError("height should not change");
            }

            @Override
            public void terrainChanged(Tile changedTile) {
                terrainChanges[0]++;
            }

            @Override
            public void waterLevelChanged(Tile changedTile) {
                throw new AssertionError("water should not change");
            }

            @Override
            public void layerDataChanged(Tile changedTile, Set<Layer> changedLayers) {
                throw new AssertionError("layers should not change");
            }

            @Override
            public void allBitLayerDataChanged(Tile changedTile) {
                throw new AssertionError("layers should not change");
            }

            @Override
            public void allNonBitlayerDataChanged(Tile changedTile) {
                throw new AssertionError("layers should not change");
            }

            @Override
            public void seedsChanged(Tile changedTile) {
                throw new AssertionError("seeds should not change");
            }
        });
        final byte[] terrainOrdinals = new byte[Constants.TILE_SIZE * Constants.TILE_SIZE];
        terrainOrdinals[5] = (byte) Terrain.BEACHES.ordinal();
        tile.inhibitEvents();
        tile.initializeTerrainOrdinals(terrainOrdinals);
        tile.releaseEvents();
        assertEquals("coalesced terrain notification", 1, terrainChanges[0]);
        assertEquals("batch terrain value", Terrain.BEACHES, tile.getTerrain(5, 0));
    }

    private static Tile newTileWithHeights() {
        final Tile tile = new Tile(0, 0, 0, 256);
        tile.inhibitEvents();
        for (int x = 0; x < Constants.TILE_SIZE; x++) {
            for (int y = 0; y < Constants.TILE_SIZE; y++) {
                tile.setHeight(x, y, ((x & 1) == 0) ? 0.0f : 255.0f);
            }
        }
        tile.releaseEvents();
        return tile;
    }

    private static void resetSimpleThemeRandom(long seed) throws ReflectiveOperationException {
        final java.lang.reflect.Field randomField = SimpleTheme.class.getDeclaredField("random");
        randomField.setAccessible(true);
        ((java.util.Random) randomField.get(null)).setSeed(seed);
    }

    private static SimpleTheme createSimpleTheme(boolean legacyPerCellPath) {
        final SortedMap<Integer, Terrain> ranges = new TreeMap<>();
        ranges.put(-1, Terrain.GRASS);
        ranges.put(95, Terrain.STONE_MIX);
        if (legacyPerCellPath) {
            return new SimpleTheme(0L, 62, ranges, null, 0, 256, false, true) { };
        }
        return new SimpleTheme(0L, 62, ranges, null, 0, 256, false, true);
    }

    private static SimpleTheme createDeterministicLayerTheme(boolean legacyPerCellPath) {
        final SortedMap<Integer, Terrain> ranges = new TreeMap<>();
        ranges.put(-1, Terrain.GRASS);
        ranges.put(95, Terrain.STONE_MIX);
        final Map<Filter, Layer> layers = new java.util.LinkedHashMap<>();
        layers.put(new HeightFilter(0, 256, 0, 255, false), Frost.INSTANCE);
        layers.put(new HeightFilter(0, 256, 0, 255, false), Populate.INSTANCE);
        layers.put(new HeightFilter(0, 256, 0, 255, false), Resources.INSTANCE);
        layers.put(new HeightFilter(0, 256, 0, 255, false), Annotations.INSTANCE);
        layers.put(new HeightFilter(0, 256, 0, 255, false), Biome.INSTANCE);
        final SimpleTheme theme = legacyPerCellPath
                ? new SimpleTheme(0L, 62, ranges, layers, 0, 256, true, true) { }
                : new SimpleTheme(0L, 62, ranges, layers, 0, 256, true, true);
        theme.setDiscreteValues(java.util.Collections.singletonMap(Biome.INSTANCE, 4));
        return theme;
    }

    private static SimpleTheme createNoisySimpleTheme(boolean legacyPerCellPath) {
        final SortedMap<Integer, Terrain> ranges = new TreeMap<>();
        ranges.put(-1, Terrain.GRASS);
        ranges.put(126, Terrain.PERMADIRT);
        ranges.put(158, Terrain.STONE_MIX);
        ranges.put(222, Terrain.DEEP_SNOW);
        final Map<Filter, Layer> layers = java.util.Collections.singletonMap(
                new HeightFilter(0, 256, 190, 256, true), Frost.INSTANCE);
        if (legacyPerCellPath) {
            return new SimpleTheme(0L, 62, ranges, layers, 0, 256, true, true) { };
        }
        return new SimpleTheme(0L, 62, ranges, layers, 0, 256, true, true);
    }

    private static SimpleTheme createNoisyMixedSimpleTheme(boolean legacyPerCellPath) {
        final SortedMap<Integer, Terrain> ranges = new TreeMap<>();
        ranges.put(-1, Terrain.GRASS);
        ranges.put(126, Terrain.PERMADIRT);
        ranges.put(158, Terrain.STONE_MIX);
        final Map<Filter, Layer> layers = new java.util.LinkedHashMap<>();
        layers.put(new HeightFilter(0, 256, 70, 180, true), FloodWithLava.INSTANCE);
        layers.put(new HeightFilter(0, 256, 55, 200, false), Resources.INSTANCE);
        layers.put(new HeightFilter(0, 256, 80, 170, false), Biome.INSTANCE);
        final SimpleTheme theme = legacyPerCellPath
                ? new SimpleTheme(0L, 62, ranges, layers, 0, 256, true, true) { }
                : new SimpleTheme(0L, 62, ranges, layers, 0, 256, true, true);
        theme.setDiscreteValues(java.util.Collections.singletonMap(Biome.INSTANCE, 4));
        return theme;
    }

    private static HeightMap createFrostExerciseHeightMap() {
        return new SumHeightMap(new ConstantHeightMap(150.0),
                new NoiseHeightMap(80.0, 0.8, 3, -0x1020_3040L));
    }

    private static volatile int benchmarkSink;
}
