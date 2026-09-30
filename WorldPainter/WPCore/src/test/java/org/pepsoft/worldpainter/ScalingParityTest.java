package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;

import java.awt.Point;
import java.util.*;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

public class ScalingParityTest {
    @Test
    public void completeWorldRescalingMatchesEveryStoredTilePlane() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        final String previous = System.getProperty(Native.GEN_KEY);
        try {
            final Map<Point, Tile> sources = ScalingBenchmark.fixture();
            final TileFactory factory = TestData.createTileFactory(62);
            for (float scale : new float[] {0.5f, 0.75f, 1f, 1.1f, 1.5f, 2f}) {
                final ScalingHelper java = new ScalingHelper(sources, factory, scale);
                final ScalingHelper rust = new ScalingHelper(sources, factory, scale);
                assertEquals(java.getTileCoords(), rust.getTileCoords());
                for (Point point : java.getTileCoords()) {
                    System.setProperty(Native.GEN_KEY, "false");
                    final Tile expected = java.createScaledTile(point.x, point.y);
                    System.setProperty(Native.GEN_KEY, "true");
                    final Tile actual = rust.createScaledTile(point.x, point.y);
                    assertTile(expected, actual, "scale " + scale + " tile " + point);
                }
            }
            final List<Layer> layers = new ArrayList<>(sources.values().iterator().next().getLayers());
            System.setProperty(Native.GEN_KEY, "true");
            assertTrue("native path must not silently fall back", ScalingTileAccess.scale(
                    factory.createTile(0, 0), sources, new HashMap<>(), factory, 1.5f, layers));
        } finally {
            if (previous == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, previous);
        }
    }

    private static void assertTile(Tile expected, Tile actual, String message) {
        expected.ensureAllReadable(); actual.ensureAllReadable();
        assertArrayEquals(message + " height", expected.heightMap, actual.heightMap);
        assertArrayEquals(message + " tall height", expected.tallHeightMap, actual.tallHeightMap);
        assertArrayEquals(message + " terrain", expected.terrain, actual.terrain);
        assertArrayEquals(message + " water", expected.waterLevel, actual.waterLevel);
        assertArrayEquals(message + " tall water", expected.tallWaterLevel, actual.tallWaterLevel);
        assertEquals(message + " bit layers", expected.bitLayerData, actual.bitLayerData);
        assertEquals(message + " layer keys", expected.layerData.keySet(), actual.layerData.keySet());
        for (Layer layer : expected.layerData.keySet()) assertArrayEquals(message + " " + layer,
                expected.layerData.get(layer), actual.layerData.get(layer));
    }

    @Test
    public void noiseBordersCustomLayersAndFourWorkersMatchJava() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        final String previous = System.getProperty(Native.GEN_KEY);
        final var workers = Executors.newFixedThreadPool(4);
        try {
            final Map<Point, Tile> sources = ScalingBenchmark.fixture();
            sources.remove(new Point(-1, 0));
            final Tile customized = sources.get(new Point(0, 0));
            customized.setLayerValue(TileRotationParityTest.NIBBLES, 3, 5, 13);
            customized.setLayerValue(TileRotationParityTest.BYTES, 9, 7, 239);
            customized.setTerrain(17, 31, Terrain.CUSTOM_1);
            final HeightMap noise = new org.pepsoft.worldpainter.heightMaps.NoiseHeightMap(42, 1.3, 3).plus(62);
            final TileFactory factory = new HeightMapTileFactory(19L, noise, TestData.MIN_HEIGHT,
                    TestData.MAX_HEIGHT, false, TestData.THEME);
            final float scale = 0.75f;
            final ScalingHelper baseline = new ScalingHelper(sources, factory, scale);
            System.setProperty(Native.GEN_KEY, "false");
            final Map<Point, Tile> expected = new HashMap<>();
            for (Point point : baseline.getTileCoords()) expected.put(point, baseline.createScaledTile(point.x, point.y));
            System.setProperty(Native.GEN_KEY, "true");
            final ScalingHelper nativeHelper = new ScalingHelper(sources, factory, scale);
            final List<java.util.concurrent.Future<?>> results = new ArrayList<>();
            for (Point point : nativeHelper.getTileCoords()) {
                results.add(workers.submit(() -> assertTile(expected.get(point),
                        nativeHelper.createScaledTile(point.x, point.y), "parallel " + point)));
            }
            for (var result : results) result.get();
        } finally {
            workers.shutdownNow();
            if (previous == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, previous);
        }
    }

    @Test
    public void mixedHeightFormatsAndPerChunkLayersPreserveParityAndUndo() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        final String previous = System.getProperty(Native.GEN_KEY);
        try {
            final Map<Point, Tile> sources = ScalingBenchmark.fixture();
            final Tile normal = new Tile(0, 0, 0, 256);
            normal.setHeight(3, 5, 73.125f);
            normal.setBitLayerValue(org.pepsoft.worldpainter.layers.Populate.INSTANCE, 31, 47, true);
            sources.put(new Point(0, 0), normal);
            final TileFactory factory = new HeightMapTileFactory(0,
                    new org.pepsoft.worldpainter.heightMaps.ConstantHeightMap(62), 0, 256, false,
                    org.pepsoft.worldpainter.themes.SimpleTheme.createSingleTerrain(Terrain.GRASS, 0, 256, 62));
            final ScalingHelper java = new ScalingHelper(sources, factory, 1.3f);
            final ScalingHelper rust = new ScalingHelper(sources, factory, 1.3f);
            for (Point point : java.getTileCoords()) {
                System.setProperty(Native.GEN_KEY, "false");
                final Tile expected = java.createScaledTile(point.x, point.y);
                System.setProperty(Native.GEN_KEY, "true");
                final Tile actual = rust.createScaledTile(point.x, point.y);
                assertTile(expected, actual, "normal destination " + point);
                final var undo = new org.pepsoft.util.undo.UndoManager();
                actual.register(undo); undo.armSavePoint();
                final int raw = actual.getRawHeight(3, 5);
                actual.setRawHeight(3, 5, 12345);
                assertTrue(undo.undo()); assertEquals(raw, actual.getRawHeight(3, 5));
                assertTrue(undo.redo()); assertEquals(12345, actual.getRawHeight(3, 5));
            }
        } finally {
            if (previous == null) System.clearProperty(Native.GEN_KEY); else System.setProperty(Native.GEN_KEY, previous);
        }
    }
}
