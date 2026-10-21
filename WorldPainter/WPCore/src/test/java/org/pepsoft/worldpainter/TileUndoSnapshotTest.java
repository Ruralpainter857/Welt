package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.layers.Biome;
import org.pepsoft.worldpainter.layers.FloodWithLava;

import static org.junit.Assert.*;

public class TileUndoSnapshotTest {
    @Test
    public void shortTileRetainsHistoricalBuffers() {
        checkSnapshot(0, 256, false);
    }

    @Test
    public void tallTileRetainsHistoricalBuffers() {
        checkSnapshot(-64, 320, false);
    }

    @Test
    public void tallSnapshotCanBeReadForFirstTimeAfterEdits() {
        checkSnapshot(-1024, 3072, true);
    }

    private void checkSnapshot(int minHeight, int maxHeight, boolean lazy) {
        Tile tile = new Tile(-3, 7, minHeight, maxHeight);
        UndoManager undo = new UndoManager(8);
        tile.register(undo);
        try {
            write(tile, minHeight, 17);
            Object heightBuffer = maxHeight - minHeight > 256 ? tile.tallHeightMap : tile.heightMap;
            Object waterBuffer = maxHeight - minHeight > 256 ? tile.tallWaterLevel : tile.waterLevel;
            TileSnapshot snapshot = new TileSnapshot(tile, undo.getSnapshot());
            undo.savePoint();
            if (!lazy) {
                check(snapshot, minHeight, 17);
            }
            write(tile, minHeight, 93);
            check(tile, minHeight, 93);
            check(snapshot, minHeight, 17);
            assertSame(heightBuffer, maxHeight - minHeight > 256 ? snapshot.tallHeightMap : snapshot.heightMap);
            assertSame(waterBuffer, maxHeight - minHeight > 256 ? snapshot.tallWaterLevel : snapshot.waterLevel);
            assertTrue(undo.undo());
            check(tile, minHeight, 17);
            check(snapshot, minHeight, 17);
            assertTrue(undo.redo());
            check(tile, minHeight, 93);
            check(snapshot, minHeight, 17);
        } finally {
            tile.unregister();
        }
    }

    private void write(Tile tile, int minHeight, int value) {
        tile.inhibitEvents();
        try {
            for (int y = 0; y < Constants.TILE_SIZE; y++) {
                for (int x = 0; x < Constants.TILE_SIZE; x++) {
                    tile.setHeight(x, y, minHeight + value + (x % 7) + 0.25f);
                    tile.setWaterLevel(x, y, minHeight + value + (y % 11));
                    tile.setTerrain(x, y, value == 17 ? Terrain.GRASS : Terrain.STONE);
                    tile.setLayerValue(Biome.INSTANCE, x, y, value);
                    tile.setBitLayerValue(FloodWithLava.INSTANCE, x, y, (x + y + value) % 3 == 0);
                }
            }
        } finally {
            tile.releaseEvents();
        }
    }

    private void check(Tile tile, int minHeight, int value) {
        int area = Constants.TILE_SIZE * Constants.TILE_SIZE;
        float[] heights = new float[area];
        int[] rounded = new int[area];
        int[] water = new int[area];
        byte[] terrain = new byte[area];
        tile.copyRenderDataTo(heights, rounded, water, terrain);
        for (int y = 0; y < Constants.TILE_SIZE; y++) {
            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                int index = x + y * Constants.TILE_SIZE;
                float expected = minHeight + value + (x % 7) + 0.25f;
                assertEquals(expected, tile.getHeight(x, y), 0f);
                assertEquals(expected, heights[index], 0f);
                assertEquals(Math.round(expected), rounded[index]);
                assertEquals(minHeight + value + (y % 11), tile.getWaterLevel(x, y));
                assertEquals(tile.getWaterLevel(x, y), water[index]);
                assertSame(value == 17 ? Terrain.GRASS : Terrain.STONE, tile.getTerrain(x, y));
                assertEquals(tile.getTerrain(x, y).ordinal(), terrain[index] & 255);
                assertEquals(value, tile.getLayerValue(Biome.INSTANCE, x, y));
                assertEquals((x + y + value) % 3 == 0, tile.getBitLayerValue(FloodWithLava.INSTANCE, x, y));
            }
        }
    }
}
