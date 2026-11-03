package org.pepsoft.worldpainter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;

import java.awt.Point;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.*;

import static org.junit.Assert.*;

/** Scaled tiles share uniform water planes again without changing any stored value or serialized tile. */
public class ScalingWaterStorageTest {
    private String sharing, gen;

    @Before
    public void saveProperties() {
        sharing = System.getProperty("welt.tile.shareTallWater");
        gen = System.getProperty(Native.GEN_KEY);
    }

    @After
    public void restoreProperties() {
        restore("welt.tile.shareTallWater", sharing);
        restore(Native.GEN_KEY, gen);
    }

    @Test
    public void sharedOutputMatchesPrivateStorageOnJavaAndNativePaths() throws Exception {
        for (boolean nativeMode : modes()) {
            System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
            final HeightMapTileFactory factory = factory();
            final Map<Point, Tile> sources = world(factory);
            final Map<Point, byte[]> before = snapshot(sources);
            for (float scale : new float[] {0.5f, 1.3f, 2f}) {
                final String message = (nativeMode ? "native" : "java") + " scale " + scale;
                System.setProperty("welt.tile.shareTallWater", "false");
                final Map<Point, Tile> expected = scaleAll(sources, factory, scale);
                System.setProperty("welt.tile.shareTallWater", "true");
                final Map<Point, Tile> actual = scaleAll(sources, factory, scale);
                assertEquals(message, expected.keySet(), actual.keySet());
                final Set<short[]> privatePlanes = Collections.newSetFromMap(new IdentityHashMap<>());
                final Set<short[]> sharedPlanes = Collections.newSetFromMap(new IdentityHashMap<>());
                for (Point point : expected.keySet()) {
                    final Tile wanted = expected.get(point), tile = actual.get(point);
                    assertTile(wanted, tile, message + " tile " + point);
                    assertArrayEquals(message + " serialized " + point, serialize(wanted), serialize(tile));
                    privatePlanes.add(wanted.tallWaterLevel);
                    sharedPlanes.add(tile.tallWaterLevel);
                }
                assertEquals(message, expected.size(), privatePlanes.size());
                assertTrue(message + " uniform planes are shared", sharedPlanes.size() < actual.size());
                // The painted lake keeps a private plane with its exact samples
                final Tile lake = actual.get(new Point(0, 0));
                assertFalse(message, isUniform(lake.tallWaterLevel));
                for (Map.Entry<Point, byte[]> entry : before.entrySet()) {
                    assertArrayEquals(message + " source " + entry.getKey(), entry.getValue(), serialize(sources.get(entry.getKey())));
                }
            }
        }
    }

    @Test
    public void transformedWorldDetachesSharedWaterOnEditAndUndo() throws Exception {
        System.setProperty("welt.tile.shareTallWater", "true");
        final HeightMapTileFactory factory = factory();
        final Dimension dimension = new Dimension(new World2(TestData.PLATFORM, -64, 320), "Surface", 7L,
                factory, Dimension.Anchor.NORMAL_DETAIL);
        for (Tile tile : world(factory).values()) dimension.addTile(tile);
        dimension.transform(CoordinateTransform.getScalingInstance(1.5f), null);
        Tile first = null, second = null;
        for (Tile tile : dimension.getTiles()) {
            tile.ensureAllReadable();
            if (!isUniform(tile.tallWaterLevel)) continue;
            if (first == null) first = tile;
            else if (tile.tallWaterLevel == first.tallWaterLevel) { second = tile; break; }
        }
        assertNotNull("two scaled tiles share a uniform water plane", second);
        final UndoManager undo = new UndoManager(8);
        dimension.registerUndoManager(undo);
        dimension.armSavePoint();
        final int level = second.getWaterLevel(3, 4);
        dimension.getTileForEditing(first.getX(), first.getY()).setWaterLevel(3, 4, level + 9);
        assertEquals(level + 9, first.getWaterLevel(3, 4));
        assertEquals(level, first.getWaterLevel(4, 4));
        assertEquals(level, second.getWaterLevel(3, 4));
        assertTrue(undo.undo());
        assertEquals(level, dimension.getTile(first.getX(), first.getY()).getWaterLevel(3, 4));
        assertTrue(undo.redo());
        assertEquals(level + 9, dimension.getTile(first.getX(), first.getY()).getWaterLevel(3, 4));
        assertEquals(level, second.getWaterLevel(3, 4));
    }

