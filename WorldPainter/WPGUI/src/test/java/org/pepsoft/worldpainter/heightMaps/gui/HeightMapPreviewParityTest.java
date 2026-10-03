package org.pepsoft.worldpainter.heightMaps.gui;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.swing.Icon;
import org.junit.Test;
import org.pepsoft.worldpainter.HeightMap;
import org.pepsoft.worldpainter.HeightMapTileFactory;
import org.pepsoft.worldpainter.heightMaps.*;
import org.pepsoft.worldpainter.nativeapi.Native;
import static org.junit.Assert.*;

public class HeightMapPreviewParityTest {
    @Test public void stockProgramsMatchAtNormalAndZoomedCoordinates() {
        HeightMap[] maps = {new ConstantHeightMap(0), new ConstantHeightMap(Double.NaN), new ConstantHeightMap(64),
                new NoiseHeightMap(128, 1.7, 4, 17), new FastNoiseLiteHeightMap(128, 1.7, 4, 17),
                new SumHeightMap(new ConstantHeightMap(32), new NoiseHeightMap(100, .7, 3, -123)),
                new ProductHeightMap(new ConstantHeightMap(2), new NoiseHeightMap(100, 1.3, 2, 123)),
                new BandedHeightMap(37, .1, 29, 1, false), new BandedHeightMap(37, .1, 29, 1, true),
                new MandelbrotHeightMap()};
        for (HeightMap map : maps) for (int zoom : new int[] {0, -1, -3, -31, Integer.MIN_VALUE})
            compare(map, zoom, -1, 0, zoom == -31 ? 0 : 1);
    }
    @Test public void unknownMapAndInexactLargeCoordinatesRetainJava() {
        HeightMap custom = new AbstractHeightMap() {
            @Override public double getHeight(int x, int y) { return x * 17.0 + y; }
            @Override public double[] getRange() { return new double[] {0, 255}; }
            @Override public Icon getIcon() { return null; }
        };
        compare(custom, 0, -1, 0, 0);
        compare(new NoiseHeightMap(128, 1, 3, 3), 0, 131073, -1, 0);
    }
    @Test public void reusedProviderObservesSeedChanges() {
        NoiseHeightMap map = new NoiseHeightMap(128, 1, 3, 3);
        HeightMapTileProvider java = new HeightMapTileProvider(map), rust = new HeightMapTileProvider(map);
        withFlags(() -> {
            for (long seed : new long[] {17, -1234567}) {
                map.setSeed(seed); renderPair(java, rust, -1, 0);
            }
            assertEquals(2, rust.completedNativePreviewTiles());
        });
    }
    @Test public void invalidShelvingRangeKeepsOriginalPreviewFailure() {
        withFlags(() -> {
            HeightMap map = new ShelvingHeightMap(new NoiseHeightMap(128, 1, 2, 3));
            for (boolean nativeMode : new boolean[] {false, true}) {
                System.setProperty(Native.GEN_KEY, Boolean.toString(nativeMode));
                System.setProperty("welt.native.heightMapPreview", Boolean.toString(nativeMode));
                HeightMapTileProvider provider = new HeightMapTileProvider(map);
                try {
                    provider.paintTile(new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB), 0, 0, 0, 0);
                    fail("The original one-entry range must still fail");
                } catch (ArrayIndexOutOfBoundsException expected) { assertEquals(0, provider.completedNativePreviewTiles()); }
            }
        });
    }
    @Test public void parallelWorkersKeepIndependentPreviewBuffers() {
        HeightMap base = new SumHeightMap(new ConstantHeightMap(32), new NoiseHeightMap(100, .7, 3, -123));
        for (HeightMap map : new HeightMap[] {base, new SlopeHeightMap(base, 3.7f),
                new TransformingHeightMap("Affine", base, 1.7f, .65f, 31, -47, .37f),
                new DisplacementHeightMap(base, new NoiseHeightMap(6, .9, 3, 177), new NoiseHeightMap(64, .5, 3, -321))}) withFlags(() -> {
            HeightMapTileProvider java = new HeightMapTileProvider(map), rust = new HeightMapTileProvider(map);
            List<int[]> expected = new ArrayList<>();
            System.setProperty(Native.GEN_KEY, "false");
            for (int i = 0; i < 16; i++) {
                BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
                java.paintTile(image, i % 4 - 2, i / 4 - 2, 0, 0);
                expected.add(((DataBufferInt) image.getRaster().getDataBuffer()).getData().clone());
            }
            System.setProperty(Native.GEN_KEY, "true"); System.setProperty("welt.native.heightMapPreview", "true");
            var workers = Executors.newFixedThreadPool(4);
            try {
                List<Future<int[]>> results = new ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    final int tile = i;
                    results.add(workers.submit(() -> {
                        long before = rust.completedNativePreviewTiles();
                        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
                        rust.paintTile(image, tile % 4 - 2, tile / 4 - 2, 0, 0);
                        assertEquals(before + 1, rust.completedNativePreviewTiles());
                        return ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
                    }));
                }
                for (int i = 0; i < results.size(); i++) assertArrayEquals(expected.get(i), results.get(i).get());
            } catch (Exception e) { throw new AssertionError(e); }
            finally { workers.shutdownNow(); }
        });
    }
    @Test public void slopeChainsMatchAtNormalAndZoomedCoordinates() {
        HeightMap[] bases = {new NoiseHeightMap(128, 1.7, 3, -123),
                new SumHeightMap(new ConstantHeightMap(32), new FastNoiseLiteHeightMap(100, .7, 3, 17))};
        for (HeightMap base : bases) for (float scaling : new float[] {1, 3.7f, 0, -2, Float.NaN})
            for (int zoom : new int[] {0, -1, -3}) compare(new SlopeHeightMap(base, scaling), zoom, -1, 0, 1);
        compare(new SlopeHeightMap(bases[0]), -31, -1, 0, 0);
        compare(new SlopeHeightMap(bases[0]), 0, 131072, 0, 0);
    }
    @Test public void slowerZoomedSlopePathRequiresAnExplicitOverride() {
        withFlags(() -> {
            HeightMap map = new SlopeHeightMap(new NoiseHeightMap(128, 1, 3, -123));
            HeightMapTileProvider java = new HeightMapTileProvider(map), rust = new HeightMapTileProvider(map);
            java.setZoom(-3); rust.setZoom(-3);
            System.setProperty("welt.native.slopePreviewZoom", "false");
            renderPair(java, rust, -1, 0); assertEquals(0, rust.completedNativePreviewTiles());
        });
    }
    @Test public void affineChainsPreserveTranslationScaleRotationAndZoom() {
        HeightMap base = new SumHeightMap(new ConstantHeightMap(32), new NoiseHeightMap(100, .7, 3, -123));
        float[][] transforms = {{1, 1, 0}, {2, 3, 0}, {-2, .65f, .37f},
                {1, 1, (float) (Math.PI / 2)}, {1.7f, .65f, -.37f}};
        for (float[] transform : transforms) for (int zoom : new int[] {0, -1, -3})
            compare(new TransformingHeightMap("Affine", base, transform[0], transform[1], 31, -47, transform[2]), zoom, -1, 0, 1);
        for (HeightMap other : new HeightMap[] {new FastNoiseLiteHeightMap(128, .7, 3, 17),
                new BandedHeightMap(37, .1, 29, 1, true), new BandedHeightMap(37, .1, 29, 1, false)})
            for (int zoom : new int[] {0, -1, -3})
                compare(new TransformingHeightMap("Affine", other, -1.7f, .65f, 31, -47, .37f), zoom, -1, 0, 1);
        compare(new TransformingHeightMap("Overflow", base, 1, 1, Integer.MIN_VALUE, 0, 0), 0, -1, 0, 0);
        compare(new TransformingHeightMap("Invalid", base, Float.NaN, 1, 0, 0, 0), 0, -1, 0, 0);
        compare(new TransformingHeightMap("Invalid", base, 0, 1, 0, 0, 0), 0, -1, 0, 0);
    }
    @Test public void affineProviderObservesReplacementAndSeedChanges() {
        TransformingHeightMap map = new TransformingHeightMap("Affine", new NoiseHeightMap(128, 1, 3, 3), 1.7f, .65f, 31, -47, .37f);
        HeightMapTileProvider java = new HeightMapTileProvider(map), rust = new HeightMapTileProvider(map);
        withFlags(() -> {
            renderPair(java, rust, -1, 0);
            map.setSeed(-1234567); renderPair(java, rust, -1, 0);
            map.setBaseHeightMap(new NoiseHeightMap(100, .7, 2, 17)); renderPair(java, rust, -1, 0);
            assertEquals(3, rust.completedNativePreviewTiles());
        });
    }
    @Test public void affineHeightValuesMatchBeforeRasterQuantisation() {
        withFlags(() -> {
            System.setProperty(Native.GEN_KEY, "true"); System.setProperty("welt.native.heightMapPreview", "true");
            HeightMap[] bases = {new NoiseHeightMap(128, 1.7, 3, -123),
                    new FastNoiseLiteHeightMap(128, .7, 3, 17), new BandedHeightMap(37, .1, 29, 1, true)};
            for (HeightMap base : bases) for (float rotation : new float[] {0, .37f, (float) (Math.PI / 2)}) {
                HeightMap map = new TransformingHeightMap("Affine", base, rotation == 0 ? 1 : -1.7f, rotation == 0 ? 1 : .65f, 31, -47, rotation);
                for (int shift : new int[] {0, 3}) {
                    double[] actual = new double[16384];
                    assertTrue(HeightMapTileFactory.tryFillPreviewHeights(map, -128, 64, shift, null, null, actual));
                    for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
                        String location = base.getClass().getSimpleName() + " rotation=" + rotation + " shift=" + shift + " x=" + x + " y=" + y;
                        double expected = map.getHeight(-128 + (x << shift), 64 + (y << shift));
                        // The existing smooth banded kernel uses the platform cosine implementation.
                        // Check its numeric error separately from the exact rendered pixel comparison.
                        if (base instanceof BandedHeightMap) assertEquals(location, expected, actual[x + y * 128], 2e-15);
                        else assertEquals(location, Double.doubleToLongBits(expected), Double.doubleToLongBits(actual[x + y * 128]));
                    }
                }
            }
        });
    }
    @Test public void displacementChainsPreservePixelsAcrossProgramsAndZoom() {
        HeightMap[] bases = {new NoiseHeightMap(128, 1.7, 3, -123),
                new FastNoiseLiteHeightMap(128, .7, 3, 17), new BandedHeightMap(37, .1, 29, 1, true)};
        for (HeightMap base : bases) for (HeightMap angle : new HeightMap[] {new ConstantHeightMap(0),
                new ConstantHeightMap(.37), new NoiseHeightMap(Math.PI * 2, .9, 3, 177)})
            for (int zoom : new int[] {0, -1, -3})
                compare(new DisplacementHeightMap(base, angle, new NoiseHeightMap(64, .5, 3, -321)), zoom, -1, 0, 1);
        compare(new DisplacementHeightMap(bases[0], new ConstantHeightMap(Double.NaN), new ConstantHeightMap(17)), 0, -1, 0, 0);
        compare(new DisplacementHeightMap(bases[0], new ConstantHeightMap(.37), new ConstantHeightMap(Double.POSITIVE_INFINITY)), 0, -1, 0, 0);
    }
    @Test public void displacementReusedProviderObservesAllChildChanges() {
        DisplacementHeightMap map = new DisplacementHeightMap(new NoiseHeightMap(128, 1.7, 3, -123),
                new NoiseHeightMap(Math.PI * 2, .9, 3, 177), new NoiseHeightMap(64, .5, 3, -321));
        HeightMapTileProvider java = new HeightMapTileProvider(map), rust = new HeightMapTileProvider(map);
        withFlags(() -> {
            renderPair(java, rust, -1, 0);
            map.setSeed(-1234567); renderPair(java, rust, -1, 0);
            map.setAngleMap(new ConstantHeightMap(-.37)); renderPair(java, rust, -1, 0);
            map.setDistanceMap(new ConstantHeightMap(-64)); renderPair(java, rust, -1, 0);
            map.setBaseHeightMap(new FastNoiseLiteHeightMap(100, .7, 2, 17)); renderPair(java, rust, -1, 0);
            assertEquals(5, rust.completedNativePreviewTiles());
        });
    }
    @Test public void displacementHeightValuesMatchBeforeRasterQuantisation() {
        withFlags(() -> {
            System.setProperty(Native.GEN_KEY, "true"); System.setProperty("welt.native.heightMapPreview", "true");
            for (HeightMap base : new HeightMap[] {new NoiseHeightMap(128, 1.7, 3, -123), new FastNoiseLiteHeightMap(128, .7, 3, 17)}) {
                HeightMap map = new DisplacementHeightMap(base, new NoiseHeightMap(Math.PI * 2, .9, 3, 177), new NoiseHeightMap(64, .5, 3, -321));
                for (int shift : new int[] {0, 3}) {
                    double[] actual = new double[16384];
                    assertTrue(HeightMapTileFactory.tryFillPreviewHeights(map, -128, 64, shift, null, null, actual));
                    for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++)
                        assertEquals("Displaced unquantised height", Double.doubleToLongBits(map.getHeight(-128 + (x << shift), 64 + (y << shift))),
                                Double.doubleToLongBits(actual[x + y * 128]));
                }
            }
        });
    }
    private static void compare(HeightMap map, int zoom, int tileX, int tileY, int expectedCalls) {
        withFlags(() -> {
            HeightMapTileProvider java = new HeightMapTileProvider(map), rust = new HeightMapTileProvider(map);
            java.setZoom(zoom); rust.setZoom(zoom); renderPair(java, rust, tileX, tileY);
            assertEquals(map.getClass().getSimpleName() + " zoom=" + zoom, expectedCalls, rust.completedNativePreviewTiles());
        });
    }
    private static void renderPair(HeightMapTileProvider java, HeightMapTileProvider rust, int tileX, int tileY) {
        BufferedImage expected = new BufferedImage(120, 124, BufferedImage.TYPE_INT_ARGB);
        BufferedImage actual = new BufferedImage(120, 124, BufferedImage.TYPE_INT_ARGB);
        System.setProperty(Native.GEN_KEY, "false"); java.paintTile(expected, tileX, tileY, -5, -3);
        System.setProperty(Native.GEN_KEY, "true"); System.setProperty("welt.native.heightMapPreview", "true");
        rust.paintTile(actual, tileX, tileY, -5, -3);
        assertArrayEquals(((DataBufferInt) expected.getRaster().getDataBuffer()).getData(), ((DataBufferInt) actual.getRaster().getDataBuffer()).getData());
    }
    private static void withFlags(Runnable action) {
        String gen = System.getProperty(Native.GEN_KEY), preview = System.getProperty("welt.native.heightMapPreview"), slopeZoom = System.getProperty("welt.native.slopePreviewZoom");
        try { System.setProperty("welt.native.slopePreviewZoom", "true"); action.run(); } finally {
            restore(Native.GEN_KEY, gen); restore("welt.native.heightMapPreview", preview);
            restore("welt.native.slopePreviewZoom", slopeZoom);
        }
    }
    private static void restore(String key, String value) { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }
}
