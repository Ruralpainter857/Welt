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
        assertEquals(Terrain.GRASS, first.getTerrain(0, 0));

        final Terrain otherTerrain = Terrain.values()[1];
        first.setTerrain(17, 29, otherTerrain);

        assertNotSame(first.terrain, second.terrain);
        assertEquals(otherTerrain, first.getTerrain(17, 29));
        assertEquals(Terrain.GRASS, second.getTerrain(17, 29));
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
        assertEquals(otherTerrain, first.getTerrain(8, 9));
        assertEquals(Terrain.GRASS, second.getTerrain(8, 9));

        assertTrue(undoManager.undo());
        assertEquals(Terrain.GRASS, first.getTerrain(8, 9));
        assertEquals(Terrain.GRASS, second.getTerrain(8, 9));

        assertTrue(undoManager.redo());
        assertEquals(otherTerrain, first.getTerrain(8, 9));
        assertEquals(Terrain.GRASS, second.getTerrain(8, 9));
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
        loaded[0].setTerrain(4, 5, Terrain.values()[1]);
        assertEquals(Terrain.GRASS, loaded[1].getTerrain(4, 5));
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
        final long terrainBytesSaved = (long) (tileCount - 1) * Constants.TILE_SIZE * Constants.TILE_SIZE;
        System.out.printf("Uniform terrain tiles=%d, shared terrain payload saved=%d bytes (%.1f MiB), "
                        + "memory=[%s]%n",
                tileCount, terrainBytesSaved, terrainBytesSaved / (1024.0 * 1024.0), memory);
    }
}
