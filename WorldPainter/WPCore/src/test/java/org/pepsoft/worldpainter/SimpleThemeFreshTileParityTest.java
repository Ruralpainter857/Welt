package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.worldpainter.heightMaps.ConstantHeightMap;
import org.pepsoft.worldpainter.heightMaps.BitmapHeightMap;
import org.pepsoft.worldpainter.heightMaps.BicubicHeightMap;
import org.pepsoft.worldpainter.heightMaps.BandedHeightMap;
import org.pepsoft.worldpainter.heightMaps.DifferenceHeightMap;
import org.pepsoft.worldpainter.heightMaps.DisplacementHeightMap;
import org.pepsoft.worldpainter.heightMaps.NoiseHeightMap;
import org.pepsoft.worldpainter.heightMaps.MinimisingHeightMap;
import org.pepsoft.worldpainter.heightMaps.MaximisingHeightMap;
import org.pepsoft.worldpainter.heightMaps.MandelbrotHeightMap;
import org.pepsoft.worldpainter.heightMaps.NinePatchHeightMap;
import org.pepsoft.worldpainter.heightMaps.ProductHeightMap;
import org.pepsoft.worldpainter.heightMaps.SumHeightMap;
import org.pepsoft.worldpainter.heightMaps.TransformingHeightMap;
import org.pepsoft.worldpainter.heightMaps.ShelvingHeightMap;
import org.pepsoft.worldpainter.heightMaps.SlopeHeightMap;
import org.pepsoft.worldpainter.layers.Frost;
import org.pepsoft.worldpainter.layers.FloodWithLava;
import org.pepsoft.worldpainter.layers.Layer;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.themes.Filter;
import org.pepsoft.worldpainter.themes.HeightFilter;
import org.pepsoft.worldpainter.themes.SimpleTheme;

import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

