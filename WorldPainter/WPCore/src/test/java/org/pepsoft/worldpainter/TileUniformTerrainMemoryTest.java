package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TileUniformTerrainMemoryTest {
    @Test
    public void freshTilesShareDefaultTerrainUntilEitherTileChanges() {
        final Tile first = new Tile(0, 0, 0, 256);
        final Tile second = new Tile(1, 0, 0, 256);
        assertSame(first.terrain, second.terrain);
        assertSame(first.heightMap, second.heightMap);
        assertSame(first.waterLevel, second.waterLevel);
        assertEquals(Terrain.GRASS, first.getTerrain(0, 0));

        final Terrain otherTerrain = Terrain.values()[1];
        first.setTerrain(17, 29, otherTerrain);

        assertNotSame(first.terrain, second.terrain);
        assertEquals(otherTerrain, first.getTerrain(17, 29));
        assertEquals(Terrain.GRASS, second.getTerrain(17, 29));
    }

    @Test
    public void changingHeightAndWaterDetachesOnlyTheEditedTile() {
        final Tile first = new Tile(0, 0, 0, 256);
        final Tile second = new Tile(1, 0, 0, 256);
        assertSame(first.heightMap, second.heightMap);
        assertSame(first.waterLevel, second.waterLevel);

        first.setHeight(17, 29, 42.0f);
        first.setWaterLevel(17, 29, 48);

        assertNotSame(first.heightMap, second.heightMap);
        assertNotSame(first.waterLevel, second.waterLevel);
        assertEquals(42.0f, first.getHeight(17, 29), 0.0f);
        assertEquals(0.0f, second.getHeight(17, 29), 0.0f);
        assertEquals(48, first.getWaterLevel(17, 29));
        assertEquals(0, second.getWaterLevel(17, 29));
    }

    @Test
    public void generatedTilesShareUniformWaterLevelsAndDetachForEdits() {
        final Tile first = new Tile(0, 0, 0, 256);
        final Tile second = new Tile(1, 0, 0, 256);
        final UndoManager undoManager = new UndoManager();
        first.register(undoManager);
        second.register(undoManager);
        final float[] heights = new float[Constants.TILE_SIZE * Constants.TILE_SIZE];
        final int[] integerHeights = new int[heights.length];
        initializeGeneratedTile(first, heights, integerHeights, 63);
        initializeGeneratedTile(second, heights, integerHeights, 63);

        assertSame(first.waterLevel, second.waterLevel);
        assertEquals(63, first.getWaterLevel(20, 30));
        undoManager.armSavePoint();
        first.setWaterLevel(20, 30, 70);
        assertNotSame(first.waterLevel, second.waterLevel);
        assertEquals(70, first.getWaterLevel(20, 30));
        assertEquals(63, second.getWaterLevel(20, 30));
        assertTrue(undoManager.undo());
        assertEquals(63, first.getWaterLevel(20, 30));
        assertEquals(63, second.getWaterLevel(20, 30));
        assertTrue(undoManager.redo());
        assertEquals(70, first.getWaterLevel(20, 30));
        assertEquals(63, second.getWaterLevel(20, 30));
    }

    @Test
    public void tallTilesShareAndDetachTheirDefaultBuffers() {
        final Tile first = new Tile(0, 0, 0, 512);
        final Tile second = new Tile(1, 0, 0, 512);
        final UndoManager undoManager = new UndoManager();
        first.register(undoManager);
        second.register(undoManager);
        undoManager.armSavePoint();
        assertSame(first.tallHeightMap, second.tallHeightMap);
        assertSame(first.tallWaterLevel, second.tallWaterLevel);

        first.setHeight(3, 5, 73.0f);
        first.setWaterLevel(3, 5, 81);

        assertNotSame(first.tallHeightMap, second.tallHeightMap);
        assertNotSame(first.tallWaterLevel, second.tallWaterLevel);
        assertEquals(73.0f, first.getHeight(3, 5), 0.0f);
        assertEquals(0.0f, second.getHeight(3, 5), 0.0f);
        assertEquals(81, first.getWaterLevel(3, 5));
        assertEquals(0, second.getWaterLevel(3, 5));

        assertTrue(undoManager.undo());
        assertEquals(0.0f, first.getHeight(3, 5), 0.0f);
        assertEquals(0, first.getWaterLevel(3, 5));
        assertTrue(undoManager.redo());
        assertEquals(73.0f, first.getHeight(3, 5), 0.0f);
        assertEquals(81, first.getWaterLevel(3, 5));
    }

    @Test
    public void generatedTallTilesShareZeroWaterLevelsWithUndoSupport() {
        final Tile first = new Tile(0, 0, 0, 512);
        final Tile second = new Tile(1, 0, 0, 512);
        final UndoManager undoManager = new UndoManager();
        first.register(undoManager);
        second.register(undoManager);
        final float[] heights = new float[Constants.TILE_SIZE * Constants.TILE_SIZE];
        final int[] integerHeights = new int[heights.length];
        initializeGeneratedTile(first, heights, integerHeights, 0);
        initializeGeneratedTile(second, heights, integerHeights, 0);
        assertSame(first.tallWaterLevel, second.tallWaterLevel);

        undoManager.armSavePoint();
        first.setWaterLevel(11, 13, 37);
        assertNotSame(first.tallWaterLevel, second.tallWaterLevel);
        assertEquals(0, second.getWaterLevel(11, 13));
        assertTrue(undoManager.undo());
        assertEquals(0, first.getWaterLevel(11, 13));
        assertTrue(undoManager.redo());
        assertEquals(37, first.getWaterLevel(11, 13));
        assertEquals(0, second.getWaterLevel(11, 13));
    }

    @Test
    public void simpleGrassTileGenerationKeepsTheDefaultBufferShared() {
        final TileFactory factory = TestData.createTileFactory(64);
        final Tile first = factory.createTile(0, 0);
        final Tile second = factory.createTile(1, 0);

        assertSame(first.terrain, second.terrain);
        assertEquals(Terrain.GRASS, first.getTerrain(63, 71));
        assertEquals(Terrain.GRASS, second.getTerrain(28, 112));
    }

    @Test
    public void undoAndRedoKeepSharedTilesIndependent() {
        final Tile first = new Tile(0, 0, 0, 256);
        final Tile second = new Tile(1, 0, 0, 256);
        final UndoManager undoManager = new UndoManager();
        first.register(undoManager);
        second.register(undoManager);
        undoManager.armSavePoint();

        final Terrain otherTerrain = Terrain.values()[1];
        first.setTerrain(8, 9, otherTerrain);
        first.setHeight(8, 9, 42.0f);
        first.setWaterLevel(8, 9, 48);
        assertEquals(otherTerrain, first.getTerrain(8, 9));
        assertEquals(Terrain.GRASS, second.getTerrain(8, 9));
        assertEquals(42.0f, first.getHeight(8, 9), 0.0f);
        assertEquals(0.0f, second.getHeight(8, 9), 0.0f);
        assertEquals(48, first.getWaterLevel(8, 9));
        assertEquals(0, second.getWaterLevel(8, 9));

        assertTrue(undoManager.undo());
        assertEquals(Terrain.GRASS, first.getTerrain(8, 9));
        assertEquals(Terrain.GRASS, second.getTerrain(8, 9));
        assertEquals(0.0f, first.getHeight(8, 9), 0.0f);
        assertEquals(0, first.getWaterLevel(8, 9));

        assertTrue(undoManager.redo());
        assertEquals(otherTerrain, first.getTerrain(8, 9));
        assertEquals(Terrain.GRASS, second.getTerrain(8, 9));
        assertEquals(42.0f, first.getHeight(8, 9), 0.0f);
        assertEquals(0.0f, second.getHeight(8, 9), 0.0f);
        assertEquals(48, first.getWaterLevel(8, 9));
        assertEquals(0, second.getWaterLevel(8, 9));
    }

    @Test
    public void serializationKeepsTilesIndependentAndReSharesDefaultsOnRead() throws Exception {
        final Tile first = new Tile(0, 0, 0, 256);
        final Tile second = new Tile(1, 0, 0, 256);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(new Tile[]{first, second});
        }
        assertSame(first.terrain, second.terrain);

        final Tile[] loaded;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            loaded = (Tile[]) input.readObject();
        }
        assertSame(loaded[0].terrain, loaded[1].terrain);
        assertSame(loaded[0].heightMap, loaded[1].heightMap);
        assertSame(loaded[0].waterLevel, loaded[1].waterLevel);
        loaded[0].setTerrain(4, 5, Terrain.values()[1]);
        loaded[0].setHeight(4, 5, 37.0f);
        loaded[0].setWaterLevel(4, 5, 51);
        assertEquals(Terrain.GRASS, loaded[1].getTerrain(4, 5));
        assertEquals(0.0f, loaded[1].getHeight(4, 5), 0.0f);
        assertEquals(0, loaded[1].getWaterLevel(4, 5));
    }

    @Test
    public void serializationReSharesTallDefaultBuffersOnRead() throws Exception {
        final Tile first = new Tile(0, 0, 0, 512);
        final Tile second = new Tile(1, 0, 0, 512);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(new Tile[]{first, second});
        }

        final Tile[] loaded;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            loaded = (Tile[]) input.readObject();
        }
        assertSame(loaded[0].tallHeightMap, loaded[1].tallHeightMap);
        assertSame(loaded[0].tallWaterLevel, loaded[1].tallWaterLevel);

        loaded[0].setHeight(4, 5, 37.0f);
        loaded[0].setWaterLevel(4, 5, 51);
        assertEquals(0.0f, loaded[1].getHeight(4, 5), 0.0f);
        assertEquals(0, loaded[1].getWaterLevel(4, 5));
    }

    @Test
    public void serializationReSharesGeneratedUniformWaterLevelsOnRead() throws Exception {
        final Tile first = new Tile(0, 0, 0, 256);
        final Tile second = new Tile(1, 0, 0, 256);
        final float[] heights = new float[Constants.TILE_SIZE * Constants.TILE_SIZE];
        final int[] integerHeights = new int[heights.length];
        initializeGeneratedTile(first, heights, integerHeights, 63);
        initializeGeneratedTile(second, heights, integerHeights, 63);

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(new Tile[]{first, second});
        }
        final Tile[] loaded;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            loaded = (Tile[]) input.readObject();
        }
        assertSame(loaded[0].waterLevel, loaded[1].waterLevel);
        loaded[0].setWaterLevel(4, 5, 51);
        assertEquals(51, loaded[0].getWaterLevel(4, 5));
        assertEquals(63, loaded[1].getWaterLevel(4, 5));
    }

    @Test
    public void benchmarkSharedTerrainMemoryWhenRequested() throws Exception {
        if (!Boolean.getBoolean("welt.tile.uniform-terrain.benchmark")) {
            return;
        }
        final int tileCount = Integer.getInteger("welt.tile.uniform-terrain.benchmark.tiles", 4096);
        final List<Tile> tiles = new ArrayList<>(tileCount);
        final BenchmarkMemorySupport.Snapshot memory = BenchmarkMemorySupport.measure(() -> {
            for (int i = 0; i < tileCount; i++) {
                tiles.add(new Tile(i, 0, 0, 256));
            }
        });
        assertSame(tiles.get(0).terrain, tiles.get(tileCount - 1).terrain);
        assertSame(tiles.get(0).heightMap, tiles.get(tileCount - 1).heightMap);
        assertSame(tiles.get(0).waterLevel, tiles.get(tileCount - 1).waterLevel);
        final long bytesPerCell = Short.BYTES + 2L * Byte.BYTES;
        final long tilePayloadBytesSaved = (long) (tileCount - 1) * Constants.TILE_SIZE * Constants.TILE_SIZE * bytesPerCell;
        System.out.printf("Uniform terrain tiles=%d, shared terrain/height/water payload saved=%d bytes (%.1f MiB), "
                        + "memory=[%s]%n",
                tileCount, tilePayloadBytesSaved, tilePayloadBytesSaved / (1024.0 * 1024.0), memory);
    }

    @Test
    public void benchmarkGeneratedUniformWaterMemoryWhenRequested() throws Exception {
        if (!Boolean.getBoolean("welt.tile.uniform-terrain.benchmark")) {
            return;
        }
        final int tileCount = Integer.getInteger("welt.tile.uniform-terrain.benchmark.tiles", 4096);
        final List<Tile> tiles = new ArrayList<>(tileCount);
        final float[] heights = new float[Constants.TILE_SIZE * Constants.TILE_SIZE];
        final int[] intHeights = new int[heights.length];
        final BenchmarkMemorySupport.Snapshot memory = BenchmarkMemorySupport.measure(() -> {
            for (int i = 0; i < tileCount; i++) {
                final Tile tile = new Tile(i, 0, 0, 256);
                initializeGeneratedTile(tile, heights, intHeights, 63);
                tiles.add(tile);
            }
        });
        assertSame(tiles.get(0).waterLevel, tiles.get(tileCount - 1).waterLevel);
        final long waterBytesSaved = (long) (tileCount - 1) * Constants.TILE_SIZE * Constants.TILE_SIZE;
        System.out.printf("Generated uniform-water tiles=%d, shared water payload saved=%d bytes (%.1f MiB), "
                        + "memory=[%s]%n",
                tileCount, waterBytesSaved, waterBytesSaved / (1024.0 * 1024.0), memory);
    }

    private static void initializeGeneratedTile(Tile tile, float[] heights, int[] intHeights, int waterLevel) {
        tile.inhibitEvents();
        try {
            tile.initializeHeightAndWaterLevels(heights, waterLevel, intHeights);
        } finally {
            tile.releaseEvents();
        }
    }
}
