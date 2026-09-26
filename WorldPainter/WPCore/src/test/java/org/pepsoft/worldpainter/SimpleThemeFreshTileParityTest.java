package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.themes.SimpleTheme;

import static org.junit.Assert.assertEquals;

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