/** Verifies the optimized initialization path against ordinary theme application. */
public final class SimpleThemeFreshTileParityTest {
    @Test
    public void javaNoiseHeightMapSimpleThemeBatchMatchesPerCellFallback() {
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            Native.setGenEnabled(false);
            final HeightMapTileFactory referenceFactory = new HeightMapTileFactory(42L,
                    new SumHeightMap(new ConstantHeightMap(62), new NoiseHeightMap(20.0, 1.0, 1, 0L)),
                    0, 256, false, createNoisySimpleTheme(true));
            final HeightMapTileFactory batchFactory = new HeightMapTileFactory(42L,
                    new SumHeightMap(new ConstantHeightMap(62), new NoiseHeightMap(20.0, 1.0, 1, 0L)),
                    0, 256, false, createNoisySimpleTheme(false));
            final Tile reference = referenceFactory.createTile(-3, 7);
            final Tile batch = batchFactory.createTile(-3, 7);
            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    assertEquals("height at " + x + ',' + y,
                            Float.floatToRawIntBits(reference.getHeight(x, y)),
                            Float.floatToRawIntBits(batch.getHeight(x, y)));
                    assertEquals("water at " + x + ',' + y,
                            reference.getWaterLevel(x, y), batch.getWaterLevel(x, y));
                    assertEquals("terrain at " + x + ',' + y,
                            reference.getTerrain(x, y), batch.getTerrain(x, y));
                    assertEquals("Frost at " + x + ',' + y,
                            reference.getBitLayerValue(Frost.INSTANCE, x, y),
                            batch.getBitLayerValue(Frost.INSTANCE, x, y));
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
    public void nativeNestedSumHeightMapsMatchJavaFreshTile() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            final HeightMap javaMap = new SumHeightMap(
                    new SumHeightMap(new ConstantHeightMap(42.25),
                            new NoiseHeightMap(17.5, 0.75, 2, -0x1020_3040L)),
                    new NoiseHeightMap(8.25, 1.5, 4, 0x5566_7788L));
            final HeightMap nativeMap = new SumHeightMap(
                    new SumHeightMap(new ConstantHeightMap(42.25),
                            new NoiseHeightMap(17.5, 0.75, 2, -0x1020_3040L)),
                    new NoiseHeightMap(8.25, 1.5, 4, 0x5566_7788L));
            final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L, javaMap,
                    0, 256, false, createSimpleTheme(true));
            final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L, nativeMap,
                    0, 256, false, createSimpleTheme(false));
            Native.setGenEnabled(false);
            final Tile javaTile = javaFactory.createTile(-2, 5);
            Native.setGenEnabled(true);
            final Tile nativeTile = nativeFactory.createTile(-2, 5);
            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    assertEquals("height at " + x + ',' + y,
                            Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                            Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                    assertEquals("water at " + x + ',' + y,
                            javaTile.getWaterLevel(x, y), nativeTile.getWaterLevel(x, y));
                    assertEquals("terrain at " + x + ',' + y,
                            javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
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
    public void nativeProductAndDifferenceHeightMapsMatchJavaFreshTile() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            final HeightMap javaMap = new DifferenceHeightMap(
                    new ProductHeightMap(
                            new SumHeightMap(new ConstantHeightMap(20.1),
                                    new NoiseHeightMap(38.0, 0.8, 2, 0x1234_5678L)),
                            new NoiseHeightMap(0.35, 2.1, 5, -0x2468_1357L)),
                    new ConstantHeightMap(1.75));
            final HeightMap nativeMap = new DifferenceHeightMap(
                    new ProductHeightMap(
                            new SumHeightMap(new ConstantHeightMap(20.1),
                                    new NoiseHeightMap(38.0, 0.8, 2, 0x1234_5678L)),
                            new NoiseHeightMap(0.35, 2.1, 5, -0x2468_1357L)),
                    new ConstantHeightMap(1.75));
            final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L, javaMap,
                    0, 256, false, createSimpleTheme(false));
            final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L, nativeMap,
                    0, 256, false, createSimpleTheme(false));
            Native.setGenEnabled(false);
            final Tile javaTile = javaFactory.createTile(-2, 5);
            Native.setGenEnabled(true);
            final Tile nativeTile = nativeFactory.createTile(-2, 5);
            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    assertEquals("height at " + x + ',' + y,
                            Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                            Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                    assertEquals("water at " + x + ',' + y,
                            javaTile.getWaterLevel(x, y), nativeTile.getWaterLevel(x, y));
                    assertEquals("terrain at " + x + ',' + y,
                            javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
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
    public void nativeMinimumAndMaximumHeightMapsMatchJavaFreshTile() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            final HeightMap javaMap = new MaximisingHeightMap(
                    new MinimisingHeightMap(
                            new SumHeightMap(new ConstantHeightMap(48.0),
                                    new NoiseHeightMap(38.0, 0.8, 2, 0x1234_5678L)),
                            new NoiseHeightMap(22.0, 2.1, 5, -0x2468_1357L)),
                    new ConstantHeightMap(54.0));
            final HeightMap nativeMap = new MaximisingHeightMap(
                    new MinimisingHeightMap(
                            new SumHeightMap(new ConstantHeightMap(48.0),
                                    new NoiseHeightMap(38.0, 0.8, 2, 0x1234_5678L)),
                            new NoiseHeightMap(22.0, 2.1, 5, -0x2468_1357L)),
                    new ConstantHeightMap(54.0));
            final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L, javaMap,
                    0, 256, false, createSimpleTheme(false));
            final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L, nativeMap,
                    0, 256, false, createSimpleTheme(false));
            Native.setGenEnabled(false);
            final Tile javaTile = javaFactory.createTile(-2, 5);
            Native.setGenEnabled(true);
            final Tile nativeTile = nativeFactory.createTile(-2, 5);
            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    assertEquals("height at " + x + ',' + y,
                            Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                            Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                    assertEquals("water at " + x + ',' + y,
                            javaTile.getWaterLevel(x, y), nativeTile.getWaterLevel(x, y));
                    assertEquals("terrain at " + x + ',' + y,
                            javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
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
    public void nativeMandelbrotHeightMapMatchesJavaFreshTile() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            final HeightMap javaMap = new SumHeightMap(new ConstantHeightMap(58.0), new MandelbrotHeightMap());
            final HeightMap nativeMap = new SumHeightMap(new ConstantHeightMap(58.0), new MandelbrotHeightMap());
            final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L, javaMap,
                    0, 256, false, createSimpleTheme(false));
            final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L, nativeMap,
                    0, 256, false, createSimpleTheme(false));
            Native.setGenEnabled(false);
            final Tile javaTile = javaFactory.createTile(0, 0);
            Native.setGenEnabled(true);
            final Tile nativeTile = nativeFactory.createTile(0, 0);
            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    assertEquals("height at " + x + ',' + y,
                            Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                            Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                    assertEquals("water at " + x + ',' + y,
                            javaTile.getWaterLevel(x, y), nativeTile.getWaterLevel(x, y));
                    assertEquals("terrain at " + x + ',' + y,
                            javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
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
    public void nativeBandedHeightMapsMatchJavaFreshTile() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            for (final boolean smooth : new boolean[] {false, true}) {
                for (final int[] tile : new int[][] {{-2, 5}, {1000, -1000}}) {
                    final HeightMap javaMap = new BandedHeightMap("banded", 9, 116.5, 7, 88.25, smooth);
                    final HeightMap nativeMap = new BandedHeightMap("banded", 9, 116.5, 7, 88.25, smooth);
                    final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L, javaMap,
                            0, 256, false, createSimpleTheme(true));
                    final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L, nativeMap,
                            0, 256, false, createSimpleTheme(false));
                    Native.setGenEnabled(false);
                    final Tile javaTile = javaFactory.createTile(tile[0], tile[1]);
                    Native.setGenEnabled(true);
                    final Tile nativeTile = nativeFactory.createTile(tile[0], tile[1]);
                    for (int x = 0; x < Constants.TILE_SIZE; x++) {
                        for (int y = 0; y < Constants.TILE_SIZE; y++) {
                            assertEquals("height at " + x + ',' + y + " smooth=" + smooth,
                                    Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                                    Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                            assertEquals("terrain at " + x + ',' + y + " smooth=" + smooth,
                                    javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
                        }
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
    public void nativeNinePatchHeightMapMatchesJavaFreshTile() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            final int[][] parameters = {{0, 8, 12}, {7, 0, 10}, {9, 6, 0}, {16, 11, 13}};
            for (final int[] parameter : parameters) {
                for (final boolean combined : new boolean[] {false, true}) {
                    for (final int[] tile : new int[][] {{0, 0}, {-1, 0}, {0, 1}, {-2, -2}, {1000, -1000}}) {
                        final HeightMap javaBase = new NinePatchHeightMap(
                                parameter[0], parameter[1], parameter[2], 117.25);
                        final HeightMap nativeBase = new NinePatchHeightMap(
                                parameter[0], parameter[1], parameter[2], 117.25);
                        final HeightMap javaMap = combined
                                ? new SumHeightMap(new ConstantHeightMap(3.25), javaBase) : javaBase;
                        final HeightMap nativeMap = combined
                                ? new SumHeightMap(new ConstantHeightMap(3.25), nativeBase) : nativeBase;
                        final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L, javaMap,
                                0, 256, false, createSimpleTheme(true));
                        final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L, nativeMap,
                                0, 256, false, createSimpleTheme(false));
                        Native.setGenEnabled(false);
                        final Tile javaTile = javaFactory.createTile(tile[0], tile[1]);
                        Native.setGenEnabled(true);
                        final Tile nativeTile = nativeFactory.createTile(tile[0], tile[1]);
                        for (int x = 0; x < Constants.TILE_SIZE; x++) {
                            for (int y = 0; y < Constants.TILE_SIZE; y++) {
                                assertEquals("height at " + x + ',' + y + " parameters="
                                                + java.util.Arrays.toString(parameter)
                                                + " combined=" + combined,
                                        Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                                        Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                                assertEquals("terrain at " + x + ',' + y,
                                        javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
                            }
                        }
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
    public void nativeSlopeHeightMapMatchesJavaFreshTile() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            for (final float scale : new float[] {1.0f, 2.5f}) {
                for (final int[] tile : new int[][] {{-2, 5}, {0, 0}, {1000, -1000}}) {
                    final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L,
                            new SlopeHeightMap(new SumHeightMap(new ConstantHeightMap(42.25),
                                    new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L)), scale),
                            0, 256, false, createSimpleTheme(false));
                    final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L,
                            new SlopeHeightMap(new SumHeightMap(new ConstantHeightMap(42.25),
                                    new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L)), scale),
                            0, 256, false, createSimpleTheme(false));
                    Native.setGenEnabled(false);
                    final Tile javaTile = javaFactory.createTile(tile[0], tile[1]);
                    Native.setGenEnabled(true);
                    final Tile nativeTile = nativeFactory.createTile(tile[0], tile[1]);
                    for (int x = 0; x < Constants.TILE_SIZE; x++) {
                        for (int y = 0; y < Constants.TILE_SIZE; y++) {
                            assertEquals("slope height at " + x + ',' + y + " scale=" + scale,
                                    Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                                    Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                            assertEquals("terrain at " + x + ',' + y,
                                    javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
                        }
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
    public void nativeDisplacementHeightMapMatchesJavaFreshTile() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            for (final int[] tile : new int[][] {{-2, 5}, {0, 0}, {1000, -1000}, {131073, 0}}) {
                final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L,
                        new DisplacementHeightMap(
                                new SumHeightMap(new ConstantHeightMap(42.25),
                                        new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L)),
                                new NoiseHeightMap(6.0, 0.75, 2, 0x1234_5678L),
                                new NoiseHeightMap(8.0, 1.25, 2, -0x2468_1357L)),
                        0, 256, false, createSimpleTheme(false));
                final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L,
                        new DisplacementHeightMap(
                                new SumHeightMap(new ConstantHeightMap(42.25),
                                        new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L)),
                                new NoiseHeightMap(6.0, 0.75, 2, 0x1234_5678L),
                                new NoiseHeightMap(8.0, 1.25, 2, -0x2468_1357L)),
                        0, 256, false, createSimpleTheme(false));
                Native.setGenEnabled(false);
                final Tile javaTile = javaFactory.createTile(tile[0], tile[1]);
                Native.setGenEnabled(true);
                final Tile nativeTile = nativeFactory.createTile(tile[0], tile[1]);
                for (int x = 0; x < Constants.TILE_SIZE; x++) {
                    for (int y = 0; y < Constants.TILE_SIZE; y++) {
                        assertEquals("displacement height at " + x + ',' + y,
                                Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                                Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                        assertEquals("terrain at " + x + ',' + y,
                                javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
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
    public void nativeTranslatedHeightMapMatchesJavaFreshTile() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            for (final int[] tile : new int[][] {{-2, 5}, {1000, -1000}}) {
                final HeightMap javaMap = new TransformingHeightMap("translation",
                        new SumHeightMap(new ConstantHeightMap(42.25),
                                new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L)),
                        1.0f, 1.0f, -13, 29, 0.0f);
                final HeightMap nativeMap = new TransformingHeightMap("translation",
                        new SumHeightMap(new ConstantHeightMap(42.25),
                                new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L)),
                        1.0f, 1.0f, -13, 29, 0.0f);
                final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L, javaMap,
                        0, 256, false, createSimpleTheme(true));
                final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L, nativeMap,
                        0, 256, false, createSimpleTheme(false));
                Native.setGenEnabled(false);
                final Tile javaTile = javaFactory.createTile(tile[0], tile[1]);
                Native.setGenEnabled(true);
                final Tile nativeTile = nativeFactory.createTile(tile[0], tile[1]);
                for (int x = 0; x < Constants.TILE_SIZE; x++) {
                    for (int y = 0; y < Constants.TILE_SIZE; y++) {
                        assertEquals("height at " + x + ',' + y,
                                Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                                Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                        assertEquals("terrain at " + x + ',' + y,
                                javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
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
    public void nativeShelvingHeightMapMatchesJavaFreshTile() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            for (final int[] tile : new int[][] {
                    {-2, 5}, {1000, -1000}, {131071, -131072}, {131073, -131073}}) {
                final ShelvingHeightMap javaMap = new ShelvingHeightMap(
                        new SumHeightMap(new ConstantHeightMap(42.25),
                                new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L)));
                final ShelvingHeightMap nativeMap = new ShelvingHeightMap(
                        new SumHeightMap(new ConstantHeightMap(42.25),
                                new NoiseHeightMap(38.0, 0.8, 3, -0x1020_3040L)));
                javaMap.setShelveHeight(37);
                javaMap.setShelveStrength(11);
                nativeMap.setShelveHeight(37);
                nativeMap.setShelveStrength(11);
                final HeightMapTileFactory javaFactory = new HeightMapTileFactory(73L, javaMap,
                        0, 256, false, createSimpleTheme(true));
                final HeightMapTileFactory nativeFactory = new HeightMapTileFactory(73L, nativeMap,
                        0, 256, false, createSimpleTheme(false));
                Native.setGenEnabled(false);
                final Tile javaTile = javaFactory.createTile(tile[0], tile[1]);
                Native.setGenEnabled(true);
                final Tile nativeTile = nativeFactory.createTile(tile[0], tile[1]);
                for (int x = 0; x < Constants.TILE_SIZE; x++) {
                    for (int y = 0; y < Constants.TILE_SIZE; y++) {
                        assertEquals("height at " + x + ',' + y,
                                Float.floatToRawIntBits(javaTile.getHeight(x, y)),
                                Float.floatToRawIntBits(nativeTile.getHeight(x, y)));
                        assertEquals("terrain at " + x + ',' + y,
                                javaTile.getTerrain(x, y), nativeTile.getTerrain(x, y));
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
    public void javaHeightMapSimpleThemeBatchMatchesPerCellFallback() {
        final HeightMap heightMap = new SumHeightMap(new ConstantHeightMap(30), new ConstantHeightMap(32));
        final HeightMapTileFactory referenceFactory = new HeightMapTileFactory(42L, heightMap,
                0, 256, false, createSimpleTheme(true));
        final HeightMapTileFactory batchFactory = new HeightMapTileFactory(42L,
                new SumHeightMap(new ConstantHeightMap(30), new ConstantHeightMap(32)),
                0, 256, false, createSimpleTheme(false));
        final Tile reference = referenceFactory.createTile(-3, 7);
        final Tile batch = batchFactory.createTile(-3, 7);
        for (int x = 0; x < Constants.TILE_SIZE; x++) {
            for (int y = 0; y < Constants.TILE_SIZE; y++) {
                assertEquals("height at " + x + ',' + y,
                        Float.floatToRawIntBits(reference.getHeight(x, y)),
                        Float.floatToRawIntBits(batch.getHeight(x, y)));
                assertEquals("water at " + x + ',' + y,
                        reference.getWaterLevel(x, y), batch.getWaterLevel(x, y));
                assertEquals("terrain at " + x + ',' + y,
                        reference.getTerrain(x, y), batch.getTerrain(x, y));
            }
        }
    }

    @Test
    public void rowMajorTerrainBatchPreservesBitLayerRandomSequence() throws Exception {
        final Map<Filter, Layer> layers = java.util.Collections.singletonMap(
                new HeightFilter(0, 256, 70, 180, true), FloodWithLava.INSTANCE);
        final SortedMap<Integer, Terrain> ranges = new TreeMap<>();
        ranges.put(-1, Terrain.GRASS);
        ranges.put(90, Terrain.STONE_MIX);
        final SimpleTheme legacyTheme = new SimpleTheme(0L, 62, ranges, layers, 0, 256, true, true) { };
        final SimpleTheme batchTheme = new SimpleTheme(0L, 62, ranges, layers, 0, 256, true, true);
        final HeightMapTileFactory legacyFactory = new HeightMapTileFactory(42L,
                new SumHeightMap(new ConstantHeightMap(75), new NoiseHeightMap(18, 0.8, 3, 0x1234_5678L)),
                0, 256, false, legacyTheme);
        final HeightMapTileFactory batchFactory = new HeightMapTileFactory(42L,
                new SumHeightMap(new ConstantHeightMap(75), new NoiseHeightMap(18, 0.8, 3, 0x1234_5678L)),
                0, 256, false, batchTheme);
        final String previousFlag = System.getProperty(Native.GEN_KEY);
        try {
            Native.setGenEnabled(false);
            resetSimpleThemeRandom(0x57454c54L);
            final Tile legacy = legacyFactory.createTile(-3, 7);
            resetSimpleThemeRandom(0x57454c54L);
            final Tile batch = batchFactory.createTile(-3, 7);
            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    assertEquals("height at " + x + ',' + y,
                            Float.floatToRawIntBits(legacy.getHeight(x, y)),
                            Float.floatToRawIntBits(batch.getHeight(x, y)));
                    assertEquals("water at " + x + ',' + y,
                            legacy.getWaterLevel(x, y), batch.getWaterLevel(x, y));
                    assertEquals("terrain at " + x + ',' + y,
                            legacy.getTerrain(x, y), batch.getTerrain(x, y));
                    assertEquals("random BIT layer at " + x + ',' + y,
                            legacy.getBitLayerValue(FloodWithLava.INSTANCE, x, y),
                            batch.getBitLayerValue(FloodWithLava.INSTANCE, x, y));
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
    public void bitmapHeightMapBulkRasterMatchesPerCellSampling() {
        for (final int[] imageTypeAndChannel : new int[][] {
                {BufferedImage.TYPE_BYTE_GRAY, 0},
                {BufferedImage.TYPE_USHORT_GRAY, 0},
                {BufferedImage.TYPE_3BYTE_BGR, 2}}) {
            for (final int[] dimensions : new int[][] {{260, 259}, {64, 73}}) {
                final BufferedImage image = new BufferedImage(dimensions[0], dimensions[1], imageTypeAndChannel[0]);
                for (int x = 0; x < image.getWidth(); x++) {
                    for (int y = 0; y < image.getHeight(); y++) {
                        final int red = (x * 17 + y * 3) & 0xff;
                        final int green = (x * 5 + y * 11) & 0xff;
                        final int blue = (x * 7 + y * 13) & 0xff;
                        image.setRGB(x, y, 0xff000000 | (red << 16) | (green << 8) | blue);
                    }
                }
                for (final boolean repeat : new boolean[] {false, true}) {
                    for (final boolean bicubic : new boolean[] {false, true}) {
                        final BitmapHeightMap legacyBitmap = BitmapHeightMap.build()
                                .withImage(image).withChannel(imageTypeAndChannel[1]).withRepeat(repeat).now();
                        final BitmapHeightMap batchBitmap = BitmapHeightMap.build()
                                .withImage(image).withChannel(imageTypeAndChannel[1]).withRepeat(repeat).now();
                        final HeightMap legacyMap = bicubic ? new BicubicHeightMap(legacyBitmap) : legacyBitmap;
                        final HeightMap batchMap = bicubic ? new BicubicHeightMap(batchBitmap) : batchBitmap;
                        final HeightMapTileFactory legacyFactory = new HeightMapTileFactory(42L, legacyMap,
                                0, 256, false, createSimpleTheme(true));
                        final HeightMapTileFactory batchFactory = new HeightMapTileFactory(42L, batchMap,
                                0, 256, false, createSimpleTheme(false));
                        for (final int[] tile : new int[][] {
                                {0, 0}, {1, 1}, {2, 2}, {-1, 0}, {0, -1}, {-1, -1}}) {
                            final Tile legacy = legacyFactory.createTile(tile[0], tile[1]);
                            final Tile batch = batchFactory.createTile(tile[0], tile[1]);
                            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                                    assertEquals("height for image type " + imageTypeAndChannel[0]
                                                    + " repeat=" + repeat + " bicubic=" + bicubic
                                                    + " at " + x + ',' + y,
                                            Float.floatToRawIntBits(legacy.getHeight(x, y)),
                                            Float.floatToRawIntBits(batch.getHeight(x, y)));
                                    assertEquals("water at " + x + ',' + y,
                                            legacy.getWaterLevel(x, y), batch.getWaterLevel(x, y));
                                    assertEquals("terrain at " + x + ',' + y,
                                            legacy.getTerrain(x, y), batch.getTerrain(x, y));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

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

    private static void resetSimpleThemeRandom(long seed) throws ReflectiveOperationException {
        final java.lang.reflect.Field randomField = SimpleTheme.class.getDeclaredField("random");
        randomField.setAccessible(true);
        ((java.util.Random) randomField.get(null)).setSeed(seed);
    }

    private static SimpleTheme createSimpleTheme(boolean legacyPerCellPath) {
        final SortedMap<Integer, Terrain> ranges = new TreeMap<>();
        ranges.put(-1, Terrain.GRASS);
        ranges.put(95, Terrain.STONE_MIX);
        if (legacyPerCellPath) {
            return new SimpleTheme(0L, 62, ranges, null, 0, 256, false, true) { };
        }
        return new SimpleTheme(0L, 62, ranges, null, 0, 256, false, true);
    }

    private static SimpleTheme createNoisySimpleTheme(boolean legacyPerCellPath) {
        final SortedMap<Integer, Terrain> ranges = new TreeMap<>();
        ranges.put(-1, Terrain.GRASS);
        ranges.put(126, Terrain.PERMADIRT);
        ranges.put(158, Terrain.STONE_MIX);
        ranges.put(222, Terrain.DEEP_SNOW);
        final Map<Filter, Layer> layers = java.util.Collections.singletonMap(
                new HeightFilter(0, 256, 190, 256, true), Frost.INSTANCE);
        if (legacyPerCellPath) {
            return new SimpleTheme(0L, 62, ranges, layers, 0, 256, true, true) { };
        }
        return new SimpleTheme(0L, 62, ranges, layers, 0, 256, true, true);
    }
}
