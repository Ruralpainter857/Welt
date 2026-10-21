package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.layers.Biome;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.layers.Jungle;
import org.pepsoft.worldpainter.layers.Layer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void emptyLayerMapsAreSharedUntilARealEditAndSupportUndo() {
        final Tile first = new Tile(0, 0, 0, 256);
        final Tile second = new Tile(1, 0, 0, 256);
        assertSame(first.layerData, second.layerData);
        assertSame(first.bitLayerData, second.bitLayerData);

        first.setBitLayerValue(Frost.INSTANCE, 3, 5, false);
        first.setLayerValue(Biome.INSTANCE, 3, 5, Biome.INSTANCE.getDefaultValue());
        first.clearLayerData(3, 5, null);
        assertSame("writing defaults should keep the shared bit map", first.bitLayerData, second.bitLayerData);
        assertSame("writing defaults should keep the shared value map", first.layerData, second.layerData);

        final UndoManager undoManager = new UndoManager();
        first.register(undoManager);
        second.register(undoManager);
        undoManager.armSavePoint();
        first.setBitLayerValue(Frost.INSTANCE, 3, 5, true);
        first.setLayerValue(Biome.INSTANCE, 3, 5, 1);

        assertNotSame(first.bitLayerData, second.bitLayerData);
        assertNotSame(first.layerData, second.layerData);
        assertTrue(first.getBitLayerValue(Frost.INSTANCE, 3, 5));
        assertFalse(second.getBitLayerValue(Frost.INSTANCE, 3, 5));
        assertEquals(1, first.getLayerValue(Biome.INSTANCE, 3, 5));
        assertEquals(Biome.INSTANCE.getDefaultValue(), second.getLayerValue(Biome.INSTANCE, 3, 5));

        assertTrue(undoManager.undo());
        assertFalse(first.getBitLayerValue(Frost.INSTANCE, 3, 5));
        assertEquals(Biome.INSTANCE.getDefaultValue(), first.getLayerValue(Biome.INSTANCE, 3, 5));
        assertTrue(undoManager.redo());
        assertTrue(first.getBitLayerValue(Frost.INSTANCE, 3, 5));
        assertEquals(1, first.getLayerValue(Biome.INSTANCE, 3, 5));
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
    public void generatedTilesShareUniformHeightsAndDetachForUndoableEdits() {
        final Tile first = new Tile(0, 0, 0, 256);
        final Tile second = new Tile(1, 0, 0, 256);
        final float[] heights = new float[Constants.TILE_SIZE * Constants.TILE_SIZE];
        Arrays.fill(heights, 64.25f);
        final int[] integerHeights = new int[heights.length];
        initializeGeneratedTile(first, heights, integerHeights, 63);
        initializeGeneratedTile(second, heights, integerHeights, 63);

        assertSame(first.heightMap, second.heightMap);
        assertEquals(64.25f, first.getHeight(20, 30), 0.0f);
        assertEquals(64.25f, second.getHeight(20, 30), 0.0f);

        final UndoManager undoManager = new UndoManager();
        first.register(undoManager);
        second.register(undoManager);
        undoManager.armSavePoint();
        first.setHeight(20, 30, 71.5f);

        assertNotSame(first.heightMap, second.heightMap);
        assertEquals(71.5f, first.getHeight(20, 30), 0.0f);
        assertEquals(64.25f, second.getHeight(20, 30), 0.0f);
        assertTrue(undoManager.undo());
        assertEquals(64.25f, first.getHeight(20, 30), 0.0f);
        assertTrue(undoManager.redo());
        assertEquals(71.5f, first.getHeight(20, 30), 0.0f);
        assertEquals(64.25f, second.getHeight(20, 30), 0.0f);
    }

    @Test
    public void generatedUniformByteLayerBuffersShareAndDetachForUndo() {
        final Tile first = new Tile(0, 0, 0, 256);
        final Tile second = new Tile(1, 0, 0, 256);
        final byte[] values = new byte[Constants.TILE_SIZE * Constants.TILE_SIZE];
        Arrays.fill(values, (byte) 7);
        initializeLayer(first, Biome.INSTANCE, values);
        initializeLayer(second, Biome.INSTANCE, values);

        assertSame(first.layerData.get(Biome.INSTANCE), second.layerData.get(Biome.INSTANCE));
        assertEquals(7, first.getLayerValue(Biome.INSTANCE, 0, 0));
        assertEquals(7, second.getLayerValue(Biome.INSTANCE, 0, 0));

        final UndoManager undoManager = new UndoManager();
        first.register(undoManager);
        second.register(undoManager);
        undoManager.armSavePoint();
        first.setLayerValue(Biome.INSTANCE, 17, 29, 9);

        assertNotSame(first.layerData.get(Biome.INSTANCE), second.layerData.get(Biome.INSTANCE));
        assertEquals(9, first.getLayerValue(Biome.INSTANCE, 17, 29));
        assertEquals(7, second.getLayerValue(Biome.INSTANCE, 17, 29));
        assertTrue(undoManager.undo());
        assertEquals(7, first.getLayerValue(Biome.INSTANCE, 17, 29));
        assertEquals(7, second.getLayerValue(Biome.INSTANCE, 17, 29));
        assertTrue(undoManager.redo());
        assertEquals(9, first.getLayerValue(Biome.INSTANCE, 17, 29));
        assertEquals(7, second.getLayerValue(Biome.INSTANCE, 17, 29));
    }

    @Test
    public void generatedUniformNibbleLayerBuffersShareAndDetach() {
        final Tile first = new Tile(0, 0, 0, 256);
        final Tile second = new Tile(1, 0, 0, 256);
        final byte[] values = new byte[Constants.TILE_SIZE * Constants.TILE_SIZE];
        Arrays.fill(values, (byte) 5);
        initializeLayer(first, Jungle.INSTANCE, values);
        initializeLayer(second, Jungle.INSTANCE, values);

        assertSame(first.layerData.get(Jungle.INSTANCE), second.layerData.get(Jungle.INSTANCE));
        assertEquals(5, first.getLayerValue(Jungle.INSTANCE, 0, 0));
        first.setLayerValue(Jungle.INSTANCE, 17, 29, 9);
        assertNotSame(first.layerData.get(Jungle.INSTANCE), second.layerData.get(Jungle.INSTANCE));
        assertEquals(9, first.getLayerValue(Jungle.INSTANCE, 17, 29));
        assertEquals(5, second.getLayerValue(Jungle.INSTANCE, 17, 29));
    }

    @Test
    public void clearingOneUniformSharedLayerCellDetachesItsTile() {
        final Tile first = new Tile(0, 0, 0, 256);
        final Tile second = new Tile(1, 0, 0, 256);
        final byte[] values = new byte[Constants.TILE_SIZE * Constants.TILE_SIZE];
        Arrays.fill(values, (byte) 7);
        initializeLayer(first, Biome.INSTANCE, values);
        initializeLayer(second, Biome.INSTANCE, values);
        assertSame(first.layerData.get(Biome.INSTANCE), second.layerData.get(Biome.INSTANCE));

        first.clearLayerData(17, 29, null);

        assertNotSame(first.layerData.get(Biome.INSTANCE), second.layerData.get(Biome.INSTANCE));
        assertEquals(Biome.INSTANCE.getDefaultValue(), first.getLayerValue(Biome.INSTANCE, 17, 29));
        assertEquals(7, second.getLayerValue(Biome.INSTANCE, 17, 29));
    }

    @Test
    public void uniformLayerArraysRemainIndependentAcrossSerialization() throws Exception {
        final Tile first = new Tile(0, 0, 0, 256);
        final Tile second = new Tile(1, 0, 0, 256);
        final byte[] values = new byte[Constants.TILE_SIZE * Constants.TILE_SIZE];
        Arrays.fill(values, (byte) 7);
        initializeLayer(first, Biome.INSTANCE, values);
        initializeLayer(second, Biome.INSTANCE, values);

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(new Tile[] {first, second});
        }
        assertSame(first.layerData.get(Biome.INSTANCE), second.layerData.get(Biome.INSTANCE));

        final Tile[] loaded;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            loaded = (Tile[]) input.readObject();
        }
        assertSame(loaded[0].layerData.get(Biome.INSTANCE), loaded[1].layerData.get(Biome.INSTANCE));
        loaded[0].setLayerValue(Biome.INSTANCE, 4, 5, 9);
        assertEquals(9, loaded[0].getLayerValue(Biome.INSTANCE, 4, 5));
        assertEquals(7, loaded[1].getLayerValue(Biome.INSTANCE, 4, 5));
    }

    @Test
    public void tallGeneratedTilesShareUniformHeightsAndDetachForEdits() {
        final Tile first = new Tile(0, 0, 0, 512);
        final Tile second = new Tile(1, 0, 0, 512);
        final float[] heights = new float[Constants.TILE_SIZE * Constants.TILE_SIZE];
        Arrays.fill(heights, 64.25f);
        final int[] integerHeights = new int[heights.length];
        initializeGeneratedTile(first, heights, integerHeights, 0);
        initializeGeneratedTile(second, heights, integerHeights, 0);

        assertSame(first.tallHeightMap, second.tallHeightMap);
        first.setHeight(20, 30, 71.5f);
        assertNotSame(first.tallHeightMap, second.tallHeightMap);
        assertEquals(71.5f, first.getHeight(20, 30), 0.0f);
        assertEquals(64.25f, second.getHeight(20, 30), 0.0f);
    }

    @Test
    public void serializedGeneratedUniformHeightsAreSharedAgainOnRead() throws Exception {
        final Tile first = new Tile(0, 0, 0, 256);
        final Tile second = new Tile(1, 0, 0, 256);
        final Tile tallFirst = new Tile(2, 0, 0, 512);
        final Tile tallSecond = new Tile(3, 0, 0, 512);
        final float[] heights = new float[Constants.TILE_SIZE * Constants.TILE_SIZE];
        Arrays.fill(heights, 64.25f);
        final int[] integerHeights = new int[heights.length];
        initializeGeneratedTile(first, heights, integerHeights, 63);
        initializeGeneratedTile(second, heights, integerHeights, 63);
        initializeGeneratedTile(tallFirst, heights, integerHeights, 0);
        initializeGeneratedTile(tallSecond, heights, integerHeights, 0);

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(new Tile[] {first, second, tallFirst, tallSecond});
        }
        final Tile[] loaded;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            loaded = (Tile[]) input.readObject();
        }

        assertSame(loaded[0].heightMap, loaded[1].heightMap);
        assertSame(loaded[2].tallHeightMap, loaded[3].tallHeightMap);
        loaded[0].setHeight(4, 5, 37.0f);
        loaded[2].setHeight(4, 5, 37.0f);
        assertEquals(37.0f, loaded[0].getHeight(4, 5), 0.0f);
        assertEquals(64.25f, loaded[1].getHeight(4, 5), 0.0f);
        assertEquals(37.0f, loaded[2].getHeight(4, 5), 0.0f);
        assertEquals(64.25f, loaded[3].getHeight(4, 5), 0.0f);
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
        assertSame(loaded[0].layerData, loaded[1].layerData);
        assertSame(loaded[0].bitLayerData, loaded[1].bitLayerData);
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

    @Test
    public void benchmarkGeneratedUniformHeightMemoryWhenRequested() throws Exception {
        if (!Boolean.getBoolean("welt.tile.uniform-height.benchmark")) {
            return;
        }
        final int tileCount = Integer.getInteger("welt.tile.uniform-height.benchmark.tiles", 4096);
        final float height = Float.parseFloat(System.getProperty("welt.tile.uniform-height.benchmark.height", "64.25"));
        final List<Tile> tiles = new ArrayList<>(tileCount);
        final float[] heights = new float[Constants.TILE_SIZE * Constants.TILE_SIZE];
        Arrays.fill(heights, height);
        final int[] intHeights = new int[heights.length];
        final AtomicLong elapsedNanos = new AtomicLong();
        final BenchmarkMemorySupport.Snapshot memory = BenchmarkMemorySupport.measure(() -> {
            final long start = System.nanoTime();
            for (int i = 0; i < tileCount; i++) {
                final Tile tile = new Tile(i, 0, 0, 256);
                initializeGeneratedTile(tile, heights, intHeights, 63);
                tiles.add(tile);
            }
            elapsedNanos.set(System.nanoTime() - start);
        });
        final boolean shared = tiles.get(0).heightMap == tiles.get(tileCount - 1).heightMap;
        final long payloadBytesSaved = shared
                ? (long) (tileCount - 1) * heights.length * Short.BYTES : 0L;
        assertEquals(height, tiles.get(0).getHeight(0, 0), 0.0f);
        System.out.printf("Generated flat-height tiles=%d, height=%.2f, elapsed=%.3f ms, "
                        + "sharedHeight=%s, estimatedPayloadSaved=%d bytes (%.1f MiB), memory=[%s]%n",
                tileCount, height, elapsedNanos.get() / 1_000_000.0, shared,
                payloadBytesSaved, payloadBytesSaved / (1024.0 * 1024.0), memory);
    }

    @Test
    public void benchmarkGeneratedUniformLayerMemoryWhenRequested() throws Exception {
        if (!Boolean.getBoolean("welt.tile.uniform-layer.benchmark")) {
            return;
        }
        final int tileCount = Integer.getInteger("welt.tile.uniform-layer.benchmark.tiles", 4096);
        final byte[] values = new byte[Constants.TILE_SIZE * Constants.TILE_SIZE];
        Arrays.fill(values, (byte) 7);
        final List<Tile> tiles = new ArrayList<>(tileCount);
        final AtomicLong elapsedNanos = new AtomicLong();
        final BenchmarkMemorySupport.Snapshot memory = BenchmarkMemorySupport.measure(() -> {
            final long start = System.nanoTime();
            for (int i = 0; i < tileCount; i++) {
                final Tile tile = new Tile(i, 0, 0, 256);
                tile.inhibitEvents();
                try {
                    tile.initializeLayerValues(Biome.INSTANCE, values);
                } finally {
                    tile.releaseEvents();
                }
                tiles.add(tile);
            }
            elapsedNanos.set(System.nanoTime() - start);
        });
        assertEquals(7, tiles.get(0).getLayerValue(Biome.INSTANCE, 0, 0));
        assertEquals(7, tiles.get(tileCount - 1).getLayerValue(Biome.INSTANCE,
                Constants.TILE_SIZE - 1, Constants.TILE_SIZE - 1));
        final long payloadBytesSaved = (long) (tileCount - 1) * values.length;
        System.out.printf("Generated uniform-byte-layer tiles=%d, elapsed=%.3f ms, "
                        + "estimatedArrayPayloadSaved=%d bytes (%.1f MiB), memory=[%s]%n",
                tileCount, elapsedNanos.get() / 1_000_000.0,
                payloadBytesSaved, payloadBytesSaved / (1024.0 * 1024.0), memory);
    }

    private static void initializeGeneratedTile(Tile tile, float[] heights, int[] intHeights, int waterLevel) {
        tile.inhibitEvents();
        try {
            tile.initializeHeightAndWaterLevels(heights, waterLevel, intHeights);
        } finally {
            tile.releaseEvents();
        }
    }

    private static void initializeLayer(Tile tile, Layer layer, byte[] values) {
        tile.inhibitEvents();
        try {
            tile.initializeLayerValues(layer, values);
        } finally {
            tile.releaseEvents();
        }
    }
}
