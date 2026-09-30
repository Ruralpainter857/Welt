package org.pepsoft.worldpainter.themes.impl.fancy;

import org.junit.Test;
import org.pepsoft.worldpainter.HeightMapTileFactory;
import org.pepsoft.worldpainter.Terrain;
import org.pepsoft.worldpainter.Tile;
import org.pepsoft.worldpainter.TileFactoryFactory;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.Native;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