    private static boolean[] modes() {
        return NativeLoader.areSlicesAvailable() ? new boolean[] {false, true} : new boolean[] {false};
    }

    private static HeightMapTileFactory factory() {
        return TileFactoryFactory.createNoiseTileFactory(11L, Terrain.GRASS, -64, 320, 58, 62, false, true, 20f, 1.0);
    }

    /** Five tall tiles at negative coordinates around a hole, with painted layers and one non-uniform lake. */
    private static Map<Point, Tile> world(HeightMapTileFactory factory) {
        final Map<Point, Tile> tiles = new HashMap<>();
        for (int tx = -2; tx <= 0; tx++) {
            for (int ty = -1; ty <= 0; ty++) {
                if (tx == -1 && ty == -1) continue;
                final Tile tile = factory.createTile(tx, ty);
                for (int x = 0; x < 128; x++) {
                    for (int y = 0; y < 128; y++) {
                        tile.setLayerValue(Resources.INSTANCE, x, y, (x + y * 3 + tx) & 15);
                        tile.setBitLayerValue(Frost.INSTANCE, x, y, (x + y) % 7 == 0);
                        tile.setLayerValue(Biome.INSTANCE, x, y, (x * 5 + y - ty) & 127);
                        if (tx == 0 && ty == 0 && x < 40) tile.setWaterLevel(x, y, 70 + y % 3);
                    }
                }
                tile.setBitLayerValue(Populate.INSTANCE, 40, 72, true);
                tiles.put(new Point(tx, ty), tile);
            }
        }
        return tiles;
    }

    private static Map<Point, Tile> scaleAll(Map<Point, Tile> sources, TileFactory factory, float scale) {
        final ScalingHelper helper = new ScalingHelper(sources, factory, scale);
        final Map<Point, Tile> result = new HashMap<>();
        for (Point point : helper.getTileCoords()) result.put(point, helper.createScaledTile(point.x, point.y));
        return result;
    }

    private static Map<Point, byte[]> snapshot(Map<Point, Tile> tiles) throws Exception {
        final Map<Point, byte[]> snapshot = new HashMap<>();
        for (Map.Entry<Point, Tile> entry : tiles.entrySet()) snapshot.put(entry.getKey(), serialize(entry.getValue()));
        return snapshot;
    }

    private static boolean isUniform(short[] plane) {
        for (short value : plane) if (value != plane[0]) return false;
        return true;
    }

    private static byte[] serialize(Tile tile) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(tile);
        }
        return bytes.toByteArray();
    }

    private static void assertTile(Tile expected, Tile actual, String message) {
        expected.ensureAllReadable();
        actual.ensureAllReadable();
        assertArrayEquals(message + " height", expected.heightMap, actual.heightMap);
        assertArrayEquals(message + " tall height", expected.tallHeightMap, actual.tallHeightMap);
        assertArrayEquals(message + " terrain", expected.terrain, actual.terrain);
        assertArrayEquals(message + " water", expected.waterLevel, actual.waterLevel);
        assertArrayEquals(message + " tall water", expected.tallWaterLevel, actual.tallWaterLevel);
        assertEquals(message + " bit layers", expected.bitLayerData, actual.bitLayerData);
        assertEquals(message + " layer keys", expected.layerData.keySet(), actual.layerData.keySet());
        for (Layer layer : expected.layerData.keySet()) {
            assertArrayEquals(message + " " + layer, expected.layerData.get(layer), actual.layerData.get(layer));
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }
}
