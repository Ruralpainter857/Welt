package org.pepsoft.worldpainter.themes.impl.fancy;

import org.junit.Test;
import org.pepsoft.worldpainter.HeightMapTileFactory;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.TileFactoryFactory;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public final class FancyThemeFreshTileBatchTest {
    @Test
    public void freshTileCacheMatchesLegacyFancyThemeAcrossNegativeCoordinates() {
        final String batchProperty = "wp.fancyTheme.freshTileBatch";
        final String previousBatch = System.getProperty(batchProperty);
        final String previousNative = System.getProperty(Native.GEN_KEY);
        final HeightMapTileFactory factory = TileFactoryFactory.createFancyTileFactory(
                42L, Terrain.GRASS, 0, 256, 58, 62, false, 20.0f, 1.0);
        final FancyTheme theme = (FancyTheme) factory.getTheme();
        try {
            System.setProperty(batchProperty, "true");
            assertTrue(theme.supportsFreshTileBatch());
            Native.setGenEnabled(false);
            System.setProperty(batchProperty, "false");
            final Tile expected = factory.createTile(-2, 1);
            System.setProperty(batchProperty, "true");
            final Tile actual = factory.createTile(-2, 1);
            assertTileEquals(expected, actual);
        } finally {
            restoreProperty(batchProperty, previousBatch);
            restoreProperty(Native.GEN_KEY, previousNative);
        }
    }

    @Test
    public void nativeHeightNeighborhoodMatchesJavaForFancyTiles() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String batchProperty = "wp.fancyTheme.freshTileBatch";
        final String previousBatch = System.getProperty(batchProperty);
        final String previousNative = System.getProperty(Native.GEN_KEY);
        final String previousNinePatch = System.getProperty(Native.NINE_PATCH_GEN_KEY);
        final HeightMapTileFactory factory = TileFactoryFactory.createFancyTileFactory(
                42L, Terrain.GRASS, 0, 256, 58, 62, false, 20.0f, 1.0);
        final int[][] tileCoordinates = {{-2, 1}, {0, 0}, {3, -4}};
        try {
            System.setProperty(batchProperty, "true");
            Native.setNinePatchGenEnabled(true);
            for (int[] coordinates : tileCoordinates) {
                Native.setGenEnabled(false);
                final Tile expected = factory.createTile(coordinates[0], coordinates[1]);
                Native.setGenEnabled(true);
                final Tile actual = factory.createTile(coordinates[0], coordinates[1]);
                assertTileEquals(expected, actual);
            }
        } finally {
            restoreProperty(batchProperty, previousBatch);
            restoreProperty(Native.GEN_KEY, previousNative);
            restoreProperty(Native.NINE_PATCH_GEN_KEY, previousNinePatch);
        }
    }

    @Test
    public void nativeFancyThemeKernelFillsTerrainAndLayerPlanes() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousNative = System.getProperty(Native.GEN_KEY);
        try {
            Native.setGenEnabled(true);
            final float[] tileHeights = {62.0f};
            final float[] neighborhood = new float[11 * 11];
            java.util.Arrays.fill(neighborhood, 62.0f);
            neighborhood[0] = 61.0f;
            final byte[] output = new byte[7];
            final boolean filled = NativeSlices.fillFancyThemeTile(1, 1, 62, 82,
                    Terrain.GRASS.ordinal(), Terrain.DESERT.ordinal(), Terrain.SANDSTONE.ordinal(),
                    Terrain.BARE_GRASS.ordinal(), Terrain.BEACHES.ordinal(),
                    Terrain.CUSTOM_1.ordinal(), Terrain.CUSTOM_2.ordinal(),
                    tileHeights, neighborhood, new double[] {25.0}, new double[] {60.0},
                    new double[] {0.5}, output);
            assumeTrue("native library predates the FancyTheme tile entry point", filled);
            assertEquals(Terrain.BEACHES.ordinal(), output[0] & 0xff);
            assertEquals(8, output[1] & 0xff);
            assertEquals(8, output[2] & 0xff);
            assertEquals(0, output[5] & 0xff);
            assertEquals(0, output[6] & 0xff);
        } finally {
            restoreProperty(Native.GEN_KEY, previousNative);
        }
    }

    @Test
    public void customFancyThemeSubclassKeepsTheLegacyPath() {
        final FancyTheme theme = new FancyTheme(0, 256, 62, null, Terrain.GRASS) {
            @Override
            public void apply(Tile tile, int x, int y) {
                super.apply(tile, x, y);
            }
        };
        assertFalse(theme.supportsFreshTileBatch());
    }

    private static void assertTileEquals(Tile expected, Tile actual) {
        final List<Layer> expectedLayers = expected.getLayers();
        assertEquals(expectedLayers, actual.getLayers());
        for (int x = 0; x < 128; x++) {
            for (int y = 0; y < 128; y++) {
                assertEquals("height at " + x + "," + y,
                        Float.floatToRawIntBits(expected.getHeight(x, y)),
                        Float.floatToRawIntBits(actual.getHeight(x, y)));
                assertEquals("water at " + x + "," + y,
                        expected.getWaterLevel(x, y), actual.getWaterLevel(x, y));
                assertEquals("terrain at " + x + "," + y,
                        expected.getTerrain(x, y), actual.getTerrain(x, y));
                for (Layer layer : expectedLayers) {
                    switch (layer.getDataSize()) {
                        case BIT, BIT_PER_CHUNK -> assertEquals(
                                "layer " + layer.getId() + " at " + x + "," + y,
                                expected.getBitLayerValue(layer, x, y),
                                actual.getBitLayerValue(layer, x, y));
                        case NIBBLE, BYTE -> assertEquals(
                                "layer " + layer.getId() + " at " + x + "," + y,
                                expected.getLayerValue(layer, x, y),
                                actual.getLayerValue(layer, x, y));
                        default -> throw new AssertionError("Unexpected layer size " + layer.getDataSize());
                    }
                }
            }
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
