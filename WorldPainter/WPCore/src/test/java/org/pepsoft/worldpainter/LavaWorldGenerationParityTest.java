package org.pepsoft.worldpainter;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.pepsoft.worldpainter.heightMaps.*;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.themes.Filter;
import org.pepsoft.worldpainter.themes.SimpleTheme;
import static org.junit.Assert.*;

/** Complete lava-world tiles must retain storage, theme ordering and random state. */
public class LavaWorldGenerationParityTest {
    private static final Layer CHUNK = new Layer("welt.test.lava.chunk", "Chunk", "", Layer.DataSize.BIT_PER_CHUNK, false, 30) { };
    private final Map<String, String> properties = new LinkedHashMap<>();

    @Before public void prepare() {
        boolean available = NativeLoader.areSlicesAvailable();
        if (Boolean.getBoolean("welt.test.requireLavaGeneration")) assertTrue("Release JNI library required", available);
        org.junit.Assume.assumeTrue(available);
        for (String key : new String[]{Native.GEN_KEY, "welt.native.bitmapGeneration",
                "welt.native.proceduralGeneration", "welt.native.coordinateGraphGeneration"}) {
            properties.put(key, System.getProperty(key));
            System.setProperty(key, "true");
        }
    }
    @After public void restore() {
        properties.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
    }
    private static SimpleTheme theme(int min, int max, boolean controlsLava) {
        var ranges = new TreeMap<Integer, Terrain>();
        ranges.put(min - 1, Terrain.CUSTOM_1); ranges.put(50, Terrain.GRASS); ranges.put(100, Terrain.STONE);
        Map<Filter, Layer> layers = new LinkedHashMap<>();
        layers.put((x, y, z, level) -> Math.floorMod(z, 16), Resources.INSTANCE);
        layers.put((x, y, z, level) -> z > 120 ? 200 : 255, Biome.INSTANCE);
        layers.put((x, y, z, level) -> z < 50 ? 0 : z < 130 ? 7 : 15, Frost.INSTANCE);
        layers.put((x, y, z, level) -> z < 90 ? 0 : z < 150 ? 9 : 15, CHUNK);
        if (controlsLava) layers.put((x, y, z, level) -> z < 90 ? 0 : z < 150 ? 7 : 15, FloodWithLava.INSTANCE);
        return new SimpleTheme(197, 62, ranges, layers, min, max, true, true);
    }
    private static Tile compare(HeightMap map, int min, int max, int tx, int ty,
                                boolean lava, boolean controlsLava, boolean supported) throws Exception {
        HeightMapTileFactory java = new HeightMapTileFactory(197, map, min, max, lava, theme(min, max, controlsLava));
        HeightMapTileFactory rust = new HeightMapTileFactory(197, map, min, max, lava, theme(min, max, controlsLava));
        var random = HeightMapImportBenchmark.random();
        System.setProperty(Native.GEN_KEY, "false"); random.setSeed(42);
        Tile expected = java.createTile(tx, ty); long expectedNext = random.nextLong();
        System.setProperty(Native.GEN_KEY, "true"); random.setSeed(42);
        long before = HeightMapTileFactory.completedNativeGeneratedTiles();
        Tile actual = rust.createTile(tx, ty); long actualNext = random.nextLong();
        assertEquals("Complete native transaction count", supported ? 1 : 0,
                HeightMapTileFactory.completedNativeGeneratedTiles() - before);
        assertEquals("Random state after complete creation", expectedNext, actualNext);
        assertEquals(expected.getLayers(), actual.getLayers());
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
            assertEquals(expected.getRawHeight(x, y), actual.getRawHeight(x, y));
            assertEquals(Float.floatToIntBits(expected.getHeight(x, y)), Float.floatToIntBits(actual.getHeight(x, y)));
            assertEquals(expected.getWaterLevel(x, y), actual.getWaterLevel(x, y));
            assertEquals(expected.getTerrain(x, y), actual.getTerrain(x, y));
            for (Layer layer : expected.getLayers()) {
                if (layer.dataSize == Layer.DataSize.BIT || layer.dataSize == Layer.DataSize.BIT_PER_CHUNK)
                    assertEquals(layer.getName() + " source=" + map.getName() + " min=" + min + " tile=" + tx + "," + ty + " cell=" + x + "," + y, expected.getBitLayerValue(layer, x, y), actual.getBitLayerValue(layer, x, y));
                else assertEquals(layer.getName(), expected.getLayerValue(layer, x, y), actual.getLayerValue(layer, x, y));
            }
            if (lava && !controlsLava) assertTrue(actual.getBitLayerValue(FloodWithLava.INSTANCE, x, y));
        }
        return actual;
    }
    @Test public void proceduralAndNestedSourcesPreserveCompleteLavaTiles() throws Exception {
        for (String source : new String[]{"noise", "affine", "displacement", "nested-warp"}) {
            HeightMap map = BitmapWorldCreationBenchmark.proceduralMap(source);
            ((AbstractHeightMap) map).setName(source);
            for (int min : new int[]{0, -64}) for (int tx : new int[]{-1, 0})
                compare(map, min, min == 0 ? 256 : 320, tx, -tx, true, false, true);
        }
    }
    @Test public void bitmapAndFractionalHeightsPreserveLavaAndLayers() throws Exception {
        BufferedImage image = new BufferedImage(32, 24, BufferedImage.TYPE_USHORT_GRAY);
        for (int y = 0; y < 24; y++) for (int x = 0; x < 32; x++) image.getRaster().setSample(x, y, 0, (x * 13 + y * 7) & 255);
        HeightMap map = new TransformingHeightMap("Lava image", new BicubicHeightMap(
                BitmapHeightMap.build().withImage(image).now(), true), 1.7f, .65f, 31, -47, .37f);
        compare(map, -64, 320, -1, 0, true, false, true);
        for (double value : new double[]{-1000, -63.5, 32.25, 100000})
            compare(new ConstantHeightMap(value), -64, 192, 0, 0, true, false, true);
    }
    @Test public void themesWhichControlLavaKeepTheirOriginalMutationOrder() throws Exception {
        compare(BitmapWorldCreationBenchmark.proceduralMap("noise"), -64, 320, -1, 1, true, true, false);
        compare(new ConstantHeightMap(160), 0, 256, 0, 0, true, true, false);
    }
    @Test public void reusingWorkersDoesNotLeakLavaIntoWaterWorlds() throws Exception {
        HeightMap map = BitmapWorldCreationBenchmark.proceduralMap("noise");
        Tile lava = compare(map, -64, 320, 0, 0, true, false, true);
        Tile water = compare(map, -64, 320, 0, 0, false, false, true);
        assertTrue(lava.hasLayer(FloodWithLava.INSTANCE));
        assertFalse(water.hasLayer(FloodWithLava.INSTANCE));
        compare(map, -64, 320, 0, 0, true, false, true);
    }
}
