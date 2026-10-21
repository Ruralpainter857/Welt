package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

public class VerticalResizeParityTest {
    @Test
    public void allStorageTransitionsAndTransformsMatchJava() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        try {
            int[][] ranges = {{0, 256}, {-64, 320}, {-128, 128}, {-512, 1024}, {0, 65536}};
            HeightTransform[] transforms = {HeightTransform.IDENTITY, HeightTransform.get(125, 10),
                    HeightTransform.get(75, -33), HeightTransform.get(-50, 70), HeightTransform.get(0, -1)};
            for (int[] oldRange : ranges) for (int[] newRange : ranges) for (HeightTransform transform : transforms) {
                Tile java = fixture(oldRange[0], oldRange[1]), rust = fixture(oldRange[0], oldRange[1]);
                System.setProperty(Native.GEN_KEY, "false");
                java.setMinMaxHeight(newRange[0], newRange[1], transform);
                System.setProperty(Native.GEN_KEY, "true");
                rust.setMinMaxHeight(newRange[0], newRange[1], transform);
                assertSameData(java, rust);
            }
            assertTrue(VerticalResizeAccess.resize(VerticalResizeAccess.prepare(-64, -128, 512,
                    true, true, HeightTransform.get(125, 10))));
        } finally { restore(previous); }
    }

    @Test
    public void notificationsAndUndoRegistrationArePreserved() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        try {
            for (int[] range : new int[][] {{-128, 512}, {0, 256}}) {
                Tile[] tiles = {fixture(-64, 320), fixture(-64, 320)};
                int[][] notices = {new int[2], new int[2]};
                for (int mode = 0; mode < 2; mode++) {
                    System.setProperty(Native.GEN_KEY, mode == 0 ? "false" : "true");
                    Tile tile = tiles[mode]; int[] counters = notices[mode];
                    tile.addListener((Tile.Listener) Proxy.newProxyInstance(Tile.Listener.class.getClassLoader(),
                            new Class<?>[] {Tile.Listener.class}, (p, m, a) -> {
                                if (m.getName().equals("heightMapChanged")) counters[0]++;
                                if (m.getName().equals("waterLevelChanged")) counters[1]++;
                                return null;
                            }));
                    UndoManager undo = new UndoManager(); tile.register(undo);
                    tile.setMinMaxHeight(range[0], range[1], HeightTransform.get(125, 10));
                    assertArrayEquals(new int[] {1, 1}, counters);
                    int raw = tile.getRawHeight(9, 11), water = tile.getWaterLevel(9, 11);
                    int changedWater = water == tile.getMaxHeight() - 1 ? water - 1 : water + 1;
                    undo.armSavePoint();
                    tile.setRawHeight(9, 11, raw + 100);
                    tile.setWaterLevel(9, 11, changedWater);
                    assertTrue(undo.undo());
                    assertEquals(raw, tile.getRawHeight(9, 11));
                    assertEquals(water, tile.getWaterLevel(9, 11));
                    assertTrue(undo.redo());
                    assertEquals(raw + 100, tile.getRawHeight(9, 11));
                    assertEquals(changedWater, tile.getWaterLevel(9, 11));
                }
                assertArrayEquals(notices[0], notices[1]);
                assertSameData(tiles[0], tiles[1]);
            }
        } finally { restore(previous); }
    }

    @Test
    public void completeDimensionResizePreservesMetadataAndAllTiles() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        try {
            Dimension java = VerticalResizeBenchmark.fixture(), rust = VerticalResizeBenchmark.fixture();
            HeightTransform transform = HeightTransform.get(125, 10);
            System.setProperty(Native.GEN_KEY, "false");
            org.pepsoft.worldpainter.util.WorldUtils.resizeDimension(java, -128, 512, transform, true, null);
            System.setProperty(Native.GEN_KEY, "true");
            org.pepsoft.worldpainter.util.WorldUtils.resizeDimension(rust, -128, 512, transform, true, null);
            assertEquals(java.getMinHeight(), rust.getMinHeight());
            assertEquals(java.getMaxHeight(), rust.getMaxHeight());
            assertEquals(java.getCeilingHeight(), rust.getCeilingHeight());
            assertEquals(java.getTileCount(), rust.getTileCount());
            for (Tile tile : java.getTiles()) assertSameData(tile, rust.getTile(tile.getX(), tile.getY()));
            assertSameData(java.getTileFactory().createTile(9, 9), rust.getTileFactory().createTile(9, 9));
        } finally { restore(previous); }
    }

    @Test
    public void sharedDefaultBuffersAndWorkersAreIndependent() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) futures.add(workers.submit(() -> {
                Tile blank = new Tile(0, 0, 0, 256), untouched = new Tile(1, 0, 0, 256);
                blank.setMinMaxHeight(-64, 320, HeightTransform.get(150, 20));
                assertEquals(20f, blank.getHeight(0, 0), 0f);
                assertEquals(20, blank.getWaterLevel(0, 0));
                assertEquals(0, untouched.getRawHeight(0, 0));
                assertEquals(0, untouched.getWaterLevel(0, 0));
            }));
            for (var future : futures) future.get();
        } finally { workers.shutdownNow(); restore(previous); }
    }

    private static Tile fixture(int min, int max) {
        Tile tile = new Tile(-3, 2, min, max);
        tile.inhibitEvents();
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
            tile.setRawHeight(x, y, ((x * 10007 + y * 317) % (max - min)) * 256 + (x + y) % 256);
            tile.setWaterLevel(x, y, min + (x * 331 + y * 71) % (max - min));
        }
        tile.setTerrain(17, 21, Terrain.CUSTOM_1);
        tile.setBitLayerValue(org.pepsoft.worldpainter.layers.Frost.INSTANCE, 17, 21, true);
        tile.setLayerValue(TileRotationParityTest.NIBBLES, 17, 21, 9);
        tile.releaseEvents();
        return tile;
    }

    private static void assertSameData(Tile expected, Tile actual) {
        assertEquals(expected.getMinHeight(), actual.getMinHeight());
        assertEquals(expected.getMaxHeight(), actual.getMaxHeight());
        assertEquals(expected.getLayers(), actual.getLayers());
        assertEquals(expected.getTerrain(17, 21), actual.getTerrain(17, 21));
        assertEquals(expected.getBitLayerValue(org.pepsoft.worldpainter.layers.Frost.INSTANCE, 17, 21),
                actual.getBitLayerValue(org.pepsoft.worldpainter.layers.Frost.INSTANCE, 17, 21));
        assertEquals(expected.getLayerValue(TileRotationParityTest.NIBBLES, 17, 21),
                actual.getLayerValue(TileRotationParityTest.NIBBLES, 17, 21));
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
            assertEquals("height " + x + "," + y, expected.getRawHeight(x, y), actual.getRawHeight(x, y));
            assertEquals("water " + x + "," + y, expected.getWaterLevel(x, y), actual.getWaterLevel(x, y));
        }
    }

    private static void restore(String previous) {
        if (previous == null) System.clearProperty(Native.GEN_KEY);
        else System.setProperty(Native.GEN_KEY, previous);
    }
}
