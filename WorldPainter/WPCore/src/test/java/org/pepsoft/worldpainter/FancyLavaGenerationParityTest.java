package org.pepsoft.worldpainter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.pepsoft.worldpainter.heightMaps.*;
import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.themes.impl.fancy.FancyTheme;
import static org.junit.Assert.*;

/** The lava mask must commute with the complete deterministic climate transaction. */
public class FancyLavaGenerationParityTest {
    private final Map<String, String> properties = new LinkedHashMap<>();
    @Before public void prepare() {
        boolean loaded = NativeLoader.areSlicesAvailable();
        if (Boolean.getBoolean("welt.test.requireFancyLavaGeneration")) assertTrue("Release JNI library required", loaded);
        org.junit.Assume.assumeTrue(loaded);
        for (String key : new String[]{Native.GEN_KEY, Native.NINE_PATCH_GEN_KEY,
                "welt.native.fancyGeneration", "wp.fancyTheme.freshTileBatch"}) {
            properties.put(key, System.getProperty(key)); System.setProperty(key, "true");
        }
    }
    @After public void restore() {
        properties.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
    }
    private static FancyTheme climate(HeightMap map, int min, int max, double temperature, double humidity) {
        FancyTheme theme = new FancyTheme(min, max, 62, map, Terrain.CUSTOM_3);
        theme.setTemperatureMap(new ConstantHeightMap(temperature));
        theme.setHumidityMap(new ConstantHeightMap(humidity));
        theme.setForestMap(new ConstantHeightMap(.5));
        return theme;
    }
    private static Tile compare(HeightMap source, FancyTheme theme, int min, int max,
                                int tx, int ty, boolean lava, boolean supported) throws Exception {
        HeightMapTileFactory java = new HeightMapTileFactory(197, source, min, max, lava, theme);
        HeightMapTileFactory rust = new HeightMapTileFactory(197, source, min, max, lava, theme);
        var random = HeightMapImportBenchmark.random();
        System.setProperty(Native.GEN_KEY, "false"); random.setSeed(42);
        Tile expected = java.createTile(tx, ty); long expectedNext = random.nextLong();
        System.setProperty(Native.GEN_KEY, "true"); random.setSeed(42);
        long before = HeightMapTileFactory.completedNativeFancyTiles();
        Tile actual = rust.createTile(tx, ty); long actualNext = random.nextLong();
        assertEquals("Complete climate transaction count", supported ? 1 : 0,
                HeightMapTileFactory.completedNativeFancyTiles() - before);
        assertEquals("Random state after complete creation", expectedNext, actualNext);
        FancyTileGenerationParityTest.assertTiles(expected, actual);
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++)
            assertEquals("Factory lava mask", lava, actual.getBitLayerValue(FloodWithLava.INSTANCE, x, y));
        return actual;
    }
    @Test public void climateBoundariesSnowAndSteepSlopesPreserveLavaWorlds() throws Exception {
        for (int min : new int[]{0, -64}) for (int height : new int[]{57, 63, 200}) {
            HeightMap source = new ConstantHeightMap(height);
            for (int temperature : new int[]{35, 15, -15})
                compare(source, climate(source, min, min == 0 ? 256 : 320, temperature, 70),
                        min, min == 0 ? 256 : 320, -1, 1, true, true);
        }
        HeightMap steep = new BandedHeightMap(3, 60, 3, 80, false);
        compare(steep, climate(steep, -64, 320, 35, 30), -64, 320, 0, 0, true, true);
    }
    @Test public void stockContinentAndDeformedTerrainPreserveCompleteLavaTiles() throws Exception {
        for (String name : new String[]{"noise", "affine", "displacement", "affine-displacement"}) {
            HeightMap source = BitmapWorldCreationBenchmark.proceduralMap(name);
            FancyTheme theme = climate(source, -64, 320, 35, 70);
            compare(source, theme, -64, 320, -1, 1, true, true);
            theme.setTemperatureMap(new ConstantHeightMap(-30));
            theme.setHumidityMap(new ConstantHeightMap(30));
            compare(source, theme, -64, 320, 0, 0, true, true);
        }
        HeightMapTileFactory stock = TileFactoryFactory.createFancyTileFactory(42, Terrain.GRASS, 0, 256, 58, 62, false, 20f, 1);
        compare(stock.getHeightMap(), (FancyTheme) stock.getTheme(), 0, 256, -1, 1, true, true);
    }
    @Test public void liveClimateAndWaterWorldsDoNotRetainThePreviousLavaMask() throws Exception {
        HeightMap source = new ConstantHeightMap(80.25);
        FancyTheme theme = climate(source, -64, 320, -30, 70);
        compare(source, theme, -64, 320, 0, 0, true, true);
        theme.setTemperatureMap(new ConstantHeightMap(35));
        assertFalse(compare(source, theme, -64, 320, 0, 0, false, true).hasLayer(FloodWithLava.INSTANCE));
        theme.setHumidityMap(new ConstantHeightMap(30));
        compare(source, theme, -64, 320, 0, 0, true, true);
    }
    @Test public void unsupportedClimateSourcesAndDisabledFlagRetainJava() throws Exception {
        HeightMap source = new ConstantHeightMap(80);
        FancyTheme theme = climate(source, -64, 320, 35, 70);
        theme.setTemperatureMap(new SlopeHeightMap(new SlopeHeightMap(new ConstantHeightMap(20))));
        compare(source, theme, -64, 320, 0, 0, true, false);
        theme = climate(source, -64, 320, 35, 70);
        compare(new ConstantHeightMap(80), theme, -64, 320, 0, 0, true, false);
        System.setProperty("welt.native.fancyGeneration", "false");
        compare(source, theme, -64, 320, 0, 0, true, false);
    }
    @Test public void concurrentLavaAndWaterWorkersOwnIndependentBuffers() throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var factories = new ArrayList<HeightMapTileFactory>();
            var expected = new ArrayList<Tile>();
            System.setProperty(Native.GEN_KEY, "false");
            for (int i = 0; i < 8; i++) {
                HeightMap source = BitmapWorldCreationBenchmark.proceduralMap((i & 1) == 0 ? "affine" : "affine-displacement");
                HeightMapTileFactory factory = new HeightMapTileFactory(197, source, -64, 320, (i & 1) == 0,
                        climate(source, -64, 320, (i & 1) == 0 ? -30 : 35, 70));
                factories.add(factory); expected.add(factory.createTile(i - 4, 4 - i));
            }
            System.setProperty(Native.GEN_KEY, "true");
            var results = new ArrayList<java.util.concurrent.Future<Tile>>();
            for (int i = 0; i < 8; i++) {
                final int at = i;
                results.add(executor.submit(() -> {
                    long before = HeightMapTileFactory.completedNativeFancyTiles();
                    Tile tile = factories.get(at).createTile(at - 4, 4 - at);
                    assertEquals(1, HeightMapTileFactory.completedNativeFancyTiles() - before);
                    return tile;
                }));
            }
            for (int i = 0; i < 8; i++) FancyTileGenerationParityTest.assertTiles(expected.get(i), results.get(i).get());
        } finally { executor.shutdownNow(); }
    }
}
