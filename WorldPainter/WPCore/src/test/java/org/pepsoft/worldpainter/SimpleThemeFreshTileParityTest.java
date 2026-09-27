package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.themes.SimpleTheme;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/** Verifies the optimized initialization path against ordinary theme application. */
public final class SimpleThemeFreshTileParityTest {
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
}
