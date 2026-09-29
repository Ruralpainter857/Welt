package org.pepsoft.worldpainter;

import org.junit.Test;
import org.pepsoft.util.ColourUtils;
import org.pepsoft.worldpainter.nativeapi.Native;
import org.pepsoft.worldpainter.nativeapi.NativeLoader;
import org.pepsoft.worldpainter.nativeapi.NativeSlices;
import org.pepsoft.worldpainter.layers.NotPresent;

import java.util.Arrays;
import java.util.Random;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Bit-exact and opt-in batch benchmark coverage for TileRenderer shading. */
public final class NativeRenderShadingParityTest {
    private static final int PIXELS = 128 * 128;

    @Test
    public void tileRenderHeightSnapshotMatchesScalarGettersForBothStorageLayouts() {
        assertRenderHeightSnapshotMatches(new Tile(0, 0, 0, 256));
        assertRenderHeightSnapshotMatches(new Tile(0, 0, -128, 384));
    }

    @Test
    public void nativeTileShadingMatchesJavaForAllBrightnessBands() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.RENDER_KEY);
        Native.setRenderEnabled(true);
        try {
            final int[] original = new int[PIXELS];
            final long[] amounts = new long[PIXELS];
            final int[] candidates = {0, 1, 64, 128, 192, 255, 256, 257, 512, 4096, Integer.MAX_VALUE};
            final Random random = new Random(0x57656c745f52314cL);
            for (int i = 0; i < PIXELS; i++) {
                original[i] = random.nextInt();
                final int terrain = candidates[i % candidates.length];
                final int fluid = candidates[(i * 7 + 3) % candidates.length];
                amounts[i] = ((long) fluid << 32) | (terrain & 0xffff_ffffL);
            }

            final int[] expected = original.clone();
            for (int i = 0; i < expected.length; i++) {
                final int terrain = (int) amounts[i];
                final int fluid = (int) (amounts[i] >>> 32);
                if (terrain != 256 || fluid != 256) {
                    final int alpha = expected[i] & 0xff00_0000;
                    expected[i] = ColourUtils.multiply(
                            ColourUtils.multiply(expected[i], terrain), fluid) | alpha;
                }
            }
            final int[] actual = original.clone();
            assertTrue("native shading batch should be available", NativeSlices.shadeColours(actual, amounts));
            assertArrayEquals(expected, actual);
        } finally {
            restoreFlag(previousFlag);
        }
    }

    @Test
    public void tileRendererNativePathMatchesTheJavaImage() {
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.RENDER_KEY);
        try {
            final Dimension dimension = TestData.createDimension(
                    new Rectangle(0, 0, Constants.TILE_SIZE, Constants.TILE_SIZE), 64);
            final Tile tile = dimension.getTile(0, 0);
            tile.inhibitEvents();
            try {
                for (int x = 0; x < Constants.TILE_SIZE; x++) {
                    for (int y = 0; y < Constants.TILE_SIZE; y++) {
                        final float height = 32 + ((x * 7 + y * 11) % 96);
                        tile.setHeight(x, y, height);
                        tile.setWaterLevel(x, y, 62 + ((x + y) % 24));
                        if (x == 4 && y == 8) {
                            tile.setBitLayerValue(org.pepsoft.worldpainter.layers.Void.INSTANCE, x, y, true);
                        } else if (x == 12 && y == 5) {
                            tile.setBitLayerValue(NotPresent.INSTANCE, x, y, true);
                        }
                    }
                }
            } finally {
                tile.releaseEvents();
            }
            final int[] javaPixels = renderTile(dimension, tile, false);
            final int[] nativePixels = renderTile(dimension, tile, true);
            assertArrayEquals(javaPixels, nativePixels);
            assertEquals("void pixel should stay transparent", 0, nativePixels[4 | (8 << Constants.TILE_SIZE_BITS)]);
            assertEquals("not-present pixel should stay transparent", 0,
                    nativePixels[12 | (5 << Constants.TILE_SIZE_BITS)]);
        } finally {
            restoreFlag(previousFlag);
        }
    }

    @Test
    public void benchmarkNativeTileShadingWhenRequested() throws Exception {
        assumeTrue(Boolean.getBoolean("welt.render.shade.benchmark"));
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.RENDER_KEY);
        Native.setRenderEnabled(true);
        try {
            final int[] original = new int[PIXELS];
            final long[] amounts = new long[PIXELS];
            final Random random = new Random(0x57656c745f52314cL);
            for (int i = 0; i < PIXELS; i++) {
                original[i] = random.nextInt() & 0x00ff_ffff;
                final int terrain = 32 + random.nextInt(481);
                final int fluid = 32 + random.nextInt(481);
                amounts[i] = ((long) fluid << 32) | (terrain & 0xffff_ffffL);
            }
            final int[] javaPixels = new int[PIXELS];
            final int[] nativePixels = new int[PIXELS];
            for (int i = 0; i < 5; i++) {
                System.arraycopy(original, 0, javaPixels, 0, PIXELS);
                System.arraycopy(original, 0, nativePixels, 0, PIXELS);
                shadeJavaInPlace(javaPixels, amounts);
                NativeSlices.shadeColours(nativePixels, amounts);
            }
            final long[] javaNanos = new long[9];
            final long[] nativeNanos = new long[9];
            for (int sample = 0; sample < javaNanos.length; sample++) {
                System.arraycopy(original, 0, javaPixels, 0, PIXELS);
                long start = System.nanoTime();
                for (int iteration = 0; iteration < 8; iteration++) {
                    shadeJavaInPlace(javaPixels, amounts);
                }
                javaNanos[sample] = (System.nanoTime() - start) / 8;

                System.arraycopy(original, 0, nativePixels, 0, PIXELS);
                start = System.nanoTime();
                for (int iteration = 0; iteration < 8; iteration++) {
                    assertTrue(NativeSlices.shadeColours(nativePixels, amounts));
                }
                nativeNanos[sample] = (System.nanoTime() - start) / 8;
            }
            Arrays.sort(javaNanos);
            Arrays.sort(nativeNanos);
            final BenchmarkMemorySupport.Snapshot javaMemory = BenchmarkMemorySupport.measure(
                    () -> shadeJavaInPlace(javaPixels, amounts));
            final BenchmarkMemorySupport.Snapshot nativeMemory = BenchmarkMemorySupport.measure(
                    () -> NativeSlices.shadeColours(nativePixels, amounts));
            System.out.printf("Tile shading Java median %.3f ms, Rust/JNI median %.3f ms, ratio %.3fx "
                            + "java_memory=[%s] native_memory=[%s]%n",
                    javaNanos[4] / 1_000_000.0, nativeNanos[4] / 1_000_000.0,
                    (double) javaNanos[4] / nativeNanos[4], javaMemory, nativeMemory);
        } finally {
            restoreFlag(previousFlag);
        }
    }

    @Test
    public void benchmarkFullTileRenderingWhenRequested() throws Exception {
        assumeTrue(Boolean.getBoolean("welt.render.tile.benchmark"));
        assumeTrue("welt_slices is only built by the native Maven profile", NativeLoader.areSlicesAvailable());
        final String previousFlag = System.getProperty(Native.RENDER_KEY);
        try {
            final Dimension dimension = TestData.createDimension(
                    new Rectangle(0, 0, Constants.TILE_SIZE, Constants.TILE_SIZE), 64);
            final Tile tile = dimension.getTile(0, 0);
            tile.inhibitEvents();
            try {
                for (int x = 0; x < Constants.TILE_SIZE; x++) {
                    for (int y = 0; y < Constants.TILE_SIZE; y++) {
                        tile.setHeight(x, y, 32 + ((x * 7 + y * 11) % 96));
                        tile.setWaterLevel(x, y, 62 + ((x + y) % 24));
                    }
                }
            } finally {
                tile.releaseEvents();
            }
            final TileRenderer javaRenderer = new TileRenderer(dimension, ColourScheme.DEFAULT, null, 0, true, null);
            final TileRenderer nativeRenderer = new TileRenderer(dimension, ColourScheme.DEFAULT, null, 0, true, null);
            final BufferedImage javaImage = new BufferedImage(
                    Constants.TILE_SIZE, Constants.TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
            final BufferedImage nativeImage = new BufferedImage(
                    Constants.TILE_SIZE, Constants.TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
            for (int i = 0; i < 4; i++) {
                Native.setRenderEnabled(false);
                javaRenderer.renderTile(tile, javaImage, 0, 0);
                Native.setRenderEnabled(true);
                nativeRenderer.renderTile(tile, nativeImage, 0, 0);
            }
            final long[] javaNanos = new long[9];
            final long[] nativeNanos = new long[9];
            for (int sample = 0; sample < javaNanos.length; sample++) {
                Native.setRenderEnabled(false);
                long start = System.nanoTime();
                for (int iteration = 0; iteration < 3; iteration++) {
                    javaRenderer.renderTile(tile, javaImage, 0, 0);
                }
                javaNanos[sample] = (System.nanoTime() - start) / 3;

                Native.setRenderEnabled(true);
                start = System.nanoTime();
                for (int iteration = 0; iteration < 3; iteration++) {
                    nativeRenderer.renderTile(tile, nativeImage, 0, 0);
                }
                nativeNanos[sample] = (System.nanoTime() - start) / 3;
            }
            Arrays.sort(javaNanos);
            Arrays.sort(nativeNanos);
            assertArrayEquals(((DataBufferInt) javaImage.getRaster().getDataBuffer()).getData(),
                    ((DataBufferInt) nativeImage.getRaster().getDataBuffer()).getData());
            final BenchmarkMemorySupport.Snapshot javaMemory = BenchmarkMemorySupport.measure(() -> {
                Native.setRenderEnabled(false);
                javaRenderer.renderTile(tile, javaImage, 0, 0);
            });
            final BenchmarkMemorySupport.Snapshot nativeMemory = BenchmarkMemorySupport.measure(() -> {
                Native.setRenderEnabled(true);
                nativeRenderer.renderTile(tile, nativeImage, 0, 0);
            });
            System.out.printf("Full TileRenderer Java median %.3f ms, Rust/JNI median %.3f ms, ratio %.3fx "
                            + "java_memory=[%s] native_memory=[%s]%n",
                    javaNanos[4] / 1_000_000.0, nativeNanos[4] / 1_000_000.0,
                    (double) javaNanos[4] / nativeNanos[4], javaMemory, nativeMemory);
        } finally {
            restoreFlag(previousFlag);
        }
    }

    private static void shadeJavaInPlace(int[] pixels, long[] amounts) {
        for (int i = 0; i < pixels.length; i++) {
            final int terrain = (int) amounts[i];
            final int fluid = (int) (amounts[i] >>> 32);
            final int alpha = pixels[i] & 0xff00_0000;
            if (terrain != 256 || fluid != 256) {
                pixels[i] = ColourUtils.multiply(ColourUtils.multiply(pixels[i], terrain), fluid) | alpha;
            }
        }
    }

    private static int[] renderTile(Dimension dimension, Tile tile, boolean nativeEnabled) {
        Native.setRenderEnabled(nativeEnabled);
        final BufferedImage image = new BufferedImage(
                Constants.TILE_SIZE, Constants.TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        new TileRenderer(dimension, ColourScheme.DEFAULT, null, 0, true, null)
                .renderTile(tile, image, 0, 0);
        return ((DataBufferInt) image.getRaster().getDataBuffer()).getData().clone();
    }

    private static void assertRenderHeightSnapshotMatches(Tile tile) {
        final int minHeight = tile.getMinHeight();
        final int heightRange = tile.getMaxHeight() - minHeight;
        tile.inhibitEvents();
        try {
            for (int x = 0; x < Constants.TILE_SIZE; x++) {
                for (int y = 0; y < Constants.TILE_SIZE; y++) {
                    tile.setHeight(x, y, minHeight + ((x * 13 + y * 7) % heightRange) + 0.25f);
                    tile.setWaterLevel(x, y, minHeight + ((x * 5 + y * 11) % heightRange));
                }
            }
        } finally {
            tile.releaseEvents();
        }
        final float[] heights = new float[PIXELS];
        final int[] intHeights = new int[PIXELS];
        final int[] waterLevels = new int[PIXELS];
        tile.copyRenderHeightDataTo(heights, intHeights, waterLevels);
        for (int x = 0; x < Constants.TILE_SIZE; x++) {
            for (int y = 0; y < Constants.TILE_SIZE; y++) {
                final int index = x | (y << Constants.TILE_SIZE_BITS);
                final float height = tile.getHeight(x, y);
                assertEquals(Float.floatToRawIntBits(height), Float.floatToRawIntBits(heights[index]));
                assertEquals(tile.getIntHeight(x, y), intHeights[index]);
                assertEquals(tile.getWaterLevel(x, y), waterLevels[index]);
            }
        }
    }

    private static void restoreFlag(String previousFlag) {
        if (previousFlag == null) {
            System.clearProperty(Native.RENDER_KEY);
        } else {
            System.setProperty(Native.RENDER_KEY, previousFlag);
        }
    }
}
