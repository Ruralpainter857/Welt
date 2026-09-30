package org.pepsoft.worldpainter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class TileRenderDataSnapshotTest {
    @Test
    public void renderSnapshotMatchesTileAccessorsForNormalAndTallTiles() {
        assertSnapshotMatchesTileAccessors(0, 256);
        assertSnapshotMatchesTileAccessors(-64, 320);
    }

    private static void assertSnapshotMatchesTileAccessors(int minHeight, int maxHeight) {
        final Tile tile = new Tile(0, 0, minHeight, maxHeight);
        final Terrain[] terrains = Terrain.values();
        tile.inhibitEvents();
        try {
            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    final int offset = x | (y << Constants.TILE_SIZE_BITS);
                    tile.setHeight(x, y, minHeight + 16.25f + ((x * 3 + y * 7) % 192));
                    tile.setWaterLevel(x, y, minHeight + 96 + ((x + y) % 24));
                    tile.setTerrain(x, y, terrains[(x * 13 + y * 17) % terrains.length]);
                }
            }
        } finally {
            tile.releaseEvents();
        }

        final int area = Constants.TILE_SIZE * Constants.TILE_SIZE;
        final float[] heights = new float[area];
        final int[] intHeights = new int[area];
        final int[] waterLevels = new int[area];
        final byte[] terrainOrdinals = new byte[area];
        tile.copyRenderDataTo(heights, intHeights, waterLevels, terrainOrdinals);

        for (int x = 0; x < Constants.TILE_SIZE; x++) {
            for (int y = 0; y < Constants.TILE_SIZE; y++) {
                final int offset = x | (y << Constants.TILE_SIZE_BITS);
                assertEquals("height " + x + ',' + y,
                        Float.floatToRawIntBits(tile.getHeight(x, y)),
                        Float.floatToRawIntBits(heights[offset]));
                assertEquals("rounded height " + x + ',' + y,
                        tile.getIntHeight(x, y), intHeights[offset]);
                assertEquals("water level " + x + ',' + y,
                        tile.getWaterLevel(x, y), waterLevels[offset]);
                assertEquals("terrain " + x + ',' + y,
                        tile.getTerrain(x, y), terrains[terrainOrdinals[offset] & 0xff]);
            }
        }
    }
}
