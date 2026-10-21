package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.undo.UndoManager;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import static org.junit.Assert.*;

public class AutoBiomeParityTest {
    @Test
    public void allTerrainLayerPrioritiesDepthsAndDimensionsMatchJava() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        MixedMaterial old = Terrain.getCustomMaterial(0);
        try {
            for (int customBiome : new int[] {-1, 2, 37, 132, 255}) {
                Terrain.setCustomMaterial(0, new MixedMaterial("biome parity",
                        new MixedMaterial.Row(org.pepsoft.minecraft.Material.STONE, 1000, 1f), customBiome, null));
                for (boolean tall : new boolean[] {false, true}) {
                    Tile tile = fixture(tall);
                    for (Dimension.Anchor anchor : new Dimension.Anchor[] {Dimension.Anchor.NORMAL_DETAIL,
                            Dimension.Anchor.NETHER_DETAIL, Dimension.Anchor.END_DETAIL}) {
                        Dimension dimension = new Dimension(TestData.WORLD, "Parity", 0, TestData.createTileFactory(62), anchor);
                        int[] expected = new int[16384];
                        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++)
                            expected[x + y * 128] = tile.getLayerValue(Biome.INSTANCE, x, y) == 255
                                    ? dimension.getAutoBiome(tile, x, y, 1) : tile.getLayerValue(Biome.INSTANCE, x, y);
                        System.setProperty(Native.GEN_KEY, "true");
                        Tile actual = fixture(tall);
                        actual.inhibitEvents();
                        int constant = anchor == Dimension.Anchor.NETHER_DETAIL ? 8 : anchor == Dimension.Anchor.END_DETAIL ? 9 : -1;
                        assertTrue("native must execute", actual.bakeAutoBiomes(constant, 1));
                        actual.releaseEvents();
                        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                            assertEquals("cell " + x + "," + y, expected[x + y * 128], actual.getLayerValue(Biome.INSTANCE, x, y));
                            assertEquals(tile.getRawHeight(x, y), actual.getRawHeight(x, y));
                            assertEquals(tile.getWaterLevel(x, y), actual.getWaterLevel(x, y));
                            assertEquals(tile.getTerrain(x, y), actual.getTerrain(x, y));
                        }
                    }
                }
            }
        } finally { Terrain.setCustomMaterial(0, old); restore(previous); }
    }

    @Test
    public void groupedBakeSupportsUndoAndPreservesExplicitBiomes() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        try {
            System.setProperty(Native.GEN_KEY, "true");
            Tile tile = new Tile(-1, -1, -64, 320);
            tile.setLayerValue(Biome.INSTANCE, 7, 8, 173);
            UndoManager undo = new UndoManager(); tile.register(undo); undo.armSavePoint();
            tile.inhibitEvents(); assertTrue(tile.bakeAutoBiomes(-1, 1)); tile.releaseEvents();
            assertEquals(173, tile.getLayerValue(Biome.INSTANCE, 7, 8));
            assertEquals(1, tile.getLayerValue(Biome.INSTANCE, 0, 0));
            assertTrue(undo.undo());
            assertEquals(255, tile.getLayerValue(Biome.INSTANCE, 0, 0));
            assertEquals(173, tile.getLayerValue(Biome.INSTANCE, 7, 8));
            assertTrue(undo.redo()); assertEquals(1, tile.getLayerValue(Biome.INSTANCE, 0, 0));
        } finally { restore(previous); }
    }

    @Test
    public void defaultOnlyResultDoesNotCreateAnEmptyBiomeLayer() {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        MixedMaterial old = Terrain.getCustomMaterial(0);
        try {
            Terrain.setCustomMaterial(0, new MixedMaterial("automatic biome",
                    new MixedMaterial.Row(org.pepsoft.minecraft.Material.STONE, 1000, 1f), 255, null));
            Tile tile = new Tile(0, 0, 0, 256);
            tile.inhibitEvents();
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) tile.setTerrain(x, y, Terrain.CUSTOM_1);
            System.setProperty(Native.GEN_KEY, "true");
            assertTrue(tile.bakeAutoBiomes(-1, 1));
            tile.releaseEvents();
            assertFalse(tile.hasLayer(Biome.INSTANCE));
        } finally { Terrain.setCustomMaterial(0, old); restore(previous); }
    }

    @Test
    public void wholeDimensionBakeMatchesJava() throws Exception {
        org.junit.Assume.assumeTrue(NativeLoader.areSlicesAvailable());
        String previous = System.getProperty(Native.GEN_KEY);
        try {
            Dimension java = AutoBiomeBenchmark.fixture(), rust = AutoBiomeBenchmark.fixture();
            System.setProperty(Native.GEN_KEY, "false"); AutoBiomeBenchmark.apply(java);
            System.setProperty(Native.GEN_KEY, "true"); AutoBiomeBenchmark.apply(rust);
            for (Tile tile : java.getTiles()) {
                Tile actual = rust.getTile(tile.getX(), tile.getY());
                for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++)
                    assertEquals(tile.getLayerValue(Biome.INSTANCE, x, y), actual.getLayerValue(Biome.INSTANCE, x, y));
            }
        } finally { restore(previous); }
    }

    private static Tile fixture(boolean tall) {
        Tile tile = new Tile(-3, 7, tall ? -64 : 0, tall ? 320 : 256);
        int[] depths = {-20, -1, 0, 1, 5, 6, 20, 21, 70};
        Terrain[] terrains = Terrain.values();
        tile.inhibitEvents();
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
            int flags = x, i = x + y * 128;
            tile.setHeight(x, y, 62.125f);
            tile.setWaterLevel(x, y, 62 + depths[y % depths.length]);
            tile.setTerrain(x, y, terrains[(i / 128) % terrains.length]);
            tile.setBitLayerValue(Frost.INSTANCE, x, y, (flags & 1) != 0);
            tile.setBitLayerValue(River.INSTANCE, x, y, (flags & 2) != 0);
            tile.setBitLayerValue(FloodWithLava.INSTANCE, x, y, (flags & 4) != 0);
            tile.setLayerValue(DeciduousForest.INSTANCE, x, y, (flags & 8) != 0 ? 5 : 0);
            tile.setLayerValue(PineForest.INSTANCE, x, y, (flags & 16) != 0 ? 3 : 0);
            tile.setLayerValue(SwampLand.INSTANCE, x, y, (flags & 32) != 0 ? 7 : 0);
            tile.setLayerValue(Jungle.INSTANCE, x, y, (flags & 64) != 0 ? 9 : 0);
            if (i % 19 == 0) tile.setLayerValue(Biome.INSTANCE, x, y, 173);
        }
        for (int x = 0; x < 128; x++) tile.setTerrain(x, 127, Terrain.CUSTOM_1);
        tile.releaseEvents(); return tile;
    }

    private static void restore(String previous) {
        if (previous == null) System.clearProperty(Native.GEN_KEY);
        else System.setProperty(Native.GEN_KEY, previous);
    }
}
